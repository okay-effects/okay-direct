package okay.direct.scala2

import okay.direct.{Effect, Effects, MultiEffects}

/**
 * DELIMITED CONTINUATIONS, `shift0` and `reset0`, the Scala 2 way: a reset's prompt an implicit, a shift0 its one
 * operation. The shift0's body runs outside the reset; `k`, the rest up to it, resumes under it again. `reset`'s `k`
 * is one-shot, `resetMulti`'s, on a `MultiEffects`, not.
 */
sealed trait Reset[R]

object Reset {
  final case class Shift0[R, A](body: (A => R) => R) extends Reset[R] with Effect.Op[Reset[R], A]
}

/** The prompt of a reset whose answer is R. */
final class Prompt[R](effect: Effect[Reset[R]]) {
  /** The rest up to this prompt's reset, as `k`, to `body`: its value the reset's. */
  def shift0[A](body: (A => R) => R): A = effect.perform(Reset.Shift0[R, A](body))
}

/**
 * MONADIC REFLECTION (Filinski): a monad's computation in direct style; a monad a type class over `M[_]`. One whose
 * bind calls the rest at most once is also `Affine` and reflects by `reify` on any implementation; any other (a
 * list) by `reifyMulti`, on a `MultiEffects`.
 */
trait Monad[M[_]] {
  def pure[A](a: A): M[A]
  def flatMap[A, B](m: M[A])(f: A => M[B]): M[B]
}

/** The monad's bind calls the rest at most once. */
trait Affine[M[_]]

object Affine {
  implicit val option: Affine[Option] = Monad.OptionMonad
}

/** A value with what was written on the way to it: the writer monad's. */
final case class Written[W, A](value: A, log: List[W])

object Monad {
  implicit object OptionMonad extends Monad[Option] with Affine[Option] { // also Affine's instance
    def pure[A](a: A): Option[A] = Some(a)
    def flatMap[A, B](m: Option[A])(f: A => Option[B]): Option[B] = m.flatMap(f)
  }

  implicit object ListMonad extends Monad[List] {
    def pure[A](a: A): List[A] = List(a)
    def flatMap[A, B](m: List[A])(f: A => List[B]): List[B] = m.flatMap(f)
  }

  /** Either with its error type fixed, as a type constructor. */
  type EitherOf[E] = { type L[A] = Either[E, A] }

  /** Written with its log's type fixed, as a type constructor. */
  type WrittenOf[W] = { type L[A] = Written[W, A] }

  def either[E]: Monad[EitherOf[E]#L] with Affine[EitherOf[E]#L] = new Monad[EitherOf[E]#L] with Affine[EitherOf[E]#L] {
    def pure[A](a: A): Either[E, A] = Right(a)
    def flatMap[A, B](m: Either[E, A])(f: A => Either[E, B]): Either[E, B] = m.flatMap(f)
  }

  def written[W]: Monad[WrittenOf[W]#L] with Affine[WrittenOf[W]#L] =
    new Monad[WrittenOf[W]#L] with Affine[WrittenOf[W]#L] {
      def pure[A](a: A): Written[W, A] = Written(a, Nil)
      def flatMap[A, B](m: Written[W, A])(f: A => Written[W, B]): Written[W, B] = {
        val rest = f(m.value)
        Written(rest.value, m.log ++ rest.log)
      }
    }
}

/** The capability to reflect values of the monad M, in a reify's body. */
trait Reflect[M[_]] {
  def apply[A](ma: M[A]): A
}

object Delimited {
  private def handler[R]: Handler[Reset[R], R, R] = new Handler[Reset[R], R, R] {
    def value(r: R): R = r
    def apply(op: Reset[R], k: Resume[Reset[R], R]): R = op match {
      case shift: Reset.Shift0[R, a] => shift.body((x: a) => k(shift, x))
    }
  }

  /** A shift0 to the prompt in scope. */
  def shift0[A, R](body: (A => R) => R)(implicit prompt: Prompt[R]): A = prompt.shift0(body)

  /** The body delimited, given its prompt; its `k`s one-shot. */
  def reset[R](body: Prompt[R] => R)(implicit fx: Effects): R =
    handle(handler[R])((effect: Effect[Reset[R]]) => body(new Prompt(effect)))

  /** The body delimited, its `k`s multi-shot. */
  def resetMulti[R](body: Prompt[R] => R)(implicit fx: MultiEffects): R =
    handle(new MultiHandler[Reset[R], R, R] {
      def value(r: R): R = r
      def apply(op: Reset[R], k: Resume[Reset[R], R]): R = handler[R](op, k)
    })((effect: Effect[Reset[R]]) => body(new Prompt(effect)))

  /** The monadic value reflected: the value it binds, the rest up to the reify bound to it. */
  def reflect[M[_], A](ma: M[A])(implicit reflect: Reflect[M]): A = reflect(ma)

  private def reflecting[M[_], R](prompt: Prompt[M[R]], monad: Monad[M]): Reflect[M] = new Reflect[M] {
    def apply[A](ma: M[A]): A = prompt.shift0[A](k => monad.flatMap(ma)(k))
  }

  /** The body's computation reified, the monad affine: on any implementation. */
  def reify[M[_], R](body: Reflect[M] => R)(implicit monad: Monad[M], affine: Affine[M], fx: Effects): M[R] =
    reset[M[R]](prompt => monad.pure(body(reflecting(prompt, monad))))

  /** The body's computation reified, of any monad: on a multi-shot implementation. */
  def reifyMulti[M[_], R](body: Reflect[M] => R)(implicit monad: Monad[M], fx: MultiEffects): M[R] =
    resetMulti[M[R]](prompt => monad.pure(body(reflecting(prompt, monad))))

  /** The either monad's computation in direct style: `r(m)` reflects, a `Left` is the result, the rest not run. */
  def either[E, R](body: Reflect[Monad.EitherOf[E]#L] => R)(implicit fx: Effects): Either[E, R] = {
    val monad = Monad.either[E]
    reify[Monad.EitherOf[E]#L, R](body)(monad, monad, fx)
  }

  /** The writer monad's computation in direct style: the value, with all that was written, in order. */
  def written[W, R](body: Reflect[Monad.WrittenOf[W]#L] => R)(implicit fx: Effects): Written[W, R] = {
    val monad = Monad.written[W]
    reify[Monad.WrittenOf[W]#L, R](body)(monad, monad, fx)
  }

  /** `entry` written, under [[written]]. */
  def tell[W](entry: W)(implicit reflect: Reflect[Monad.WrittenOf[W]#L]): Unit = reflect(Written((), List(entry)))
}
