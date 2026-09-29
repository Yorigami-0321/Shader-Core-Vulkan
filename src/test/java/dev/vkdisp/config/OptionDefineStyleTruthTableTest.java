package dev.vkdisp.config;

/**
 * 【参考调研】F 线单测 / 布尔 #define 两种风格 + STRING 选项的真值表对比材料（18-PARALLEL §10 P-1e）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §3.5（OptionBinding = 选项 → 着色器 #define / uniform 的绑定）；
 *    ② docs/18-PARALLEL.md §4 F 线完成标准（"#define 表生成结果可对比"）与 §10 P-1e（布尔 #define 两种风格
 *       LITERAL vs OF 兼容 IFDEF_TRUE 与 STRING 选项的 GLSL 映射：两风格均已实现且有单测，缺"真值表对比
 *       材料" → 本文件即该材料，结论写进 OptionBinding 的类级 Javadoc）；
 *    ③ docs/07-CONSTRAINTS.md X9（待确认项不许用猜的值填）与 T11（跳过必须显式 WARN）；
 *    ④ C 预处理器语言事实（不受版权保护）：#define NAME 替换文本 / #define NAME（空替换文本）/ #undef NAME /
 *       #ifdef 只测"是否已定义"。
 *    许可证：本仓库自有文档与自有类型（本项目 MIT）→ 可直接消费；参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 /
 *    Beryl ARR）与 OptiFine（sp614x/optifine 无 LICENSE = ARR）零接触、未读其任何代码（07-CONSTRAINTS L12 / X20）。
 *    → 能否并入本项目（MIT）：可以（本文件为独立编写的测试，用例数据全部自造）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：JUnit 5 表驱动断言 —— 同一份 Option 定义 + 同一份值快照分别按 LITERAL / IFDEF_TRUE 求值，
 *    逐行对比 #define 表输出；期望值全部手写在真值表里（不由被测逻辑反推，避免自证循环）。
 * 2. 备选：只给两种风格各写一份独立快照（OptionBindingTest 的现状做法）—— 已有但不够：两份快照并排放着看不出
 *    "哪一行必须不同、哪一行必须相同"；本文件补的正是逐项对比 + 结论（结论落在 OptionBinding 类级 Javadoc）。
 * 3. 我们的差异点：① 一行 = 一个选项在两种风格下的期望产物，循环逐行对比；② 附加"必须逐字节相同"断言
 *    （非布尔 define / uniforms / diagnostics / 跳过行为与风格无关）；③ 并排表可直接当 P4.2 / P4.3 的对比材料；
 *    ④ 不改 docs/（文档同步归 env-1，18-PARALLEL §7.2）。
 * 4. 许可证核对：本项目 MIT；JUnit 5 = EPL-2.0（仅 testImplementation 依赖，不进分发 jar）；零第三方代码复制。
 * 5. 性能基线：测试代码不进运行时，无性能影响（18-PARALLEL §7.7）。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.vkdisp.pack.Option;
import dev.vkdisp.pack.OptionType;

/**
 * P-1e 真值表对比材料：同一选项在 {@link OptionBinding.DefineStyle#LITERAL} 与
 * {@link OptionBinding.DefineStyle#IFDEF_TRUE} 两种风格下的 {@code #define} 表输出逐项对比。
 *
 * <p>结论已写进 {@link OptionBinding} 的类级 Javadoc（"P-1e 真值表结论"），本类只负责把它变成会红的断言。
 */
class OptionDefineStyleTruthTableTest {

    /**
     * 真值表的一行：同一选项在两种风格下的期望产物（期望值手写，不由被测逻辑反推）。
     *
     * @param option         选项定义
     * @param value          当前值（两种风格共用同一份值快照）
     * @param literalLine    LITERAL 风格下的 {@code #define} 行；null = 该风格不产出
     * @param ifdefLine      IFDEF_TRUE 风格下的 {@code #define} 行；null = 不进 {@code defines()} 表
     * @param ifdefUndefLine IFDEF_TRUE 风格下的 {@code #undef} 行；null = 不进 {@code undefines()} 集合
     */
    private record TruthRow(
            Option option,
            String value,
            String literalLine,
            String ifdefLine,
            String ifdefUndefLine) {
    }

