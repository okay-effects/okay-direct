package okay.direct.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import okay.direct.Effects;
import okay.direct.std.Delimited;
import okay.direct.std.Delimited.Prompt;
import okay.direct.std.Reflection;
import okay.direct.std.State;
import org.junit.jupiter.api.Test;

/** Delimited continuations and monadic reflection, one-shot: on every implementation. */
interface DelimitedTests {
    Effects effects();

    /** Optional's reflect, written once: its value's type known here. */
    static <A, R> A get(Prompt<Optional<R>> prompt, Optional<A> m) {
        return Reflection.reflect(prompt, m, Optional::flatMap);
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
}
