package dev.vkdisp.pack;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GAP-024 的门控决策单测（纯逻辑，无 GPU / 无 FML）。
 *
 * <p>🔖 <b>本类守的两件事</b>：
 * ① <b>BSL 默认档恰好跳过 {@code composite2}（MOTION_BLUR）与 {@code composite3}（DOF）</b> ——
 *    这是「执行包自己写下的开关」的唯一可观察判据（跳过多了会砍功能，跳过少了就是没接线）；
 * ② <b>自报行永远存在，且 UNKNOWN 一律「保留 + 点名」</b> ——
 *    「没有这行」与「这行说什么都没跳过」必须可区分（GAP-026 同一族的教训）。
 */
class ChainEnableGatingTest {

    /** BSL v10.1.8 逐行取来的真实开关表（`shaders.properties`），默认档取值见 `settings.glsl`。 */
    private static final Map<String, String> BSL_DEFAULTS = Map.ofEntries(
            Map.entry("AO", "true"),
            Map.entry("LIGHT_SHAFT", "true"),
            Map.entry("MOTION_BLUR", "false"),
            Map.entry("DOF", "false"),
            Map.entry("FXAA", "true"),
            Map.entry("TAA", "true"),
            Map.entry("RETRO_FILTER", "false"),
            Map.entry("SHADOW", "true"),
            Map.entry("MULTICOLORED_BLOCKLIGHT", "false"));

    private static List<ChainEnableGating.Step> bslSteps() {
        return List.of(
                new ChainEnableGating.Step("deferred", "AO"),
                new ChainEnableGating.Step("composite1", "LIGHT_SHAFT"),
                new ChainEnableGating.Step("composite2", "MOTION_BLUR"),
                new ChainEnableGating.Step("composite3", "DOF"),
                new ChainEnableGating.Step("composite6", "FXAA && !RETRO_FILTER"),
                new ChainEnableGating.Step("composite7", "TAA && !RETRO_FILTER"));
    }

    @Test
    @DisplayName("🔖 BSL 默认档：恰好跳过 composite2(MOTION_BLUR) 与 composite3(DOF)，且只跳这两级")
    void bslDefaultSkipsExactlyTwoSteps() {
        ChainEnableGating.Plan plan = ChainEnableGating.plan(bslSteps(), BSL_DEFAULTS, true);

        assertEquals(List.of("composite2", "composite3"), plan.skipped(),
                "包明确关着的就是这两级；多跳 = 砍功能，少跳 = 没接线");
        assertEquals(6, plan.considered());
        assertEquals(4, plan.kept());
        assertEquals(6, plan.switches(), "BSL 这六级全都写了 enabled 表达式");
        assertTrue(plan.unresolved().isEmpty(),
                "BSL 的表达式全是我方支持的形态，不该出现知识边界，实际: " + plan.unresolved());
        assertFalse(plan.keeps("composite2"), "被跳过的一级必须报告为「不进链」");
        assertTrue(plan.keeps("composite1"), "开着的一级不许被跳");
    }

    @Test
    @DisplayName("🔖 &&/! 的三值组合要按包意图算，不是按「任一为假就跳」的粗口径")
    void compoundExpressionsFollowPackIntent() {
        // RETRO_FILTER 打开时：FXAA 虽为真，composite6 也必须被跳（包写的是 `FXAA && !RETRO_FILTER`）。
        Map<String, String> retroOn = new java.util.HashMap<>(BSL_DEFAULTS);
        retroOn.put("RETRO_FILTER", "true");
        ChainEnableGating.Plan plan = ChainEnableGating.plan(bslSteps(), retroOn, true);

        assertTrue(plan.skipped().contains("composite6"), "RETRO_FILTER 开 ⇒ composite6 该跳");
        assertTrue(plan.skipped().contains("composite7"), "同理 composite7");
        assertFalse(plan.skipped().contains("composite1"), "LIGHT_SHAFT 与 RETRO_FILTER 无关，不许连带跳");
    }

    @Test
    @DisplayName("🔴 认不出的名字 ⇒ 保留该级 + 逐条点名（知识边界必须可见，X11）")
    void unknownOptionKeepsStepButIsReported() {
        List<ChainEnableGating.Step> steps = List.of(
                new ChainEnableGating.Step("composite5", "SOME_PACK_ONLY_FLAG"),
                new ChainEnableGating.Step("composite9", "AO && WHAT_IS_THIS"));

        ChainEnableGating.Plan plan = ChainEnableGating.plan(steps, BSL_DEFAULTS, true);

        assertTrue(plan.skipped().isEmpty(), "看不懂 ⇒ 不许替包决定，一律保留，实际: " + plan.skipped());
        assertEquals(2, plan.kept());
        assertEquals(2, plan.unresolved().size(), "两条都要点名");
        assertTrue(plan.unresolved().get(0).contains("SOME_PACK_ONLY_FLAG"),
                "自报要能看出是哪个名字认不出，实际: " + plan.unresolved());
        assertTrue(plan.unresolved().get(1).contains("WHAT_IS_THIS"),
                "&& 里只认出一半时，认不出的那一半仍要报出来，实际: " + plan.unresolved());
    }

