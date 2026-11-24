package okay.direct;

/**
 * A handler of F whose continuations are DETACHED: its clause gets the rest as a {@link Later} and returns at once,
 * resuming it when it likes — from a callback, another thread, a consumer's pull. Run by a {@link DetachedEffects}.
 */
public interface DetachedHandler<F, A, B> {
    B ret(A value);

    void on(F op, Later<F> k);
}
