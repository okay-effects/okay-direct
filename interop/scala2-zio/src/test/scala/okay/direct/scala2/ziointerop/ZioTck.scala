package okay.direct.scala2.ziointerop

import zio.{Exit, Runtime, Unsafe, ZEnvironment, ZIO}
import okay.direct.Effects
import okay.direct.loom.LoomEffects
import okay.direct.threads.ThreadEffects
import okay.direct.scala2.{Emits, Fail, Reader, State}
import zio.stream.ZStream
import org.junit.jupiter.api.Assertions.{assertEquals, assertTrue}
import org.junit.jupiter.api.Test
import Zio._

abstract class ZioTck {
  implicit def fx: Effects
  implicit val runtime: Runtime[Any] = Runtime.default

  def run[E, A](z: ZIO[Any, E, A]): Exit[E, A] = Unsafe.unsafe(implicit unsafe => Runtime.default.unsafe.run(z))

  @Test def aZIOAwaitedInABody(): Unit = {
    val result = runAwaiting { implicit z => State.run(0) { implicit s => State.put(await(ZIO.succeed(41)) + 1); State.get[Int] } }
    assertEquals((42, 42), result)
  }

  @Test def aBodyAsAZIOItsChannelsTheEffects(): Unit = {
    val program: ZIO[Int, String, Int] = toZIO[Int, String, Int] { (r, f, z) =>
      fromZIO(ZIO.serviceWith[Int](_ + 1))(r, f, z) + 1
    }
    assertEquals(Exit.succeed(43), run(program.provideEnvironment(ZEnvironment(41))))
    val failing: ZIO[Any, String, Int] = toZIO[Any, String, Int] { (r, f, z) => 1 + fromZIO[Any, String, Int](ZIO.fail("bad"))(r, f, z) }
    assertEquals(Exit.fail("bad"), run(failing))
    val dying: ZIO[Any, Nothing, Int] = toZIO[Any, Nothing, Int] { (_, _, _) => throw new IllegalStateException("boom") }
    assertTrue(run(dying).causeOption.exists(_.isDie))
  }
}

trait ZioMore { this: ZioTck =>
  @Test def bothAndRace(): Unit = {
    assertEquals((1, 2), runAwaiting { implicit z => both(implicit z => await(ZIO.sleep(zio.Duration.fromMillis(20)) *> ZIO.succeed(1)), _ => 2) })
    assertEquals(Right(7), runAwaiting { implicit z => race(implicit z => await(ZIO.sleep(zio.Duration.fromSeconds(5)) *> ZIO.succeed(0)), _ => 7) })
  }

  @Test def aScopeClosedHoweverTheBodyEnds(): Unit = {
    val log = List.newBuilder[String]
    def acquire(name: String) = ZIO.acquireRelease(ZIO.succeed { log += s"open $name"; name })(n => ZIO.succeed(log += s"close $n"))
    val ok = runAwaiting { implicit z => State.run(0) { implicit s => scoped(acquire("a")) { name => State.put(1); name.length } } }
    val failed = runAwaiting { implicit z => Fail.attempt[String, Int] { implicit f => scoped(acquire("b"))(_ => Fail.raise("refused")) } }
    assertEquals((1, 1), ok)
    assertEquals(Left("refused"), failed)
    assertEquals(List("open a", "close a", "open b", "close b"), log.result())
  }

  @Test def aLayerForTheBody(): Unit = {
    val result = runAwaiting { implicit z => withLayer(zio.ZLayer.succeed(41)) { implicit r => Reader.ask[ZEnvironment[Int]].get[Int] + 1 } }
    assertEquals(42, result)
  }

  @Test def eachElementOfAStreamInTheBody(): Unit = {
    val result = runAwaiting { implicit z => State.run(0) { implicit s => foreach(ZStream(1, 2, 3))(x => State.put(State.get[Int] + x)) } }
    assertEquals((6, ()), result)
  }

  @Test def theBodyAsAStreamOfWhatItEmits(): Unit = {
    val stream = toStream[Int] { (e, z) =>
      implicit val emits = e
      implicit val zios = z
      Emits.emit(1)
      Emits.emit(await(ZIO.succeed(2)))
      Emits.emit(3)
    }
    assertEquals(Exit.succeed(zio.Chunk(1, 2, 3)), run(stream.runCollect))
    assertEquals(Exit.succeed(zio.Chunk(1, 2)), run(stream.take(2).runCollect))
  }
}

class LoomZioTest extends ZioTck with ZioMore {
  implicit val fx: Effects = new LoomEffects
}

class ThreadZioTest extends ZioTck with ZioMore {
  implicit val fx: Effects = new ThreadEffects
}
