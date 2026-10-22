package okay.direct.loom;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import jdk.internal.vm.Continuation;
import jdk.internal.vm.ContinuationScope;
import okay.direct.Abandoned;
import okay.direct.Answer;
import okay.direct.Body;
import okay.direct.DetachedEffects;
import okay.direct.DetachedHandler;
import okay.direct.Effect;
import okay.direct.Handler;
import okay.direct.Later;
import okay.direct.Resume;
import okay.direct.TailHandler;

/**
 * ON HOTSPOT'S CONTINUATIONS, {@code jdk.internal.vm.Continuation}: the body runs in a continuation of a scope of
 * its own, and {@code perform} yields to that scope, through any continuations of inner handlers. One-shot; its
 * continuations can be DETACHED, run again on whatever thread resumes them (a {@link DetachedEffects}).
 * Scoped values are switched with the stack ({@link Bindings}). Needs, to compile and to run,
 * {@code --add-exports=java.base/jdk.internal.vm=ALL-UNNAMED}, {@code --add-exports=java.base/jdk.internal.access=ALL-UNNAMED},
 * and to run {@code --add-opens=java.base/java.lang=ALL-UNNAMED}.
 */
public final class LoomEffects implements DetachedEffects {
    @Override
    public <F, A, R, E extends Exception> R handle(Handler<F, A, R, E> handler, Body<F, A, E> body) throws E {
        return new Run<F, A, E>(body).handle(handler);
    }

    @Override
    public <F, A, R, E extends Exception> R handle(TailHandler<F, A, R> handler, Body<F, A, E> body) throws E {
        return new Run<F, A, E>(body).handle(handler);
    }

    @Override
    public <F, A, R, E extends Exception> CompletableFuture<R> start(DetachedHandler<F, A, R> handler, Body<F, A, E> body) {
        var result = new CompletableFuture<R>();
        new Run<F, A, E>(body).detached(handler, result);
        return result;
    }

    @Override
    public String toString() {
        return "LoomEffects";
    }

    /** An answer that is a failure, to be thrown at the perform. */
    private record Raised(Throwable failure) {}

    static RuntimeException thrown(Throwable failure) {
        if (failure instanceof RuntimeException e) return e;
        if (failure instanceof Error e) throw e;
        return new CompletionException(failure);
    }

    private static final class Run<F, A, E extends Exception> implements Effect<F> {
        private final ContinuationScope scope = new ContinuationScope("okay.direct");
        private final Continuation continuation;
        private A value;
        private Throwable failure;
        private Op<F, ?> pending;
        private Object answer;
        private boolean abandoned;

        Run(Body<F, A, E> body) {
            continuation = new Continuation(scope, () -> {
                try {
                    value = body.run(this);
                } catch (Abandoned unwound) {
                    // this body's rest dropped by its handler has unwound; another's goes on unwinding
                    if (!unwound.of(this)) failure = unwound;
                } catch (Throwable t) {
                    failure = t;
                }
            });
        }

        /** Outside this body (another thread, a clause, after the handler) the scope is not mounted: yield throws
         * IllegalStateException. */
        @Override
        @SuppressWarnings("unchecked") // the answer resume() was given with this very operation as its witness: an X
        public <X> X perform(Op<F, X> op) {
            if (abandoned) throw new Abandoned(this);
            pending = op;
            var inside = Bindings.current();
            Continuation.yield(scope);
            Bindings.restore(inside);
            if (abandoned) throw new Abandoned(this);
            if (answer instanceof Raised raised) {
                answer = null;
                throw thrown(raised.failure());
            }
            return (X) answer;
        }

        /** Runs the body to its next operation; false when it has returned. */
        private boolean next() throws E {
            step();
            if (failure != null) throw rethrown();
            return !continuation.isDone();
        }

        @SuppressWarnings("unchecked") // what the body throws is an E, or unchecked
        private E rethrown() {
            if (failure instanceof RuntimeException e) throw e;
            if (failure instanceof Error e) throw e;
            return (E) failure;
        }

