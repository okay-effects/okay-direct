package okay.direct;

/** An implementation whose continuations are MULTI-SHOT: it runs every handler, multi-shot ones too. */
public interface MultiEffects extends Effects {
    <F, A, R, E extends Exception> R handle(MultiHandler<F, A, R, E> handler, Body<F, A, E> body) throws E;
}
