package okay.direct;

/**
 * The capability to perform the operations of {@code F}: given by F's handler to the body it handles.
 *
 * <p>It is used only by the body it was given to, while that body runs: on the body's thread, or in a body nested
 * in it. Anywhere else, after its handler has returned, on another thread, or in the handler's own clause,
 * {@code perform} throws {@link IllegalStateException}.
 */
public interface Effect<F> {
    /** Suspends the body up to F's handler, and returns the answer the handler resumes it with. */
    <X> X perform(Op<F, X> op);

    /**
     * An operation of the effect {@code F}, answered by a value of type {@code X}. Operations are data.
     *
     * <p>An effect is a sealed interface, its operations records implementing both it and {@code Op}:
     * <pre>{@code
     * sealed interface State<S> {
     *     record Get<S>() implements State<S>, Op<State<S>, S> {}
     *     record Put<S>(S value) implements State<S>, Op<State<S>, Void> {}
     * }
     * }</pre>
     * A handler matches an {@code F} exhaustively; the operation itself is the witness of its answer's type. An
     * {@code Op<F, X>} must be an {@code F}: implementations hand it to F's handler as one.
     */
    interface Op<F, X> {}
}
