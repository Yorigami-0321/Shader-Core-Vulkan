package dev.vkdisp.glsl.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.vkdisp.glsl.TranslateDiagnostic;

/**
 * 【参考调研】D 线单测（attribute/varying 重写）/ 04-SPEC §3.3 与 GLSL 限定符公开语义
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/04-SPEC.md §3.3 / §4、docs/18-PARALLEL.md §4 D 线完成标准 ——
 *    仓库内文档事实，不受版权保护。外部候选 IrisShaders/glsl-transformer（GPL-3.0 + 例外条款）→
 *    按禁止处理（07-CONSTRAINTS L12 §1.3 陷阱 2 / X21），本任务不读其代码、零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 测试，不含任何外部项目代码；样本全部为本任务自造
 *    （18-PARALLEL §7.6：禁止把第三方 pack 的 .glsl 片段复制进单测预期值）。
 *    → 能否并入本项目（MIT）：可以（仅采纳不受版权保护的事实性信息）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：JUnit 5 逐字符比对（18-PARALLEL §4 D 线证据要求"输入 OF 方言样本 → 输出与预期
 *    字符串比对"），覆盖顶点/片元两个方向的限定符映射与边界输入。
 * 2. 备选：无 —— 文本级转译用字符串断言最直观，不引入 golden-file 框架。
 * 3. 我们的差异点：边界用例（空输入 / 无 varying / 重复声明 / 半截声明 / 未闭合注释 / CRLF）
 *    全部显式断言"诊断而非崩溃"，对应 18-PARALLEL §7.3 的边界用例清单要求。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：测试代码不进运行时；冷路径无性能要求（18-PARALLEL §7.7）。
 */
/**
 * {@link AttributeRewriter} 的单测：限定符映射、行内替换的保真性、以及边界输入必须出诊断而非崩溃。
 */
class AttributeRewriterTest {

    private static final String OF_SAMPLE = """
            attribute vec4 mc_Entity;
            varying vec3 vNormal;
            void main() {
                vNormal = vec3(1.0);
            }
            """;

    @Test
    void vertexAttributeBecomesIn() {
        AttributeRewriter.Result result = AttributeRewriter.rewrite(ShaderStage.VERTEX, "attribute vec4 mc_Entity;\n");
        assertEquals("in vec4 mc_Entity;\n", result.text());
        assertEquals(1, result.rewrittenCount());
        assertTrue(result.diagnostics().isEmpty(), "正常重写不该产生诊断（成功不刷噪声）");
    }

    @Test
    void vertexVaryingBecomesOut() {
        AttributeRewriter.Result result = AttributeRewriter.rewrite(ShaderStage.VERTEX, "varying vec3 vNormal;\n");
        assertEquals("out vec3 vNormal;\n", result.text());
        assertEquals(1, result.rewrittenCount());
    }

    @Test
    void fragmentVaryingBecomesIn() {
        AttributeRewriter.Result result = AttributeRewriter.rewrite(ShaderStage.FRAGMENT, "varying vec3 vNormal;\n");
        assertEquals("in vec3 vNormal;\n", result.text());
        assertEquals(1, result.rewrittenCount());
    }

    @Test
    void fullOfSampleIsRewrittenDirectionally() {
        AttributeRewriter.Result result = AttributeRewriter.rewrite(ShaderStage.VERTEX, OF_SAMPLE);
        List<String> lines = List.of(result.text().split("\n", -1));
        assertEquals("in vec4 mc_Entity;", lines.get(0));
        assertEquals("out vec3 vNormal;", lines.get(1));
        assertEquals("void main() {", lines.get(2), "非声明行必须一字不动");
        assertEquals("    vNormal = vec3(1.0);", lines.get(3), "缩进必须保留");
        assertEquals(2, result.rewrittenCount());
    }

