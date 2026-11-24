package okay.direct.espresso;

import okay.direct.Body;
import okay.direct.Handler;
import okay.direct.MultiEffects;
import okay.direct.MultiHandler;
import okay.direct.StatefulHandler;
import okay.direct.InPlace;
import org.graalvm.continuations.Continuation;

/**
 * ON ESPRESSO's CONTINUATIONS, multi-shot: every handled body a fiber of a driver (Espresso's continuations do not
 * nest), and a resumption of a multi-shot handler a copy of the rest, its frames copied and its heap shared. Runs on
 * Espresso, Java on Truffle, with {@code --experimental-options --java.Continuum=true}; scoped values switched with
 * the fibers, as on Loom: {@code --add-exports=java.base/jdk.internal.access=ALL-UNNAMED} and
 * {@code --add-opens=java.base/java.lang=ALL-UNNAMED}.
 */
public final class EspressoEffects implements MultiEffects {
    public EspressoEffects() {
        if (!Continuation.isSupported())
            throw new UnsupportedOperationException("needs Espresso with --experimental-options --java.Continuum=true");
    }

    @Override
    public <F, A, B, E extends Exception> B handle(Handler<F, A, B, E> handler, Body<F, A, E> body) throws E {
        return new Handling.General<>(handler, body).run();
    }

    @Override
    public <F, A, B, E extends Exception> B handle(InPlace<F, A, B> handler, Body<F, A, E> body) throws E {
        return new Handling.Tail<F, A, B, E>(handler, body).run();
    }

    @Override
    public <F, S, A, B, E extends Exception> B handle(StatefulHandler<F, S, A, B> handler, S initial, Body<F, A, E> body)
            throws E {
        return new Handling.Stateful<F, S, A, B, E>(handler, initial, body).run();
    }

    @Override
    public <F, A, B, E extends Exception> B handle(MultiHandler<F, A, B, E> handler, Body<F, A, E> body) throws E {
        return new Handling.Multi<>(handler, body).run();
    }

    @Override
    public String toString() {
        return "EspressoEffects";
    }
}
