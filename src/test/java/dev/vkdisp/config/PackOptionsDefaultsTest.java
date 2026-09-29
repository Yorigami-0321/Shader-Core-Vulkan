package dev.vkdisp.config;
/**
 * 【参考调研】F 线单测 / 选项默认值与缺省（18-PARALLEL §4 F 线完成标准第一条）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/18-PARALLEL.md §4 F 线（"选项默认值正确"）与 §7.3（并行线证据：单测全绿 +
 *    边界用例清单）；② docs/04-SPEC.md §3.1 / §3.5；③ docs/07-CONSTRAINTS.md T11（降级不静默）；
 *    ④ F2 冻结契约 dev.vkdisp.pack.Option（defaultValue 为空串 = 解析层未取到默认值）。
 *    许可证：本仓库自有文档与自有类型（MIT）→ 可直接消费；JUnit 5 = EPL-2.0（仅测试期依赖，不进分发 jar）。
 *    第三方源码零接触（07-CONSTRAINTS L12）。
 *    → 能否并入本项目（MIT）：可以（本文件为独立编写的测试）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：JUnit 5 标准断言（assertEquals / assertTrue / assertFalse）。
 * 2. 备选：手写 main() 断言 —— 否决（F4 已接好 JUnit 5，失败必须在 ./gradlew test 里显式红）。
 * 3. 我们的差异点：把"默认值正确"拆成可逐条断言的用例：正常默认值 / 大小写归一 / 缺省推导（有允许值 /
 *    无允许值）/ 非法默认值回退 / 越界默认值钳制 / 重复声明 / null 条目 / 空选项集 / 未识别允许值。
 * 4. 许可证核对：本项目 MIT；JUnit EPL-2.0 仅测试期依赖；零第三方代码复制。
 * 5. 性能基线：测试代码不进运行时，无性能影响（18-PARALLEL §7.7）。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.vkdisp.pack.Option;

/** 默认值 / 缺省 / 空集 / 重复声明 的验收。 */
class PackOptionsDefaultsTest {

    @Test
    void defaultValueFromF2OptionIsUsedVerbatim() {
        PackOptions options = PackOptions.of(List.of(
                OptionFixtures.integer("SHADOW_QUALITY", "2", "0", "1", "2"),
                OptionFixtures.bool("SHADOWS", "true"),
                OptionFixtures.text("WATER_STYLE", "fancy", "fast", "fancy")));

        assertEquals(3, options.size());
        assertEquals("2", options.value("SHADOW_QUALITY"));
        assertEquals("true", options.value("SHADOWS"));
        assertEquals("fancy", options.value("WATER_STYLE"));
    }

    @Test
    void validDefaultsProduceNoDiagnostics() {
        List<String> sinkLog = new ArrayList<>();
        PackOptions options = PackOptions.of(List.of(
                OptionFixtures.integer("SHADOW_QUALITY", "2", "0", "1", "2"),
                OptionFixtures.bool("SHADOWS", "true")),
                OptionDiagnosticSink.collectingLines(sinkLog));

        assertTrue(options.diagnostics().isEmpty(), options.diagnostics().toString());
        assertTrue(sinkLog.isEmpty(), sinkLog.toString());
    }

    @Test
    void booleanDefaultIsNormalisedToDeclaredVocabulary() {
        PackOptions options = PackOptions.of(List.of(OptionFixtures.bool("SHADOWS", "TRUE")));

        assertEquals("true", options.value("SHADOWS"), "大小写归一到允许值原文");
        assertTrue(options.diagnostics().isEmpty());
    }

    @Test
    void missingDefaultWithAllowedValuesDerivesFirstAllowedAndWarns() {
        List<String> sinkLog = new ArrayList<>();
        PackOptions options = PackOptions.of(
                List.of(OptionFixtures.integer("SSAO", "", "0", "1", "2")),
                OptionDiagnosticSink.collectingLines(sinkLog));

        assertEquals("0", options.value("SSAO"));
        assertTrue(options.hasDiagnostic("DEFAULT_MISSING"), options.diagnostics().toString());
        assertEquals(1, sinkLog.size(), sinkLog.toString());
        assertTrue(sinkLog.get(0).startsWith("[WARN] DEFAULT_MISSING:"), sinkLog.get(0));
    }

    @Test
    void missingDefaultWithoutAllowedValuesDerivesTypeZero() {
        PackOptions options = PackOptions.of(List.of(
                OptionFixtures.integer("A", ""),
                OptionFixtures.floating("B", ""),
                OptionFixtures.bool("C", ""),
                OptionFixtures.text("D", "")));

        assertEquals("0", options.value("A"));
        assertEquals("0.0", options.value("B"));
        assertEquals("false", options.value("C"));
        assertEquals("", options.value("D"));
        assertEquals(4, options.diagnostics().size(), options.diagnostics().toString());
        for (OptionDiagnostic diagnostic : options.diagnostics()) {
            assertEquals("DEFAULT_MISSING", diagnostic.code());
        }
    }

