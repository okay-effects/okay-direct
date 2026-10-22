package okay.direct.scala2

import java.time.Instant
import java.util.UUID
import java.util.concurrent.{CompletableFuture, TimeUnit}
import scala.concurrent.duration._
import okay.direct.{DetachedEffects, Effect}
import okay.direct.loom.LoomEffects
import okay.direct.threads.ThreadEffects
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.{assertEquals, assertFalse, assertThrows, assertTrue}
import org.junit.jupiter.api.Test

object AsyncTck {
  /** Retried, backing off twice as long each time. */
  def retry[X](attempts: Int)(call: => X)(implicit async: Effect[Async]): X = {
    def attempt(n: Int): X =
      try call
      catch {
        case _: RuntimeException if n + 1 < attempts =>
          Async.sleep((1L << n).seconds)
          attempt(n + 1)
      }
    attempt(0)
  }
}

/** Structured concurrency, time, channels, SQL and tracing, in Scala 2, on an implementation with detached rests. */
abstract class AsyncTck {
  import AsyncTck._

  def effects: DetachedEffects
  implicit lazy val fx: DetachedEffects = effects

  @Test def awaitWithNoThreadWaiting(): Unit = {
    def later[X](x: X) = CompletableFuture.supplyAsync(() => x, CompletableFuture.delayedExecutor(20, TimeUnit.MILLISECONDS))
    assertEquals(3, Async.run { implicit async => Async.await(later(1)) + Async.await(later(2)) }.get(5, TimeUnit.SECONDS))
  }

  @Test def bothAtOnceInVirtualTime(): Unit = {
    implicit val clock: VirtualClock = new VirtualClock()
    val pair = Async.run { implicit async =>
      Async.both(implicit async => { Async.sleep(10.seconds); "a" }, implicit async => { Async.sleep(20.seconds); "b" })
    }
    clock.advance(19.seconds)
    assertFalse(pair.isDone)
    clock.advance(1.second)
    assertEquals(("a", "b"), pair.join())
    assertEquals(Instant.EPOCH.plusSeconds(20), clock.now())
  }

  @Test def allRaceAndTimeout(): Unit = {
    implicit val clock: VirtualClock = new VirtualClock()
    var slowCleaned = false
    val result = Async.run { implicit async =>
      val all = Async.all(implicit async => { Async.sleep(3.seconds); 1 }, implicit async => { Async.sleep(1.second); 2 })
      val first = Async.race(
        implicit async => try { Async.sleep(5.seconds); "slow" } finally slowCleaned = true,
        implicit async => { Async.sleep(1.second); "fast" })
      val inTime = Async.timeout(2.seconds) { implicit async => Async.sleep(1.second); 7 }
      val late = Async.timeout(2.seconds) { implicit async => Async.sleep(5.seconds); 8 }
      (all, first, inTime, late)
    }
    clock.run()
    assertEquals((List(1, 2), "fast", Some(7), None), result.join())
    assertTrue(slowCleaned)
  }

  @Test def retriesTakeNoTime(): Unit = {
    implicit val clock: VirtualClock = new VirtualClock()
    var calls = List.empty[Long]
    var failing = 3
    val result = Async.run { implicit async =>
      retry(5) {
        calls :+= clock.now().getEpochSecond
        failing -= 1
        if (failing >= 0) throw new IllegalStateException("down") else "ok"
      }
    }
    clock.run()
    assertEquals("ok", result.join())
    assertEquals(List(0L, 1L, 3L, 7L), calls)
  }

  @Test def aForkedBodyIsCancelled(): Unit = {
    implicit val clock: VirtualClock = new VirtualClock()
    var cleaned = false
    val result = Async.run { implicit async =>
      val task = Async.fork { implicit async =>
        try Async.sleep(10.seconds)
        finally cleaned = true
      }
      Async.sleep(1.second)
      task.cancel()
      s"cancelled at ${Async.now.getEpochSecond}"
    }
    clock.advance(1.second)
    assertEquals("cancelled at 1", result.join())
    assertTrue(cleaned)
  }

  @Test def aProducerAndAConsumer(): Unit = {
    val numbers = new Channel[Int]()
    val sum = Async.run { implicit async =>
      Async.both(
        implicit async => { for (i <- 1 to 100) numbers.send(i); numbers.close() },
        implicit async => numbers.iterator.sum)._2
    }
    assertEquals(5050, sum.get(5, TimeUnit.SECONDS))
  }

