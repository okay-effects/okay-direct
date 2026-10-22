package okay.direct.std;

import okay.direct.Body;
import okay.direct.Effect;
import okay.direct.Effect.Op;
import okay.direct.Effects;
import okay.direct.Handler;
import okay.direct.Resume;

/** Failure with an error of type E: the rest of the body dropped. */
public sealed interface Fail<E> {
    /** Answered by any X: never resumed. */
    record Raise<E, X>(E error) implements Fail<E>, Op<Fail<E>, X> {}

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
            public Outcome<E, A> value(A value) {
                return new Success<>(value);
            }

            @Override
            public Outcome<E, A> operation(Fail<E> op, Resume<Fail<E>, Outcome<E, A>, T> k) {
                return switch (op) {
                    case Raise<E, ?> raise -> new Failure<>(raise.error());
                };
            }
        }, body);
    }
}
