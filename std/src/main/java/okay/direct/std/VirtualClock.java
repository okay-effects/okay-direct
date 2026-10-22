package okay.direct.std;

import java.time.Duration;
import java.time.Instant;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;

/**
 * VIRTUAL TIME for {@link Async}: a clock that moves only when it is told to. A test runs a body on it, then moves
 * the time; each timer due by then completes, in the order of its time (in the order made, at the same time), on the
 * thread that moves the clock — so the bodies waiting for them go on there, and the test's order is always the same.
 * <pre>{@code
 * var clock = new VirtualClock();
 * var result = Async.run(fx, clock, async -> retry(async, () -> service.call()));   // backs off 1s, 2s, 4s
 * clock.advance(Duration.ofSeconds(7));                                              // no time taken
 * assertEquals("ok", result.getNow(null));
 * }</pre>
 */
public final class VirtualClock implements Async.Clock {
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
    public VirtualClock() {
        this(Instant.EPOCH);
    }

    public VirtualClock(Instant start) {
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
        return "VirtualClock(" + now + ", " + due.size() + " waiting)";
    }
}
