# Design

Why okay-direct is built as it is: what was tried, what the compilers and the JVM allowed, and what the tests found.

1. [Direct style, not a monad](#direct-style-not-a-monad)
2. [GADTs in Java: the operation as a witness](#gadts-in-java-the-operation-as-a-witness)
3. [Capabilities, not rows](#capabilities-not-rows)
4. [Checked exceptions: as markers, not as control](#checked-exceptions-as-markers-not-as-control)
5. [Shots as types](#shots-as-types)
6. [A dropped rest unwinds](#a-dropped-rest-unwinds)
7. [Scoped values and continuations](#scoped-values-and-continuations)
8. [Multi-shot: frames copied, heap shared](#multi-shot-frames-copied-heap-shared)
9. [Control and audit with capabilities](#control-and-audit-with-capabilities)
10. [What the tests found](#what-the-tests-found)

## Direct style, not a monad

A freer monad makes a program data, and a handler its interpreter; in Java and Kotlin that costs a `flatMap` at each
step, and — without union types and higher kinds — rows written by hand (`With<Reader, With<State, ...>>` and the
places `Init(Init(Last()))`). With continuations of the runtime a program is ordinary code: `perform` suspends the stack
up to the handler, `resume` puts it back. Filinski's monadic reflection is then exactly `perform` and `handle`; no
transformation of code (Danvy and Filinski's CPS, a macro, a plugin) is needed, so lambdas, functions, `try`, loops and
the JDK's streams all work as they are.

The JVM gives three such runtimes, each an implementation: Loom's internal continuations (one-shot, nested), threads
(one-shot, portable), Espresso's (copyable: multi-shot).

## GADTs in Java: the operation as a witness

An effect is a GADT: `Get` is answered by `S`, `Put` by `Unit`. Java, like Kotlin, does not refine a type parameter
in a `switch` case: in `case Get<S> g`, the answer type `X` is not known to be `S`. So the operation itself is the
WITNESS: an operation is an `Effect.Op<F, X>`, and a handler answers with `k.answer(op, value)` — `value` must be the
`X` of `op`. It compiles only with an answer of the right type; the implementations check that the witness is the very
operation being handled. The alternatives were weighed:

- an `answer: (S) -> X` field in each operation (the identity, a witness by data): no cast anywhere, but every
  operation carries a function and a second type parameter, and operations stop being plain data;
- one guarded cast in the kernel: chosen there — the casts live inside the implementations, never in a program.

Scala 3 refines GADTs, so its front end's clauses return the answer itself (`case Get() => s`); Scala 2.13 refines
from typed patterns but its match analysis is wrong on them, so it keeps the witness.

## Capabilities, not rows

`perform` goes through a capability, `Effect<F>`, given to the body by its handler — the style of Effekt and of Scala 3's
capture checking — instead of a row of effects in the program's type found by implicit search:

- **the check is static**: a missing handler does not compile, there is no capability to pass;
- **no erasure problem**: two `State<Integer>` are two objects, where a lookup by type could not tell them apart, nor
  `State<Integer>` from `State<String>`;
- **no places to write**: Java has no implicit search, so rows need their places written (`Init(Init(Last()))`) or found
  by reflection at run time (and a cast).

The cost: capabilities are passed. `Implicit` passes them by `ScopedValue` instead, checked at run time; Scala 3's
context parameters pass them implicitly AND check them statically.

## Checked exceptions: as markers, not as control

Checked exceptions are Java's effect system: `throws Reader, State` is a row, a `catch` removes an effect. Tried as
the control: a throw unwinds the frames, so a `catch` has no continuation to resume — unless the continuation is taken
before (Loom), and then the exception only carries it, and an operation for a handler further out unwinds the frames
between. Tried as markers for implicit capabilities: `javac` infers the rest of a row as one type variable, the least
upper bound — with two effects left it is `Effect`, the row collapsed; exceptions cannot be generic (`State<S>`); and
the JDK's lambdas declare no `throws`, so `forEach` could not perform. Kept: a body's own checked exceptions, `Body<F, A,
E>`, thrown on by `handle` — one type variable, inferred well.

## Detached continuations

A `Resume` dies with its clause: the handler's code holds the stack the rest returns to. A `Later` has no such place
to return to: resumed, it runs the rest on the resuming thread up to the next operation, where the clause is called
again, and its end completes a future. Loom's continuations are not tied to a thread, so this is a `run()` on another
one; threads hand the answer over and wait there. Espresso's continuations are single-threaded, so it is not a
`DetachedEffects`. What it buys: `await` with no thread waiting, and generators pulled with no thread of their own.

## Shots as types

Whether a continuation may be resumed more than once depends on the runtime: Loom's cannot be copied, Espresso's can.
So it is a type: `Resume` is one-shot and given by every `Effects`; `MultiResume` extends it and is given only to a
`MultiHandler`, run only by a `MultiEffects`. A program needing many shots (`Choose`) does not compile against a one-shot
implementation. The Scala front ends add `Affine[M]` for monadic reflection: a monad whose bind calls the rest at most
once reflects anywhere; any other, by `reifyMulti` on a `MultiEffects`.

## A dropped rest unwinds

A clause that does not resume drops the rest; on Loom the continuation could just be forgotten, on threads the body's
thread would wait forever. Both unwind it instead, the same way: `Abandoned` is thrown at the `perform` the rest waits
in, so `finally` blocks run — resources are released, even under a `Fail`. `Abandoned` knows whose rest it is, and the
body of a handler nested inside lets it through to the body it belongs to.

## Scoped values and continuations

`ScopedValue`'s bindings live in a field of the `Thread`. A virtual thread is a `Thread` of its own, so the JDK switches
them with it; a continuation of ours runs on the thread that runs it, and nothing switched them: a value bound in a body
leaked into its handler's clause, and a resumed body saw its handler's. `LoomEffects` and `EspressoEffects` save and put
back the bindings at every switch, through `JavaLangAccess` and `Thread.setScopedValueBindings` (hence the flags), the
cache of scoped values cleared. `Implicit` carries the implicit capabilities in scope into a body itself, so it works
where a body has a thread of its own.

## Multi-shot: frames copied, heap shared

A copy of a suspended continuation has its own frames, and the same heap: the semantics of a freer monad, where a
program's locals are its own and the objects it points to are shared. Two consequences:

- **a clause collects its results after the resumptions**: `Choose.all` made a list after the first resumption and added
  the second's to it; a branch forked inside the second resumption added to the same list. Now it makes each list after
  both resumptions;
- **an object made before a multi-shot operation and filled after it is one for every branch**: Scala 3's
  `List(flip, flip)` makes its varargs array first and fills it after the flips, so every branch fills the one array.
  Bind the answers to locals first.

A handler's state shared by the branches would be the same mistake; `StatefulHandler` keeps it in the handling, which
is copied with the branch.

## Control and audit with capabilities

okay-core's interceptors ([its docs](../../okay-core/docs/intercept.md)) are handlers inserted in a row, the guarantee
for a sensitive effect an opaque type in the row. With capabilities an interceptor is a WRAPPER of a capability, its
clause run at the `perform`; the guarantee is that a sensitive effect's operations are of the family `Audited<C>`,
performed through a capability only `Audit.audit` makes, and answered by the handler only `Audit.handle` installs —
whose body gets a `Vault`, not a capability. `Audited` must be an interface the operations implement: javac's bridge
method casts a clause's argument to the erased family, and a class would fail it. The observer is an `Effects` that
wraps every capability its handlers hand out.

## What the tests found

| | Found by | Fixed |
|---|---|---|
| a body's handler swallowed the unwinding of an outer handler's dropped rest | the audit's refusal through `Fail`, under a bank | `Abandoned` knows its owner |
| a tail clause that threw left the body waiting | a refusal from a tail handler | the body unwound |
| `Audited` as a class broke on javac's bridge cast | the audit tests | an interface the operations implement |
| scoped values leaked between a body and its clause on Loom | `Implicit`'s clause test | the bindings switched with the stack |
| on Espresso, a continuation's bottom frame pointed at the original | the first multi-shot copy | the frames patched to the copy |
| on Espresso, a nested choice's rest kept to be copied pointed at a handling the branch had copied | choices in choices | the chain cut by key |
| on Espresso, a rest resumed from an outer clause sent its events to its first owner | `shift0` to an outer prompt | the resumer owns the rest |
| on Espresso, one branch dropped marked the whole multi-shot handling dropped | the triples' `none` | the dropped chain's fibers marked |
| `Choose.all` collected into a list shared by the branches | an outer choice inside an inner one | lists made after both resumptions |
