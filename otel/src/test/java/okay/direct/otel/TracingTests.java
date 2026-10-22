package okay.direct.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import okay.direct.Answer;
import okay.direct.Effect;
import okay.direct.Effects;
import okay.direct.Interceptor;
import okay.direct.TailHandler;
import okay.direct.std.Fail;
import okay.direct.std.State;
import org.junit.jupiter.api.Test;

/** Every operation a span: a program traced with no change to it. */
abstract class TracingTests {
    abstract okay.direct.DetachedEffects effects();

    final InMemorySpanExporter exported = InMemorySpanExporter.create();
    final SdkTracerProvider provider = SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(exported)).build();

    List<String> names() {
        return exported.getFinishedSpanItems().stream().map(SpanData::getName).toList();
    }

    static int count(Effect<State<Integer>> state, int times) {
        for (int i = 0; i < times; i++) State.put(state, State.get(state) + 1);
        return State.get(state);
    }

    @Test
    void eachOperationIsASpan() {
        var fx = Tracing.traced(effects(), provider.get("test"));
        var result = State.run(fx, 0, (Effect<State<Integer>> state) -> count(state, 2));
        assertEquals(2, result.value());
        assertEquals(List.of("State.Get", "State.Put", "State.Get", "State.Put", "State.Get"), names());
        var first = exported.getFinishedSpanItems().getFirst();
        assertEquals("State", first.getAttributes().get(AttributeKey.stringKey("okay.effect")));
        assertEquals(null, first.getAttributes().get(AttributeKey.stringKey("okay.op"))); // not written by default
    }

    @Test
    void anOperationIsWrittenOnlyAsDescribed() {
        var fx = Tracing.traced(effects(), provider.get("test"), op -> op instanceof State.Put<?> ? "put" : null);
        State.run(fx, 0, (Effect<State<Integer>> state) -> count(state, 1));
        var described = exported.getFinishedSpanItems().stream()
            .map(span -> span.getAttributes().get(AttributeKey.stringKey("okay.op"))).toList();
        assertEquals(java.util.Arrays.asList(null, "put", null), described);
    }

    @Test
    void anOperationRefusedBeforeItsHandlerIsNoSpan() {
        var fx = Tracing.traced(effects(), provider.get("test"));
        assertThrows(IllegalStateException.class, () -> State.run(fx, 0, (Effect<State<Integer>> state) -> {
            Effect<State<Integer>> guarded = Interceptor.intercept(state,
                new Interceptor<State<Integer>, State<Integer>>() {
                    @Override
                    public void operation(State<Integer> op, Pass<State<Integer>> pass, Answer<State<Integer>> k) {
                        switch (op) {
                            case State.Get<Integer> get -> k.answer(get, pass.pass(get));
                            case State.Put<Integer> put -> {
                                if (put.value() > 1) throw new IllegalStateException("too big");
                                k.answer(put, pass.pass(put));
                            }
                        }
                    }
                });
            return count(guarded, 3);
        }));
        // the interceptor is on the traced capability, outside it: the refused Put never reached it
        assertEquals(List.of("State.Get", "State.Put", "State.Get"), names());
    }

    sealed interface Ask {
        record Question() implements Ask, Effect.Op<Ask, Integer> {}
    }

    @Test
    void anOperationWhoseHandlerFailedEndsAbandoned() {
        var fx = Tracing.traced(effects(), provider.get("test"));
        assertThrows(IllegalStateException.class, () -> fx.handle(new TailHandler<Ask, Integer, Integer>() {
            @Override
            public Integer value(Integer value) {
                return value;
            }

            @Override
            public void operation(Ask op, Answer<Ask> k) {
                throw new IllegalStateException("no answer");
            }
        }, (Effect<Ask> ask) -> ask.perform(new Ask.Question())));
        // the clause failed: the rest it was given is dropped, and unwinds
        var span = exported.getFinishedSpanItems().getFirst();
        assertEquals("Ask.Question", span.getName());
        assertEquals(true, span.getAttributes().get(AttributeKey.booleanKey("okay.abandoned")));
    }

    @Test
    void aDroppedRestEndsItsSpanAbandoned() {
        var fx = Tracing.traced(effects(), provider.get("test"));
        var outcome = Fail.attempt(fx, String.class, fail -> Fail.raise(fail, "no"));
        assertEquals(new Fail.Failure<String, Object>("no"), outcome);
        var span = exported.getFinishedSpanItems().getFirst();
        assertEquals("Fail.Raise", span.getName());
        assertEquals(true, span.getAttributes().get(AttributeKey.booleanKey("okay.abandoned")));
    }

    @Test
    void anAwaitIsASpanOfTheWaitAndAFailedFutureAnError() throws Exception {
        var fx = Tracing.traced(effects(), provider.get("test"));
        var result = okay.direct.std.Async.run(fx, async -> {
            okay.direct.std.Async.await(async, java.util.concurrent.CompletableFuture.supplyAsync(() -> 1,
                java.util.concurrent.CompletableFuture.delayedExecutor(30, java.util.concurrent.TimeUnit.MILLISECONDS)));
            try {
                return okay.direct.std.Async.await(async, java.util.concurrent.CompletableFuture.<Integer>failedFuture(
                    new IllegalStateException("down")));
            } catch (IllegalStateException e) {
                return -1;
            }
        });
        assertEquals(-1, result.get(5, java.util.concurrent.TimeUnit.SECONDS));
        var spans = exported.getFinishedSpanItems();
        assertEquals(List.of("Async.Await", "Async.Await"), names());
        var wait = spans.get(0);
        org.junit.jupiter.api.Assertions.assertTrue(wait.getEndEpochNanos() - wait.getStartEpochNanos() >= 25_000_000L);
        assertEquals(StatusCode.ERROR, spans.get(1).getStatus().getStatusCode());
        assertEquals("down", spans.get(1).getStatus().getDescription());
    }
}