    /** 布尔行：真值词原样进 LITERAL 表，IFDEF_TRUE 按真/假分流。 */
    private static TruthRow boolRow(
            String name, String value, String literalLine, String ifdefLine, String ifdefUndefLine) {
        return new TruthRow(OptionFixtures.bool(name, value), value, literalLine, ifdefLine, ifdefUndefLine);
    }

    /** 非布尔行：两种风格必须产出完全相同的 {@code #define} 行，且永不产 {@code #undef}。 */
    private static TruthRow typedRow(String name, OptionType type, String value) {
        Option option = switch (type) {
            case BOOLEAN -> OptionFixtures.bool(name, value);
            case INTEGER -> OptionFixtures.integer(name, value);
            case FLOAT -> OptionFixtures.floating(name, value);
            case STRING -> OptionFixtures.text(name, value);
        };
        String line = "#define " + name + " " + value;
        return new TruthRow(option, value, line, line, null);
    }

    /**
     * P-1e 真值表（13 行 = 10 个布尔词变体 + 3 个非布尔）。
     *
     * <p>分组：① 布尔真（true / on / yes / 1 / TRUE）→ LITERAL 保留原始词，IFDEF_TRUE 只留宏名；
     * ② 布尔假（false / off / no / 0 / OFF）→ LITERAL 照样出表，IFDEF_TRUE 移进 {@code undefines()}；
     * ③ 非布尔（INTEGER / FLOAT / STRING）→ 两风格逐字节相同。
     */
    private static final List<TruthRow> TRUTH_TABLE = List.of(
            boolRow("SHADOWS", "true", "#define SHADOWS true", "#define SHADOWS", null),
            boolRow("WETNESS", "on", "#define WETNESS on", "#define WETNESS", null),
            boolRow("CAVES", "yes", "#define CAVES yes", "#define CAVES", null),
            boolRow("USE_TAA", "1", "#define USE_TAA 1", "#define USE_TAA", null),
            boolRow("UPPER_ON", "TRUE", "#define UPPER_ON TRUE", "#define UPPER_ON", null),
            boolRow("OLD_LIGHTING", "false", "#define OLD_LIGHTING false", null, "#undef OLD_LIGHTING"),
            boolRow("DRYNESS", "off", "#define DRYNESS off", null, "#undef DRYNESS"),
            boolRow("NETHER", "no", "#define NETHER no", null, "#undef NETHER"),
            boolRow("USE_BLOOM", "0", "#define USE_BLOOM 0", null, "#undef USE_BLOOM"),
            boolRow("UPPER_OFF", "OFF", "#define UPPER_OFF OFF", null, "#undef UPPER_OFF"),
            typedRow("SHADOW_QUALITY", OptionType.INTEGER, "2"),
            typedRow("SUN_BRIGHTNESS", OptionType.FLOAT, "1.5"),
            typedRow("WATER_STYLE", OptionType.STRING, "fancy"));

    // ---------------------------------------------------------------- 构造

    private static List<Option> definitions() {
        List<Option> definitions = new ArrayList<>(TRUTH_TABLE.size());
        for (TruthRow row : TRUTH_TABLE) {
            definitions.add(row.option());
        }
        return definitions;
    }

    private static Map<String, String> values() {
        Map<String, String> values = new LinkedHashMap<>();
        for (TruthRow row : TRUTH_TABLE) {
            values.put(row.option().name(), row.value());
        }
        return values;
    }

    private static OptionBinding bind(OptionBinding.DefineStyle style) {
        List<String> sinkLog = new ArrayList<>();
        return OptionBinding.of(definitions(), values(), OptionDiagnosticSink.collectingLines(sinkLog), style);
    }

    /** 某宏名在表里的规范 {@code #define} 行（不在表里 → null）。 */
    private static String defineLine(OptionBinding binding, String name) {
        String replacement = binding.defines().get(name);
        if (replacement == null) {
            return null;
        }
        return replacement.isEmpty() ? "#define " + name : "#define " + name + " " + replacement;
    }

