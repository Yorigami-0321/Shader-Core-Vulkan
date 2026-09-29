package dev.vkdisp.config;
/**
 * 【参考调研】F 线单测 / 赋值、钳制与非法值（18-PARALLEL §4 F 线：越界钳制 + WARN；§7.3：边界用例）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/18-PARALLEL.md §4 F 线（"值越界能钳制并打 WARN（T11）"）与 §7.3
 *    （并行线证据：边界用例 = 越界 / 非法值 / 缺省）；② docs/04-SPEC.md §3.1 / §3.5；③ docs/07-CONSTRAINTS.md T11；
 *    ④ F2 冻结契约 dev.vkdisp.pack.Option / OptionType。
 *    许可证：本仓库自有文档与自有类型（MIT）→ 可直接消费；JUnit 5 = EPL-2.0（仅测试期依赖）。
 *    第三方源码零接触（07-CONSTRAINTS L12）。
 *    → 能否并入本项目（MIT）：可以（本文件为独立编写的测试）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：JUnit 5 标准断言（assertEquals / assertThrows / assertTrue / assertFalse）。
 * 2. 备选：只测"正常赋值" —— 否决（T11 的证据点恰恰在越界 / 非法值 / 空值这些降级路径上）。
 * 3. 我们的差异点：非法值用例覆盖了三类"看起来像数字但其实不是"的陷阱：GLSL 的 "1f" 后缀、
 *    "0x1p3" 十六进制浮点、"NaN"/"Infinity"；越界用例覆盖允许值列表两端与 32 位整数边界。
 * 4. 许可证核对：本项目 MIT；JUnit EPL-2.0 仅测试期依赖；零第三方代码复制。
 * 5. 性能基线：测试代码不进运行时，无性能影响（18-PARALLEL §7.7）。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 赋值语义：采纳 / 钳制 / 拒绝 / 重置 / 快照隔离。 */
class PackOptionsMutationTest {

    @Test
    void allowedValueIsAcceptedVerbatim() {
        PackOptions options = PackOptions.of(List.of(OptionFixtures.integer("Q", "0", "0", "1", "2")));

        PackOptions.SetOutcome outcome = options.set("Q", "1");

        assertEquals(PackOptions.SetStatus.ACCEPTED, outcome.status());
        assertEquals("1", outcome.appliedValue());
        assertTrue(outcome.changed());
        assertTrue(outcome.diagnostics().isEmpty());
        assertEquals("1", options.value("Q"));
    }

    @Test
    void valueAboveMaxIsClampedAndWarned() {
        List<String> sinkLog = new ArrayList<>();
        PackOptions options = PackOptions.of(List.of(OptionFixtures.integer("Q", "0", "0", "1", "2")),
                OptionDiagnosticSink.collectingLines(sinkLog));

        PackOptions.SetOutcome outcome = options.set("Q", "9");

        assertEquals(PackOptions.SetStatus.CLAMPED, outcome.status());
        assertEquals("2", outcome.appliedValue());
        assertEquals("0", outcome.previousValue());
        assertTrue(options.hasDiagnostic("OUT_OF_RANGE_CLAMPED"), options.diagnostics().toString());
        assertTrue(sinkLog.stream().anyMatch(line -> line.startsWith("[WARN] OUT_OF_RANGE_CLAMPED:")),
                sinkLog.toString());
    }

    @Test
    void valueBelowMinIsClampedAndWarned() {
        PackOptions options = PackOptions.of(List.of(OptionFixtures.integer("Q", "1", "0", "1", "2")));

        PackOptions.SetOutcome outcome = options.set("Q", "-5");

        assertEquals(PackOptions.SetStatus.CLAMPED, outcome.status());
        assertEquals("0", outcome.appliedValue());
        assertEquals("1", outcome.previousValue());
    }

    @Test
    void nonIntegerValueIsRejectedAndPreviousValueKept() {
        PackOptions options = PackOptions.of(List.of(OptionFixtures.integer("Q", "1", "0", "1", "2")));

        PackOptions.SetOutcome outcome = options.set("Q", "1.5");

        assertEquals(PackOptions.SetStatus.REJECTED, outcome.status());
        assertEquals("1", outcome.appliedValue(), "拒绝后保留旧值");
        assertFalse(outcome.changed());
        assertEquals("1", options.value("Q"));
        assertTrue(options.hasDiagnostic("INVALID_VALUE"), options.diagnostics().toString());
    }

