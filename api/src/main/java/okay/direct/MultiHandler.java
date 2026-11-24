package okay.direct;

/** A handler of F that may resume the rest more than once (Choose): runs only on a {@link MultiEffects}. */
public interface MultiHandler<F, A, B, E extends Exception> {
    B ret(A value);

    B on(F op, MultiResume<F, B, E> k) throws E;
}
