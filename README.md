# okay-direct

Algebraic effects in direct style for Java 25: `perform` is a method call, `handle` runs a body. No monad, no
`flatMap`, no code transformation. One API, several implementations.

**Start here: [getting started](docs/getting-started.md)** — the release 0.1.0 is a Maven repository on GitHub Pages,
`https://okay-effects.github.io/okay-direct/maven`, group `io.github.okay-effects`.

**Documentation: [docs/](docs/README.md)** — [getting started](docs/getting-started.md), the [guide](docs/guide.md) to the Java API, [Scala](docs/scala.md) and its
cats-effect and ZIO interop, the [implementations](docs/implementations.md), and the [design](docs/design.md).

## Modules

| Module | Artifact | What is in it |
|---|---|---|
| `api` | `okay-direct-api` | perform and handle, of no implementation |
| `std` | `okay-direct-std` | `State`, `Reader`, `Fail`, `Choose`, `Trail`, `Emits`, `Async` (structured concurrency, time), `VirtualClock`, `Channel`, `Delimited`, `Reflection` on the API |
| `tck` | `okay-direct-tck` | what every implementation does: JUnit tests to extend |
| `loom` | `okay-direct-loom` | on HotSpot's `jdk.internal.vm.Continuation` |
| `threads` | `okay-direct-threads` | on virtual threads, portable |
| `espresso` | `okay-direct-espresso` | on Espresso's continuations: MULTI-SHOT, a `MultiEffects` |
| `sql` | `okay-direct-sql` | SQL as an effect: statements are operations, `Transaction` their handler; read-only and logged capabilities |
| `otel` | `okay-direct-otel` | every operation a span of OpenTelemetry, with no change to the program |
| `interop/scala` | `okay-direct-scala` | the Scala 3 front end, `okay.direct.scala3` |
| `interop/scala2` | `okay-direct-scala2` | the Scala 2.13 front end, `okay.direct.scala2` |
| `interop/scala-cats`, `interop/scala2-cats` | `okay-direct-scala-cats`, `okay-direct-scala2-cats` | cats-effect and fs2: `await`, `toIO`, `both`/`race`, `use`, `foreach`/`toStream`, cats monads reflected |
| `interop/scala-zio`, `interop/scala2-zio` | `okay-direct-scala-zio`, `okay-direct-scala2-zio` | ZIO and ZStream: its channels the effects, `fromZIO`/`toZIO`, `both`/`race`, `scoped`/`withLayer`, `foreach`/`toStream` |

## The API, `okay.direct`

| Type | What it is |
|---|---|
| `Effect.Op<F, X>` | an operation of the effect `F`, answered by an `X` |
| `Effect<F>` | the capability to perform `F`'s operations, given to the body by `F`'s handler |
| `Body<F, A, E>` | the handled body: run with the capability, it may throw the checked exception `E` |
| `Handler<F, A, R, E>` | `value(a)`, and `operation(op, k)` with `k` the rest of the body, `Resume`: one-shot |
| `MultiHandler<F, A, R, E>` | the same with `k` a `MultiResume`: resumed any number of times |
| `TailHandler<F, A, R>` | answers every operation in place, `Answer<F>`: runs in constant stack |
| `StatefulHandler<F, S, A, R>` | the same with a state kept by the implementation: each resumption's own |
| `Effects` | an implementation: `handle(handler, body) throws E`, for one-shot handlers |
| `MultiEffects` | an implementation with multi-shot continuations: `MultiHandler`s too |
| `Abandoned` | thrown at `perform` when the handler drops the rest: the body unwinds, `finally` runs |
| `Later<F>`, `DetachedHandler`, `DetachedEffects` | a continuation that outlives its clause: resumed once, any time, from any thread; `start` gives a `CompletableFuture` |
| `Implicit<F>` | a key, by `ScopedValue`, under which a capability is found instead of passed |
| `Interceptor<C, F>` | control of one effect's operations at the `perform`: tell, pass on, change, refuse |
| `Audited<C>`, `Audit` | sensitive effects: performed only under an audit, answered only by their own handler |
| `Observer` | every operation of every effect, seen as data with its answer: tell, pass on, refuse |

