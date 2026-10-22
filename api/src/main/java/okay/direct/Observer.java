package okay.direct;

/**
 * AN OBSERVER OF EVERY EFFECT: {@link #watch} makes an implementation whose every capability, of every handler
 * installed through it, shows each operation to the observer first, at the {@code perform}. It sees the operation
 * as data, and its answer at its type; it may tell, pass it on as it is ({@code proceed}), or refuse (throw). Its
 * own operations, of a trail or a log, are not shown to it.
 */
@FunctionalInterface
public interface Observer {
    <X> X observe(Seen<X> seen);

    /** An operation seen: what it is, and the way on to its handler. */
    interface Seen<X> {
        /** The operation, as it is: data. */
        Effect.Op<?, X> op();

        /** The effect's name: the sealed interface the operation is a case of. */
        default String effect() {
            var enclosing = op().getClass().getEnclosingClass();
            return enclosing != null ? enclosing.getSimpleName() : op().getClass().getSimpleName();
        }

        /** The operation performed, past the observer, to its handler: its answer. */
        X proceed();
    }

    /** {@code effects}, every operation performed through a capability of its handlers shown to {@code observer}. */
    static Effects watch(Effects effects, Observer observer) {
        return new Watched(effects, observer);
    }
}
