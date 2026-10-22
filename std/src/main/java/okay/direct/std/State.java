package okay.direct.std;

import okay.direct.Answer;
import okay.direct.Body;
import okay.direct.Effect;
import okay.direct.Effect.Op;
import okay.direct.Effects;
import okay.direct.StatefulHandler;

/** A state of type S, read and written. */
public sealed interface State<S> {
    record Get<S>() implements State<S>, Op<State<S>, S> {}
    record Put<S>(S value) implements State<S>, Op<State<S>, Void> {}

    static <S> S get(Effect<State<S>> state) {
        return state.perform(new Get<>());
    }

    static <S> void put(Effect<State<S>> state, S value) {
        state.perform(new Put<>(value));
    }

    /** The state a body ended with, and its value. */
    record Final<S, A>(S state, A value) {}

    /** The body from {@code initial}: under a multi-shot handler, the state is each resumption's own. */
    static <S, A, E extends Exception> Final<S, A> run(Effects effects, S initial, Body<State<S>, A, E> body) throws E {
        return effects.handle(new StatefulHandler<State<S>, S, A, Final<S, A>>() {
            @Override
            public Final<S, A> value(S state, A value) {
                return new Final<>(state, value);
            }

            @Override
            public S operation(S state, State<S> op, Answer<State<S>> k) {
                return switch (op) {
                    case Get<S> get -> {
                        k.answer(get, state);
                        yield state;
                    }
                    case Put<S> put -> {
                        k.answer(put, null);
                        yield put.value();
                    }
                };
            }
        }, initial, body);
    }
}
