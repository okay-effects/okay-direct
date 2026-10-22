package okay.direct;

/**
 * A DETACHED CONTINUATION: the rest of a body from an operation up to its handler, outliving the clause that got it —
 * resumed at most once, at any time, from any thread. Resuming runs the rest ON THE RESUMING THREAD until it performs
 * the handler's effect again (the clause called again, with a new {@code Later}) or ends (the handler's value
 * completes {@link DetachedEffects#start}'s future). A second resumption throws {@link IllegalStateException}.
 */
public interface Later<F> {
    /** The rest resumed with {@code answer}; {@code op} is the operation being handled, the witness of its type. */
    <X> void answer(Effect.Op<F, X> op, X answer);

    /** The rest resumed by a failure: thrown at its {@code perform} (an unchecked one as it is, a checked one in a
     * {@code CompletionException}). */
    void raise(Throwable failure);

    /** The rest dropped: it unwinds ({@code finally} blocks run), and the future completes with a
     * {@code CancellationException}. */
    void drop();
}