    @Test
    void missingBooleanDefaultDerivesTheOffTokenOfDeclaredVocabulary() {
        PackOptions options = PackOptions.of(List.of(
                OptionFixtures.bool("A", ""),
                OptionFixtures.bool("B", "", "on", "off")));

        assertEquals("false", options.value("A"), "允许值 true/false → 取表示关的那一项");
        assertEquals("off", options.value("B"), "自定义布尔词表 → 取表示关的那一项");
        assertEquals(2, options.diagnostics().size(), options.diagnostics().toString());
    }

    @Test
    void emptyOptionListIsLegalAndSilent() {
        List<String> sinkLog = new ArrayList<>();
        PackOptions options = PackOptions.of(List.of(), OptionDiagnosticSink.collectingLines(sinkLog));

        assertEquals(0, options.size());
        assertTrue(options.values().isEmpty());
        assertTrue(options.definitions().isEmpty());
        assertTrue(options.diagnostics().isEmpty());
        assertTrue(sinkLog.isEmpty());
    }

    @Test
    void shaderPackWithoutOptionsGivesEmptyContainer() {
        PackOptions options = PackOptions.of(OptionFixtures.pack());

        assertEquals(0, options.size());
        assertTrue(options.values().isEmpty());
    }

    @Test
    void invalidDefaultFallsBackToDerivedAndWarnsTwice() {
        PackOptions options = PackOptions.of(List.of(OptionFixtures.integer("BAD", "abc")));

        assertEquals("0", options.value("BAD"));
        assertTrue(options.hasDiagnostic("INVALID_VALUE"), options.diagnostics().toString());
        assertTrue(options.hasDiagnostic("INVALID_DEFAULT_VALUE"), options.diagnostics().toString());
    }

    @Test
    void outOfRangeDefaultIsClampedAndWarned() {
        PackOptions options = PackOptions.of(List.of(OptionFixtures.integer("SHADOW_QUALITY", "7", "0", "1", "2")));

        assertEquals("2", options.value("SHADOW_QUALITY"));
        assertTrue(options.hasDiagnostic("OUT_OF_RANGE_CLAMPED"), options.diagnostics().toString());
    }

    @Test
    void duplicateOptionKeepsFirstDeclarationAndWarns() {
        PackOptions options = PackOptions.of(List.of(
                OptionFixtures.integer("SHADOW_QUALITY", "1", "0", "1", "2"),
                OptionFixtures.integer("SHADOW_QUALITY", "2", "0", "1", "2")));

        assertEquals(1, options.size());
        assertEquals("1", options.value("SHADOW_QUALITY"), "首个声明胜出");
        assertTrue(options.hasDiagnostic("DUPLICATE_OPTION"), options.diagnostics().toString());
    }

    @Test
    void nullOptionEntryIsWarnedAndSkipped() {
        List<Option> definitions = new ArrayList<>();
        definitions.add(OptionFixtures.integer("SSAO", "1", "0", "1"));
        definitions.add(null);

        PackOptions options = PackOptions.of(definitions);

        assertEquals(1, options.size());
        assertTrue(options.hasDiagnostic("NULL_OPTION"), options.diagnostics().toString());
    }

    @Test
    void unparseableAllowedValueIsWarnedAndExcludedFromClamping() {
        PackOptions options = PackOptions.of(List.of(OptionFixtures.integer("Q", "0", "0", "abc", "2")));

        assertTrue(options.hasDiagnostic("OPTION_VALUE_UNPARSEABLE"), options.diagnostics().toString());
        PackOptions.SetOutcome outcome = options.set("Q", "9");
        assertEquals(PackOptions.SetStatus.CLAMPED, outcome.status());
        assertEquals("2", outcome.appliedValue(), "只剩可解析的 0 与 2，越界钳到最大者");
    }

    @Test
    void definitionsExposeF2OptionsUnchanged() {
        Option ssao = OptionFixtures.slider("SSAO", "1", "0", "1", "2");
        PackOptions options = PackOptions.of(List.of(ssao));

        assertEquals(List.of(ssao), options.definitions(), "F2 的 Option 对象原样消费，不改字段");
        assertEquals(ssao, options.definition("SSAO"));
        assertTrue(ssao.slider(), "slider 标记由 F2 承载，本线只读");
    }
}
