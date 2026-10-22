# Getting started

From nothing to a running program: the dependency, the JVM, a first program in Java and in Scala, your own effect, and
which implementation to run on.

1. [What you need](#what-you-need)
2. [The dependency](#the-dependency)
3. [Choosing an implementation](#choosing-an-implementation)
4. [A first program: Java](#a-first-program-java)
5. [A first program: Scala](#a-first-program-scala)
6. [Running on Espresso: multi-shot](#running-on-espresso-multi-shot)
7. [Where to go next](#where-to-go-next)

## What you need

- **JDK 25 or newer**: the libraries are Java 25 bytecode.
- **For Scala**: Scala 3.9 or newer for `okay-direct-scala` (it is built with 3.9.0), Scala 2.13.18 for
  `okay-direct-scala2`.
- **For multi-shot continuations**: Espresso, Java on Truffle, JDK 25 ([below](#running-on-espresso-multi-shot)).

## The dependency

The release is a Maven repository on GitHub Pages, `https://okay-effects.github.io/okay-direct/maven`; the group is
`io.github.okay-effects`, each module an artifact `okay-direct-<module>`, version `0.1.0`.

| Artifact | When |
|---|---|
| `okay-direct-api` | always (the others bring it) |
| `okay-direct-std` | the standard effects, delimited continuations, monadic reflection (Java) |
| `okay-direct-loom` / `-threads` / `-espresso` | an implementation, to run on: one |
| `okay-direct-scala` / `okay-direct-scala2` | the Scala 3 / Scala 2.13 front end |
| `okay-direct-scala-cats`, `-scala-zio`, `-scala2-cats`, `-scala2-zio` | cats-effect and fs2, ZIO and ZStream |
| `okay-direct-tck` | to test an implementation of your own |

**Gradle** (Kotlin DSL):

```kotlin
repositories {
    mavenCentral()
    maven("https://okay-effects.github.io/okay-direct/maven")
}

dependencies {
    implementation("io.github.okay-effects:okay-direct-std:0.1.0")
    implementation("io.github.okay-effects:okay-direct-loom:0.1.0")
}

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
```

**Maven**:

```xml
<repositories>
  <repository>
    <id>okay-direct</id>
    <url>https://okay-effects.github.io/okay-direct/maven</url>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>io.github.okay-effects</groupId>
    <artifactId>okay-direct-std</artifactId>
    <version>0.1.0</version>
  </dependency>
  <dependency>
    <groupId>io.github.okay-effects</groupId>
    <artifactId>okay-direct-loom</artifactId>
    <version>0.1.0</version>
  </dependency>
</dependencies>
```

**sbt**: the artifacts are plain, each front end built for one Scala, so `%`, not `%%`:

```scala-sbt
resolvers += "okay-direct" at "https://okay-effects.github.io/okay-direct/maven"

libraryDependencies ++= Seq(
  "io.github.okay-effects" % "okay-direct-scala" % "0.1.0",     // Scala 3; okay-direct-scala2 on 2.13
  "io.github.okay-effects" % "okay-direct-loom"  % "0.1.0"
)
```

## Choosing an implementation

| | `LoomEffects` | `ThreadEffects` | `EspressoEffects` |
|---|---|---|---|
| artifact | `okay-direct-loom` | `okay-direct-threads` | `okay-direct-espresso` |
| continuations | one-shot | one-shot | multi-shot |
| speed | fast: 100,000 operations in about 0.06 s | slower: a thread hand-over per operation | a copy per multi-shot resumption |
| needs | three JVM flags | nothing | Espresso and its flags |

Start with **Loom**. Take **threads** where the JVM flags cannot be set. Take **Espresso** for a handler that resumes
more than once: `Choose`, backtracking, the list or stream monad.

Loom runs on HotSpot's own continuations, an internal API, so the JVM that runs the program needs:

```
--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED
--add-exports=java.base/jdk.internal.access=ALL-UNNAMED
--add-opens=java.base/java.lang=ALL-UNNAMED
```

Gradle, for `run` and for tests:

```kotlin
val loom = listOf(
    "--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED",
    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
    "--add-opens=java.base/java.lang=ALL-UNNAMED")
tasks.withType<JavaExec> { jvmArgs(loom) }
tasks.withType<Test> { jvmArgs(loom) }
```

sbt: `fork := true` and `javaOptions ++= Seq(...)` with the same three. Your code is compiled without them: only
`okay-direct-loom` touches the internal API.

## A first program: Java

A shop's cart: an effect of our own, prices; a program totalling the cart; two handlers that give prices their
meaning; and the `Fail` of the standard effects for an item with no price. It is checked against the release 0.1.0.

**The effect and the program.** An effect is a sealed interface, its operations records implementing it and
`Effect.Op<Effect, Answer>`. The program takes the capabilities it needs:

```java
// the effect: one operation, as data — Price(item) is answered by an Integer
sealed interface Prices {
    record Price(String item) implements Prices, Op<Prices, Integer> {}
}

// the program: plain Java; it performs inside a stream's lambda, and knows nothing of where prices come from
static int total(Effect<Prices> prices, Effect<Fail<String>> fail, List<String> cart) {
    return cart.stream()
        .mapToInt(item -> {
            int price = prices.perform(new Prices.Price(item));
            return price >= 0 ? price : Fail.raise(fail, "no price for " + item);
        })
        .sum();
}
```

**The handlers**, written apart. A handler is an exhaustive `switch` answering through `k`, the operation the witness
of its answer's type; a `TailHandler` answers in place. An interceptor is a handler standing on top of a capability:

```java
// a handler: what Price means — answered from a price list, -1 for an item not in it
static <A> A priceList(Effects fx, Map<String, Integer> list, Body<Prices, A, RuntimeException> body) {
    return fx.handle(new TailHandler<Prices, A, A>() {
        public A value(A result) { return result; }
        public void operation(Prices op, Answer<Prices> k) {
            switch (op) {
                case Prices.Price p -> k.answer(p, list.getOrDefault(p.item(), -1));
            }
        }
    }, body);
}

// another, on top of it: a sale — each price asked of the one further out, 10% off, told to a trail
static Effect<Prices> sale(Effect<Prices> prices, Effect<Trail> trail) {
    return Interceptor.intercept(prices, (op, pass, k) -> {
        switch (op) {
            case Prices.Price p -> {
                int full = pass.pass(p);
                int discounted = full * 9 / 10;
                Trail.record(trail, p.item() + ": " + full + " -> " + discounted);
                k.answer(p, discounted);
            }
        }
    });
}
```

**Running it**, on `fx = new LoomEffects()` (or `new ThreadEffects()`, with no flags):

```java
var list = Map.of("tea", 30, "cake", 50, "jam", 20);

Fail.attempt(fx, fail -> priceList(fx, list, prices -> total(prices, fail, List.of("tea", "cake", "jam"))));
// Success[value=100]

Fail.attempt(fx, fail -> priceList(fx, list, prices -> total(prices, fail, List.of("tea", "coffee", "cake"))));
// Failure[error=no price for coffee] — and cake is never priced

Trail.keep(fx, trail -> Fail.attempt(fx, fail ->
    priceList(fx, list, prices -> total(sale(prices, trail), fail, List.of("tea", "cake", "jam")))));
// Kept[lines=[tea: 30 -> 27, cake: 50 -> 45, jam: 20 -> 18], value=Success[value=90]]
```

What is unusual here:

- **An effect inside a stream's lambda.** In monadic style `mapToInt` cannot wait for a monad: it would be rewritten
  with `traverse` and `flatMap`. Here the stack from `perform` up to its handler is suspended, whatever is between.
- **An early exit from the middle of a stream.** At `coffee`, `Fail`'s handler does not resume the rest: it unwinds,
  the stream stops, `cake` is never priced. No exception reaches the caller: it gets a value, `Failure`.
- **The meaning changes, the program does not.** `total` is the same; the prices come from a list, from a sale on top of
  it with a trail, from a database, from a test — whichever handler is installed.
- **The signature lists the effects.** `total(Effect<Prices>, Effect<Fail<String>>, ...)` says what it may do; without
  a handler of prices there is no capability to call it with: it does not compile.

The imports: `okay.direct.{Answer, Body, Effect, Effect.Op, Effects, Interceptor, TailHandler}`,
`okay.direct.loom.LoomEffects`, `okay.direct.std.{Fail, Trail}`. A handler that resumes the rest, or does not, or wraps
what it returns, is a general `Handler` ([Guide](guide.md#handling-the-four-kinds-of-handler)).

## A first program: Scala

Scala 3: an effect is an enum, a capability a context parameter, so nothing is passed by hand:

```scala
import okay.direct.{Effect, Effects}
import okay.direct.loom.LoomEffects
import okay.direct.scala3.*

def program(using Effect[Reader[Int]], Effect[State[Int]], Effect[Fail[String]]): Int =
  val base = Reader.ask[Int]
  State.put(State.get[Int] + 1)
  if base < 0 then Fail.raise(s"negative: $base") else base * 2

@main def hello(): Unit =
  given Effects = LoomEffects()
  println(Reader.run(21)(State.run(0)(Fail.attempt(program))))     // (1,Right(42))
```

With scala-cli, the whole of it in one file:

```scala
//> using scala 3.9.0
//> using repository https://okay-effects.github.io/okay-direct/maven
//> using dep io.github.okay-effects:okay-direct-scala:0.1.0
//> using dep io.github.okay-effects:okay-direct-loom:0.1.0
//> using javaOpt --add-exports=java.base/jdk.internal.vm=ALL-UNNAMED
//> using javaOpt --add-exports=java.base/jdk.internal.access=ALL-UNNAMED
//> using javaOpt --add-opens=java.base/java.lang=ALL-UNNAMED
```

Scala 2.13: the same with implicits —
`Reader.run(21) { implicit r => State.run(0) { implicit s => Fail.attempt[String, Int] { implicit f => program } } }`
([Scala](scala.md#scala-213)).

## Running on Espresso: multi-shot

Install the Espresso standalone for JDK 25 (macOS on Apple silicon here; other platforms on the same page,
`https://gds.oracle.com/download/espresso/archive/`):

```sh
mkdir -p ~/.local/espresso-dl && cd ~/.local/espresso-dl
curl -sSLO https://gds.oracle.com/download/espresso/archive/espresso-java25-25.0.0-macos-aarch64.tar.gz
cd ~/.local && tar -xzf espresso-dl/espresso-java25-25.0.0-macos-aarch64.tar.gz
```

Run with its `bin/java` and `--experimental-options --java.Continuum=true`, plus
`--add-exports=java.base/jdk.internal.access=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED`; depend on
`okay-direct-espresso`. A multi-shot handler takes a `MultiEffects`:

```java
MultiEffects fx = new EspressoEffects();
List<List<Boolean>> all = Choose.all(fx, choose -> List.of(Choose.flip(choose), Choose.flip(choose)));
// [[true, true], [true, false], [false, true], [false, false]]
```

On Loom or threads `Choose.all` does not compile: they are not `MultiEffects`. Enumerating with plain loops and giving
the results out by a generator, `Emits.stream(fx, out -> ...)`, needs no multi-shot continuation and runs on any JDK
([Guide](guide.md#generators-streams-on-any-jdk)).

## Where to go next

- [Guide](guide.md): the four kinds of handler, exceptions, implicit capabilities, interceptors and audited
  effects, delimited continuations, monadic reflection, the contract.
- [Scala](scala.md): the front ends, cats-effect and ZIO.
- [Implementations](implementations.md): how each works, the TCK to test one of your own.
- [Design](design.md): why it is built as it is.
