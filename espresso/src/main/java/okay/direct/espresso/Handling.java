package okay.direct.espresso;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import okay.direct.Abandoned;
import okay.direct.Answer;
import okay.direct.Body;
import okay.direct.Effect;
import okay.direct.Handler;
import okay.direct.MultiHandler;
import okay.direct.MultiResume;
import okay.direct.Resume;
import okay.direct.StatefulHandler;
import okay.direct.TailHandler;
import org.graalvm.continuations.ContinuationEntryPoint;

/**
 * A HANDLER INSTALLED: its body, the code that runs its clauses (its owner: a fiber, or the thread), and its key —
 * the same in every copy, so a capability finds its handling in the branch it runs in. A multi-shot resumption
 * copies the chain of fibers from the operation up to its handling, with the handlings installed in them and their
 * state: so a state handled inside a choice is each branch's own, and one outside is threaded through them.
 */
abstract class Handling<F, A, R, E extends Exception> {
    final Object key;
    final Body<F, A, E> body;
    /** The fiber whose code runs this handling's clauses; null: the thread's. */
    Fiber owner;
    /** The continuation its clause holds now, to copy with it. */
    Copyable active;

    Handling(Body<F, A, E> body) {
        this.key = new Object();
        this.body = body;
        this.owner = Driver.current().running;
    }

    /** A copy of this handling, for a branch: its fields as they are, the references remapped after. */
    Handling(Handling<F, A, R, E> of) {
        this.key = of.key;
        this.body = of.body;
        this.owner = of.owner;
        this.active = of.active;
    }

    abstract Handling<F, A, R, E> copy();

    void remap(Map<Object, Object> copies) {
        owner = (Fiber) copies.getOrDefault(owner, owner);
        if (active != null) active = (Copyable) copies.getOrDefault(active, active);
    }

    /** The body's dispatch: its value, its failure, or each operation to the clause. */
    abstract R dispatch(Driver.Event event) throws E;

    /** Runs the handled body, in the owner's code. */
    final R run() throws E {
        var driver = Driver.current();
        var first = owner == null ? driver.loop(this, Fiber.start(this), null) : (Driver.Event) driver.ask(new Driver.Start(this));
        return dispatch(first);
    }

    /**
     * Resumes the body at {@code performer} with {@code answer}: its next event, given to whoever resumes it. The
     * rest runs in the context of its resumer — a clause further out may resume it — so the resumer becomes the
     * handling's owner: its events go there, and an operation of the body not this handling's is looked for there.
     */
    final Driver.Event resume(Fiber performer, Object answer) {
        var driver = Driver.current();
        owner = driver.running;
        return owner == null ? driver.loop(this, performer, answer)
            : (Driver.Event) driver.ask(new Driver.Continue(this, performer, answer));
    }

    /**
     * The body's rest dropped: it unwinds, Abandoned thrown at its perform; its end awaited. The fibers of that rest
     * are marked, not this handling: under a multi-shot handler, one branch dropped is not the others.
     */
    final void abandon(Driver.Performed performed) {
        for (var f = performed.performer(); ; f = f.handling.owner) {
            f.abandonedFor = key;
            if (f.handling.key == key) break;
        }
        resume(performed.performer(), new Driver.Abandon(key));
    }

    ContinuationEntryPoint entry() {
        return capability -> {
            Driver.current().running.capability = capability;
            Driver.Event result;
            try {
                result = new Driver.Returned(body.run(new Capability<>(key)));
            } catch (Abandoned unwound) {
                result = unwound.of(key) ? new Driver.Ended() : new Driver.Failed(unwound);
            } catch (Throwable t) {
                result = new Driver.Failed(t);
            }
            Driver.current().running.result = result;
        };
    }

    @SuppressWarnings("unchecked") // the body's value is an A
    final A value(Driver.Returned returned) {
        return (A) returned.value();
    }

