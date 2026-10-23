package okay.direct.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import okay.direct.Effect;
import okay.direct.Effects;
import okay.direct.Handler;
import okay.direct.Implicit;
import okay.direct.Resume;
import okay.direct.TailHandler;
import okay.direct.Answer;
import okay.direct.std.Fail;
import okay.direct.std.Reader;
import okay.direct.std.State;
import org.junit.jupiter.api.Test;

/**
 * THE TECHNOLOGY COMPATIBILITY KIT: what every implementation of {@link Effects} does. An implementation's test
 * extends it and gives the implementation.
 */
public abstract class EffectsTck implements InterceptorTests, DelimitedTests, EmitsTests {
    @Override
    public abstract Effects effects();

    /** Reads a base, counts in state, fails on a negative result: three effects, no monad. */
    static int program(Effect<Reader<Integer>> reader, Effect<State<Integer>> state, Effect<Fail<String>> fail,
            boolean heads) {
        int base = Reader.ask(reader);
        State.put(state, State.get(state) + 1);
        int result = heads ? base : -base;
        return result < 0 ? Fail.raise(fail, "negative: " + result) : result;
    }

    @Test
    void handlersNestInAnyOrder() {
        var fx = effects();
        var heads = Reader.run(fx, 42, reader -> State.run(fx, 0, state -> Fail.<String, Integer, RuntimeException>attempt(fx,
            fail -> program(reader, state, fail, true))));
        assertEquals(new State.Final<>(1, new Fail.Success<String, Integer>(42)), heads);

        var tails = Fail.<String, State.Final<Integer, Integer>, RuntimeException>attempt(fx, fail -> State.run(fx, 0,
            state -> Reader.run(fx, 42, reader -> program(reader, state, fail, false))));
        assertEquals(new Fail.Failure<String, State.Final<Integer, Integer>>("negative: -42"), tails);
    }

    @Test
    void theErrorsTypeGivenAsAClassTypesTheBodyWithNoTypedLambda() {
        var fx = effects();
        var ok = Reader.run(fx, 42, reader -> State.run(fx, 0, state ->
            Fail.attempt(fx, String.class, fail -> program(reader, state, fail, true))));
        assertEquals(new State.Final<>(1, new Fail.Success<String, Integer>(42)), ok);
        Fail.Outcome<String, Integer> no = Fail.attempt(fx, String.class, fail -> Fail.raise(fail, "no"));
        assertEquals(new Fail.Failure<String, Integer>("no"), no);
    }

    @Test
    void anOuterOperationPassesTheInnerHandler() {
        var fx = effects();
        var result = Reader.run(fx, 10, reader -> State.run(fx, 1, state -> Reader.ask(reader) + State.get(state)));
        assertEquals(new State.Final<>(1, 11), result);
    }

    @Test
    void operationsInLambdasAndAcrossFunctions() {
        var fx = effects();
        var result = State.run(fx, 0, state -> {
            List.of(1, 2, 3, 4).forEach(x -> State.put(state, State.get(state) + x));
            return List.of(10, 20).stream().map(x -> x + State.get(state)).toList();
        });
        assertEquals(new State.Final<>(10, List.of(20, 30)), result);
    }

    @Test
    void aClauseRunsOutsideTheBodyAndPerformsOuterEffects() {
        var fx = effects();
        // a handler of Fail that counts each failure in an outer state, and resumes nothing
        var result = State.run(fx, 0, state -> fx.handle(new Handler<Fail<String>, String, String, RuntimeException>() {
            @Override
            public String value(String value) {
                return value;
            }

            @Override
            public String operation(Fail<String> op, Resume<Fail<String>, String, RuntimeException> k) {
                State.put(state, State.get(state) + 1);
                return switch (op) {
                    case Fail.Raise<String, ?> raise -> "recovered from " + raise.error();
                };
            }
        }, fail -> Fail.<String, String>raise(fail, "boom")));
        assertEquals(new State.Final<>(1, "recovered from boom"), result);
    }

    @Test
    void aGeneralHandlerResumesAndWrapsTheRest() {
        var result = effects().handle(new Handler<Reader<Integer>, Integer, String, RuntimeException>() {
            @Override
            public String value(Integer value) {
                return "value " + value;
            }

            @Override
            public String operation(Reader<Integer> op, Resume<Reader<Integer>, String, RuntimeException> k) {
                return switch (op) {
                    case Reader.Ask<Integer> ask -> "[" + k.resume(ask, 20) + "]";
                };
            }
        }, reader -> Reader.ask(reader) + Reader.ask(reader));
        assertEquals("[[value 40]]", result);
    }