    @Test
    void fragmentAttributeIsErrorAndLineIsLeftUntouched() {
        AttributeRewriter.Result result = AttributeRewriter.rewrite(ShaderStage.FRAGMENT, "attribute vec4 Color;\n");
        assertEquals("attribute vec4 Color;\n", result.text(), "非法声明不重写，交给诊断显式失败");
        assertEquals(0, result.rewrittenCount());
        assertEquals(1, result.diagnostics().size());
        TranslateDiagnostic diagnostic = result.diagnostics().get(0);
        assertEquals(TranslateDiagnostic.Severity.ERROR, diagnostic.severity());
        assertEquals(1, diagnostic.line());
        assertTrue(diagnostic.message().contains("片元阶段"), "错误信息必须说清原因：" + diagnostic.format());
    }

    @Test
    void unknownStageWithDeclarationIsErrorWithoutGuessing() {
        AttributeRewriter.Result result = AttributeRewriter.rewrite(ShaderStage.UNKNOWN, "varying vec3 v;\n");
        assertEquals("varying vec3 v;\n", result.text(), "阶段未知时拒绝猜测方向");
        assertEquals(TranslateDiagnostic.Severity.ERROR, result.diagnostics().get(0).severity());
        assertTrue(result.diagnostics().get(0).message().contains("未知"));
    }

