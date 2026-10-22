package okay.direct.scala3

import okay.direct.{Effect, Effects, MultiEffects, MultiResume, Resume}

/**
 * THE SCALA 3 FRONT END over okay-direct's API, on any of its implementations. An effect is a GADT: an enum whose
 * cases are its operations, each an `Effect.Op` of the effect with its answer type:
 * {{{
 * enum State[S]:
 *   case Get[S]() extends State[S], Effect.Op[State[S], S]
 *   case Put[S](value: S) extends State[S], Effect.Op[State[S], Unit]
 * }}}
 * A capability is a context parameter, `Effect[E]`: a body is `Effect[E] ?=> A`, and a function performing E says
 * so, `(using Effect[State[Int]])` — a missing handler does not compile. A clause gets the operation as
 * `E & Effect.Op[E, X]`: matched exhaustively, each case knowing X by GADT inference — no witness, no cast.
 */

/** A handler of E: the body's value to the result, and an operation with `k`, the rest of the body: ONE-SHOT. */
trait Handler[E, A, R]:
  def value(a: A): R
  def apply[X](op: E & Effect.Op[E, X], k: X => R): R

/** A handler of E that may resume `k` any number of times: runs only on a `MultiEffects`. */
trait MultiHandler[E, A, R]:
  def value(a: A): R
  def apply[X](op: E & Effect.Op[E, X], k: X => R): R

/** A handler of E answering every operation in place: in constant stack. */
trait TailHandler[E, A, R]:
  def value(a: A): R
  def apply[X](op: E & Effect.Op[E, X]): X

/**
 * A handler of E with a state kept by the implementation: each operation answered in place with the state of its
 * time, returning the answer and the state to go on with. Under a multi-shot handler further out, each resumption
 * has its own: a state inside a choice is each branch's, one outside is threaded through them.
 */
trait StatefulHandler[E, S, A, R]:
  def value(s: S, a: A): R
  def apply[X](s: S, op: E & Effect.Op[E, X]): (X, S)

/** The body, run with E's capability in scope, handled by `h`. */
def handle[E, A, R](h: Handler[E, A, R])(body: Effect[E] ?=> A)(using fx: Effects): R =
  fx.handle(
    new okay.direct.Handler[E, A, R, RuntimeException]:
      def value(a: A): R = h.value(a)
      def operation(op: E, k: Resume[E, R, RuntimeException]): R = op match
        case o: (E & Effect.Op[E, x]) @unchecked => h(o, (a: x) => k.resume(o, a))
    ,
    (effect: Effect[E]) => body(using effect)
  )

def handle[E, A, R](h: TailHandler[E, A, R])(body: Effect[E] ?=> A)(using fx: Effects): R =
  fx.handle(
    new okay.direct.TailHandler[E, A, R]:
      def value(a: A): R = h.value(a)
      def operation(op: E, k: okay.direct.Answer[E]): Unit = op match
        case o: (E & Effect.Op[E, x]) @unchecked => k.answer(o, h(o))
    ,
    (effect: Effect[E]) => body(using effect)
  )

def handle[E, S, A, R](h: StatefulHandler[E, S, A, R], initial: S)(body: Effect[E] ?=> A)(using fx: Effects): R =
  fx.handle[E, S, A, R, RuntimeException](
    new okay.direct.StatefulHandler[E, S, A, R]:
      def value(s: S, a: A): R = h.value(s, a)
      def operation(s: S, op: E, k: okay.direct.Answer[E]): S = op match
        case o: (E & Effect.Op[E, x]) @unchecked =>
          val (answer, next) = h(s, o)
          k.answer(o, answer)
          next
    ,
    initial,
    (effect: Effect[E]) => body(using effect)
  )

def handle[E, A, R](h: MultiHandler[E, A, R])(body: Effect[E] ?=> A)(using fx: MultiEffects): R =
  fx.handle(
    new okay.direct.MultiHandler[E, A, R, RuntimeException]:
      def value(a: A): R = h.value(a)
      def operation(op: E, k: MultiResume[E, R, RuntimeException]): R = op match
        case o: (E & Effect.Op[E, x]) @unchecked => h(o, (a: x) => k.resume(o, a))
    ,
    (effect: Effect[E]) => body(using effect)
  )
