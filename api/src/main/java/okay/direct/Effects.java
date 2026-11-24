package okay.direct;

/**
 * AN IMPLEMENTATION OF PERFORM AND HANDLE. {@code handle} runs the body with the capability of F; each
 * {@code perform} through it suspends the body up to this handler, and the handler resumes it, or not. What the
 * body throws, {@code handle} throws on. Programs are written against this interface and the capabilities, and run
 * on any implementation.
 *
 * <p>Every implementation runs one-shot handlers: {@link Handler}, {@link InPlace}. One whose continuations
 * are multi-shot is a {@link MultiEffects}, and runs {@link MultiHandler}s too: which handler runs where is a type.
 *
 * <p>A program runs on one implementation: handlers of different implementations do not nest.
 */
public interface Effects {
    <F, A, B, E extends Exception> B handle(Handler<F, A, B, E> handler, Body<F, A, E> body) throws E;

    <F, A, B, E extends Exception> B handle(InPlace<F, A, B> handler, Body<F, A, E> body) throws E;

    /** F handled with a state from {@code initial}. One-shot implementations keep it in the handler's loop. */
    default <F, S, A, B, E extends Exception> B handle(StatefulHandler<F, S, A, B> handler, S initial,
            Body<F, A, E> body) throws E {
        return handle(new InPlace<F, A, B>() {
            private S state = initial;

            @Override
            public B ret(A value) {
                return handler.ret(state, value);
            }

            @Override
            public void answer(F op, Answer<F> k) {
                state = handler.answer(state, op, k);
            }
        }, body);
    }
}
