# okay-direct: documentation

Algebraic effects in DIRECT STYLE on the JVM: `perform` is a method call, `handle` runs a body, a handler is an
exhaustive `switch` over an effect's operations and a continuation. No monad, no `flatMap`, no code transformation.
One API, several implementations; front ends for Scala 3 and Scala 2.13; interop with cats-effect and ZIO.

| Document | What it covers |
|---|---|
| [Getting started](getting-started.md) | the dependency (Gradle, Maven, sbt), which implementation and its JVM flags, a first program in Java and in Scala, your own effect, Espresso |
| [Guide](guide.md) | the Java API: effects, handlers, capabilities, the standard effects, implicit capabilities, control and audit, delimited continuations, monadic reflection, multi-shot |
| [Scala](scala.md) | the Scala 3 and Scala 2 front ends; cats-effect and ZIO: awaiting, bodies as `IO`/`ZIO`, ZIO's channels as effects, parallel bodies, resources, streams |
| [Implementations](implementations.md) | Loom's continuations, threads, Espresso's multi-shot continuations; the contract every one keeps; flags; the TCK |
| [Design](design.md) | why it is built as it is: GADTs in Java, capabilities against rows, checked exceptions, scoped values, the shared heap of multi-shot; the bugs the tests found |

The [README](../README.md) is the short tour.

## Modules

| Module | Artifact | |
|---|---|---|
| `api` | `okay-direct-api` | the interfaces: perform and handle, of no implementation |
| `std` | `okay-direct-std` | `State`, `Reader`, `Fail`, `Choose`, `Trail`, `Emits`, `Async` (structured concurrency, time), `VirtualClock`, `Channel`, `Delimited`, `Reflection` |
| `tck` | `okay-direct-tck` | what every implementation does, as JUnit tests to extend |
| `loom` | `okay-direct-loom` | on HotSpot's `jdk.internal.vm.Continuation`; one-shot |
| `threads` | `okay-direct-threads` | a virtual thread per body; one-shot; portable |
| `espresso` | `okay-direct-espresso` | on Espresso's continuations; MULTI-SHOT |
| `sql` | `okay-direct-sql` | SQL as an effect: statements are operations, `Transaction` their handler; read-only and logged capabilities |
| `otel` | `okay-direct-otel` | every operation a span of OpenTelemetry, with no change to the program |
| `interop/scala`, `interop/scala2` | `okay-direct-scala`, `okay-direct-scala2` | the front ends: effects, handlers, reflection, `Async`, `Channel`, `Sql` |
| `interop/scala-cats`, `interop/scala2-cats` | `okay-direct-scala-cats`, `okay-direct-scala2-cats` | cats-effect and fs2 |
| `interop/scala-zio`, `interop/scala2-zio` | `okay-direct-scala-zio`, `okay-direct-scala2-zio` | ZIO and ZStream |

Java 25 (a JDK 25 toolchain), Gradle; `./gradlew build` builds and tests everything — the Espresso tests where
Espresso is installed ([Implementations](implementations.md#espresso)).
