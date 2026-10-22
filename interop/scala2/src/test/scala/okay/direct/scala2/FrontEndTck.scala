package okay.direct.scala2

import okay.direct.{Effect, Effects}
import okay.direct.loom.LoomEffects
import okay.direct.threads.ThreadEffects
import org.junit.jupiter.api.Assertions.{assertEquals, assertThrows, assertTrue}
import org.junit.jupiter.api.Test

object Programs {
  /** Reads a base, counts in state, fails on a negative result: the capabilities implicit. */
  def program(heads: Boolean)(implicit r: Effect[Reader[Int]], s: Effect[State[Int]], f: Effect[Fail[String]]): Int = {
    val base = Reader.ask[Int]
    State.put(State.get[Int] + 1)
    val result = if (heads) base else -base
    if (result < 0) Fail.raise(s"negative: $result") else result
  }

  def add(x: Int)(implicit s: Effect[State[Int]]): Unit = State.put(State.get[Int] + x)
}

/** The front end's programs, on an implementation. */
abstract class FrontEndTck {
  import Programs._

  implicit def fx: Effects

  @Test def handlersNestInAnyOrder(): Unit = {
    assertEquals((1, Right(42)), Reader.run(42) { implicit r =>
      State.run(0) { implicit s => Fail.attempt[String, Int] { implicit f => program(heads = true) } }
    })
    assertEquals(Left("negative: -42"), Fail.attempt[String, (Int, Int)] { implicit f =>
      State.run(0) { implicit s => Reader.run(42) { implicit r => program(heads = false) } }
    })
  }

  @Test def operationsInLambdasAndAcrossFunctions(): Unit = {
    val result = State.run(0) { implicit s =>
      List(1, 2, 3, 4).foreach(add)
      List(10, 20).map(_ + State.get[Int])
    }
    assertEquals((10, List(20, 30)), result)
  }

  @Test def anAbandonedRestRunsItsFinallyBlocks(): Unit = {
    var cleaned = false
    val result = State.run(0) { implicit s =>
      Fail.attempt[String, Int] { implicit f =>
        try Fail.raise("boom")
        finally {
          cleaned = true
          State.put(7)
        }
      }
    }
    assertTrue(cleaned)
    assertEquals((7, Left("boom")), result)
  }

  @Test def aGeneralHandlerResumesAndWrapsTheRest(): Unit = {
    val result = handle(new Handler[Reader[Int], Int, String] {
      def value(a: Int): String = s"value $a"
      def apply(op: Reader[Int], k: Resume[Reader[Int], String]): String = op match {
        case ask: Reader.Ask[Int] => s"[${k(ask, 20)}]"
      }
    }) { implicit r => Reader.ask[Int] + Reader.ask[Int] }
    assertEquals("[[value 40]]", result)
  }

  @Test def twoStatesOfOneTypeAreTwoCapabilities(): Unit = {
    val result = State.run(100) { outer =>
      State.run(1) { implicit inner =>
        State.put(State.get[Int](outer) + 1)(outer)
        State.put(State.get[Int] + 1)
        State.get[Int]
      }
    }
    assertEquals((101, (2, 2)), result)
  }

  @Test def continuationsAreOneShot(): Unit = {
    assertThrows(classOf[IllegalStateException], () =>
      handle(new Handler[Reader[Boolean], Boolean, Boolean] {
        def value(a: Boolean): Boolean = a
        def apply(op: Reader[Boolean], k: Resume[Reader[Boolean], Boolean]): Boolean = op match {
          case ask: Reader.Ask[Boolean] => k(ask, true) && k(ask, false)
        }
      }) { implicit r => Reader.ask[Boolean] }
    )
    ()
  }

  @Test def shift0GivesTheRestUpToItsReset(): Unit = {
    import Delimited._
    assertEquals(22, reset[Int] { implicit p => 1 + shift0[Int, Int](k => k(10) * 2) })
    assertEquals(42, reset[Int] { implicit p => 1 + shift0[Int, Int](_ => 42) })
  }