    /** 某宏名的规范 {@code #undef} 行（不在集合里 → null）。 */
    private static String undefLine(OptionBinding binding, String name) {
        return binding.undefines().contains(name) ? "#undef " + name : null;
    }

    /** 某宏名在 IFDEF_TRUE 下的产物：有 define 用 define，否则用 undef（两风格都不缺席）。 */
    private static String defineOrUndefLine(OptionBinding binding, String name) {
        String define = defineLine(binding, name);
        return define != null ? define : undefLine(binding, name);
    }

    private static List<String> codes(OptionBinding binding) {
        List<String> codes = new ArrayList<>();
        for (OptionDiagnostic diagnostic : binding.diagnostics()) {
            codes.add(diagnostic.code());
        }
        return codes;
    }

    // ------------------------------------------------ 逐项对比（表驱动主断言）

    @Test
    void everyTruthTableRowIsComparedAcrossBothStyles() {
        OptionBinding literal = bind(OptionBinding.DefineStyle.LITERAL);
        OptionBinding ifdef = bind(OptionBinding.DefineStyle.IFDEF_TRUE);

        for (TruthRow row : TRUTH_TABLE) {
            String name = row.option().name();
            assertEquals(row.literalLine(), defineLine(literal, name), "LITERAL 的 " + name);
            assertTrue(literal.defines().containsKey(name), "LITERAL 下每个选项都必须在表里：" + name);
            assertNull(undefLine(literal, name), "LITERAL 永不产 #undef：" + name);

            assertEquals(row.ifdefLine(), defineLine(ifdef, name), "IFDEF_TRUE 的 " + name);
            assertEquals(row.ifdefUndefLine(), undefLine(ifdef, name), "IFDEF_TRUE 的 #undef " + name);
        }
    }

    @Test
    void booleanTrueKeepsRawTokenInLiteralButOnlyNameInIfdefTrue() {
        OptionBinding literal = bind(OptionBinding.DefineStyle.LITERAL);
        OptionBinding ifdef = bind(OptionBinding.DefineStyle.IFDEF_TRUE);

        assertEquals("true", literal.define("SHADOWS").orElseThrow(), "LITERAL 原样保留真值词");
        assertEquals("", ifdef.define("SHADOWS").orElseThrow(), "IFDEF_TRUE 真值只有宏名、替换文本为空");
        assertEquals("#define SHADOWS", defineLine(ifdef, "SHADOWS"));

        assertEquals("TRUE", literal.define("UPPER_ON").orElseThrow(), "真值词不归一化（大小写原样）");
        assertEquals("", ifdef.define("UPPER_ON").orElseThrow());
        assertEquals("#define UPPER_ON", defineLine(ifdef, "UPPER_ON"));
    }

    @Test
    void booleanFalseStaysInLiteralTableButMovesToUndefinesInIfdefTrue() {
        OptionBinding literal = bind(OptionBinding.DefineStyle.LITERAL);
        OptionBinding ifdef = bind(OptionBinding.DefineStyle.IFDEF_TRUE);

        assertEquals("false", literal.define("OLD_LIGHTING").orElseThrow(), "LITERAL 下假值同样出表");
        assertEquals("#define OLD_LIGHTING false", defineLine(literal, "OLD_LIGHTING"));
        assertTrue(ifdef.define("OLD_LIGHTING").isEmpty(), "IFDEF_TRUE 下假值不进 defines 表");
        assertNull(defineLine(ifdef, "OLD_LIGHTING"), "假值不产 #define 行");
        assertEquals("#undef OLD_LIGHTING", undefLine(ifdef, "OLD_LIGHTING"));
        assertEquals("#undef UPPER_OFF", defineOrUndefLine(ifdef, "UPPER_OFF"));
        assertTrue(literal.undefines().isEmpty(), "LITERAL 永远没有 #undef：" + literal.undefines());
    }

    // ---------------------------------------------------------- 整表快照

