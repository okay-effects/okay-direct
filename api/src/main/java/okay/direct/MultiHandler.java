package okay.direct;

/** A handler of F that may resume the rest more than once (Choose): runs only on a {@link MultiEffects}. */
public interface MultiHandler<F, A, R, E extends Exception> {
    R value(A value);

    R operation(F op, MultiResume<F, R, E> k) throws E;
}
