package dev.vkdisp.glsl.lexer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A1 的 L1 层守卫：**无损**是这一层的全部意义 —— 拼回去不等于输入的词法源，
 * 后面每一阶段的「只替换一段 offset」都会变成「悄悄改写没让它改的字节」。
 */
class GlslTokensTest {

    @Test
    @DisplayName("🔖 无损：token 拼回必须与输入逐字节相同（含未闭合注释/字符串/奇怪空白）")
    void tokenizeIsLossless() {
        List<String> samples = List.of(
                "",
                "uniform sampler2D tex;\n",
                "// 行注释\n/* 块\n注释 */ int a;\n",
                "const char* s = \"未闭合的字符串\n",
                "a\r\nb\nc",
                "layout(location = 0) out vec4 color; // 尾注释",
                "/* 未闭合的块注释一直到这里");
        for (String sample : samples) {
            String rendered = GlslTokens.render(GlslTokens.tokenize(sample));
            assertEquals(sample, rendered, "无损不变量被破坏，样本=" + sample.replace("\n", "\\n"));
        }
    }

    @Test
    @DisplayName("token 带原文下标，且切片与 text 逐字相同（改写只能按 offset 区间做）")
    void tokensCarryOffsets() {
        String source = "uniform sampler2D tex;";
        List<GlslTokens.Token> tokens = GlslTokens.tokenize(source);
        for (GlslTokens.Token token : tokens) {
            assertEquals(token.text(), source.substring(token.start(), token.end()),
                    "token 的区间必须指回原文同一位置");
            assertEquals(1, token.line(), "单行样本的行号恒为 1");
        }
        assertEquals(GlslTokens.Kind.IDENT, tokens.get(0).kind());
        assertEquals("uniform", tokens.get(0).text());
    }

    @Test
    @DisplayName("注释与字符串在 codeViews 里被抹成空格但**长度不变**（等长同下标）")
    void codeViewsAreEqualLength() {
        String source = "int a; // 注释\nint b; /* 块 */ int c;\n";
        List<String> views = GlslTokens.codeViews(source, null);
        List<String> raw = GlslTokens.physicalLines(source);
        assertEquals(raw.size(), views.size(), "行数不变（诊断行号才对得上原文）");
        for (int i = 0; i < views.size(); i++) {
            assertEquals(raw.get(i).length(), views.get(i).length(),
                    "第 " + (i + 1) + " 行的视图必须与原始行等长");
            assertFalse(views.get(i).contains("注释"), "注释内容应被抹掉：" + views.get(i));
        }
        assertTrue(views.get(0).startsWith("int a;"), "代码部分原位保留：" + views.get(0));
    }

    @Test
    @DisplayName("🔴 续行判定只有一份实现：逻辑行拼接把 \\ 续行接起来并如实标 continued")
    void logicalLinesJoinContinuations() {
        String source = "#define A \\\n  1\nint x;\n";
        List<GlslTokens.LogicalLine> lines = GlslTokens.logicalLines(source);
        assertEquals(2, lines.size(), "两条物理行拼成一条逻辑行 + 一条普通行");
        assertTrue(lines.get(0).continued(), "拼接出来的那条必须标 continued");
        assertEquals(1, lines.get(0).firstLineNumber());
        assertTrue(lines.get(0).text().contains("#define A") && lines.get(0).text().contains("1"),
                "拼接结果应含两半，实测：" + lines.get(0).text());
        assertFalse(lines.get(1).continued());
    }

    @Test
    @DisplayName("行尾反斜杠后带尾随空格（包实测写法）也算续行")
    void trailingSpaceAfterBackslashCounts() {
        assertTrue(GlslTokens.endsWithLineContinuation("#define A \\".strip()));
        assertTrue(GlslTokens.endsWithLineContinuation("#define A \\   ".strip()),
                "strip 之后以 \\ 结尾即算续行 —— 这是旧两份实现共同的口径，合并后不许变");
        assertFalse(GlslTokens.endsWithLineContinuation("int a;"));
    }

    @Test
    @DisplayName("codeTokens 丢掉空白/换行/注释，但保留字符串（找声明时字符串要单独判）")
    void codeTokensDropTriviaOnly() {
        List<GlslTokens.Token> code = GlslTokens.codeTokens("a; // c\n/* b */ \"s\"");
        StringBuilder text = new StringBuilder();
        for (GlslTokens.Token token : code) {
            text.append(token.text());
        }
        assertEquals("a;\"s\"", text.toString(), "注释与空白被丢掉，字符串留下");
    }
}
