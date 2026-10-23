package okay.direct.threads;

import okay.direct.DetachedEffects;
import org.junit.jupiter.api.Test;
import okay.direct.tck.DetachedEffectsTck;

class ThreadEffectsTest extends DetachedEffectsTck {
    @Override
    public DetachedEffects effects() {
        return new ThreadEffects();
    }

    /** an interceptor's shape under a tail handler, in constant stack */
    @Test
    void aGeneralHandlerResumingInsideATailHandlersBodyRunsInConstantStack() {
        generalHandlerResumingInsideATailHandlersBodyInConstantStack();
    }
}
