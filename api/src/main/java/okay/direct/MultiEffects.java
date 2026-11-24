package okay.direct;

/** An implementation whose continuations are MULTI-SHOT: it runs every handler, multi-shot ones too. */
public interface MultiEffects extends Effects {
    <F, A, B, E extends Exception> B handle(MultiHandler<F, A, B, E> handler, Body<F, A, E> body) throws E;
}
