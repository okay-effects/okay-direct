package okay.direct.loom;

import okay.direct.Effects;
import okay.direct.tck.EffectsTck;

class LoomEffectsTest extends EffectsTck {
    @Override
    public Effects effects() {
        return new LoomEffects();
    }
}