    @SuppressWarnings("unchecked") // an operation of F is an F
    final F op(Driver.Performed performed) {
        return (F) performed.op();
    }

    @SuppressWarnings("unchecked") // what the body throws is an E, or unchecked
    final E rethrown(Driver.Failed failed) {
        if (failed.failure() instanceof RuntimeException e) throw e;
        if (failed.failure() instanceof Error e) throw e;
        return (E) failed.failure();
    }

    final IllegalStateException ended() {
        return new IllegalStateException("the body ended without a value");
    }

    /** A continuation of an operation, held by a clause: copied with the chain it is in. */
    abstract static class Copyable {
        Handling<?, ?, ?, ?> handling;
        Driver.Performed performed;

        Copyable(Handling<?, ?, ?, ?> handling, Driver.Performed performed) {
            this.handling = handling;
            this.performed = performed;
        }

        abstract Copyable copy();

        void remap(Map<Object, Object> copies) {
            handling = (Handling<?, ?, ?, ?>) copies.getOrDefault(handling, handling);
            var performer = (Fiber) copies.getOrDefault(performed.performer(), performed.performer());
            performed = new Driver.Performed(performed.op(), performer);
        }

        final void check(Effect.Op<?, ?> op) {
            if (op != performed.op()) throw new IllegalArgumentException("resumed with " + op + ", not " + performed.op());
        }
    }

    /** The chain from {@code performer} up to {@code target}'s body copied: fibers, handlings in them, their clauses' continuations. */
    static Fiber copyChain(Fiber performer, Handling<?, ?, ?, ?> target) {
        // up to the body of the target's handling: by key, as a rest kept to be copied again points at the
        // handling as it was, the target perhaps a copy of it in a branch further out
        var fibers = new ArrayList<Fiber>();
        for (var f = performer; ; f = f.handling.owner) {
            fibers.add(f);
            if (f.handling.key == target.key) break;
        }
        Map<Object, Object> copies = new IdentityHashMap<>();
        var handlings = new ArrayList<Handling<?, ?, ?, ?>>();
        var actives = new ArrayList<Copyable>();
        for (var f : fibers) {
            copies.put(f, new Fiber());
            if (f.handling.key == target.key) {
                if (f.handling != target) copies.put(f.handling, target);
            } else {
                var h = f.handling.copy();
                copies.put(f.handling, h);
                handlings.add(h);
                if (f.handling.active != null) {
                    var k = f.handling.active.copy();
                    copies.put(f.handling.active, k);
                    actives.add(k);
                }
            }
        }
        var frames = new ArrayList<Object[]>();
        for (var f : fibers) {
            Copier.Copy copy;
            try {
                copy = Copier.copy(f.continuation);
            } catch (IOException | ClassNotFoundException e) {
                throw new IllegalStateException("the continuation could not be copied", e);
            }
            var f2 = (Fiber) copies.get(f);
            f2.continuation = copy.continuation();
            f2.capability = copy.capability();
            f2.handling = (Handling<?, ?, ?, ?>) copies.getOrDefault(f.handling, f.handling);
            f2.request = f.request instanceof Driver.Continue c
                ? new Driver.Continue((Handling<?, ?, ?, ?>) copies.getOrDefault(c.handling(), c.handling()),
                    (Fiber) copies.getOrDefault(c.performer(), c.performer()), c.answer())
                : f.request;
            f2.reply = f.reply;
            f2.result = f.result;
            f2.abandonedFor = f.abandonedFor;
            copies.put(f.continuation, copy.continuation());
            copies.put(f.capability, copy.capability());
            frames.addAll(copy.frames());
        }
        Copier.patch(frames, copies);
        for (var h : handlings) h.remap(copies);
        for (var k : actives) k.remap(copies);
        return (Fiber) copies.get(performer);
    }

    /** A general handler: each operation to its clause with the rest, one-shot. */
    static final class General<F, A, R, E extends Exception> extends Handling<F, A, R, E> {
        private final Handler<F, A, R, E> handler;

