package okay.direct.scala2.catsinterop

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import okay.direct.Effects
import okay.direct.loom.LoomEffects
import okay.direct.threads.ThreadEffects
import okay.direct.scala2.{Delimited, Emits, Fail, State}
import scala.concurrent.duration._
import org.junit.jupiter.api.Assertions.{assertEquals, assertThrows}
import org.junit.jupiter.api.Test
import Cats._

abstract class CatsTck {
  implicit def fx: Effects
  implicit val runtime: IORuntime = IORuntime.global

  @Test def anIOAwaitedInABody(): Unit = {
    val result = runAwaiting { implicit a =>
      State.run(0) { implicit s =>
        State.put(await(IO.pure(41)) + 1)
        State.get[Int] + await(IO(1))
      }
    }
    assertEquals((42, 43), result)
  }

  @Test def aBodyAsAnIO(): Unit = {
    val io = toIO { implicit a => State.run(1) { implicit s => await(IO.pure(41)) + State.get[Int] } }
    assertEquals((1, 42), io.unsafeRunSync())
    val failing = toIO[Int] { implicit a => await(IO.raiseError[Int](new IllegalStateException("io failed"))) }
    assertEquals("io failed", assertThrows(classOf[IllegalStateException], () => { failing.unsafeRunSync(); () }).getMessage)
  }

  @Test def eitherReflectedByCats(): Unit = {
    import Delimited._
    type E[x] = Either[String, x]
    assertEquals(Right(3), reify[E, Int] { implicit r => reflect[E, Int](Right(1)) + reflect[E, Int](Right(2)) })
    assertEquals(Left("no"), reify[E, Int] { implicit r => reflect[E, Int](Right(1)) + reflect[E, Int](Left("no")) })
  }
}

trait CatsMore { this: CatsTck =>
  @Test def bothAndRace(): Unit = {
    assertEquals((1, 2), runAwaiting { implicit a => both(implicit a => await(IO.sleep(20.millis) *> IO(1)), _ => 2) })
    assertEquals(Right(7), runAwaiting { implicit a => race(implicit a => await(IO.sleep(5.seconds) *> IO(0)), _ => 7) })
  }

  @Test def aResourceReleasedHoweverTheBodyEnds(): Unit = {
    val log = List.newBuilder[String]
    def resource(name: String) = cats.effect.Resource.make(IO(log += s"open $name").as(name))(n => IO(log += s"close $n").void)
    val ok = runAwaiting { implicit a => State.run(0) { implicit s => use(resource("a")) { name => State.put(1); name.length } } }
    val failed = runAwaiting { implicit a => Fail.attempt[String, Int] { implicit f => use(resource("b"))(_ => Fail.raise("refused")) } }
    assertEquals((1, 1), ok)
    assertEquals(Left("refused"), failed)
    assertEquals(List("open a", "close a", "open b", "close b"), log.result())
  }

  @Test def eachElementOfAStreamInTheBody(): Unit = {
    val result = runAwaiting { implicit a =>
      State.run(0) { implicit s => foreach(fs2.Stream(1, 2, 3).covary[IO])(x => State.put(State.get[Int] + x)) }
    }
    assertEquals((6, ()), result)
  }

  @Test def theBodyAsAStreamOfWhatItEmits(): Unit = {
    val stream = toStream[Int] { (e, a) =>
      implicit val emits = e
      implicit val awaits = a
      Emits.emit(1)
      Emits.emit(await(IO(2)))
      Emits.emit(3)
    }
    assertEquals(List(1, 2, 3), stream.compile.toList.unsafeRunSync())
    assertEquals(List(1, 2), stream.take(2).compile.toList.unsafeRunSync())
  }
}

class LoomCatsTest extends CatsTck with CatsMore {
  implicit val fx: Effects = new LoomEffects
}

class ThreadCatsTest extends CatsTck with CatsMore {
  implicit val fx: Effects = new ThreadEffects
}
