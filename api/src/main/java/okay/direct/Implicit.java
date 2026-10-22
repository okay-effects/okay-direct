package okay.direct;

/**
 * AN IMPLICIT CAPABILITY: a key, by {@link ScopedValue}, under which the capability of F is found, instead of being
 * passed. {@link #in} binds it for a body; {@link #get} finds it anywhere the body calls:
 * <pre>{@code
 * static final Implicit<State<Integer>> COUNTER = Implicit.newInstance();
 *
 * static int next() { return State.get(COUNTER.get()) + 1; }
 *
 * State.run(fx, 0, COUNTER.in(() -> next()));
 * }</pre>
 * Two keys of the same effect are two capabilities: an inner binding of a key hides the outer one only for that
 * key. The implicit capabilities in scope where {@code in} is called are carried into the body, on any
 * implementation, a body on a thread of its own too. A handler's clauses run outside its body, and see the
 * capabilities of outside it.
 */
public final class Implicit<F> {
    /** All the implicit capabilities in scope, to carry into a body. */
    private static final ScopedValue<ScopedValue.Carrier> IN_SCOPE = ScopedValue.newInstance();

    private final ScopedValue<Effect<F>> capability = ScopedValue.newInstance();

    private Implicit() {}

    public static <F> Implicit<F> newInstance() {
        return new Implicit<>();
    }

    /** The capability bound to this key: throws {@link IllegalStateException} where none is. */
    public Effect<F> get() {
        if (!capability.isBound()) throw new IllegalStateException("no capability bound to " + this);
        return capability.get();
    }

    /** The body, run with its capability bound to this key, and with the implicit capabilities in scope here. */
    public <A, E extends Exception> Body<F, A, E> in(Thunk<A, E> body) {
        var outside = IN_SCOPE.isBound() ? IN_SCOPE.get() : null;
        return effect -> {
            var inside = outside == null ? ScopedValue.where(capability, effect) : outside.where(capability, effect);
            return inside.where(IN_SCOPE, inside).call(body::run);
        };
    }

    /** A body whose capabilities are implicit. */
    @FunctionalInterface
    public interface Thunk<A, E extends Exception> {
        A run() throws E;
    }
}
