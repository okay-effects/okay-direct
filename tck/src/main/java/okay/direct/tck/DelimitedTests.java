package okay.direct.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import okay.direct.Effects;
import okay.direct.std.Delimited;
import okay.direct.std.Fail;
import okay.direct.std.Delimited.Prompt;
import okay.direct.std.Reflection;
import okay.direct.std.State;
import org.junit.jupiter.api.Test;

/** Delimited continuations and monadic reflection, one-shot: on every implementation. */
interface DelimitedTests {
    Effects effects();

    static <A, R> A get(Prompt<Optional<R>> prompt, Optional<A> m) {
        return Reflection.get(prompt, m);
    }

    /** A little typed JSON, for the path below. */
    sealed interface Json {
        default Optional<Json> field(String key) {
            return this instanceof Obj o ? Optional.ofNullable(o.fields().get(key)) : Optional.empty();
        }

        default Optional<String> str() {
            return this instanceof Str s ? Optional.of(s.value()) : Optional.empty();
        }
    }

    record Obj(Map<String, Json> fields) implements Json {}

    record Str(String value) implements Json {}

    @Test
    default void optionalReflectedWithItsTypesInferred() {
        var fx = effects();
        Json doc = new Obj(Map.of("user", new Obj(Map.of("address",
            new Obj(Map.of("city", new Str("Kyiv")))))));
        var visited = new ArrayList<String>();
        Optional<String> city = Reflection.optional(fx, p -> Reflection.get(p,
            Stream.of("user", "address", "city").reduce(doc, (node, key) -> {
                visited.add(key);
                return Reflection.get(p, node.field(key));
            }, (a, b) -> a).str()));
        assertEquals(Optional.of("Kyiv"), city);
        visited.clear();
        Optional<String> number = Reflection.optional(fx, p -> Reflection.get(p,
            Stream.of("user", "phone", "number").reduce(doc, (node, key) -> {
                visited.add(key);
                return Reflection.get(p, node.field(key));
            }, (a, b) -> a).str()));
        assertEquals(Optional.empty(), number);
        assertEquals(List.of("user", "phone"), visited); // the rest of the fold not run
        assertEquals(Optional.empty(), Reflection.optional(fx, p -> (String) null)); // null is empty, as in map
    }

    @Test
    default void shift0GivesTheRestUpToItsReset() {
        int result = Delimited.reset(effects(), (Prompt<Integer> p) -> 1 + p.<Integer>shift0(k -> k.resume(10) * 2));
        assertEquals(22, result);
    }

    @Test
    default void shift0DroppingTheRestAnswersForTheReset() {
        var cleaned = new AtomicBoolean();
        int result = Delimited.reset(effects(), (Prompt<Integer> p) -> {
            try {
                return 1 + p.<Integer>shift0(k -> 42);
            } finally {
                cleaned.set(true);
            }
        });
        assertEquals(42, result);
        assertTrue(cleaned.get());
    }

    @Test
    default void shift0ToAnOuterPromptPassesTheInnerReset() {
        var fx = effects();
        String result = Delimited.reset(fx, (Prompt<String> outer) -> "outer(" + Delimited.reset(fx,
            (Prompt<String> inner) -> "inner(" + outer.<String>shift0(k -> "caught[" + k.resume("x") + "]") + ")") + ")");
        assertEquals("caught[outer(inner(x))]", result);
    }

    @Test
    default void aShift0sBodyRunsOutsideItsReset() {
        var fx = effects();
        // in the body of a shift0, the reset's own prompt is out of its body: refused
        assertThrows(IllegalStateException.class, () -> Delimited.reset(fx,
            (Prompt<Integer> p) -> 1 + p.<Integer>shift0(k -> k.resume(p.<Integer>shift0(k2 -> 100)))));
        // a prompt further out is in scope there: its shift0 takes the rest up to its own reset
        int result = Delimited.reset(fx, (Prompt<Integer> outer) -> 1000 + Delimited.reset(fx,
            (Prompt<Integer> p) -> 1 + p.<Integer>shift0(k -> outer.<Integer>shift0(k2 -> k2.resume(k.resume(10))))));
        assertEquals(1011, result);
    }

    @Test
    default void aOneShotRestResumedTwiceIsRefused() {
        assertThrows(IllegalStateException.class, () -> Delimited.reset(effects(),
            (Prompt<Integer> p) -> p.<Integer>shift0(k -> k.resume(1) + k.resume(2))));
    }

    @Test
    default void reflectionOfOptional() {
        var fx = effects();
        Optional<Integer> some = Reflection.reify(fx, Optional::of,
            (Prompt<Optional<Integer>> p) -> get(p, Optional.of(1)) + get(p, Optional.of(2)));
        assertEquals(Optional.of(3), some);
        var cleaned = new AtomicBoolean();
        Optional<Integer> none = Reflection.reify(fx, Optional::of, (Prompt<Optional<Integer>> p) -> {
            try {
                return get(p, Optional.of(1)) + get(p, Optional.<Integer>empty());
            } finally {
                cleaned.set(true);
            }
        });
        assertEquals(Optional.empty(), none);
        assertTrue(cleaned.get());
    }

    @Test
    default void reflectionWithOtherEffects() {
        var fx = effects();
        var result = State.run(fx, 10, state -> Reflection.reify(fx, Optional::of, (Prompt<Optional<Integer>> p) -> {
            int a = get(p, Optional.of(State.get(state)));
            State.put(state, a + 1);
            return a + get(p, Optional.of(State.get(state)));
        }));
        assertEquals(new State.Final<>(11, Optional.of(21)), result);
    }

    static Fail.Outcome<String, Integer> parse(String s) {
        try {
            return new Fail.Success<>(Integer.parseInt(s));
        } catch (NumberFormatException e) {
            return new Fail.Failure<>("not a number: " + s);
        }
    }

    @Test
    default void anOutcomeReflected() {
        var fx = effects();
        Fail.Outcome<String, Integer> sum = Reflection.outcome(fx, p -> Reflection.get(p, parse("40"))
            + Reflection.get(p, parse("2")));
        assertEquals(new Fail.Success<String, Integer>(42), sum);
        var cleaned = new AtomicBoolean();
        Fail.Outcome<String, Integer> failed = Reflection.outcome(fx, p -> {
            try {
                return Reflection.get(p, parse("4o")) + Reflection.get(p, parse("2"));
            } finally {
                cleaned.set(true);
            }
        });
        assertEquals(new Fail.Failure<String, Integer>("not a number: 4o"), failed);
        assertTrue(cleaned.get());
    }

    static Reflection.Written<String, Integer> step(int n) {
        return new Reflection.Written<>(n * 10, List.of("step " + n));
    }

    @Test
    default void aWriterReflected() {
        Reflection.Written<String, Integer> written = Reflection.written(effects(), p -> {
            Reflection.tell(p, "start");
            int total = 0;
            for (int i = 1; i <= 3; i++) total += Reflection.get(p, step(i));
            Reflection.tell(p, "end");
            return total;
        });
        assertEquals(new Reflection.Written<>(60, List.of("start", "step 1", "step 2", "step 3", "end")), written);
    }
}
