package dev.vkdisp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 【参考调研】P2.4 选项改写单测 / 04-SPEC §4 选项语义 + 18-PARALLEL §5 P2.4 设计 ④
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/04-SPEC.md §4（选项 → #define）与 docs/18-PARALLEL.md §5 P2.4
 *    ④「逐行改写 `#define NAME <值>` / `const NAME = <值>;`（保行号保注释）」—— 仓库内文档事实，
 *    不受版权保护。外部候选 IrisShaders/glsl-transformer（GPL-3.0 + 例外条款）→ 按禁止处理
 *    （07-CONSTRAINTS L12 §1.3 陷阱 2 / X21），本任务不读其代码、零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 测试，样本全部为本任务自造（18-PARALLEL §7.6）。
 *    → 能否并入本项目（MIT）：可以（仅采纳不受版权保护的事实性信息）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：无外部实现可参考，按 {@link OptionSourceRewriter} 类契约（保持行数/注释/CRLF）断言。
 * 2. 备选：无 —— 文本级断言足够，不引入快照框架。
 * 3. 我们的差异点：边界用例（null 源 / 空覆盖表 / CRLF / 裸 #define / 注释顶值 / 值相同 /
 *    未命中名）全部显式断言，对应 18-PARALLEL §7.3 的边界用例清单要求。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：测试代码不进运行时；被测件是❄️冷路径（18-PARALLEL §7.7）。
 */
/**
 * {@link OptionSourceRewriter} 单测：OF 形态逐字比对、行数/注释保留、CRLF、裸宏、缺失登记。
 */
class OptionSourceRewriterTest {

    @Test
    void rewritesDefineValueInPlaceKeepingComment() {
        String source = "#define SHADOW_DARKNESS 0.10 // [0.05 0.10 0.20]\n";
        OptionSourceRewriter.Result result = OptionSourceRewriter.apply(
                source, Map.of("SHADOW_DARKNESS", "0.20"));
        assertEquals("#define SHADOW_DARKNESS 0.20 // [0.05 0.10 0.20]\n", result.text(),
                "只换值记号，尾注与行结构必须原样保留");
        assertEquals(1, result.appliedCount());
        assertEquals(java.util.Set.of("SHADOW_DARKNESS"), result.appliedNames());
        assertTrue(result.missingNames().isEmpty());
    }

    @Test
    void rewritesConstAssignmentValue() {
        String source = "const float shadowDistance = 64.0; // [32.0 64.0]\n";
        OptionSourceRewriter.Result result = OptionSourceRewriter.apply(
                source, Map.of("shadowDistance", "32.0"));
        assertEquals("const float shadowDistance = 32.0; // [32.0 64.0]\n", result.text(),
                "const 选项常量就地换值，注释保留");
        assertEquals(1, result.appliedCount());
    }

    @Test
    void bareDefineReceivesValue() {
        String source = "#define ENABLE_FOG\nvoid main() {}\n";
        OptionSourceRewriter.Result result = OptionSourceRewriter.apply(
                source, Map.of("ENABLE_FOG", "true"));
        assertEquals("#define ENABLE_FOG true\nvoid main() {}\n", result.text(),
                "裸宏新值为 true → 就地补上值");
        assertEquals(1, result.appliedCount());
    }

    @Test
    void bareDefineFalseBecomesUndef() {
        String source = "#define ENABLE_FOG // [true false]\n";
        OptionSourceRewriter.Result result = OptionSourceRewriter.apply(
                source, Map.of("ENABLE_FOG", "false"));
        assertEquals("#undef ENABLE_FOG // [true false]\n", result.text(),
                "OF 布尔关 = 宏不存在（#undef），尾注保留");
        assertEquals(1, result.appliedCount());
    }

    @Test
    void commentInValueSlotTreatedAsBareDefine() {
        String source = "#define ENABLE_FOG // [true false]\n";
        OptionSourceRewriter.Result result = OptionSourceRewriter.apply(
                source, Map.of("ENABLE_FOG", "true"));
        assertEquals("#define ENABLE_FOG true // [true false]\n", result.text(),
                "值位被注释顶替 → 按裸宏补值，注释整段保留");
        assertEquals(1, result.appliedCount());
    }

    @Test
    void lineCountAndUntouchedLinesPreserved() {
        String source = """
                #version 330 core
                #define A 1 // [1 2]
                void main() {}
                // trailing
                """;
        OptionSourceRewriter.Result result = OptionSourceRewriter.apply(source, Map.of("A", "2"));
        String[] before = source.split("\n", -1);
        String[] after = result.text().split("\n", -1);
        assertEquals(before.length, after.length, "行数必须逐行对齐（F3 行号契约）");
        assertEquals("#version 330 core", after[0]);
        assertEquals("#define A 2 // [1 2]", after[1]);
        assertEquals("void main() {}", after[2]);
        assertEquals("// trailing", after[3]);
    }

