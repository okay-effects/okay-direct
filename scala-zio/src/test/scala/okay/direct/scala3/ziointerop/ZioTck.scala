package okay.direct.scala3.ziointerop

import zio.{Exit, Runtime, Unsafe, ZEnvironment, ZIO}
import okay.direct.Effects
import okay.direct.loom.LoomEffects
import okay.direct.threads.ThreadEffects
import okay.direct.scala3.{Emits, Fail, Reader, State}
import zio.stream.ZStream
import org.junit.jupiter.api.Assertions.{assertEquals, assertTrue}
import org.junit.jupiter.api.Test

abstract class ZioTck:
  given Effects = effects
  def effects: Effects
  given Runtime[Any] = Runtime.default

  def run[E, A](z: ZIO[Any, E, A]): Exit[E, A] = Unsafe.unsafe(implicit unsafe => Runtime.default.unsafe.run(z))

  @Test def aZIOAwaitedInABody(): Unit =
    val result = runAwaiting(State.run(0) { State.put(await(ZIO.succeed(41)) + 1); State.get[Int] })
    assertEquals((42, 42), result)

  @Test def aBodyAsAZIOItsChannelsTheEffects(): Unit =
    val program: ZIO[Int, String, Int] = toZIO[Int, String, Int](fromZIO(ZIO.serviceWith[Int](_ + 1)) + 1)
    assertEquals(Exit.succeed(43), run(program.provideEnvironment(ZEnvironment(41))))
    val failing: ZIO[Any, String, Int] = toZIO[Any, String, Int](1 + fromZIO[Any, String, Int](ZIO.fail("bad")))
    assertEquals(Exit.fail("bad"), run(failing))
    val dying: ZIO[Any, Nothing, Int] = toZIO[Any, Nothing, Int](throw IllegalStateException("boom"))
    assertTrue(run(dying).causeOption.exists(_.isDie))

  @Test def bothAndRace(): Unit =
    assertEquals((1, 2), runAwaiting(both(await(ZIO.sleep(zio.Duration.fromMillis(20)) *> ZIO.succeed(1)), 2)))
    assertEquals(Right(7), runAwaiting(race(await(ZIO.sleep(zio.Duration.fromSeconds(5)) *> ZIO.succeed(0)), 7)))

  @Test def aScopeClosedHoweverTheBodyEnds(): Unit =
    val log = List.newBuilder[String]
    def acquire(name: String) = ZIO.acquireRelease(ZIO.succeed { log += s"open $name"; name })(n => ZIO.succeed(log += s"close $n"))
    val ok = runAwaiting(State.run(0)(scoped(acquire("a"))(name => { State.put(1); name.length })))
    val failed = runAwaiting(Fail.attempt[String, Int](scoped(acquire("b"))(_ => Fail.raise("refused"))))
    assertEquals((1, 1), ok)
    assertEquals(Left("refused"), failed)
    assertEquals(List("open a", "close a", "open b", "close b"), log.result())

  @Test def aLayerForTheBody(): Unit =
    val result = runAwaiting(withLayer(zio.ZLayer.succeed(41))(Reader.ask[ZEnvironment[Int]].get[Int] + 1))
    assertEquals(42, result)

  @Test def eachElementOfAStreamInTheBody(): Unit =
    val result = runAwaiting(State.run(0)(foreach(ZStream(1, 2, 3))(a => State.put(State.get[Int] + a))))
    assertEquals((6, ()), result)

  @Test def theBodyAsAStreamOfWhatItEmits(): Unit =
    val stream = toStream[Int]:
      Emits.emit(1)
      Emits.emit(await(ZIO.succeed(2)))
      Emits.emit(3)
    assertEquals(Exit.succeed(zio.Chunk(1, 2, 3)), run(stream.runCollect))
    assertEquals(Exit.succeed(zio.Chunk(1, 2)), run(stream.take(2).runCollect))

class LoomZioTest extends ZioTck:
  def effects = LoomEffects()

class ThreadZioTest extends ZioTck:
  def effects = ThreadEffects()
