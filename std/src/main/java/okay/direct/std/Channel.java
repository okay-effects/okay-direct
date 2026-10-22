package okay.direct.std;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import okay.direct.Effect;

/**
 * A CHANNEL between {@link Async} bodies, as Go's: {@code send} waits while the channel is full — an unbuffered one
 * until a receiver takes the value — and {@code receive} while it is empty. Waiting is an await: no thread is held.
 * A closed channel takes no more values; its values are still received, and then {@link Closed} is thrown.
 * <pre>{@code
 * var numbers = new Channel<Integer>();
 * Async.run(fx, async -> Async.both(async,
 *     a -> { for (int i = 1; i <= 3; i++) numbers.send(a, i); numbers.close(); return null; },
 *     b -> { int sum = 0; for (int n : numbers.iterable(b)) sum += n; return sum; },
 *     (none, sum) -> sum));                                                  // 6
 * }</pre>
 * {@link #select} waits for the first of several channels, or a timeout. A task cancelled while it waits takes
 * nothing and gives nothing: a value goes to the next receiver, a send waiting is taken back.
 */
public final class Channel<T> {
    /** Thrown by a send to a closed channel, and by a receive from a closed one that has no values left. */
    public static final class Closed extends RuntimeException {
        Closed() {
            super("the channel is closed", null, false, false);
        }
    }

    /** A value received in a select: the case it was received for, and the value. */
    private record Picked(int index, Object value) {}

    /** A receiver waiting: completed by the first of its select's cases to be chosen — its claim. */
    private static final class Receiver extends CompletableFuture<Picked> implements AsyncRun.Revocable {
        private final AtomicBoolean claim;
        private final int index;

        Receiver(AtomicBoolean claim, int index) {
            this.claim = claim;
            this.index = index;
        }

        boolean claim() {
            return claim.compareAndSet(false, true);
        }

        @Override
        public boolean revoke() {
            return claim();
        }
    }

    /** A sender waiting with its value: given up while still waiting, it takes the value back. */
    private final class Sender extends CompletableFuture<Void> implements AsyncRun.Revocable {
        private final T value;

        Sender(T value) {
            this.value = value;
        }

        T value() {
            return value;
        }

        CompletableFuture<Void> sent() {
            return this;
        }

        @Override
        public boolean revoke() {
            synchronized (Channel.this) {
                return senders.remove(this);
            }
        }
    }

    private final int capacity;
    private final ArrayDeque<T> buffer = new ArrayDeque<>();
    private final ArrayDeque<Receiver> receivers = new ArrayDeque<>();
    private final ArrayDeque<Sender> senders = new ArrayDeque<>();
    private boolean closed;

    /** Unbuffered: a send waits for its receiver. */
    public Channel() {
        this(0);
    }

    /** Buffered: up to {@code capacity} values wait in it for their receivers. */
    public Channel(int capacity) {
        if (capacity < 0) throw new IllegalArgumentException("capacity " + capacity);
        this.capacity = capacity;
    }

    /** Sends {@code value}, not null: waits while the channel is full. */
    public void send(Effect<Async> async, T value) {
        Async.await(async, sending(java.util.Objects.requireNonNull(value, "a channel's value")));
    }

    /** Receives a value: waits while the channel is empty; throws {@link Closed} once it is closed and empty. */
    public T receive(Effect<Async> async) {
        @SuppressWarnings("unchecked") // what a receiver of this channel is given is a T
        T value = (T) Async.await(async, receiving(new Receiver(new AtomicBoolean(), 0))).value();
        return value;
    }

    /** The values received until the channel is closed and empty, for a {@code for} loop in the body. */
    public Iterable<T> iterable(Effect<Async> async) {
        return () -> new java.util.Iterator<T>() {
            private T next;
            private boolean ended;

            @Override
            public boolean hasNext() {
                if (ended) return false;
                if (next != null) return true;
                try {
                    next = receive(async);
                    return true;
                } catch (Closed closed) {
                    ended = true;
                    return false;
                }
            }

            @Override
            public T next() {
                if (!hasNext()) throw new java.util.NoSuchElementException();
                var value = next;
                next = null;
                return value;
            }
        };
    }

