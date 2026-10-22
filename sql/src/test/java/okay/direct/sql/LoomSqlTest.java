package okay.direct.sql;

import okay.direct.Effects;
import okay.direct.loom.LoomEffects;

class LoomSqlTest extends SqlTests {
    @Override
    Effects effects() {
        return new LoomEffects();
    }
}
