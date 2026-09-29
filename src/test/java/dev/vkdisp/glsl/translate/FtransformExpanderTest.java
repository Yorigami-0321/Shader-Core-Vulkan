package dev.vkdisp.glsl.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.vkdisp.glsl.TranslateDiagnostic;

/**
 * 【参考调研】D 线二期单测（ftransform 展开）/ GLSL 1.20 固定功能内建 + 04-SPEC §3.2 / §4
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/04-SPEC.md §3.2（gbufferProjection / gbufferModelView 的语义）、
 *    §4（位置属性字面名 Position）、docs/18-PARALLEL.md §4 D 线完成标准与 §7.3 证据规范 ——
 *    仓库内文档事实；另加 GLSL 官方公开语义（ftransform 是顶点阶段固定功能内建，
 *    等价于 gl_ModelViewProjectionMatrix * gl_Vertex）。
 *    外部候选 IrisShaders/glsl-transformer（GPL-3.0 + 例外条款）→ 按禁止处理
 *    （07-CONSTRAINTS L12 §1.3 陷阱 2 / X21），本任务不读其代码、零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 测试；样本全部为本任务自造（18-PARALLEL §7.6）。
 *    → 能否并入本项目（MIT）：可以（仅采纳不受版权保护的事实性信息）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：JUnit 5 字符串逐字比对 —— 展开式、位置属性名与类型的三种来源
 *    （Position / vaPosition / gl_Vertex）、未声明时的显式 WARN、阶段错误。
 * 2. 备选：无 —— 文本级转译用字符串断言最直观。
 * 3. 我们的差异点：矩阵名直接从冻结的 {@link UniformCatalog} 取，断言"目录即事实来源"；
 *    边界（注释 / 字符串 / 成员访问 / 跨行调用 / 带参数调用 / 幂等 / 恶意输入）逐条断言。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：测试代码不进运行时；冷路径无性能要求（18-PARALLEL §7.7）。
 */
/**
 * {@link FtransformExpander} 的单测：{@code ftransform()} → 显式矩阵乘展开的逐字比对与边界。
 */
class FtransformExpanderTest {

    @Test
    void ftransformIsExpandedGolden() {
        String source = """
                #version 120
                attribute vec3 Position;
                void main() {
                    gl_Position = ftransform();
                }
                """;
        FtransformExpander.Result result = FtransformExpander.expand(ShaderStage.VERTEX, source);
        assertEquals("""
                #version 120
                attribute vec3 Position;
                void main() {
                    gl_Position = (gbufferProjection * gbufferModelView * vec4(Position, 1.0));
                }
                """, result.text());
        assertEquals(1, result.expandedCount());
        assertEquals("vec4(Position, 1.0)", result.positionOperand());
        assertTrue(result.diagnostics().isEmpty(), "已声明 vec3 位置属性时不该有诊断："
                + result.diagnostics());
    }

    @Test
    void expansionUsesTheFrozenUniformCatalogNames() {
        String projection = UniformCatalog.find(FtransformExpander.PROJECTION_UNIFORM).name();
        String modelView = UniformCatalog.find(FtransformExpander.MODELVIEW_UNIFORM).name();
        FtransformExpander.Result result = FtransformExpander.expand(
                ShaderStage.VERTEX, "attribute vec3 Position;\nvoid main() { ftransform(); }\n");
        assertTrue(projection != null && modelView != null, "冻结目录必须含 gbufferProjection / gbufferModelView");
        assertTrue(result.text().contains(projection + " * " + modelView + " * "),
                "矩阵名以已冻结的 UniformCatalog 为准：" + result.text());
    }

    @Test
    void declaredVec4AttributeIsUsedWithoutPadding() {
        String source = "attribute vec4 gl_Vertex;\nvoid main() {\n    gl_Position = ftransform();\n}\n";
        FtransformExpander.Result result = FtransformExpander.expand(ShaderStage.VERTEX, source);
        assertTrue(result.text().contains(
                "gl_Position = (gbufferProjection * gbufferModelView * gl_Vertex);"), result.text());
        assertEquals("gl_Vertex", result.positionOperand(), "vec4 属性不能补 (…, 1.0)（会变成 5 个分量）");
    }

