package okay.direct.std;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import okay.direct.Body;
import okay.direct.DetachedEffects;
import okay.direct.Effect;
import okay.direct.Effect.Op;

/**
 * ASYNCHRONY IN DIRECT STYLE: {@code await(async, future)} is a method call that gives the future's value — and no
 * thread waits for it. The handler leaves the rest DETACHED and resumes it from the future's callback, on whatever
 * thread completes it; the body goes on there. So a run is a {@code CompletableFuture} of the body's value.
 * <pre>{@code
 * CompletableFuture<Integer> total = Async.run(fx, async ->
 *     Async.await(async, service.quote("tea")) + Async.await(async, service.quote("cake")));
 * }</pre>
 * A failed future is thrown at its await (unchecked as it is, checked in a {@code CompletionException}). Needs a
 * {@link DetachedEffects}: Loom's or threads'.
 *
 * <p>STRUCTURED CONCURRENCY: {@link #fork} starts a body of its own, a {@link Task}, beside this one; {@link #both},
 * {@link #all}, {@link #race} and {@link #timeout} fork and join, and cancel what is not needed any more. A task
 * cancelled is dropped at the await it waits in, or at its next one: it unwinds, its {@code finally} blocks run, and
 * its own tasks are cancelled with it. A run ends only when the tasks forked in it have ended: none outlives it.
 *
 * <p>TIME is the handler's: {@link #sleep}, {@link #now} and the timeouts ask the run's {@link Clock}. The system's
 * by default; a {@link VirtualClock} in a test, so that hours of retries and timeouts take no time, in an order
 * that is always the same.
 */
public sealed interface Async {
    record Await<X>(CompletionStage<X> stage) implements Async, Op<Async, X> {}

    /** A body of its own, started beside this one: answered by its task. */
    record Fork<X>(Body<Async, X, ? extends Exception> body) implements Async, Op<Async, Task<X>> {}

    /** A future completed after {@code delay}, by the run's clock. */
    record Timer(Duration delay) implements Async, Op<Async, CompletableFuture<Void>> {}

    /** The time, by the run's clock. */
    record Now() implements Async, Op<Async, Instant> {}

    /** The time of a run: what its sleeps and timeouts wait by. */
    interface Clock {
        Instant now();

        /** A future completed after {@code delay}. */
        CompletableFuture<Void> after(Duration delay);

        /** The system's time; a timer completes on the common pool. */
        Clock SYSTEM = new Clock() {
            @Override
            public Instant now() {
                return Instant.now();
            }

            @Override
            public CompletableFuture<Void> after(Duration delay) {
                return CompletableFuture.runAsync(() -> {},
                    CompletableFuture.delayedExecutor(Math.max(0, delay.toNanos()), TimeUnit.NANOSECONDS));
            }

            @Override
            public String toString() {
                return "Clock.SYSTEM";
            }
        };
    }

    /** A body forked: its value when it ends — after the tasks it forked have ended — and its cancellation. */
    interface Task<X> {
        CompletableFuture<X> future();

        /** Drops the body at the await it waits in, or at its next one; its tasks cancelled too. */
        void cancel();
    }

    /** The stage's value, once it is there; the body suspended meanwhile, no thread held. */
    static <X> X await(Effect<Async> async, CompletionStage<X> stage) {
        return async.perform(new Await<>(stage));
    }

    /**
     * {@code body} started beside this one, at once, on this thread until its first await. It has its own capability:
     * the one it is given, not this body's.
     */
    static <X> Task<X> fork(Effect<Async> async, Body<Async, X, ? extends Exception> body) {
        return async.perform(new Fork<>(body));
    }

    /** The task's value, once it has ended; its failure thrown here. */
    static <X> X join(Effect<Async> async, Task<X> task) {
        return await(async, task.future());
    }

    /** The body suspended for {@code delay}, by the run's clock. */
    static void sleep(Effect<Async> async, Duration delay) {
        await(async, timer(async, delay));
    }

    /** A future completed after {@code delay}, by the run's clock: to await, or to race. */
    static CompletableFuture<Void> timer(Effect<Async> async, Duration delay) {
        return async.perform(new Timer(delay));
    }

