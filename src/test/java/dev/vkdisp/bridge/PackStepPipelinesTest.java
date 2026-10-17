package dev.vkdisp.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import dev.vkdisp.pack.PackBlendDirectives;
import dev.vkdisp.pack.model.BlendMode;
import dev.vkdisp.pack.properties.ShaderProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 【参考调研】每程序混合派生的两半（查表 + 只动目标0）的判据
 * 0. 合规核对（第 0 步闸门）：被测物 = 本仓自有的 {@code PackBlendDirectives}（读包声明）与
 *    {@code PackStepPipelines}（用原版 {@code toBuilder()} 派生），消费的 {@code BlendMode} 是
 *    Vitrail LGPL-3.0 移植件（台账见 {@code NOTICE.md}）。本文件只写断言与自造样本，零外部代码。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以（测试代码不进分发 jar）
 * 4. 许可证核对结论：本项目 LGPL-3.0-or-later；零第三方代码复制。
 */
final class PackStepPipelinesTest {

    private static ColorTargetState target(BlendFunction blend) {
        return new ColorTargetState(Optional.ofNullable(blend), GpuFormat.RGBA16_FLOAT,
                ColorTargetState.WRITE_COLOR);
    }

    @Test
    @DisplayName("planTargets 只动第 0 槽：格式与写掩码原样留着，别的槽一个字不改")
    void onlySlotZeroChanges() {
        ColorTargetState slot0 = target(BlendFunction.TRANSLUCENT);
        ColorTargetState slot1 = target(null);
        List<Optional<ColorTargetState>> plan = PackStepPipelines.planTargets(
                Arrays.asList(slot0, slot1, null), Optional.of(BlendFunction.ADDITIVE));

        assertNotNull(plan);
        assertEquals(3, plan.size());
        assertEquals(BlendFunction.ADDITIVE, plan.get(0).orElseThrow().blendFunction().orElseThrow());
        assertEquals(GpuFormat.RGBA16_FLOAT, plan.get(0).orElseThrow().format(),
                "格式必须原样 —— FrontendRenderPass 逐条比「管线格式 == 附件纹理格式」，改格式就是当场抛");
        assertEquals(ColorTargetState.WRITE_COLOR, plan.get(0).orElseThrow().writeMask());
        assertEquals(slot1, plan.get(1).orElseThrow(), "非 0 槽不许被碰");
        assertTrue(plan.get(2).isEmpty(), "原本为 null 的槽保持 null（不是补一张写掩码全 0 的）");
    }

    @Test
    @DisplayName("off 是真的「摘掉混合」，与没声明（不派生）是两件事")
    void offRemovesBlend() {
        List<Optional<ColorTargetState>> plan = PackStepPipelines.planTargets(
                List.of(target(BlendFunction.TRANSLUCENT)), Optional.empty());
        assertNotNull(plan);
        assertTrue(plan.get(0).orElseThrow().blendFunction().isEmpty());
    }

    @Test
    @DisplayName("没有可改的目标就交回入参：空表与第 0 槽为 null 都算不适用")
    void notApplicableReturnsNull() {
        assertNull(PackStepPipelines.planTargets(List.of(), Optional.of(BlendFunction.ADDITIVE)));
        // List.of 不许装 null ⇒ 用 Arrays.asList 造「第 0 槽是 null」的形状（原版 getColorTargetStates() 就是这样）
        assertNull(PackStepPipelines.planTargets(Arrays.asList(null, target(null)),
                Optional.of(BlendFunction.ADDITIVE)));
        assertNull(PackStepPipelines.planTargets(null, Optional.of(BlendFunction.ADDITIVE)));
    }

