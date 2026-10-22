package okay.direct.scala3

import okay.direct.{Audit, Audited, Effect, Effects, Observer}
import okay.direct.loom.LoomEffects
import okay.direct.threads.ThreadEffects
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** A sensitive effect: its operations Audited.Ops. */
enum Pay:
  case Transfer(to: String, amount: Int) extends Pay, Audited.Op[Pay, String]
  case Balance() extends Pay, Audited.Op[Pay, Int]

def bank[A](balance: Int)(body: Audit.Vault[Pay] ?=> A)(using Effects): A =
  var left = balance
  handleSensitive(new SensitiveHandler[Pay, A, A]:
    def value(a: A) = a
    def apply[X](op: Pay & Audited.Op[Pay, X]): X = op match
      case Pay.Transfer(to, amount) => left -= amount; s"ok:$to:$amount"
      case Pay.Balance() => left
  )(body)

def masked(account: String): String = "****" + account.takeRight(4)

/** The audit of payments: normalized, told masked, refused over the limit. */
def guarded(limit: Int)(using Effect[Trail], Effect[Fail[String]]): Interceptor[Pay, Audited[Pay]] =
  new Interceptor[Pay, Audited[Pay]]:
    def apply[X](op: Pay & Effect.Op[Audited[Pay], X], pass: Pass[Audited[Pay]]): X = op match
      case Pay.Transfer(to0, amount) =>
        val to = to0.trim.toUpperCase
        if amount > limit then
          val why = s"refused: $amount to ${masked(to)} over the limit $limit"
          Trail.record(why)
          Fail.raise(why)
        else
          val receipt = pass(Pay.Transfer(to, amount))
          Trail.record(s"transfer $amount to ${masked(to)} → $receipt")
          receipt
      case Pay.Balance() =>
        val b = pass(Pay.Balance())
        Trail.record(s"balance → $b")
        b

val passing: Interceptor[Pay, Audited[Pay]] = new Interceptor[Pay, Audited[Pay]]:
  def apply[X](op: Pay & Effect.Op[Audited[Pay], X], pass: Pass[Audited[Pay]]): X = pass(op)

/** A program of payments: it knows nothing of the audit. */
def payments(using pay: Effect[Audited[Pay]]): Int =
  pay.perform(Pay.Transfer(" acct-1234 ", 100))
  val balance = pay.perform(Pay.Balance())
  pay.perform(Pay.Transfer("acct-77", 5000))
  balance

abstract class ControlTck:
  given Effects = effects
  def effects: Effects

  @Test def anInterceptorTellsAndChangesAPlainEffect(): Unit =
    val result = Trail.keep:
      State.run(1):
        intercept(new Interceptor[State[Int], State[Int]]:
          def apply[X](op: State[Int] & Effect.Op[State[Int], X], pass: Pass[State[Int]]): X = op match
            case State.Put(v) => Trail.record(s"put $v"); pass(State.Put(v * 2))
            case get @ State.Get() => pass(get)
        ):
          State.put(5)
          State.get[Int]
    assertEquals((List("put 5"), (10, 10)), result)

  @Test def theAuditOfASensitiveEffectNormalizesTellsAndRefuses(): Unit =
    val result = Trail.keep(Fail.attempt[String, Int](bank(1000)(audit(guarded(1000))(payments))))
    assertEquals((List(
      "transfer 100 to ****1234 → ok:ACCT-1234:100",
      "balance → 900",
      "refused: 5000 to ****T-77 over the limit 1000"),
      Left("refused: 5000 to ****T-77 over the limit 1000")), result)

  @Test def theObserverSeesEveryOperationAndItsAnswer(): Unit =
    val result = Trail.keep:
      val telling = new Observer:
        def observe[X](seen: Observer.Seen[X]): X =
          Trail.record(s"${seen.effect} ${seen.op}")
          val x = seen.proceed()
          Trail.record(s"  → $x")
          x
      // the implementation inside: the watched one, the observer's context parameter, nested deeper
      observe(telling):
        Reader.run(5):
          State.run(1):
            State.put(State.get[Int] + Reader.ask[Int])
            bank(100)(audit(passing)(summon[Effect[Audited[Pay]]].perform(Pay.Transfer("acct", State.get[Int]))))
    assertEquals(List("State Get()", "  → 1", "Reader Ask()", "  → 5", "State Put(6)", "  → ()",
      "State Get()", "  → 6", "Pay Transfer(acct,6)", "  → ok:acct:6"), result._1)
    assertEquals((6, "ok:acct:6"), result._2)

  @Test def theObserverRefuses(): Unit =
    val readOnly = new Observer:
      def observe[X](seen: Observer.Seen[X]): X =
        if seen.effect == "Pay" then throw SecurityException(s"read-only: ${seen.op} refused")
        seen.proceed()
    val result = observe(readOnly):
      try bank(100)(audit(passing)(summon[Effect[Audited[Pay]]].perform(Pay.Balance())))
      catch case e: SecurityException => -1
    assertEquals(-1, result)

class LoomControlTest extends ControlTck:
  def effects = LoomEffects()

class ThreadControlTest extends ControlTck:
  def effects = ThreadEffects()
