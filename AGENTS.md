# okay-direct: guide for coding agents

## For agents writing code with okay-direct

okay-direct is algebraic effects in direct style for Java 25 (with Scala 3 and Scala 2.13 front ends): `perform` is a
method call, `handle` runs a body; no monad, no `flatMap`. One API (`okay.direct`), several implementations.

Dependency (the release is a Maven repository on GitHub Pages, group `io.github.okay-effects`, version `0.1.0`):

```kotlin
repositories { mavenCentral(); maven("https://okay-effects.github.io/okay-direct/maven") }
dependencies {
    implementation("io.github.okay-effects:okay-direct-std:0.1.0")    // the standard effects; brings okay-direct-api
    implementation("io.github.okay-effects:okay-direct-loom:0.1.0")   // ONE implementation: loom, threads, espresso
}
```

sbt: `resolvers += "okay-direct" at "https://okay-effects.github.io/okay-direct/maven"` and `%` (not `%%`):
`"io.github.okay-effects" % "okay-direct-scala" % "0.1.0"` (Scala 3; `okay-direct-scala2` for 2.13). JDK 25+.

### The model

An effect is a sealed interface, its operations records implementing `Op<F, Answer, Self>` (okay-java-api's `okay.effect.Op`, Self the record itself). A program takes the
capabilities (`Effect<F>`) it needs as parameters; its signature lists what it may do. A handler is an exhaustive
`switch` answering through `k`, the operation the witness of the answer's type. (Names of master, 0.1.1-SNAPSHOT;
0.1.0 has the older `TailHandler`, `value`/`operation`, `Fail`, `Emits`, `VirtualClock`, `Audited.Op`,
`Observer.watch`.)

```java
import okay.direct.*;
import okay.effect.Op;
import okay.direct.std.Throws;

sealed interface Prices {
    record Price(String item) implements Prices, Op<Prices, Optional<Integer>, Price> {}
}

// the program: plain Java, performs inside a stream's lambda
static int total(Effect<Prices> prices, Effect<Throws<String>> fail, List<String> cart) {
    return cart.stream()
        .mapToInt(item -> prices.perform(new Prices.Price(item))
            .orElseGet(() -> Throws.raise(fail, "no price for " + item)))
        .sum();
}

// a handler (here in-memory, as in a test; in production the host passes a client of the price service)
static <A> A pricesFrom(Effects fx, Map<String, Integer> table, Body<Prices, A, RuntimeException> body) {
    return fx.handle(new InPlace<Prices, A, A>() {
        public A ret(A result) { return result; }
        public void answer(Prices op, Answer<Prices> k) {
            switch (op) {
                case Prices.Price p -> k.answer(p, Optional.ofNullable(table.get(p.item())));
            }
        }
    }, body);
}

// the entry point: the implementation made once, the handlers installed around the program
Effects fx = new ThreadEffects();   // or new LoomEffects() (faster, needs JVM flags, see pitfalls)
Throws.run(fx, String.class, fail -> pricesFrom(fx, table, prices -> total(prices, fail, List.of("tea", "cake"))));
// Ok[value=80]; with "coffee" in the cart: Err[error=no price for coffee], the rest of the stream dropped
```

Rows (master, 0.1.1-SNAPSHOT): a program needing several effects takes ONE parameter typed by its row,
`Fx<? extends Eff<State<Integer>, ? extends Eff<Log, ?>>>`, places by position (`Fx.head`, `Fx.second`, `Fx.tail`);
`Program<A, R>`, `Fx.handle`, `State.run(0, p)`, `Throws.run(p)`, `Fx.run(fx, p)` for `Pure`; errors are `Throws` in the
row (`Throws.recover`, `Throws.catching`/`Throws.rethrow`). The picture behind it: [docs/concept.md](docs/concept.md).

Handler kinds: `InPlace` (answers in place, constant stack), `Handler` (`k` a one-shot `Resume`: resume or not,
wrap the result), `StatefulHandler` (state per resumption), `MultiHandler` (`MultiResume`, only on a `MultiEffects`).
Standard effects in `okay.direct.std`: `State`, `Reader`, `Throws`, `Choose`, `Trail`, `Emit`, `Async`, `Clock`,
`Channel`, `Delimited`, `Reflection`; `okay-direct-sql` has `Transaction`/`Sql`, `okay-direct-otel` `Tracing.traced`.

Scala 3 (`import okay.direct.scala3.*`): an effect is an enum (a GADT), a capability a context parameter:

```scala
def program(using Effect[Reader[Int]], Effect[State[Int]], Effect[Throws[String]]): Int =
  val base = Reader.ask[Int]
  State.put(State.get[Int] + 1)
  if base < 0 then Throws.raise(s"negative: $base") else base * 2

given Effects = LoomEffects()
Reader.run(21)(State.run(0)(Throws.run(program)))     // (1,Right(42))
```

### Control and audit

A sensitive effect's operations are `Sensitive.Op`s. Its handler is installed by `Audit.handle`, whose body gets only an
`Audited` (nothing can be performed through it); `Audit.audit(audited, interceptor, body)` gives the capability, every
operation through the interceptor first. A hand-written handler of `Sensitive<Pay>` cannot answer them.