    @Test
    void unknownStageWithoutDeclarationIsPurePassthrough() {
        String source = "void main() {\n    gl_Position = vec4(0.0);\n}\n";
        AttributeRewriter.Result result = AttributeRewriter.rewrite(ShaderStage.UNKNOWN, source);
        assertEquals(source, result.text());
        assertEquals(0, result.rewrittenCount());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void leadingInterpolationQualifierIsHandled() {
        AttributeRewriter.Result result = AttributeRewriter.rewrite(
                ShaderStage.VERTEX, "flat varying ivec3 blockId;\n");
        assertEquals("flat out ivec3 blockId;\n", result.text());
        assertEquals(1, result.rewrittenCount());
    }

    @Test
    void indentationAndTrailingCommentArePreserved() {
        AttributeRewriter.Result result = AttributeRewriter.rewrite(
                ShaderStage.VERTEX, "    varying vec3 v; // keep me\n");
        assertEquals("    out vec3 v; // keep me\n", result.text());
    }

    @Test
    void commentsAndPreprocessorLinesAreUntouched() {
        String source = """
                // varying vec3 inLineComment;
                /* varying vec3 inBlockComment; */
                #define SCALE varying vec3 inMacro;
                varying vec3 realOne;
                """;
        AttributeRewriter.Result result = AttributeRewriter.rewrite(ShaderStage.VERTEX, source);
        List<String> lines = List.of(result.text().split("\n", -1));
        assertEquals("// varying vec3 inLineComment;", lines.get(0));
        assertEquals("/* varying vec3 inBlockComment; */", lines.get(1));
        assertEquals("#define SCALE varying vec3 inMacro;", lines.get(2));
        assertEquals("out vec3 realOne;", lines.get(3));
        assertEquals(1, result.rewrittenCount(), "只有真正的声明被重写");
    }

    @Test
    void inlineBlockCommentBeforeDeclarationKeepsColumnPositions() {
        AttributeRewriter.Result result = AttributeRewriter.rewrite(
                ShaderStage.VERTEX, "/* keep */ varying vec3 v; // tail\n");
        assertEquals("/* keep */ out vec3 v; // tail\n", result.text(),
                "注释被替换为等长空格，因此关键字列位置可直接复用到原始行");
        assertEquals(1, result.rewrittenCount());
    }

    @Test
    void multiLineBlockCommentIsSkipped() {
        String source = """
                varying vec3 first;
                /*
                varying vec3 insideComment;
                */
                varying vec3 second;
                """;
        AttributeRewriter.Result result = AttributeRewriter.rewrite(ShaderStage.VERTEX, source);
        List<String> lines = List.of(result.text().split("\n", -1));
        assertEquals("out vec3 first;", lines.get(0));
        assertEquals("varying vec3 insideComment;", lines.get(2), "块注释内部一字不动");
        assertEquals("out vec3 second;", lines.get(4));
        assertEquals(2, result.rewrittenCount());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void duplicateDeclarationWarnsButKeepsBothRewritten() {
        AttributeRewriter.Result result = AttributeRewriter.rewrite(
                ShaderStage.VERTEX, "varying vec3 v;\nvarying vec3 v;\n");
        assertEquals("out vec3 v;\nout vec3 v;\n", result.text());
        assertEquals(2, result.rewrittenCount());
        assertEquals(1, result.diagnostics().size());
        TranslateDiagnostic diagnostic = result.diagnostics().get(0);
        assertEquals(TranslateDiagnostic.Severity.WARN, diagnostic.severity());
        assertEquals(2, diagnostic.line(), "诊断必须指向重复出现的那一行");
        assertFalse(diagnostic.severity().isError(), "重复声明是 WARN，结果仍可用（T11 显式可见）");
    }

    @Test
    void halfDeclarationsAreErrorsNotCrashes() {
        AttributeRewriter.Result missingName = AttributeRewriter.rewrite(ShaderStage.VERTEX, "varying vec3;\n");
        assertEquals(TranslateDiagnostic.Severity.ERROR, missingName.diagnostics().get(0).severity());
        assertEquals("varying vec3;\n", missingName.text());

        AttributeRewriter.Result missingType = AttributeRewriter.rewrite(ShaderStage.VERTEX, "attribute;\n");
        assertEquals(TranslateDiagnostic.Severity.ERROR, missingType.diagnostics().get(0).severity());
        assertEquals("attribute;\n", missingType.text());
    }

    @Test
    void declarationWithoutSemicolonWarnsButStillRewrites() {
        AttributeRewriter.Result result = AttributeRewriter.rewrite(ShaderStage.VERTEX, "varying vec3 v\n");
        assertEquals("out vec3 v\n", result.text());
        assertEquals(1, result.rewrittenCount());
        assertEquals(TranslateDiagnostic.Severity.WARN, result.diagnostics().get(0).severity());
        assertTrue(result.diagnostics().get(0).message().contains("分号"));
    }

    @Test
    void unterminatedBlockCommentIsError() {
        AttributeRewriter.Result result = AttributeRewriter.rewrite(ShaderStage.VERTEX, "varying vec3 v;\n/* open\n");
        assertTrue(result.text().startsWith("out vec3 v;"), "注释之前的声明已重写");
        TranslateDiagnostic first = result.diagnostics().get(0);
        assertEquals(TranslateDiagnostic.Severity.ERROR, first.severity());
        assertEquals(2, first.line());
        assertTrue(first.message().contains("未闭合"));
    }

    @Test
    void emptyAndNullInputAreUntouched() {
        AttributeRewriter.Result empty = AttributeRewriter.rewrite(ShaderStage.VERTEX, "");
        assertEquals("", empty.text());
        assertEquals(0, empty.rewrittenCount());
        assertTrue(empty.diagnostics().isEmpty());

        AttributeRewriter.Result nullInput = AttributeRewriter.rewrite(ShaderStage.VERTEX, null);
        assertEquals("", nullInput.text());
        assertTrue(nullInput.diagnostics().isEmpty());
    }

    @Test
    void crlfLineEndingsArePreserved() {
        AttributeRewriter.Result result = AttributeRewriter.rewrite(
                ShaderStage.VERTEX, "varying vec3 v;\r\nvoid main() {}\r\n");
        assertEquals("out vec3 v;\r\nvoid main() {}\r\n", result.text());
    }

    @Test
    void rewritingIsIdempotentOnItsOwnOutput() {
        AttributeRewriter.Result first = AttributeRewriter.rewrite(ShaderStage.VERTEX, OF_SAMPLE);
        AttributeRewriter.Result second = AttributeRewriter.rewrite(ShaderStage.VERTEX, first.text());
        assertEquals(first.text(), second.text(), "输出即不动点");
        assertEquals(0, second.rewrittenCount());
        assertTrue(second.diagnostics().isEmpty());
    }
}
