package okay.direct;

/**
 * SENSITIVE EFFECTS, performed only under an audit. The handler of a sensitive effect is installed by
 * {@link #handle}, which gives its body not a capability but an {@link Audited} place: nothing can be performed
 * through it. {@link #audit} takes it and an interceptor, and gives its body the capability,
 * {@code Effect<Sensitive<C>>}, every operation through the interceptor first. So every sensitive operation
 * answered has passed an audit: a program cannot perform it otherwise, nor a handler written by hand answer it (see
 * {@link Sensitive}).
 */
public final class Audit {
    private Audit() {}

    /** A handler of the sensitive effect C: answers each operation in place, the operation the witness. */
    public interface Handler<C, A, B> {
        B ret(A value);

        void answer(C op, Answer<Sensitive<C>> k);
    }

    /** A body under the handler of a sensitive effect: it gets the audited place. */
    @FunctionalInterface
    public interface AuditedBody<C, A, E extends Exception> {
        A run(Audited<C> audited) throws E;
    }

    /** The sensitive effect C handled by {@code handler}; the body gets its audited place. */
    public static <C, A, B, E extends Exception> B handle(Effects effects, Handler<C, A, B> handler,
            AuditedBody<C, A, E> body) throws E {
        return effects.handle(new InPlace<Sensitive<C>, A, B>() {
            @Override
            public B ret(A value) {
                return handler.ret(value);
            }

            @Override
            @SuppressWarnings("unchecked") // an operation of Sensitive<C> is one of the cases C (see Sensitive)
            public void answer(Sensitive<C> op, Answer<Sensitive<C>> k) {
                handler.answer((C) (Object) op, k);
            }
        }, (Effect<Sensitive<C>> effect) -> body.run(new Audited<>(effect)));
    }

    /** The body, its capability of C every operation of which goes through {@code interceptor} to the handler. */
    public static <C, A, E extends Exception> A audit(Audited<C> audited, Interceptor<C, Sensitive<C>> interceptor,
            Body<Sensitive<C>, A, E> body) throws E {
        var guarded = new Intercepted<>(audited.effect, interceptor);
        try {
            return body.run(guarded);
        } finally {
            guarded.close();
        }
    }
}
