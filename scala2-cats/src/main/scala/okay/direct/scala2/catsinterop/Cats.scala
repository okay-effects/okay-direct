package okay.direct.scala2.catsinterop

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.IORuntime
import okay.direct.{Effect, Effects}
import okay.direct.scala2.{Affine, Answer, Emits, Monad, TailHandler, handle}

/** IOs awaited in a direct-style body. */
sealed trait Awaits

object Awaits {
  final case class Await[A](io: IO[A]) extends Awaits with Effect.Op[Awaits, A]
}

/**
 * CATS-EFFECT AND THE DIRECT STYLE, Scala 2. An `IO` in a body is AWAITED: run on cats-effect's runtime, the body
 * waiting (cheap on a virtual thread), its failure thrown at the await. A body is an `IO` by `toIO`. A strict cats
 * monad reflects through `monad`; a lazy one (`IO`, `Eval`) is awaited, not reflected.
 */
object Cats {
  private trait Run {
    def apply[X](io: IO[X]): X
  }

  /** The IO run, the body waiting for its value; its failure thrown here. */
  def await[A](io: IO[A])(implicit awaits: Effect[Awaits]): A = awaits.perform(Awaits.Await(io))

  private def awaiting[A](run: Run)(body: Effect[Awaits] => A)(implicit fx: Effects): A =
    handle(new TailHandler[Awaits, A, A] {
      def value(a: A): A = a
      def apply(op: Awaits, k: Answer[Awaits]): Unit = op match {
        case await: Awaits.Await[x] => k(await, run(await.io))
      }
    })(body)

  /** The body run here, its IOs on `runtime`. */
  def runAwaiting[A](body: Effect[Awaits] => A)(implicit fx: Effects, runtime: IORuntime): A =
    awaiting(new Run { def apply[X](io: IO[X]): X = io.unsafeRunSync()(runtime) })(body)

  /** The body as an IO: run when the IO runs, on a blocking thread, interruptibly; its IOs on the IO's own runtime. */
  def toIO[A](body: Effect[Awaits] => A)(implicit fx: Effects): IO[A] =
    Dispatcher.sequential[IO].use { dispatcher =>
      IO.interruptible(awaiting(new Run { def apply[X](io: IO[X]): X = dispatcher.unsafeRunSync(io) })(body))
    }

  // ---- PARALLEL: each body an IO on its own thread, awaited together; a parallel body has the capabilities given
  // to it here (Awaits), not those of the body it is in, confined to that body's thread.

  /** Both bodies at once: their values. */
  def both[A, B](left: Effect[Awaits] => A, right: Effect[Awaits] => B)(implicit awaits: Effect[Awaits], fx: Effects): (A, B) =
    await(IO.both(toIO(left), toIO(right)))

  /** The first body to end: its value; the other cancelled. */
  def race[A, B](left: Effect[Awaits] => A, right: Effect[Awaits] => B)(implicit awaits: Effect[Awaits], fx: Effects): Either[A, B] =
    await(IO.race(toIO(left), toIO(right)))

  // ---- RESOURCES: acquired and released in the body, which keeps all its capabilities.

  /** The resource acquired, the body run with it, released after however the body ends. */
  def use[R, A](resource: cats.effect.Resource[IO, R])(body: R => A)(implicit awaits: Effect[Awaits]): A = {
    val (r, release) = await(resource.allocated)
    try body(r)
    finally await(release)
  }

  // ---- STREAMS

  private sealed trait Pulled[+A]
  private final case class Item[A](a: A) extends Pulled[A]
  private case object End extends Pulled[Nothing]
  private final case class Failed(t: Throwable) extends Pulled[Nothing]

  /** Each element of the stream to `f`, in THIS body, with all its capabilities; cancelled if the body leaves early. */
  def foreach[A](stream: fs2.Stream[IO, A])(f: A => Unit)(implicit awaits: Effect[Awaits]): Unit = {
    val handoff = new java.util.concurrent.SynchronousQueue[Pulled[A]]()
    def put(p: Pulled[A]): IO[Unit] = IO.interruptible(handoff.put(p))
    val fiber = await(stream.evalMap(a => put(Item(a))).compile.drain.redeemWith(e => put(Failed(e)), _ => put(End)).start)
    try {
      var going = true
      while (going) handoff.take() match {
        case Item(a) => f(a)
        case End => going = false
        case Failed(t) => throw t
      }
    } finally await(fiber.cancel)
  }

  /** The body as a stream of what it emits: run when the stream is pulled, each emit waiting for the stream to take it. */
  def toStream[A](body: (Effect[Emits[A]], Effect[Awaits]) => Unit)(implicit fx: Effects): fs2.Stream[IO, A] =
    fs2.Stream.eval(cats.effect.std.Queue.synchronous[IO, Option[A]]).flatMap { queue =>
      val producer = toIO { implicit awaits =>
        handle(new TailHandler[Emits[A], Unit, Unit] {
          def value(u: Unit): Unit = ()
          def apply(op: Emits[A], k: Answer[Emits[A]]): Unit = op match {
            case emit: Emits.Emit[A] => k(emit, await(queue.offer(Some(emit.value))))
          }
        })((emits: Effect[Emits[A]]) => body(emits, awaits))
      }
      fs2.Stream.fromQueueNoneTerminated(queue).concurrently(fs2.Stream.eval(producer.flatMap(_ => queue.offer(None))))
    }

  /** A cats monad as the front end's `Monad`: a strict one reflects. */
  def monad[F[_]](implicit m: cats.Monad[F]): Monad[F] = new Monad[F] {
    def pure[A](a: A): F[A] = m.pure(a)
    def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B] = m.flatMap(fa)(f)
  }

  /** `Either`, by cats: its bind calls the rest at most once, so it reflects on any implementation. */
  implicit def eitherMonad[E]: Monad[({ type L[x] = Either[E, x] })#L] = monad[({ type L[x] = Either[E, x] })#L]
  implicit def eitherAffine[E]: Affine[({ type L[x] = Either[E, x] })#L] = new Affine[({ type L[x] = Either[E, x] })#L] {}
}
