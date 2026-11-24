package okay.direct.std;

import okay.direct.Answer;
import okay.direct.Body;
import okay.effect.Eff;
import okay.direct.Effect;
import okay.effect.Op;
import okay.direct.Fx;
import okay.direct.Effects;
import okay.direct.TailHandler;
import okay.direct.Program;

/** An environment of type E, read. */
public sealed interface Reader<E> {
    record Ask<E>() implements Reader<E>, Op<Reader<E>, E, Ask<E>> {}

    static <E> E ask(Effect<Reader<E>> reader) {
        return reader.perform(new Ask<>());
    }

    static <E, A, T extends Exception> A run(Effects effects, E env, Body<Reader<E>, A, T> body) throws T {
        return effects.handle(new TailHandler<Reader<E>, A, A>() {
            @Override
            public A value(A value) {
                return value;
            }

            @Override
            public void operation(Reader<E> op, Answer<Reader<E>> k) {
                switch (op) {
                    case Ask<E> ask -> k.answer(ask, env);
                }
            }
        }, body);
    }

    /** In a row: the program's first place answered by {@code env}; a program of the rest. */
    static <E, R, A> Program<A, R> run(E env, Program<A, Eff<Reader<E>, R>> program) {
        return outer -> run(outer.effects(), env, reader -> program.run(Fx.with(reader, outer)));
    }
}