    @Test
    void anAbandonedRestUnwindsAndRunsItsFinallyBlocks() {
        var fx = effects();
        var cleaned = new AtomicBoolean();
        var result = State.run(fx, 0, state -> Fail.<String, Integer, RuntimeException>attempt(fx, fail -> {
            try {
                return Fail.<String, Integer>raise(fail, "boom");
            } finally {
                cleaned.set(true);
                State.put(state, 7); // an outer effect, still handled while unwinding
            }
        }));
        assertTrue(cleaned.get());
        assertEquals(new State.Final<>(7, new Fail.Failure<String, Integer>("boom")), result);
    }

    @Test
    void continuationsAreOneShot() {
        var fx = effects();
        assertThrows(IllegalStateException.class, () -> fx.handle(new Handler<Reader<Boolean>, Boolean, Boolean, RuntimeException>() {
            @Override
            public Boolean value(Boolean value) {
                return value;
            }

            @Override
            public Boolean operation(Reader<Boolean> op, Resume<Reader<Boolean>, Boolean, RuntimeException> k) {
                return switch (op) {
                    case Reader.Ask<Boolean> ask -> k.resume(ask, true) && k.resume(ask, false);
                };
            }
        }, Reader::ask));
    }

    @Test
    void aCapabilityDoesNotOutliveItsHandler() {
        var escaped = new AtomicReference<Effect<Reader<Integer>>>();
        Reader.run(effects(), 1, reader -> {
            escaped.set(reader);
            return 0;
        });
        assertThrows(IllegalStateException.class, () -> Reader.ask(escaped.get()));
    }

    @Test
    void aCapabilityIsConfinedToItsBodysThread() throws InterruptedException {
        var failure = Reader.run(effects(), 1, reader -> {
            var thrown = new AtomicReference<Throwable>();
            var other = Thread.ofPlatform().start(() -> {
                try {
                    Reader.ask(reader);
                } catch (Throwable t) {
                    thrown.set(t);
                }
            });
            other.join(); // InterruptedException: a checked exception of the body, thrown on by run
            return thrown.get();
        });
        assertInstanceOf(IllegalStateException.class, failure);
    }

    @Test
    void aClauseCannotPerformItsOwnEffect() {
        var fx = effects();
        var own = new AtomicReference<Effect<Reader<Integer>>>();
        assertThrows(IllegalStateException.class, () -> fx.handle(new Handler<Reader<Integer>, Integer, Integer, RuntimeException>() {
            @Override
            public Integer value(Integer value) {
                return value;
            }

            @Override
            public Integer operation(Reader<Integer> op, Resume<Reader<Integer>, Integer, RuntimeException> k) {
                return Reader.ask(own.get()); // the clause runs outside the body: its own capability is not usable here
            }
        }, reader -> {
            own.set(reader);
            return Reader.ask(reader);
        }));
    }

    @Test
    void checkedExceptionsOfTheBodyPassThroughHandle() {
        var fx = effects();
        var e = assertThrows(IOException.class, () -> State.run(fx, 0, state -> {
            State.put(state, 1);
            if (State.get(state) == 1) throw new IOException("io");
            return 0;
        }));
        assertEquals("io", e.getMessage());
    }

    @Test
    void uncheckedExceptionsAndErrorsOfTheBodyPropagate() {
        var fx = effects();
        assertEquals("thrown", assertThrows(IllegalArgumentException.class, () -> State.run(fx, 0, state -> {
            State.put(state, 1);
            throw new IllegalArgumentException("thrown");
        })).getMessage());
        assertEquals("error", assertThrows(AssertionError.class, () -> State.run(fx, 0, state -> {
            State.put(state, 1);
            throw new AssertionError("error");
        })).getMessage());
    }

    @Test
    void aTailHandlerRunsInConstantStack() {
        var result = State.run(effects(), 0, state -> {
            for (int i = 0; i < 100_000; i++) State.put(state, State.get(state) + 1);
            return "done";
        });
        assertEquals(new State.Final<>(100_000, "done"), result);
    }

    /** a tail handler of Reader whose clause does what {@code clause} says */
    static <A> TailHandler<Reader<Integer>, A, A> tailReader(java.util.function.BiConsumer<Reader.Ask<Integer>, Answer<Reader<Integer>>> clause) {
        return new TailHandler<>() {
            @Override
            public A value(A value) {
                return value;
            }

            @Override
            public void operation(Reader<Integer> op, Answer<Reader<Integer>> k) {
                switch (op) {
                    case Reader.Ask<Integer> ask -> clause.accept(ask, k);
                }
            }
        };
    }