  @Test def shift0ToAnOuterPromptPassesTheInnerReset(): Unit = {
    import Delimited._
    val result = reset[String] { outer =>
      "outer(" + reset[String] { _ => "inner(" + outer.shift0[String](k => s"caught[${k("x")}]") + ")" } + ")"
    }
    assertEquals("caught[outer(inner(x))]", result)
  }

  @Test def reflectionOfOption(): Unit = {
    import Delimited._
    assertEquals(Some(3), reify[Option, Int] { implicit r => reflect(Option(1)) + reflect(Option(2)) })
    var cleaned = false
    val none = reify[Option, Int] { implicit r =>
      try reflect(Option(1)) + reflect(Option.empty[Int])
      finally cleaned = true
    }
    assertEquals(None, none)
    assertTrue(cleaned)
  }

  @Test def reflectionWithOtherEffects(): Unit = {
    import Delimited._
    val result = State.run(10) { implicit s =>
      reify[Option, Int] { implicit r =>
        val a = reflect(Option(State.get[Int]))
        State.put(a + 1)
        a + reflect(Option(State.get[Int]))
      }
    }
    assertEquals((11, Some(21)), result)
  }

  @Test def aTailHandlerRunsInConstantStack(): Unit = {
    val result = State.run(0) { implicit s =>
      for (_ <- 1 to 100000) State.put(State.get[Int] + 1)
      "done"
    }
    assertEquals((100000, "done"), result)
  }
}

class LoomFrontEndTest extends FrontEndTck {
  implicit val fx: Effects = new LoomEffects
}

class ThreadFrontEndTest extends FrontEndTck {
  implicit val fx: Effects = new ThreadEffects
}

/** On Espresso: the same programs, and multi-shot ones. */
class EspressoFrontEndTest extends FrontEndTck {
  implicit val fx: okay.direct.MultiEffects = new okay.direct.espresso.EspressoEffects

  @Test def chooseResumesTheRestBothWays(): Unit =
    assertEquals(List(List(true, true), List(true, false), List(false, true), List(false, false)),
      Choose.all { implicit c => List(Choose.flip, Choose.flip) })

  @Test def aStateInsideTheChoiceIsEachBranchsOwn(): Unit = {
    val all = Choose.all { implicit c =>
      State.run(0) { implicit s =>
        State.put(State.get[Int] + 1)
        val heads = Choose.flip
        State.put(State.get[Int] + 10)
        heads
      }
    }
    assertEquals(List((11, true), (11, false)), all)
  }

  @Test def aStateOutsideTheChoiceIsThreadedThroughTheBranches(): Unit = {
    val result = State.run(0) { implicit s =>
      Choose.all { implicit c =>
        Choose.flip
        State.put(State.get[Int] + 1)
        State.get[Int]
      }
    }
    assertEquals((2, List(1, 2)), result)
  }

  @Test def aTrailInsideTheChoiceIsEachBranchsOwn(): Unit = {
    val all = Choose.all { implicit c =>
      Trail.keep { implicit t =>
        Trail.record("start")
        val heads = Choose.flip
        Trail.record(s"heads $heads")
        heads
      }
    }
    assertEquals(List((List("start", "heads true"), true), (List("start", "heads false"), false)), all)
  }

  @Test def aMultiShotRestResumedTwice(): Unit = {
    import Delimited._
    assertEquals(112, resetMulti[Int] { implicit p => 1 + shift0[Int, Int](k => k(10) + k(100)) })
  }

  @Test def reflectionOfTheListMonad(): Unit = {
    import Delimited._
    assertEquals(List(11, 21, 12, 22), reifyMulti[List, Int] { implicit r => reflect(List(1, 2)) + reflect(List(10, 20)) })
  }
}

class EspressoControlTest extends ControlTck {
  def effects: Effects = new okay.direct.espresso.EspressoEffects
}