        @SuppressWarnings("unchecked") // an Op<F, X> is one of F's operations: an F
        private F operation() {
            return (F) pending;
        }

        <R> R handle(Handler<F, A, R, E> handler) throws E {
            if (!next()) return handler.value(value);
            var k = new Once<R>(pending, handler);
            try {
                return handler.operation(operation(), k);
            } finally {
                if (k.fresh) {
                    k.fresh = false;
                    abandon();
                }
            }
        }

        <R> R handle(TailHandler<F, A, R> handler) throws E {
            while (next()) {
                var op = pending;
                var k = new Once<R>(op, null);
                var answered = false;
                try {
                    handler.operation(operation(), k);
                    answered = !k.fresh;
                } finally {
                    if (k.fresh) {
                        k.fresh = false;
                        abandon();
                    }
                }
                if (!answered) throw new IllegalStateException("a tail handler did not answer " + op);
            }
            return handler.value(value);
        }

        /** The body unwinds: Abandoned thrown at the perform it is suspended at. */
        private void abandon() {
            abandoned = true;
            while (!continuation.isDone()) step();
        }

        /** Runs the body until it yields or ends, the handler's scoped-value bindings kept. */
        private void step() {
            var outside = Bindings.current();
            try {
                continuation.run();
            } finally {
                Bindings.restore(outside);
            }
        }

        /** The body run to its next operation, here: the clause given a detached rest; or its end, the future's. */
        <R> void detached(DetachedHandler<F, A, R> handler, CompletableFuture<R> result) {
            boolean more;
            try {
                more = next();
            } catch (Throwable t) {
                result.completeExceptionally(t);
                return;
            }
            if (!more) {
                try {
                    result.complete(handler.value(value));
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
                return;
            }
            var k = new Detached<R>(pending, handler, result);
            try {
                handler.operation(operation(), k);
            } catch (Throwable t) {
                if (k.take()) abandon();
                result.completeExceptionally(t);
            }
        }

        private final class Detached<R> implements Later<F> {
            private final Op<F, ?> op;
            private final DetachedHandler<F, A, R> handler;
            private final CompletableFuture<R> result;
            private final AtomicBoolean taken = new AtomicBoolean();

            Detached(Op<F, ?> op, DetachedHandler<F, A, R> handler, CompletableFuture<R> result) {
                this.op = op;
                this.handler = handler;
                this.result = result;
            }

            boolean take() {
                return taken.compareAndSet(false, true);
            }

            @Override
            public <X> void answer(Op<F, X> op, X value) {
                if (op != this.op) throw new IllegalArgumentException("resumed with " + op + ", not with " + this.op);
                if (!take()) throw new IllegalStateException("one-shot: " + op + " resumed twice");
                answer = value;
                detached(handler, result);
            }

            @Override
            public void raise(Throwable failure) {
                if (!take()) throw new IllegalStateException("one-shot: " + op + " resumed twice");
                answer = new Raised(failure);
                detached(handler, result);
            }

            @Override
            public void drop() {
                if (!take()) throw new IllegalStateException("one-shot: " + op + " resumed twice");
                abandon();
                result.completeExceptionally(new CancellationException("the rest dropped by its handler"));
            }
        }

        private final class Once<R> implements Resume<F, R, E>, Answer<F> {
            private final Op<F, ?> op;
            private final Handler<F, A, R, E> handler;
            private boolean fresh = true;

            Once(Op<F, ?> op, Handler<F, A, R, E> handler) {
                this.op = op;
                this.handler = handler;
            }

            @Override
            public <X> R resume(Op<F, X> op, X value) throws E {
                answer(op, value);
                return handle(handler);
            }

            @Override
            public <X> void answer(Op<F, X> op, X value) {
                if (op != this.op) throw new IllegalArgumentException("resumed with " + op + ", not with " + this.op);
                if (!fresh) throw new IllegalStateException("one-shot: " + op + " resumed twice");
                fresh = false;
                answer = value;
            }
        }
    }
}
