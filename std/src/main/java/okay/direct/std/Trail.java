package okay.direct.std;

import java.util.ArrayList;
import java.util.List;
import okay.direct.Answer;
import okay.direct.Body;
import okay.direct.Effect;
import okay.direct.Effect.Op;
import okay.direct.Effects;
import okay.direct.StatefulHandler;

/** An audit trail: lines recorded in order. Handled outside the interceptors that record to it. */
public sealed interface Trail {
    record Record(String line) implements Trail, Op<Trail, Void> {}

    static void record(Effect<Trail> trail, String line) {
        trail.perform(new Record(line));
    }

    /** The lines a body recorded, and its value. */
    record Kept<A>(List<String> lines, A value) {}

    /** The lines kept as a state: each multi-shot resumption's own. */
    static <A, E extends Exception> Kept<A> keep(Effects effects, Body<Trail, A, E> body) throws E {
        return effects.handle(new StatefulHandler<Trail, List<String>, A, Kept<A>>() {
            @Override
            public Kept<A> value(List<String> lines, A value) {
                return new Kept<>(lines, value);
            }

            @Override
            public List<String> operation(List<String> lines, Trail op, Answer<Trail> k) {
                return switch (op) {
                    case Record record -> {
                        k.answer(record, null);
                        var next = new ArrayList<>(lines);
                        next.add(record.line());
                        yield List.copyOf(next);
                    }
                };
            }
        }, List.of(), body);
    }
}
