package okay.direct;

import okay.effect.Pure;
import okay.effect.Eff;
/**
 * THE CAPABILITIES OF A ROW: the value at run time of the row type {@code R} (see {@link Eff}). A place is taken by
 * its position, as the handlers are nested: {@link #head} the nearest, {@link #tail} the rest. A program that needs
 * one effect takes an {@link Effect}; one that needs several takes the row.
 *
 * <pre>{@code
 * static int step(Fx<? extends Eff<State<Integer>, ? extends Eff<Reader<Integer>, ?>>> fx) {
 *     Effect<State<Integer>> state = Fx.head(fx);
 *     int by = Reader.ask(Fx.second(fx));
 *     int s = State.get(state);
 *     State.put(state, s + by);
 *     return s;
 * }
 * Fx.run(effects, Reader.run(10, State.run(0, Example::step)));   // Final[state=10, value=0]
 * }</pre>
 *
 * <p>A row's capabilities are of one implementation (the one given to {@link #on}), and like every capability are
 * used only in the dynamic extent of the body they were given to.
 */
public final class Fx<R> {

    private final Effects effects;
    private final Effect<?> head;
    private final Fx<?> tail;

    private Fx(Effects effects, Effect<?> head, Fx<?> tail) {
        this.effects = effects;
        this.head = head;
        this.tail = tail;
    }

    /** The empty row on an implementation: where a program of {@code Pure} starts. */
    public static Fx<Pure> on(Effects effects) {
        return new Fx<>(effects, null, null);
    }

    /** The implementation the row's capabilities are of: handlers of the row are installed on it. */
    public Effects effects() {
        return effects;
    }

    /** The capability of the first place. */
    @SuppressWarnings("unchecked")
    public static <E> Effect<E> head(Fx<? extends Eff<E, ?>> fx) {
        return (Effect<E>) fx.head;
    }

    /** The capability of the second place. */
    public static <E> Effect<E> second(Fx<? extends Eff<?, ? extends Eff<E, ?>>> fx) {
        return head(tail(fx));
    }

    /** The capability of the third place. */
    public static <E> Effect<E> third(Fx<? extends Eff<?, ? extends Eff<?, ? extends Eff<E, ?>>>> fx) {
        Fx<Eff<?, ? extends Eff<E, ?>>> rest = tail(fx);
        return second(rest);
    }

    /** The row without its first place: a program of the rest is called with it. */
    @SuppressWarnings("unchecked")
    public static <R> Fx<R> tail(Fx<? extends Eff<?, ? extends R>> fx) {
        return (Fx<R>) fx.tail;
    }

    /** The row seen as a wider type: {@code Fx<Eff<A, Pure>>} as {@code Fx<Eff<A, ?>>}, a named row as its list. */
    @SuppressWarnings("unchecked")
    public static <R> Fx<R> fit(Fx<? extends R> fx) {
        return (Fx<R>) fx;
    }

    /**
     * The row with one more place in front: how a handler written for a {@link Body} gives its capability to a
     * program of a row ({@code outer -> State.run(outer.effects(), 0, s -> p.run(Fx.with(s, outer)))}).
     */
    public static <E, R> Fx<Eff<E, R>> with(Effect<E> effect, Fx<R> rest) {
        if (effect == null) throw new NullPointerException("effect");
        return new Fx<>(rest.effects, effect, rest);
    }

    /** Runs a program that performs nothing more: every place of its row handled. */
    public static <A> A run(Effects effects, Program<A, Pure> program) {
        return program.run(on(effects));
    }

    /** The program with its first place handled by a one-shot handler: a program of the rest of the row. */
    public static <F, R, A, B> Program<B, R> handle(Handler<F, A, B, RuntimeException> handler,
            Program<A, Eff<F, R>> program) {
        return outer -> outer.effects.handle(handler, (Effect<F> e) -> program.run(with(e, outer)));
    }

    /** The program with its first place handled in place (constant stack). */
    public static <F, R, A, B> Program<B, R> handle(InPlace<F, A, B> handler,
            Program<A, Eff<F, R>> program) {
        return outer -> outer.effects.handle(handler, (Effect<F> e) -> program.run(with(e, outer)));
    }

    /** The program with its first place handled with a state from {@code initial}. */
    public static <F, S, R, A, B> Program<B, R> handle(StatefulHandler<F, S, A, B> handler, S initial,
            Program<A, Eff<F, R>> program) {
        return outer -> outer.effects.handle(handler, initial, (Effect<F> e) -> program.run(with(e, outer)));
    }

    /**
     * The program with its first place handled by a multi-shot handler. Multi-shot is a type: the implementation is
     * given, and must be the row's own, else {@link IllegalStateException} when the program runs.
     */
    public static <F, R, A, B> Program<B, R> handle(MultiEffects multi,
            MultiHandler<F, A, B, RuntimeException> handler, Program<A, Eff<F, R>> program) {
        return outer -> {
            if (outer.effects != multi)
                throw new IllegalStateException("a multi-shot handler on another implementation than the row's");
            return multi.handle(handler, (Effect<F> e) -> program.run(with(e, outer)));
        };
    }

    @Override
    public String toString() {
        int n = 0;
        for (Fx<?> f = this; f.head != null; f = f.tail) n++;
        return "Fx(" + n + " places on " + effects + ")";
    }
}
