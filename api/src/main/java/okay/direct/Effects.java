package okay.direct;

/**
 * AN IMPLEMENTATION OF PERFORM AND HANDLE. {@code handle} runs the body with the capability of F; each
 * {@code perform} through it suspends the body up to this handler, and the handler resumes it, or not. What the
 * body throws, {@code handle} throws on. Programs are written against this interface and the capabilities, and run
 * on any implementation.
 *
 * <p>Every implementation runs one-shot handlers: {@link Handler}, {@link TailHandler}. One whose continuations
 * are multi-shot is a {@link MultiEffects}, and runs {@link MultiHandler}s too: which handler runs where is a type.
 *
 * <p>A program runs on one implementation: handlers of different implementations do not nest.
 */
public interface Effects {
    <F, A, R, E extends Exception> R handle(Handler<F, A, R, E> handler, Body<F, A, E> body) throws E;

    <F, A, R, E extends Exception> R handle(TailHandler<F, A, R> handler, Body<F, A, E> body) throws E;

    /** F handled with a state from {@code initial}. One-shot implementations keep it in the handler's loop. */
    default <F, S, A, R, E extends Exception> R handle(StatefulHandler<F, S, A, R> handler, S initial,
            Body<F, A, E> body) throws E {
        return handle(new TailHandler<F, A, R>() {
            private S state = initial;

            @Override
            public R value(A value) {
                return handler.value(state, value);
            }

            @Override
            public void operation(F op, Answer<F> k) {
                state = handler.operation(state, op, k);
            }
        }, body);
    }
}
