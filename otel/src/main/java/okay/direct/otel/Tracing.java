package okay.direct.otel;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import java.util.function.Function;
import okay.direct.Abandoned;
import okay.direct.DetachedEffects;
import okay.direct.Effect;
import okay.direct.Effects;
import okay.direct.Observer;

/**
 * TRACING with OpenTelemetry, and no change to a program: each operation performed through a capability of
 * {@link #traced} effects is a span, from its {@code perform} to its answer — so it covers the handler's work,
 * a statement's, a service's, a wait. Named {@code Effect.Operation} ({@code Sql.Update}, {@code State.Get}); a
 * failure answered to the operation (a failed future at its {@code Async.await}) is its span's, recorded; a rest
 * dropped — by its handler, a handler further out, or a handler's clause that failed — ends its span marked
 * {@code okay.abandoned}.
 *
 * <p>An operation is data, and may hold what must not leave the program: it is written into its span only as
 * {@code describe} says, nothing by default. A span's parent is the context current at the {@code perform}, as the
 * application's own span of a request; the spans of operations are not made current, as a body may go on on another
 * thread.
 */
public final class Tracing {
    private Tracing() {}

    /** {@code effects}, each operation a span of {@code tracer}; operations not written into their spans. */
    public static Effects traced(Effects effects, Tracer tracer) {
        return Observer.watch(effects, observer(tracer, op -> null));
    }

    /** The same, each operation written into its span, as {@code okay.op}, as {@code describe} says (null: not). */
    public static Effects traced(Effects effects, Tracer tracer, Function<Effect.Op<?, ?>, String> describe) {
        return Observer.watch(effects, observer(tracer, describe));
    }

    /** {@code effects} with detached continuations, traced: an {@code Async} run's awaits spans too, a wait each. */
    public static DetachedEffects traced(DetachedEffects effects, Tracer tracer) {
        return Observer.watch(effects, observer(tracer, op -> null));
    }

    /** The same, each operation written into its span as {@code describe} says. */
    public static DetachedEffects traced(DetachedEffects effects, Tracer tracer,
            Function<Effect.Op<?, ?>, String> describe) {
        return Observer.watch(effects, observer(tracer, describe));
    }

    /** The observer making the spans: to combine with another. */
    public static Observer observer(Tracer tracer, Function<Effect.Op<?, ?>, String> describe) {
        return new Observer() {
            @Override
            public <X> X observe(Seen<X> seen) {
                var operation = seen.op().getClass().getSimpleName();
                Span span = tracer.spanBuilder(seen.effect() + "." + operation)
                    .setAttribute("okay.effect", seen.effect())
                    .setAttribute("okay.operation", operation)
                    .startSpan();
                var description = describe.apply(seen.op());
                if (description != null) span.setAttribute("okay.op", description);
                try {
                    return seen.proceed();
                } catch (Abandoned abandoned) {
                    span.setAttribute("okay.abandoned", true);
                    throw abandoned;
                } catch (RuntimeException | Error failure) {
                    span.recordException(failure);
                    span.setStatus(StatusCode.ERROR, String.valueOf(failure.getMessage()));
                    throw failure;
                } finally {
                    span.end();
                }
            }
        };
    }
}