    /** The time, by the run's clock. */
    static Instant now(Effect<Async> async) {
        return async.perform(new Now());
    }

    /** Both bodies at once, their values combined; the first failure thrown, the other body cancelled. */
    static <A, B, R> R both(Effect<Async> async, Body<Async, A, ? extends Exception> a,
            Body<Async, B, ? extends Exception> b, BiFunction<? super A, ? super B, ? extends R> combine) {
        var ta = fork(async, a);
        var tb = fork(async, b);
        try {
            await(async, AsyncRun.allOrFirstFailure(List.of(ta.future(), tb.future())));
            return combine.apply(ta.future().join(), tb.future().join());
        } catch (Throwable t) {
            ta.cancel();
            tb.cancel();
            throw t;
        }
    }

    /** All the bodies at once, their values in order; the first failure thrown, the others cancelled. */
    static <X> List<X> all(Effect<Async> async, List<? extends Body<Async, X, ? extends Exception>> bodies) {
        var tasks = new ArrayList<Task<X>>();
        try {
            for (var body : bodies) tasks.add(fork(async, body));
            await(async, AsyncRun.allOrFirstFailure(tasks.stream().map(Task::future).toList()));
            return tasks.stream().map(t -> t.future().join()).toList();
        } catch (Throwable t) {
            tasks.forEach(Task::cancel);
            throw t;
        }
    }

    /**
     * The value of the body that succeeds first, the others cancelled. If all fail, the first failure is thrown, the
     * others suppressed in it.
     */
    static <X> X race(Effect<Async> async, List<? extends Body<Async, X, ? extends Exception>> bodies) {
        var tasks = new ArrayList<Task<X>>();
        try {
            for (var body : bodies) tasks.add(fork(async, body));
            var first = new CompletableFuture<X>();
            var failures = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
            var left = new AtomicInteger(tasks.size());
            for (var task : tasks) {
                // no lock held here: completing resumes the body, which must not run under a monitor of ours
                task.future().whenComplete((value, failure) -> {
                    if (failure == null) {
                        first.complete(value);
                        return;
                    }
                    failures.add(AsyncRun.unwrapped(failure));
                    if (left.decrementAndGet() == 0) {
                        var thrown = failures.peek();
                        failures.stream().skip(1).forEach(thrown::addSuppressed);
                        first.completeExceptionally(thrown);
                    }
                });
            }
            if (tasks.isEmpty()) first.completeExceptionally(new IllegalArgumentException("a race of no bodies"));
            return await(async, first);
        } finally {
            tasks.forEach(Task::cancel);
        }
    }

    /** The body's value if it ends within {@code limit}, by the run's clock; else empty, the body cancelled. */
    static <X> Optional<X> timeout(Effect<Async> async, Duration limit, Body<Async, X, ? extends Exception> body) {
        var timer = timer(async, limit);
        var task = fork(async, body);
        try {
            var first = new CompletableFuture<Optional<X>>();
            task.future().whenComplete((value, failure) -> {
                if (failure == null) first.complete(Optional.ofNullable(value));
                else first.completeExceptionally(AsyncRun.unwrapped(failure));
            });
            timer.whenComplete((done, failure) -> first.complete(Optional.empty()));
            return await(async, first);
        } finally {
            task.cancel();
        }
    }

    /** The body run, its awaits resumed by their stages' callbacks: the body's value, when it and its tasks end. */
    static <A, E extends Exception> CompletableFuture<A> run(DetachedEffects effects, Body<Async, A, E> body) {
        return run(effects, Clock.SYSTEM, body);
    }

    /** The same, its time {@code clock}'s. */
    static <A, E extends Exception> CompletableFuture<A> run(DetachedEffects effects, Clock clock,
            Body<Async, A, E> body) {
        return AsyncRun.start(effects, clock, body).future();
    }

    /** Thrown where a task's failure is unwrapped: a checked one, in a {@code CompletionException}. */
    static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException c && c.getCause() != null ? c.getCause() : failure;
    }
}
