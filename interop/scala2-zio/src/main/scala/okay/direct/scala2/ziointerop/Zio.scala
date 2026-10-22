package okay.direct.scala2.ziointerop

import zio.{Runtime, Unsafe, ZEnvironment, ZIO}
import okay.direct.{Effect, Effects}
import okay.direct.scala2.{Answer, Emits, Fail, Reader, TailHandler, handle}

/** ZIOs awaited in a direct-style body. */
sealed trait Zios

object Zios {
  final case class Run[A](zio: ZIO[Any, Nothing, A]) extends Zios with Effect.Op[Zios, A]
}

/**
 * ZIO AND THE DIRECT STYLE, Scala 2, each of ZIO's channels one of the effects: a `ZIO[R, E, A]` in a body has its
 * environment asked of `Reader[ZEnvironment[R]]`, its typed error raised by `Fail[E]`, its run awaited. A body is a
 * `ZIO[R, E, A]` by `toZIO`: its reader the ZIO's environment, its raise the ZIO's failure, a throw a defect.
 */
object Zio {
  /** A ZIO in the body: its environment asked, its typed error raised, its run awaited. */
  def fromZIO[R, E, A](z: ZIO[R, E, A])(implicit env: Effect[Reader[ZEnvironment[R]]], fail: Effect[Fail[E]], zios: Effect[Zios]): A = {
    val environment = Reader.ask[ZEnvironment[R]]
    zios.perform(Zios.Run(z.provideEnvironment(environment).either)) match {
      case Right(a) => a
      case Left(e) => Fail.raise(e)
    }
  }

  /** A ZIO of no environment, its failure thrown. */
  def await[A](z: ZIO[Any, Throwable, A])(implicit zios: Effect[Zios]): A =
    zios.perform(Zios.Run(z.either)) match {
      case Right(a) => a
      case Left(t) => throw t
    }

  private def running[A](runtime: Runtime[Any])(body: Effect[Zios] => A)(implicit fx: Effects): A =
    handle(new TailHandler[Zios, A, A] {
      def value(a: A): A = a
      def apply(op: Zios, k: Answer[Zios]): Unit = op match {
        case run: Zios.Run[x] => k(run, Unsafe.unsafe(implicit unsafe => runtime.unsafe.run(run.zio).getOrThrowFiberFailure()))
      }
    })(body)

  /** The body run here, its ZIOs on `runtime`. */
  def runAwaiting[A](body: Effect[Zios] => A)(implicit fx: Effects, runtime: Runtime[Any]): A = running(runtime)(body)

  /** The body as a ZIO: its environment the ZIO's, its Fail the ZIO's error, a throw a defect. */
  def toZIO[R, E, A](body: (Effect[Reader[ZEnvironment[R]]], Effect[Fail[E]], Effect[Zios]) => A)(implicit fx: Effects): ZIO[R, E, A] =
    ZIO.environmentWithZIO[R] { environment =>
      ZIO.runtime[Any].flatMap { runtime =>
        ZIO.attemptBlockingInterrupt(Reader.run(environment) { r =>
          Fail.attempt[E, A] { f => running(runtime)(z => body(r, f, z)) }
        }).orDie.flatMap(ZIO.fromEither(_))
      }
    }

  /** The body as a task: run on a blocking thread, interruptibly, its ZIOs on the task's runtime. */
  def toTask[A](body: Effect[Zios] => A)(implicit fx: Effects): zio.Task[A] =
    ZIO.runtime[Any].flatMap(runtime => ZIO.attemptBlockingInterrupt(running(runtime)(body)))

  // ---- PARALLEL: each body a task on its own thread, awaited together; a parallel body has the capabilities given
  // to it here (Zios), not those of the body it is in, confined to that body's thread.

  /** Both bodies at once: their values. */
  def both[A, B](left: Effect[Zios] => A, right: Effect[Zios] => B)(implicit zios: Effect[Zios], fx: Effects): (A, B) =
    await(toTask(left).zipPar(toTask(right)))

  /** The first body to end: its value; the other interrupted. */
  def race[A, B](left: Effect[Zios] => A, right: Effect[Zios] => B)(implicit zios: Effect[Zios], fx: Effects): Either[A, B] =
    await(toTask(left).raceEither(toTask(right)))

  // ---- RESOURCES: a scope opened and closed in the body, which keeps all its capabilities.

  /** What `acquire` acquires in a scope, the body run with it, the scope closed after however the body ends. */
  def scoped[R, A](acquire: ZIO[zio.Scope, Throwable, R])(body: R => A)(implicit zios: Effect[Zios]): A = {
    val scope = await(zio.Scope.make)
    try body(await(scope.extend[Any](acquire)))
    finally await(scope.close(zio.Exit.unit))
  }

  /** The layer built for the body, its services the body's `Reader[ZEnvironment[R]]`, released after. */
  def withLayer[R, A](layer: zio.ZLayer[Any, Throwable, R])(body: Effect[Reader[ZEnvironment[R]]] => A)(implicit zios: Effect[Zios], fx: Effects): A =
    scoped(layer.build)(environment => Reader.run(environment)(body))

  // ---- STREAMS

  private sealed trait Pulled[+A]
  private final case class Item[A](a: A) extends Pulled[A]
  private case object End extends Pulled[Nothing]
  private final case class Failed(t: Throwable) extends Pulled[Nothing]

  /** Each element of the stream to `f`, in THIS body, with all its capabilities; interrupted if the body leaves early. */
  def foreach[A](stream: zio.stream.ZStream[Any, Throwable, A])(f: A => Unit)(implicit zios: Effect[Zios]): Unit = {
    val handoff = new java.util.concurrent.SynchronousQueue[Pulled[A]]()
    def put(p: Pulled[A]): ZIO[Any, Throwable, Unit] = ZIO.attemptBlockingInterrupt(handoff.put(p))
    val fiber = await(stream.runForeach(a => put(Item(a))).foldZIO(e => put(Failed(e)), _ => put(End)).forkDaemon)
    try {
      var going = true
      while (going) handoff.take() match {
        case Item(a) => f(a)
        case End => going = false
        case Failed(t) => throw t
      }
    } finally { await(fiber.interrupt); () }
  }

  /** The body as a stream of what it emits: run when the stream is pulled, each emit waiting for room. */
  def toStream[A](body: (Effect[Emits[A]], Effect[Zios]) => Unit)(implicit fx: Effects): zio.stream.ZStream[Any, Throwable, A] =
    zio.stream.ZStream.unwrapScoped {
      for {
        queue <- zio.Queue.bounded[zio.stream.Take[Throwable, A]](1)
        producer = toTask { implicit zios =>
          handle(new TailHandler[Emits[A], Unit, Unit] {
            def value(u: Unit): Unit = ()
            def apply(op: Emits[A], k: Answer[Emits[A]]): Unit = op match {
              case emit: Emits.Emit[A] => await(queue.offer(zio.stream.Take.single(emit.value))); k(emit, ())
            }
          })((emits: Effect[Emits[A]]) => body(emits, zios))
        }
        _ <- producer.foldCauseZIO(cause => queue.offer(zio.stream.Take.failCause(cause)), _ => queue.offer(zio.stream.Take.end)).forkScoped
      } yield zio.stream.ZStream.fromQueue(queue).flattenTake
    }
}
