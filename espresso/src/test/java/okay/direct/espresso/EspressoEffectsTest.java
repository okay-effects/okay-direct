package okay.direct.espresso;

import okay.direct.MultiEffects;
import okay.direct.tck.MultiEffectsTck;

class EspressoEffectsTest extends MultiEffectsTck {
    @Override
    public MultiEffects effects() {
        return new EspressoEffects();
    }
}
