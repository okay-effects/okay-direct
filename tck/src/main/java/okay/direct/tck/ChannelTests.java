package okay.direct.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okay.direct.DetachedEffects;
import okay.direct.std.Async;
import okay.direct.std.Channel;
import okay.direct.std.VirtualClock;
import org.junit.jupiter.api.Test;

/** Channels between bodies, as Go's, and select: no thread waits. */
interface ChannelTests {
    DetachedEffects effects();

    @Test
    default void aProducerAndAConsumer() throws Exception {
        var numbers = new Channel<Integer>();
        var sum = Async.run(effects(), async -> Async.both(async,
            a -> {
                for (int i = 1; i <= 100; i++) numbers.send(a, i);
                numbers.close();
                return null;
            },
            b -> {
                int total = 0;
                for (int n : numbers.iterable(b)) total += n;
                return total;
            },
            (none, total) -> total));
        assertEquals(5050, sum.get(5, TimeUnit.SECONDS));
    }

    @Test
    default void aBufferedChannelTakesValuesUntilItIsFull() {
        var log = new ArrayList<String>();
        var channel = new Channel<Integer>(2);
        var received = Async.run(effects(), async -> {
            Async.fork(async, a -> {
                channel.send(a, 1);
                channel.send(a, 2);
                log.add("2 sent");
                channel.send(a, 3);
                log.add("3 sent");
                return null;
            });
            log.add("forked");
            return List.of(channel.receive(async), channel.receive(async), channel.receive(async));
        });
        assertEquals(List.of(1, 2, 3), received.join());
        assertEquals(List.of("2 sent", "forked", "3 sent"), log);
    }

    @Test
    default void pingPongTakesNoStack() throws Exception {
        var ping = new Channel<Integer>();
        var pong = new Channel<Integer>();
        var last = Async.run(effects(), async -> Async.both(async,
            a -> {
                int n = 0;
                for (int i = 0; i < 20_000; i++) {
                    ping.send(a, n);
                    n = pong.receive(a);
                }
                ping.close();
                return n;
            },
            b -> {
                for (int n : ping.iterable(b)) pong.send(b, n + 1);
                return null;
            },
            (n, none) -> n));
        assertEquals(20_000, last.get(60, TimeUnit.SECONDS));
    }

    @Test
    default void manyProducersOneConsumer() throws Exception {
        var channel = new Channel<Integer>(4);
        var sum = Async.run(effects(), async -> {
            var producers = new ArrayList<Async.Task<Void>>();
            for (int p = 0; p < 10; p++) {
                producers.add(Async.fork(async, a -> {
                    for (int i = 1; i <= 100; i++) channel.send(a, i);
                    return null;
                }));
            }
            Async.fork(async, a -> {
                for (var producer : producers) Async.join(a, producer);
                channel.close();
                return null;
            });
            int total = 0;
            for (int n : channel.iterable(async)) total += n;
            return total;
        });
        assertEquals(50_500, sum.get(10, TimeUnit.SECONDS));
    }

    @Test
    default void selectTakesTheChannelThatIsReady() {
        var empty = new Channel<String>();
        var full = new Channel<Integer>(1);
        var chosen = Async.run(effects(), async -> {
            full.send(async, 42);
            return Channel.<String>select()
                .receive(empty, s -> "empty gave " + s)
                .receive(full, n -> "full gave " + n)
                .await(async);
        });
        assertEquals("full gave 42", chosen.join());
    }

    @Test
    default void selectTimesOutAndLosesNoValue() {
        var clock = new VirtualClock();
        var channel = new Channel<Integer>();
        var result = Async.run(effects(), clock, async -> {
            var first = Channel.<String>select()
                .receive(channel, n -> "got " + n)
                .timeout(Duration.ofSeconds(5), () -> "timed out")
                .await(async);
            // the select given up: a value sent now goes to the next receiver, not to it
            Async.fork(async, a -> {
                channel.send(a, 7);
                return null;
            });
            return first + ", then " + channel.receive(async);
        });
        clock.advance(Duration.ofSeconds(5));
        assertEquals("timed out, then 7", result.join());
    }

    @Test
    default void aCancelledReceiverTakesNothing() {
        var clock = new VirtualClock();
        var channel = new Channel<Integer>();
        var result = Async.run(effects(), clock, async -> {
            var receiver = Async.fork(async, a -> channel.receive(a));
            receiver.cancel();
            Async.fork(async, a -> {
                channel.send(a, 1);
                return null;
            });
            return channel.receive(async);
        });
        clock.run();
        assertEquals(1, result.join());
    }

    @Test
    default void aCancelledSenderTakesItsValueBack() {
        var channel = new Channel<Integer>();
        var result = Async.run(effects(), async -> {
            var sender = Async.fork(async, a -> {
                channel.send(a, 1);
                return null;
            });
            sender.cancel();
            Async.fork(async, a -> {
                channel.send(a, 2);
                return null;
            });
            return channel.receive(async);
        });
        assertEquals(2, result.join());
    }

    @Test
    default void aClosedChannel() {
        var channel = new Channel<Integer>(1);
        var result = Async.run(effects(), async -> {
            channel.send(async, 1);
            channel.close();
            var first = channel.receive(async);
            assertThrows(Channel.Closed.class, () -> channel.receive(async));
            assertThrows(Channel.Closed.class, () -> channel.send(async, 2));
            return first;
        });
        assertEquals(1, result.join());
    }
}
