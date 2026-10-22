package okay.direct.scala2

import okay.direct.{Effect, Effects, MultiEffects}

/** A state of type S, read and written. */
sealed trait State[S]

object State {
  final case class Get[S]() extends State[S] with Effect.Op[State[S], S]
  final case class Put[S](value: S) extends State[S] with Effect.Op[State[S], Unit]

  def get[S](implicit state: Effect[State[S]]): S = state.perform(Get[S]())
  def put[S](value: S)(implicit state: Effect[State[S]]): Unit = state.perform(Put(value))

  /** The body from `initial`: the state it ends with, and its value. Each multi-shot resumption's own. */
  def run[S, A](initial: S)(body: Effect[State[S]] => A)(implicit fx: Effects): (S, A) =
    handle(new StatefulHandler[State[S], S, A, (S, A)] {
      def value(s: S, a: A): (S, A) = (s, a)
      def apply(s: S, op: State[S], k: Answer[State[S]]): S = op match {
        case get: Get[S] => k(get, s); s
        case put: Put[S] => k(put, ()); put.value
      }
    }, initial)(body)
}

/** An environment of type E, read. */
sealed trait Reader[E]

object Reader {
  final case class Ask[E]() extends Reader[E] with Effect.Op[Reader[E], E]

  def ask[E](implicit reader: Effect[Reader[E]]): E = reader.perform(Ask[E]())

  def run[E, A](env: E)(body: Effect[Reader[E]] => A)(implicit fx: Effects): A =
    handle(new TailHandler[Reader[E], A, A] {
      def value(a: A): A = a
      def apply(op: Reader[E], k: Answer[Reader[E]]): Unit = op match {
        case ask: Ask[E] => k(ask, env)
      }
    })(body)
}

/** Failure with an error of type E: the rest of the body dropped. */
sealed trait Fail[E]

object Fail {
  final case class Raise[E](error: E) extends Fail[E] with Effect.Op[Fail[E], Nothing]

  def raise[E](error: E)(implicit fail: Effect[Fail[E]]): Nothing = fail.perform(Raise(error))

  def attempt[E, A](body: Effect[Fail[E]] => A)(implicit fx: Effects): Either[E, A] =
    handle(new Handler[Fail[E], A, Either[E, A]] {
      def value(a: A): Either[E, A] = Right(a)
      def apply(op: Fail[E], k: Resume[Fail[E], Either[E, A]]): Either[E, A] = op match {
        case Raise(e) => Left(e)
      }
    })(body)
}

/** A choice of two ways: its handler resumes the rest twice, so it needs a `MultiEffects`. */
sealed trait Choose

object Choose {
  case object Flip extends Choose with Effect.Op[Choose, Boolean]

  def flip(implicit choose: Effect[Choose]): Boolean = choose.perform(Flip)

  /** Every way the choices can go: the body's values, all of them. */
  def all[A](body: Effect[Choose] => A)(implicit fx: MultiEffects): List[A] =
    handle(new MultiHandler[Choose, A, List[A]] {
      def value(a: A): List[A] = List(a)
      def apply(op: Choose, k: Resume[Choose, List[A]]): List[A] = op match {
        case Flip => k(Flip, true) ++ k(Flip, false)
      }
    })(body)
}

/** A generator's values: each given to its handler, the body going on when the handler has taken it. */
sealed trait Emits[A]

object Emits {
  final case class Emit[A](value: A) extends Emits[A] with Effect.Op[Emits[A], Unit]

  def emit[A](value: A)(implicit emits: Effect[Emits[A]]): Unit = emits.perform(Emit(value))

  /** The values the body emits, in order: kept as a state, each multi-shot resumption's own. */
  def collect[A](body: Effect[Emits[A]] => Unit)(implicit fx: Effects): List[A] =
    handle(new StatefulHandler[Emits[A], List[A], Unit, List[A]] {
      def value(values: List[A], u: Unit): List[A] = values.reverse
      def apply(values: List[A], op: Emits[A], k: Answer[Emits[A]]): List[A] = op match {
        case emit: Emit[A] => k(emit, ()); emit.value :: values
      }
    }, Nil)(body)
}
