# Guide: the Java API

Everything here is in `okay.direct` (the API, module `api`) and `okay.direct.std` (the standard effects, module
`std`). The examples run on any implementation `fx` of `Effects`: `new LoomEffects()`, `new ThreadEffects()`, or
`new EspressoEffects()` ([Implementations](implementations.md)).

1. [Effects and operations](#effects-and-operations)
2. [Performing: capabilities](#performing-capabilities)
3. [Handling: the four kinds of handler](#handling-the-four-kinds-of-handler)
4. [One-shot and multi-shot](#one-shot-and-multi-shot)
5. [When a handler drops the rest](#when-a-handler-drops-the-rest)
6. [Exceptions](#exceptions)
7. [The standard effects](#the-standard-effects)
8. [Implicit capabilities](#implicit-capabilities)
9. [Control and audit](#control-and-audit)
10. [Generators: streams on any JDK](#generators-streams-on-any-jdk)
11. [Detached continuations: asynchrony without waiting](#detached-continuations-asynchrony-without-waiting)
12. [Delimited continuations](#delimited-continuations)
13. [Monadic reflection](#monadic-reflection)
14. [The contract](#the-contract)

## Effects and operations

An effect is a sealed interface; its operations are records implementing both it and `Effect.Op<F, X>`, the effect
`F` and the operation's answer type `X`:

```java
import okay.direct.Effect.Op;

public sealed interface State<S> {
    record Get<S>() implements State<S>, Op<State<S>, S> {}
    record Put<S>(S value) implements State<S>, Op<State<S>, Void> {}
}
```

Operations are data: they can be matched, compared, logged, intercepted. An operation of `F` must be an `F` — the
implementations hand it to `F`'s handler as one.

## Performing: capabilities

A body performs an effect through its CAPABILITY, an `Effect<F>`, which `F`'s handler gives it:

```java
static <S> S get(Effect<State<S>> state) { return state.perform(new State.Get<>()); }
static <S> void put(Effect<State<S>> state, S value) { state.perform(new State.Put<>(value)); }
```

`perform` returns the operation's answer, typed by the operation. A program takes the capabilities it needs and calls
ordinary methods — in lambdas, across functions, in streams:

```java
static int program(Effect<Reader<Integer>> reader, Effect<State<Integer>> state, Effect<Fail<String>> fail) {
    int base = Reader.ask(reader);
    State.put(state, State.get(state) + 1);
    return base < 0 ? Fail.raise(fail, "negative") : base;
}

Reader.run(fx, 42, reader -> State.run(fx, 0, state -> Fail.attempt(fx, (Effect<Fail<String>> fail) -> program(reader, state, fail))));
```

A missing handler does not compile: there is no capability to pass. Two handlers of one effect — two counters, both
`State<Integer>` — are two capabilities, never confused.

## Handling: the four kinds of handler

`fx.handle(handler, body)` runs `body` with the handler's capability; each `perform` through it suspends the body up
to the handler, which answers. A handler is an exhaustive `switch` over the effect; the operation itself is the
WITNESS of its answer's type, so an answer of the wrong type does not compile (Java has no GADT inference: see
[Design](design.md#gadts-in-java-the-operation-as-a-witness)).

**`TailHandler<F, A, R>`** answers every operation in place, through `Answer<F>`, and runs in a loop, in constant stack:

```java
fx.handle(new TailHandler<Reader<E>, A, A>() {
    public A value(A value) { return value; }
    public void operation(Reader<E> op, Answer<Reader<E>> k) {
        switch (op) {
            case Reader.Ask<E> ask -> k.answer(ask, env);
        }
    }
}, body);
```

**`StatefulHandler<F, S, A, R>`** is the same with a state the implementation keeps: each operation gets the state of its
time and returns the state to go on with. Under a multi-shot handler further out, each resumption has its own (see
below); `State` and `Trail` are written so:

```java
fx.handle(new StatefulHandler<State<S>, S, A, Final<S, A>>() {
    public Final<S, A> value(S state, A value) { return new Final<>(state, value); }
    public S operation(S state, State<S> op, Answer<State<S>> k) {
        return switch (op) {
            case Get<S> get -> { k.answer(get, state); yield state; }
            case Put<S> put -> { k.answer(put, null); yield put.value(); }
        };
    }
}, initial, body);
```

**`Handler<F, A, R, E>`** is the general one: an operation with `k`, the rest of the body up to the handler, a
`Resume`. `k.resume(op, answer)` runs the rest — still under this handler (a deep handler) — and returns what the
handler returns for it; the clause may wrap it, or not resume at all:

```java
fx.handle(new Handler<Fail<E>, A, Outcome<E, A>, T>() {
    public Outcome<E, A> value(A value) { return new Success<>(value); }
    public Outcome<E, A> operation(Fail<E> op, Resume<Fail<E>, Outcome<E, A>, T> k) {
        return switch (op) {
            case Raise<E, ?> raise -> new Failure<>(raise.error());     // k not resumed: the rest dropped
        };
    }
}, body);
```

**`MultiHandler<F, A, R, E>`** is the general one whose `k` is a `MultiResume`, resumed any number of times
([below](#one-shot-and-multi-shot)).

A handler's clauses run OUTSIDE its body: an operation a clause performs goes to the handlers further out, never to
its own handler — a clause performing its own effect is refused.

The general handler's `resume` nests the stack once per resumption, as a deep handler must; a body performing
hundreds of thousands of operations wants a `TailHandler` or a `StatefulHandler`.

## One-shot and multi-shot

How many times a continuation may be resumed is a TYPE:

| | resumed | given to | run by |
|---|---|---|---|
| `Resume<F, R, E>` | at most once; a second time throws `IllegalStateException` | `Handler` | every `Effects` |
| `MultiResume<F, R, E>` | any number of times | `MultiHandler` | a `MultiEffects` only |

`LoomEffects` and `ThreadEffects` are one-shot `Effects`; `EspressoEffects` is a `MultiEffects`. A multi-shot handler
does not compile against a one-shot implementation:

```java
Choose.all(new LoomEffects(), choose -> Choose.flip(choose));
// error: incompatible types: LoomEffects cannot be converted to MultiEffects
```

Each resumption of a `MultiResume` has its own FRAMES — the locals of the rest — and the one HEAP: an object the rest
changes is changed for every resumption. So a clause makes its results after the resumptions, not in an object it
changes between them; and a body binds the answers of multi-shot operations to locals before it builds an object of
them ([Design](design.md#multi-shot-frames-copied-heap-shared)).

## When a handler drops the rest

A clause that does not resume `k` drops the rest of the body. The rest is not left hanging: `Abandoned` is thrown at
the `perform` it is suspended at, the body unwinds — its `finally` blocks run, and an operation of an outer effect in
one is handled as ever — and the handler returns its clause's value. `Abandoned` knows whose rest it is: it unwinds
through the bodies of handlers nested inside, up to the body of the handler that dropped it. Programs do not catch
it. A clause that throws drops the rest in the same way, then its exception goes on.

## Exceptions

A body is a `Body<F, A, E extends Exception>`: it may throw the checked `E`, and `handle` throws `E` on:

```java
var result = State.run(fx, 0, state -> {
    if (State.get(state) == 0) throw new IOException("io");     // State.run throws IOException
    return 1;
});
```

`Resume.resume` throws `E` too: resuming runs the rest, which may throw it. Unchecked exceptions and errors of a body
propagate out of `handle` as they are.

## The standard effects

`okay.direct.std`:

| Effect | Operations | Handlers |
|---|---|---|
| `State<S>` | `get`, `put` | `State.run(fx, initial, body)`: the final state and the value, `State.Final` |
| `Reader<E>` | `ask` | `Reader.run(fx, env, body)` |
| `Fail<E>` | `raise` (answered by any type: never resumed) | `Fail.attempt(fx, body)`: `Fail.Success` or `Fail.Failure` |
| `Choose` | `flip` | `Choose.all(multiFx, body)`: every way, multi-shot |
| `Trail` | `record(line)` | `Trail.keep(fx, body)`: the lines and the value, `Trail.Kept` |
| `Emits<A>` | `emit(value)` | `Emits.collect(fx, body)`: a list; `Emits.stream(fx, body)`: a lazy `Stream` |
| `Async` | `await(future)` | `Async.run(detachedFx, body)`: a `CompletableFuture`, no thread waiting |

plus `Delimited` and `Reflection`, below.

## Implicit capabilities

Passing capabilities is explicit and checked; when it is tiresome, an `Implicit<F>` is a key under which a
capability is found, by `ScopedValue`:

```java
static final Implicit<State<Integer>> COUNTER = Implicit.newInstance();
static final Implicit<Reader<Integer>> ENV = Implicit.newInstance();

static int addEnv() {
    State.put(COUNTER.get(), State.get(COUNTER.get()) + Reader.ask(ENV.get()));
    return State.get(COUNTER.get());
}

Reader.run(fx, 5, ENV.in(() -> State.run(fx, 1, COUNTER.in(Program::addEnv))));
```

`key.in(body)` makes a `Body` binding the key; the implicit capabilities in scope where `in` is called are carried
into the body, so it works on every implementation, a body on a thread of its own too. Two keys of one effect are two
capabilities; an inner binding of a key hides the outer. A handler's clauses see the capabilities of outside its body.
Where no capability is bound, `get` throws `IllegalStateException`: an implicit capability is checked when the program
runs, an explicit one when it compiles. (Scala 3's context parameters are both at once: [Scala](scala.md).)

## Control and audit

**An interceptor** stands between a capability and its handler: each operation comes to it first, at the `perform`.
It may tell (perform effects of its own), pass the operation on, change the operation or its answer, or refuse:

```java
Effect<State<Integer>> doubling = Interceptor.intercept(state, (op, pass, k) -> {
    switch (op) {
        case State.Get<Integer> get -> k.answer(get, pass.pass(get));
        case State.Put<Integer> put -> {
            Trail.record(trail, "put " + put.value());
            k.answer(put, pass.pass(new State.Put<>(put.value() * 2)));
        }
    }
});
```

**A sensitive effect** is performed only under an audit, and answered only by its own handler — a guarantee by types.
Its operations are `Audited.Op`s, of the family `Audited<C>`:

```java
sealed interface Pay {
    record Transfer(String to, int amount) implements Pay, Audited.Op<Pay, String> {}
    record Balance() implements Pay, Audited.Op<Pay, Integer> {}
}
```

- its handler is installed by `Audit.handle(fx, handler, body)`, whose body gets a `Vault<Pay>`: nothing can be
  performed through it;
- `Audit.audit(vault, interceptor, body)` gives the body the capability, `Effect<Audited<Pay>>`, every operation through
  the interceptor;
- a handler of `Audited<Pay>` written by hand gets each operation as an `Audited<Pay>`, of no case and no answer type:
  it can drop the rest, not answer it (a forged witness is refused).

```java
Interceptor<Pay, Audited<Pay>> guarded = (op, pass, k) -> {
    switch (op) {
        case Pay.Transfer t when t.amount() > limit -> { Trail.record(trail, "refused"); Fail.raise(fail, "refused"); }
        case Pay.Transfer t -> {
            var receipt = pass.pass(new Pay.Transfer(t.to().trim().toUpperCase(), t.amount()));
            Trail.record(trail, "transfer " + t.amount() + " → " + receipt);
            k.answer(t, receipt);
        }
        case Pay.Balance b -> k.answer(b, pass.pass(b));
    }
};

Trail.keep(fx, trail -> Fail.attempt(fx, (Effect<Fail<String>> fail) ->
    Pay.bank(fx, 1000, vault -> Audit.audit(vault, guarded, pay -> payments(pay)))));
```

`Pay.bank` is an ordinary handler of `Pay` (an `Audit.Handler`, a `switch` answering through `k`) installed by
`Audit.handle`: the TCK's `Pay.java`. `payments` is a program over `Effect<Audited<Pay>>` that knows nothing of the
audit.

**The observer** sees every operation of every effect, with its answer: `Observer.watch(fx, observer)` is an
implementation whose every capability shows each operation to the observer first. It sees the operation as data
(`op()`, `effect()` — the sealed interface's name) and its answer at its type (`proceed()`); it may refuse by throwing.
Its own operations — to a trail — are not shown to it:

```java
Effects watched = Observer.watch(fx, new Observer() {
    public <X> X observe(Seen<X> seen) {
        Trail.record(trail, seen.effect() + " " + seen.op());
        var x = seen.proceed();
        Trail.record(trail, "  → " + x);
        return x;
    }
});
// State Get[]  → 1, Reader Ask[]  → 5, State Put[value=6]  → null, Pay Transfer[to=acct, amount=6]  → ok:acct:6
```

## Generators: streams on any JDK

A generator is a body in direct style that emits values: its loops do the enumerating, the effect `Emits` only gives the
values out. One resumption per value is enough, so it runs on every implementation — on an ordinary JDK, by Loom or
threads:

```java
Stream<List<Integer>> triples = Emits.stream(fx, out -> {
    for (int a = 1; a <= 10; a++)
        for (int b = a; b <= 10; b++)
            for (int c = b; c <= 15; c++)
                if (a * a + b * b == c * c) Emits.emit(out, List.of(a, b, c));
});   // [3, 4, 5], [6, 8, 10]
```

- `Emits.collect(fx, body)` runs the body to its end, here, and gives the list of what it emitted.
- `Emits.stream(fx, body)` is LAZY: the body runs as the stream is pulled, each `emit` waiting until its value is
  taken, so a stream that stops early (`findFirst`, `limit`) stops it, and an endless generator is fine. The body runs
  on a virtual thread of its own, so it has the capabilities it is given there, not those of the code making the
  stream. Closed, the stream drops the body's rest, which unwinds (`finally` runs); left unclosed, the body is stopped
  when the stream is collected. A failure of the body is thrown where the stream is pulled.

The other way to the same triples is [monadic reflection of streams](#monadic-reflection): there `each` forks the
rest of the body for every element, which needs multi-shot continuations, so Espresso. A generator needs neither.

## Detached continuations: asynchrony without waiting

A `Resume` lives while its clause runs. A DETACHED continuation, a `Later<F>`, outlives it: the clause of a
`DetachedHandler` gets it and returns at once, and anyone resumes it later — a future's callback, another thread, a
consumer pulling a stream — exactly once:

| | |
|---|---|
| `k.answer(op, x)` | the rest resumed with `x`, ON THE RESUMING THREAD, up to its next operation of this handler or its end |
| `k.raise(t)` | the rest resumed by a failure, thrown at its `perform` |
| `k.drop()` | the rest dropped: it unwinds, `finally` runs, the future is cancelled |

`DetachedEffects.start(handler, body)` runs the body at once up to its first operation, and returns a
`CompletableFuture` of the handler's value. As with shots, it is a type: `LoomEffects` and `ThreadEffects` are
`DetachedEffects`; Espresso, whose continuations are single-threaded, is not.

**`std.Async`** awaits a future in direct style with no thread waiting: its handler resumes the rest from the
future's callback, and the body goes on on the thread that completed the future.

```java
CompletableFuture<Integer> total = Async.run(fx, async ->
    Async.await(async, service.quote("tea")) + Async.await(async, service.quote("cake")));
```

A failed future is thrown at its `await`. In the shop's handler `Async.await(async, service.quote(item))` takes the
place of `service.quote(item).join()`: the program is the same, and now no thread waits for the service.

**`Emits.stream` on a `DetachedEffects`** is PULLED: each value taken resumes the generator's detached rest up to its
next `emit`, on the thread that pulls — the generator has no thread of its own. On other implementations it runs on a
virtual thread of its own.

A detached body runs apart from the code that started it — after its first operation, on whatever thread resumes it —
so it uses its own handler's capability and those of handlers inside it; a capability of outside it is not its own.

## Delimited continuations

`std.Delimited` is `shift0` and `reset0` (Materzok and Biernacki) on the API: a reset is a handler, its prompt the
capability, a `shift0` its one operation.

```java
int r = Delimited.reset(fx, (Prompt<Integer> p) -> 1 + p.<Integer>shift0(k -> k.resume(10) * 2));   // 22
```

The `shift0`'s body runs OUTSIDE its reset; `k`, the rest up to the reset, resumes under it again. So in a `shift0`'s
body the reset's own prompt is out of scope (refused), and a prompt further out is in scope, its `shift0` taking the
rest up to its own reset. The answer type is the reset's throughout. On `Effects` `k` is one-shot, on `MultiEffects`
(`Delimited.reset(multiFx, ...)`) multi-shot:

```java
Delimited.reset(espresso, (Prompt<Integer> p) -> 1 + p.<Integer>shift0(k -> k.resume(10) + k.resume(100)));   // 112
```

## Monadic reflection

`std.Reflection` is Filinski's: a monad's computation written in direct style. `reify` runs the body and gives its value
to the monad's `unit`; `reflect(m)` takes a monadic value apart, the rest of the body up to the reify the function
given to the monad's `bind` — a reset and a `shift0`. Java has no higher kinds, so the monad is not a type: `unit` goes
to `reify`, `bind` to each `reflect`, as method references; a monad's reflect is written once, where its value's type
is known:

```java
static <A, R> A get(Prompt<Optional<R>> p, Optional<A> m) { return Reflection.reflect(p, m, Optional::flatMap); }

Optional<Integer> sum = Reflection.reify(fx, Optional::of,
    (Prompt<Optional<Integer>> p) -> get(p, Optional.of(1)) + get(p, Optional.of(2)));       // Optional[3]
```

The prompt's type is written in the lambda: Java does not infer the answer type from `unit` alone. A monad whose bind
calls the rest at most once (`Optional`, an either, a reader) reflects on any implementation; one that calls it more
often needs a `MultiEffects`. Java's streams are such a monad:

```java
Stream<List<Integer>> triples = Reflection.stream(espresso, (Prompt<Stream<List<Integer>>> p) -> {
    int a = Reflection.each(p, IntStream.rangeClosed(1, 10).boxed());
    int b = Reflection.each(p, IntStream.rangeClosed(a, 10).boxed());
    int c = Reflection.each(p, IntStream.rangeClosed(b, 15).boxed());
    if (a * a + b * b != c * c) Reflection.none(p);
    return List.of(a, b, c);
});   // [3, 4, 5], [6, 8, 10]
```

On an ordinary JDK, write the same as a [generator](#generators-streams-on-any-jdk). `each`'s bind is EAGER: it resumes the rest for every element while its clause runs, then joins the streams —
`Stream.flatMap` would resume it later, when the stream is consumed, after the continuation has ended. For the same
reason a lazy monad (a future, an IO, a state function) cannot be reflected: its bind calls the rest after `reflect`
has returned. Await it instead ([Scala](scala.md#cats-effect)).

## The contract

What every implementation keeps, checked by the [TCK](implementations.md#the-tck):

- an operation goes to the handler whose capability performs it; operations of other effects pass the handlers between;
- a handler's clauses run outside its body; a clause performing its own effect is refused;
- a `Resume` is resumed at most once, while its clause runs; a `MultiResume`, on a `MultiEffects`, any number of times;
  a `Later`, on a `DetachedEffects`, at most once, at any time, from any thread;
- a rest not resumed unwinds by `Abandoned`, `finally` blocks run, up to its own handler's body;
- a capability is used only by its body while it runs — on the body's thread, or in a body nested in it; after its
  handler has returned, on another thread, or in its own handler's clause, `perform` throws `IllegalStateException`;
- what a body throws, `handle` throws;
- scoped values bound in a body are the body's: a clause sees those of outside it;
- a program runs on one implementation: handlers of different implementations do not nest.