An effect is a sealed interface, its operations records; a handler is an exhaustive `switch`, and the operation is
the witness of its answer's type, so `k.answer(get, state)` compiles only with an answer of the right type:

```java
public sealed interface State<S> {
    record Get<S>() implements State<S>, Op<State<S>, S> {}
    record Put<S>(S value) implements State<S>, Op<State<S>, Void> {}
}

public void operation(State<S> op, Answer<State<S>> k) {
    switch (op) {
        case Get<S> get -> k.answer(get, state);
        case Put<S> put -> { state = put.value(); k.answer(put, null); }
    }
}
```

A program takes the capabilities it needs and calls ordinary methods — in lambdas, in streams, across functions;
its handlers are written apart, and change what it means without changing it:

```java
// the effect: one operation, as data — Price(item) is answered by an Integer
sealed interface Prices {
    record Price(String item) implements Prices, Op<Prices, Optional<Integer>> {}   // an item may have no price
}

// the program: plain Java; it performs inside a stream's lambda, and knows nothing of where prices come from
static int total(Effect<Prices> prices, Effect<Fail<String>> fail, List<String> cart) {
    return cart.stream()
        .mapToInt(item -> prices.perform(new Prices.Price(item))
            .orElseGet(() -> Fail.raise(fail, "no price for " + item)))
        .sum();
}
```

```java
// an external price service, asynchronous — an HTTP client, a gRPC stub; empty for an unknown item
interface PriceService {
    CompletableFuture<Optional<Integer>> quote(String item);
}

// a handler: what Price means — asked of the service, its answer waited for
static <A> A pricesFrom(Effects fx, PriceService service, Body<Prices, A, RuntimeException> body) {
    return fx.handle(new TailHandler<Prices, A, A>() {
        public A value(A result) { return result; }
        public void operation(Prices op, Answer<Prices> k) {
            switch (op) {
                case Prices.Price p -> k.answer(p, service.quote(p.item()).join());
            }
        }
    }, body);
}

// another, on top of it: a sale — each price asked of the handler further out, 10% off, told to a trail
static Effect<Prices> sale(Effect<Prices> prices, Effect<Trail> trail) {
    return Interceptor.intercept(prices, (op, pass, k) -> {
        switch (op) {
            case Prices.Price p -> {
                Optional<Integer> full = pass.pass(p);
                Optional<Integer> discounted = full.map(price -> price * 9 / 10);
                full.ifPresent(price -> Trail.record(trail, p.item() + ": " + price + " -> " + discounted.get()));
                k.answer(p, discounted);
            }
        }
    });
}
```

```java
// a fake of the service: each answer 100 ms later, from another thread
var table = Map.of("tea", 30, "cake", 50, "jam", 20);
PriceService service = item -> CompletableFuture.supplyAsync(() -> Optional.ofNullable(table.get(item)),
    CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS));

Fail.attempt(fx, String.class, fail -> pricesFrom(fx, service, prices -> total(prices, fail, List.of("tea", "cake", "jam"))));
// Success[value=100]

Fail.attempt(fx, String.class, fail -> pricesFrom(fx, service, prices -> total(prices, fail, List.of("tea", "coffee", "cake"))));
// Failure[error=no price for coffee] — and cake is never priced

Trail.keep(fx, trail -> Fail.attempt(fx, String.class, fail ->
    pricesFrom(fx, service, prices -> total(sale(prices, trail), fail, List.of("tea", "cake", "jam")))));
// Kept[lines=[tea: 30 -> 27, cake: 50 -> 45, jam: 20 -> 18], value=Success[value=90]]
```

- **An effect inside a stream's lambda.** In monadic style `mapToInt` cannot wait for a monad: it would be rewritten
  with `traverse` and `flatMap`. Here the stack from `perform` up to its handler is suspended, whatever is between.
- **An early exit from the middle of a stream.** At `coffee`, `Fail`'s handler does not resume the rest: it unwinds,
  the stream stops, `cake` is never priced. No exception reaches the caller: it gets a value, `Failure`.
- **Asynchrony without async code.** The service answers on another thread, later; `total` does not know it: its
  handler waits for the future, and on a virtual thread a wait holds no OS thread. No `thenCompose`, no callback.
