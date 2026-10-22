package okay.direct;

/** The answer to an operation, given in place: the body goes on with it at once. */
public interface Answer<F> {
    <X> void answer(Effect.Op<F, X> op, X value);
}
