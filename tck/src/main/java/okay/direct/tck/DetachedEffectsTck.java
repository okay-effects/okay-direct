package okay.direct.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import okay.direct.Answer;
import okay.direct.Body;
import okay.direct.DetachedEffects;
import okay.direct.DetachedHandler;
import okay.direct.Effect;
import okay.direct.Effect.Op;
import okay.direct.Effects;
import okay.direct.Later;
import okay.direct.TailHandler;
import okay.direct.std.Async;
import okay.direct.std.Fail;
import org.junit.jupiter.api.Test;

/** What an implementation with detached continuations does too: a rest resumed after its clause, from anywhere. */
public abstract class DetachedEffectsTck extends EffectsTck {
    @Override
    public abstract DetachedEffects effects();

    static <X> CompletableFuture<X> later(X value) {
        return CompletableFuture.supplyAsync(() -> value, CompletableFuture.delayedExecutor(20, TimeUnit.MILLISECONDS));
    }

    @Test
    void awaitWithoutWaiting() throws Exception {
        var sum = Async.run(effects(), async -> Async.await(async, later(1)) + Async.await(async, later(2)));
        assertEquals(3, sum.get(5, TimeUnit.SECONDS));
    }

    @Test
    void aFailedFutureIsThrownAtItsAwait() throws Exception {
        var message = Async.run(effects(), async -> {
            try {
                return "got " + Async.await(async, CompletableFuture.<Integer>failedFuture(new IllegalStateException("down")));
            } catch (IllegalStateException e) {
                return e.getMessage();
            }
        });
        assertEquals("down", message.get(5, TimeUnit.SECONDS));
    }

    sealed interface Prices {
        record Price(String item) implements Prices, Op<Prices, Integer> {}
    }

    static int total(Effect<Prices> prices, Effect<Fail<String>> fail, List<String> cart) {
        return cart.stream()
            .mapToInt(item -> {
                int price = prices.perform(new Prices.Price(item));
                return price >= 0 ? price : Fail.raise(fail, "no price for " + item);
            })
            .sum();
    }

    /** Prices asked of an asynchronous service, awaited: no thread waits. */
    static <A> A pricesFrom(Effects fx, Effect<Async> async, Map<String, Integer> table, Body<Prices, A, RuntimeException> body) {
        return fx.handle(new TailHandler<Prices, A, A>() {
            public A value(A result) {
                return result;
            }

            public void operation(Prices op, Answer<Prices> k) {
                switch (op) {
                    case Prices.Price p -> k.answer(p, Async.await(async, later(table.getOrDefault(p.item(), -1))));
                }
            }
        }, body);
    }

    @Test
    void theShopAwaitingAnAsynchronousService() throws Exception {
        var fx = effects();
        var table = Map.of("tea", 30, "cake", 50, "jam", 20);
        var ok = Async.run(fx, async -> Fail.attempt(fx, (Effect<Fail<String>> fail) ->
            pricesFrom(fx, async, table, prices -> total(prices, fail, List.of("tea", "cake", "jam")))));
        var missing = Async.run(fx, async -> Fail.attempt(fx, (Effect<Fail<String>> fail) ->
            pricesFrom(fx, async, table, prices -> total(prices, fail, List.of("tea", "coffee", "cake")))));
        assertEquals(new Fail.Success<String, Integer>(100), ok.get(5, TimeUnit.SECONDS));
        assertEquals(new Fail.Failure<String, Integer>("no price for coffee"), missing.get(5, TimeUnit.SECONDS));
    }

    sealed interface Ask {
        record Question() implements Ask, Op<Ask, Integer> {}
    }

    @Test
    void aDetachedRestIsResumedOnce() throws Exception {
        var kept = new AtomicReference<Later<Ask>>();
        var asked = new AtomicReference<Ask.Question>();
        var result = effects().start(new DetachedHandler<Ask, Integer, Integer>() {
            public Integer value(Integer value) {
                return value;
            }

            public void operation(Ask op, Later<Ask> k) {
                switch (op) {
                    case Ask.Question q -> {
                        asked.set(q);
                        kept.set(k);
                    }
                }
            }
        }, (Effect<Ask> ask) -> ask.perform(new Ask.Question()) * 2);
        kept.get().answer(asked.get(), 21);
        assertEquals(42, result.get(5, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> kept.get().answer(asked.get(), 1));
    }

    @Test
    void aDroppedRestUnwindsAndTheFutureIsCancelled() {
        var cleaned = new AtomicBoolean();
        var kept = new AtomicReference<Later<Ask>>();
        var result = effects().start(new DetachedHandler<Ask, Integer, Integer>() {
            public Integer value(Integer value) {
                return value;
            }

            public void operation(Ask op, Later<Ask> k) {
                kept.set(k);
            }
        }, (Effect<Ask> ask) -> {
            try {
                return ask.perform(new Ask.Question());
            } finally {
                cleaned.set(true);
            }
        });
        kept.get().drop();
        assertTrue(cleaned.get());
        assertThrows(CancellationException.class, () -> result.get(5, TimeUnit.SECONDS));
    }
}
