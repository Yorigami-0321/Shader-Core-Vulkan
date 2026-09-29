package dev.vkdisp.config;
/**
 * 【参考调研】F 线单测 / 选项值 → #define 表与 uniform 值（18-PARALLEL §4 F 线："#define 表生成结果可对比"）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §3.5（OptionBinding = 选项 → 着色器 #define / uniform 的绑定）
 *    与 §3.1（GLSL 侧选项来源为 {#define NAME 值} 与 {const int NAME = 值}）；
 *    ② docs/18-PARALLEL.md §4 F 线（"#define 表生成结果可对比（单测快照式断言）"）、§7.3（并行线证据规范）；
 *    ③ docs/07-CONSTRAINTS.md T11（跳过必须显式 WARN）；④ F2 冻结契约 dev.vkdisp.pack.Option / OptionType。
 *    许可证：本仓库自有文档与自有类型（MIT）→ 可直接消费；C 预处理器的 #define / #undef 属语言事实；
 *    JUnit 5 = EPL-2.0（仅测试期依赖）。第三方源码零接触（07-CONSTRAINTS L12）。
 *    → 能否并入本项目（MIT）：可以（本文件为独立编写的测试）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：JUnit 5 标准断言；期望文本用 Java 文本块写死，逐字符比对（快照式断言）。
 * 2. 备选：断言"包含某几行" —— 已作为补充，但不足以覆盖顺序与遗漏，故主断言用整段文本相等。
 * 3. 我们的差异点：两种 DefineStyle 各有一份快照；"不能变成 #define"的四类情况（宏名非法 / 值非法 /
 *    自由文本 / 快照缺值）各有独立用例，且都要求有 WARN 与 sink 取证。
 * 4. 许可证核对：本项目 MIT；JUnit EPL-2.0 仅测试期依赖；零第三方代码复制。
 * 5. 性能基线：测试代码不进运行时，无性能影响（18-PARALLEL §7.7）。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.vkdisp.pack.Option;

/** #define 表 / uniform 值的快照断言与"跳过必须显式"的边界。 */
class OptionBindingTest {

    private static PackOptions sampleOptions(List<String> sinkLog) {
        return PackOptions.of(List.of(
                OptionFixtures.bool("SHADOWS", "true"),
                OptionFixtures.integer("SHADOW_QUALITY", "2", "0", "1", "2"),
                OptionFixtures.floating("SUN_BRIGHTNESS", "1.5"),
                OptionFixtures.text("WATER_STYLE", "fancy", "fast", "fancy")),
                OptionDiagnosticSink.collectingLines(sinkLog));
    }

    @Test
    void literalStyleDefinesSnapshotIsExact() {
        OptionBinding binding = OptionBinding.of(sampleOptions(new ArrayList<>()));

        String expected = """
                option-defines v1
                style=LITERAL
                #define SHADOWS true
                #define SHADOW_QUALITY 2
                #define SUN_BRIGHTNESS 1.5
                #define WATER_STYLE fancy
                """;
        assertEquals(expected, binding.definesText());
        assertTrue(binding.undefines().isEmpty());
        assertTrue(binding.diagnostics().isEmpty(), binding.diagnostics().toString());
    }

    @Test
    void uniformsSnapshotIsExact() {
        OptionBinding binding = OptionBinding.of(sampleOptions(new ArrayList<>()));

        String expected = """
                option-uniforms v1
                SHADOWS BOOLEAN text=true bool=true
                SHADOW_QUALITY INTEGER text=2 int=2
                SUN_BRIGHTNESS FLOAT text=1.5 float=1.5
                WATER_STYLE STRING text=fancy
                """;
        assertEquals(expected, binding.uniformsText());
    }

    @Test
    void ifdefTrueStyleRendersEmptyDefineAndUndef() {
        PackOptions options = PackOptions.of(List.of(
                OptionFixtures.bool("SHADOWS", "true"),
                OptionFixtures.integer("SHADOW_QUALITY", "2", "0", "1", "2"),
                OptionFixtures.bool("OLD_LIGHTING", "false")));
        OptionBinding binding = OptionBinding.of(options, OptionBinding.DefineStyle.IFDEF_TRUE);

        String expected = """
                option-defines v1
                style=IFDEF_TRUE
                #define SHADOWS
                #define SHADOW_QUALITY 2
                #undef OLD_LIGHTING
                """;
        assertEquals(expected, binding.definesText());
        assertEquals("", binding.defines().get("SHADOWS"), "真值形态只有宏名、没有替换文本");
        assertEquals(java.util.Set.of("OLD_LIGHTING"), binding.undefines());
        assertEquals("SHADOWS BOOLEAN text=true bool=true", binding.uniform("SHADOWS").orElseThrow().format());
    }

    @Test
    void emptyValueIsSkippedWithWarn() {
        PackOptions options = PackOptions.of(List.of(OptionFixtures.text("LABEL", "")));
        List<String> sinkLog = new ArrayList<>();

        OptionBinding binding = OptionBinding.of(options, OptionDiagnosticSink.collectingLines(sinkLog),
                OptionBinding.DefineStyle.LITERAL);

        assertTrue(binding.defines().isEmpty());
        assertTrue(binding.uniforms().isEmpty());
        assertTrue(binding.hasDiagnostic("DEFINE_SKIPPED_EMPTY_VALUE"), binding.diagnostics().toString());
        assertTrue(sinkLog.stream().anyMatch(line -> line.startsWith("[WARN] DEFINE_SKIPPED_EMPTY_VALUE:")),
                sinkLog.toString());
    }

