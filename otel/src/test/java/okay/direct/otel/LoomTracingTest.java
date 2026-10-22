package okay.direct.otel;

import okay.direct.DetachedEffects;
import okay.direct.loom.LoomEffects;

class LoomTracingTest extends TracingTests {
    @Override
    DetachedEffects effects() {
        return new LoomEffects();
    }
}
