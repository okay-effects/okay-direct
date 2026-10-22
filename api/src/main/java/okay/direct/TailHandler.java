package okay.direct;

/**
 * A handler of F that answers every operation in place (Reader, State): it resumes the rest once, at once, so an
 * implementation runs it in a loop, in constant stack, however many operations the body performs.
 */
public interface TailHandler<F, A, R> {
    R value(A value);

    /** Must answer the operation through {@code k}, exactly once. */
    void operation(F op, Answer<F> k);
}
