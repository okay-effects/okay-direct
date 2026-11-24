package okay.direct.std;

import java.util.ArrayList;
import java.util.List;
import okay.direct.Body;
import okay.effect.Eff;
import okay.direct.Effect;
import okay.effect.Op;
import okay.direct.Fx;
import okay.direct.MultiEffects;
import okay.direct.MultiHandler;
import okay.direct.MultiResume;
import okay.direct.Program;

/** A choice of two ways: its handler resumes the rest twice, so it runs only on a {@link MultiEffects}. */
public sealed interface Choose {
    record Flip() implements Choose, Op<Choose, Boolean, Flip> {}

    static boolean flip(Effect<Choose> choose) {
        return choose.perform(new Flip());
    }

    /** Every way the choices can go: the body's values, all of them. */
    static <A, E extends Exception> List<A> all(MultiEffects effects, Body<Choose, A, E> body) throws E {
        return effects.handle(new MultiHandler<Choose, A, List<A>, E>() {
            @Override
            public List<A> ret(A value) {
                return List.of(value);
            }

            @Override
            public List<A> on(Choose op, MultiResume<Choose, List<A>, E> k) throws E {
                return switch (op) {
                    case Flip flip -> {
                        // each list made after both resumptions: a branch forked inside the second shares this
                        // frame's heap, so nothing here is changed after the first
                        var heads = k.resume(flip, true);
                        var tails = k.resume(flip, false);
                        var all = new ArrayList<A>(heads);
                        all.addAll(tails);
                        yield List.copyOf(all);
                    }
                };
            }
        }, body);
    }

    /**
     * In a row: every way the program's choices (its first place) can go; a program of the rest. Multi-shot is a
     * type: {@code effects} is the row's implementation, else the program throws {@link IllegalStateException}.
     */
    static <R, A> Program<List<A>, R> collect(MultiEffects effects, Program<A, Eff<Choose, R>> program) {
        return outer -> {
            if (outer.effects() != effects)
                throw new IllegalStateException("choices on another implementation than the row's");
            return all(effects, choose -> program.run(Fx.with(choose, outer)));
        };
    }
}
