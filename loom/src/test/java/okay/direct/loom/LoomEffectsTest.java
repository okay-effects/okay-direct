package okay.direct.loom;

import okay.direct.DetachedEffects;
import okay.direct.tck.DetachedEffectsTck;

class LoomEffectsTest extends DetachedEffectsTck {
    @Override
    public DetachedEffects effects() {
        return new LoomEffects();
    }
}
