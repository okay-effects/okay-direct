package okay.direct.std;

import java.lang.ref.Cleaner;
import java.util.ArrayList;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.SynchronousQueue;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import okay.direct.Answer;
import okay.direct.Effect;
import okay.direct.Effect.Op;
import okay.direct.Effects;
import okay.direct.TailHandler;

/**
 * A GENERATOR: a body in direct style that emits values. Its loops do the enumerating, the effect only gives the
 * values out, so a one-shot continuation is enough: it runs on every implementation, an ordinary JDK's too. (The
 * multi-shot alternative, a monad of streams reflected, is {@link Reflection#stream}, on a {@code MultiEffects}.)
 * <pre>{@code
 * Stream<List<Integer>> triples = Emits.stream(fx, out -> {
 *     for (int a = 1; a <= 10; a++)
 *         for (int b = a; b <= 10; b++)
 *             for (int c = b; c <= 15; c++)
 *                 if (a * a + b * b == c * c) Emits.emit(out, List.of(a, b, c));
 * });
 * }</pre>
 */
public sealed interface Emits<A> {
    record Emit<A>(A value) implements Emits<A>, Op<Emits<A>, Void> {}

    /** The value given out; the body goes on when it has been taken. */
    static <A> void emit(Effect<Emits<A>> out, A value) {
        out.perform(new Emit<>(value));
    }

    /** A generator's body: it emits, and returns nothing. */
    @FunctionalInterface
    interface Generator<A, E extends Exception> {
        void run(Effect<Emits<A>> out) throws E;
    }

    /** Every value the body emits, in order: the body run to its end, here. */
    static <A, E extends Exception> List<A> collect(Effects effects, Generator<A, E> body) throws E {
        return effects.handle(new TailHandler<Emits<A>, Void, List<A>>() {
            private final List<A> values = new ArrayList<>();

            @Override
            public List<A> value(Void unit) {
                return List.copyOf(values);
            }

            @Override
            public void operation(Emits<A> op, Answer<Emits<A>> k) {
                switch (op) {
                    case Emit<A> emit -> {
                        values.add(emit.value());
                        k.answer(emit, null);
                    }
                }
            }
        }, (Effect<Emits<A>> out) -> {
            body.run(out);
            return null;
        });
    }

    /**
     * The values the body emits as a LAZY stream: the body run as the stream is pulled, each emit waiting until its
     * value is taken; a stream that stops early ({@code findFirst}, {@code limit}) stops it. The body runs on a virtual
     * thread of its own, so it has the capabilities it is given here, not those of the code making the stream. Closed,
     * the stream drops the body's rest, which unwinds ({@code finally} blocks run); a stream left unclosed stops the
     * body when it is collected. A failure of the body is the stream's, thrown as it is pulled.
     */
    static <A> Stream<A> stream(Effects effects, Generator<A, ? extends Exception> body) {
        var producer = new Producer<A>(effects, body);
        var spliterator = new Spliterators.AbstractSpliterator<A>(Long.MAX_VALUE, Spliterator.ORDERED) {
            @Override
            public boolean tryAdvance(Consumer<? super A> action) {
                return producer.next(action);
            }
        };
        Producer.CLEANER.register(spliterator, producer::stop);
        return StreamSupport.stream(spliterator, false).onClose(producer::stop);
    }

    /** The body on its thread, handing its values over one at a time. */
    final class Producer<A> {
        private static final Cleaner CLEANER = Cleaner.create();

        private sealed interface Handed {}
        private record Value(Object value) implements Handed {}
        private record End() implements Handed {}
        private record Failed(Throwable failure) implements Handed {}

        private final SynchronousQueue<Handed> handoff = new SynchronousQueue<>();
        private final Effects effects;
        private final Generator<A, ? extends Exception> body;
        private Thread thread;
        private boolean ended;

        private Producer(Effects effects, Generator<A, ? extends Exception> body) {
            this.effects = effects;
            this.body = body;
        }

        @SuppressWarnings("unchecked") // what the body emits is an A
        synchronized boolean next(Consumer<? super A> action) {
            if (ended) return false;
            if (thread == null) thread = Thread.ofVirtual().start(this::run);
            Handed handed;
            try {
                handed = handoff.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            switch (handed) {
                case Value v -> {
                    action.accept((A) v.value());
                    return true;
                }
                case End e -> {
                    ended = true;
                    return false;
                }
                case Failed f -> {
                    ended = true;
                    if (f.failure() instanceof RuntimeException r) throw r;
                    if (f.failure() instanceof Error r) throw r;
                    throw new IllegalStateException("the generator failed", f.failure());
                }
            }
        }

        /** The body stopped: interrupted where it waits for its value to be taken, so its rest dropped. */
        synchronized void stop() {
            ended = true;
            if (thread != null) thread.interrupt();
        }

        private void run() {
            Handed last;
            try {
                effects.handle(new TailHandler<Emits<A>, Void, Void>() {
                    @Override
                    public Void value(Void unit) {
                        return null;
                    }

                    @Override
                    public void operation(Emits<A> op, Answer<Emits<A>> k) {
                        switch (op) {
                            case Emit<A> emit -> {
                                try {
                                    handoff.put(new Value(emit.value()));
                                } catch (InterruptedException e) {
                                    // the stream stopped: no answer, so the body's rest is dropped and unwinds
                                    throw new Stopped();
                                }
                                k.answer(emit, null);
                            }
                        }
                    }
                }, (Effect<Emits<A>> out) -> {
                    body.run(out);
                    return null;
                });
                last = new End();
            } catch (Stopped stopped) {
                return;
            } catch (Throwable t) {
                last = new Failed(t);
            }
            try {
                handoff.put(last);
            } catch (InterruptedException e) {
                // the stream stopped meanwhile: nobody to hand the end to
            }
        }

        /** The stream stopped while the body waited. */
        private static final class Stopped extends RuntimeException {
            Stopped() {
                super(null, null, false, false);
            }
        }
    }
}
