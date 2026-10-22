package okay.direct.scala3.ziointerop

import zio.{Runtime, Unsafe, ZEnvironment, ZIO}
import okay.direct.{Effect, Effects}
import okay.direct.scala3.{Emits, Fail, Reader, TailHandler, handle}

/**
 * ZIO AND THE DIRECT STYLE, each of ZIO's channels one of the effects: a `ZIO[R, E, A]` in a body has its environment
 * asked of `Reader[ZEnvironment[R]]`, its typed error raised by `Fail[E]`, its run AWAITED on ZIO's runtime (the body
 * waiting, cheap on a virtual thread). A body is a `ZIO[R, E, A]` by `toZIO`: its reader the ZIO's environment, its
 * raise the ZIO's failure, a throw a defect; run on a blocking thread, interruptibly.
 */
enum Zios:
  case Run[A](zio: ZIO[Any, Nothing, A]) extends Zios, Effect.Op[Zios, A]

/** A ZIO in the body: its environment asked, its typed error raised, its run awaited. */
def fromZIO[R, E, A](z: ZIO[R, E, A])(using Effect[Reader[ZEnvironment[R]]], Effect[Fail[E]], Effect[Zios]): A =
  val environment = Reader.ask[ZEnvironment[R]]
  summon[Effect[Zios]].perform(Zios.Run(z.provideEnvironment(environment).either)) match
    case Right(a) => a
    case Left(e) => Fail.raise(e)

/** A ZIO of no environment, its failure thrown. */
def await[A](z: ZIO[Any, Throwable, A])(using zios: Effect[Zios]): A =
  zios.perform(Zios.Run(z.either)) match
    case Right(a) => a
    case Left(t) => throw t

private def running[A](runtime: Runtime[Any])(body: Effect[Zios] ?=> A)(using Effects): A =
  handle(new TailHandler[Zios, A, A]:
    def value(a: A) = a
    def apply[X](op: Zios & Effect.Op[Zios, X]): X = op match
      case Zios.Run(z) => Unsafe.unsafe(implicit unsafe => runtime.unsafe.run(z).getOrThrowFiberFailure())
  )(body)

/** The body run here, its ZIOs on `runtime`. */
def runAwaiting[A](body: Effect[Zios] ?=> A)(using fx: Effects, runtime: Runtime[Any]): A = running(runtime)(body)

/** The body as a ZIO: its environment the ZIO's, its Fail the ZIO's error, a throw a defect. */
def toZIO[R, E, A](body: (Effect[Reader[ZEnvironment[R]]], Effect[Fail[E]], Effect[Zios]) ?=> A)(using fx: Effects): ZIO[R, E, A] =
  ZIO.environmentWithZIO[R]: environment =>
    ZIO.runtime[Any].flatMap: runtime =>
      ZIO.attemptBlockingInterrupt(Reader.run(environment)(Fail.attempt[E, A](running(runtime)(body))))
        .orDie
        .flatMap(ZIO.fromEither(_))

/** The body as a task: run on a blocking thread, interruptibly, its ZIOs on the task's runtime. */
def toTask[A](body: Effect[Zios] ?=> A)(using Effects): zio.Task[A] =
  ZIO.runtime[Any].flatMap(runtime => ZIO.attemptBlockingInterrupt(running(runtime)(body)))

// ---- PARALLEL: each body a task on its own thread, awaited together. A parallel body has the capabilities it is
// given here (Zios), not those of the body it is in: they are confined to that body's thread.

/** Both bodies at once: their values. */
def both[A, B](left: Effect[Zios] ?=> A, right: Effect[Zios] ?=> B)(using Effect[Zios], Effects): (A, B) =
  await(toTask(left).zipPar(toTask(right)))

/** The first body to end: its value; the other interrupted. */
def race[A, B](left: Effect[Zios] ?=> A, right: Effect[Zios] ?=> B)(using Effect[Zios], Effects): Either[A, B] =
  await(toTask(left).raceEither(toTask(right)))

// ---- RESOURCES: a scope opened and closed in the body, so the body keeps all its capabilities.

/** What `acquire` acquires in a scope, the body run with it, the scope closed after however the body ends. */
def scoped[R, A](acquire: ZIO[zio.Scope, Throwable, R])(body: R => A)(using Effect[Zios]): A =
  val scope = await(zio.Scope.make)
  try body(await(scope.extend[Any](acquire)))
  finally await(scope.close(zio.Exit.unit))

/** The layer built for the body, its services the body's `Reader[ZEnvironment[R]]`, released after. */
def withLayer[R, A](layer: zio.ZLayer[Any, Throwable, R])(body: Effect[Reader[ZEnvironment[R]]] ?=> A)(using Effect[Zios], Effects): A =
  scoped(layer.build)(environment => Reader.run(environment)(body))

// ---- STREAMS

private enum Pulled[+A]:
  case Item(a: A)
  case End
  case Failed(t: Throwable)

/** Each element of the stream to `f`, in THIS body, with all its capabilities: the stream run on the runtime, its
 * elements handed over one by one; interrupted if the body leaves early. */
def foreach[A](stream: zio.stream.ZStream[Any, Throwable, A])(f: A => Unit)(using Effect[Zios]): Unit =
  val handoff = java.util.concurrent.SynchronousQueue[Pulled[A]]()
  def put(p: Pulled[A]): ZIO[Any, Throwable, Unit] = ZIO.attemptBlockingInterrupt(handoff.put(p))
  val fiber = await(stream.runForeach(a => put(Pulled.Item(a))).foldZIO(e => put(Pulled.Failed(e)), _ => put(Pulled.End)).forkDaemon)
  try
    var going = true
    while going do
      handoff.take() match
        case Pulled.Item(a) => f(a)
        case Pulled.End => going = false
        case Pulled.Failed(t) => throw t
  finally await(fiber.interrupt)

/** The body as a stream of what it emits: run when the stream is pulled, each emit waiting for room; its failure
 * the stream's. */
def toStream[A](body: (Effect[Emits[A]], Effect[Zios]) ?=> Unit)(using Effects): zio.stream.ZStream[Any, Throwable, A] =
  zio.stream.ZStream.unwrapScoped:
    for
      queue <- zio.Queue.bounded[zio.stream.Take[Throwable, A]](1)
      producer = toTask:
        handle(new TailHandler[Emits[A], Unit, Unit]:
          def value(u: Unit) = ()
          def apply[X](op: Emits[A] & Effect.Op[Emits[A], X]): X = op match
            case Emits.Emit(a) => await(queue.offer(zio.stream.Take.single(a))); ()
        )(body)
      _ <- producer.foldCauseZIO(cause => queue.offer(zio.stream.Take.failCause(cause)), _ => queue.offer(zio.stream.Take.end)).forkScoped
    yield zio.stream.ZStream.fromQueue(queue).flattenTake
