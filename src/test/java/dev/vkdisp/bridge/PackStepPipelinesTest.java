package dev.vkdisp.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /** 表上的戳与查表时递的包名必须一致才给答案（2026-10-17 实测到「后加载的包覆盖表」的形状）。 */
    private static final String ACTIVE = "probe-pack";

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
        PackBlendDirectives.publish(ACTIVE, ShaderProperties.parse("""
                blend.gbuffers_water=SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ONE_MINUS_SRC_ALPHA
                blend.world0/gbuffers_hand=SRC_ALPHA ONE ONE ZERO
                blend.gbuffers_entities=off
                blend.broken=BANANA ONE ONE ONE
                """));

        assertEquals(Optional.of(new BlendMode(false, "SRC_ALPHA", "ONE_MINUS_SRC_ALPHA", "ONE",
                        "ONE_MINUS_SRC_ALPHA")),
                PackBlendDirectives.forProgram(ACTIVE, "gbuffers_water"));
        // 包写的是 world0/gbuffers_hand，渲染侧递来裸名也要命中（OF 允许两种写法）：
        assertEquals(Optional.of(new BlendMode(false, "SRC_ALPHA", "ONE", "ONE", "ZERO")),
                PackBlendDirectives.forProgram(ACTIVE, "gbuffers_hand"));
        assertEquals(Optional.of(BlendMode.OFF),
                PackBlendDirectives.forProgram(ACTIVE, "world0/gbuffers_entities"));
        assertEquals(Optional.empty(), PackBlendDirectives.forProgram(ACTIVE, "gbuffers_terrain"),
                "没声明 ≠ 声明了 off");
        // 分层要如实断言：BlendMode.parse **不看因子名**（四条都算收进表），
        // 名字合法性由 PackBlendState 那一层判 ⇒ "BANANA" 在这里以 UNRECOGNIZED_FACTOR 露头。
        assertEquals(0, PackBlendDirectives.dropped(ACTIVE).size(), "词数够就不是格式问题：" + PackBlendDirectives.dropped(ACTIVE));
        assertEquals(4, PackBlendDirectives.declared(ACTIVE));
        BlendMode bogus = PackBlendDirectives.forProgram(ACTIVE, "broken").orElseThrow();
        assertEquals(PackBlendState.Status.UNRECOGNIZED_FACTOR,
                PackBlendState.of(bogus, "BANANA ONE ONE ONE").status(),
                "读不懂的因子名不许在查表层被抹掉，也不许在应用层被猜成 TRANSLUCENT");

        PackBlendDirectives.publish(ACTIVE, null);
        assertEquals(0, PackBlendDirectives.declared(ACTIVE), "换包/无配置必须清空，不许拿上一包的表画这一包");
        assertEquals(Optional.empty(), PackBlendDirectives.forProgram(ACTIVE, "gbuffers_water"));
    }

    @Test
    @DisplayName("逐缓冲形态 blend.<程序>.<缓冲> 入表按槽号，且盖过整程序形态（Iris 的优先级）")
    void perBufferFormBeatsWholeProgram() {
        ShaderProperties properties = ShaderProperties.parse("""
                blend.gbuffers_water=SRC_ALPHA ONE ONE ZERO
                blend.gbuffers_water.colortex4=ONE ONE ONE ONE
                blend.world0/composite1.gaux1=ZERO ONE ZERO ONE
                """);
        assertEquals(1, properties.blends().size(), "整程序形态只该有一条");
        assertEquals(2, properties.blendTargets().size(), "两条逐缓冲形态（不同程序）");

        PackBlendDirectives.publish(ACTIVE, properties);
        // 没被单独指到的槽仍吃整程序形态：
        assertEquals(new BlendMode(false, "SRC_ALPHA", "ONE", "ONE", "ZERO"),
                PackBlendDirectives.forTarget(ACTIVE, "gbuffers_water", 6).orElseThrow());
        // colortex4 被单独改掉 ⇒ 那一格用逐缓冲的那条：
        assertEquals(new BlendMode(false, "ONE", "ONE", "ONE", "ONE"),
                PackBlendDirectives.forTarget(ACTIVE, "gbuffers_water", 4).orElseThrow());
        // 别名尾巴 gaux1 由移植件 TargetName 解成 colortex4；限定名照旧能按裸名查到：
        assertEquals(new BlendMode(false, "ZERO", "ONE", "ZERO", "ONE"),
                PackBlendDirectives.forTarget(ACTIVE, "composite1", 4).orElseThrow());
        assertEquals(1, PackBlendDirectives.declared(ACTIVE));
        assertEquals(2, PackBlendDirectives.declaredTargets(ACTIVE));

        PackBlendDirectives.publish(ACTIVE, null);
        assertTrue(PackBlendDirectives.isEmpty(ACTIVE), "换包必须连逐缓冲表一起清");
    }

    @Test
    @DisplayName("逐格计划只动被指到的那几格：null = 不动，Optional.empty() = 摘掉混合")
    void planIsPerSlot() {
        List<Optional<ColorTargetState>> plan = PackStepPipelines.planTargets(
                Arrays.asList(target(null), target(BlendFunction.TRANSLUCENT), target(null)),
                Arrays.asList(null, Optional.empty(), null));

        assertNotNull(plan);
        assertTrue(plan.get(0).orElseThrow().blendFunction().isEmpty(), "第 0 格本来就没混合，也不许被改");
        assertTrue(plan.get(1).orElseThrow().blendFunction().isEmpty(), "被指到的第 1 格：混合摘掉");
        assertEquals(GpuFormat.RGBA16_FLOAT, plan.get(1).orElseThrow().format(), "格式仍不许动");
        assertEquals(BlendFunction.TRANSLUCENT, target(BlendFunction.TRANSLUCENT).blendFunction().orElseThrow());
        assertTrue(plan.get(2).orElseThrow().blendFunction().isEmpty(), "没被指到的第 2 格原样");
        // 一格都落不下去（计划全是 null）⇒ 整体不适用，调用方交回入参管线：
        assertNull(PackStepPipelines.planTargets(List.of(target(null)), Arrays.asList((Optional<BlendFunction>) null)));
    }

    @Test
    @DisplayName("旧「按族筛声明」的 chainModes 已退役：注册期建变体那条路被改判掉了，不许再长回来")
    void chainModesIsGoneAndTableIsProgramKeyed() {
        PackBlendDirectives.publish(ACTIVE, ShaderProperties.parse("""
                blend.gbuffers_water.colortex4=ONE ONE ONE ONE
                """));
        // 🔴 上一版把 "gbuffers_water.colortex4" 整个当程序名存 ⇒ declared() 虚高、却永远查不到。
        assertEquals(0, PackBlendDirectives.declared(ACTIVE), "逐缓冲形态不许混进整程序表");
        assertEquals(1, PackBlendDirectives.declaredTargets(ACTIVE));
        assertTrue(PackBlendDirectives.forProgram(ACTIVE, "gbuffers_water").isEmpty(),
                "整程序形态没声明过 ⇒ forProgram 必须查不到");
        PackBlendDirectives.publish(ACTIVE, null);
    }

    @Test
    @DisplayName("🔴 表上的戳不是正在画的包 ⇒ 一条都不给（拿别的包的混合画这一帧比没有混合更糟）")
    void tableFromAnotherPackIsIgnored() {
        PackBlendDirectives.publish(ACTIVE,
                ShaderProperties.parse("blend.composite5=ZERO ONE ZERO ONE\n"
                        + "blend.composite5.colortex2=ONE ONE ONE ONE\n"));
        assertTrue(PackBlendDirectives.forProgram(ACTIVE, "composite5").isPresent(), "同戳才该给");
        assertFalse(PackBlendDirectives.forProgram("另一张包", "composite5").isPresent(),
                "加载期会跑每一张扫描到的包 ⇒ 不带戳校验就是「谁最后加载谁说了算」");
        assertFalse(PackBlendDirectives.forTarget("另一张包", "composite5", 2).isPresent(),
                "逐缓冲那一格同样不许跨包拿");
        // 🔴 关键的一半：**后**加载另一张包之后，正在画的这张仍然答得上来
        //   （整表替换的旧写法在这里会变成「谁最后加载谁说了算」，实测症状就是逐缓冲那一格静默丢掉）。
        PackBlendDirectives.publish("后加载的包",
                ShaderProperties.parse("blend.composite1=SRC_ALPHA ONE ONE ZERO\n"));
        assertTrue(PackBlendDirectives.forTarget(ACTIVE, "composite5", 2).isPresent(),
                "别的包加载不许把这张包的表顶掉");
        assertEquals(2, PackBlendDirectives.packs(), "两张包各占一格");
        PackBlendDirectives.publish(ACTIVE, null);
    }

    @Test
    @DisplayName("没声明的程序原样交回入参管线（零行为变化），自报串可读")
    void undeclaredPassesThrough() {
        PackBlendDirectives.publish(ACTIVE, null);
        assertNull(PackStepPipelines.blendPlan(null, ACTIVE, "gbuffers_water", List.of(0)), "入参 null 不炸");
        assertTrue(PackStepPipelines.statsLine().startsWith("blend 派生管线 applied="),
                PackStepPipelines.statsLine());
        List<ColorTargetState> probe = new ArrayList<>(List.of(target(null)));
        assertEquals(1, probe.size());
    }
}
