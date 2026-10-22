package okay.direct;

/** A capability whose operations go through an interceptor, at the perform, before the one it guards. */
final class Intercepted<C, F> implements Effect<F> {
    private final Effect<F> outer;
    private final Interceptor<C, F> interceptor;
    private volatile boolean closed;

    Intercepted(Effect<F> outer, Interceptor<C, F> interceptor) {
        this.outer = outer;
        this.interceptor = interceptor;
    }

    @Override
    @SuppressWarnings("unchecked") // an operation of F is one of the cases C; the answer is the one given with it
    public <X> X perform(Op<F, X> op) {
        if (closed) throw new IllegalStateException("performed outside its audit: " + op);
        var k = new Given(op);
        interceptor.operation((C) op, outer::perform, k);
        if (!k.given) throw new IllegalStateException("the interceptor did not answer " + op);
        return (X) k.value;
    }

    void close() {
        closed = true;
    }

    private final class Given implements Answer<F> {
        private final Op<F, ?> op;
        private boolean given;
        private Object value;

        Given(Op<F, ?> op) {
            this.op = op;
        }

        @Override
        public <X> void answer(Op<F, X> op, X value) {
            if (op != this.op) throw new IllegalArgumentException("answered " + op + ", not " + this.op);
            if (given) throw new IllegalStateException(op + " answered twice");
            given = true;
            this.value = value;
        }
    }
}
