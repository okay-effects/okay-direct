package okay.direct

/**
 * THE SCALA 2 FRONT END over okay-direct's API, on any of its implementations. An effect is a sealed trait, its
 * operations case classes, each an `Effect.Op` of the effect with its answer type:
 * {{{
 * sealed trait State[S]
 * object State {
 *   final case class Get[S]() extends State[S] with Effect.Op[State[S], S]
 *   final case class Put[S](value: S) extends State[S] with Effect.Op[State[S], Unit]
 * }
 * }}}
 * A capability is an implicit, `Effect[E]`: a body is `implicit e => ...`, and a function performing E takes it
 * `(implicit state: Effect[State[Int]])`. A clause matches the operation exhaustively and answers through `k`,
 * the operation the witness of its answer's type: `case get: Get[S] => k(get, state)`. Scala 2 infers the answer
 * type from a pattern only partly, and its match analysis is wrong on that, so the witness stays, as in Java.
 */
package object scala2 {
  /** The body, run with E's capability, handled by `h`. */
  def handle[E, A, R](h: Handler[E, A, R])(body: Effect[E] => A)(implicit fx: Effects): R =
    fx.handle[E, A, R, RuntimeException](new okay.direct.Handler[E, A, R, RuntimeException] {
      def value(a: A): R = h.value(a)
      def operation(op: E, k: okay.direct.Resume[E, R, RuntimeException]): R =
        h(op, new Resume[E, R] { def apply[X](o: Effect.Op[E, X], x: X): R = k.resume(o, x) })
    }, (effect: Effect[E]) => body(effect))

  def handle[E, A, R](h: TailHandler[E, A, R])(body: Effect[E] => A)(implicit fx: Effects): R =
    fx.handle[E, A, R, RuntimeException](new okay.direct.TailHandler[E, A, R] {
      def value(a: A): R = h.value(a)
      def operation(op: E, k: okay.direct.Answer[E]): Unit =
        h(op, new Answer[E] { def apply[X](o: Effect.Op[E, X], x: X): Unit = k.answer(o, x) })
    }, (effect: Effect[E]) => body(effect))

  def handle[E, S, A, R](h: StatefulHandler[E, S, A, R], initial: S)(body: Effect[E] => A)(implicit fx: Effects): R =
    fx.handle[E, S, A, R, RuntimeException](new okay.direct.StatefulHandler[E, S, A, R] {
      def value(s: S, a: A): R = h.value(s, a)
      def operation(s: S, op: E, k: okay.direct.Answer[E]): S =
        h(s, op, new Answer[E] { def apply[X](o: Effect.Op[E, X], x: X): Unit = k.answer(o, x) })
    }, initial, new okay.direct.Body[E, A, RuntimeException] { def run(effect: Effect[E]): A = body(effect) })

  def handle[E, A, R](h: MultiHandler[E, A, R])(body: Effect[E] => A)(implicit fx: MultiEffects): R =
    fx.handle[E, A, R, RuntimeException](new okay.direct.MultiHandler[E, A, R, RuntimeException] {
      def value(a: A): R = h.value(a)
      def operation(op: E, k: okay.direct.MultiResume[E, R, RuntimeException]): R =
        h(op, new Resume[E, R] { def apply[X](o: Effect.Op[E, X], x: X): R = k.resume(o, x) })
    }, (effect: Effect[E]) => body(effect))
}
