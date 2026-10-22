package okay.direct;

/**
 * SENSITIVE EFFECTS, performed only under an audit. The handler of a sensitive effect is installed by
 * {@link #handle}, which gives its body not a capability but a {@link Vault}: nothing can be performed through it.
 * {@link #audit} takes the vault and an interceptor, and gives its body the capability, {@code Effect<Audited<C>>},
 * every operation through the interceptor first. So every sensitive operation answered has passed an audit: a
 * program cannot perform it otherwise, nor a handler written by hand answer it (see {@link Audited}).
 */
public final class Audit {
    private Audit() {}

    /** A handler of the sensitive effect C: answers each operation in place, the operation the witness. */
    public interface Handler<C, A, R> {
        R value(A value);

        void operation(C op, Answer<Audited<C>> k);
    }

    /** The handler of a sensitive effect, as it is given to its body: only {@link #audit} performs through it. */
    public static final class Vault<C> {
        private final Effect<Audited<C>> effect;

        private Vault(Effect<Audited<C>> effect) {
            this.effect = effect;
        }
    }

    /** A body under the handler of a sensitive effect. */
    @FunctionalInterface
    public interface VaultBody<C, A, E extends Exception> {
        A run(Vault<C> vault) throws E;
    }

    /** The sensitive effect C handled by {@code handler}; the body gets its vault. */
    public static <C, A, R, E extends Exception> R handle(Effects effects, Handler<C, A, R> handler,
            VaultBody<C, A, E> body) throws E {
        return effects.handle(new TailHandler<Audited<C>, A, R>() {
            @Override
            public R value(A value) {
                return handler.value(value);
            }

            @Override
            @SuppressWarnings("unchecked") // an operation of Audited<C> is one of the cases C (see Audited)
            public void operation(Audited<C> op, Answer<Audited<C>> k) {
                handler.operation((C) (Object) op, k);
            }
        }, (Effect<Audited<C>> effect) -> body.run(new Vault<>(effect)));
    }

    /** The body, its capability of C every operation of which goes through {@code interceptor} to the vault. */
    public static <C, A, E extends Exception> A audit(Vault<C> vault, Interceptor<C, Audited<C>> interceptor,
            Body<Audited<C>, A, E> body) throws E {
        var guarded = new Intercepted<>(vault.effect, interceptor);
        try {
            return body.run(guarded);
        } finally {
            guarded.close();
        }
    }
}
