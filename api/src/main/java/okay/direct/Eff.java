package okay.direct;

/**
 * THE ROW — the effects a program may perform — as a list in its type, in the order of the handlers, the nearest
 * first: {@code Eff<State<Integer>, Eff<Log, Pure>>} is a state, a log under it, and nothing more;
 * {@code Eff<State<Integer>, ?>} a state and anything under it. Only a type: there is no value of a row. Its value
 * at run time is a {@link Fx}, the capabilities of its places.
 *
 * <p>The same row as okay-effects' {@code Eff<E, R>}: a program written as data ({@code Comp<A, R>}) and one in
 * direct style ({@code Program<R, A, X>}) say what they may do with one type.
 *
 * <p>Java does not capture a wildcard nested in a type argument, so a row to be used inside longer rows is written
 * open at every step: {@code Eff<State<Integer>, ? extends Eff<Log, ?>>}. A row may be named
 * ({@code interface Shop extends Eff<Prices, Eff<Log, Pure>> {}}) for reading its places; handlers take apart only
 * rows written as {@code Eff}.
 */
public interface Eff<E, R> {}