    @Test
    void floatTrapsAreRejected() {
        PackOptions options = PackOptions.of(List.of(OptionFixtures.floating("F", "1.0")));

        for (String trap : List.of("1f", "0x1p3", "NaN", "Infinity", "1.0.0", "")) {
            PackOptions.SetOutcome outcome = options.set("F", trap);
            assertEquals(PackOptions.SetStatus.REJECTED, outcome.status(), "应拒绝: '" + trap + "'");
        }
        assertEquals("1.0", options.value("F"));
        assertTrue(options.hasDiagnostic("INVALID_VALUE"));
    }

    @Test
    void freeIntegerAcceptsAny32BitValue() {
        PackOptions options = PackOptions.of(List.of(OptionFixtures.integer("FOV", "70")));

        assertEquals("12345", options.set("FOV", "12345").appliedValue());
        assertTrue(options.diagnostics().isEmpty(), options.diagnostics().toString());
    }

    @Test
    void freeIntegerBeyond32BitIsClampedToIntBoundary() {
        PackOptions options = PackOptions.of(List.of(OptionFixtures.integer("FOV", "70")));

        PackOptions.SetOutcome tooBig = options.set("FOV", "4294967296");
        assertEquals(PackOptions.SetStatus.CLAMPED, tooBig.status());
        assertEquals(String.valueOf(Integer.MAX_VALUE), tooBig.appliedValue());

        PackOptions.SetOutcome absurd = options.set("FOV", "99999999999999999999999");
        assertEquals(PackOptions.SetStatus.CLAMPED, absurd.status());
        assertEquals(String.valueOf(Integer.MAX_VALUE), absurd.appliedValue());

        PackOptions.SetOutcome tooSmall = options.set("FOV", "-4294967296");
        assertEquals(String.valueOf(Integer.MIN_VALUE), tooSmall.appliedValue());
    }

    @Test
    void freeFloatIsNormalisedToGlslFloatText() {
        PackOptions options = PackOptions.of(List.of(OptionFixtures.floating("SUN", "0.0")));

        assertEquals("2.0", options.set("SUN", "2").appliedValue());
        assertEquals("100.0", options.set("SUN", "1e2").appliedValue());
        assertEquals("0.5", options.set("SUN", "0.50").appliedValue());
        assertEquals("-3.25", options.set("SUN", "-3.25").appliedValue());
    }

    @Test
    void discreteFloatMatchesNumericallyAndKeepsDeclaredText() {
        PackOptions options = PackOptions.of(List.of(OptionFixtures.floating("B", "1.0", "1.0", "2.0")));

        assertEquals("1.0", options.set("B", "1.00").appliedValue(), "数值相等即视为命中允许值，输出允许值原文");
        assertEquals("2.0", options.set("B", "2.5").appliedValue(), "越界钳到最大允许值");
    }

    @Test
    void unknownOptionIsRejectedAndWarned() {
        List<String> sinkLog = new ArrayList<>();
        PackOptions options = PackOptions.of(List.of(OptionFixtures.integer("Q", "0", "0")),
                OptionDiagnosticSink.collectingLines(sinkLog));

        PackOptions.SetOutcome outcome = options.set("NOPE", "1");

        assertEquals(PackOptions.SetStatus.REJECTED, outcome.status());
        assertNull(outcome.previousValue());
        assertNull(outcome.appliedValue());
        assertTrue(options.hasDiagnostic("UNKNOWN_OPTION"));
        assertTrue(sinkLog.stream().anyMatch(line -> line.startsWith("[WARN] UNKNOWN_OPTION:")), sinkLog.toString());
    }

    @Test
    void nullValueIsRejectedWithoutNpe() {
        PackOptions options = PackOptions.of(List.of(OptionFixtures.integer("Q", "0", "0")));

        PackOptions.SetOutcome outcome = options.set("Q", null);

        assertEquals(PackOptions.SetStatus.REJECTED, outcome.status());
        assertEquals("0", outcome.appliedValue());
        assertTrue(options.hasDiagnostic("NULL_VALUE"));
    }

    @Test
    void nullNameIsProgrammerError() {
        PackOptions options = PackOptions.of(List.of(OptionFixtures.integer("Q", "0", "0")));
        assertThrows(NullPointerException.class, () -> options.set(null, "1"));
    }

