package okay.direct;

/** An implementation whose capabilities show each operation to an observer first. */
final class Watched implements Effects {
    /** Bound while an observer's clause runs: its own operations are not shown to it. */
    private static final ScopedValue<Boolean> OBSERVING = ScopedValue.newInstance();

    private final Effects effects;
    private final Observer observer;

    Watched(Effects effects, Observer observer) {
        this.effects = effects;
        this.observer = observer;
    }

    @Override
    public <F, A, R, E extends Exception> R handle(Handler<F, A, R, E> handler, Body<F, A, E> body) throws E {
        return effects.handle(handler, (Effect<F> effect) -> body.run(watched(effect)));
    }

    @Override
    public <F, A, R, E extends Exception> R handle(TailHandler<F, A, R> handler, Body<F, A, E> body) throws E {
        return effects.handle(handler, (Effect<F> effect) -> body.run(watched(effect)));
    }

    private <F> Effect<F> watched(Effect<F> effect) {
        return new Effect<>() {
            @Override
            public <X> X perform(Op<F, X> op) {
                if (OBSERVING.isBound()) return effect.perform(op);
                Observer.Seen<X> seen = new Observer.Seen<>() {
                    @Override
                    public Op<?, X> op() {
                        return op;
                    }

                    @Override
                    public X proceed() {
                        return effect.perform(op);
                    }
                };
                return ScopedValue.where(OBSERVING, Boolean.TRUE).call(() -> observer.observe(seen));
            }
        };
    }
}
