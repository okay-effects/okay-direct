package okay.direct.scala2

import okay.direct.{Audit, Audited, Effect, Effects, Observer}
import okay.direct.loom.LoomEffects
import okay.direct.threads.ThreadEffects
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import Control._

/** A sensitive effect: its operations Audited.Ops. */
sealed trait Pay

object Pay {
  final case class Transfer(to: String, amount: Int) extends Pay with Audited.Op[Pay, String]
  final case class Balance() extends Pay with Audited.Op[Pay, Int]

  def bank[A](balance: Int)(body: Audit.Vault[Pay] => A)(implicit fx: Effects): A = {
    var left = balance
    handleSensitive(new SensitiveHandler[Pay, A, A] {
      def value(a: A): A = a
      def apply(op: Pay, k: Answer[Audited[Pay]]): Unit = op match {
        case t: Transfer => left -= t.amount; k(t, s"ok:${t.to}:${t.amount}")
        case b: Balance => k(b, left)
      }
    })(body)
  }

  def masked(account: String): String = "****" + account.takeRight(4)

  /** The audit of payments: normalized, told masked, refused over the limit. */
  def guarded(limit: Int)(implicit trail: Effect[Trail], fail: Effect[Fail[String]]): Interceptor[Pay, Audited[Pay]] =
    new Interceptor[Pay, Audited[Pay]] {
      def apply(op: Pay, pass: Pass[Audited[Pay]], k: Answer[Audited[Pay]]): Unit = op match {
        case t: Transfer =>
          val to = t.to.trim.toUpperCase
          if (t.amount > limit) {
            val why = s"refused: ${t.amount} to ${masked(to)} over the limit $limit"
            Trail.record(why)
            Fail.raise(why)
          } else {
            val receipt = pass(Transfer(to, t.amount))
            Trail.record(s"transfer ${t.amount} to ${masked(to)} → $receipt")
            k(t, receipt)
          }
        case b: Balance =>
          val balance = pass(b)
          Trail.record(s"balance → $balance")
          k(b, balance)
      }
    }

  val passing: Interceptor[Pay, Audited[Pay]] = new Interceptor[Pay, Audited[Pay]] {
    def apply(op: Pay, pass: Pass[Audited[Pay]], k: Answer[Audited[Pay]]): Unit = op match {
      case t: Transfer => k(t, pass(t))
      case b: Balance => k(b, pass(b))
    }
  }

  /** A program of payments: it knows nothing of the audit. */
  def payments(pay: Effect[Audited[Pay]]): Int = {
    pay.perform(Transfer(" acct-1234 ", 100))
    val balance: Int = pay.perform(Balance())
    pay.perform(Transfer("acct-77", 5000))
    balance
  }
}

abstract class ControlTck {
  import Pay._

  def effects: Effects

  @Test def anInterceptorTellsAndChangesAPlainEffect(): Unit = {
    implicit val fx: Effects = effects
    val result = Trail.keep { implicit trail =>
      State.run(1) { implicit state =>
        intercept(new Interceptor[State[Int], State[Int]] {
          def apply(op: State[Int], pass: Pass[State[Int]], k: Answer[State[Int]]): Unit = op match {
            case get: State.Get[Int] => k(get, pass(get))
            case put: State.Put[Int] => Trail.record(s"put ${put.value}"); k(put, pass(State.Put(put.value * 2)))
          }
        }) { implicit state => // the same name: in Scala 2 it hides the outer capability
          State.put(5)
          State.get[Int]
        }
      }
    }
    assertEquals((List("put 5"), (10, 10)), result)
  }

  @Test def theAuditOfASensitiveEffectNormalizesTellsAndRefuses(): Unit = {
    implicit val fx: Effects = effects
    val result = Trail.keep { implicit trail =>
      Fail.attempt[String, Int] { implicit fail =>
        bank(1000) { implicit vault => audit(guarded(1000))(payments) }
      }
    }
    assertEquals((List(
      "transfer 100 to ****1234 → ok:ACCT-1234:100",
      "balance → 900",
      "refused: 5000 to ****T-77 over the limit 1000"),
      Left("refused: 5000 to ****T-77 over the limit 1000")), result)
  }

  @Test def theObserverSeesEveryOperationAndItsAnswer(): Unit = {
    val result = Trail.keep { implicit trail =>
      val telling = new Observer {
        def observe[X](seen: Observer.Seen[X]): X = {
          Trail.record(s"${seen.effect} ${seen.op}")
          val x = seen.proceed()
          Trail.record(s"  → $x")
          x
        }
      }
      observe(telling) { implicit watched =>
        Reader.run(5) { implicit reader =>
          State.run(1) { implicit state =>
            State.put(State.get[Int] + Reader.ask[Int])
            bank(100) { implicit vault => audit(passing)(pay => pay.perform(Transfer("acct", State.get[Int]))) }
          }
        }
      }(effects)
    }(effects)
    assertEquals(List("State Get()", "  → 1", "Reader Ask()", "  → 5", "State Put(6)", "  → ()",
      "State Get()", "  → 6", "Pay Transfer(acct,6)", "  → ok:acct:6"), result._1)
    assertEquals((6, "ok:acct:6"), result._2)
  }

  @Test def theObserverRefuses(): Unit = {
    val readOnly = new Observer {
      def observe[X](seen: Observer.Seen[X]): X =
        if (seen.effect == "Pay") throw new SecurityException(s"read-only: ${seen.op} refused")
        else seen.proceed()
    }
    val result = observe(readOnly) { implicit watched =>
      try bank(100) { implicit vault => audit(passing)(pay => pay.perform(Balance()): Int) }
      catch { case _: SecurityException => -1 }
    }(effects)
    assertEquals(-1, result)
  }
}

class LoomControlTest extends ControlTck {
  def effects: Effects = new LoomEffects
}

class ThreadControlTest extends ControlTck {
  def effects: Effects = new ThreadEffects
}
