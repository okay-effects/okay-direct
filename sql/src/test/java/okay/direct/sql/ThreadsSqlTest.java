package okay.direct.sql;

import okay.direct.Effects;
import okay.direct.threads.ThreadEffects;

class ThreadsSqlTest extends SqlTests {
    @Override
    Effects effects() {
        return new ThreadEffects();
    }
}
