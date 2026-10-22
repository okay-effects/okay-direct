package okay.direct.scala3

import okay.direct.{Effect, Effects}
import okay.direct.loom.LoomEffects
import okay.direct.threads.ThreadEffects
import org.junit.jupiter.api.Assertions.{assertEquals, assertThrows, assertTrue}
import org.junit.jupiter.api.Test

/** Reads a base, counts in state, fails on a negative result: no capability passed by hand. */
def program(heads: Boolean)(using Effect[Reader[Int]], Effect[State[Int]], Effect[Fail[String]]): Int =
  val base = Reader.ask[Int]
  State.put(State.get[Int] + 1)
  val result = if heads then base else -base
  if result < 0 then Fail.raise(s"negative: $result") else result

/** The front end's programs, on an implementation. */
abstract class FrontEndTck:
  given Effects = effects
  def effects: Effects

  @Test def handlersNestInAnyOrder(): Unit =
    assertEquals((1, Right(42)), Reader.run(42)(State.run(0)(Fail.attempt(program(true)))))
    assertEquals(Left("negative: -42"), Fail.attempt(State.run(0)(Reader.run(42)(program(false)))))

  @Test def operationsInLambdasAndAcrossFunctions(): Unit =
    def add(x: Int)(using Effect[State[Int]]): Unit = State.put(State.get[Int] + x)
    val result = State.run(0):
      List(1, 2, 3, 4).foreach(add)
      List(10, 20).map(_ + State.get[Int])
    assertEquals((10, List(20, 30)), result)

  @Test def anAbandonedRestRunsItsFinallyBlocks(): Unit =
    var cleaned = false
    val result = State.run(0):
      Fail.attempt[String, Int]:
        try Fail.raise("boom")
        finally
          cleaned = true
          State.put(7)
    assertTrue(cleaned)
    assertEquals((7, Left("boom")), result)

  @Test def aGeneralHandlerResumesAndWrapsTheRest(): Unit =
    val result = handle(new Handler[Reader[Int], Int, String]:
      def value(a: Int) = s"value $a"
      def apply[X](op: Reader[Int] & Effect.Op[Reader[Int], X], k: X => String) = op match
        case Reader.Ask() => s"[${k(20)}]"
    )(Reader.ask[Int] + Reader.ask[Int])
    assertEquals("[[value 40]]", result)

  @Test def twoStatesOfOneTypeAreTwoCapabilities(): Unit =
    val result = State.run(100):
      val outer = summon[Effect[State[Int]]]
      State.run(1):
        State.put(State.get[Int](using outer) + 1)(using outer)
        State.put(State.get[Int] + 1)
        State.get[Int]
    assertEquals((101, (2, 2)), result)

  @Test def continuationsAreOneShot(): Unit =
    assertThrows(classOf[IllegalStateException], () =>
      handle(new Handler[Reader[Boolean], Boolean, Boolean]:
        def value(a: Boolean) = a
        def apply[X](op: Reader[Boolean] & Effect.Op[Reader[Boolean], X], k: X => Boolean) = op match
          case Reader.Ask() => k(true) && k(false)
      )(Reader.ask[Boolean])
    )

  @Test def shift0GivesTheRestUpToItsReset(): Unit =
    assertEquals(22, reset[Int](1 + shift0[Int, Int](k => k(10) * 2)))
    assertEquals(42, reset[Int](1 + shift0[Int, Int](_ => 42)))

  @Test def shift0ToAnOuterPromptPassesTheInnerReset(): Unit =
    val result = reset[String]:
      val outer = summon[Prompt[String]]
      "outer(" + reset[String]("inner(" + outer.shift0[String](k => s"caught[${k("x")}]") + ")") + ")"
    assertEquals("caught[outer(inner(x))]", result)

  @Test def reflectionOfOption(): Unit =
    assertEquals(Some(3), reify[Option, Int](reflect(Option(1)) + reflect(Option(2))))
    var cleaned = false
    val none = reify[Option, Int]:
      try reflect(Option(1)) + reflect(Option.empty[Int])
      finally cleaned = true
    assertEquals(None, none)
    assertTrue(cleaned)

  @Test def reflectionWithOtherEffects(): Unit =
    val result = State.run(10):
      reify[Option, Int]:
        val a = reflect(Option(State.get[Int]))
        State.put(a + 1)
        a + reflect(Option(State.get[Int]))
    assertEquals((11, Some(21)), result)

  @Test def aTailHandlerRunsInConstantStack(): Unit =
    val result = State.run(0):
      for _ <- 1 to 100_000 do State.put(State.get[Int] + 1)
      "done"
    assertEquals((100_000, "done"), result)

class LoomFrontEndTest extends FrontEndTck:
  def effects = LoomEffects()

class ThreadFrontEndTest extends FrontEndTck:
  def effects = ThreadEffects()

/** On Espresso: the same programs, and multi-shot ones. */
class EspressoFrontEndTest extends FrontEndTck:
  private lazy val multi = okay.direct.espresso.EspressoEffects()
  override def effects: okay.direct.MultiEffects = multi
  given okay.direct.MultiEffects = multi

  @Test def chooseResumesTheRestBothWays(): Unit =
    // locals, not `List(Choose.flip, Choose.flip)`: Scala 3 makes that varargs array on the heap BEFORE the
    // flips and fills it after, so every branch would fill the one array (the heap is shared, the frames copied)
    val all = Choose.all:
      val a = Choose.flip
      val b = Choose.flip
      List(a, b)
    assertEquals(List(List(true, true), List(true, false), List(false, true), List(false, false)), all)

  @Test def aStateInsideTheChoiceIsEachBranchsOwn(): Unit =
    val all = Choose.all:
      State.run(0):
        State.put(State.get[Int] + 1)
        val heads = Choose.flip
        State.put(State.get[Int] + 10)
        heads
    assertEquals(List((11, true), (11, false)), all)

  @Test def aStateOutsideTheChoiceIsThreadedThroughTheBranches(): Unit =
    val result = State.run(0):
      Choose.all:
        Choose.flip
        State.put(State.get[Int] + 1)
        State.get[Int]
    assertEquals((2, List(1, 2)), result)

  @Test def aTrailInsideTheChoiceIsEachBranchsOwn(): Unit =
    val all = Choose.all:
      Trail.keep:
        Trail.record("start")
        val heads = Choose.flip
        Trail.record(s"heads $heads")
        heads
    assertEquals(List((List("start", "heads true"), true), (List("start", "heads false"), false)), all)

  @Test def aMultiShotRestResumedTwice(): Unit =
    assertEquals(112, resetMulti[Int](1 + shift0[Int, Int](k => k(10) + k(100))))

  @Test def reflectionOfTheListMonad(): Unit =
    assertEquals(List(11, 21, 12, 22), reifyMulti[List, Int](reflect(List(1, 2)) + reflect(List(10, 20))))

class EspressoControlTest extends ControlTck:
  def effects = okay.direct.espresso.EspressoEffects()
