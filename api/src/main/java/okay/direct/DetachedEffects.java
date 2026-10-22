package okay.direct;

import java.util.concurrent.CompletableFuture;

/**
 * An implementation whose continuations can be DETACHED from their clause (Loom's, threads'): it runs
 * {@link DetachedHandler}s. Whether a continuation may outlive its clause is a type, as whether it may be resumed
 * more than once is.
 *
 * <p>A detached body runs apart from the code that started it: on the thread that resumes it, after its first
 * operation. So it uses its own handler's capability and those of handlers inside it; a capability of outside it is
 * not its own.
 */
public interface DetachedEffects extends Effects {
    /**
     * The body run at once, on this thread, until it performs the handler's effect: the clause gets the rest as a
     * {@code Later}, and this returns. The future completes with the handler's value when the body ends — on the
     * thread that resumed it last — or exceptionally with what the body or a clause threw, or with a
     * {@code CancellationException} when the rest is dropped.
     */
    <F, A, R, E extends Exception> CompletableFuture<R> start(DetachedHandler<F, A, R> handler, Body<F, A, E> body);
}
