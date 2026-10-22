package okay.direct.threads;

import java.util.concurrent.SynchronousQueue;
import okay.direct.Abandoned;
import okay.direct.Answer;
import okay.direct.Body;
import okay.direct.Effect;
import okay.direct.Effects;
import okay.direct.Handler;
import okay.direct.Resume;
import okay.direct.TailHandler;

/**
 * ON THREADS: the body runs on a virtual thread of its own, and {@code perform} hands the operation to the
 * handler's thread and waits for the answer. One-shot. Portable: any JDK 21+, no flags.
 */
public final class ThreadEffects implements Effects {
    @Override
    public <F, A, R, E extends Exception> R handle(Handler<F, A, R, E> handler, Body<F, A, E> body) throws E {
        return new Run<F, A, E>(body).handle(handler);
    }

    @Override
    public <F, A, R, E extends Exception> R handle(TailHandler<F, A, R> handler, Body<F, A, E> body) throws E {
        return new Run<F, A, E>(body).handle(handler);
    }

    @Override
    public String toString() {
        return "ThreadEffects";
    }

    /** What the body hands its handler: an operation and where to answer it, or that it has ended. */
    private sealed interface Event {}
    private record Performed(Effect.Op<?, ?> op, SynchronousQueue<Reply> reply) implements Event {}
    private record Ended() implements Event {}
    private record Reply(Object answer, boolean abandoned) {}

    /** The body whose thread this is: a capability is used only in its body, or in a body nested in it. */
    private static final ThreadLocal<Run<?, ?, ?>> CURRENT = new ThreadLocal<>();

    private static final class Run<F, A, E extends Exception> implements Effect<F> {
        private final SynchronousQueue<Event> handler = new SynchronousQueue<>();
        /** The body this handler runs in, if any. */
        private final Run<?, ?, ?> outer = CURRENT.get();
        private A value;
        private Throwable failure;
        private volatile boolean abandoned;

        Run(Body<F, A, E> body) {
            Thread.ofVirtual().start(() -> {
                CURRENT.set(this);
                try {
                    value = body.run(this);
                } catch (Abandoned unwound) {
                    // this body's rest dropped by its handler has unwound; another's goes on unwinding
                    if (!unwound.of(this)) failure = unwound;
                } catch (Throwable t) {
                    failure = t;
                }
                put(handler, new Ended());
            });
        }

        @Override
        @SuppressWarnings("unchecked") // the answer resume() was given with this very operation as its witness: an X
        public <X> X perform(Op<F, X> op) {
            if (abandoned) throw new Abandoned(this);
            if (!within()) throw new IllegalStateException("performed outside its body: " + op);
            var reply = new SynchronousQueue<Reply>();
            put(handler, new Performed(op, reply));
            var r = take(reply);
            if (r.abandoned()) throw new Abandoned(this);
            return (X) r.answer();
        }

        /** This thread runs this body, or a body nested in it. */
        private boolean within() {
            for (Run<?, ?, ?> run = CURRENT.get(); run != null; run = run.outer) if (run == this) return true;
            return false;
        }

        /** Waits for the body's next operation; null when it has returned. */
        private Performed next() throws E {
            return switch (take(handler)) {
                case Performed p -> p;
                case Ended e -> {
                    if (failure != null) throw rethrown();
                    yield null;
                }
            };
        }

        @SuppressWarnings("unchecked") // what the body throws is an E, or unchecked
        private E rethrown() {
            if (failure instanceof RuntimeException e) throw e;
            if (failure instanceof Error e) throw e;
            return (E) failure;
        }

        @SuppressWarnings("unchecked") // an Op<F, X> is one of F's operations: an F
        private F operation(Performed p) {
            return (F) p.op();
        }

        <R> R handle(Handler<F, A, R, E> handler) throws E {
            var p = next();
            if (p == null) return handler.value(value);
            var k = new Once<R>(p, handler);
            try {
                return handler.operation(operation(p), k);
            } finally {
                if (k.fresh) {
                    k.fresh = false;
                    abandon(p);
                }
            }
        }

        <R> R handle(TailHandler<F, A, R> handler) throws E {
            for (var p = next(); p != null; p = next()) {
                var k = new Once<R>(p, null);
                var answered = false;
                try {
                    handler.operation(operation(p), k);
                    answered = !k.fresh;
                } finally {
                    if (k.fresh) {
                        k.fresh = false;
                        abandon(p);
                    }
                }
                if (!answered) throw new IllegalStateException("a tail handler did not answer " + p.op());
            }
            return handler.value(value);
        }

        /** The body unwinds: Abandoned thrown at the perform it waits in; its end awaited. */
        private void abandon(Performed p) {
            abandoned = true;
            put(p.reply(), new Reply(null, true));
            while (!(take(handler) instanceof Ended)) {
                // a perform of this handler while unwinding throws before it is handed over: none comes
            }
        }

        private final class Once<R> implements Resume<F, R, E>, Answer<F> {
            private final Performed performed;
            private final Handler<F, A, R, E> handler;
            private boolean fresh = true;

            Once(Performed performed, Handler<F, A, R, E> handler) {
                this.performed = performed;
                this.handler = handler;
            }

            @Override
            public <X> R resume(Op<F, X> op, X value) throws E {
                answer(op, value);
                return handle(handler);
            }

            @Override
            public <X> void answer(Op<F, X> op, X value) {
                if (op != performed.op()) throw new IllegalArgumentException("resumed with " + op + ", not with " + performed.op());
                if (!fresh) throw new IllegalStateException("one-shot: " + op + " resumed twice");
                fresh = false;
                put(performed.reply(), new Reply(value, false));
            }
        }
    }

    private static <T> void put(SynchronousQueue<T> queue, T t) {
        try {
            queue.put(t);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static <T> T take(SynchronousQueue<T> queue) {
        try {
            return queue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