        General(Handler<F, A, R, E> handler, Body<F, A, E> body) {
            super(body);
            this.handler = handler;
        }

        private General(General<F, A, R, E> of) {
            super(of);
            this.handler = of.handler;
        }

        @Override
        General<F, A, R, E> copy() {
            return new General<>(this);
        }

        @Override
        R dispatch(Driver.Event event) throws E {
            return switch (event) {
                case Driver.Returned returned -> handler.value(value(returned));
                case Driver.Failed failed -> throw rethrown(failed);
                case Driver.Ended ended -> throw ended();
                case Driver.Performed performed -> {
                    var k = new Once<F, A, R, E>(this, performed);
                    active = k;
                    try {
                        yield handler.operation(op(performed), k);
                    } finally {
                        if (k.fresh) {
                            k.fresh = false;
                            abandon(performed);
                        }
                    }
                }
            };
        }

        static final class Once<F, A, R, E extends Exception> extends Copyable implements Resume<F, R, E> {
            boolean fresh = true;

            Once(Handling<?, ?, ?, ?> handling, Driver.Performed performed) {
                super(handling, performed);
            }

            @Override
            Copyable copy() {
                var k = new Once<F, A, R, E>(handling, performed);
                k.fresh = fresh;
                return k;
            }

            @Override
            @SuppressWarnings("unchecked") // the handling of a Once<F, A, R, E> is a General<F, A, R, E>
            public <X> R resume(Effect.Op<F, X> op, X answer) throws E {
                check(op);
                if (!fresh) throw new IllegalStateException("one-shot: " + op + " resumed twice");
                fresh = false;
                var h = (General<F, A, R, E>) handling;
                return h.dispatch(h.resume(performed.performer(), answer));
            }
        }
    }

    /** A handler answering in place: in a loop, in constant stack. */
    static final class Tail<F, A, R, E extends Exception> extends Handling<F, A, R, E> {
        private final TailHandler<F, A, R> handler;

        Tail(TailHandler<F, A, R> handler, Body<F, A, E> body) {
            super(body);
            this.handler = handler;
        }

        private Tail(Tail<F, A, R, E> of) {
            super(of);
            this.handler = of.handler;
        }

        @Override
        Tail<F, A, R, E> copy() {
            return new Tail<>(this);
        }

        @Override
        R dispatch(Driver.Event event) throws E {
            while (true) {
                switch (event) {
                    case Driver.Returned returned -> {
                        return handler.value(value(returned));
                    }
                    case Driver.Failed failed -> throw rethrown(failed);
                    case Driver.Ended ended -> throw ended();
                    case Driver.Performed performed -> {
                        var k = new Given<F>(this, performed);
                        active = k;
                        try {
                            handler.operation(op(performed), k);
                        } finally {
                            if (!k.given) abandon(performed);
                        }
                        if (!k.given) throw new IllegalStateException("a tail handler did not answer " + performed.op());
                        event = resume(performed.performer(), k.value);
                    }
                }
            }
        }
    }

    /** A handler with a state, kept here: copied with a branch, so each resumption has its own. */
    static final class Stateful<F, S, A, R, E extends Exception> extends Handling<F, A, R, E> {
        private final StatefulHandler<F, S, A, R> handler;
        private S state;

        Stateful(StatefulHandler<F, S, A, R> handler, S initial, Body<F, A, E> body) {
            super(body);
            this.handler = handler;
            this.state = initial;
        }

        private Stateful(Stateful<F, S, A, R, E> of) {
            super(of);
            this.handler = of.handler;
            this.state = of.state;
        }

        @Override
        Stateful<F, S, A, R, E> copy() {
            return new Stateful<>(this);
        }

