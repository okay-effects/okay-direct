package okay.direct.tck;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import okay.direct.Answer;
import okay.effect.Eff;
import okay.direct.Effect;
import okay.effect.Op;
import okay.direct.Effects;
import okay.direct.Fx;
import okay.direct.Program;
import okay.effect.Pure;
import okay.direct.InPlace;
import okay.direct.std.Fail;
import okay.direct.std.Reader;
import okay.direct.std.State;
import org.junit.jupiter.api.Test;

/** Rows: a program's type lists its effects as nested {@code Eff}s; handlers take the places in order. */
interface RowsTests {
    Effects effects();

    /** An effect of the tests: a line logged. */
    sealed interface Log {
        record Line(String text) implements Log, Op<Log, Void, Line> {}
    }

    static void log(Effect<Log> log, String text) {
        log.perform(new Log.Line(text));
    }

    /** The lines a program logged, and its value. */
    record Logged<A>(List<String> lines, A value) {}

    static <R, A> Program<Logged<A>, R> logged(Program<A, Eff<Log, R>> program) {
        var lines = new ArrayList<String>();
        return Fx.handle(new InPlace<Log, A, Logged<A>>() {
            @Override
            public Logged<A> ret(A value) {
                return new Logged<>(List.copyOf(lines), value);
            }

            @Override
            public void answer(Log op, Answer<Log> k) {
                switch (op) {
                    case Log.Line line -> {
                        lines.add(line.text());
                        k.answer(line, null);
                    }
                }
            }
        }, program);
    }

    /** A program of an open row: a state, a reader under it, anything under that. */
    static int step(Fx<? extends Eff<State<Integer>, ? extends Eff<Reader<Integer>, ?>>> fx) {
        Effect<State<Integer>> state = Fx.head(fx);
        int by = Reader.ask(Fx.second(fx));
        int s = State.get(state);
        State.put(state, s + by);
        return s;
    }

    /** Calls step twice, logs, and passes the rest of its row on: a program of a longer row. */
    static int twice(Fx<Eff<State<Integer>, Eff<Reader<Integer>, Eff<Log, Pure>>>> fx) {
        int a = step(fx);
        int b = step(fx);
        log(Fx.third(fx), a + "," + b);
        return a + b;
    }

    @Test
    default void aProgramOfARowRunsUnderItsHandlersInOrder() {
        var result = Fx.run(effects(), Reader.run(10, State.run(0, RowsTests::step)));
        assertEquals(new State.Final<>(10, 0), result);
    }

    @Test
    default void anOpenRowIsUsedInALongerRow() {
        var result = Fx.run(effects(), logged(Reader.run(5, State.run(1, RowsTests::twice))));
        assertEquals(new Logged<>(List.of("1,6"), new State.Final<>(11, 7)), result);
    }

    @Test
    default void twoPlacesOfOneEffectAreTwoPlaces() {
        Program<String, Eff<Reader<String>, Eff<Reader<Integer>, Pure>>> both =
                fx -> Reader.ask(Fx.head(fx)) + Reader.ask(Fx.second(fx));
        assertEquals("n=3", Fx.run(effects(), Reader.run(3, Reader.run("n=", both))));
    }

    @Test
    default void aFailureDropsTheRestAndTheOrderOfHandlersIsTheMeaning() {
        Program<Integer, Eff<Fail<String>, Eff<State<Integer>, Pure>>> p = fx -> {
            Effect<State<Integer>> state = Fx.second(fx);
            State.put(state, 1);
            Fail.raise(Fx.head(fx), "stop");
            State.put(state, 2);
            return 0;
        };
        // the state outside the failure: what was put before the failure stays
        assertEquals(new State.Final<>(1, new Fail.Failure<String, Integer>("stop")),
                Fx.run(effects(), State.run(0, Fail.attempt(String.class, p))));
    }

    @Test
    default void aRowProgramCallsBodyStyleCodeAndBodyStyleCodeCallsARowProgram() {
        // a body-style handler gives its capability to a row program through Fx.with
        var result = Fx.run(effects(), (Fx<Pure> outer) -> State.run(outer.effects(), 2,
                state -> Reader.run(outer.effects(), 3,
                        reader -> step(Fx.with(state, Fx.with(reader, outer))))));
        assertEquals(new State.Final<>(5, 2), result);
    }

    @Test
    default void aRowDoesNotOutliveItsHandlers() {
        var escaped = new AtomicReference<Fx<Eff<Reader<Integer>, Pure>>>();
        Fx.run(effects(), Reader.run(1, (Fx<Eff<Reader<Integer>, Pure>> fx) -> {
            escaped.set(fx);
            return 0;
        }));
        assertThrows(IllegalStateException.class, () -> Reader.ask(Fx.head(escaped.get())));
    }

    @Test
    default void checkedExceptionsMeetTheRowAtItsEnds() {
        Program<String, Eff<Fail<java.io.IOException>, Pure>> read = fx -> Fail.catching(Fx.head(fx),
                java.io.IOException.class, () -> {
                    throw new java.io.FileNotFoundException("no.txt");
                });
        var thrown = assertThrows(java.io.IOException.class, () -> Fail.rethrow(effects(), read));
        assertEquals("no.txt", thrown.getMessage());
        Program<String, Eff<Fail<java.io.IOException>, Pure>> fine = fx -> Fail.catching(Fx.head(fx),
                java.io.IOException.class, () -> "text");
        assertEquals("text", assertDoesNotThrow(() -> Fail.rethrow(effects(), fine)));
    }
}
