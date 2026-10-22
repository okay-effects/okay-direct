package okay.direct.scala3

import okay.direct.{Effect, Effects, MultiEffects}

/** A state of type S, read and written. */
enum State[S]:
  case Get[S]() extends State[S], Effect.Op[State[S], S]
  case Put[S](value: S) extends State[S], Effect.Op[State[S], Unit]

object State:
  def get[S](using state: Effect[State[S]]): S = state.perform(Get())
  def put[S](value: S)(using state: Effect[State[S]]): Unit = state.perform(Put(value))

  /** The body from `initial`: the state it ends with, and its value. Each multi-shot resumption's own. */
  def run[S, A](initial: S)(body: Effect[State[S]] ?=> A)(using Effects): (S, A) =
    handle(new StatefulHandler[State[S], S, A, (S, A)]:
      def value(s: S, a: A) = (s, a)
      def apply[X](s: S, op: State[S] & Effect.Op[State[S], X]): (X, S) = op match
        case Get() => (s, s)
        case Put(next) => ((), next)
    , initial)(body)

/** An environment of type E, read. */
enum Reader[E]:
  case Ask[E]() extends Reader[E], Effect.Op[Reader[E], E]

object Reader:
  def ask[E](using reader: Effect[Reader[E]]): E = reader.perform(Ask())

  def run[E, A](env: E)(body: Effect[Reader[E]] ?=> A)(using Effects): A =
    handle(new TailHandler[Reader[E], A, A]:
      def value(a: A) = a
      def apply[X](op: Reader[E] & Effect.Op[Reader[E], X]): X = op match
        case Ask() => env
    )(body)

/** Failure with an error of type E: the rest of the body dropped. */
enum Fail[E]:
  case Raise[E](error: E) extends Fail[E], Effect.Op[Fail[E], Nothing]

object Fail:
  def raise[E](error: E)(using fail: Effect[Fail[E]]): Nothing = fail.perform(Raise(error))

  def attempt[E, A](body: Effect[Fail[E]] ?=> A)(using Effects): Either[E, A] =
    handle(new Handler[Fail[E], A, Either[E, A]]:
      def value(a: A) = Right(a)
      def apply[X](op: Fail[E] & Effect.Op[Fail[E], X], k: X => Either[E, A]) = op match
        case Raise(e) => Left(e)
    )(body)

/** A choice of two ways: its handler resumes the rest twice, so it needs a `MultiEffects`. */
enum Choose:
  case Flip extends Choose, Effect.Op[Choose, Boolean]

object Choose:
  def flip(using choose: Effect[Choose]): Boolean = choose.perform(Flip)

  /** Every way the choices can go: the body's values, all of them. */
  def all[A](body: Effect[Choose] ?=> A)(using MultiEffects): List[A] =
    handle(new MultiHandler[Choose, A, List[A]]:
      def value(a: A) = List(a)
      def apply[X](op: Choose & Effect.Op[Choose, X], k: X => List[A]) = op match
        case Flip => k(true) ++ k(false)
    )(body)

/** A generator's values: each given to its handler, the body going on when the handler has taken it. */
enum Emits[A]:
  case Emit[A](value: A) extends Emits[A], Effect.Op[Emits[A], Unit]

object Emits:
  def emit[A](value: A)(using emits: Effect[Emits[A]]): Unit = emits.perform(Emit(value))

  /** The values the body emits, in order: kept as a state, each multi-shot resumption's own. */
  def collect[A](body: Effect[Emits[A]] ?=> Unit)(using Effects): List[A] =
    handle(new StatefulHandler[Emits[A], List[A], Unit, List[A]]:
      def value(values: List[A], u: Unit) = values.reverse
      def apply[X](values: List[A], op: Emits[A] & Effect.Op[Emits[A], X]): (X, List[A]) = op match
        case Emit(a) => ((), a :: values)
    , Nil)(body)
