package okay.direct

/**
 * THE SCALA 2 FRONT END over okay-direct's API, on any of its implementations. An effect is a sealed trait, its
 * operations case classes, each an `Op` of the effect with its answer type:
 * {{{
 * sealed trait State[S]
 * object State {
 *   final case class Get[S]() extends State[S] with Op[State[S], S]
 *   final case class Put[S](value: S) extends State[S] with Op[State[S], Unit]
 * }
 * }}}
 * A capability is an implicit, `Effect[E]`: a body is `implicit e => ...`, and a function performing E takes it
 * `(implicit state: Effect[State[Int]])`. A clause matches the operation exhaustively and answers through `k`,
 * the operation the witness of its answer's type: `case get: Get[S] => k(get, state)`. Scala 2 infers the answer
 * type from a pattern only partly, and its match analysis is wrong on that, so the witness stays, as in Java.
 */
package object scala2 {
  /** An operation of the effect `F`, answered by an `X`: okay-java-api's `okay.effect.Op`, shared with okay-java and
    * every Java library of the family, its `Self` the effect itself. */
  type Op[F, X] = okay.effect.Op[F, X, F]

  /** An operation of the sensitive effect `C`, answered by an `X` (okay-direct's `Audited.Op`). */
  type AuditedOp[C, X] = okay.direct.Audited.Op[C, X, okay.direct.Audited[C]]

  /** The capability of asynchrony and structured concurrency: okay-direct's `std.Async`, see [[Async$]]. */
  type Async = okay.direct.std.Async

  /** The time as an effect: okay-direct's `std.Clock`, see [[Clock$]]. */
  type Clock = okay.direct.std.Clock

  /** SQL as an effect (module `okay-direct-sql` on the class path), see [[Sql$]]. */
  type Sql = okay.direct.sql.Sql

  /** The body, run with E's capability, handled by `h`. */
  def handle[E, A, B](h: Handler[E, A, B])(body: Effect[E] => A)(implicit fx: Effects): B =
    fx.handle[E, A, B, RuntimeException](new okay.direct.Handler[E, A, B, RuntimeException] {
      def ret(a: A): B = h.ret(a)
      def on(op: E, k: okay.direct.Resume[E, B, RuntimeException]): B =
        h.on(op, new Resume[E, B] { def apply[X](o: Op[E, X], x: X): B = k.resume(o, x) })
    }, (effect: Effect[E]) => body(effect))

  def handle[E, A, B](h: InPlace[E, A, B])(body: Effect[E] => A)(implicit fx: Effects): B =
    fx.handle[E, A, B, RuntimeException](new okay.direct.InPlace[E, A, B] {
      def ret(a: A): B = h.ret(a)
      def answer(op: E, k: okay.direct.Answer[E]): Unit =
        h.answer(op, new Answer[E] { def apply[X](o: Op[E, X], x: X): Unit = k.answer(o, x) })
    }, (effect: Effect[E]) => body(effect))

  def handle[E, S, A, B](h: StatefulHandler[E, S, A, B], initial: S)(body: Effect[E] => A)(implicit fx: Effects): B =
    fx.handle[E, S, A, B, RuntimeException](new okay.direct.StatefulHandler[E, S, A, B] {
      def ret(s: S, a: A): B = h.ret(s, a)
      def answer(s: S, op: E, k: okay.direct.Answer[E]): S =
        h.answer(s, op, new Answer[E] { def apply[X](o: Op[E, X], x: X): Unit = k.answer(o, x) })
    }, initial, new okay.direct.Body[E, A, RuntimeException] { def run(effect: Effect[E]): A = body(effect) })

  def handle[E, A, B](h: MultiHandler[E, A, B])(body: Effect[E] => A)(implicit fx: MultiEffects): B =
    fx.handle[E, A, B, RuntimeException](new okay.direct.MultiHandler[E, A, B, RuntimeException] {
      def ret(a: A): B = h.ret(a)
      def on(op: E, k: okay.direct.MultiResume[E, B, RuntimeException]): B =
        h.on(op, new Resume[E, B] { def apply[X](o: Op[E, X], x: X): B = k.resume(o, x) })
    }, (effect: Effect[E]) => body(effect))
}