        @Override
        R dispatch(Driver.Event event) throws E {
            while (true) {
                switch (event) {
                    case Driver.Returned returned -> {
                        return handler.value(state, value(returned));
                    }
                    case Driver.Failed failed -> throw rethrown(failed);
                    case Driver.Ended ended -> throw ended();
                    case Driver.Performed performed -> {
                        var k = new Given<F>(this, performed);
                        active = k;
                        try {
                            state = handler.operation(state, op(performed), k);
                        } finally {
                            if (!k.given) abandon(performed);
                        }
                        if (!k.given) throw new IllegalStateException("a stateful handler did not answer " + performed.op());
                        event = resume(performed.performer(), k.value);
                    }
                }
            }
        }
    }

    /** An answer given in place. */
    static final class Given<F> extends Copyable implements Answer<F> {
        boolean given;
        Object value;

        Given(Handling<?, ?, ?, ?> handling, Driver.Performed performed) {
            super(handling, performed);
        }

        @Override
        Copyable copy() {
            var k = new Given<F>(handling, performed);
            k.given = given;
            k.value = value;
            return k;
        }

        @Override
        public <X> void answer(Effect.Op<F, X> op, X value) {
            check(op);
            if (given) throw new IllegalStateException(op + " answered twice");
            given = true;
            this.value = value;
        }
    }

    /** A multi-shot handler: each resumption runs a copy of the rest, the rest itself kept as it was. */
    static final class Multi<F, A, R, E extends Exception> extends Handling<F, A, R, E> {
        private final MultiHandler<F, A, R, E> handler;

        Multi(MultiHandler<F, A, R, E> handler, Body<F, A, E> body) {
            super(body);
            this.handler = handler;
        }

        private Multi(Multi<F, A, R, E> of) {
            super(of);
            this.handler = of.handler;
        }

        @Override
        Multi<F, A, R, E> copy() {
            return new Multi<>(this);
        }

        @Override
        R dispatch(Driver.Event event) throws E {
            return switch (event) {
                case Driver.Returned returned -> handler.value(value(returned));
                case Driver.Failed failed -> throw rethrown(failed);
                case Driver.Ended ended -> throw ended();
                case Driver.Performed performed -> {
                    var k = new Many<F, A, R, E>(this, performed);
                    active = k;
                    try {
                        yield handler.operation(op(performed), k);
                    } finally {
                        if (!k.resumed) abandon(performed);
                    }
                }
            };
        }

        static final class Many<F, A, R, E extends Exception> extends Copyable implements MultiResume<F, R, E> {
            boolean resumed;

            Many(Handling<?, ?, ?, ?> handling, Driver.Performed performed) {
                super(handling, performed);
            }

            @Override
            Copyable copy() {
                var k = new Many<F, A, R, E>(handling, performed);
                k.resumed = resumed;
                return k;
            }

            @Override
            @SuppressWarnings("unchecked") // the handling of a Many<F, A, R, E> is a Multi<F, A, R, E>
            public <X> R resume(Effect.Op<F, X> op, X answer) throws E {
                check(op);
                resumed = true;
                var h = (Multi<F, A, R, E>) handling;
                var branch = copyChain(performed.performer(), h);
                return h.dispatch(h.resume(branch, answer));
            }
        }
    }

    /** The capability given to a body: finds its handling by key, in the branch it runs in. */
    static final class Capability<F> implements Effect<F> {
        private final Object key;

        Capability(Object key) {
            this.key = key;
        }

        @Override
        @SuppressWarnings("unchecked") // the answer given with this very operation as its witness: an X
        public <X> X perform(Op<F, X> op) {
            var driver = Driver.current();
            if (driver.running == null) throw new IllegalStateException("performed outside its body: " + op);
            var reply = driver.ask(new Driver.Perform(key, op));
            if (reply instanceof Driver.Abandon abandon) throw new Abandoned(abandon.key());
            if (reply instanceof Driver.Refuse refuse) throw new IllegalStateException(refuse.why());
            return (X) reply;
        }
    }
}
