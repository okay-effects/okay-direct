package okay.direct.scala3

import okay.direct.{Effect, Effects, MultiEffects}

/**
 * DELIMITED CONTINUATIONS, `shift0` and `reset0`, the Scala 3 way: a reset's prompt is a context parameter, a shift0
 * its one operation. The shift0's body runs outside the reset; `k`, the rest up to it, resumes under it again. The
 * answer type `R` is the reset's throughout. `reset`'s `k` is one-shot, `resetMulti`'s, on a `MultiEffects`, not.
 */
enum Reset[R]:
  case Shift0[R, A](body: (A => R) => R) extends Reset[R], Effect.Op[Reset[R], A]

/** The prompt of a reset whose answer is R. */
final class Prompt[R](effect: Effect[Reset[R]]):
  /** The rest up to this prompt's reset, as `k`, to `body`: its value the reset's. */
  def shift0[A](body: (A => R) => R): A = effect.perform(Reset.Shift0[R, A](body))

/** A shift0 to the prompt in scope. */
def shift0[A, R](body: (A => R) => R)(using prompt: Prompt[R]): A = prompt.shift0(body)

/** The body delimited, its prompt in scope; its `k`s one-shot. */
def reset[R](body: Prompt[R] ?=> R)(using Effects): R =
  handle(new Handler[Reset[R], R, R]:
    def value(r: R) = r
    def apply[X](op: Reset[R] & Effect.Op[Reset[R], X], k: X => R): R = op match
      case Reset.Shift0(f) => f(k)
  )(body(using Prompt(summon[Effect[Reset[R]]])))

/** The body delimited, its `k`s multi-shot. */
def resetMulti[R](body: Prompt[R] ?=> R)(using MultiEffects): R =
  handle(new MultiHandler[Reset[R], R, R]:
    def value(r: R) = r
    def apply[X](op: Reset[R] & Effect.Op[Reset[R], X], k: X => R): R = op match
      case Reset.Shift0(f) => f(k)
  )(body(using Prompt(summon[Effect[Reset[R]]])))

/**
 * MONADIC REFLECTION (Filinski): a monad's computation in direct style. `reify` runs the body and gives its value to
 * `pure`; `reflect(m)` in it binds `m`, the rest up to the reify the function given to `flatMap`. A monad is a type
 * class over `M[_]`; one whose bind calls the rest at most once is also `Affine` and reflects on any implementation,
 * by `reify`; any other (a list) by `reifyMulti`, on a `MultiEffects`: `reify` of a `List` does not compile.
 */
trait Monad[M[_]]:
  def pure[A](a: A): M[A]
  def flatMap[A, B](m: M[A])(f: A => M[B]): M[B]

/** The monad's bind calls the rest at most once. */
trait Affine[M[_]]

object Affine:
  given Affine[Option] = Monad.OptionMonad
  given either[E]: Affine[[A] =>> Either[E, A]] = new Affine[[A] =>> Either[E, A]] {}
  given written[W]: Affine[[A] =>> Written[W, A]] = new Affine[[A] =>> Written[W, A]] {}

/** A value with what was written on the way to it: the writer monad's. */
final case class Written[W, A](value: A, log: List[W])

object Monad:
  object OptionMonad extends Monad[Option], Affine[Option]:
    def pure[A](a: A) = Some(a)
    def flatMap[A, B](m: Option[A])(f: A => Option[B]) = m.flatMap(f)
  given Monad[Option] = OptionMonad

  given Monad[List] with
    def pure[A](a: A) = List(a)
    def flatMap[A, B](m: List[A])(f: A => List[B]) = m.flatMap(f)

  given either[E]: Monad[[A] =>> Either[E, A]] with
    def pure[A](a: A) = Right(a)
    def flatMap[A, B](m: Either[E, A])(f: A => Either[E, B]) = m.flatMap(f)

  given written[W]: Monad[[A] =>> Written[W, A]] with
    def pure[A](a: A) = Written(a, Nil)
    def flatMap[A, B](m: Written[W, A])(f: A => Written[W, B]) =
      val rest = f(m.value)
      Written(rest.value, m.log ++ rest.log)

/** The capability to reflect values of the monad M, in a reify's body. */
trait Reflect[M[_]]:
  def apply[A](ma: M[A]): A

/** The monadic value reflected: the value it binds, the rest up to the reify bound to it. */
def reflect[M[_], A](ma: M[A])(using reflect: Reflect[M]): A = reflect(ma)

private def reflecting[M[_], R](prompt: Prompt[M[R]], monad: Monad[M]): Reflect[M] = new Reflect[M]:
  def apply[A](ma: M[A]): A = prompt.shift0[A](k => monad.flatMap(ma)(k))

/** The body's computation reified, the monad affine: on any implementation. */
def reify[M[_], R](body: Reflect[M] ?=> R)(using monad: Monad[M], affine: Affine[M], fx: Effects): M[R] =
  reset[M[R]](monad.pure(body(using reflecting(summon[Prompt[M[R]]], monad))))

/** The body's computation reified, of any monad: on a multi-shot implementation. */
def reifyMulti[M[_], R](body: Reflect[M] ?=> R)(using monad: Monad[M], fx: MultiEffects): M[R] =
  resetMulti[M[R]](monad.pure(body(using reflecting(summon[Prompt[M[R]]], monad))))

/** The either monad's computation in direct style: a `Left` reflected is the result, the rest not run. */
def either[E, R](body: Reflect[[A] =>> Either[E, A]] ?=> R)(using Effects): Either[E, R] =
  reify[[A] =>> Either[E, A], R](body)

/** The writer monad's computation in direct style: the value, with all that was written, in order. */
def written[W, R](body: Reflect[[A] =>> Written[W, A]] ?=> R)(using Effects): Written[W, R] =
  reify[[A] =>> Written[W, A], R](body)

/** `entry` written, under [[written]]. */
def tell[W](entry: W)(using reflect: Reflect[[A] =>> Written[W, A]]): Unit = reflect(Written((), List(entry)))
