package okay.direct.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import okay.direct.Answer;
import okay.direct.Audit;
import okay.direct.Audited;
import okay.direct.Effect;
import okay.direct.Effects;
import okay.direct.Interceptor;
import okay.direct.Observer;
import okay.direct.TailHandler;
import okay.direct.std.Fail;
import okay.direct.std.Reader;
import okay.direct.std.State;
import okay.direct.std.Trail;
import org.junit.jupiter.api.Test;

/** Control and audit of effects: interceptors, sensitive effects, the observer; on every implementation. */
interface InterceptorTests {
    Effects effects();

    static String masked(String account) {
        return "****" + account.substring(Math.max(0, account.length() - 4));
    }

    /** A program of payments: it knows nothing of the audit it runs under. */
    static int payments(Effect<Audited<Pay>> pay) {
        pay.perform(new Pay.Transfer(" acct-1234 ", 100));
        int balance = pay.perform(new Pay.Balance());
        pay.perform(new Pay.Transfer("acct-77", 5000));
        return balance;
    }

    /** The audit of payments: the account normalized, a limit, the trail masked, a refusal raised. */
    static Interceptor<Pay, Audited<Pay>> guarded(int limit, Effect<Trail> trail, Effect<Fail<String>> fail) {
        return (op, pass, k) -> {
            switch (op) {
                case Pay.Transfer transfer -> {
                    var to = transfer.to().trim().toUpperCase();
                    if (transfer.amount() > limit) {
                        var why = "refused: " + transfer.amount() + " to " + masked(to) + " over the limit " + limit;
                        Trail.record(trail, why);
                        Fail.<String, Void>raise(fail, why);
                    } else {
                        var receipt = pass.pass(new Pay.Transfer(to, transfer.amount()));
                        Trail.record(trail, "transfer " + transfer.amount() + " to " + masked(to) + " → " + receipt);
                        k.answer(transfer, receipt);
                    }
                }
                case Pay.Balance balance -> {
                    var b = pass.pass(balance);
                    Trail.record(trail, "balance → " + b);
                    k.answer(balance, b);
                }
            }
        };
    }

    static Interceptor<Pay, Audited<Pay>> passing() {
        return (op, pass, k) -> {
            switch (op) {
                case Pay.Transfer transfer -> k.answer(transfer, pass.pass(transfer));
                case Pay.Balance balance -> k.answer(balance, pass.pass(balance));
            }
        };
    }

    @Test
    default void anInterceptorTellsAndChangesAPlainEffect() {
        var fx = effects();
        var result = Trail.keep(fx, trail -> State.run(fx, 1, state -> {
            Effect<State<Integer>> doubling = Interceptor.intercept(state, (op, pass, k) -> {
                switch (op) {
                    case State.Get<Integer> get -> k.answer(get, pass.pass(get));
                    case State.Put<Integer> put -> {
                        Trail.record(trail, "put " + put.value());
                        k.answer(put, pass.pass(new State.Put<>(put.value() * 2)));
                    }
                }
            });
            State.put(doubling, 5);
            return State.get(doubling);
        }));
        assertEquals(new Trail.Kept<>(List.of("put 5"), new State.Final<>(10, 10)), result);
    }

    @Test
    default void theAuditOfASensitiveEffectNormalizesTellsAndRefuses() {
        var fx = effects();
        var result = Trail.keep(fx, trail -> Fail.<String, Integer, RuntimeException>attempt(fx,
            fail -> Pay.bank(fx, 1000, vault -> Audit.audit(vault, guarded(1000, trail, fail), InterceptorTests::payments))));
        assertEquals(new Trail.Kept<>(List.of(
            "transfer 100 to ****1234 → ok:ACCT-1234:100",
            "balance → 900",
            "refused: 5000 to ****T-77 over the limit 1000"),
            new Fail.Failure<String, Integer>("refused: 5000 to ****T-77 over the limit 1000")), result);
    }

