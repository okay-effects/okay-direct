package okay.direct.std;

import java.util.ArrayList;
import java.util.List;
import okay.direct.Body;
import okay.direct.Effect;
import okay.direct.Effect.Op;
import okay.direct.MultiEffects;
import okay.direct.MultiHandler;
import okay.direct.MultiResume;

/** A choice of two ways: its handler resumes the rest twice, so it runs only on a {@link MultiEffects}. */
public sealed interface Choose {
    record Flip() implements Choose, Op<Choose, Boolean> {}

    static boolean flip(Effect<Choose> choose) {
        return choose.perform(new Flip());
    }

    /** Every way the choices can go: the body's values, all of them. */
    static <A, E extends Exception> List<A> all(MultiEffects effects, Body<Choose, A, E> body) throws E {
        return effects.handle(new MultiHandler<Choose, A, List<A>, E>() {
            @Override
            public List<A> value(A value) {
                return List.of(value);
            }

            @Override
            public List<A> operation(Choose op, MultiResume<Choose, List<A>, E> k) throws E {
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
}
