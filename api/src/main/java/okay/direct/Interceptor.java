package okay.direct;

/**
 * AN INTERCEPTOR of the operations of one effect, the cases {@code C} of the family {@code F}: each operation
 * performed through the capability it guards comes to it first, at the {@code perform}, before any handler. It may
 * <ul>
 * <li>tell: perform effects of its own, to a trail, a log, a metric;</li>
 * <li>pass the operation on, {@code pass.pass(op)}, to the capability it guards, and answer with what came back;</li>
 * <li>change the operation, passing on another one, or change the answer;</li>
 * <li>refuse: not pass it on, and throw, or fail through an effect of its own, {@code Fail.raise}.</li>
 * </ul>
 * It answers through {@code k}, exactly once, the operation the witness of the answer's type, unless it refuses.
 * For a plain effect {@code C} and {@code F} are the same; for a sensitive one {@code F} is {@code Audited<C>}.
 */
@FunctionalInterface
public interface Interceptor<C, F> {
    void operation(C op, Pass<F> pass, Answer<F> k);

    /** The operation passed on, past the interceptor, to the capability it guards. */
    interface Pass<F> {
        <X> X pass(Effect.Op<F, X> op);
    }

    /** The capability {@code outer} with each of its operations through {@code interceptor} first. */
    static <F> Effect<F> intercept(Effect<F> outer, Interceptor<F, F> interceptor) {
        return new Intercepted<>(outer, interceptor);
    }
}