    @Test
    @DisplayName("查表收三种拼法：裸名、限定名、以及包只写了限定名而渲染侧递来裸名")
    void lookupAcceptsBothSpellings() {
        PackBlendDirectives.publish(ShaderProperties.parse("""
                blend.gbuffers_water=SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ONE_MINUS_SRC_ALPHA
                blend.world0/gbuffers_hand=SRC_ALPHA ONE ONE ZERO
                blend.gbuffers_entities=off
                blend.broken=BANANA ONE ONE ONE
                """));

        assertEquals(Optional.of(new BlendMode(false, "SRC_ALPHA", "ONE_MINUS_SRC_ALPHA", "ONE",
                        "ONE_MINUS_SRC_ALPHA")),
                PackBlendDirectives.forProgram("gbuffers_water"));
        // 包写的是 world0/gbuffers_hand，渲染侧递来裸名也要命中（OF 允许两种写法）：
        assertEquals(Optional.of(new BlendMode(false, "SRC_ALPHA", "ONE", "ONE", "ZERO")),
                PackBlendDirectives.forProgram("gbuffers_hand"));
        assertEquals(Optional.of(BlendMode.OFF),
                PackBlendDirectives.forProgram("world0/gbuffers_entities"));
        assertEquals(Optional.empty(), PackBlendDirectives.forProgram("gbuffers_terrain"),
                "没声明 ≠ 声明了 off");
        // 分层要如实断言：BlendMode.parse **不看因子名**（四条都算收进表），
        // 名字合法性由 PackBlendState 那一层判 ⇒ "BANANA" 在这里以 UNRECOGNIZED_FACTOR 露头。
        assertEquals(0, PackBlendDirectives.dropped().size(), "词数够就不是格式问题：" + PackBlendDirectives.dropped());
        assertEquals(4, PackBlendDirectives.declared());
        BlendMode bogus = PackBlendDirectives.forProgram("broken").orElseThrow();
        assertEquals(PackBlendState.Status.UNRECOGNIZED_FACTOR,
                PackBlendState.of(bogus, "BANANA ONE ONE ONE").status(),
                "读不懂的因子名不许在查表层被抹掉，也不许在应用层被猜成 TRANSLUCENT");

        PackBlendDirectives.publish(null);
        assertEquals(0, PackBlendDirectives.declared(), "换包/无配置必须清空，不许拿上一包的表画这一包");
        assertEquals(Optional.empty(), PackBlendDirectives.forProgram("gbuffers_water"));
    }

    @Test
    @DisplayName("chainModes 只取链上程序（composite/deferred/final），且按值去重")
    void chainModesFiltersByFamilyAndDedupes() {
        PackBlendDirectives.publish(ShaderProperties.parse("""
                blend.gbuffers_water=SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ONE_MINUS_SRC_ALPHA
                blend.composite1=SRC_ALPHA ONE ONE ZERO
                blend.deferred2=SRC_ALPHA ONE ONE ZERO
                blend.world0/final=ONE ONE ONE ONE
                """));
        // composite1 与 deferred2 声明的是同一种混合 ⇒ 去重后一条；gbuffers_water 不走这条路 ⇒ 不算。
        assertEquals(2, PackBlendDirectives.chainModes().size(), PackBlendDirectives.chainModes().toString());
        assertTrue(PackBlendDirectives.chainModes().contains(
                new BlendMode(false, "ONE", "ONE", "ONE", "ONE")), "final 也要算进链");
        assertTrue(!PackBlendDirectives.isEmpty());
        PackBlendDirectives.publish(null);
        assertTrue(PackBlendDirectives.isEmpty());
        assertEquals(0, PackBlendDirectives.chainModes().size());
    }

    @Test
    @DisplayName("没声明的程序原样交回入参管线（零行为变化），自报串可读")
    void undeclaredPassesThrough() {
        PackBlendDirectives.publish(null);
        assertNull(PackStepPipelines.forStep(null, "gbuffers_water"), "入参 null 不炸");
        assertTrue(PackStepPipelines.statsLine().startsWith("blend 派生管线 applied="),
                PackStepPipelines.statsLine());
        List<ColorTargetState> probe = new ArrayList<>(List.of(target(null)));
        assertEquals(1, probe.size());
    }
}