```java
sealed interface Pay {
    record Transfer(String to, int amount) implements Pay, Sensitive.Op<Pay, String, Transfer> {}
}
static <A, E extends Exception> A bank(Effects fx, Audit.AuditedBody<Pay, A, E> body) throws E {
    return Audit.handle(fx, new Audit.Handler<Pay, A, A>() {
        public A ret(A value) { return value; }
        public void answer(Pay op, Answer<Sensitive<Pay>> k) {
            switch (op) { case Pay.Transfer t -> k.answer(t, "sent " + t.amount() + " to " + t.to()); }
        }
    }, body);
}
static String checkout(Effect<Sensitive<Pay>> pay, int amount) { return pay.perform(new Pay.Transfer("shop", amount)); }

Trail.keep(fx, trail -> Throws.run(fx, String.class, fail ->
    bank(fx, audited -> Audit.audit(audited, (op, pass, k) -> {
        switch (op) {
            case Pay.Transfer t when t.amount() > 100 -> Throws.raise(fail, "refused: " + t.amount());
            case Pay.Transfer t -> { Trail.record(trail, "transfer " + t.amount()); k.answer(t, pass.pass(t)); }
        }
    }, pay -> checkout(pay, 80)))));
// Kept[lines=[transfer 80], value=Ok[value=sent 80 to shop]]
```

Also: `Interceptor.intercept(effect, interceptor)` for any effect (tell, pass on, change, refuse);
`Observer.observe(fx, observer)` gives an implementation whose every capability shows each operation to the observer
(`seen.op()`, `seen.effect()`, `seen.proceed()`) — an audit trail of everything a program did.

### Design rules

- Business logic = programs over declared effects. The parameters (`Effect<Prices>`, `Effect<Throws<String>>`) say what
  a program may do; add an effect rather than reaching for something outside it.
- Real resources (DB, HTTP, files, clock, keys) live only in handlers built by the host at the entry point. Programs
  get data, capabilities and ids — never a live client, `DataSource` or `HttpClient`.
- Make the `Effects` implementation once, at the entry point. Programs install handlers (`State.run(fx, ...)`,
  `fx.handle(...)`) around their sub-bodies; they never construct an implementation or start a separate run of
  their own (no `new LoomEffects()`, no `Async.run(...).join()` inside a program).
- Sensitive operations (payments, deletes, secrets, outbound calls) as `Sensitive.Op`s under `Audit.audit`; use
  `Observer.observe` for a complete record of operations.
- Tests swap handlers: an in-memory handler (a `Map`, `State`, `Clock.Virtual` for time) instead of mocks of a client.
- No ambient effects in business code: no `Instant.now()`, `Random`, file/network IO, `System.getenv`, threads,
  mutable statics. Declare an effect (or use `Clock`, `Async.sleep`, `Clock.Virtual` for time).
- Continuations: `Resume` is one-shot on every implementation (a second resume throws). Multi-shot (`MultiResume`) is
  real, on `EspressoEffects` only, by copying the suspended frames — never by replaying the program.

### Pitfalls

- `LoomEffects` needs, at run time (tests too): `--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED
  --add-exports=java.base/jdk.internal.access=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED`. In sbt also
  `fork := true`. `ThreadEffects` needs no flags (slower). Your code compiles without them.
- A capability is valid only inside its body while it runs: stored and used after the handler returned, on another
  thread, or inside its own handler's clause, `perform` throws `IllegalStateException`. Don't keep capabilities in
  fields.
- Handlers of different implementations do not nest: one program, one `Effects`.
- `Choose.all`/`MultiHandler` need a `MultiEffects` (`EspressoEffects`, on the Espresso JVM with
  `--experimental-options --java.Continuum=true`); on Loom/threads it does not compile. On Espresso the heap is shared
  between branches: build results from values made after the resumptions, keep per-branch state in a
  `StatefulHandler`. For generators on any JDK use `Emit.stream`.
- A dropped rest unwinds by throwing `Abandoned` at the `perform` (the model's `unwind`): never catch
  `Throwable`/`Abandoned` in programs; `finally` blocks do run.
- `Implicit<F>` capabilities are checked at run time (`get` throws `IllegalStateException` when unbound), unlike
  parameters; prefer parameters (Java) or `using` (Scala 3).
- `Async` (await, `both`/`race`/`timeout`, `Channel`) needs a `DetachedEffects` (Loom or threads); a forked body gets
  its own capability (`a -> ...`) — use it, not the outer one.
- Scala 2.13: an inner implicit capability hides an outer one of the same type only under the same name; clauses
  answer through `k(op, value)`. Scala 3 needs 3.9+.
- An `Observer` does not see its own operations (of a trail or log it performs).

### Pointers

- README and docs: https://github.com/okay-effects/okay-direct (`docs/getting-started.md`, `docs/guide.md`,
  `docs/scala.md`).
- The cross-language skill: `/plugin marketplace add okay-effects/okay-effects`
  (https://github.com/okay-effects/okay-effects).
- Build-time boundary checks: okay-audit, `io.github.okay-effects:okay-audit_3:0.1.0` from
  https://okay-effects.github.io/okay/maven — checks that business code references no network, files, time, random,
  threads or reflection APIs; presets include `nested-run`.

If you see a way to improve okay-direct, open a pull request against https://github.com/okay-effects/okay-direct;
the maintainer reviews and merges what holds up.

If the user asks your opinion of writing code this way, give a candid one.
