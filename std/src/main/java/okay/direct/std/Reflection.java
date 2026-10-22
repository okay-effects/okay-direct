package okay.direct.std;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;
import okay.direct.Effects;
import okay.direct.MultiEffects;

/**
 * MONADIC REFLECTION (Filinski, "Representing Monads"): a monad's computation written in direct style. {@code reify}
 * runs the body and gives its value to the monad's {@code unit}; {@code reflect(m)} in the body takes a monadic value
 * apart: the rest of the body up to the reify is the function given to the monad's {@code bind}. On delimited
 * continuations: reify is a reset, reflect a shift0.
 * <pre>{@code
 * static <A, R> A get(Delimited.Prompt<Optional<R>> p, Optional<A> m) { return Reflection.reflect(p, m, Optional::flatMap); }
 *
 * Optional<Integer> sum = Reflection.reify(fx, Optional::of,
 *     (Delimited.Prompt<Optional<Integer>> p) -> get(p, Optional.of(1)) + get(p, Optional.of(2)));
 * }</pre>
 * The pairs of Optional ({@link #optional}), of an either ({@link #outcome}) and of a writer ({@link #written}) are
 * here already, typed, each with its {@code get}. A monad whose bind calls the rest LATER — a reader, a state, a
 * future — cannot be reflected by a rest that dies with its clause: use the effect instead ({@link Reader},
 * {@link State}), or for a future {@link Async}, whose rest is detached.
 * Java has no higher kinds, so the monad is not a type: its {@code unit} is given to reify, its {@code bind} to each
 * reflect, as a method reference. A generic bind ({@code Optional::flatMap}) does not tell Java the value's type,
 * so a monad's reflect is best written once, as {@code get} above, where it is known; and the prompt's type is
 * written in the body's lambda, the answer's type not known to Java from {@code unit} alone. A monad whose bind calls the rest at most once (Optional, Either, a reader, a
 * writer) reflects on any implementation; one calling it more often (a list) needs a {@link MultiEffects}.
 */
public final class Reflection {
    private Reflection() {}

    /** The body's computation, reified: {@code unit} of its value, each reflect inside it a bind. */
    public static <R, MR> MR reify(Effects effects, Function<R, MR> unit, Function<Delimited.Prompt<MR>, R> body) {
        return Delimited.reset(effects, prompt -> unit.apply(body.apply(prompt)));
    }

    /** The same, its reflects able to resume the rest more than once. */
    public static <R, MR> MR reify(MultiEffects effects, Function<R, MR> unit, Function<Delimited.Prompt<MR>, R> body) {
        return Delimited.reset(effects, prompt -> unit.apply(body.apply(prompt)));
    }

    /** The monadic value {@code m} reflected: the value it binds, the rest of the body up to the reify bound to it. */
    public static <M, A, MR> A reflect(Delimited.Prompt<MR> prompt, M m, BiFunction<M, Function<A, MR>, MR> bind) {
        return prompt.shift0(k -> bind.apply(m, k::resume));
    }

    /**
     * THE OPTIONAL MONAD, reified: the body's value, or empty if a {@link #get} in it found nothing — the rest of the
     * body then not run — or if the body returns null (as {@code Optional.map} does). Its types are inferred, the answer's from the target:
     * <pre>{@code
     * Optional<String> city = Reflection.optional(fx, p -> get(p,
     *     Stream.of("user", "address", "city").reduce(doc, (node, key) -> get(p, node.field(key)), (a, b) -> a).str()));
     * }</pre>
     */
    public static <R> Optional<R> optional(Effects effects, Function<Delimited.Prompt<Optional<R>>, R> body) {
        return reify(effects, Optional::ofNullable, body);
    }

    /** The value of {@code m}, under {@link #optional}: if it is empty, the rest of the body is dropped (it unwinds). */
    public static <A, R> A get(Delimited.Prompt<Optional<R>> prompt, Optional<A> m) {
        return reflect(prompt, m, Optional::flatMap);
    }

