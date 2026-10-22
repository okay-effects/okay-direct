package okay.direct.scala3

import okay.direct.{Answer, Audit, Audited, Effect, Effects, Observer}

/**
 * CONTROL AND AUDIT, the Scala 3 way. An interceptor's clause gets the operation as `C & Effect.Op[F, X]` and
 * returns its answer, `X` known in each case: it may tell, pass the operation on (`pass(op)`), change it or its
 * answer, or refuse — throw, or `Fail.raise`, whose type is `Nothing`.
 */
trait Interceptor[C, F]:
  def apply[X](op: C & Effect.Op[F, X], pass: Pass[F]): X

/** An operation passed on, past the interceptor, to the capability it guards. */
trait Pass[F]:
  def apply[X](op: Effect.Op[F, X]): X

/** A handler of the sensitive effect C: each operation answered in place, `X` known in each case. */
trait SensitiveHandler[C, A, R]:
  def value(a: A): R
  def apply[X](op: C & Audited.Op[C, X]): X

private def toJava[C, F](i: Interceptor[C, F]): okay.direct.Interceptor[C, F] = (op, pass, k) =>
  op match
    case o: (C & Effect.Op[F, x]) @unchecked =>
      k.answer(o, i(o, new Pass[F]:
        def apply[Y](op: Effect.Op[F, Y]): Y = pass.pass(op)))

/** The body with E's capability in scope intercepted: each of its operations through `i` first. */
def intercept[F, A](i: Interceptor[F, F])(body: Effect[F] ?=> A)(using outer: Effect[F]): A =
  body(using okay.direct.Interceptor.intercept(outer, toJava(i)))

/** The sensitive effect C handled by `h`: the body gets its vault, through which only `audit` performs. */
def handleSensitive[C, A, R](h: SensitiveHandler[C, A, R])(body: Audit.Vault[C] ?=> A)(using fx: Effects): R =
  Audit.handle[C, A, R, RuntimeException](
    fx,
    new Audit.Handler[C, A, R]:
      def value(a: A): R = h.value(a)
      def operation(op: C, k: Answer[Audited[C]]): Unit = op match
        case o: (C & Audited.Op[C, x]) @unchecked => k.answer(o, h(o))
    ,
    (vault: Audit.Vault[C]) => body(using vault)
  )

/** The body with C's capability, every operation of it through the interceptor `i` to the vault. */
def audit[C, A](i: Interceptor[C, Audited[C]])(body: Effect[Audited[C]] ?=> A)(using vault: Audit.Vault[C]): A =
  Audit.audit[C, A, RuntimeException](vault, toJava(i), (effect: Effect[Audited[C]]) => body(using effect))

/** The body with an implementation whose every capability shows each operation to `observer` first. */
def observe[A](observer: Observer)(body: Effects ?=> A)(using fx: Effects): A =
  body(using Observer.watch(fx, observer))

/** An audit trail: lines recorded in order. */
enum Trail:
  case Record(line: String) extends Trail, Effect.Op[Trail, Unit]

object Trail:
  def record(line: String)(using trail: Effect[Trail]): Unit = trail.perform(Record(line))

  /** The lines the body recorded, and its value: kept as a state, each multi-shot resumption's own. */
  def keep[A](body: Effect[Trail] ?=> A)(using Effects): (List[String], A) =
    handle(new StatefulHandler[Trail, List[String], A, (List[String], A)]:
      def value(lines: List[String], a: A) = (lines.reverse, a)
      def apply[X](lines: List[String], op: Trail & Effect.Op[Trail, X]): (X, List[String]) = op match
        case Record(line) => ((), line :: lines)
    , Nil)(body)