    @Test
    @DisplayName("🔴 自报行永远存在，含「一个都没跳」那一次；且必须说清本臂 gating 是开还是关")
    void reportAlwaysExistsAndStatesItsOwnMode() {
        ChainEnableGating.Plan none = ChainEnableGating.plan(
                List.of(new ChainEnableGating.Step("final", null),
                        new ChainEnableGating.Step("composite0", "  ")),
                Map.of(), true);
        String line = none.report("SomePack");
        assertTrue(line.startsWith(ChainEnableGating.REPORT_PREFIX), "取证按前缀 grep，前缀不许漂");
        assertTrue(line.contains("skipped=0"), "零跳过也要打这一行，否则「没这行」=「没跳过」不可区分");
        assertTrue(line.contains("gating=on"), line);
        assertEquals(0, none.switches(), "没写开关的步不占 switches 计数（也不该进 unresolved）");
        assertTrue(none.unresolved().isEmpty(), "包没给开关 ≠ 我方看不懂，这两件事必须分开报");

        ChainEnableGating.Plan off = ChainEnableGating.plan(bslSteps(), BSL_DEFAULTS, false);
        assertTrue(off.skipped().isEmpty(), "关掉门控 ⇒ 行为与接入前逐字一致");
        assertEquals(6, off.switches(), "OFF 臂也要报「这个包到底有几级带开关」，否则两臂不可比");
        String offLine = off.report("BSL_v10.1.8");
        assertTrue(offLine.contains("gating=off"), offLine);
        assertTrue(offLine.contains(ChainEnableGating.CONFIG_KEY),
                "关掉时必须把配置键名写进日志，让人知道是哪个开关：" + offLine);
    }

    @Test
    @DisplayName("空选项表 ⇒ 全部保留 + 全部点名（我方不掌握选项时的保守形态）")
    void emptyOptionTableKeepsEverythingAndSaysSo() {
        ChainEnableGating.Plan plan = ChainEnableGating.plan(bslSteps(), Map.of(), true);

        assertTrue(plan.skipped().isEmpty(), "没有任何值可查 ⇒ 一条都不许跳");
        assertEquals(6, plan.unresolved().size(), "六级全部要出现在知识边界清单里");
    }

    @Test
    @DisplayName("null 步直接拒绝 —— 漏一步就少一条自报，不能静默")
    void nullStepIsRejected() {
        List<ChainEnableGating.Step> withNull = new java.util.ArrayList<>();
        withNull.add(new ChainEnableGating.Step("deferred", "AO"));
        withNull.add(null);
        assertThrows(IllegalArgumentException.class,
                () -> ChainEnableGating.plan(withNull, BSL_DEFAULTS, true));
    }

    @Test
    @DisplayName("🔖 开关的两个常量不许漂；且「不在游戏进程」不许被当成「开关坏掉」")
    void switchFieldMustActuallyExistAndNoForgeEnvIsNotAFailure() {
        // h33 的教训是「配置键名」与「承载它的 Java 字段名」混用一个常量 ⇒
        // NoSuchFieldException 被吞 ⇒ 开关永远取默认值而日志看起来正常。
        assertEquals("CHAIN_ENABLE_GATING", PackChainGatingSwitch.FIELD_NAME);
        assertEquals("pack.chainEnableGating", PackChainGatingSwitch.CONFIG_KEY);
        assertFalse(PackChainGatingSwitch.CONFIG_KEY.equals(PackChainGatingSwitch.FIELD_NAME),
                "两个常量必须不同，否则就是在重演 h33");
        assertTrue(PackChainGatingSwitch.DEFAULT_ENABLED,
                "默认必须是「开」：门控执行的是包自己的声明，不门控才是违约（与 capabilityGate 相反）");

        // 🔴 本方法**不能**用反射去验 `VkDispConfig` 有没有这个字段：单测类路径里没有 NeoForge，
        //   `Class.forName("dev.vkdisp.VkDispConfig")` 直接 `NoClassDefFoundError`
        //   （实测：`ModConfigSpec$ConfigValue` 找不到）⇒ 这条判据只能在**游戏进程**里验，
        //   即下面的「无 FML 环境不得留下 reflectionFailure」+ 一次 runClient 的 [GAP-024] 自报行。
        //   字段真被改名/删掉时，`PackPostChain` 会把 ERROR 打进日志（那条由运行侧取证覆盖）。
        PackChainGatingSwitch.clearReflectionFailure();
        PackChainGatingSwitch.override(null);
        assertTrue(PackChainGatingSwitch.enabled(),
                "无 FML 环境 ⇒ 回落默认值「开」，而不是抛出去");
        assertNull(PackChainGatingSwitch.reflectionFailure(),
                "「本进程不是游戏进程」不是开关故障；留下失败位会让 PackPostChain 对着一份"
                        + "好配置打 ERROR，并漏给同一 JVM 的后续测试（假报警 + 串味）");
    }

    @Test
    @DisplayName("override 槽能把开关切成 off（A/B 取证用的单二进制内对照）")
    void overrideSlotDrivesTheSwitch() {
        PackChainGatingSwitch.override(() -> false);
        try {
            assertFalse(PackChainGatingSwitch.enabled());
            PackChainGatingSwitch.override(() -> true);
            assertTrue(PackChainGatingSwitch.enabled());
        } finally {
            PackChainGatingSwitch.override(null);
        }
        // 无 FML 环境（单测）⇒ 回落默认值「开」，而不是抛异常
        assertTrue(PackChainGatingSwitch.enabled());
    }
}
