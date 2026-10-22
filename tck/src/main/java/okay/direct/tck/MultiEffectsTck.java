package okay.direct.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import okay.direct.MultiEffects;
import okay.direct.std.Delimited;
import okay.direct.std.Delimited.Prompt;
import okay.direct.std.Reflection;
import okay.direct.std.Choose;
import okay.direct.std.Fail;
import okay.direct.std.State;
import org.junit.jupiter.api.Test;

/** What a multi-shot implementation does too: a continuation resumed more than once. */
public abstract class MultiEffectsTck extends EffectsTck {
    @Override
    public abstract MultiEffects effects();

    @Test
    void chooseResumesTheRestBothWays() {
        var all = Choose.all(effects(), choose -> List.of(Choose.flip(choose), Choose.flip(choose)));
        assertEquals(List.of(List.of(true, true), List.of(true, false), List.of(false, true), List.of(false, false)), all);
    }

    @Test
    void eachBranchHasItsOwnLocalsAndAllShareTheHeap() {
        var heap = new ArrayList<String>();
        var all = Choose.all(effects(), choose -> {
            int local = 10;
            local += Choose.flip(choose) ? 1 : 2;
            local += Choose.flip(choose) ? 100 : 200;
            heap.add("local=" + local);
            return local;
        });
        assertEquals(List.of(111, 211, 112, 212), all);
        assertEquals(List.of("local=111", "local=211", "local=112", "local=212"), heap);
    }

    @Test
    void aStateInsideTheChoiceIsEachBranchsOwn() {
        var fx = effects();
        var all = Choose.all(fx, choose -> State.run(fx, 0, state -> {
            State.put(state, State.get(state) + 1);
            boolean heads = Choose.flip(choose);
            State.put(state, State.get(state) + 10);
            return heads;
        }));
        assertEquals(List.of(new State.Final<>(11, true), new State.Final<>(11, false)), all);
    }

    @Test
    void aStateOutsideTheChoiceIsThreadedThroughTheBranches() {
        var fx = effects();
        var result = State.run(fx, 0, state -> Choose.all(fx, choose -> {
            Choose.flip(choose);
            State.put(state, State.get(state) + 1);
            return State.get(state);
        }));
        assertEquals(new State.Final<>(2, List.of(1, 2)), result);
    }

    @Test
    void aFailureInsideABranchIsThatBranchs() {
        var fx = effects();
        var all = Choose.all(fx, choose -> Fail.<String, Integer, RuntimeException>attempt(fx,
            fail -> Choose.flip(choose) ? 1 : Fail.<String, Integer>raise(fail, "tails")));
        assertEquals(List.of(new Fail.Success<String, Integer>(1), new Fail.Failure<String, Integer>("tails")), all);
    }

    @Test
    void anOuterChoiceInsideAnInnerOneForksTheInnerToo() {
        var fx = effects();
        // flip(outer) inside the inner choice: its rest runs to the outer handler through the inner one, so each
        // outer branch finishes the inner choice its own way
        var all = Choose.all(fx, outer -> {
            boolean a = Choose.flip(outer);
            return Choose.all(fx, inner -> a + "," + Choose.flip(inner) + "," + Choose.flip(outer));
        });
        assertEquals(List.of(
            List.of("true,true,true", "true,false,true"), List.of("true,true,true", "true,false,false"),
            List.of("true,true,false", "true,false,true"), List.of("true,true,false", "true,false,false"),
            List.of("false,true,true", "false,false,true"), List.of("false,true,true", "false,false,false"),
            List.of("false,true,false", "false,false,true"), List.of("false,true,false", "false,false,false")), all);
    }

    /** The list monad's reflect: its bind resumes the rest once for each element. */
    static <A, R> A each(Prompt<List<R>> prompt, List<A> xs) {
        return Reflection.reflect(prompt, xs, (List<A> list, java.util.function.Function<A, List<R>> k) ->
            list.stream().flatMap(x -> k.apply(x).stream()).toList());
    }

    @Test
    void aMultiShotRestResumedTwice() {
        int result = Delimited.reset(effects(), (Prompt<Integer> p) -> 1 + p.<Integer>shift0(k -> k.resume(10) + k.resume(100)));
        assertEquals(112, result);
    }

    @Test
    void reflectionOfTheListMonad() {
        List<Integer> sums = Reflection.reify(effects(), List::of, (Prompt<List<Integer>> p) -> each(p, List.of(1, 2)) + each(p, List.of(10, 20)));
        assertEquals(List.of(11, 21, 12, 22), sums);
    }

    @Test
    void reflectionOfTheListMonadWithAStateInside() {
        var fx = effects();
        List<State.Final<Integer, Integer>> all = Reflection.reify(fx, List::of, (Prompt<List<State.Final<Integer, Integer>>> p) ->
            State.run(fx, 0, state -> {
                State.put(state, State.get(state) + each(p, List.of(1, 2)));
                State.put(state, State.get(state) * 10);
                return State.get(state);
            }));
        assertEquals(List.of(new State.Final<>(10, 10), new State.Final<>(20, 20)), all);
    }

    @Test
    void reflectionOfStreams() {
        Stream<Integer> sums = Reflection.stream(effects(),
            (Prompt<Stream<Integer>> p) -> Reflection.each(p, Stream.of(1, 2)) + Reflection.each(p, Stream.of(10, 20)));
        assertEquals(List.of(11, 21, 12, 22), sums.toList());
    }

    @Test
    void pythagoreanTriplesByStreamReflection() {
        Stream<List<Integer>> triples = Reflection.stream(effects(), (Prompt<Stream<List<Integer>>> p) -> {
            int a = Reflection.each(p, IntStream.rangeClosed(1, 10).boxed());
            int b = Reflection.each(p, IntStream.rangeClosed(a, 10).boxed());
            int c = Reflection.each(p, IntStream.rangeClosed(b, 15).boxed());
            if (a * a + b * b != c * c) Reflection.none(p);
            return List.of(a, b, c);
        });
        // a <= b <= 10, c <= 15: (5, 12, 13) and (9, 12, 15) have b over 10
        assertEquals(List.of(List.of(3, 4, 5), List.of(6, 8, 10)), triples.toList());
    }
}
