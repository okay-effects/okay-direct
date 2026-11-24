package okay.direct.scala2

import okay.direct.{Effect, Effects, MultiEffects}

/** A state of type S, read and written. */
sealed trait State[S]

object State {
  final case class Get[S]() extends State[S] with Op[State[S], S]
  final case class Put[S](value: S) extends State[S] with Op[State[S], Unit]

  def get[S](implicit state: Effect[State[S]]): S = state.perform(Get[S]())
  def put[S](value: S)(implicit state: Effect[State[S]]): Unit = state.perform(Put(value))

  /** The body from `initial`: the state it ends with, and its value. Each multi-shot resumption's own. */
  def run[S, A](initial: S)(body: Effect[State[S]] => A)(implicit fx: Effects): (S, A) =
    handle(new StatefulHandler[State[S], S, A, (S, A)] {
      def ret(s: S, a: A): (S, A) = (s, a)
      def answer(s: S, op: State[S], k: Answer[State[S]]): S = op match {
        case get: Get[S] => k(get, s); s
        case put: Put[S] => k(put, ()); put.value
      }
    }, initial)(body)
}

/** An environment of type E, read. */
sealed trait Reader[E]

object Reader {
  final case class Ask[E]() extends Reader[E] with Op[Reader[E], E]

  def ask[E](implicit reader: Effect[Reader[E]]): E = reader.perform(Ask[E]())

  def run[E, A](env: E)(body: Effect[Reader[E]] => A)(implicit fx: Effects): A =
    handle(new InPlace[Reader[E], A, A] {
      def ret(a: A): A = a
      def answer(op: Reader[E], k: Answer[Reader[E]]): Unit = op match {
        case ask: Ask[E] => k(ask, env)
      }
    })(body)
}

/** THROWS: a failure with an error of type E; the rest of the body dropped, unwound, before it is answered. */
sealed trait Throws[E]

object Throws {
  final case class Raise[E](error: E) extends Throws[E] with Op[Throws[E], Nothing]

  def raise[E](error: E)(implicit fail: Effect[Throws[E]]): Nothing = fail.perform(Raise(error))

  def run[E, A](body: Effect[Throws[E]] => A)(implicit fx: Effects): Either[E, A] =
    handle(new Handler[Throws[E], A, Either[E, A]] {
      def ret(a: A): Either[E, A] = Right(a)
      def on(op: Throws[E], k: Resume[Throws[E], Either[E, A]]): Either[E, A] = op match {
        case Raise(e) => Left(e)
      }
    })(body)

  /** The body's value, or what `rescue` makes of its failure: the rest dropped, unwound, first. */
  def recover[E, A](rescue: E => A)(body: Effect[Throws[E]] => A)(implicit fx: Effects): A =
    run[E, A](body).fold(rescue, identity)
}

/** A choice of two ways: its handler resumes the rest twice, so it needs a `MultiEffects`. */
sealed trait Choose

object Choose {
  case object Flip extends Choose with Op[Choose, Boolean]

  def flip(implicit choose: Effect[Choose]): Boolean = choose.perform(Flip)

  /** Every way the choices can go: the body's values, all of them. */
  def all[A](body: Effect[Choose] => A)(implicit fx: MultiEffects): List[A] =
    handle(new MultiHandler[Choose, A, List[A]] {
      def ret(a: A): List[A] = List(a)
      def on(op: Choose, k: Resume[Choose, List[A]]): List[A] = op match {
        case Flip => k(Flip, true) ++ k(Flip, false)
      }
    })(body)
}

/** A generator's values: each given to its handler, the body going on when the handler has taken it. */
sealed trait Emits[A]

object Emits {
  final case class Emit[A](value: A) extends Emits[A] with Op[Emits[A], Unit]

  def emit[A](value: A)(implicit emits: Effect[Emits[A]]): Unit = emits.perform(Emit(value))

  /** The values the body emits, in order: kept as a state, each multi-shot resumption's own. */
  def collect[A](body: Effect[Emits[A]] => Unit)(implicit fx: Effects): List[A] =
    handle(new StatefulHandler[Emits[A], List[A], Unit, List[A]] {
      def ret(values: List[A], u: Unit): List[A] = values.reverse
      def answer(values: List[A], op: Emits[A], k: Answer[Emits[A]]): List[A] = op match {
        case emit: Emit[A] => k(emit, ()); emit.value :: values
      }
    }, Nil)(body)
}
