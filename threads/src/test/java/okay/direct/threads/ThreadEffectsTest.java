package okay.direct.threads;

import okay.direct.Effects;
import okay.direct.tck.EffectsTck;

class ThreadEffectsTest extends EffectsTck {
    @Override
    public Effects effects() {
        return new ThreadEffects();
    }
}
