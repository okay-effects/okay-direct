# The concept: what algebraic effects are, and how to work with them

This is the stable picture the okay-effects family is built to: what an effect is, the invariants every library
keeps, how the styles of writing a program (types, direct style, parameters, exceptions, data) are one model seen
from different sides, and what makes it an industrial standard rather than a library. The [guide](guide.md) is the
API; [design](design.md) the reasons for its details; [okay-effects](https://github.com/okay-effects/okay-effects)
the reference model of the same concept in Scala 3, Java and Rust.

## 1. Three nouns

- **An operation** is a request, and it is DATA: a record with the question's arguments, whose type says the type of
  its answer (`record Price(String item) implements Prices, Op<Prices, Optional<Integer>>`).
- **A handler** answers the operations of one effect: in place, or with the rest of the program in hand (the
  continuation `k`), which it resumes once, many times, or never.
- **A program** asks operations and does nothing else to the world. Its type lists what it may ask: its **row**.

Running a program is choosing its handlers, one line at the entry point:
`Fx.run(fx, Reader.run(config, State.run(0, Shop::checkout)))`. Everything else follows from this: what a program
does is in its type; how it is done is in the handlers; a test, a production run, a recording, a replay of a recorded
log, an audit are different handlers of the same program.

## 2. The invariants

1. **An operation is data, its answer's type in its type.** `Op<F, X>`: a handler matching an operation learns the
   type of the answer it owes; a wrong answer does not compile (the operation is the witness, `k.answer(op, x)`).
2. **An effect is closed, a handler exhaustive.** An effect is a sealed interface; a handler is a `switch` over it with
   no default: a new operation breaks every handler that does not answer it, at compile time.
3. **The row is a type: a list of effects, the nearest handler first.** `Eff<State<Integer>, Eff<Log, Pure>>`. The
   order of the handlers is meaning (a state outside a choice is one state through all ways; inside, one per way),
   so the order is in the type.
4. **One source of truth.** Operations, effects and rows are defined once. Every style below is a projection of them;
   no style has its own definition of an effect, and the conversions between styles are total.
5. **Handlers by what they do with the rest.** In place (`TailHandler`, constant stack); with a one-shot `k`
   (`Handler`); with a state (`StatefulHandler`); with a multi-shot `k` (`MultiHandler`). How many shots is a TYPE
   (`MultiEffects`), never a flag checked late.
6. **A capability lives while its body runs.** Used after its handler returned, on another thread, or in its own
   handler's clause, it throws; it never silently works.
7. **A dropped rest unwinds.** A handler that does not resume ends the body as an exception would: `finally` runs.
8. **Multi-shot is never replay.** The rest taken many times is a value that is copied (frames, a state machine, a
   cloneable coroutine), not the body run again from its start.
9. **Real resources live only in handlers.** Programs get data, effects and ids, never live clients, clocks, random or
   threads; the host builds the handlers over its clients at the entry point.
10. **Sensitive effects are handled only under audit.** An effect its author marks sensitive cannot be given a plain
    handler: only `audit(...)` and its handler take it, and every operation passes the auditor.

## 3. The styles, and how they complement each other

| Style | What it is in the model | In okay-direct |
|---|---|---|
| **Types** | the row: what a program may ask, as a nested list in its type | `Eff<E, R>`, `Pure` |
| **Direct style** | the program is ordinary code; `perform` a call that returns the answer | `Program<R, A>`, `Fx<R>` |
| **Parameters** | one place of the row given to code that needs only it | `Effect<F>` = `Fx.head(fx)` |
| **Exceptions** | an error is an effect with a place in the row; Java's checked exceptions meet it at the ends | `Fail<E>`, `Fail.catching`, `Fail.rethrow` |
| **Data** | the program as a value of operations and binds: inspected, sent, stored, resumed many times without frames | okay-effects' `Comp<A, R>` (same `Eff` row); `Reflection` |

They are not alternatives to choose between: one program uses them all. A method that needs several effects takes the
row (`Fx<...>`); a helper that needs one takes its capability (`Effect<Log>`); a call into a library that throws
`IOException` is wrapped by `Fail.catching` into the row's failure; at the entry point `Fail.rethrow` gives the
failure back to Java's `throws`; the same operations, under the same row, can be written as data where a program has
to be stored or inspected. Code written in the body style (`Body<F, A, E>`, one capability) and code of rows call each
other through `Fx.with`.

```java
// the row: a state, a reader of the step under it, anything under that (open, so longer rows fit)
static int step(Fx<? extends Eff<State<Integer>, ? extends Eff<Reader<Integer>, ?>>> fx) {
    Effect<State<Integer>> state = Fx.head(fx);      // a place given as a parameter-style capability
    int by = Reader.ask(Fx.second(fx));
    int s = State.get(state);
    State.put(state, s + by);
    return s;
}

// handlers in the order of the row, nearest first; Fx.run takes only a program of Pure
State.Final<Integer, Integer> r = Fx.run(new LoomEffects(), Reader.run(10, State.run(0, Shop::step)));

// errors: a checked exception into the row, and out of it at the end
Program<Eff<Fail<IOException>, Pure>, String> read =
        fx -> Fail.catching(Fx.head(fx), IOException.class, () -> Files.readString(path));
String text = Fail.rethrow(effects, read);           // throws IOException
```

## 4. Rows in Java: the rules

Java has no implicit search and does not capture wildcards nested in type arguments, so the row is POSITIONAL, as in
okay-effects' Java kernel:

- **A place is taken by its position**: `Fx.head`, `Fx.second`, `Fx.third`, `Fx.tail` for the rest. Two places of one
  effect (`Eff<Reader<String>, Eff<Reader<Integer>, Pure>>`) are two places — no lookup by type, so no erasure problem.
- **Open rows are written open at every step**: `Fx<? extends Eff<State<Integer>, ? extends Eff<Log, ?>>>`. A program
  so typed is a program of every row that starts with these places. Closed rows (`Eff<A, Eff<B, Pure>>`) are for
  programs that say exactly what they do.
- **A handler takes the first place**: `Fx.handle(handler, program)` and the standard forms (`State.run(0, p)`,
  `Reader.run(env, p)`, `Fail.attempt(p)`, `Choose.collect(multi, p)`) turn a `Program<Eff<F, R>, A>` into a
  `Program<R, B>`; `Fx.run` runs a `Program<Pure, A>`. A place left unhandled does not compile.
- **A named row** (`interface Shop extends Eff<Prices, Eff<Log, Pure>> {}`) can be read (`head`, `second`) but not
  taken apart by handlers; `Fx.fit` widens a row's type.
- **A program throws no checked exception.** An error is `Fail<E>` in the row. (A type parameter for the exception was
  tried: Java infers it as `Exception` as soon as two handlers are nested, so every caller would catch `Exception`.)
- **A body-style handler serves a row** through `Fx.with(capability, rest)`; that is how every standard form is
  written, in one line.

What javac refuses, kept as tests (`tck/src/test/.../RowTypesTest.java`): a place read as another effect, a place the
row has not, the first place of `Pure`, handlers in another order than the row, a state of another type, a place left
unhandled.

## 5. How to work with effects

1. **Name the effects first**: what the program asks and what it is answered. An effect is a sealed interface, its
   operations records. Real resources become effects (`Prices`, `Clock`, `Sql`), never parameters of live clients.
2. **Write the program against its row** in direct style. A function that needs one effect takes its `Effect<F>`;
   one that needs several takes the row. No ambient effects: time, randomness, IO, environment are effects.
3. **Write the handlers**: the production one over the host's client; the test one over a `Map` or a `State`; a
   recording one where a run must be reproducible. Errors and aborts are `Fail`, not exceptions thrown through.
4. **Choose the order** of handlers: it is meaning, and it is in the row's type.
5. **Run in one line** at the entry point. Inside a program, install a handler around a sub-program and keep it a
   program; never run a sub-program to pull a value out.
6. **Control and audit** at the boundary: `Observer` for a trail, `Interceptor` to check or change operations,
   sensitive effects under `Audit`.
7. **Tests swap handlers**, not mocks; the TCK checks the implementation, not the program.

## 6. What makes it an industrial standard

| Part | What it is |
|---|---|
| **API** | one small module with no dependencies, stable by semver: `Op`, `Effect`, `Eff`, `Pure`, `Fx`, `Program`, the handler kinds, `Effects` |
| **SPI** | `Effects` / `MultiEffects` / `DetachedEffects`, implemented by threads (portable, the default on a stock JDK), Loom (fastest; needs `--add-exports`), Espresso (multi-shot), a bytecode transform (okay-java's resumable plugin) |
| **TCK** | every implementation passes the same tests (`EffectsTck`, `MultiEffectsTck`, `DetachedEffectsTck`, `RowsTests`) and the types refuse the same programs (`RowTypesTest`) |
| **Contract** | written: dynamic extent of capabilities, one-shot by default, shots as a type, a dropped rest unwinds, exceptions of bodies and clauses, scoped values across continuations, interrupt and cancellation, never resume under a monitor |
| **Checking** | `okay-audit`: effects only through capabilities, no live resources in programs, no capability escaping its body |
| **Interop** | `CompletableFuture` (`DetachedEffects`), streams (`Emits`), transactions (`sql`), tracing (`otel`), cats-effect and ZIO |
| **Debugging** | the stack of a direct-style program is the program's; `Observer`/`Trail` record the operations |

What is NOT done, on purpose: a monad-only API (Java programmers would not read it); annotations and AOP
(`@Effect`), which lose the types and the stack; multi-shot as a run-time flag; effect rows found by reflection.

## 7. Where the family stands, and what is next

- Done (okay-direct 0.1.1-SNAPSHOT): the row (`Eff`, `Pure`, `Fx`, `Program`) over the existing API, on every
  implementation; row forms of `State`, `Reader`, `Fail`, `Choose`; `Fail.catching`/`Fail.rethrow`; TCK and
  compile-time refusals.
- Next: **one Java API.** Three exist today — okay-direct, okay-java (resumable bytecode), okay-kernel-api. The
  standard is one: the api above, with okay-java's resumable transform as another implementation of its SPI, passing
  the same TCK. Then: row forms for the remaining standard effects (`Trail`, `Emits`, `Async`, `Channel`), rows in the
  Scala front ends (as context parameters they already carry the effects; the row is their type), the data style in
  Java on the same `Eff` (okay-effects' `Comp`), and okay-audit's check that a capability does not escape its body.
