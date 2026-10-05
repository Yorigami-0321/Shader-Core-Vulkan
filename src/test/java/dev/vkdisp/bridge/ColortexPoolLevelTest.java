package dev.vkdisp.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** mip 级数公式守卫（h46 M 臂：差一 = createTexture 每帧抛、全链静默停摆）。 */
class ColortexPoolLevelTest {

    @Test
    @DisplayName("🔴 引擎校验式一致：854×480 → 10（11 会抛），1024 → 11，1×1 → 1")
    void levelCountMatchesEngineLimit() {
        assertEquals(10, ColortexPool.levelCount(854, 480));
        assertEquals(11, ColortexPool.levelCount(1024, 1024));
        assertEquals(11, ColortexPool.levelCount(1920, 1080));
        assertEquals(1, ColortexPool.levelCount(1, 1));
        // 非 2 的幂：floor(log2)+1
        assertEquals(12, ColortexPool.levelCount(2048, 1152));
    }
}
