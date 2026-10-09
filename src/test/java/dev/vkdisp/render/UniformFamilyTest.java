package dev.vkdisp.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GAP-022「按程序族分别供值」的<b>决策</b>那一格（纯函数，可离线测）。
 *
 * <p>钉的是两条性质：
 * ① {@code GBUFFER} 那一族<b>无视开关</b> —— 它的顶点阶段要用这个矩阵写 {@code gl_Position}，
 *    喂 GL 口径不是「效果差」而是把 clip.z 打出设备值域；
 * ② {@code CHAIN} 那一族才吃开关，且开关关时两族都留在引擎口径（= OFF 态逐字不变）。
 */
@DisplayName("GAP-022 深度/矩阵的按族供值决策")
class UniformFamilyTest {

    @Test
    @DisplayName("开关开着：只有链吃 GL 口径，gbuffers_* 仍引擎口径")
    public void onlyChainTakesGlConventionWhenSwitchOn() {
        assertTrue(OfUniformManager.glConventionFor(OfUniformManager.Family.CHAIN, true),
                "链（composite*/deferred*/final）是 passthrough 顶点 + 包片元按 GL 口径写分支 ⇒ 该翻");
        assertFalse(OfUniformManager.glConventionFor(OfUniformManager.Family.GBUFFER, true),
                "gbuffers_* 的顶点阶段逐字用 gbufferProjection 写 gl_Position ⇒ 绝不翻");
    }

    @Test
    @DisplayName("开关关着：两族都是引擎口径（OFF 态与今天逐字一致）")
    public void switchOffKeepsBothFamiliesOnEngineConvention() {
        assertFalse(OfUniformManager.glConventionFor(OfUniformManager.Family.CHAIN, false));
        assertFalse(OfUniformManager.glConventionFor(OfUniformManager.Family.GBUFFER, false));
    }

    @Test
    @DisplayName("枚举只有两族 —— 加第三族时必须显式想过它的顶点阶段用不用这个矩阵")
    public void familySetIsExactlyTwo() {
        OfUniformManager.Family[] values = OfUniformManager.Family.values();
        assertTrue(values.length == 2, "族数变了：" + java.util.Arrays.toString(values));
    }
}
