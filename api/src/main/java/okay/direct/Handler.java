package okay.direct;

/**
 * A handler of F: the body's value to the result, and an operation with the rest of the body, {@code k}, which is
 * ONE-SHOT: resumed at most once. A handler resuming more than once is a {@link MultiHandler}. {@code E} is what
 * the body throws: resuming it may throw that.
 */
public interface Handler<F, A, R, E extends Exception> {
    R value(A value);

    /** {@code k} not resumed: the rest is abandoned, and unwinds (see {@link Abandoned}). */
    R operation(F op, Resume<F, R, E> k) throws E;
}
