# Scala: the front ends and the interop

Two front ends over the Java API, each in its language's own way, running on every implementation; they and their
interop live in `interop/`:

| | Scala 3, `interop/scala`, `okay.direct.scala3` | Scala 2.13, `interop/scala2`, `okay.direct.scala2` |
|---|---|---|
| an effect | an enum: a GADT | a sealed trait with case classes |
| a capability | a context parameter, `using Effect[E]` | an implicit, `implicit e: Effect[E]` |
| a body | a context function, `Effect[E] ?=> A` | `implicit e => ...` |
| a clause | returns the answer: GADT inference knows `X` | answers through `k(op, x)`: the operation the witness |
| a missing handler | does not compile | does not compile |

1. [Scala 3](#scala-3)
2. [Scala 2.13](#scala-213)
3. [Control and audit](#control-and-audit)
4. [Delimited continuations and monadic reflection](#delimited-continuations-and-monadic-reflection)
5. [Structured concurrency, time, channels](#structured-concurrency-time-channels)
6. [SQL and tracing](#sql-and-tracing)
7. [Cats-effect](#cats-effect)
8. [ZIO](#zio)
9. [What the interop does not do](#what-the-interop-does-not-do)

## Scala 3

```scala
import okay.direct.{Effect, Effects}
import okay.direct.scala3.*

enum State[S]:
  case Get[S]() extends State[S], Effect.Op[State[S], S]
  case Put[S](value: S) extends State[S], Effect.Op[State[S], Unit]

def program(heads: Boolean)(using Effect[Reader[Int]], Effect[State[Int]], Effect[Fail[String]]): Int =
  val base = Reader.ask[Int]
  State.put(State.get[Int] + 1)
  val result = if heads then base else -base
  if result < 0 then Fail.raise(s"negative: $result") else result

given Effects = LoomEffects()
Reader.run(42)(State.run(0)(Fail.attempt(program(true))))     // (1, Right(42))
```

A clause gets the operation as `E & Effect.Op[E, X]` and returns its answer; the match is exhaustive, and in each case
`X` is known, so there is no witness and no cast:

```scala
def apply[X](s: S, op: State[S] & Effect.Op[State[S], X]): (X, S) = op match
  case Get() => (s, s)
  case Put(next) => ((), next)
```

The handler traits mirror the Java ones: `Handler` (one-shot `k: X => R`), `MultiHandler` (multi-shot, on a
`MultiEffects`), `TailHandler` (`apply[X](op): X`), `StatefulHandler` (`apply[X](s, op): (X, S)`); `handle(h)(body)`,
`handle(h, initial)(body)`. The standard effects: `State`, `Reader`, `Fail`, `Choose`, `Trail`, `Emits` (a generator:
`Emits.emit(a)`, `Emits.collect(body)`).

Two capabilities of one type — two `State[Int]` — are told apart by passing one explicitly, `(using outer)`. Scala 3
prefers the more deeply nested given, so a body's own capability hides an outer one of its type.

## Scala 2.13

```scala
import okay.direct.{Effect, Effects}
import okay.direct.scala2._

sealed trait State[S]
object State {
  final case class Get[S]() extends State[S] with Effect.Op[State[S], S]
  final case class Put[S](value: S) extends State[S] with Effect.Op[State[S], Unit]
}

def program(heads: Boolean)(implicit r: Effect[Reader[Int]], s: Effect[State[Int]], f: Effect[Fail[String]]): Int = ...

Reader.run(42) { implicit r => State.run(0) { implicit s => Fail.attempt[String, Int] { implicit f => program(true) } } }
```

A clause answers through `k`, the operation the witness: `case get: Get[S] => k(get, state)`. Scala 2.13 does infer
the answer type from a typed pattern, but its match analysis is wrong on it — a reachable case called unreachable, a
missing one not reported — so the witness stays, as in Java. Scala 2 does not prefer the more deeply nested implicit:
an inner capability hides an outer one of its type only under the same name.

## Control and audit

The Java API's interceptors, sensitive effects and observer ([Guide](guide.md#control-and-audit)), each front end's way.
Scala 3 (top level of `okay.direct.scala3`) — the interceptor's clause returns the answer:

```scala
enum Pay:
  case Transfer(to: String, amount: Int) extends Pay, Audited.Op[Pay, String]
  case Balance() extends Pay, Audited.Op[Pay, Int]

def guarded(limit: Int)(using Effect[Trail], Effect[Fail[String]]) = new Interceptor[Pay, Audited[Pay]]:
  def apply[X](op: Pay & Effect.Op[Audited[Pay], X], pass: Pass[Audited[Pay]]): X = op match
    case Pay.Transfer(to, amount) if amount > limit => Trail.record(s"refused: $amount"); Fail.raise("refused")
    case Pay.Transfer(to, amount) => pass(Pay.Transfer(to.trim.toUpperCase, amount))
    case Pay.Balance() => pass(Pay.Balance())

Trail.keep(Fail.attempt(bank(1000)(audit(guarded(1000))(payments))))
observe(telling)(Reader.run(5)(State.run(1)(program)))     // every operation, told
```

`intercept(i)(body)` intercepts the capability in scope; `handleSensitive(h)(body)` installs a sensitive effect's
handler, its vault in scope; `audit(i)(body)` gives the body the audited capability; `observe(o)(body)` runs the body on
the watched implementation, a context parameter. Scala 2 has the same in `Control._`, the clauses answering through `k`.

## Delimited continuations and monadic reflection

The effect `Reset[R]` with its one operation `Shift0`; the prompt is in scope:

```scala
reset[Int](1 + shift0[Int, Int](k => k(10) * 2))                       // 22
resetMulti[Int](1 + shift0[Int, Int](k => k(10) + k(100)))             // 112: multi-shot, on a MultiEffects
```

Both Scalas have higher kinds, so reflection takes a real type class, `Monad[M[_]]`:

```scala
reify[Option, Int](reflect(Option(1)) + reflect(Option(2)))            // Some(3)
reifyMulti[List, Int](reflect(List(1, 2)) + reflect(List(10, 20)))     // List(11, 21, 12, 22)
```

A monad whose bind calls the rest at most once is also `Affine` (`Option` is; `List` is not): `reify` asks for
`Affine[M]`, `reifyMulti` for a `MultiEffects`. So `reify[List, Int]` does not compile — the number of shots is a type
here too. Scala 2: `Delimited._`, the prompt and the reflection capability passed as implicit lambda parameters.

`Either` and a writer, `Written[W, A]` (a value and its log), are affine monads given too, with their own reifies:

```scala
either[String, Int](reflect(parse("40")) + reflect(parse("2")))         // Right(42); "4o": Left(not a number: 4o)
written[String, Int] { tell("start"); (1 to 3).map(i => reflect(step(i))).sum }
                                                    // Written(60, List(start, step 1, step 2, step 3))
```

Scala 2 has no type lambdas without a plugin, so its `Delimited.either` and `Delimited.written` give the capability
as a function, `r(m)` reflecting: `Delimited.either[String, Int](r => r(parse("40")) + r(parse("2")))`.

Scala 3 builds `List(flip, flip)`'s varargs array before the flips and fills it after: under a multi-shot handler every
branch fills that one array. Bind multi-shot answers to locals first: `val a = flip; val b = flip; List(a, b)`.

## Structured concurrency, time, channels

Both front ends wrap the Java `std.Async` and `std.Channel`: the same tasks, cancellation and clocks, on a
`DetachedEffects` given (Loom's or threads'). The capability `Effect[Async]` is a context parameter; a forked body is
a context function, its own capability the innermost given, so its awaits use it with nothing written:

```scala
given DetachedEffects = LoomEffects()
given clock: VirtualClock = VirtualClock()         // the run's time; the system's when none is given

val result = Async.run:
  val (a, b) = Async.both({ Async.sleep(10.seconds); "a" }, { Async.sleep(20.seconds); "b" })
  val first = Async.race({ Async.sleep(5.seconds); "slow" }, { Async.sleep(1.second); "fast" })
  val inTime = Async.timeout(2.seconds) { Async.sleep(1.second); 7 }      // Some(7)
  (a, b, first, inTime)
clock.run()                                        // 20 seconds of virtual time, at once
```

`fork`/`join`, `all`, `sleep`, `timer`, `now` too; durations are Scala's. A [`Channel`](guide.md#channels-and-select)
sends and receives with the capability in scope, its `iterator` ends when it is closed, and `select` takes cases:

```scala
val numbers = Channel[Int]()
Async.run:
  Async.both({ for i <- 1 to 100 do numbers.send(i); numbers.close() }, numbers.iterator.sum)._2   // 5050

Channel.select(orders.received(o => s"order $o"), cancels.received(id => s"cancel $id"),
  Channel.timeout(5.seconds)("idle"))
```

Scala 2 is the same with implicits; a forked body takes its capability by THE SAME NAME, so that it hides the outer
one (two implicits of one type by different names are ambiguous in Scala 2):
`Async.both(implicit async => ..., implicit async => ...)`.

## SQL and tracing

With `okay-direct-sql` on the class path, `Transaction.run(db) { ... }` handles `Sql`, the capability in scope:

```scala
def transfer(amount: Int)(using Effect[Sql], Effect[Fail[String]]): Unit =
  Sql.update("update account set balance = balance - ? where id = ?", amount, "ann")
  if Sql.one("select balance from account where id = ?", "ann")(_.getInt(1)).get < 0 then Fail.raise("too poor")
  Sql.update("update account set balance = balance + ? where id = ?", amount, "bob")

Fail.attempt[String, Unit](Transaction.run(db)(transfer(130)))   // Left(too poor), the debit rolled back
transfer(30)(using Sql.logged(println))                          // each statement told; Sql.readOnly refuses changes
```

Tracing needs nothing of its own: `given DetachedEffects = Tracing.traced(LoomEffects(), tracer)` makes every
operation of every body a span, `Async.await`s included.

## Cats-effect

Modules `scala-cats` (`okay.direct.scala3.catsinterop`) and `scala2-cats` (`okay.direct.scala2.catsinterop.Cats`); cats-
effect 3.7, fs2 3.14. In direct style an `IO` in a body is AWAITED — an operation of the effect `Awaits` whose handler runs
it on cats-effect's runtime while the body waits, cheap on a virtual thread; its failure is thrown at the await:

| | |
|---|---|
| `await(io)` | the IO run, its value; in a body with `Effect[Awaits]` |
| `runAwaiting(body)` | the body run here, its IOs on the given `IORuntime` |
| `toIO(body)` | the body as an `IO`: run when the IO runs, on a blocking thread, interruptibly |
| `both(left, right)`, `race(left, right)` | two bodies at once, each an IO on its own thread: `IO.both`, `IO.race` |
| `use(resource)(body)` | the `Resource` acquired, the body run with it, released however the body ends |
| `foreach(stream)(f)` | each element of an fs2 `Stream` to `f`, in this body, with all its capabilities |
| `toStream(body)` | the body as a `Stream` of what it emits (`Emits.emit`), each emit waiting for the stream to take it |
| `monad[F]` | a cats `Monad` as the front end's, for reflection of a strict one (`Either` given, `Affine`) |

```scala
val io: IO[(Int, Int)] = toIO(State.run(1)(await(IO.pure(41)) + State.get[Int]))
use(Resource.make(open)(close))(file => State.put(read(file)))
foreach(fs2.Stream(1, 2, 3).covary[IO])(a => State.put(State.get[Int] + a))
toStream[Int] { Emits.emit(1); Emits.emit(await(IO(2))) }.compile.toList      // List(1, 2)
```

A lazy monad (`IO`, `Eval`, `State`) cannot be reflected: its bind calls the rest after the reflect has returned, when
the handler's continuation has ended. Await it instead.

## ZIO

Modules `scala-zio` (`okay.direct.scala3.ziointerop`) and `scala2-zio` (`okay.direct.scala2.ziointerop.Zio`); ZIO 2.1.
Each of ZIO's channels is one of the effects, as in okay-simple: a `ZIO[R, E, A]` in a body has its environment asked of
`Reader[ZEnvironment[R]]`, its typed error raised by `Fail[E]`, its run awaited (the effect `Zios`):

| | |
|---|---|
| `fromZIO(z)` | a `ZIO[R, E, A]` in a body: the environment asked, the error raised, the run awaited |
| `await(task)` | a `ZIO[Any, Throwable, A]`, its failure thrown |
| `runAwaiting(body)` | the body run here, its ZIOs on the given `Runtime` |
| `toZIO(body)` | the body as a `ZIO[R, E, A]`: its reader the environment, its raise the failure, a throw a defect |
| `toTask(body)` | the body as a `Task`: run on a blocking thread, interruptibly |
| `both`, `race` | two bodies at once: `zipPar`, `raceEither` |
| `scoped(acquire)(body)` | what `acquire: ZIO[Scope, Throwable, R]` acquires, the body run with it, the scope closed after |
| `withLayer(layer)(body)` | the `ZLayer` built for the body, its services the body's `Reader[ZEnvironment[R]]` |
| `foreach(stream)(f)` | each element of a `ZStream` to `f`, in this body |
| `toStream(body)` | the body as a `ZStream` of what it emits |

```scala
val program: ZIO[Int, String, Int] = toZIO[Int, String, Int](fromZIO(ZIO.serviceWith[Int](_ + 1)) + 1)
program.provideEnvironment(ZEnvironment(41))      // 43
toZIO[Any, String, Int](fromZIO[Any, String, Int](ZIO.fail("bad")))     // fails with "bad"
withLayer(ZLayer.succeed(41))(Reader.ask[ZEnvironment[Int]].get[Int] + 1)    // 42
```

## What the interop does not do

- **A parallel body has only the capabilities given to it**, `Awaits` or `Zios`: it runs on a thread of its own, and a
  capability is confined to its body's thread. One of the outer body's capabilities used in it is refused, at run time.
- **Resources and streams keep the body's capabilities** because they run in the body: `use` and `scoped` acquire and
  release there, by `try`/`finally` (so the release runs when a handler drops the rest, too); `foreach` hands each
  element over to the body's thread.
- **Cancellation goes one way**: an `IO` or a `ZIO` made by `toIO`/`toZIO` is interruptible, its thread interrupted;
  a body awaiting cannot be cancelled from inside it.
- **Multi-shot** handlers run only on Espresso, and the interop's tests run on Loom and threads.
