package okay.direct.std;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import okay.direct.Body;
import okay.direct.DetachedEffects;
import okay.direct.DetachedHandler;
import okay.direct.Effect;
import okay.direct.Effect.Op;
import okay.direct.Later;

/**
 * ASYNCHRONY IN DIRECT STYLE: {@code await(async, future)} is a method call that gives the future's value — and no
 * thread waits for it. The handler leaves the rest DETACHED and resumes it from the future's callback, on whatever
 * thread completes it; the body goes on there. So a run is a {@code CompletableFuture} of the body's value.
 * <pre>{@code
 * CompletableFuture<Integer> total = Async.run(fx, async ->
 *     Async.await(async, service.quote("tea")) + Async.await(async, service.quote("cake")));
 * }</pre>
 * A failed future is thrown at its await (unchecked as it is, checked in a {@code CompletionException}). Needs a
 * {@link DetachedEffects}: Loom's or threads'.
 */
public sealed interface Async {
    record Await<X>(CompletionStage<X> stage) implements Async, Op<Async, X> {}

    /** The stage's value, once it is there; the body suspended meanwhile, no thread held. */
    static <X> X await(Effect<Async> async, CompletionStage<X> stage) {
        return async.perform(new Await<>(stage));
    }

    /** The body run, its awaits resumed by their stages' callbacks: the body's value, when it ends. */
    static <A, E extends Exception> CompletableFuture<A> run(DetachedEffects effects, Body<Async, A, E> body) {
        return effects.start(new DetachedHandler<Async, A, A>() {
            @Override
            public A value(A value) {
                return value;
            }

            @Override
            public void operation(Async op, Later<Async> k) {
                switch (op) {
                    case Await<?> await -> resumeWhenDone(await, k);
                }
            }
        }, body);
    }

    private static <X> void resumeWhenDone(Await<X> await, Later<Async> k) {
        await.stage().whenComplete((value, failure) -> {
            if (failure == null) k.answer(await, value);
            else k.raise(failure instanceof CompletionException c && c.getCause() != null ? c.getCause() : failure);
        });
    }
}
