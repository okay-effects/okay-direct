package okay.direct.std;

import java.util.function.Function;
import okay.direct.Effect;
import okay.direct.Effect.Op;
import okay.direct.Effects;
import okay.direct.Handler;
import okay.direct.MultiEffects;
import okay.direct.MultiHandler;
import okay.direct.MultiResume;
import okay.direct.Resume;

/**
 * DELIMITED CONTINUATIONS, {@code shift0} and {@code reset0} (Materzok and Biernacki), on any implementation: a reset
 * is a handler, its prompt the capability, a shift0 its one operation. The shift0's body runs outside the reset, and
 * {@code k} — the rest up to the reset — resumes under the reset again (a deep handler): so a shift0 in the body of
 * a shift0 goes to the reset further out, and reaches this one only through {@code k}. The answer type {@code R} is
 * the reset's, the same throughout (no answer-type modification).
 *
 * <p>How many times {@code k} may be resumed is a type: a reset on {@link Effects} gives a one-shot {@code k}, on a
 * {@link MultiEffects} a multi-shot one.
 */
public final class Delimited {
    private Delimited() {}

    /** The rest of the body up to its reset, given the shift0's answer: the reset's answer. */
    @FunctionalInterface
    public interface Cont<A, R> {
        R resume(A answer);
    }

    /** A shift0's body: given the rest, the reset's answer. */
    @FunctionalInterface
    public interface Shift<A, R> {
        R apply(Cont<A, R> k);
    }

    /** The prompt of a reset whose answer is R: the capability to shift0 to it. */
    public static final class Prompt<R> {
        private final Effect<Control<R>> effect;

        private Prompt(Effect<Control<R>> effect) {
            this.effect = effect;
        }

        /** The rest of the body up to this prompt's reset, as {@code k}, to {@code body}: its value the reset's. */
        public <A> A shift0(Shift<A, R> body) {
            return effect.perform(new Shift0<>(body));
        }
    }

    /** The control effect of a reset whose answer is R. */
    private sealed interface Control<R> {}

    private record Shift0<R, A>(Shift<A, R> body) implements Control<R>, Op<Control<R>, A> {
        R run(Resume<Control<R>, R, RuntimeException> k) {
            return body.apply(answer -> k.resume(this, answer));
        }
    }

    /** The body delimited, its {@code k}s one-shot. */
    public static <R> R reset(Effects effects, Function<Prompt<R>, R> body) {
        return effects.handle(new Handler<Control<R>, R, R, RuntimeException>() {
            @Override
            public R value(R value) {
                return value;
            }

            @Override
            public R operation(Control<R> op, Resume<Control<R>, R, RuntimeException> k) {
                return switch (op) {
                    case Shift0<R, ?> shift -> shift.run(k);
                };
            }
        }, (Effect<Control<R>> effect) -> body.apply(new Prompt<>(effect)));
    }

    /** The body delimited, its {@code k}s multi-shot. */
    public static <R> R reset(MultiEffects effects, Function<Prompt<R>, R> body) {
        return effects.handle(new MultiHandler<Control<R>, R, R, RuntimeException>() {
            @Override
            public R value(R value) {
                return value;
            }

            @Override
            public R operation(Control<R> op, MultiResume<Control<R>, R, RuntimeException> k) {
                return switch (op) {
                    case Shift0<R, ?> shift -> shift.run(k);
                };
            }
        }, (Effect<Control<R>> effect) -> body.apply(new Prompt<>(effect)));
    }
}