    /** No more values: waiting senders get {@link Closed}; receivers too, once the values left are taken. */
    public void close() {
        List<Receiver> waitingReceivers;
        List<Sender> waitingSenders;
        synchronized (this) {
            if (closed) return;
            closed = true;
            waitingReceivers = new ArrayList<>(receivers);
            waitingSenders = new ArrayList<>(senders);
            receivers.clear();
            senders.clear();
        }
        for (var r : waitingReceivers) if (r.claim()) r.completeExceptionally(new Closed());
        for (var s : waitingSenders) s.sent().completeExceptionally(new Closed());
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    private CompletableFuture<Void> sending(T value) {
        Receiver taker = null;
        CompletableFuture<Void> sent;
        synchronized (this) {
            if (closed) return CompletableFuture.failedFuture(new Closed());
            while (!receivers.isEmpty()) {
                var r = receivers.poll();
                if (r.claim()) {
                    taker = r;
                    break;
                }
            }
            if (taker != null) sent = CompletableFuture.completedFuture(null);
            else if (buffer.size() < capacity) {
                buffer.add(value);
                sent = CompletableFuture.completedFuture(null);
            } else {
                var sender = new Sender(value);
                senders.add(sender);
                sent = sender;
            }
        }
        if (taker != null) taker.complete(new Picked(taker.index, value)); // outside the lock: it may run the taker
        return sent;
    }

    /** {@code r} completed with a value at once if there is one and its claim is won, else left waiting here. */
    private CompletableFuture<Picked> receiving(Receiver r) {
        Sender unblocked = null;
        Object value = null;
        boolean got = false, fail = false;
        synchronized (this) {
            if (!buffer.isEmpty() || !senders.isEmpty() || closed) {
                if (!r.claim()) return r; // another case of its select has won
                if (!buffer.isEmpty()) {
                    value = buffer.poll();
                    if (!senders.isEmpty()) {
                        unblocked = senders.poll();
                        buffer.add(unblocked.value());
                    }
                    got = true;
                } else if (!senders.isEmpty()) {
                    unblocked = senders.poll();
                    value = unblocked.value();
                    got = true;
                } else fail = true;
            } else {
                receivers.removeIf(w -> w.claim.get()); // the given up and the chosen, gone
                receivers.add(r);
            }
        }
        if (unblocked != null) unblocked.sent().complete(null);
        if (got) r.complete(new Picked(r.index, value));
        else if (fail) r.completeExceptionally(new Closed());
        return r;
    }

    /** A select's cases: the first to be ready chosen, its function run in the body. */
    public static <R> Select<R> select() {
        return new Select<>();
    }

    /** The cases of a select, built and then awaited. */
    public static final class Select<R> {
        private final List<Channel<?>> channels = new ArrayList<>();
        private final List<Function<Object, R>> onReceive = new ArrayList<>();
        private Duration limit;
        private Supplier<R> onTimeout;

        private Select() {}

        /** A value received from {@code channel}, given to {@code then}; a closed, empty one throws {@link Closed}. */
        @SuppressWarnings("unchecked") // the value received from a Channel<T> is a T
        public <T> Select<R> receive(Channel<T> channel, Function<? super T, ? extends R> then) {
            channels.add(channel);
            onReceive.add(value -> then.apply((T) value));
            return this;
        }

        /** Nothing received within {@code limit}, by the run's clock: {@code then}. */
        public Select<R> timeout(Duration limit, Supplier<? extends R> then) {
            this.limit = limit;
            this.onTimeout = then::get;
            return this;
        }

        /** Waits for the first case ready, and runs its function here, in the body. */
        public R await(Effect<Async> async) {
            var claim = new AtomicBoolean();
            var first = new Receiver(claim, -1);
            var cases = new ArrayList<Receiver>();
            for (int i = 0; i < channels.size() && !claim.get(); i++) {
                var r = new Receiver(claim, i);
                cases.add(r);
                r.whenComplete((picked, failure) -> {
                    if (failure != null) first.completeExceptionally(failure);
                    else first.complete(picked);
                });
                channels.get(i).receiving(r);
            }
            if (limit != null && !claim.get()) {
                Async.timer(async, limit).whenComplete((done, failure) -> {
                    if (claim.compareAndSet(false, true)) first.complete(new Picked(-1, null));
                });
            }
            var picked = Async.await(async, new Selected(first, claim));
            return picked.index() < 0 ? onTimeout.get() : onReceive.get(picked.index()).apply(picked.value());
        }
    }

    /** What a select awaits: given up, it claims all its cases, so no channel gives it a value. */
    private static final class Selected extends CompletableFuture<Picked> implements AsyncRun.Revocable {
        private final AtomicBoolean claim;

        Selected(CompletableFuture<Picked> first, AtomicBoolean claim) {
            this.claim = claim;
            first.whenComplete((picked, failure) -> {
                if (failure != null) completeExceptionally(failure);
                else complete(picked);
            });
        }

        @Override
        public boolean revoke() {
            return claim.compareAndSet(false, true);
        }
    }
}