    @Test
    default void aHandlerOfASensitiveEffectWrittenByHandCannotAnswer() {
        var fx = effects();
        assertThrows(IllegalArgumentException.class, () -> fx.handle(new TailHandler<Audited<Pay>, String, String>() {
            @Override
            public String value(String value) {
                return value;
            }

            @Override
            public void operation(Audited<Pay> op, Answer<Audited<Pay>> k) {
                // the operation is an Audited<Pay>: no case, no answer's type; a forged witness is refused
                k.answer(new Pay.Transfer("forged", 0), "forged");
            }
        }, pay -> pay.perform(new Pay.Transfer("acct", 1))));
    }

    @Test
    default void anAuditedCapabilityDoesNotOutliveItsAudit() {
        var fx = effects();
        var escaped = new AtomicReference<Effect<Audited<Pay>>>();
        Pay.bank(fx, 10, vault -> Audit.audit(vault, passing(), pay -> {
            escaped.set(pay);
            return 0;
        }));
        assertThrows(IllegalStateException.class, () -> escaped.get().perform(new Pay.Balance()));
    }

    @Test
    default void theObserverSeesEveryOperationAndItsAnswer() {
        var fx = effects();
        var result = Trail.keep(fx, trail -> {
            var watched = Observer.watch(fx, new Observer() {
                @Override
                public <X> X observe(Seen<X> seen) {
                    Trail.record(trail, seen.effect() + " " + seen.op());
                    var x = seen.proceed();
                    Trail.record(trail, "  → " + x);
                    return x;
                }
            });
            return Reader.run(watched, 5, reader -> State.run(watched, 1, state -> {
                State.put(state, State.get(state) + Reader.ask(reader));
                return Pay.bank(watched, 100, vault -> Audit.audit(vault, passing(),
                    pay -> pay.perform(new Pay.Transfer("acct", State.get(state)))));
            }));
        });
        assertEquals(List.of(
            "State Get[]", "  → 1", "Reader Ask[]", "  → 5", "State Put[value=6]", "  → null",
            "State Get[]", "  → 6", "Pay Transfer[to=acct, amount=6]", "  → ok:acct:6"), result.lines());
        assertEquals(new State.Final<>(6, "ok:acct:6"), result.value());
    }

    @Test
    default void theObserverRefusesAndItsOwnOperationsAreNotShownToIt() {
        var fx = effects();
        var result = Trail.keep(fx, trail -> {
            var watched = Observer.watch(fx, new Observer() {
                @Override
                public <X> X observe(Seen<X> seen) {
                    if (seen.effect().equals("Pay")) {
                        Trail.record(trail, "read-only: " + seen.op() + " refused");
                        throw new SecurityException("read-only");
                    }
                    return seen.proceed();
                }
            });
            // the trail handled through the watched implementation too: its records not shown to the observer
            return Trail.keep(watched, inner -> {
                Trail.record(inner, "start");
                try {
                    return Pay.bank(watched, 100, vault -> Audit.audit(vault, passing(), pay -> pay.perform(new Pay.Balance())));
                } catch (SecurityException e) {
                    return -1;
                }
            });
        });
        assertEquals(List.of("read-only: Balance[] refused"), result.lines());
        assertEquals(new Trail.Kept<>(List.of("start"), -1), result.value());
    }

    @Test
    default void anAbandonedRestUnwindsThroughInnerHandlers() {
        var fx = effects();
        var cleaned = new AtomicBoolean();
        var result = Fail.<String, State.Final<Integer, Integer>, RuntimeException>attempt(fx, fail -> State.run(fx, 0, state -> {
            try {
                State.put(state, 1);
                return Fail.<String, Integer>raise(fail, "boom");
            } finally {
                cleaned.set(true);
            }
        }));
        assertTrue(cleaned.get());
        assertEquals(new Fail.Failure<String, State.Final<Integer, Integer>>("boom"), result);
    }

    @Test
    default void aTailClauseThatThrowsUnwindsTheBody() {
        var fx = effects();
        var cleaned = new AtomicBoolean();
        assertThrows(SecurityException.class, () -> fx.handle(new TailHandler<Reader<Integer>, Integer, Integer>() {
            @Override
            public Integer value(Integer value) {
                return value;
            }

            @Override
            public void operation(Reader<Integer> op, Answer<Reader<Integer>> k) {
                throw new SecurityException("no");
            }
        }, reader -> {
            try {
                return Reader.ask(reader);
            } finally {
                cleaned.set(true);
            }
        }));
        assertTrue(cleaned.get());
    }
}
