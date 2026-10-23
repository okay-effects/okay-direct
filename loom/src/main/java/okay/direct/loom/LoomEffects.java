package okay.direct.loom;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
        return new Run<F, A, E>(body).inPlace(handler);
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

    /** One operation in this many of a tail handler yields, the others answered in place (see inPlaceSinceYield). */
    private static final int YIELD_EVERY = 256;

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
        /** ANSWERED IN PLACE: a tail handler's clause, called at the perform on the body's own stack — no yield, no
         * continuation switched; null for a handler that takes the rest */
        private TailHandler<F, A, ?> tail;
        /** the bindings outside the body, which the clause runs with */
        private Object outside;
        private boolean inClause;
        /** operations answered in place since the last yield: every YIELD_EVERY-th is yielded instead. A yield
         * freezes the body's stack into the heap and thaws it lazily, so the stack it runs on stays small — what
         * keeps a general handler resuming inside this body (an interceptor's: each resumption a call deeper) in
         * constant stack, as when every operation yielded */
        private int inPlaceSinceYield;
        private boolean answered;
        private final Answer<F> inPlaceAnswer = new Answer<>() {
            @Override
            public <X> void answer(Op<F, X> op, X value) {
                if (op != pending) throw new IllegalArgumentException("resumed with " + op + ", not with " + pending);
                if (answered) throw new IllegalStateException("one-shot: " + op + " resumed twice");
                answered = true;
                answer = value;
            }
        };

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
            if (tail != null) {
                if (inClause || Continuation.getCurrentContinuation(scope) == null)
                    throw new IllegalStateException("a capability used outside its body, or by its own handler's clause");
                if (++inPlaceSinceYield < YIELD_EVERY) return answeredInPlace(op);
                inPlaceSinceYield = 0; // this one by a yield, answered by inPlace's loop: the stack frozen, compacted
            }
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

        /**
         * The operation given to the tail handler's clause here, at the perform: the clause runs with the bindings of
         * outside the body (switched only if the body has bound others), its answer returned at once. Usable as a yield
         * is: only where the body's scope is mounted (on its thread, in it, while it runs) and not from the clause
         * itself. A clause that throws, or does not answer, ends the body as a dropped rest does: Abandoned unwinds it,
         * and handle throws the failure.
         */
        @SuppressWarnings("unchecked") // answered through inPlaceAnswer with this very operation as its witness: an X
        private <X> X answeredInPlace(Op<F, X> op) {
            pending = op;
            answered = false;
            inClause = true;
            var inside = Bindings.current();
            var switched = inside != outside;
            if (switched) Bindings.restore(outside);
            try {
                tail.operation(operation(), inPlaceAnswer);
            } catch (Throwable t) {
                failure = t;
                abandoned = true;
                throw new Abandoned(this);
            } finally {
                inClause = false;
                if (switched) Bindings.restore(inside);
            }
            if (!answered) {
                failure = new IllegalStateException("a tail handler did not answer " + op);
                abandoned = true;
                throw new Abandoned(this);
            }
            var x = (X) answer;
            answer = null;
            return x;
        }

        /** The body run with its operations answered in place, at the perform, but one in YIELD_EVERY: that one
         * yields, and is answered by the tail handler's loop as every operation was before. */
        <R> R inPlace(TailHandler<F, A, R> handler) throws E {
            tail = handler;
            outside = Bindings.current();
            return handle(handler); // the loop answers the operations that yield; the rest are answered at the perform
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

        /**
         * The body run to its next operation, here: the clause given a detached rest; or its end, the future's. A rest
         * the clause answers before it returns, on its own thread, goes on in this loop: awaits of what is already
         * there take no stack.
         */
        <R> void detached(DetachedHandler<F, A, R> handler, CompletableFuture<R> result) {
            while (true) {
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
                    // answered or not, the rest unwinds: the run has failed
                    if (k.left() || k.take()) abandon();
                    result.completeExceptionally(t);
                    return;
                }
                if (!k.left()) return;
            }
        }

        private final class Detached<R> implements Later<F> {
            private static final int IN_CLAUSE = 0, LEFT = 1, ANSWERED_IN_CLAUSE = 2;

            private final Op<F, ?> op;
            private final DetachedHandler<F, A, R> handler;
            private final CompletableFuture<R> result;
            private final AtomicBoolean taken = new AtomicBoolean();
            private final AtomicInteger state = new AtomicInteger(IN_CLAUSE);
            private final Thread clause = Thread.currentThread();

            Detached(Op<F, ?> op, DetachedHandler<F, A, R> handler, CompletableFuture<R> result) {
                this.op = op;
                this.handler = handler;
                this.result = result;
            }

            boolean take() {
                return taken.compareAndSet(false, true);
            }

            /** The clause has returned: true if it answered the rest meanwhile, on its thread, to go on with here. */
            boolean left() {
                return !state.compareAndSet(IN_CLAUSE, LEFT);
            }

            /** The rest goes on: in the clause's loop if answered from the clause itself, else here. */
            private void resume() {
                if (Thread.currentThread() == clause && state.compareAndSet(IN_CLAUSE, ANSWERED_IN_CLAUSE)) return;
                detached(handler, result);
            }

            @Override
            public <X> void answer(Op<F, X> op, X value) {
                if (op != this.op) throw new IllegalArgumentException("resumed with " + op + ", not with " + this.op);
                if (!take()) throw new IllegalStateException("one-shot: " + op + " resumed twice");
                answer = value;
                resume();
            }

            @Override
            public void raise(Throwable failure) {
                if (!take()) throw new IllegalStateException("one-shot: " + op + " resumed twice");
                answer = new Raised(failure);
                resume();
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
