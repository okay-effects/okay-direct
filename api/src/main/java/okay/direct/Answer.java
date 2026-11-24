package okay.direct;

import okay.effect.Op;
/** The answer to an operation, given in place: the body goes on with it at once. */
public interface Answer<F> {
    <X> void answer(Op<F, X, ?> op, X value);
}