    @Test
    void crlfLineEndingsArePreservedWithoutBareLf() {
        String source = "#define A 1\r\n#define B 2\r\n";
        OptionSourceRewriter.Result result = OptionSourceRewriter.apply(source, Map.of("A", "9"));
        assertEquals("#define A 9\r\n#define B 2\r\n", result.text(),
                "CRLF 行尾原样保留，不许混入裸 LF");
        assertFalse(result.text().replace("\r\n", "").contains("\n"), "输出里除 CRLF 外没有裸 LF");
        assertEquals(1, result.appliedCount(), "B 值未变不算改写");
    }

    @Test
    void sameValueStillCountsAsAppliedName() {
        String source = "#define A 1\n";
        OptionSourceRewriter.Result result = OptionSourceRewriter.apply(source, Map.of("A", "1"));
        assertEquals(source, result.text(), "值相同 → 文本不变、appliedCount=0");
        assertEquals(0, result.appliedCount());
        assertEquals(java.util.Set.of("A"), result.appliedNames(),
                "声明被找到即登记（即便值恰好相同）");
        assertTrue(result.missingNames().isEmpty());
    }

    @Test
    void missingNamesAreReportedNotThrown() {
        String source = "#define A 1\n";
        OptionSourceRewriter.Result result = OptionSourceRewriter.apply(
                source, Map.of("A", "2", "NOT_IN_FILE", "5"));
        assertEquals(java.util.Set.of("NOT_IN_FILE"), result.missingNames(),
                "未命中的请求名进 missingNames（由调用方决定是否告警）");
        assertEquals(java.util.Set.of("A"), result.appliedNames());
    }

    @Test
    void nullAndEmptyInputsNeverThrow() {
        assertEquals("", OptionSourceRewriter.apply(null, Map.of("A", "1")).text());
        String source = "#define A 1\n";
        assertEquals(source, OptionSourceRewriter.apply(source, null).text(), "空覆盖表 → 原样返回");
        assertEquals(source, OptionSourceRewriter.apply(source, Map.of()).text());
        OptionSourceRewriter.Result empty = OptionSourceRewriter.apply(null, null);
        assertEquals(0, empty.appliedCount());
        assertTrue(empty.appliedNames().isEmpty());
        assertTrue(empty.missingNames().isEmpty());
    }

    @Test
    void unrelatedLinesNeverTouched() {
        String source = """
                #version 330 core
                #define OTHER 3 // [1 3]
                const float keep = 7.0;
                int x = 1;
                """;
        OptionSourceRewriter.Result result = OptionSourceRewriter.apply(
                source, Map.of("SHADOW_DARKNESS", "0.20"));
        assertEquals(source, result.text(), "没有任何命中 → 逐字节原样");
        assertEquals(0, result.appliedCount());
        assertEquals(java.util.Set.of("SHADOW_DARKNESS"), result.missingNames());
    }

    @Test
    void multipleOptionLinesRewrittenIndependently() {
        String source = """
                #define SHADOW_DARKNESS 0.10 // [0.05 0.10 0.20]
                #define ENABLE_FOG true // [true false]
                const float shadowDistance = 64.0; // [32.0 64.0]
                """;
        OptionSourceRewriter.Result result = OptionSourceRewriter.apply(source, Map.of(
                "SHADOW_DARKNESS", "0.05",
                "ENABLE_FOG", "false",
                "shadowDistance", "32.0"));
        List<String> lines = List.of(result.text().split("\n", -1));
        assertEquals("#define SHADOW_DARKNESS 0.05 // [0.05 0.10 0.20]", lines.get(0));
        assertEquals("#define ENABLE_FOG false // [true false]", lines.get(1));
        assertEquals("const float shadowDistance = 32.0; // [32.0 64.0]", lines.get(2));
        assertEquals(3, result.appliedCount());
        assertTrue(result.missingNames().isEmpty());
    }

    @Test
    void macroDefinedWithBracketCommentDirectiveUntouched() {
        // 条件编译/其它指令行（#if/#endif）不匹配任何模式，永不被改写。
        String source = """
                #ifdef SHADOW_DARKNESS
                #define SHADOW_DARKNESS 0.10
                #endif
                """;
        OptionSourceRewriter.Result result = OptionSourceRewriter.apply(
                source, Map.of("SHADOW_DARKNESS", "0.20"));
        List<String> lines = List.of(result.text().split("\n", -1));
        assertEquals("#ifdef SHADOW_DARKNESS", lines.get(0), "#ifdef 行不许动");
        assertEquals("#define SHADOW_DARKNESS 0.20", lines.get(1));
        assertEquals("#endif", lines.get(2), "#endif 行不许动");
        assertEquals(1, result.appliedCount());
    }
}
