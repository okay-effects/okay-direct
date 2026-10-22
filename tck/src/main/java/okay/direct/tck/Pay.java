package okay.direct.tck;

import okay.direct.Answer;
import okay.direct.Audit;
import okay.direct.Audited;
import okay.direct.Effects;

/** A sensitive effect: its operations Audited.Ops, so performed only under an audit. */
sealed interface Pay {
    record Transfer(String to, int amount) implements Pay, Audited.Op<Pay, String> {}
    record Balance() implements Pay, Audited.Op<Pay, Integer> {}

    /** A bank with {@code balance}: an ordinary handler of Pay, installed by Audit.handle. */
    static <A, E extends Exception> A bank(Effects effects, int balance, Audit.VaultBody<Pay, A, E> body) throws E {
        return Audit.handle(effects, new Audit.Handler<Pay, A, A>() {
            private int left = balance;

            @Override
            public A value(A value) {
                return value;
            }

            @Override
            public void operation(Pay op, Answer<Audited<Pay>> k) {
                switch (op) {
                    case Transfer transfer -> {
                        left -= transfer.amount();
                        k.answer(transfer, "ok:" + transfer.to() + ":" + transfer.amount());
                    }
                    case Balance balance -> k.answer(balance, left);
                }
            }
        }, body);
    }
}