- **The meaning changes, the program does not.** `total` is the same; the prices come from the service, from a sale on
  top of it with a trail, from a database, from a test — whichever handler is installed.
- **The signature lists the effects.** `total(Effect<Prices>, Effect<Fail<String>>, ...)` says what it may do; without
  a handler of prices there is no capability to call it with: it does not compile.

Or with the capabilities implicit, found by key wherever the body calls:

```java
static final Implicit<State<Integer>> COUNTER = Implicit.newInstance();
static final Implicit<Reader<Integer>> ENV = Implicit.newInstance();

static int addEnv() {
    State.put(COUNTER.get(), State.get(COUNTER.get()) + Reader.ask(ENV.get()));
    return State.get(COUNTER.get());
}

Reader.run(fx, 5, ENV.in(() -> State.run(fx, 1, COUNTER.in(Program::addEnv))));
```

Two keys of one effect are two capabilities; an inner binding of a key hides the outer one. A handler's clauses
see the capabilities of outside its body. Where no capability is bound, `get` throws `IllegalStateException`: that
an implicit capability is there is checked when the program runs, not when it compiles.

## Delimited continuations and monadic reflection

`std.Delimited`: `reset(fx, p -> ...)` and `p.shift0(k -> ...)`, shift0/reset0 as a handler and its operation; the
shift0's body runs outside the reset, `k` resumes under it again. On `Effects` `k` is one-shot, on `MultiEffects`
multi-shot. `std.Reflection`: Filinski's `reify` (a reset, `unit` of the body's value) and `reflect` (a shift0 to
`bind`); Java has no higher kinds, so `unit` and `bind` are given as method references. `Optional`'s pair is there,
typed: `Reflection.optional` reifies, `Reflection.get` reflects, an empty one drops the rest:

```java
Optional<String> city = Reflection.optional(fx, p -> get(p,
    Stream.of("user", "address", "city").reduce(doc, (node, key) -> get(p, node.field(key)), (a, b) -> a).str()));
```

(`doc` typed data: a `Json` whose `field` and `str` give `Optional`s.)

`Async.await(async, future)` awaits a future in direct style with no thread waiting: on a `DetachedEffects` the
rest is resumed by the future's callback. `Emits.stream(fx, out -> ...)` is a generator, PULLED on a
`DetachedEffects` (no thread of its own): plain loops enumerate, `Emits.emit` gives values out, the result a lazy
`Stream` — one-shot, so on any JDK. `Reflection.stream(fx, p -> ...)` and `Reflection.each(p, stream)` reflect Java's
streams instead, multi-shot: the bind is
eager, the rest resumed for each element while the clause runs, as `Stream.flatMap` would resume it only when the
stream is consumed. `Reflection.none(p)` drops a way:

```java
Stream<List<Integer>> triples = Reflection.stream(fx, (Prompt<Stream<List<Integer>>> p) -> {
    int a = Reflection.each(p, IntStream.rangeClosed(1, 10).boxed());
    int b = Reflection.each(p, IntStream.rangeClosed(a, 10).boxed());
    int c = Reflection.each(p, IntStream.rangeClosed(b, 15).boxed());
    if (a * a + b * b != c * c) Reflection.none(p);
    return List.of(a, b, c);
});   // [3, 4, 5], [6, 8, 10]
```

The Scala front ends have both, with a real `Monad[M[_]]` type class: `reify[Option, Int](reflect(Option(1)) +
reflect(Option(2)))`. A monad whose bind calls the rest at most once is also `Affine` and reifies by `reify` on any
implementation; any other, a `List`, by `reifyMulti` on a `MultiEffects`: `reify[List, Int]` does not compile.

## Structured concurrency, time, channels

`Async` forks bodies and joins them: `both`, `all`, `race`, `timeout`, each cancelling what is not needed any more —
a cancelled task unwinds at its await, its `finally` blocks run, and a run ends only after its tasks. Time is the
handler's: `sleep` and timeouts ask the run's clock, and a `VirtualClock` makes a test of hours of retries run in no
time, in an order that is always the same. `Channel` is Go's channel between bodies, with `select`; no thread waits:

```java
var clock = new VirtualClock();
var winner = Async.run(fx, clock, async -> Async.race(async, List.of(
    a -> { Async.sleep(a, Duration.ofSeconds(5)); return "slow"; },
    b -> { Async.sleep(b, Duration.ofSeconds(1)); return "fast"; })));
clock.advance(Duration.ofSeconds(1));       // "fast"; the slow one cancelled, its finally run
```

## SQL and tracing

Module `sql`: a statement is an operation, `Transaction.run(fx, db, sql -> ...)` its handler — committed when the body
returns, rolled back when it fails or when a `Fail` further out drops its rest; `Sql.readOnly` and `Sql.logged` control
the capability. Module `otel`: `Tracing.traced(fx, tracer)` makes every operation a span of OpenTelemetry, awaits
included, with no change to the program ([guide](docs/guide.md#sql-a-transaction-is-a-handler)).

## Cats-effect and ZIO

In direct style an `IO` or a `ZIO` in a body is AWAITED: an operation whose handler runs it on the library's runtime
while the body waits, cheap on a virtual thread. A body becomes an `IO` or a `ZIO` the other way, run on a blocking
thread, interruptibly. A ZIO's channels are effects, as in okay-simple: its environment `Reader[ZEnvironment[R]]`, its
typed error `Fail[E]`, a throw a defect:

```scala
val program: ZIO[Int, String, Int] = toZIO[Int, String, Int](fromZIO(ZIO.serviceWith[Int](_ + 1)) + 1)
val io: IO[(Int, Int)] = toIO(State.run(1)(await(IO.pure(41)) + State.get[Int]))
```

A strict cats monad (`Either`) is reflected through `monad`; a lazy one (`IO`, `Eval`) binds the rest after the
reflect has returned, which a handler's continuation does not allow, so it is awaited instead.

## Control and audit

An interceptor stands between a capability and its handler: each operation comes to it first, at the `perform`.
`Interceptor.intercept(state, i)` is the capability `state` intercepted. A SENSITIVE effect's operations are
`Audited.Op`s: its handler is installed by `Audit.handle`, whose body gets a `Vault`, through which nothing can be
performed; `Audit.audit(vault, interceptor, body)` gives the body the capability, every operation through the
interceptor. So no sensitive operation is answered that did not pass an audit; a handler of `Audited<Pay>` written
by hand gets operations of no case and no answer type, and cannot answer them. `Observer.watch(fx, observer)` is an
implementation whose every capability shows each operation to the observer, its own operations not shown to it.

```java
sealed interface Pay {
    record Transfer(String to, int amount) implements Pay, Audited.Op<Pay, String> {}
    record Balance() implements Pay, Audited.Op<Pay, Integer> {}
}

Interceptor<Pay, Audited<Pay>> guarded = (op, pass, k) -> {
    switch (op) {
        case Pay.Transfer t when t.amount() > limit -> Fail.raise(fail, "refused: " + t.amount());
        case Pay.Transfer t -> k.answer(t, pass.pass(new Pay.Transfer(t.to().trim(), t.amount())));
        case Pay.Balance b -> k.answer(b, pass.pass(b));
    }
};

Pay.bank(fx, 1000, vault -> Audit.audit(vault, guarded, pay -> payments(pay)));
```

## The contract

- A continuation `Resume` is one-shot; a `MultiResume` is not. A multi-shot handler runs only on a `MultiEffects`:
  `Choose.all` does not compile against a one-shot implementation.
- A capability is used only by its body while it runs: on the body's thread or in a body nested in it. After its
  handler has returned, on another thread, or in its handler's own clause, `perform` throws `IllegalStateException`.
- A program runs on one implementation: handlers of different implementations do not nest.

## Scala 3

An effect is an enum, a GADT; a capability a context parameter; a body a context function. A clause gets the
operation as `E & Effect.Op[E, X]` and returns the answer itself: GADT inference knows `X` in each case, so there
is no witness, and a missing handler does not compile.

```scala
enum State[S]:
  case Get[S]() extends State[S], Effect.Op[State[S], S]
  case Put[S](value: S) extends State[S], Effect.Op[State[S], Unit]

def apply[X](op: State[S] & Effect.Op[State[S], X]): X = op match
  case Get() => state
  case Put(s) => state = s

def program(heads: Boolean)(using Effect[Reader[Int]], Effect[State[Int]], Effect[Fail[String]]): Int =
  val base = Reader.ask[Int]
  State.put(State.get[Int] + 1)
  if heads then base else Fail.raise("negative")

given Effects = LoomEffects()
Reader.run(42)(State.run(0)(Fail.attempt(program(true))))
```

Control and audit in Scala 3: an interceptor's clause returns the answer, `X` known in each case.

```scala
enum Pay:
  case Transfer(to: String, amount: Int) extends Pay, Audited.Op[Pay, String]
  case Balance() extends Pay, Audited.Op[Pay, Int]

def guarded(limit: Int)(using Effect[Trail], Effect[Fail[String]]) = new Interceptor[Pay, Audited[Pay]]:
  def apply[X](op: Pay & Effect.Op[Audited[Pay], X], pass: Pass[Audited[Pay]]): X = op match
    case Pay.Transfer(to, amount) if amount > limit => Fail.raise(s"refused: $amount")
    case Pay.Transfer(to, amount) => pass(Pay.Transfer(to.trim, amount))
    case Pay.Balance() => pass(Pay.Balance())

Trail.keep(Fail.attempt(bank(1000)(audit(guarded(1000))(payments))))
observe(telling)(Reader.run(5)(State.run(1)(program)))   // the observer's implementation, a context parameter
```

## Scala 2.13

An effect is a sealed trait with case classes; a capability an implicit; a body `implicit e => ...`. Scala 2 infers
the answer type from a typed pattern but its match analysis is wrong on it, so a clause answers through `k` with the
operation as the witness, as in Java: `case get: Get[S] => k(get, state)`.

```scala
Reader.run(42) { implicit r => State.run(0) { implicit s => Fail.attempt[String, Int] { implicit f => program(true) } } }
```

Control and audit in Scala 2 (`Control._`): `intercept`, `handleSensitive`, `audit`, `observe`, `Trail`; a clause
answers through `k`: `case t: Transfer => k(t, pass(t))`. An inner capability hides an outer one of the same type
only under the same name: Scala 2 does not prefer the more deeply nested implicit.

## Implementations

| | `LoomEffects` | `ThreadEffects` | `EspressoEffects` |
|---|---|---|---|
| on | `jdk.internal.vm.Continuation`, a scope per handler | a virtual thread per body | Espresso's continuations, a fiber per body under a driver |
| resume | one-shot | one-shot | MULTI-SHOT: a `MultiEffects` |
| JVM | HotSpot, JDK 25+ | any JDK 25+, no flags | Espresso (Java on Truffle) JDK 25, `--experimental-options --java.Continuum=true` |
| flags | `--add-exports` of `jdk.internal.vm`, `jdk.internal.access`; `--add-opens` of `java.lang` | none | `--add-exports` of `jdk.internal.access`; `--add-opens` of `java.lang` |
| scoped values | switched with the stack | a thread per body | switched with the fibers |

All three pass the TCK; Espresso the multi-shot one too, `MultiEffectsTck`: `Choose`, a state inside a choice each
branch's own and one outside threaded through them, a failure per branch, choices nested.

**Multi-shot on Espresso.** A resumption of a `MultiResume` runs a COPY of the rest: Espresso writes a suspended
continuation's frames to an `ObjectOutput`, and here the objects go by reference, not serialized, so the copy has
its own frames — its locals — and the same heap. The copy is the chain of fibers from the operation up to the
handler, with the handlers installed in them and their state. Since the heap is one, a clause collects its results in
values made after the resumptions, not in an object it changes between them (`MultiResume`'s javadoc). A body
too: an object made before a multi-shot operation and filled after it is one for all the branches. Scala 3's
`List(flip, flip)` is such a case, its varargs array made first; `val a = flip; val b = flip; List(a, b)` is not.

Handlers whose state must be each branch's own keep it in a `StatefulHandler`: `State` and `Trail` do, in Java and
in both Scala front ends. The front ends' tests run on Espresso too, `./gradlew :scala:espressoTest :scala2:espressoTest`.

The `espresso` module's tests run on Espresso itself: installed at `~/.local/espresso-java25-25.0.0-macos-aarch64`,
or `-PespressoHome=...`; without it they are skipped.

```sh
./gradlew build
```
