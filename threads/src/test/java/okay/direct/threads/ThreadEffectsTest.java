package okay.direct.threads;

import okay.direct.DetachedEffects;
import okay.direct.tck.DetachedEffectsTck;

class ThreadEffectsTest extends DetachedEffectsTck {
    @Override
    public DetachedEffects effects() {
        return new ThreadEffects();
    }
}
