package okay.direct.scala3

import okay.direct.{Effect, Effects, MultiEffects, MultiResume, Resume}

/**
 * THE SCALA 3 FRONT END over okay-direct's API, on any of its implementations. An effect is a GADT: an enum whose
 * cases are its operations, each an `Effect.Op` of the effect with its answer type:
 * {{{
 * enum State[S]:
 *   case Get[S]() extends State[S], Op[State[S], S]
 *   case Put[S](value: S) extends State[S], Op[State[S], Unit]
 * }}}
 * A capability is a context parameter, `Effect[E]`: a body is `Effect[E] ?=> A`, and a function performing E says
 * so, `(using Effect[State[Int]])` — a missing handler does not compile. A clause gets the operation as
 * `E & Op[E, X]`: matched exhaustively, each case knowing X by GADT inference — no witness, no cast.
 */

/** A handler of E: the body's value to the result, and an operation with `k`, the rest of the body: ONE-SHOT. */
trait Handler[E, A, B]:
  def ret(a: A): B
  def on[X](op: E & Op[E, X], k: X => B): B

/** A handler of E that may resume `k` any number of times: runs only on a `MultiEffects`. */
trait MultiHandler[E, A, B]:
  def ret(a: A): B
  def on[X](op: E & Op[E, X], k: X => B): B

/** A handler of E answering every operation in place: in constant stack. */
trait InPlace[E, A, B]:
  def ret(a: A): B
  def answer[X](op: E & Op[E, X]): X

/**
 * A handler of E with a state kept by the implementation: each operation answered in place with the state of its
 * time, returning the answer and the state to go on with. Under a multi-shot handler further out, each resumption
 * has its own: a state inside a choice is each branch's, one outside is threaded through them.
 */
trait StatefulHandler[E, S, A, B]:
  def ret(s: S, a: A): B
  def answer[X](s: S, op: E & Op[E, X]): (X, S)

/** The body, run with E's capability in scope, handled by `h`. */
def handle[E, A, B](h: Handler[E, A, B])(body: Effect[E] ?=> A)(using fx: Effects): B =
  fx.handle(
    new okay.direct.Handler[E, A, B, RuntimeException]:
      def ret(a: A): B = h.ret(a)
      def on(op: E, k: Resume[E, B, RuntimeException]): B = op match
        case o: (E & Op[E, x]) @unchecked => h.on(o, (a: x) => k.resume(o, a))
    ,
    (effect: Effect[E]) => body(using effect)
  )

def handle[E, A, B](h: InPlace[E, A, B])(body: Effect[E] ?=> A)(using fx: Effects): B =
  fx.handle(
    new okay.direct.InPlace[E, A, B]:
      def ret(a: A): B = h.ret(a)
      def answer(op: E, k: okay.direct.Answer[E]): Unit = op match
        case o: (E & Op[E, x]) @unchecked => k.answer(o, h.answer(o))
    ,
    (effect: Effect[E]) => body(using effect)
  )

def handle[E, S, A, B](h: StatefulHandler[E, S, A, B], initial: S)(body: Effect[E] ?=> A)(using fx: Effects): B =
  fx.handle[E, S, A, B, RuntimeException](
    new okay.direct.StatefulHandler[E, S, A, B]:
      def ret(s: S, a: A): B = h.ret(s, a)
      def answer(s: S, op: E, k: okay.direct.Answer[E]): S = op match
        case o: (E & Op[E, x]) @unchecked =>
          val (answer, next) = h.answer(s, o)
          k.answer(o, answer)
          next
    ,
    initial,
    (effect: Effect[E]) => body(using effect)
  )

def handle[E, A, B](h: MultiHandler[E, A, B])(body: Effect[E] ?=> A)(using fx: MultiEffects): B =
  fx.handle(
    new okay.direct.MultiHandler[E, A, B, RuntimeException]:
      def ret(a: A): B = h.ret(a)
      def on(op: E, k: MultiResume[E, B, RuntimeException]): B = op match
        case o: (E & Op[E, x]) @unchecked => h.on(o, (a: x) => k.resume(o, a))
    ,
    (effect: Effect[E]) => body(using effect)
  )
