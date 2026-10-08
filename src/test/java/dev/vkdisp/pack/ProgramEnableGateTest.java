package dev.vkdisp.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vkdisp.pack.ProgramEnableGate.Result;
import dev.vkdisp.pack.ProgramEnableGate.Verdict;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GAP-024 的门控求值：用 BSL v10.1.8 里<b>真实出现过</b>的 24 条
 * {@code program.*.enabled} 表达式的形态来测（我从包里逐行抄的，见下表），
 * 不测我自己发明的语法。
 */
class ProgramEnableGateTest {

    /** 选项值表：名字 → 值。缺席 = 我方不掌握这个名字（必须判 UNKNOWN，不许猜）。 */
    private static java.util.function.Function<String, Optional<String>> resolver(Map<String, String> values) {
        return name -> Optional.ofNullable(values.get(name));
    }

    @Test
    @DisplayName("BSL 默认选项态下：AO / LIGHT_SHAFT 开着 ⇒ deferred 与 composite1 进链")
    void bslDefaultsKeepEnabledPasses() {
        var defaults = Map.of(
                "AO", "true", "LIGHT_SHAFT", "true", "SHADOW", "true",
                "FXAA", "true", "TAA", "true", "RETRO_FILTER", "false",
                "MOTION_BLUR", "false", "DOF", "false", "MULTICOLORED_BLOCKLIGHT", "false");
        assertEquals(Verdict.TRUE, ProgramEnableGate.evaluate("AO", resolver(defaults)).verdict());
        assertEquals(Verdict.TRUE,
                ProgramEnableGate.evaluate("FXAA && !RETRO_FILTER", resolver(defaults)).verdict());
        assertEquals(Verdict.TRUE,
                ProgramEnableGate.evaluate("TAA && !RETRO_FILTER", resolver(defaults)).verdict());
        assertEquals(Verdict.FALSE, ProgramEnableGate.evaluate("MOTION_BLUR", resolver(defaults)).verdict());
        assertEquals(Verdict.FALSE, ProgramEnableGate.evaluate("DOF", resolver(defaults)).verdict());
    }

    @Test
    @DisplayName("带维度的复合式：SHADOW && MULTICOLORED_BLOCKLIGHT（world-1 那条）")
    void conjunctiveExpressionFollowsBothOperands() {
        var on = Map.of("SHADOW", "true", "MULTICOLORED_BLOCKLIGHT", "true");
        var off = Map.of("SHADOW", "true", "MULTICOLORED_BLOCKLIGHT", "false");
        assertEquals(Verdict.TRUE, ProgramEnableGate.evaluate("SHADOW && MULTICOLORED_BLOCKLIGHT", resolver(on)).verdict());
        assertEquals(Verdict.FALSE, ProgramEnableGate.evaluate("SHADOW && MULTICOLORED_BLOCKLIGHT", resolver(off)).verdict());
    }

    @Test
    @DisplayName("数字型选项：0 = 假、非 0 = 真（CLOUDS 这类带 [0 1 2 3] 的候选值）")
    void numericValuesAreTruthinessNotUnknown() {
        assertEquals(Verdict.FALSE, ProgramEnableGate.evaluate("CLOUDS", resolver(Map.of("CLOUDS", "0"))).verdict());
        assertEquals(Verdict.TRUE, ProgramEnableGate.evaluate("CLOUDS", resolver(Map.of("CLOUDS", "2"))).verdict());
    }

    @Test
    @DisplayName("🔴 认不出的名字 ⇒ UNKNOWN，并且 keepPass()=true（看不懂就不少画一级）")
    void unresolvedNameKeepsThePassAndReportsItself() {
        Result r = ProgramEnableGate.evaluate("SOME_FANCY_TOGGLE", resolver(Map.of("AO", "true")));
        assertEquals(Verdict.UNKNOWN, r.verdict());
        assertTrue(r.keepPass(), "UNKNOWN 必须保守地保留这一级");
        assertEquals(java.util.List.of("SOME_FANCY_TOGGLE"), r.unresolvedNames(),
                "没认出来的名字必须交出去，供调用方自报（X11 禁静默）");
    }

    @Test
    @DisplayName("🔴 不支持的记号（比较 / 函数）⇒ UNKNOWN 并带原因，不猜值")
    void unsupportedSyntaxIsUnknownNotGuessed() {
        Result cmp = ProgramEnableGate.evaluate("CLOUDS == 2", resolver(Map.of("CLOUDS", "2")));
        assertEquals(Verdict.UNKNOWN, cmp.verdict());
        assertTrue(cmp.unresolvedNames().toString().contains("语法"),
                "语法不认必须说清是没认出来，而不是默默当成假：" + cmp.unresolvedNames());
        Result bad = ProgramEnableGate.evaluate("!(", resolver(Map.of()));
        assertEquals(Verdict.UNKNOWN, bad.verdict(), "括号不配对也不能抛出去");
    }

    @Test
    @DisplayName("三值逻辑：FALSE 在 && 里短路掉 UNKNOWN；TRUE 在 || 里短路掉 UNKNOWN")
    void kleeneLogicShortCircuitsCorrectly() {
        assertEquals(Verdict.FALSE, ProgramEnableGate.and(Verdict.UNKNOWN, Verdict.FALSE));
        assertEquals(Verdict.UNKNOWN, ProgramEnableGate.and(Verdict.UNKNOWN, Verdict.TRUE));
        assertEquals(Verdict.TRUE, ProgramEnableGate.or(Verdict.UNKNOWN, Verdict.TRUE));
        assertEquals(Verdict.UNKNOWN, ProgramEnableGate.or(Verdict.UNKNOWN, Verdict.FALSE));
        assertEquals(Verdict.UNKNOWN, ProgramEnableGate.not(Verdict.UNKNOWN),
                "!UNKNOWN 仍是 UNKNOWN（若这里变 TRUE/FALSE 就是在猜）");
    }

    @Test
    @DisplayName("空表达式 = 没有开关 = 恒启用")
    void blankExpressionMeansAlwaysOn() {
        assertEquals(Verdict.TRUE, ProgramEnableGate.evaluate(null, resolver(Map.of())).verdict());
        assertEquals(Verdict.TRUE, ProgramEnableGate.evaluate("   ", resolver(Map.of())).verdict());
        assertFalse(ProgramEnableGate.evaluate("AO", resolver(Map.of("AO", "false"))).keepPass());
    }

    @Test
    @DisplayName("括号与 || 的优先级（BSL 目前没用，但我方支持了就得对）")
    void parenthesesAndOrBinding() {
        var values = Map.of("A", "true", "B", "false", "C", "false");
        java.util.function.Function<String, Optional<String>> r = resolver(values);
        assertEquals(Verdict.FALSE, ProgramEnableGate.evaluate("A && (B || C)", r).verdict());
        assertEquals(Verdict.TRUE, ProgramEnableGate.evaluate("(A || B) && !C", r).verdict());
        assertEquals(Verdict.TRUE, ProgramEnableGate.evaluate("A || B && C", r).verdict());
    }
}
