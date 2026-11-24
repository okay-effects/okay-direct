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
import java.util.function.Function;

/** THROWS: a failure with an error of type E; the rest of the body dropped, unwound, before it is answered. */
public sealed interface Throws<E> {
    /** Answered by any X: never resumed. */
    record Raise<E, X>(E error) implements Throws<E>, Op<Throws<E>, X, Raise<E, X>> {}

    static <E, X> X raise(Effect<Throws<E>> fail, E error) {
        return fail.perform(new Raise<E, X>(error));
    }

    sealed interface Result<E, A> {}
    record Ok<E, A>(A value) implements Result<E, A> {}
    record Err<E, A>(E error) implements Result<E, A> {}

    /**
     * The same, the error's type given as a class: Java infers the type of the body's capability from it, so the
     * lambda needs no type — {@code Throws.run(fx, String.class, fail -> ...)}. For an error of a generic type, type
     * the lambda's parameter instead, {@code (Effect<Throws<List<String>>> fail) -> ...}.
     */
    static <E, A, T extends Exception> Result<E, A> run(Effects effects, Class<E> errors, Body<Throws<E>, A, T> body)
            throws T {
        return run(effects, body);
    }

    static <E, A, T extends Exception> Result<E, A> run(Effects effects, Body<Throws<E>, A, T> body) throws T {
        return effects.handle(new Handler<Throws<E>, A, Result<E, A>, T>() {
            @Override
            public Result<E, A> ret(A value) {
                return new Ok<>(value);
            }

            @Override
            public Result<E, A> on(Throws<E> op, Resume<Throws<E>, Result<E, A>, T> k) {
                return switch (op) {
                    case Raise<E, ?> raise -> new Err<>(raise.error());
                };
            }
        }, body);
    }

    /** In a row: the program's first place a failure, its outcome a value; a program of the rest. */
    static <E, R, A> Program<Result<E, A>, R> run(Program<A, Eff<Throws<E>, R>> program) {
        return outer -> run(outer.effects(), (Effect<Throws<E>> fail) -> program.run(Fx.with(fail, outer)));
    }

    /** The same, the error's type given as a class (for a lambda with no types). */
    static <E, R, A> Program<Result<E, A>, R> run(Class<E> errors, Program<A, Eff<Throws<E>, R>> program) {
        return run(program);
    }

    /** The body's value, or what {@code rescue} makes of its failure: the rest dropped, unwound, first. */
    static <E, A, T extends Exception> A recover(Effects effects, Function<? super E, ? extends A> rescue,
            Body<Throws<E>, A, T> body) throws T {
        return switch (run(effects, body)) {
            case Ok<E, A> ok -> ok.value();
            case Err<E, A> err -> rescue.apply(err.error());
        };
    }

    /** The same, the error's type given as a class (for a lambda with no types). */
    static <E, A, T extends Exception> A recover(Effects effects, Class<E> errors,
            Function<? super E, ? extends A> rescue, Body<Throws<E>, A, T> body) throws T {
        return recover(effects, rescue, body);
    }

    /** In a row: the program's first place a failure, recovered from by {@code rescue}; a program of the rest. */
    static <E, R, A> Program<A, R> recover(Function<? super E, ? extends A> rescue,
            Program<A, Eff<Throws<E>, R>> program) {
        return outer -> recover(outer.effects(), rescue,
            (Effect<Throws<E>> fail) -> program.run(Fx.with(fail, outer)));
    }

    /** A call that throws E: its value, or its exception raised as a failure of the row. */
    @FunctionalInterface
    interface Throwing<A, E extends Exception> {
        A call() throws E;
    }

    /** Java's checked exceptions INTO a row: the call's exception of type E becomes a failure; others pass. */
    static <E extends Exception, A> A catching(Effect<Throws<E>> fail, Class<E> type, Throwing<A, ? extends E> call) {
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
    static <E extends Exception, A> A rethrow(Effects effects, Program<A, Eff<Throws<E>, Pure>> program) throws E {
        return switch (Fx.run(effects, run(program))) {
            case Ok<E, A> success -> success.value();
            case Err<E, A> failure -> throw failure.error();
        };
    }
}
