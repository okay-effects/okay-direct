package okay.direct.std;

import java.time.Duration;
import java.time.Instant;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import okay.direct.Answer;
import okay.direct.Body;
import okay.direct.Effect;
import okay.direct.Effects;
import okay.direct.Fx;
import okay.direct.InPlace;
import okay.direct.Program;
import okay.effect.Eff;
import okay.effect.Op;

/**
 * CLOCK: the time — the system's, or one a test sets and moves. Its handler answers from a {@link Time}: the
 * system's ({@link #system}), or any other ({@link #at}), a {@link Virtual} one in a test. The same {@code Time}
 * is what an {@link Async} run's sleeps and timeouts wait by.
 * <pre>{@code
 * var clock = new Clock.Virtual();
 * Clock.at(fx, clock, time -> Clock.now(time));                                      // the epoch
 * var result = Async.run(fx, clock, async -> retry(async, () -> service.call()));   // backs off 1s, 2s, 4s
 * clock.advance(Duration.ofSeconds(7));                                              // no time taken
 * assertEquals("ok", result.getNow(null));
 * }</pre>
 */
public sealed interface Clock {
    record Now() implements Clock, Op<Clock, Instant, Now> {}

    static Instant now(Effect<Clock> clock) {
        return clock.perform(new Now());
    }

    /** The body, its time the system's. */
    static <A, E extends Exception> A system(Effects effects, Body<Clock, A, E> body) throws E {
        return at(effects, Time.SYSTEM, body);
    }

    /** The body, its time {@code time}'s. */
    static <A, E extends Exception> A at(Effects effects, Time time, Body<Clock, A, E> body) throws E {
        return effects.handle(new InPlace<Clock, A, A>() {
            @Override
            public A ret(A value) {
                return value;
            }

            @Override
            public void answer(Clock op, Answer<Clock> k) {
                switch (op) {
                    case Now now -> k.answer(now, time.now());
                }
            }
        }, body);
    }

    /** In a row: the program's first place answered by the system's time; a program of the rest. */
    static <R, A> Program<A, R> system(Program<A, Eff<Clock, R>> program) {
        return at(Time.SYSTEM, program);
    }

    /** In a row: the program's first place answered by {@code time}; a program of the rest. */
    static <R, A> Program<A, R> at(Time time, Program<A, Eff<Clock, R>> program) {
        return outer -> at(outer.effects(), time, (Effect<Clock> clock) -> program.run(Fx.with(clock, outer)));
    }

    /** A TIME: what a clock's handler answers, and what an {@link Async} run's sleeps and timeouts wait by. */
    interface Time {
        Instant now();

        /** A future completed after {@code delay}. */
        CompletableFuture<Void> after(Duration delay);

        /** The system's time; a timer completes on the common pool. */
        Time SYSTEM = new Time() {
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
                return "Clock.Time.SYSTEM";
            }
        };
    }

    /**
     * VIRTUAL TIME: a time that moves only when it is told to. A test runs a body on it, then moves the time; each
     * timer due by then completes, in the order of its time (in the order made, at the same time), on the thread that
     * moves the clock — so the bodies waiting for them go on there, and the test's order is always the same.
     */
    final class Virtual implements Time {
        private record Due(Instant at, long order, CompletableFuture<Void> timer) implements Comparable<Due> {
            @Override
            public int compareTo(Due other) {
                int byTime = at.compareTo(other.at);
                return byTime != 0 ? byTime : Long.compare(order, other.order);
            }
        }

        private final PriorityQueue<Due> due = new PriorityQueue<>();
        private Instant now;
        private long made;

        /** Starting at the epoch. */
        public Virtual() {
            this(Instant.EPOCH);
        }

        public Virtual(Instant start) {
            this.now = start;
        }

        @Override
        public synchronized Instant now() {
            return now;
        }

        @Override
        public CompletableFuture<Void> after(Duration delay) {
            var timer = new CompletableFuture<Void>();
            synchronized (this) {
                due.add(new Due(now.plus(delay.isNegative() ? Duration.ZERO : delay), made++, timer));
            }
            return timer;
        }

        /** The time moved on by {@code by}: each timer due by then completed, in order, the time its own as it does. */
        public void advance(Duration by) {
            Instant until;
            synchronized (this) {
                until = now.plus(by);
            }
            while (true) {
                Due next;
                synchronized (this) {
                    next = due.peek();
                    if (next == null || next.at().isAfter(until)) {
                        now = until;
                        return;
                    }
                    due.poll();
                    now = next.at();
                }
                next.timer().complete(null);
            }
        }

        /** The time moved on until no timer is left; the time then that of the last. Never ends on a body that keeps
         * making timers. */
        public void run() {
            while (true) {
                Due next;
                synchronized (this) {
                    next = due.poll();
                    if (next == null) return;
                    now = next.at();
                }
                next.timer().complete(null);
            }
        }

        /** How many timers wait. */
        public synchronized int waiting() {
            return due.size();
        }

        @Override
        public synchronized String toString() {
            return "Clock.Virtual(" + now + ", " + due.size() + " waiting)";
        }
    }
}
