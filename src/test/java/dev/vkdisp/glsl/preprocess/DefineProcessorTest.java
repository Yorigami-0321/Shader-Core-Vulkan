package dev.vkdisp.glsl.preprocess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.vkdisp.glsl.SourceLineMap;
import dev.vkdisp.glsl.TranslateDiagnostic;

/**
 * 【参考调研】C 线单测 — DefineProcessor
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = OptiFine / Iris 官方文档的条件编译语义（handover §6.2 / §6.3：基于选项宏的
 *    #ifdef / #ifndef / #if / #elif / #else / #endif，以及 #define / #undef）
 *    —— 格式事实，不受版权保护。外部候选 IrisShaders/glsl-preprocessor（GPL-3.0 + 例外条款）
 *    → 一律按禁止处理（handover §2.1 / 07-CONSTRAINTS X20/X21），本任务不读其代码、零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 测试，样本全部自造。
 *    → 能否并入本项目（MIT）：可以（仅采纳不受版权保护的事实性语义）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：C 标准预处理器的条件编译子集 + 对象宏展开（handover §5.3③）。
 * 2. 备选：无 —— 文本级断言最直观。
 * 3. 我们的差异点：未闭合 / #else 顺序错误等边界显式报 ERROR（T11），并对每条输出行登记
 *    "→ include 展开后输入行"的映射，验证阶段行号映射正确。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：测试代码不进运行时（18-PARALLEL §7.7）。
 */
class DefineProcessorTest {

    private static SourceLineMap identityMap(String file, int lines) {
        return SourceLineMap.identity(file, lines);
    }

    @Test
    void ifElifElseSelectsCorrectBranch() {
        String src = "#if 0\nint a = 1;\n#elif 1\nint b = 2;\n#else\nint c = 3;\n#endif\n";
        DefineProcessor.Result r = DefineProcessor.process(src, identityMap("x.fsh", 7));

        assertTrue(r.diagnostics().isEmpty(), "合法条件编译不应产生诊断");
        assertTrue(r.text().contains("int b = 2;"), "应选中 #elif 分支");
        assertFalse(r.text().contains("int a = 1;"), "未选中分支应被删除");
        assertFalse(r.text().contains("int c = 3;"), "未选中分支应被删除");
        assertFalse(r.text().contains("#if") || r.text().contains("#endif"),
                "指令行应被删除");
    }

    @Test
    void ifndefAndNestedIf() {
        // 宏取数值后参与 #if 求值（选项宏常见写法：#define FLAG 1 / #if FLAG）
        String src = "#ifndef FLAG\n#define FLAG 1\n#endif\n#if FLAG\nint on = 1;\n#endif\n";
        DefineProcessor.Result r = DefineProcessor.process(src, identityMap("x.fsh", 5));
        assertTrue(r.diagnostics().isEmpty());
        assertTrue(r.text().contains("int on = 1;"), "FLAG 定义为 1 → #if FLAG 为真 → 内部选中");
        assertFalse(r.text().contains("#ifndef") || r.text().contains("#if"),
                "所有指令应被删除");
    }

    @Test
    void defineAndUndef() {
        String src = "#define A 5\nint x = A;\n#undef A\nint y = A;\n";
        DefineProcessor.Result r = DefineProcessor.process(src, identityMap("x.fsh", 4));
        assertTrue(r.diagnostics().isEmpty());
        assertTrue(r.text().contains("int x = 5;"), "宏定义后应被展开");
        assertTrue(r.text().contains("int y = A;"), "undef 后宏不再展开，保留原标识符");
    }

    @Test
    void objectMacroExpandsInCode() {
        String src = "#define VAL 42\nfloat f = VAL;\n";
        DefineProcessor.Result r = DefineProcessor.process(src, identityMap("x.fsh", 2));
        assertTrue(r.diagnostics().isEmpty());
        assertTrue(r.text().contains("float f = 42;"), "对象宏应展开为值");
    }

    @Test
    void unbalancedEndifReportsErrorWithLocation() {
        DefineProcessor.Result r = DefineProcessor.process("#endif\n", identityMap("y.fsh", 1));
        assertFalse(r.diagnostics().isEmpty(), "未闭合 #endif 必须报错");
        TranslateDiagnostic d = r.diagnostics().get(0);
        assertEquals(TranslateDiagnostic.Severity.ERROR, d.severity());
        assertEquals("y.fsh", d.sourceFile());
        assertEquals(1, d.line());
    }

    @Test
    void unclosedIfReportsError() {
        DefineProcessor.Result r =
                DefineProcessor.process("#ifdef FOO\nint x;\n", identityMap("z.fsh", 2));
        boolean hasError = r.diagnostics().stream()
                .anyMatch(d -> d.severity() == TranslateDiagnostic.Severity.ERROR
                        && d.message().contains("未闭合"));
        assertTrue(hasError, "未闭合的 #if 必须报错");
    }

    @Test
    void lineMapMapsOutputBackToInputLine() {
        // 输入 6 行：1 int a / 2 #define（删）/ 3 int b / 4 #if 0 / 5 int c（删）/ 6 #endif
        String src = "int a;\n#define X 1\nint b;\n#if 0\nint c;\n#endif\n";
        DefineProcessor.Result r = DefineProcessor.process(src, identityMap("m.fsh", 6));
        SourceLineMap map = r.lineMap();
        // 阶段映射记录"输出行 → include 展开后输入行号"（sourceFile 在此阶段为 null，由编排入口 compose）
        assertEquals(1, map.originOf(1).sourceLine());
        assertEquals(3, map.originOf(2).sourceLine());
    }

    @Test
    void nonPreprocessorDirectivesAreKept() {
        String src = "#version 150\nprecision highp float;\n";
        DefineProcessor.Result r = DefineProcessor.process(src, identityMap("v.fsh", 2));
        assertTrue(r.diagnostics().isEmpty());
        assertTrue(r.text().contains("#version 150"), "#version 应保留");
        assertTrue(r.text().contains("precision highp float;"), "#precision 应保留");
    }
}
