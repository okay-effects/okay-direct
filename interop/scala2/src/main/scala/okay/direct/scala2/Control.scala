package okay.direct.scala2

import okay.direct.{Audit, Audited, Effect, Effects, Observer}

/**
 * CONTROL AND AUDIT, the Scala 2 way: an interceptor's clause matches the operation and answers through `k`, the
 * operation the witness of its answer's type, as in Java. It may tell, pass the operation on (`pass(op)`), change
 * it or its answer, or refuse — throw, or `Fail.raise`, not answering.
 */
trait Interceptor[C, F] {
  def apply(op: C, pass: Pass[F], k: Answer[F]): Unit
}

/** An operation passed on, past the interceptor, to the capability it guards. */
trait Pass[F] {
  def apply[X](op: Effect.Op[F, X]): X
}

/** A handler of the sensitive effect C: each operation answered in place through `k`. */
trait SensitiveHandler[C, A, R] {
  def value(a: A): R
  def apply(op: C, k: Answer[Audited[C]]): Unit
}

object Control {
  private def toJava[C, F](i: Interceptor[C, F]): okay.direct.Interceptor[C, F] =
    new okay.direct.Interceptor[C, F] {
      def operation(op: C, pass: okay.direct.Interceptor.Pass[F], k: okay.direct.Answer[F]): Unit =
        i(op, new Pass[F] { def apply[X](o: Effect.Op[F, X]): X = pass.pass(o) },
          new Answer[F] { def apply[X](o: Effect.Op[F, X], x: X): Unit = k.answer(o, x) })
    }

  /** The body with the capability `outer` intercepted: each of its operations through `i` first. */
  def intercept[F, A](i: Interceptor[F, F])(body: Effect[F] => A)(implicit outer: Effect[F]): A =
    body(okay.direct.Interceptor.intercept(outer, toJava(i)))

  /** The sensitive effect C handled by `h`: the body gets its vault, through which only `audit` performs. */
  def handleSensitive[C, A, R](h: SensitiveHandler[C, A, R])(body: Audit.Vault[C] => A)(implicit fx: Effects): R =
    Audit.handle[C, A, R, RuntimeException](fx, new Audit.Handler[C, A, R] {
      def value(a: A): R = h.value(a)
      def operation(op: C, k: okay.direct.Answer[Audited[C]]): Unit =
        h(op, new Answer[Audited[C]] { def apply[X](o: Effect.Op[Audited[C], X], x: X): Unit = k.answer(o, x) })
    }, new Audit.VaultBody[C, A, RuntimeException] { def run(vault: Audit.Vault[C]): A = body(vault) })

  /** The body with C's capability, every operation of it through the interceptor `i` to the vault. */
  def audit[C, A](i: Interceptor[C, Audited[C]])(body: Effect[Audited[C]] => A)(implicit vault: Audit.Vault[C]): A =
    Audit.audit[C, A, RuntimeException](vault, toJava(i),
      new okay.direct.Body[Audited[C], A, RuntimeException] { def run(effect: Effect[Audited[C]]): A = body(effect) })

  /** The body with an implementation whose every capability shows each operation to `observer` first. */
  def observe[A](observer: Observer)(body: Effects => A)(implicit fx: Effects): A =
    body(Observer.watch(fx, observer))
}

/** An audit trail: lines recorded in order. */
sealed trait Trail

object Trail {
  final case class Record(line: String) extends Trail with Effect.Op[Trail, Unit]

  def record(line: String)(implicit trail: Effect[Trail]): Unit = trail.perform(Record(line))

  /** The lines the body recorded, and its value: kept as a state, each multi-shot resumption's own. */
  def keep[A](body: Effect[Trail] => A)(implicit fx: Effects): (List[String], A) =
    handle(new StatefulHandler[Trail, List[String], A, (List[String], A)] {
      def value(lines: List[String], a: A): (List[String], A) = (lines.reverse, a)
      def apply(lines: List[String], op: Trail, k: Answer[Trail]): List[String] = op match {
        case record: Record => k(record, ()); record.line :: lines
      }
    }, Nil)(body)
}
