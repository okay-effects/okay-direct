# Getting started

From nothing to a running program: the dependency, the JVM, a first program in Java and in Scala, your own effect, and
which implementation to run on.

1. [What you need](#what-you-need)
2. [The dependency](#the-dependency)
3. [Choosing an implementation](#choosing-an-implementation)
4. [A first program: Java](#a-first-program-java)
5. [Your own effect](#your-own-effect)
6. [A first program: Scala](#a-first-program-scala)
7. [Running on Espresso: multi-shot](#running-on-espresso-multi-shot)
8. [Where to go next](#where-to-go-next)

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

A program asks a `Reader` for a base, counts in a `State`, and fails on a negative base — three effects, each a
capability it is given:

```java
import okay.direct.Effect;
import okay.direct.Effects;
import okay.direct.loom.LoomEffects;
import okay.direct.std.Fail;
import okay.direct.std.Reader;
import okay.direct.std.State;

public class Hello {
    static int program(Effect<Reader<Integer>> reader, Effect<State<Integer>> state, Effect<Fail<String>> fail) {
        int base = Reader.ask(reader);
        State.put(state, State.get(state) + 1);
        return base < 0 ? Fail.raise(fail, "negative: " + base) : base * 2;
    }

    public static void main(String[] args) {
        Effects fx = new LoomEffects();
        var result = Reader.run(fx, 21, reader ->
            State.run(fx, 0, state ->
                Fail.<String, Integer, RuntimeException>attempt(fx, fail -> program(reader, state, fail))));
        System.out.println(result);   // Final[state=1, value=Success[value=42]]
    }
}
```

`program` is ordinary Java: it calls methods, and could call them in lambdas, in streams, in other functions. Each
`run`/`attempt` is a handler; the body it runs gets the handler's capability. Change `21` to `-1` and the result is
`Final[state=1, value=Failure[error=negative: -1]]`; change `new LoomEffects()` to `new ThreadEffects()` and it runs
without the flags.

## Your own effect

An effect is a sealed interface, its operations records implementing it and `Effect.Op<Effect, Answer>`; a handler is
an exhaustive `switch` answering through `k`, the operation the witness of its answer's type:

```java
import okay.direct.Answer;
import okay.direct.Effect;
import okay.direct.Effect.Op;
import okay.direct.TailHandler;

sealed interface Console {
    record Print(String line) implements Console, Op<Console, Void> {}
    record Read() implements Console, Op<Console, String> {}
}

static void print(Effect<Console> console, String line) { console.perform(new Console.Print(line)); }
static String read(Effect<Console> console) { return console.perform(new Console.Read()); }

// the real console
String name = fx.handle(new TailHandler<Console, String, String>() {
    public String value(String value) { return value; }
    public void operation(Console op, Answer<Console> k) {
        switch (op) {
            case Console.Print print -> { System.out.println(print.line()); k.answer(print, null); }
            case Console.Read read -> k.answer(read, System.console().readLine());
        }
    }
}, console -> {
    print(console, "your name?");
    return read(console);
});
```

The same program runs under another handler — one answering `Read` from a list, recording `Print`s — for a test. A
handler that resumes the rest, or does not, or wraps what it returns, is a general `Handler` ([Guide](guide.md#handling-the-four-kinds-of-handler)).

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

On Loom or threads `Choose.all` does not compile: they are not `MultiEffects`.

## Where to go next

- [Guide](guide.md): the four kinds of handler, exceptions, implicit capabilities, interceptors and audited
  effects, delimited continuations, monadic reflection, the contract.
- [Scala](scala.md): the front ends, cats-effect and ZIO.
- [Implementations](implementations.md): how each works, the TCK to test one of your own.
- [Design](design.md): why it is built as it is.
