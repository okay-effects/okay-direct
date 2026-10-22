package okay.direct.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okay.direct.Effect;
import okay.direct.Effects;
import okay.direct.std.Emits;
import org.junit.jupiter.api.Test;

/** Generators: loops enumerate, an effect gives the values out; one-shot, on every implementation. */
interface EmitsTests {
    Effects effects();

    static void triples(Effect<Emits<List<Integer>>> out) {
        for (int a = 1; a <= 10; a++)
            for (int b = a; b <= 10; b++)
                for (int c = b; c <= 15; c++)
                    if (a * a + b * b == c * c) Emits.emit(out, List.of(a, b, c));
    }

    @Test
    default void aGeneratorCollected() {
        assertEquals(List.of(List.of(3, 4, 5), List.of(6, 8, 10)), Emits.collect(effects(), EmitsTests::triples));
    }

    @Test
    default void aGeneratorAsALazyStream() {
        var emitted = new AtomicInteger();
        var first = Emits.<Integer>stream(effects(), out -> {
            for (int i = 1; ; i++) {
                emitted.incrementAndGet();
                Emits.emit(out, i * i);
            }
        }).filter(x -> x > 50).findFirst();
        assertEquals(64, first.orElseThrow());
        assertTrue(emitted.get() <= 9, "pulled only as far as needed: " + emitted.get());
        assertEquals(List.of(List.of(3, 4, 5), List.of(6, 8, 10)), Emits.stream(effects(), EmitsTests::triples).toList());
    }

    @Test
    default void aClosedStreamStopsItsBodyWhichUnwinds() throws InterruptedException {
        var cleaned = new CountDownLatch(1);
        try (var stream = Emits.<Integer>stream(effects(), out -> {
            try {
                for (int i = 0; ; i++) Emits.emit(out, i);
            } finally {
                cleaned.countDown();
            }
        })) {
            assertEquals(List.of(0, 1, 2), stream.limit(3).toList());
        }
        assertTrue(cleaned.await(5, TimeUnit.SECONDS), "the body unwound, its finally run");
    }

    @Test
    default void aFailureOfTheBodyIsTheStreams() {
        var stream = Emits.<Integer>stream(effects(), out -> {
            Emits.emit(out, 1);
            throw new IllegalStateException("generator failed");
        });
        var e = assertThrows(IllegalStateException.class, stream::toList);
        assertEquals("generator failed", e.getMessage());
    }
}