    @Test
    void unsafeOptionNameIsSkippedWithWarn() {
        PackOptions options = PackOptions.of(List.of(OptionFixtures.text("my option", "fancy", "fast", "fancy")));

        OptionBinding binding = OptionBinding.of(options);

        assertTrue(binding.defines().isEmpty());
        assertTrue(binding.hasDiagnostic("DEFINE_SKIPPED_UNSAFE_NAME"), binding.diagnostics().toString());
    }

    @Test
    void freeTextValueIsSkippedWithWarn() {
        PackOptions options = PackOptions.of(List.of(OptionFixtures.text("LABEL", "hello world")));

        OptionBinding binding = OptionBinding.of(options);

        assertTrue(binding.defines().isEmpty());
        assertTrue(binding.hasDiagnostic("DEFINE_SKIPPED_UNSAFE_VALUE"), binding.diagnostics().toString());
    }

    @Test
    void missingValueInSnapshotIsSkippedWithWarn() {
        List<Option> definitions = List.of(OptionFixtures.integer("Q", "1", "0", "1"));
        List<String> sinkLog = new ArrayList<>();

        OptionBinding binding = OptionBinding.of(definitions, Map.of(),
                OptionDiagnosticSink.collectingLines(sinkLog), OptionBinding.DefineStyle.LITERAL);

        assertTrue(binding.defines().isEmpty());
        assertTrue(binding.hasDiagnostic("MISSING_VALUE"), binding.diagnostics().toString());
        assertEquals(1, sinkLog.size());
    }

    @Test
    void integerOutside32BitRangeInSnapshotIsSkippedWithWarn() {
        List<Option> definitions = List.of(OptionFixtures.integer("BIG", "0"));
        OptionBinding binding = OptionBinding.of(definitions, Map.of("BIG", "99999999999"));

        assertTrue(binding.defines().isEmpty());
        assertTrue(binding.hasDiagnostic("DEFINE_SKIPPED_UNSAFE_VALUE"), binding.diagnostics().toString());
    }

    @Test
    void nullOptionEntryIsWarnedAndSkipped() {
        List<Option> definitions = new ArrayList<>();
        definitions.add(OptionFixtures.integer("Q", "1", "0", "1"));
        definitions.add(null);

        OptionBinding binding = OptionBinding.of(definitions, Map.of("Q", "1"));

        assertTrue(binding.hasDiagnostic("NULL_OPTION"), binding.diagnostics().toString());
        assertEquals(1, binding.defines().size());
    }

    @Test
    void declaredBooleanVocabularyMapsToBoolUniform() {
        PackOptions options = PackOptions.of(List.of(OptionFixtures.bool("QUALITY", "on", "on", "off")));

        OptionBinding on = OptionBinding.of(options);
        assertEquals("QUALITY BOOLEAN text=on bool=true", on.uniform("QUALITY").orElseThrow().format());
        assertEquals("on", on.defines().get("QUALITY"));

        options.set("QUALITY", "off");
        OptionBinding off = OptionBinding.of(options);
        assertEquals("QUALITY BOOLEAN text=off bool=false", off.uniform("QUALITY").orElseThrow().format());
    }

    @Test
    void bindingIsSnapshotAndIsRebuiltAfterChange() {
        PackOptions options = PackOptions.of(List.of(OptionFixtures.bool("SHADOWS", "true")));
        OptionBinding before = OptionBinding.of(options);

        options.set("SHADOWS", "false");
        OptionBinding after = OptionBinding.of(options);

        assertTrue(before.definesText().contains("#define SHADOWS true"), "旧绑定是快照，不跟着变");
        assertTrue(after.definesText().contains("#define SHADOWS false"));
    }

    @Test
    void emptyOptionSetProducesHeaderOnlyText() {
        OptionBinding binding = OptionBinding.of(List.of(), Map.of());

        String expectedDefines = """
                option-defines v1
                style=LITERAL
                """;
        String expectedUniforms = """
                option-uniforms v1
                """;
        assertEquals(expectedDefines, binding.definesText());
        assertEquals(expectedUniforms, binding.uniformsText());
        assertThrows(UnsupportedOperationException.class, () -> binding.defines().put("X", "1"));
        assertThrows(UnsupportedOperationException.class, () -> binding.uniforms().clear());
        assertThrows(UnsupportedOperationException.class, () -> binding.undefines().add("X"));
    }

    @Test
    void lookupsAreOptional() {
        OptionBinding binding = OptionBinding.of(sampleOptions(new ArrayList<>()));

        assertEquals("true", binding.define("SHADOWS").orElseThrow());
        assertTrue(binding.define("NOPE").isEmpty());
        assertTrue(binding.uniform("NOPE").isEmpty());
        assertEquals(OptionBinding.DefineStyle.LITERAL, binding.style());
    }

    @Test
    void nullDefinitionsAreProgrammerError() {
        assertThrows(NullPointerException.class, () -> OptionBinding.of(null, Map.of()));
    }

    @Test
    void appendDefinesToWritesTheSameCanonicalText() {
        OptionBinding binding = OptionBinding.of(sampleOptions(new ArrayList<>()));
        StringBuilder target = new StringBuilder();

        binding.appendDefinesTo(target);

        assertEquals(binding.definesText(), target.toString());
    }

    @Test
    void packedOptionsSinkIsReusedByBinding() {
        List<String> sinkLog = new ArrayList<>();
        PackOptions options = PackOptions.of(List.of(OptionFixtures.text("LABEL", "")),
                OptionDiagnosticSink.collectingLines(sinkLog));

        OptionBinding.of(options);

        assertTrue(sinkLog.stream().anyMatch(line -> line.startsWith("[WARN] DEFAULT_MISSING:")), sinkLog.toString());
        assertTrue(sinkLog.stream().anyMatch(line -> line.startsWith("[WARN] DEFINE_SKIPPED_EMPTY_VALUE:")),
                sinkLog.toString());
    }
}
