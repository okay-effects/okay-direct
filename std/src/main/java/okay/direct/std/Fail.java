package okay.direct.std;

import okay.direct.Body;
import okay.effect.Eff;
import okay.direct.Effect;
import okay.effect.Op;
import okay.direct.Fx;
import okay.direct.Effects;
import okay.direct.Handler;
import okay.direct.Resume;
import okay.direct.Program;
import okay.effect.Pure;

/** Failure with an error of type E: the rest of the body dropped. */
public sealed interface Fail<E> {
    /** Answered by any X: never resumed. */
    record Raise<E, X>(E error) implements Fail<E>, Op<Fail<E>, X, Raise<E, X>> {}

    static <E, X> X raise(Effect<Fail<E>> fail, E error) {
        return fail.perform(new Raise<E, X>(error));
    }

    sealed interface Outcome<E, A> {}
    record Success<E, A>(A value) implements Outcome<E, A> {}
    record Failure<E, A>(E error) implements Outcome<E, A> {}

    /**
     * The same, the error's type given as a class: Java infers the type of the body's capability from it, so the
     * lambda needs no type — {@code Fail.attempt(fx, String.class, fail -> ...)}. For an error of a generic type, type
     * the lambda's parameter instead, {@code (Effect<Fail<List<String>>> fail) -> ...}.
     */
    static <E, A, T extends Exception> Outcome<E, A> attempt(Effects effects, Class<E> errors, Body<Fail<E>, A, T> body)
            throws T {
        return attempt(effects, body);
    }

    static <E, A, T extends Exception> Outcome<E, A> attempt(Effects effects, Body<Fail<E>, A, T> body) throws T {
        return effects.handle(new Handler<Fail<E>, A, Outcome<E, A>, T>() {
            @Override
            public Outcome<E, A> ret(A value) {
                return new Success<>(value);
            }

            @Override
            public Outcome<E, A> on(Fail<E> op, Resume<Fail<E>, Outcome<E, A>, T> k) {
                return switch (op) {
                    case Raise<E, ?> raise -> new Failure<>(raise.error());
                };
            }
        }, body);
    }

    /** In a row: the program's first place a failure, its outcome a value; a program of the rest. */
    static <E, R, A> Program<Outcome<E, A>, R> attempt(Program<A, Eff<Fail<E>, R>> program) {
        return outer -> attempt(outer.effects(), (Effect<Fail<E>> fail) -> program.run(Fx.with(fail, outer)));
    }

    /** The same, the error's type given as a class (for a lambda with no types). */
    static <E, R, A> Program<Outcome<E, A>, R> attempt(Class<E> errors, Program<A, Eff<Fail<E>, R>> program) {
        return attempt(program);
    }

    /** A call that throws E: its value, or its exception raised as a failure of the row. */
    @FunctionalInterface
    interface Throwing<A, E extends Exception> {
        A call() throws E;
    }

    /** Java's checked exceptions INTO a row: the call's exception of type E becomes a failure; others pass. */
    static <E extends Exception, A> A catching(Effect<Fail<E>> fail, Class<E> type, Throwing<A, ? extends E> call) {
        try {
            return call.call();
        } catch (RuntimeException e) {
            if (type.isInstance(e)) return raise(fail, type.cast(e));
            throw e;
        } catch (Exception e) {
            // the call throws only E's (or unchecked ones, caught above)
            return raise(fail, type.cast(e));
        }
    }

    /** A row's failure OUT as Java's checked exception: the program run, a failure thrown. */
    static <E extends Exception, A> A rethrow(Effects effects, Program<A, Eff<Fail<E>, Pure>> program) throws E {
        return switch (Fx.run(effects, attempt(program))) {
            case Success<E, A> success -> success.value();
            case Failure<E, A> failure -> throw failure.error();
        };
    }
}