    @Test
    void fullDefineTableSnapshotIsExactForEachStyle() {
        String literalExpected = """
                option-defines v1
                style=LITERAL
                #define SHADOWS true
                #define WETNESS on
                #define CAVES yes
                #define USE_TAA 1
                #define UPPER_ON TRUE
                #define OLD_LIGHTING false
                #define DRYNESS off
                #define NETHER no
                #define USE_BLOOM 0
                #define UPPER_OFF OFF
                #define SHADOW_QUALITY 2
                #define SUN_BRIGHTNESS 1.5
                #define WATER_STYLE fancy
                """;
        String ifdefExpected = """
                option-defines v1
                style=IFDEF_TRUE
                #define SHADOWS
                #define WETNESS
                #define CAVES
                #define USE_TAA
                #define UPPER_ON
                #define SHADOW_QUALITY 2
                #define SUN_BRIGHTNESS 1.5
                #define WATER_STYLE fancy
                #undef OLD_LIGHTING
                #undef DRYNESS
                #undef NETHER
                #undef USE_BLOOM
                #undef UPPER_OFF
                """;

        assertEquals(literalExpected, bind(OptionBinding.DefineStyle.LITERAL).definesText());
        assertEquals(ifdefExpected, bind(OptionBinding.DefineStyle.IFDEF_TRUE).definesText());
    }

    @Test
    void sideBySideComparisonTableIsExact() {
        String expected = """
                name           type    value LITERAL (#define)          IFDEF_TRUE (#define/#undef)
                SHADOWS        BOOLEAN true  #define SHADOWS true       #define SHADOWS
                WETNESS        BOOLEAN on    #define WETNESS on         #define WETNESS
                CAVES          BOOLEAN yes   #define CAVES yes          #define CAVES
                USE_TAA        BOOLEAN 1     #define USE_TAA 1          #define USE_TAA
                UPPER_ON       BOOLEAN TRUE  #define UPPER_ON TRUE      #define UPPER_ON
                OLD_LIGHTING   BOOLEAN false #define OLD_LIGHTING false #undef OLD_LIGHTING
                DRYNESS        BOOLEAN off   #define DRYNESS off        #undef DRYNESS
                NETHER         BOOLEAN no    #define NETHER no          #undef NETHER
                USE_BLOOM      BOOLEAN 0     #define USE_BLOOM 0        #undef USE_BLOOM
                UPPER_OFF      BOOLEAN OFF   #define UPPER_OFF OFF      #undef UPPER_OFF
                SHADOW_QUALITY INTEGER 2     #define SHADOW_QUALITY 2   #define SHADOW_QUALITY 2
                SUN_BRIGHTNESS FLOAT   1.5   #define SUN_BRIGHTNESS 1.5 #define SUN_BRIGHTNESS 1.5
                WATER_STYLE    STRING  fancy #define WATER_STYLE fancy  #define WATER_STYLE fancy
                """;

        assertEquals(expected, sideBySideTable());
    }

    /** 用真实 binding 输出渲染的并排对比表（P4.2 / P4.3 可直接粘走的对比材料）。 */
    private static String sideBySideTable() {
        OptionBinding literal = bind(OptionBinding.DefineStyle.LITERAL);
        OptionBinding ifdef = bind(OptionBinding.DefineStyle.IFDEF_TRUE);
        StringBuilder table = new StringBuilder();
        appendRow(table, "name", "type", "value", "LITERAL (#define)", "IFDEF_TRUE (#define/#undef)");
        for (TruthRow row : TRUTH_TABLE) {
            String name = row.option().name();
            appendRow(table, name, row.option().type().name(), row.value(),
                    defineLine(literal, name), defineOrUndefLine(ifdef, name));
        }
        return table.toString();
    }

    private static void appendRow(
            StringBuilder table, String name, String type, String value, String literal, String ifdef) {
        table.append(pad(name, 14)).append(' ')
                .append(pad(type, 7)).append(' ')
                .append(pad(value, 5)).append(' ')
                .append(pad(literal, 26)).append(' ')
                .append(ifdef).append('\n');
    }