    @Test
    void textOptionMustStayInsideDeclaredList() {
        PackOptions options = PackOptions.of(List.of(OptionFixtures.text("WATER_STYLE", "fast", "fast", "fancy")));

        assertEquals("fancy", options.set("WATER_STYLE", "fancy").appliedValue());
        PackOptions.SetOutcome rejected = options.set("WATER_STYLE", "ultra");
        assertEquals(PackOptions.SetStatus.REJECTED, rejected.status());
        assertEquals("fancy", options.value("WATER_STYLE"), "文本没有中间态，拒绝后保留旧值");
        assertTrue(options.hasDiagnostic("VALUE_NOT_ALLOWED"));
    }

    @Test
    void freeTextAcceptsAnyNonBlankValueAndRejectsEmpty() {
        PackOptions options = PackOptions.of(List.of(OptionFixtures.text("LABEL", "abc")));

        assertEquals("hello world", options.set("LABEL", "hello world").appliedValue());
        PackOptions.SetOutcome empty = options.set("LABEL", "   ");
        assertEquals(PackOptions.SetStatus.REJECTED, empty.status());
        assertTrue(options.hasDiagnostic("INVALID_VALUE"));
    }

    @Test
    void booleanAcceptsDeclaredVocabularyCaseInsensitively() {
        PackOptions options = PackOptions.of(List.of(OptionFixtures.bool("TOGGLE", "off", "on", "off")));

        assertEquals("on", options.set("TOGGLE", "ON").appliedValue());
        PackOptions.SetOutcome rejected = options.set("TOGGLE", "junk");
        assertEquals(PackOptions.SetStatus.REJECTED, rejected.status());
        assertTrue(options.hasDiagnostic("VALUE_NOT_ALLOWED"));
    }

    @Test
    void resetRestoresEffectiveDefaultWithoutDiagnostics() {
        PackOptions options = PackOptions.of(List.of(OptionFixtures.integer("Q", "1", "0", "1", "2")));
        options.set("Q", "2");

        PackOptions.SetOutcome outcome = options.reset("Q");

        assertEquals(PackOptions.SetStatus.RESET, outcome.status());
        assertEquals("2", outcome.previousValue());
        assertEquals("1", outcome.appliedValue());
        assertTrue(outcome.changed());
        assertTrue(outcome.diagnostics().isEmpty());
        assertEquals("1", options.value("Q"));
    }

    @Test
    void resetUnknownOptionIsRejectedAndWarned() {
        PackOptions options = PackOptions.of(List.of(OptionFixtures.integer("Q", "1", "0", "1")));

        PackOptions.SetOutcome outcome = options.reset("NOPE");

        assertEquals(PackOptions.SetStatus.REJECTED, outcome.status());
        assertTrue(options.hasDiagnostic("UNKNOWN_OPTION"));
    }

    @Test
    void resetAllReportsHowManyValuesActuallyChanged() {
        PackOptions options = PackOptions.of(List.of(
                OptionFixtures.integer("A", "0", "0", "1"),
                OptionFixtures.integer("B", "0", "0", "1"),
                OptionFixtures.integer("C", "0", "0", "1")));
        options.set("A", "1");
        options.set("B", "1");

        assertEquals(2, options.resetAll());
        assertEquals("0", options.value("A"));
        assertEquals("0", options.value("B"));
    }

    @Test
    void valueSnapshotsAreUnmodifiableAndIsolated() {
        PackOptions options = PackOptions.of(List.of(OptionFixtures.integer("Q", "0", "0", "1")));
        Map<String, String> before = options.values();

        assertThrows(UnsupportedOperationException.class, () -> before.put("X", "1"));
        assertThrows(UnsupportedOperationException.class, () -> options.defaults().put("X", "1"));

        options.set("Q", "1");

        assertEquals("0", before.get("Q"), "快照是拷贝，不随容器变化");
        assertEquals("1", options.value("Q"));
    }

    @Test
    void unknownLookupsAreExplicit() {
        PackOptions options = PackOptions.of(List.of(OptionFixtures.integer("Q", "0", "0")));

        assertTrue(options.findValue("NOPE").isEmpty());
        assertTrue(options.findDefinition("NOPE").isEmpty());
        assertFalse(options.contains("NOPE"));
        assertFalse(options.hasDiagnostic("NOT_A_REAL_CODE"));
        assertThrows(IllegalArgumentException.class, () -> options.value("NOPE"));
        assertThrows(IllegalArgumentException.class, () -> options.definition("NOPE"));
    }
}
