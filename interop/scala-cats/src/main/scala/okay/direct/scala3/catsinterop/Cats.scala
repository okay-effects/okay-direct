package okay.direct.scala3.catsinterop

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.IORuntime
import okay.direct.{Effect, Effects}
import okay.direct.scala3.{Affine, Emits, Monad, TailHandler, handle}

/**
 * CATS-EFFECT AND THE DIRECT STYLE. An `IO` in a body is AWAITED: run on cats-effect's runtime, the body waiting for
 * its value (cheap on a virtual thread), its failure thrown at the await. A body is an `IO` by `toIO`: run when the
 * IO runs, on a blocking thread, interruptibly. A strict cats monad (`Either`, `Option`, `List`) is reflected by the
 * front end's `reify`/`reflect` through `monad`; a lazy one (`IO`, `Eval`, `State`) binds the rest after the reflect
 * has returned, which a handler's continuation does not allow: await it instead.
 */
enum Awaits:
  case Await[A](io: IO[A]) extends Awaits, Effect.Op[Awaits, A]

/** The IO run, the body waiting for its value; its failure thrown here. */
def await[A](io: IO[A])(using awaits: Effect[Awaits]): A = awaits.perform(Awaits.Await(io))

private def awaiting[A](run: [X] => IO[X] => X)(body: Effect[Awaits] ?=> A)(using Effects): A =
  handle(new TailHandler[Awaits, A, A]:
    def value(a: A) = a
    def apply[X](op: Awaits & Effect.Op[Awaits, X]): X = op match
      case Awaits.Await(io) => run(io)
  )(body)

/** The body run here, its IOs on `runtime`. */
def runAwaiting[A](body: Effect[Awaits] ?=> A)(using fx: Effects, runtime: IORuntime): A =
  awaiting([X] => (io: IO[X]) => io.unsafeRunSync()(using runtime))(body)

/** The body as an IO: run when the IO runs, on a blocking thread, interruptibly; its IOs on the IO's own runtime. */
def toIO[A](body: Effect[Awaits] ?=> A)(using fx: Effects): IO[A] =
  Dispatcher.sequential[IO].use: dispatcher =>
    IO.interruptible(awaiting([X] => (io: IO[X]) => dispatcher.unsafeRunSync(io))(body))

/** A cats monad as the front end's `Monad`: a strict one reflects. */
def monad[F[_]](using m: cats.Monad[F]): Monad[F] = new Monad[F]:
  def pure[A](a: A): F[A] = m.pure(a)
  def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B] = m.flatMap(fa)(f)

/** `Either`, by cats: its bind calls the rest at most once, so it reflects on any implementation. */
given eitherMonad[E]: Monad[[X] =>> Either[E, X]] = monad[[X] =>> Either[E, X]]
given eitherAffine[E]: Affine[[X] =>> Either[E, X]] = new Affine[[X] =>> Either[E, X]] {}

// ---- PARALLEL: each body an IO on its own thread, awaited together. A parallel body has the capabilities it is
// given here (Awaits), not those of the body it is in: they are confined to that body's thread.

/** Both bodies at once: their values. */
def both[A, B](left: Effect[Awaits] ?=> A, right: Effect[Awaits] ?=> B)(using Effect[Awaits], Effects): (A, B) =
  await(IO.both(toIO(left), toIO(right)))

/** The first body to end: its value; the other cancelled. */
def race[A, B](left: Effect[Awaits] ?=> A, right: Effect[Awaits] ?=> B)(using Effect[Awaits], Effects): Either[A, B] =
  await(IO.race(toIO(left), toIO(right)))

// ---- RESOURCES: acquired and released in the body, so the body keeps all its capabilities.

/** The resource acquired, the body run with it, released after however the body ends: a value, a throw, its rest
 * dropped by a handler (which unwinds it, `finally` and all). */
def use[R, A](resource: cats.effect.Resource[IO, R])(body: R => A)(using Effect[Awaits]): A =
  val (r, release) = await(resource.allocated)
  try body(r)
  finally await(release)

// ---- STREAMS

private enum Pulled[+A]:
  case Item(a: A)
  case End
  case Failed(t: Throwable)

/** Each element of the stream to `f`, in THIS body, with all its capabilities: the stream run on the runtime, its
 * elements handed over one by one, the stream waiting for each to be taken; cancelled if the body leaves early. */
def foreach[A](stream: fs2.Stream[IO, A])(f: A => Unit)(using Effect[Awaits]): Unit =
  val handoff = java.util.concurrent.SynchronousQueue[Pulled[A]]()
  def put(p: Pulled[A]): IO[Unit] = IO.interruptible(handoff.put(p))
  val fiber = await(stream.evalMap(a => put(Pulled.Item(a))).compile.drain.redeemWith(e => put(Pulled.Failed(e)), _ => put(Pulled.End)).start)
  try
    var going = true
    while going do
      handoff.take() match
        case Pulled.Item(a) => f(a)
        case Pulled.End => going = false
        case Pulled.Failed(t) => throw t
  finally await(fiber.cancel)

/** The body as a stream of what it emits: run when the stream is pulled, each emit waiting for the stream to take
 * the value; its failure the stream's. */
def toStream[A](body: (Effect[Emits[A]], Effect[Awaits]) ?=> Unit)(using Effects): fs2.Stream[IO, A] =
  fs2.Stream.eval(cats.effect.std.Queue.synchronous[IO, Option[A]]).flatMap: queue =>
    val producer = toIO:
      handle(new TailHandler[Emits[A], Unit, Unit]:
        def value(u: Unit) = ()
        def apply[X](op: Emits[A] & Effect.Op[Emits[A], X]): X = op match
          case Emits.Emit(a) => await(queue.offer(Some(a)))
      )(body)
    fs2.Stream.fromQueueNoneTerminated(queue).concurrently(fs2.Stream.eval(producer.flatMap(_ => queue.offer(None))))
