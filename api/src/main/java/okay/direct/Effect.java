package okay.direct;

import okay.effect.Op;

/**
 * The capability to perform the operations of {@code F}: given by F's handler to the body it handles.
 *
 * <p>It is used only by the body it was given to, while that body runs: on the body's thread, or in a body nested
 * in it. Anywhere else, after its handler has returned, on another thread, or in the handler's own clause,
 * {@code perform} throws {@link IllegalStateException}.
 */
public interface Effect<F> {
    /**
     * Suspends the body up to F's handler, and returns the answer the handler resumes it with. The operation is an
     * okay-java-api {@link Op} of F, which names itself ({@code record Get<S>() implements State<S>,
     * Op<State<S>, S, Get<S>>}): a class that names itself and is not an F does not compile.
     */
    <X> X perform(Op<F, X, ?> op);
}
