package okay.direct.scala3.catsinterop

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import okay.direct.Effects
import okay.direct.loom.LoomEffects
import okay.direct.threads.ThreadEffects
import okay.direct.scala3.{Emits, Fail, State, reflect, reify}
import scala.concurrent.duration.*
import org.junit.jupiter.api.Assertions.{assertEquals, assertThrows}
import org.junit.jupiter.api.Test

abstract class CatsTck:
  given Effects = effects
  def effects: Effects
  given IORuntime = IORuntime.global

  @Test def anIOAwaitedInABody(): Unit =
    val result = runAwaiting:
      State.run(0):
        State.put(await(IO.pure(41)) + 1)
        State.get[Int] + await(IO(1))
    assertEquals((42, 43), result)

  @Test def aBodyAsAnIO(): Unit =
    val io = toIO(State.run(1)(await(IO.pure(41)) + State.get[Int]))
    assertEquals((1, 42), io.unsafeRunSync())
    val failing = toIO[Int](await(IO.raiseError[Int](IllegalStateException("io failed"))))
    assertEquals("io failed", assertThrows(classOf[IllegalStateException], () => failing.unsafeRunSync()).getMessage)

  @Test def eitherReflectedByCats(): Unit =
    val ok = reify[[X] =>> Either[String, X], Int](reflect(Right(1): Either[String, Int]) + reflect(Right(2): Either[String, Int]))
    assertEquals(Right(3), ok)
    val no = reify[[X] =>> Either[String, X], Int](reflect(Right(1): Either[String, Int]) + reflect(Left("no"): Either[String, Int]))
    assertEquals(Left("no"), no)

  @Test def bothAndRace(): Unit =
    assertEquals((1, 2), runAwaiting(both(await(IO.sleep(20.millis) *> IO(1)), 2)))
    assertEquals(Right(7), runAwaiting(race(await(IO.sleep(5.seconds) *> IO(0)), 7)))

  @Test def aResourceReleasedHoweverTheBodyEnds(): Unit =
    val log = List.newBuilder[String]
    def resource(name: String) = cats.effect.Resource.make(IO(log += s"open $name").as(name))(n => IO(log += s"close $n").void)
    val ok = runAwaiting(State.run(0)(use(resource("a"))(name => { State.put(1); name.length })))
    val failed = runAwaiting(Fail.attempt[String, Int](use(resource("b"))(_ => Fail.raise("refused"))))
    assertEquals((1, 1), ok)
    assertEquals(Left("refused"), failed)
    assertEquals(List("open a", "close a", "open b", "close b"), log.result())

  @Test def eachElementOfAStreamInTheBody(): Unit =
    val result = runAwaiting(State.run(0)(foreach(fs2.Stream(1, 2, 3).covary[IO])(a => State.put(State.get[Int] + a))))
    assertEquals((6, ()), result)

  @Test def theBodyAsAStreamOfWhatItEmits(): Unit =
    val stream = toStream[Int]:
      Emits.emit(1)
      Emits.emit(await(IO(2)))
      Emits.emit(3)
    assertEquals(List(1, 2, 3), stream.compile.toList.unsafeRunSync())
    assertEquals(List(1, 2), stream.take(2).compile.toList.unsafeRunSync())

class LoomCatsTest extends CatsTck:
  def effects = LoomEffects()

class ThreadCatsTest extends CatsTck:
  def effects = ThreadEffects()