    @Test
    void aTailHandlersClauseThatThrowsEndsTheBodyAndHandleThrowsIt() {
        var fx = effects();
        var unwound = new AtomicBoolean();
        var thrown = assertThrows(IllegalArgumentException.class, () -> fx.handle(tailReader((ask, k) -> {
            throw new IllegalArgumentException("clause");
        }), (Effect<Reader<Integer>> reader) -> {
            try {
                return Reader.ask(reader);
            } finally {
                unwound.set(true);
            }
        }));
        assertEquals("clause", thrown.getMessage());
        assertTrue(unwound.get());
    }

    @Test
    void aTailHandlersClauseThatDoesNotAnswerEndsTheBody() {
        var fx = effects();
        var unwound = new AtomicBoolean();
        assertThrows(IllegalStateException.class, () -> fx.handle(tailReader((ask, k) -> {}), (Effect<Reader<Integer>> reader) -> {
            try {
                return Reader.ask(reader);
            } finally {
                unwound.set(true);
            }
        }));
        assertTrue(unwound.get());
    }

    @Test
    void aTailHandlersClauseMayPerformAnOuterEffect() {
        var fx = effects();
        var result = State.run(fx, 7, state -> fx.handle(tailReader((ask, k) -> {
            State.put(state, State.get(state) + 1);   // the clause performs the outer State, every ask
            k.answer(ask, State.get(state));
        }), (Effect<Reader<Integer>> reader) -> Reader.ask(reader) + Reader.ask(reader)));
        assertEquals(new State.Final<>(9, 17), result);
    }

    @Test
    void aProgramRunsInAVirtualThread() throws InterruptedException {
        var fx = effects();
        var out = new AtomicReference<Object>();
        Thread.ofVirtual().start(() -> {
            try {
                out.set(Reader.run(fx, 10, reader -> State.run(fx, 1, state -> {
                    Thread.sleep(5); // parks inside the body
                    return Reader.ask(reader) + State.get(state);
                })));
            } catch (Throwable t) {
                out.set(t);
            }
        }).join();
        assertEquals(new State.Final<>(1, 11), out.get());
    }

    static final Implicit<State<Integer>> COUNTER = Implicit.newInstance();
    static final Implicit<State<Integer>> OTHER = Implicit.newInstance();
    static final Implicit<Reader<Integer>> ENV = Implicit.newInstance();

    /** No capability passed: each found by its key. */
    static int addEnv() {
        State.put(COUNTER.get(), State.get(COUNTER.get()) + Reader.ask(ENV.get()));
        return State.get(COUNTER.get());
    }

    @Test
    void implicitCapabilitiesAreFoundByKeyAcrossFunctions() {
        var fx = effects();
        var result = Reader.run(fx, 5, ENV.in(() -> State.run(fx, 1, COUNTER.in(EffectsTck::addEnv))));
        assertEquals(new State.Final<>(6, 6), result);
    }

    @Test
    void twoKeysOfOneEffectAreTwoCapabilitiesAndAnInnerBindingHidesTheOuter() {
        var fx = effects();
        var result = State.run(fx, 100, COUNTER.in(() -> State.run(fx, 10, OTHER.in(() -> State.run(fx, 1, COUNTER.in(() -> {
            State.put(OTHER.get(), State.get(OTHER.get()) + 1);   // the middle one
            State.put(COUNTER.get(), State.get(COUNTER.get()) + 1); // the inner one: hides the outer
            return State.get(COUNTER.get());
        }))))));
        assertEquals(new State.Final<>(100, new State.Final<>(11, new State.Final<>(2, 2))), result);
    }

    @Test
    void aClauseSeesTheImplicitCapabilitiesOfOutsideItsBody() {
        var fx = effects();
        // the Reader's clause answers with COUNTER: the outer state, not the one bound inside its body
        var result = State.run(fx, 100, COUNTER.in(() -> fx.handle(new Handler<Reader<Integer>, State.Final<Integer, Integer>,
                State.Final<Integer, Integer>, RuntimeException>() {
            @Override
            public State.Final<Integer, Integer> value(State.Final<Integer, Integer> value) {
                return value;
            }

            @Override
            public State.Final<Integer, Integer> operation(Reader<Integer> op,
                    Resume<Reader<Integer>, State.Final<Integer, Integer>, RuntimeException> k) {
                return switch (op) {
                    case Reader.Ask<Integer> ask -> k.resume(ask, State.get(COUNTER.get()));
                };
            }
        }, ENV.in(() -> State.run(fx, 1, COUNTER.in(() -> Reader.ask(ENV.get()) + State.get(COUNTER.get())))))));
        assertEquals(new State.Final<>(100, new State.Final<>(1, 101)), result);
    }

    @Test
    void anImplicitCapabilityIsNotBoundOutsideItsBody() {
        var fx = effects();
        State.run(fx, 0, COUNTER.in(() -> 0));
        assertThrows(IllegalStateException.class, COUNTER::get);
    }
}