    private static String pad(String text, int width) {
        StringBuilder padded = new StringBuilder(text);
        while (padded.length() < width) {
            padded.append(' ');
        }
        return padded.toString();
    }

    // ------------------------------------------------ 风格无关的部分（必须逐字节相同）

    @Test
    void uniformsAndDiagnosticsAreStyleIndependent() {
        OptionBinding literal = bind(OptionBinding.DefineStyle.LITERAL);
        OptionBinding ifdef = bind(OptionBinding.DefineStyle.IFDEF_TRUE);

        assertEquals(literal.uniformsText(), ifdef.uniformsText(), "风格只影响 #define 表，不影响 uniform 值");
        assertEquals(literal.diagnostics(), ifdef.diagnostics(), "合法输入下两风格诊断必须相同");
        assertTrue(literal.diagnostics().isEmpty(), literal.diagnostics().toString());
        assertEquals(TRUTH_TABLE.size(), literal.uniforms().size());
        for (int i = 0; i < TRUTH_TABLE.size(); i++) {
            assertEquals(literal.uniforms().get(i).format(), ifdef.uniforms().get(i).format(),
                    "uniform 逐条相同：" + TRUTH_TABLE.get(i).option().name());
        }
    }

    @Test
    void skippedAndStringOptionsBehaveIdenticallyInBothStyles() {
        List<Option> definitions = List.of(
                OptionFixtures.text("WATER_STYLE", "fancy", "fast", "fancy"),
                OptionFixtures.text("FREE_TEXT", "hello world"),
                OptionFixtures.text("bad name", "fancy", "fast", "fancy"),
                OptionFixtures.integer("QUALITY", "1", "0", "1"),
                OptionFixtures.integer("ABSENT", "1", "0", "1"));
        Map<String, String> values = Map.of(
                "WATER_STYLE", "fancy",
                "FREE_TEXT", "hello world",
                "bad name", "fancy",
                "QUALITY", "99999999999");

        List<String> literalSink = new ArrayList<>();
        List<String> ifdefSink = new ArrayList<>();
        OptionBinding literal = OptionBinding.of(definitions, values,
                OptionDiagnosticSink.collectingLines(literalSink), OptionBinding.DefineStyle.LITERAL);
        OptionBinding ifdef = OptionBinding.of(definitions, values,
                OptionDiagnosticSink.collectingLines(ifdefSink), OptionBinding.DefineStyle.IFDEF_TRUE);

        assertEquals(1, literal.defines().size(), "只有合法 STRING 标识符进表");
        assertEquals("fancy", literal.define("WATER_STYLE").orElseThrow());
        assertEquals(literal.defines(), ifdef.defines(), "跳过行为与风格无关（合法项逐字节相同）");
        assertEquals(literal.undefines(), ifdef.undefines());
        assertEquals(literal.diagnostics(), ifdef.diagnostics());
        assertEquals(literalSink, ifdefSink, "两风格必须产生逐行相同的 WARN（T11）");

        assertEquals(List.of(
                "DEFINE_SKIPPED_UNSAFE_VALUE",
                "DEFINE_SKIPPED_UNSAFE_NAME",
                "DEFINE_SKIPPED_UNSAFE_VALUE",
                "MISSING_VALUE"), codes(literal));
        assertTrue(ifdef.hasDiagnostic("DEFINE_SKIPPED_UNSAFE_VALUE"), ifdef.diagnostics().toString());
    }

    @Test
    void defaultStyleIsLiteralAndSwitchIsOneCall() {
        PackOptions options = PackOptions.of(List.of(OptionFixtures.bool("SHADOWS", "true")));

        assertEquals(OptionBinding.DefineStyle.LITERAL, OptionBinding.of(options).style(),
                "P-1e 未决项：默认风格暂留 LITERAL（真实包定稿前不改，X9）");
        assertEquals(OptionBinding.DefineStyle.LITERAL, OptionBinding.of(definitions(), values()).style());
        assertEquals(OptionBinding.DefineStyle.IFDEF_TRUE,
                OptionBinding.of(options, OptionBinding.DefineStyle.IFDEF_TRUE).style());
    }
}
