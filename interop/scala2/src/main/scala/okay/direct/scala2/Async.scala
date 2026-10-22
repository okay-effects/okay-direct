package okay.direct.scala2

import java.time.{Duration => JDuration, Instant}
import java.util.concurrent.{CompletableFuture, CompletionStage}
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._
import scala.jdk.DurationConverters._
import scala.jdk.OptionConverters._
import okay.direct.{Body, DetachedEffects, Effect}
import okay.direct.std.{Async => J, Channel => JChannel, VirtualClock => JVirtualClock}

/**
 * ASYNCHRONY AND STRUCTURED CONCURRENCY in direct style, the Scala 2 way: `Async.await(future)` gives the future's
 * value with no thread waiting, the capability an implicit `Effect[Async]`. A forked body is given its own: name it
 * as the outer one, `implicit async =>`, so that it hides it.
 * {{{
 * implicit val fx: DetachedEffects = new LoomEffects
 * Async.run { implicit async =>
 *   val (user, orders) = Async.both(implicit async => Async.await(users.find(id)),
 *                                   implicit async => Async.await(orders.of(id)))
 *   render(user, orders)
 * }
 * }}}
 * Time is the run's clock, an implicit: the system's by default, a [[VirtualClock]] in a test.
 */
object Async {
  type Clock = J.Clock
  type Task[X] = J.Task[X]

  /** The system's time. */
  val SystemClock: Clock = J.Clock.SYSTEM

  private def body[X](b: Effect[Async] => X): Body[Async, X, RuntimeException] =
    new Body[Async, X, RuntimeException] { def run(async: Effect[Async]): X = b(async) }

  /** The body run, its awaits resumed by their futures' callbacks: its value when it and its tasks end. */
  def run[A](b: Effect[Async] => A)(implicit fx: DetachedEffects, clock: Clock = SystemClock): CompletableFuture[A] =
    J.run(fx, clock, body(b))

  /** The stage's value, once it is there; the body suspended meanwhile, no thread held. */
  def await[X](stage: CompletionStage[X])(implicit async: Effect[Async]): X = J.await(async, stage)

  /** `b` started beside this one, with its own capability: its task. */
  def fork[X](b: Effect[Async] => X)(implicit async: Effect[Async]): Task[X] = J.fork(async, body(b))

  /** The task's value once it has ended; its failure thrown here. */
  def join[X](task: Task[X])(implicit async: Effect[Async]): X = J.join(async, task)

  def sleep(delay: FiniteDuration)(implicit async: Effect[Async]): Unit = J.sleep(async, delay.toJava)

  /** A future completed after `delay`, by the run's clock. */
  def timer(delay: FiniteDuration)(implicit async: Effect[Async]): CompletableFuture[Void] = J.timer(async, delay.toJava)

  def now(implicit async: Effect[Async]): Instant = J.now(async)

  /** Both at once; the first failure thrown, the other cancelled. */
  def both[A, B](a: Effect[Async] => A, b: Effect[Async] => B)(implicit async: Effect[Async]): (A, B) =
    J.both[A, B, (A, B)](async, body(a), body(b), (x: A, y: B) => (x, y))

  /** All at once, the values in order; the first failure thrown, the others cancelled. */
  def all[X](bodies: (Effect[Async] => X)*)(implicit async: Effect[Async]): List[X] =
    J.all[X](async, bodies.map(b => body(b)).asJava).asScala.toList

  /** The first to succeed, the others cancelled; all failed, the first failure, the others suppressed. */
  def race[X](bodies: (Effect[Async] => X)*)(implicit async: Effect[Async]): X =
    J.race[X](async, bodies.map(b => body(b)).asJava)

  /** The value if the body ends within `limit`, else None and the body cancelled. */
  def timeout[X](limit: FiniteDuration)(b: Effect[Async] => X)(implicit async: Effect[Async]): Option[X] =
    J.timeout(async, limit.toJava, body(b)).toScala
}

/** VIRTUAL TIME: a clock that moves only when told, its timers completing in order on the thread that moves it. */
final class VirtualClock(start: Instant = Instant.EPOCH) extends J.Clock {
  private val clock = new JVirtualClock(start)
  def now(): Instant = clock.now()
  def after(delay: JDuration): CompletableFuture[Void] = clock.after(delay)
  /** The time moved on by `by`: each timer due by then completed, in order. */
  def advance(by: FiniteDuration): Unit = clock.advance(by.toJava)
  /** The time moved on until no timer is left. */
  def run(): Unit = clock.run()
  def waiting: Int = clock.waiting()
  override def toString: String = clock.toString
}

/**
 * A CHANNEL between `Async` bodies, as Go's: `send` waits while it is full (an unbuffered one, `capacity` 0, until a
 * receiver takes the value), `receive` while it is empty; no thread waits. Closed, it takes no more values, its last
 * ones still received, then [[Channel.Closed]] is thrown — which ends `iterator`.
 */
final class Channel[T](capacity: Int = 0) {
  private[scala2] val underlying: JChannel[T] = new JChannel[T](capacity)

  def send(value: T)(implicit async: Effect[Async]): Unit = underlying.send(async, value)
  def receive(implicit async: Effect[Async]): T = underlying.receive(async)
  def close(): Unit = underlying.close()
  def isClosed: Boolean = underlying.isClosed

  /** The values received until the channel is closed and empty. */
  def iterator(implicit async: Effect[Async]): Iterator[T] = underlying.iterable(async).iterator.asScala

  /** A select's case: a value received from this channel, given to `onValue`. */
  def received[R](onValue: T => R): Channel.Case[R] = new Channel.Case[R] {
    private[scala2] def addTo(select: JChannel.Select[R]): Unit = {
      select.receive(underlying, (value: T) => onValue(value))
      ()
    }
  }
}

object Channel {
  type Closed = JChannel.Closed

  /** A case of a select. */
  trait Case[R] {
    private[scala2] def addTo(select: JChannel.Select[R]): Unit
  }

  /** A select's case: nothing received within `limit`, by the run's clock. */
  def timeout[R](limit: FiniteDuration)(onTimeout: => R): Case[R] = new Case[R] {
    private[scala2] def addTo(select: JChannel.Select[R]): Unit = {
      select.timeout(limit.toJava, () => onTimeout)
      ()
    }
  }

  /** Waits for the first case ready, and runs its function here, in the body. */
  def select[R](cases: Case[R]*)(implicit async: Effect[Async]): R = {
    val select = JChannel.select[R]()
    cases.foreach(_.addTo(select))
    select.await(async)
  }
}
