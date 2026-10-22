package okay.direct;

/** A body handled by F's handler: run with F's capability, it may throw the checked exception E, which its
 * {@code handle} throws on. */
@FunctionalInterface
public interface Body<F, A, E extends Exception> {
    A run(Effect<F> effect) throws E;
}
