package okay.direct.std;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import okay.direct.Body;
import okay.direct.DetachedEffects;
import okay.direct.DetachedHandler;
import okay.direct.Later;

/** A body of {@link Async}: its handler, its tasks, its cancellation. */
final class AsyncRun<A> implements Async.Task<A> {
    /** A stage awaited that can be given up before it completes: a channel's receive, which then keeps its value. */
    interface Revocable {
        /** True if the stage will not complete now; false if it has completed, or is completing. */
        boolean revoke();
    }

    /** The await a body waits in. */
    private record Waiting(CompletionStage<?> stage, Later<Async> k) {}

    private final DetachedEffects effects;
    private final Async.Clock clock;
    private final Set<AsyncRun<?>> tasks = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Waiting> waiting = new AtomicReference<>();
    private final CompletableFuture<A> future = new CompletableFuture<>();
    private volatile boolean cancelled;

    private AsyncRun(DetachedEffects effects, Async.Clock clock) {
        this.effects = effects;
        this.clock = clock;
    }

    static <A> AsyncRun<A> start(DetachedEffects effects, Async.Clock clock, Body<Async, A, ? extends Exception> body) {
        var run = new AsyncRun<A>(effects, clock);
        run.run(body);
        return run;
    }

    @Override
    public CompletableFuture<A> future() {
        return future;
    }

    @Override
    public void cancel() {
        cancelled = true;
        tasks.forEach(AsyncRun::cancel);
        var w = waiting.get();
        if (w == null) return; // running: dropped at its next await
        if (w.stage() instanceof Revocable r && !r.revoke()) return; // its value is coming: dropped at the next await
        if (waiting.compareAndSet(w, null)) w.k().drop();
    }

    private void run(Body<Async, A, ? extends Exception> body) {
        effects.start(new DetachedHandler<Async, A, A>() {
            @Override
            public A value(A value) {
                return value;
            }

            @Override
            public void operation(Async op, Later<Async> k) {
                if (cancelled) {
                    k.drop();
                    return;
                }
                switch (op) {
                    case Async.Await<?> await -> awaitThen(await, k);
                    case Async.Fork<?> fork -> fork(fork, k);
                    case Async.Timer timer -> k.answer(timer, clock.after(timer.delay()));
                    case Async.Now now -> k.answer(now, clock.now());
                }
            }
        }, body).whenComplete((value, failure) -> {
            if (failure != null) tasks.forEach(AsyncRun::cancel);
            // a run ends when its tasks have: none outlives it
            CompletableFuture.allOf(tasks.stream().map(t -> t.future.handle((x, e) -> null))
                    .toArray(CompletableFuture<?>[]::new))
                .whenComplete((done, ignored) -> {
                    if (failure != null) future.completeExceptionally(unwrapped(failure));
                    else future.complete(value);
                });
        });
    }

    private <X> void awaitThen(Async.Await<X> await, Later<Async> k) {
        var w = new Waiting(await.stage(), k);
        waiting.set(w);
        if (cancelled && waiting.compareAndSet(w, null)) {
            k.drop();
            return;
        }
        await.stage().whenComplete((value, failure) -> {
            if (!waiting.compareAndSet(w, null)) return; // cancelled meanwhile
            if (failure == null) k.answer(await, value);
            else k.raise(unwrapped(failure));
        });
    }

    private <X> void fork(Async.Fork<X> fork, Later<Async> k) {
        var task = new AsyncRun<X>(effects, clock);
        tasks.add(task);
        task.future.whenComplete((value, failure) -> tasks.remove(task));
        task.run(fork.body());
        k.answer(fork, task);
    }

    static Throwable unwrapped(Throwable failure) {
        return Async.unwrap(failure);
    }

    /** Completes when all have succeeded, or at the first failure. */
    static CompletableFuture<Void> allOrFirstFailure(List<? extends CompletableFuture<?>> futures) {
        var all = new CompletableFuture<Void>();
        for (var f : futures) {
            f.whenComplete((value, failure) -> {
                if (failure != null) all.completeExceptionally(failure instanceof CompletionException
                    ? failure : new CompletionException(failure));
            });
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture<?>[]::new))
            .whenComplete((done, failure) -> all.complete(null));
        return all;
    }
}
