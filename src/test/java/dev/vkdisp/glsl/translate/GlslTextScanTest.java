package dev.vkdisp.glsl.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.vkdisp.glsl.TranslateDiagnostic;

/**
 * 【参考调研】D 线二期单测（共用扫描原语）/ GLSL 公开词法事实 + 一期等长视图口径
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/18-PARALLEL.md §4 D 线与 §7.3 证据规范、一期 CommentState 的
 *    "注释替换为等长空格"口径 —— 仓库内事实；另加 GLSL 官方公开词法规则（标识符字符集、
 *    括号配对、双引号字符串、反斜杠续行的预处理指令）。外部候选 IrisShaders/glsl-transformer
 *    （GPL-3.0 + 例外条款）→ 按禁止处理（07-CONSTRAINTS L12 §1.3 陷阱 2 / X21），
 *    本任务不读其代码、零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 测试；样本全部为本任务自造（18-PARALLEL §7.6）。
 *    → 能否并入本项目（MIT）：可以（仅采纳不受版权保护的事实性信息）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：JUnit 5 直接断言扫描原语的边界 —— 等长同下标、转义与未闭合引号、
 *    嵌套括号、续行标记、头部区定位。
 * 2. 备选：无 —— 原语逻辑很小，直接调用最直观。
 * 3. 我们的差异点：把"视图与原始行等长同下标"这条不变量单独测掉 —— 它是三个变换类能安全
 *    按列替换原始行的前提；预处理指令续行（宏体）也被显式验证为"不进重写范围"。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：测试代码不进运行时；冷路径无性能要求（18-PARALLEL §7.7）。
 */
/** {@link GlslTextScan} 的单测：等长同下标视图、括号配对、续行标记、头部区定位。 */
class GlslTextScanTest {

    @Test
    void blankStringsKeepsLengthAndBlanksQuotedSpans() {
        String line = "\"abc\" + x";
        String view = GlslTextScan.blankStrings(line);
        assertEquals(line.length(), view.length(), "视图必须与原始行等长（按列改原始行的前提）");
        assertEquals("+ x", view.strip());
    }

    @Test
    void blankStringsHandlesEscapedAndUnterminatedQuotes() {
        String escaped = "\"a\\\"b\" + y";
        assertEquals("+ y", GlslTextScan.blankStrings(escaped).strip());

        String unterminated = "\"abc";
        assertEquals(unterminated.length(), GlslTextScan.blankStrings(unterminated).length());
        assertTrue(GlslTextScan.blankStrings(unterminated).isBlank());
    }

    @Test
    void matchCloseParenHandlesNestingAndRejectsBadInput() {
        assertEquals(12, GlslTextScan.matchCloseParen("f(a, g(b), c)", 1));
        assertEquals(-1, GlslTextScan.matchCloseParen("f(a", 1), "未配对");
        assertEquals(-1, GlslTextScan.matchCloseParen("abc", 0), "起点不是左括号");
        assertEquals(-1, GlslTextScan.matchCloseParen("abc", -1));
    }

    @Test
    void identifierLookupAndTokenBoundary() {
        int[] span = GlslTextScan.identifierAt("obj.texture2D", 4);
        assertEquals(4, span[0]);
        assertEquals(13, span[1]);
        assertFalse(GlslTextScan.atTokenStart("obj.texture2D", 4), "成员访问点后不是独立标识符");

        int[] whole = GlslTextScan.identifierAt("xgl_FragColor", 0);
        assertEquals(13, whole[1], "整段是一个标识符，不是 gl_FragColor");
        assertNull(GlslTextScan.identifierAt("123", 0), "数字开头不是标识符");

        assertTrue(GlslTextScan.atTokenStart("gl_FragColor", 0));
    }

    @Test
    void preprocessorSkipLinesCoversHashLinesAndContinuations() {
        String continuation = "#define A " + '\\';
        List<String> raw = List.of(continuation, "    body", "code", "#pragma", "x");
        boolean[] skip = GlslTextScan.preprocessorSkipLines(raw, raw);
        assertEquals(List.of(true, true, false, true, false),
                List.of(skip[0], skip[1], skip[2], skip[3], skip[4]));
    }

    @Test
    void headerEndStopsAtFirstCodeLine() {
        List<String> lines = List.of("#version 330", "// c", "", "    ", "out vec4 x;");
        assertEquals(4, GlslTextScan.headerEnd(lines));
    }

    @Test
    void headerEndOfCommentOnlyFileIsLineCount() {
        List<String> lines = List.of("// a", "/* b */");
        assertEquals(2, GlslTextScan.headerEnd(lines));
    }

    @Test
    void codeViewsBlanksCommentsAndStringsAndReportsUnterminatedBlockComment() {
        List<String> raw = List.of("a /* open", "b \"quoted\"");
        List<TranslateDiagnostic> diagnostics = new java.util.ArrayList<>();
        List<String> codes = GlslTextScan.codeViews(raw, diagnostics);
        assertEquals(2, codes.size());
        assertEquals(raw.get(0).length(), codes.get(0).length());
        assertEquals(raw.get(1).length(), codes.get(1).length());
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        assertEquals(TranslateDiagnostic.Severity.ERROR, diagnostics.get(0).severity());
        assertEquals(1, diagnostics.get(0).line());
        assertTrue(diagnostics.get(0).message().contains("未闭合"));
    }

    @Test
    void codeViewsWithoutBlockCommentProducesNoDiagnostics() {
        List<TranslateDiagnostic> diagnostics = new java.util.ArrayList<>();
        List<String> codes = GlslTextScan.codeViews(List.of("out vec4 x;"), diagnostics);
        assertEquals("out vec4 x;", codes.get(0));
        assertTrue(diagnostics.isEmpty());
    }
}
