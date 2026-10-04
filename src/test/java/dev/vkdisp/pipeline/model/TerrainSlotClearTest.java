package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】逐槽清屏色决策单测 / 修「诊断色泄漏进用户可见画面（绿天空）」
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库自有的 {@code dev.vkdisp.bridge.MrtTerrainPass}（被替换的旧实现）
 *    与 {@code evidence/h27b-isolated-lane-replication.md} §六（绿天空的实测定位链）。
 *    仓库内自有文档与自有代码，不受版权保护。
 *    → 能否并入本项目（MIT）：可以（测试代码不进分发 jar）
 *    → 例外条款：无
 * 1. 官方/主实现：无（纯逻辑决策类）。
 * 2. 备选：无。
 * 3. 我们的差异点（每条测试钉一个具体主张）：
 *    <ul>
 *      <li>🔴 <b>生产模式下槽 0 绝不能是纯绿</b> —— 那是绿天空的直接成因（h27b §六）。</li>
 *      <li>🔴 <b>诊断色的可区分能力必须保留</b>（槽 0 非黑）——
 *      {@code MrtPlan} 给槽 0 的指纹恰好是 0.0 = 黑，
 *      「没画」与「很暗」在截图上无法区分；不能为了「干净」把这项能力删掉。</li>
 *      <li>🔴 <b>生产模式是零值，不是「换成另一个好看的颜色」</b> ——
 *      任何非零颜色都会以「假色天空」的形式出现在用户画面里。</li>
 *    </ul>
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：测试代码不进运行时。
 */
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 逐槽清屏色决策单测（钉住「诊断色不进产品画面」与「可诊断性不丢」两条）。 */
class TerrainSlotClearTest {

    private static final float[] ZERO = {0.0F, 0.0F, 0.0F, 0.0F};

    @Test
    @DisplayName("🔴 生产模式槽 0 绝不是纯绿（绿天空的直接成因）")
    void productionSlotZeroIsNotGreen() {
        float[] slot0 = TerrainSlotClear.production().rgba(0);
        assertArrayEquals(ZERO, slot0,
                "生产模式下槽 0 必须零值。纯绿(0,1,0) 会让天空那片保持绿 —— "
                        + "我方 pass 只画地形，那片区域从不被画进 gbuffer，"
                        + "而 composite 采 colortex0 ⇒ 绿天空进最终画面（evidence/h27b §六）");
    }

    @Test
    @DisplayName("🔴 生产模式所有槽都零值（不只槽 0）")
    void allProductionSlotsAreNeutral() {
        TerrainSlotClear clear = TerrainSlotClear.production();
        for (int slot = 0; slot < 8; slot++) {
            assertArrayEquals(ZERO, clear.rgba(slot), "槽 " + slot + " 在生产模式下必须零值");
        }
        assertTrue(clear.isNeutralClear());
    }

    @Test
    @DisplayName("🔴 诊断模式保留高对比色（可区分「没画」与「很暗」的能力不许丢）")
    void diagnosticModeKeepsHighContrastColors() {
        TerrainSlotClear clear = TerrainSlotClear.diagnostic();
        assertArrayEquals(new float[] {0.0F, 1.0F, 0.0F, 1.0F}, clear.rgba(0),
                "槽 0 必须是非黑且不代表语义的颜色 —— MrtPlan 给它的指纹恰好是 0.0(黑)，"
                        + "若改成黑，「一个片元都没出」与「画了但很暗」在截图上无法区分");
        assertArrayEquals(new float[] {0.0F, 0.0F, 1.0F, 1.0F}, clear.rgba(1));
        assertArrayEquals(new float[] {1.0F, 0.0F, 1.0F, 1.0F}, clear.rgba(2));
        assertFalse(clear.isNeutralClear());
    }

    @Test
    @DisplayName("🔴 越界槽按 >=2 处理（与旧实现同口径，不抛）")
    void outOfRangeSlotFallsBack() {
        TerrainSlotClear clear = TerrainSlotClear.diagnostic();
        assertArrayEquals(clear.rgba(2), clear.rgba(7),
                "槽下标越界按「>=2」处理，与旧实现一致；不抛异常（建 pass 时抛会砸帧）");
        assertArrayEquals(clear.rgba(2), clear.rgba(-1));
    }

    @Test
    @DisplayName("🔴 两种模式的槽 0 明确不同（防止两模式被写成同一个值）")
    void modesDifferOnSlotZero() {
        float[] diagnostic = TerrainSlotClear.diagnostic().rgba(0);
        float[] production = TerrainSlotClear.production().rgba(0);
        org.junit.jupiter.api.Assertions.assertNotEquals(
                java.util.Arrays.toString(diagnostic), java.util.Arrays.toString(production));
    }

    @Test
    @DisplayName("🔴 生产模式说明文案把「黑天空」标成预期（避免取证者误读成故障）")
    void productionExplanationMarksBlackSkyAsExpected() {
        String text = TerrainSlotClear.production().explainOnce(3);
        assertTrue(text.contains("NEUTRAL"), text);
        assertTrue(text.contains("预期"), "必须明说「黑是预期，不是故障」—— 否则会被当成新 bug 去查: " + text);
        assertTrue(text.contains("h27b"), "应指向实测证据出处: " + text);
    }

    @Test
    @DisplayName("诊断模式说明文案强调「不代表渲染语义」")
    void diagnosticExplanationWarnsSemantics() {
        String text = TerrainSlotClear.diagnostic().explainOnce(3);
        assertTrue(text.contains("DIAGNOSTIC"), text);
        assertTrue(text.contains("不代表任何渲染语义"), text);
    }

    @Test
    @DisplayName("两种模式的色都不承载渲染语义（显式判据，供调用点断言）")
    void noRenderSemanticsInEitherMode() {
        assertTrue(TerrainSlotClear.production().carriesNoRenderSemantics());
        assertTrue(TerrainSlotClear.diagnostic().carriesNoRenderSemantics());
    }

    @Test
    @DisplayName("format 输出可读（证据行 / 单测比对用）")
    void formatIsReadable() {
        assertEquals("NEUTRAL[0]=(0.0,0.0,0.0,0.0)", TerrainSlotClear.production().format(0));
        assertTrue(TerrainSlotClear.diagnostic().format(1).startsWith("DIAGNOSTIC[1]="));
    }

    @Test
    @DisplayName("of(mode) 与便捷构造等价")
    void factoriesAgree() {
        for (TerrainSlotClear.Mode mode : TerrainSlotClear.Mode.values()) {
            TerrainSlotClear viaOf = TerrainSlotClear.of(mode);
            TerrainSlotClear viaFactory = mode == TerrainSlotClear.Mode.NEUTRAL
                    ? TerrainSlotClear.production()
                    : TerrainSlotClear.diagnostic();
            assertEquals(viaFactory.mode(), viaOf.mode());
            assertArrayEquals(viaFactory.rgba(0), viaOf.rgba(0));
        }
    }
}
