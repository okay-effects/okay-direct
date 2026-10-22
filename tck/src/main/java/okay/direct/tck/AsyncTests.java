package okay.direct.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import okay.direct.DetachedEffects;
import okay.direct.Effect;
import okay.direct.std.Async;
import okay.direct.std.VirtualClock;
import org.junit.jupiter.api.Test;

/** Structured concurrency and time in direct style: forks, joins, races, timeouts, cancellation; virtual time. */
interface AsyncTests {
    DetachedEffects effects();

    static Duration s(long seconds) {
        return Duration.ofSeconds(seconds);
    }

    static Throwable failure(CompletableFuture<?> future) {
        try {
            future.get(5, TimeUnit.SECONDS);
            throw new AssertionError("succeeded: " + future.join());
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    default void awaitsOfWhatIsAlreadyThereTakeNoStack() throws Exception {
        var sum = Async.run(effects(), async -> {
            long total = 0;
            for (int i = 0; i < 200_000; i++) total += Async.await(async, CompletableFuture.completedFuture(i));
            return total;
        });
        assertEquals(199_999L * 200_000 / 2, sum.get(30, TimeUnit.SECONDS));
    }

    @Test
    default void bothRunAtOnceInVirtualTime() {
        var clock = new VirtualClock();
        var sum = Async.run(effects(), clock, async -> Async.both(async,
            a -> {
                Async.sleep(a, s(10));
                return 1;
            },
            b -> {
                Async.sleep(b, s(20));
                return 2;
            },
            Integer::sum));
        clock.advance(s(19));
        assertFalse(sum.isDone());
        clock.advance(s(1));
        assertEquals(3, sum.join());
        assertEquals(Instant.EPOCH.plus(s(20)), clock.now()); // at once: 20 seconds, not 30
    }

    @Test
    default void allFailsAtTheFirstFailureAndCancelsTheRest() {
        var clock = new VirtualClock();
        var cleaned = new AtomicBoolean();
        var result = Async.run(effects(), clock, async -> Async.all(async, List.<okay.direct.Body<Async, Integer,
                RuntimeException>>of(
            a -> {
                Async.sleep(a, s(1));
                throw new IllegalStateException("first");
            },
            b -> {
                try {
                    Async.sleep(b, s(10));
                    return 2;
                } finally {
                    cleaned.set(true);
                }
            })));
        clock.advance(s(1));
        assertEquals("first", failure(result).getMessage());
        assertTrue(cleaned.get());
    }

    @Test
    default void allGivesTheValuesInOrder() {
        var clock = new VirtualClock();
        var result = Async.run(effects(), clock, async -> Async.all(async, List.<okay.direct.Body<Async, String,
                RuntimeException>>of(
            a -> {
                Async.sleep(a, s(3));
                return "a";
            },
            b -> {
                Async.sleep(b, s(1));
                return "b";
            })));
        clock.run();
        assertEquals(List.of("a", "b"), result.join());
    }

    @Test
    default void raceTakesTheFirstAndCancelsTheRest() {
        var clock = new VirtualClock();
        var slowCleaned = new AtomicBoolean();
        var winner = Async.run(effects(), clock, async -> Async.race(async, List.<okay.direct.Body<Async, String,
                RuntimeException>>of(
            a -> {
                try {
                    Async.sleep(a, s(5));
                    return "slow";
                } finally {
                    slowCleaned.set(true);
                }
            },
            b -> {
                Async.sleep(b, s(1));
                return "fast";
            })));
        clock.advance(s(1));
        assertEquals("fast", winner.join());
        assertTrue(slowCleaned.get());
    }

    @Test
    default void aRaceAllFailedThrowsTheFirstFailure() {
        var clock = new VirtualClock();
        var result = Async.run(effects(), clock, async -> Async.race(async, List.<okay.direct.Body<Async, String,
                RuntimeException>>of(
            a -> {
                Async.sleep(a, s(2));
                throw new IllegalStateException("second");
            },
            b -> {
                Async.sleep(b, s(1));
                throw new IllegalStateException("first");
            })));
        clock.run();
        var thrown = failure(result);
        assertEquals("first", thrown.getMessage());
        assertEquals("second", thrown.getSuppressed()[0].getMessage());
    }

    @Test
    default void timeoutGivesNothingAndCancelsTheBody() {
        var clock = new VirtualClock();
        var cleaned = new AtomicBoolean();
        var result = Async.run(effects(), clock, async -> List.of(
            Async.timeout(async, s(2), a -> {
                Async.sleep(a, s(1));
                return 7;
            }),
            Async.timeout(async, s(2), a -> {
                try {
                    Async.sleep(a, s(5));
                    return 8;
                } finally {
                    cleaned.set(true);
                }
            })));
        clock.run();
        assertEquals(List.of(Optional.of(7), Optional.empty()), result.join());
        assertTrue(cleaned.get());
    }

    /** Retried, backing off twice as long each time. */
    static <X> X retry(Effect<Async> async, int attempts, java.util.function.Supplier<X> call) {
        for (int attempt = 0; ; attempt++) {
            try {
                return call.get();
            } catch (RuntimeException e) {
                if (attempt + 1 == attempts) throw e;
                Async.sleep(async, Duration.ofSeconds(1L << attempt));
            }
        }
    }

    @Test
    default void retriesBackingOffTakeNoTimeInVirtualTime() {
        var clock = new VirtualClock();
        var calls = new ArrayList<Long>();
        var failing = new AtomicInteger(3);
        var result = Async.run(effects(), clock, async -> retry(async, 5, () -> {
            calls.add(clock.now().getEpochSecond());
            if (failing.getAndDecrement() > 0) throw new IllegalStateException("down");
            return "ok";
        }));
        clock.run();
        assertEquals("ok", result.join());
        assertEquals(List.of(0L, 1L, 3L, 7L), calls); // backed off 1, 2, 4 seconds
    }

    @Test
    default void aRunEndsAfterItsTasks() {
        var clock = new VirtualClock();
        var taskDone = new AtomicBoolean();
        var result = Async.run(effects(), clock, async -> {
            Async.fork(async, a -> {
                Async.sleep(a, s(3));
                taskDone.set(true);
                return null;
            });
            return "parent";
        });
        assertFalse(result.isDone()); // its body has returned, its task not
        clock.advance(s(3));
        assertEquals("parent", result.join());
        assertTrue(taskDone.get());
    }

    @Test
    default void aTaskCancelledCancelsItsOwnTasks() {
        var clock = new VirtualClock();
        var cleaned = new ArrayList<String>();
        var result = Async.run(effects(), clock, async -> {
            var task = Async.fork(async, a -> {
                Async.fork(a, b -> {
                    try {
                        Async.sleep(b, s(10));
                    } finally {
                        cleaned.add("inner");
                    }
                    return null;
                });
                try {
                    Async.sleep(a, s(10));
                } finally {
                    cleaned.add("outer");
                }
                return null;
            });
            Async.sleep(async, s(1));
            task.cancel();
            return "cancelled at " + Async.now(async).getEpochSecond();
        });
        clock.advance(s(1));
        assertEquals("cancelled at 1", result.join());
        assertEquals(List.of("inner", "outer"), cleaned);
    }

    @Test
    default void aFailedRunCancelsItsTasks() {
        var clock = new VirtualClock();
        var cleaned = new AtomicBoolean();
        var result = Async.run(effects(), clock, async -> {
            Async.fork(async, a -> {
                try {
                    Async.sleep(a, s(10));
                } finally {
                    cleaned.set(true);
                }
                return null;
            });
            Async.sleep(async, s(1));
            throw new IllegalStateException("failed");
        });
        clock.advance(s(1));
        assertEquals("failed", failure(result).getMessage());
        assertTrue(cleaned.get());
    }

    @Test
    default void aJoinedTasksFailureIsThrownAtTheJoin() {
        var clock = new VirtualClock();
        var result = Async.run(effects(), clock, async -> {
            var task = Async.fork(async, a -> {
                Async.sleep(a, s(1));
                throw new IllegalArgumentException("bad");
            });
            try {
                return Async.join(async, task);
            } catch (IllegalArgumentException e) {
                return "caught " + e.getMessage();
            }
        });
        clock.run();
        assertEquals("caught bad", result.join());
    }

    @Test
    default void theSystemsClockSleepsForReal() throws Exception {
        var started = System.nanoTime();
        var result = Async.run(effects(), async -> Async.both(async,
            a -> {
                Async.sleep(a, Duration.ofMillis(50));
                return 1;
            },
            b -> {
                Async.sleep(b, Duration.ofMillis(50));
                return 2;
            },
            Integer::sum));
        assertEquals(3, result.get(5, TimeUnit.SECONDS));
        assertTrue(System.nanoTime() - started >= 50_000_000L);
    }

    @Test
    default void aCancelledRunsFutureIsACancellation() {
        var clock = new VirtualClock();
        var result = Async.run(effects(), clock, async -> {
            var task = Async.fork(async, a -> {
                Async.sleep(a, s(10));
                return "never";
            });
            task.cancel();
            try {
                return Async.join(async, task);
            } catch (java.util.concurrent.CancellationException e) {
                return "cancelled";
            }
        });
        clock.run();
        assertEquals("cancelled", result.join());
        assertInstanceOf(VirtualClock.class, clock);
    }
}