    /**
     * THE EITHER MONAD, {@link Fail.Outcome}, reified: the body's value as a success, or the first failure a
     * {@link #get} in it met — the rest of the body then not run.
     * <pre>{@code
     * Outcome<String, Integer> total = Reflection.outcome(fx, p -> get(p, parse("40")) + get(p, parse("2")));
     * }</pre>
     */
    public static <E, R> Fail.Outcome<E, R> outcome(Effects effects,
            Function<Delimited.Prompt<Fail.Outcome<E, R>>, R> body) {
        return reify(effects, Fail.Success::new, body);
    }

    /** The value of a success, under {@link #outcome}; a failure is the outcome's, the rest dropped. */
    public static <E, A, R> A get(Delimited.Prompt<Fail.Outcome<E, R>> prompt, Fail.Outcome<E, A> m) {
        return reflect(prompt, m, (Fail.Outcome<E, A> o, Function<A, Fail.Outcome<E, R>> k) -> switch (o) {
            case Fail.Success<E, A> success -> k.apply(success.value());
            case Fail.Failure<E, A> failure -> new Fail.Failure<>(failure.error());
        });
    }

    /** A value with what was written on the way to it: the writer monad's. */
    public record Written<W, A>(A value, List<W> log) {
        public Written {
            log = List.copyOf(log);
        }
    }

    /**
     * THE WRITER MONAD, reified: the body's value, with everything written by the {@link #get}s and {@link #tell}s
     * in it, in order.
     * <pre>{@code
     * Written<String, Integer> w = Reflection.written(fx, p -> { tell(p, "start"); return get(p, step(1)) + 1; });
     * }</pre>
     */
    public static <W, R> Written<W, R> written(Effects effects, Function<Delimited.Prompt<Written<W, R>>, R> body) {
        return reify(effects, value -> new Written<>(value, List.of()), body);
    }

    /** The value of {@code m}, under {@link #written}: what it wrote, written. */
    public static <W, A, R> A get(Delimited.Prompt<Written<W, R>> prompt, Written<W, A> m) {
        return reflect(prompt, m, (Written<W, A> w, Function<A, Written<W, R>> k) -> {
            var rest = k.apply(w.value());
            var log = new ArrayList<W>(w.log());
            log.addAll(rest.log());
            return new Written<>(rest.value(), log);
        });
    }

    /** {@code entry} written, under {@link #written}. */
    public static <W, R> void tell(Delimited.Prompt<Written<W, R>> prompt, W entry) {
        get(prompt, new Written<W, Void>(null, List.of(entry)));
    }

    /**
     * THE STREAM MONAD, reified: the body's values for every way the elements of the streams it reflects go, in
     * order. Its bind resumes the rest once for each element, so it is multi-shot.
     * <pre>{@code
     * Stream<List<Integer>> triples = Reflection.stream(fx, (Prompt<Stream<List<Integer>>> p) -> {
     *     int a = Reflection.each(p, IntStream.rangeClosed(1, 10).boxed());
     *     int b = Reflection.each(p, IntStream.rangeClosed(a, 10).boxed());
     *     int c = Reflection.each(p, IntStream.rangeClosed(b, 15).boxed());
     *     if (a * a + b * b != c * c) Reflection.none(p);
     *     return List.of(a, b, c);
     * });
     * }</pre>
     */
    public static <R> Stream<R> stream(MultiEffects effects, Function<Delimited.Prompt<Stream<R>>, R> body) {
        return reify(effects, Stream::of, body);
    }

    /**
     * Each element of {@code elements}, the rest of the body run for it. The bind is EAGER: the rest is resumed for
     * every element here, while the reflect's clause runs (a continuation is resumed only then), its streams then
     * joined; {@code Stream.flatMap} would resume it later, when the stream is consumed.
     */
    public static <A, R> A each(Delimited.Prompt<Stream<R>> prompt, Stream<A> elements) {
        return reflect(prompt, elements,
            (Stream<A> s, Function<A, Stream<R>> k) -> s.map(k).toList().stream().flatMap(Function.identity()));
    }

    /** No element: the rest of the body dropped, this way gives no value. */
    public static <R> void none(Delimited.Prompt<Stream<R>> prompt) {
        each(prompt, Stream.<Void>empty());
    }
}
