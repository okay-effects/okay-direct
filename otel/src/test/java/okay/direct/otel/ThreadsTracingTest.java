package okay.direct.otel;

import okay.direct.DetachedEffects;
import okay.direct.threads.ThreadEffects;

class ThreadsTracingTest extends TracingTests {
    @Override
    DetachedEffects effects() {
        return new ThreadEffects();
    }
}
