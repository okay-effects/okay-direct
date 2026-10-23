package okay.direct.loom;

import okay.direct.DetachedEffects;
import org.junit.jupiter.api.Test;
import okay.direct.tck.DetachedEffectsTck;

class LoomEffectsTest extends DetachedEffectsTck {
    @Override
    public DetachedEffects effects() {
        return new LoomEffects();
    }

    /** an interceptor's shape under a tail handler: on Loom, a yield now and then keeps the stack small */
    @Test
    void aGeneralHandlerResumingInsideATailHandlersBodyRunsInConstantStack() {
        generalHandlerResumingInsideATailHandlersBodyInConstantStack();
    }
}
