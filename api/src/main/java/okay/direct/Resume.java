package okay.direct;

/**
 * The rest of the handled body, from an operation up to its handler: ONE-SHOT, resumed at most once. A second
 * {@code resume} throws {@link IllegalStateException}; not resuming it abandons the rest (see {@link Abandoned}).
 *
 * <p>{@code op} is the operation being handled: it fixes the type of {@code answer}. Returns what the handler
 * returns once the rest is handled, so the rest is handled by the same handler (deep); throws what the rest throws.
 */
public interface Resume<F, R, E extends Exception> {
    <X> R resume(Effect.Op<F, X> op, X answer) throws E;
}
