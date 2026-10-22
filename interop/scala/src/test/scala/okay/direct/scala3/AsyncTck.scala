package okay.direct.scala3

import java.time.Instant
import java.util.UUID
import java.util.concurrent.{CompletableFuture, TimeUnit}
import scala.concurrent.duration.*
import okay.direct.{DetachedEffects, Effect}
import okay.direct.loom.LoomEffects
import okay.direct.threads.ThreadEffects
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.{assertEquals, assertFalse, assertThrows, assertTrue}
import org.junit.jupiter.api.Test

/** Retried, backing off twice as long each time. */
def retry[X](attempts: Int)(call: => X)(using Effect[Async]): X =
  def attempt(n: Int): X =
    try call
    catch
      case e: RuntimeException if n + 1 < attempts =>
        Async.sleep((1L << n).seconds)
        attempt(n + 1)
  attempt(0)

/** Structured concurrency, time, channels, SQL and tracing, in Scala 3, on an implementation with detached rests. */
abstract class AsyncTck:
  def effects: DetachedEffects
  given DetachedEffects = effects

  @Test def awaitWithNoThreadWaiting(): Unit =
    def later[X](x: X) = CompletableFuture.supplyAsync(() => x, CompletableFuture.delayedExecutor(20, TimeUnit.MILLISECONDS))
    assertEquals(3, Async.run(Async.await(later(1)) + Async.await(later(2))).get(5, TimeUnit.SECONDS))

  @Test def bothAtOnceInVirtualTime(): Unit =
    given clock: VirtualClock = VirtualClock()
    val pair = Async.run:
      Async.both({ Async.sleep(10.seconds); "a" }, { Async.sleep(20.seconds); "b" })
    clock.advance(19.seconds)
    assertFalse(pair.isDone)
    clock.advance(1.second)
    assertEquals(("a", "b"), pair.join())
    assertEquals(Instant.EPOCH.plusSeconds(20), clock.now())

  @Test def allRaceAndTimeout(): Unit =
    given clock: VirtualClock = VirtualClock()
    var slowCleaned = false
    val result = Async.run:
      val all = Async.all({ Async.sleep(3.seconds); 1 }, { Async.sleep(1.second); 2 })
      val first = Async.race(
        try { Async.sleep(5.seconds); "slow" } finally slowCleaned = true,
        { Async.sleep(1.second); "fast" })
      val inTime = Async.timeout(2.seconds) { Async.sleep(1.second); 7 }
      val late = Async.timeout(2.seconds) { Async.sleep(5.seconds); 8 }
      (all, first, inTime, late)
    clock.run()
    assertEquals((List(1, 2), "fast", Some(7), None), result.join())
    assertTrue(slowCleaned)

  @Test def retriesTakeNoTime(): Unit =
    given clock: VirtualClock = VirtualClock()
    var calls = List.empty[Long]
    var failing = 3
    val result = Async.run:
      retry(5):
        calls :+= clock.now().getEpochSecond
        failing -= 1
        if failing >= 0 then throw IllegalStateException("down") else "ok"
    clock.run()
    assertEquals("ok", result.join())
    assertEquals(List(0L, 1L, 3L, 7L), calls)

  @Test def aForkedBodyUsesItsOwnCapabilityAndIsCancelled(): Unit =
    given clock: VirtualClock = VirtualClock()
    var cleaned = false
    val result = Async.run:
      val task = Async.fork:
        try Async.sleep(10.seconds) // the forked body's own capability: the innermost given
        finally cleaned = true
      Async.sleep(1.second)
      task.cancel()
      s"cancelled at ${Async.now.getEpochSecond}"
    clock.advance(1.second)
    assertEquals("cancelled at 1", result.join())
    assertTrue(cleaned)

  @Test def aProducerAndAConsumer(): Unit =
    val numbers = Channel[Int]()
    val sum = Async.run:
      val (_, total) = Async.both(
        { for i <- 1 to 100 do numbers.send(i); numbers.close() },
        numbers.iterator.sum)
      total
    assertEquals(5050, sum.get(5, TimeUnit.SECONDS))

  @Test def selectAndItsTimeout(): Unit =
    given clock: VirtualClock = VirtualClock()
    val orders = Channel[String](1)
    val cancels = Channel[Int]()
    val result = Async.run:
      orders.send("tea")
      val first = Channel.select(orders.received(o => s"order $o"), cancels.received(id => s"cancel $id"),
        Channel.timeout(5.seconds)("idle"))
      val second = Channel.select(orders.received(o => s"order $o"), Channel.timeout(5.seconds)("idle"))
      (first, second)
    clock.run()
    assertEquals(("order tea", "idle"), result.join())

  @Test def aClosedChannel(): Unit =
    val channel = Channel[Int](1)
    val result = Async.run:
      channel.send(1)
      channel.close()
      val first = channel.receive
      assertThrows(classOf[Channel.Closed], () => channel.receive)
      first
    assertEquals(1, result.join())

  @Test def eitherAndWriterReflected(): Unit =
    def parse(s: String): Either[String, Int] = s.toIntOption.toRight(s"not a number: $s")
    assertEquals(Right(42), either[String, Int](reflect(parse("40")) + reflect(parse("2"))))
    assertEquals(Left("not a number: 4o"), either[String, Int](reflect(parse("4o")) + reflect(parse("2"))))
    def step(n: Int) = Written(n * 10, List(s"step $n"))
    val w = written[String, Int]:
      tell("start")
      val total = (1 to 3).map(i => reflect(step(i))).sum
      tell("end")
      total
    assertEquals(Written(60, List("start", "step 1", "step 2", "step 3", "end")), w)

  @Test def aTransactionRollsBackOnAFailFurtherOut(): Unit =
    val db = JdbcDataSource()
    db.setURL(s"jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
    Transaction.run(db):
      Sql.update("create table account(id varchar primary key, balance int)")
      Sql.update("insert into account values (?, ?), (?, ?)", "ann", 100, "bob", 0)
    def balances = Transaction.run(db)(Sql.query("select id, balance from account order by id")(r =>
      r.getString(1) -> r.getInt(2)).toMap)
    def transfer(amount: Int)(using Effect[Sql], Effect[Fail[String]]): Unit =
      Sql.update("update account set balance = balance - ? where id = ?", amount, "ann")
      if Sql.one("select balance from account where id = ?", "ann")(_.getInt(1)).get < 0 then Fail.raise("too poor")
      Sql.update("update account set balance = balance + ? where id = ?", amount, "bob")
    assertEquals(Left("too poor"), Fail.attempt[String, Unit](Transaction.run(db)(transfer(130))))
    assertEquals(Map("ann" -> 100, "bob" -> 0), balances)
    var log = List.empty[String]
    assertEquals(Right(()), Fail.attempt[String, Unit](Transaction.run(db)(transfer(30)(using Sql.logged(log :+= _)))))
    assertEquals(Map("ann" -> 70, "bob" -> 30), balances)
    assertEquals(3, log.size)
    assertThrows(classOf[Sql.Refused], () => Transaction.run(db)(Sql.update("delete from account")(using Sql.readOnly)))

  @Test def tracedAwaits(): Unit =
    import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
    import io.opentelemetry.sdk.trace.SdkTracerProvider
    import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
    import scala.jdk.CollectionConverters.*
    val exported = InMemorySpanExporter.create()
    val tracer = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exported)).build().get("t")
    given DetachedEffects = okay.direct.otel.Tracing.traced(effects, tracer)
    Async.run(Async.await(CompletableFuture.completedFuture(1))).join()
    assertEquals(List("Async.Await"), exported.getFinishedSpanItems.asScala.map(_.getName).toList)

class LoomAsyncTest extends AsyncTck:
  def effects = LoomEffects()

class ThreadAsyncTest extends AsyncTck:
  def effects = ThreadEffects()
