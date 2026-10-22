package okay.direct.std;

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
 * Optional's pair is here already, typed: {@link #optional} and {@link #get}.
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
