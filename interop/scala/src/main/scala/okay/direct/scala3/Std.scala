package okay.direct.scala3

import okay.direct.{Effect, Effects, MultiEffects}

/** A state of type S, read and written. */
enum State[S]:
  case Get[S]() extends State[S], Op[State[S], S]
  case Put[S](value: S) extends State[S], Op[State[S], Unit]

object State:
  def get[S](using state: Effect[State[S]]): S = state.perform(Get())
  def put[S](value: S)(using state: Effect[State[S]]): Unit = state.perform(Put(value))

  /** The body from `initial`: the state it ends with, and its value. Each multi-shot resumption's own. */
  def run[S, A](initial: S)(body: Effect[State[S]] ?=> A)(using Effects): (S, A) =
    handle(new StatefulHandler[State[S], S, A, (S, A)]:
      def ret(s: S, a: A) = (s, a)
      def answer[X](s: S, op: State[S] & Op[State[S], X]): (X, S) = op match
        case Get() => (s, s)
        case Put(next) => ((), next)
    , initial)(body)

/** An environment of type E, read. */
enum Reader[E]:
  case Ask[E]() extends Reader[E], Op[Reader[E], E]

object Reader:
  def ask[E](using reader: Effect[Reader[E]]): E = reader.perform(Ask())

  def run[E, A](env: E)(body: Effect[Reader[E]] ?=> A)(using Effects): A =
    handle(new InPlace[Reader[E], A, A]:
      def ret(a: A) = a
      def answer[X](op: Reader[E] & Op[Reader[E], X]): X = op match
        case Ask() => env
    )(body)

/** THROWS: a failure with an error of type E; the rest of the body dropped, unwound, before it is answered. */
enum Throws[E]:
  case Raise[E](error: E) extends Throws[E], Op[Throws[E], Nothing]

object Throws:
  def raise[E](error: E)(using fail: Effect[Throws[E]]): Nothing = fail.perform(Raise(error))

  def run[E, A](body: Effect[Throws[E]] ?=> A)(using Effects): Either[E, A] =
    handle(new Handler[Throws[E], A, Either[E, A]]:
      def ret(a: A) = Right(a)
      def on[X](op: Throws[E] & Op[Throws[E], X], k: X => Either[E, A]) = op match
        case Raise(e) => Left(e)
    )(body)

  /** The body's value, or what `rescue` makes of its failure: the rest dropped, unwound, first. */
  def recover[E, A](rescue: E => A)(body: Effect[Throws[E]] ?=> A)(using Effects): A =
    run[E, A](body).fold(rescue, identity)

/** A choice of two ways: its handler resumes the rest twice, so it needs a `MultiEffects`. */
enum Choose:
  case Flip extends Choose, Op[Choose, Boolean]

object Choose:
  def flip(using choose: Effect[Choose]): Boolean = choose.perform(Flip)

  /** Every way the choices can go: the body's values, all of them. */
  def all[A](body: Effect[Choose] ?=> A)(using MultiEffects): List[A] =
    handle(new MultiHandler[Choose, A, List[A]]:
      def ret(a: A) = List(a)
      def on[X](op: Choose & Op[Choose, X], k: X => List[A]) = op match
        case Flip => k(true) ++ k(false)
    )(body)

/** A generator's values: each given to its handler, the body going on when the handler has taken it. */
enum Emit[A]:
  case Send[A](value: A) extends Emit[A], Op[Emit[A], Unit]

object Emit:
  def send[A](value: A)(using emit: Effect[Emit[A]]): Unit = emit.perform(Send(value))

  /** The values the body emits, in order: kept as a state, each multi-shot resumption's own. */
  def collect[A](body: Effect[Emit[A]] ?=> Unit)(using Effects): List[A] =
    handle(new StatefulHandler[Emit[A], List[A], Unit, List[A]]:
      def ret(values: List[A], u: Unit) = values.reverse
      def answer[X](values: List[A], op: Emit[A] & Op[Emit[A], X]): (X, List[A]) = op match
        case Send(a) => ((), a :: values)
    , Nil)(body)
