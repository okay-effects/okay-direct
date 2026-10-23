# Implementations

Three implementations of `Effects`, each passing the same TCK:

| | `LoomEffects` | `ThreadEffects` | `EspressoEffects` |
|---|---|---|---|
| module | `loom` | `threads` | `espresso` |
| on | `jdk.internal.vm.Continuation` | a virtual thread per body | Espresso's continuations |
| shots | one | one | MANY: a `MultiEffects` |
| detached continuations | yes: a `DetachedEffects` | yes: a `DetachedEffects` | no |
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
- **Detached**: a continuation is not tied to a thread (a virtual thread's moves from carrier to carrier), so a `Later`
  runs the rest on whatever thread resumes it — `run()` there, up to the next yield.
- **Scoped values switched with the stack.** A virtual thread has its own `Thread` object, so its `ScopedValue`
  bindings; a continuation of ours runs on the thread that runs it, and Loom does not switch the bindings on a yield. So
  `LoomEffects` saves the bindings of the stack it leaves and puts back those of the stack it goes on with, the thread's
  cache of scoped values cleared (`Bindings`): without it a scoped value bound in a body leaked into its handler's clause.
- **`synchronized` in a body is fine** (JDK 24+, JEP 491). A native frame between `handle` and `perform` — a call by
  reflection, a class's static initializer — pins the continuation, and the `perform` throws.
- **NO RESUMING UNDER A MONITOR.** A continuation run while the thread holds a monitor taken OUTSIDE it — a future
  completed inside `synchronized`, whose callback resumes a detached body — crashes HotSpot 25 when the body yields
  again (SIGSEGV in `LightweightSynchronizer::exit`; a few lines reproduce it with `Continuation` alone). `std` never
  does it: its channels and clocks complete outside their locks, `Emits` takes a `ReentrantLock`. Code completing a
  future an `Async` body awaits must do the same.
- **A tail handler answers at the `perform`, no yield.** A `TailHandler`'s (and so a `StatefulHandler`'s) clause is
  called right at the `perform`, on the body's own stack, with the bindings of outside the body — its continuation is
  run once, only to mark its scope: `perform` is usable where a yield would be (the scope mounted: in the body, on its
  thread, while it runs) and not from the clause itself. A clause that throws or does not answer unwinds the body as a
  dropped rest does. One operation in 256 still yields: a yield freezes the body's stack into the heap and thaws it
  lazily, so the stack stays small — what keeps a general handler resuming inside the body (an interceptor, a call
  deeper at each resumption) in constant stack. About 20× faster than a yield per operation: a counter's `get` and
  `put` 6 ns each, 10^8 asks of a `Reader` 0.5 s (JDK 26, Apple Silicon).
- **Awaits of what is already there take no stack**: a rest answered by its clause before the clause returns goes on
  in the run's loop, not in a call from the clause.
- **Flags**, to compile and to run:

  ```
  --add-exports=java.base/jdk.internal.vm=ALL-UNNAMED
  --add-exports=java.base/jdk.internal.access=ALL-UNNAMED
  --add-opens=java.base/java.lang=ALL-UNNAMED            (to run)
  ```

## Threads

Every handled body runs on a virtual thread of its own; `perform` hands the operation to the handler's thread through
a one-value slot and waits for the answer. Portable: no flags, no internal API (the release is Java 25 bytecode, so JDK 25+).

- **Hand-off by spinning, then parking.** A side waiting for the other spins first (2^12 spin-waits, ~45 µs at most),
  then parks: the other side is usually a clause or a body step away, and a parked platform thread is woken by the OS
  in microseconds. An operation round trip ~150 ns, 50× less than through a `SynchronousQueue` made per operation; the
  cost, up to ~45 µs of a core spent by a body or handler that waits long (an await of I/O).
- **No answer at the `perform`.** Loom's tail handlers answer on the body's stack; here the clause stays on the
  handler's thread: it would otherwise see the scoped values bound inside the body, and without internal API there is
  no switching them.
- **One-shot.** A dropped rest is told so through its reply, and unwinds.
- **Detached.** A `Later` hands its answer to the body's thread and waits, on the resuming thread, for the body's next
  operation or its end.
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
`EffectsTck` (one-shot), `DetachedEffectsTck` (with detached continuations: `AsyncTests` and `ChannelTests` too) or
`MultiEffectsTck` (multi-shot) and gives the implementation:

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
| `EffectsTck` | handlers nested in any order; operations passing inner handlers; operations in lambdas and across functions; clauses outside their bodies; a general handler wrapping the rest; a dropped rest unwinding, `finally` and all; one-shot refused twice; capabilities confined (after the handler, another thread, the own clause); checked exceptions through `handle`; errors; constant stack; a virtual thread; implicit capabilities; a tail clause that throws, that does not answer, that performs an outer effect | 22 |
| `InterceptorTests` | an interceptor changing a plain effect; the audit of payments; a hand-written handler of a sensitive effect unable to answer; an audited capability not outliving its audit; the observer telling and refusing; a dropped rest unwinding through inner handlers; a throwing tail clause | 8 |
| `DelimitedTests` | `shift0`; a dropped rest; an outer prompt through an inner reset; the reset's own prompt refused in a `shift0`'s body; one-shot refused twice; reflection of `Optional` (its types inferred, a path through JSON by a fold), of an either, of a writer, with other effects | 10 |
| `EmitsTests` | a generator collected; a generator as a lazy stream, pulled only as far as needed; a closed stream stopping its body, which unwinds; a failure of the body thrown by the stream | 4 |
| `DetachedEffectsTck` | `await` with no thread waiting; a failed future thrown at its await; the shop awaiting an asynchronous service; a `Later` resumed once; a dropped `Later` unwinding, the future cancelled | 5 |
| `AsyncTests` | 200 000 awaits of completed futures in constant stack; `both`, `all`, `race`, `timeout` in virtual time, failures and cancellations; retries backing off in no time; a run ending after its tasks; a task cancelled with its own tasks; a failed run cancelling its tasks; a join's failure; the system's clock; a cancelled join | 14 |
| `ChannelTests` | a producer and a consumer; a buffered channel taking values until full; 20 000 ping-pongs in constant stack; many producers; select of the ready channel; select's timeout losing no value; a cancelled receiver taking nothing; a cancelled sender taking its value back; a closed channel | 9 |
| `MultiEffectsTck` | `Choose`; each branch's locals and one heap; a state inside a choice, and outside; a failure per branch; an outer choice forking an inner one; a multi-shot `k`; reflection of lists and streams; Pythagorean triples | 11 |

The nested choice's eight results were checked against an independent freer monad.
