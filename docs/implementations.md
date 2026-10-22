# Implementations

Three implementations of `Effects`, each passing the same TCK:

| | `LoomEffects` | `ThreadEffects` | `EspressoEffects` |
|---|---|---|---|
| module | `loom` | `threads` | `espresso` |
| on | `jdk.internal.vm.Continuation` | a virtual thread per body | Espresso's continuations |
| shots | one | one | MANY: a `MultiEffects` |
| JVM | HotSpot, JDK 25+ | any JDK 25+ | Espresso (Java on Truffle), JDK 25 |
| flags | see [Loom](#loom) | none | see [Espresso](#espresso) |
| 100,000 operations of `State` | about 0.06 s | about 1.5 s | (multi-shot: a copy per resumption) |

1. [Loom](#loom)
2. [Threads](#threads)
3. [Espresso](#espresso)
4. [The TCK](#the-tck)

## Loom

Every handled body runs in a `jdk.internal.vm.Continuation` of a `ContinuationScope` of its own; `perform` YIELDS TO
THAT SCOPE, through any continuations of handlers nested inside — Loom's continuations nest, and a yield to an outer
scope suspends the whole chain. The handler's clause runs where `handle` was called, outside; resuming is `run()` of the
continuation. This is multi-prompt `shift0`, each scope a prompt.

- **One-shot**: Loom's continuations cannot be copied; a second resumption throws.
- **Scoped values switched with the stack.** A virtual thread has its own `Thread` object, so its `ScopedValue`
  bindings; a continuation of ours runs on the thread that runs it, and Loom does not switch the bindings on a yield. So
  `LoomEffects` saves the bindings of the stack it leaves and puts back those of the stack it goes on with, the thread's
  cache of scoped values cleared (`Bindings`): without it a scoped value bound in a body leaked into its handler's clause.
- **`synchronized` is fine** (JDK 24+, JEP 491). A native frame between `handle` and `perform` — a call by reflection,
  a class's static initializer — pins the continuation, and the `perform` throws.
- **Flags**, to compile and to run:

  ```
  --add-exports=java.base/jdk.internal.vm=ALL-UNNAMED
  --add-exports=java.base/jdk.internal.access=ALL-UNNAMED
  --add-opens=java.base/java.lang=ALL-UNNAMED            (to run)
  ```

## Threads

Every handled body runs on a virtual thread of its own; `perform` hands the operation to the handler's thread through
a `SynchronousQueue` and waits for the answer. Portable: no flags, no internal API (the release is Java 25 bytecode, so JDK 25+).

- **One-shot.** A dropped rest is told so through its reply, and unwinds.
- **The confinement of capabilities** is checked by a chain of bodies in a `ThreadLocal`: a body's thread, and the
  threads of bodies nested in it.
- `ThreadLocal`s and `Thread.currentThread()` inside a body are the body's thread's, not the caller's — unlike Loom,
  where a body runs on its handler's thread. A program should not count on either.

## Espresso

Espresso is Java on Truffle; its continuations (`org.graalvm.continuations`, with `--java.Continuum=true`) can be
SERIALIZED, so copied — the one way on the JVM to a multi-shot continuation without rewriting bytecode.

**Installing.** The JDK 25 standalone, for macOS on Apple silicon:

```sh
mkdir -p ~/.local/espresso-dl && cd ~/.local/espresso-dl
curl -sSLO https://gds.oracle.com/download/espresso/archive/espresso-java25-25.0.0-macos-aarch64.tar.gz
cd ~/.local && tar -xzf espresso-dl/espresso-java25-25.0.0-macos-aarch64.tar.gz
```

The build looks for it at `~/.local/espresso-java25-25.0.0-macos-aarch64` (`-PespressoHome=...` for elsewhere); the
`espresso` module's tests run ON it (Gradle's test `executable`), and are skipped where it is not. The front ends' tests
run on it by `./gradlew :scala:espressoTest :scala2:espressoTest`.

**Running.** `--experimental-options --java.Continuum=true`, and for the scoped values
`--add-exports=java.base/jdk.internal.access=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED`.

**How it works.**

- **Fibers under a driver.** Espresso's continuations do not nest. So every handled body is a fiber of its own, and a
  thread-local DRIVER resumes them all: a fiber asks it for what it needs and suspends — to perform an operation, to
  start the body of a handler it installs, to resume a rest its handler's clause resumes — and the driver delivers the
  events (a value, a failure, an operation) to the code of the handling they are for: the thread's own code in its loop,
  or the fiber running that handling, resumed with the event as its reply.
- **A copy of a continuation: its frames, not the heap.** Espresso writes a suspended continuation's frames to an
  `ObjectOutput` and reads them back. Java serialization would copy every object reachable from them — the driver too —
  and cannot read back the lambdas Continuum makes serializable. So `Copier` gives Espresso an `ObjectOutput` that takes
  the objects BY REFERENCE: each frame's arrays are copied, so each copy has its own locals; everything they point to is
  the same heap. The copy's frames are then pointed at its own continuation, capability to suspend, and records.
- **A multi-shot resumption** copies the CHAIN of fibers from the operation up to the handler — with the handlings
  installed in them, their state, their clauses' continuations — and runs the copy; the rest itself is kept as it was,
  to be copied again. So a `StatefulHandler`'s state inside a choice is each branch's own; one outside is threaded
  through the branches, as in a freer monad.
- **A capability finds its handling by KEY**, the same in every copy, in the branch it runs in: a program may keep a
  capability in a field or a lambda, and the copies share that object.
- **The resumer owns the rest.** A rest resumed from a clause further out runs in that clause's context: the events of
  its handling go to whoever resumed it.
- **A dropped branch** is marked on the fibers of its chain, not on the handling: under a multi-shot handler one branch
  dropped is not the others.

The cost: each resumption of a `MultiResume` copies the frames of the chain; the TCK takes some seconds on Espresso
against a tenth of one on Loom.

## The TCK

Module `tck`, `okay.direct.tck`: what every implementation does, as JUnit tests. An implementation's test extends
`EffectsTck` (one-shot) or `MultiEffectsTck` (multi-shot) and gives the implementation:

```java
class LoomEffectsTest extends EffectsTck {
    @Override
    public Effects effects() {
        return new LoomEffects();
    }
}
```

| | | tests |
|---|---|---|
| `EffectsTck` | handlers nested in any order; operations passing inner handlers; operations in lambdas and across functions; clauses outside their bodies; a general handler wrapping the rest; a dropped rest unwinding, `finally` and all; one-shot refused twice; capabilities confined (after the handler, another thread, the own clause); checked exceptions through `handle`; errors; constant stack; a virtual thread; implicit capabilities | 18 |
| `InterceptorTests` | an interceptor changing a plain effect; the audit of payments; a hand-written handler of a sensitive effect unable to answer; an audited capability not outliving its audit; the observer telling and refusing; a dropped rest unwinding through inner handlers; a throwing tail clause | 8 |
| `DelimitedTests` | `shift0`; a dropped rest; an outer prompt through an inner reset; the reset's own prompt refused in a `shift0`'s body; one-shot refused twice; reflection of `Optional`, with other effects | 7 |
| `MultiEffectsTck` | `Choose`; each branch's locals and one heap; a state inside a choice, and outside; a failure per branch; an outer choice forking an inner one; a multi-shot `k`; reflection of lists and streams; Pythagorean triples | 11 |

The nested choice's eight results were checked against an independent freer monad.