  @Test def selectAndItsTimeout(): Unit = {
    implicit val clock: VirtualClock = new VirtualClock()
    val orders = new Channel[String](1)
    val cancels = new Channel[Int]()
    val result = Async.run { implicit async =>
      orders.send("tea")
      val first = Channel.select(orders.received(o => s"order $o"), cancels.received(id => s"cancel $id"),
        Channel.timeout(5.seconds)("idle"))
      val second = Channel.select(orders.received(o => s"order $o"), Channel.timeout(5.seconds)("idle"))
      (first, second)
    }
    clock.run()
    assertEquals(("order tea", "idle"), result.join())
  }

  @Test def aClosedChannel(): Unit = {
    val channel = new Channel[Int](1)
    val result = Async.run { implicit async =>
      channel.send(1)
      channel.close()
      val first = channel.receive
      assertThrows(classOf[Channel.Closed], () => channel.receive)
      first
    }
    assertEquals(1, result.join())
  }

  @Test def eitherAndWriterReflected(): Unit = {
    def parse(s: String): Either[String, Int] = s.toIntOption.toRight(s"not a number: $s")
    assertEquals(Right(42), Delimited.either[String, Int](r => r(parse("40")) + r(parse("2"))))
    assertEquals(Left("not a number: 4o"), Delimited.either[String, Int](r => r(parse("4o")) + r(parse("2"))))
    def step(n: Int) = Written(n * 10, List(s"step $n"))
    val w = Delimited.written[String, Int] { implicit r =>
      Delimited.tell("start")
      val total = (1 to 3).map(i => r(step(i))).sum
      Delimited.tell("end")
      total
    }
    assertEquals(Written(60, List("start", "step 1", "step 2", "step 3", "end")), w)
  }

  @Test def aTransactionRollsBackOnAFailFurtherOut(): Unit = {
    val db = new JdbcDataSource()
    db.setURL(s"jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
    Transaction.run(db) { implicit sql =>
      Sql.update("create table account(id varchar primary key, balance int)")
      Sql.update("insert into account values (?, ?), (?, ?)", "ann", 100, "bob", 0)
    }
    def balances = Transaction.run(db) { implicit sql =>
      Sql.query("select id, balance from account order by id")(r => r.getString(1) -> r.getInt(2)).toMap
    }
    def transfer(amount: Int)(implicit sql: Effect[Sql], fail: Effect[Fail[String]]): Unit = {
      Sql.update("update account set balance = balance - ? where id = ?", amount, "ann")
      if (Sql.one("select balance from account where id = ?", "ann")(_.getInt(1)).get < 0) Fail.raise("too poor")
      Sql.update("update account set balance = balance + ? where id = ?", amount, "bob")
      ()
    }
    assertEquals(Left("too poor"), Fail.attempt[String, Unit] { implicit fail =>
      Transaction.run(db) { implicit sql => transfer(130) }
    })
    assertEquals(Map("ann" -> 100, "bob" -> 0), balances)
    var log = List.empty[String]
    assertEquals(Right(()), Fail.attempt[String, Unit] { implicit fail =>
      Transaction.run(db) { sql => transfer(30)(Sql.logged(log :+= _)(sql), fail) }
    })
    assertEquals(Map("ann" -> 70, "bob" -> 30), balances)
    assertEquals(3, log.size)
    assertThrows(classOf[Sql.Refused], () => Transaction.run(db) { sql => Sql.update("delete from account")(Sql.readOnly(sql)) })
  }

  @Test def tracedAwaits(): Unit = {
    import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
    import io.opentelemetry.sdk.trace.SdkTracerProvider
    import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
    import scala.jdk.CollectionConverters._
    val exported = InMemorySpanExporter.create()
    val tracer = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exported)).build().get("t")
    val traced: DetachedEffects = okay.direct.otel.Tracing.traced(effects, tracer)
    Async.run { implicit async => Async.await(CompletableFuture.completedFuture(1)) }(traced, Async.SystemClock).join()
    assertEquals(List("Async.Await"), exported.getFinishedSpanItems.asScala.map(_.getName).toList)
  }
}

class LoomAsyncTest extends AsyncTck {
  def effects = new LoomEffects
}

class ThreadAsyncTest extends AsyncTck {
  def effects = new ThreadEffects
}