    @Test
    void declaredVaPositionIsHonoured() {
        String source = "attribute vec3 vaPosition;\nvoid main() {\n    gl_Position = ftransform();\n}\n";
        FtransformExpander.Result result = FtransformExpander.expand(ShaderStage.VERTEX, source);
        assertTrue(result.text().contains(
                "(gbufferProjection * gbufferModelView * vec4(vaPosition, 1.0))"), result.text());
        assertEquals("vec4(vaPosition, 1.0)", result.positionOperand());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void missingPositionAttributeDefaultsWithWarning() {
        String source = "void main() {\n    gl_Position = ftransform();\n}\n";
        FtransformExpander.Result result = FtransformExpander.expand(ShaderStage.VERTEX, source);
        assertTrue(result.text().contains("vec4(Position, 1.0)"));
        assertEquals(1, result.diagnostics().size(), result.diagnostics().toString());
        TranslateDiagnostic warning = result.diagnostics().get(0);
        assertEquals(TranslateDiagnostic.Severity.WARN, warning.severity());
        assertEquals(2, warning.line());
        assertTrue(warning.message().contains("Position"), warning.format());
    }

    @Test
    void nonVertexStageIsErrorAndTextUntouched() {
        String source = "void main() {\n    gl_Position = ftransform();\n}\n";
        FtransformExpander.Result fragment = FtransformExpander.expand(ShaderStage.FRAGMENT, source);
        assertEquals(source, fragment.text());
        assertEquals(0, fragment.expandedCount());
        assertEquals(TranslateDiagnostic.Severity.ERROR, fragment.diagnostics().get(0).severity());
        assertTrue(fragment.diagnostics().get(0).message().contains("片元阶段"));

        FtransformExpander.Result unknown = FtransformExpander.expand(ShaderStage.UNKNOWN, source);
        assertEquals(source, unknown.text(), "阶段未知时拒绝展开");
        assertEquals(TranslateDiagnostic.Severity.ERROR, unknown.diagnostics().get(0).severity());

        FtransformExpander.Result nullStage = FtransformExpander.expand(null, source);
        assertEquals(TranslateDiagnostic.Severity.ERROR, nullStage.diagnostics().get(0).severity());
    }

    @Test
    void callWithArgumentsIsErrorAndTextUntouched() {
        String source = "void main() {\n    gl_Position = ftransform(vec4(1.0));\n}\n";
        FtransformExpander.Result result = FtransformExpander.expand(ShaderStage.VERTEX, source);
        assertEquals(source, result.text());
        assertEquals(0, result.expandedCount());
        TranslateDiagnostic error = result.diagnostics().get(0);
        assertEquals(TranslateDiagnostic.Severity.ERROR, error.severity());
        assertTrue(error.message().contains("不接受参数"), error.format());
    }

    @Test
    void callSplitAcrossLinesWarnsAndTextUntouched() {
        String source = "void main() {\n    gl_Position = ftransform(\n        1.0);\n}\n";
        FtransformExpander.Result result = FtransformExpander.expand(ShaderStage.VERTEX, source);
        assertEquals(source, result.text());
        assertEquals(0, result.expandedCount());
        TranslateDiagnostic warning = result.diagnostics().get(0);
        assertEquals(TranslateDiagnostic.Severity.WARN, warning.severity());
        assertTrue(warning.message().contains("跨行"), warning.format());
    }

    @Test
    void commentsStringsAndMemberAccessAreUntouched() {
        String source = """
                // gl_Position = ftransform();
                /* ftransform(); */
                void main() {
                    "ftransform()";
                    obj.ftransform();
                }
                """;
        FtransformExpander.Result result = FtransformExpander.expand(ShaderStage.VERTEX, source);
        assertEquals(source, result.text());
        assertEquals(0, result.expandedCount());
        assertTrue(result.diagnostics().isEmpty(), "没有展开就不该有默认位置属性的 WARN："
                + result.diagnostics());
        assertNull(result.positionOperand());
    }

    @Test
    void expansionIsIdempotentOnItsOwnOutput() {
        String source = "attribute vec3 Position;\nvoid main() {\n    gl_Position = ftransform();\n}\n";
        FtransformExpander.Result first = FtransformExpander.expand(ShaderStage.VERTEX, source);
        FtransformExpander.Result second = FtransformExpander.expand(ShaderStage.VERTEX, first.text());
        assertEquals(first.text(), second.text(), "输出即不动点");
        assertEquals(0, second.expandedCount());
        assertTrue(second.diagnostics().isEmpty(), "第二遍没有调用可展开，不该再有诊断");
    }

    @Test
    void emptyAndNullInputAreUntouched() {
        FtransformExpander.Result empty = FtransformExpander.expand(ShaderStage.VERTEX, "");
        assertEquals("", empty.text());
        assertEquals(0, empty.expandedCount());
        assertNull(empty.positionOperand());
        assertTrue(empty.diagnostics().isEmpty());

        FtransformExpander.Result nullInput = FtransformExpander.expand(ShaderStage.VERTEX, null);
        assertEquals("", nullInput.text());
        assertTrue(nullInput.diagnostics().isEmpty());
    }

    @Test
    void hostileInputsNeverThrow() {
        List<String> hostile = List.of("", "\n", "ftransform", "ftransform(", "ftransform()",
                "ftransform());", "}", "#", "#define ftransform()", "attribute", "attribute vec3",
                "in vec3 Position;", "flat in vec4 Position;", "x.ftransform()", "\\u0000");
        for (String source : hostile) {
            FtransformExpander.Result result = FtransformExpander.expand(ShaderStage.VERTEX, source);
            assertNotNull(result, "任何输入都必须返回结果而不是抛异常：" + source);
        }
    }
}
