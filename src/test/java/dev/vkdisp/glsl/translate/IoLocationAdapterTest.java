package dev.vkdisp.glsl.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.vkdisp.glsl.TranslateDiagnostic;

/**
 * 【参考调研】D 线 in/out location 补写单测（P4.1.2 驱动层）/ 04-SPEC §4 + shaderc 实测原文
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 runClient 实测 shaderc 原文（2026-09-30，/tmp/p41a_runclient.log）：
 *    {@code 'location' : SPIR-V requires location for user input/output} 与
 *    {@code 'location' : not supported for this version or the enabled extensions} ——
 *    驱动报错事实；docs/04-SPEC.md §4「字段名必须与着色器里的 attribute 声明完全一致」
 *    （顶点属性按名字绑定 → 顶点 in 不注入 location）—— 仓库内文档事实。均不受版权保护。
 *    外部候选 IrisShaders/glsl-transformer（GPL-3.0 + 例外条款，18-PARALLEL §4 明示"按禁止处理"）→
 *    按禁止处理（07-CONSTRAINTS L12 §1.3 陷阱 2 / X21），零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 测试，样本全部为本任务自造（18-PARALLEL §7.6）。
 *    → 能否并入本项目（MIT）：可以（仅采纳不受版权保护的事实性信息）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：JUnit 5 断言"片元 in/out 与顶点 out 补 location、顶点 in 跳过、in/out 独立
 *    计数、已有 layout 占号、逗号拆语句、行数恒等、幂等、边界不崩溃"八件事。
 * 2. 备选：无 —— 文本级断言足够，不引入快照框架。
 * 3. 我们的差异点：边界用例（同行多语句 WARN / 注释保护 / 前置限定符 / 接口块 / null 阶段 /
 *    CRLF / 未闭合注释）全部显式断言，对应 18-PARALLEL §7.3 的边界用例清单要求。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：测试代码不进运行时；冷路径无性能要求（18-PARALLEL §7.7）。
 */
/**
 * {@link IoLocationAdapter} 的单测：按阶段补 location 的范围、编号规则、逗号拆分与边界输入。
 */
class IoLocationAdapterTest {

    @Test
    void fragmentInputsGetSequentialLocations() {
        String source = """
                #version 330
                in vec2 texCoord;
                in vec3 lightDir, upDir;
                void main() {}
                """;
        IoLocationAdapter.Result result = IoLocationAdapter.locate(ShaderStage.FRAGMENT, source);
        List<String> lines = List.of(result.text().split("\n", -1));
        assertEquals("layout(location = 0) in vec2 texCoord;", lines.get(1),
                "首条片元输入落 0（本仓库全屏 VS 的 vUv 也在 0 —— 接口契约）");
        assertEquals("layout(location = 1) in vec3 lightDir; layout(location = 2) in vec3 upDir;",
                lines.get(2), "逗号多声明名同行拆语句，各自独占 location");
        assertEquals(2, result.locatedCount(), "拆语句按 1 行算");
        assertTrue(result.diagnostics().isEmpty(), () -> "成功补写无诊断：" + result.diagnostics());
        assertEquals(source.split("\n", -1).length, result.text().split("\n", -1).length,
                "等行数（行号映射不被切断）");
    }

    @Test
    void inAndOutAreNumberedSeparately() {
        String source = "in vec2 a;\nout vec4 b;\n";
        IoLocationAdapter.Result result = IoLocationAdapter.locate(ShaderStage.FRAGMENT, source);
        assertEquals("layout(location = 0) in vec2 a;\nlayout(location = 0) out vec4 b;\n",
                result.text(), "in / out 两个方向各自独立从 0 起");
        assertEquals(2, result.locatedCount());
    }

    @Test
    void existingLocationsSeedAndReserve() {
        String source = """
                layout(location = 0) in vec2 uv;
                in vec3 extra;
                out vec4 c;
                out vec4 d;
                """;
        IoLocationAdapter.Result result = IoLocationAdapter.locate(ShaderStage.FRAGMENT, source);
        List<String> lines = List.of(result.text().split("\n", -1));
        assertEquals("layout(location = 0) in vec2 uv;", lines.get(0), "已有 layout 的声明原样");
        assertEquals("layout(location = 1) in vec3 extra;", lines.get(1),
                "已有 0 占号 → 后补的 in 取最小未占用 1");
        assertEquals("layout(location = 0) out vec4 c;", lines.get(2));
        assertEquals("layout(location = 1) out vec4 d;", lines.get(3));
    }

    @Test
    void vertexAttributesAreSkippedButVertexOutputsAreLocated() {
        String source = """
                in vec3 Position;
                in vec4 mc_Entity;
                out vec2 vUv;
                """;
        IoLocationAdapter.Result result = IoLocationAdapter.locate(ShaderStage.VERTEX, source);
        List<String> lines = List.of(result.text().split("\n", -1));
        assertEquals("in vec3 Position;", lines.get(0),
                "顶点属性按 04-SPEC §4 名字绑定，不注入 location");
        assertEquals("in vec4 mc_Entity;", lines.get(1), "同上");
        assertEquals("layout(location = 0) out vec2 vUv;", lines.get(2),
                "顶点 out（跨阶段 varying）照补");
        assertEquals(1, result.locatedCount());
    }

    @Test
    void nullStageIsTreatedAsNonVertex() {
        IoLocationAdapter.Result result = IoLocationAdapter.locate(null, "in vec2 a;\n");
        assertEquals("layout(location = 0) in vec2 a;\n", result.text(),
                "null 阶段按非顶点处理（javadoc 口径）：in 照补");
        assertEquals(1, result.locatedCount());
    }

    @Test
    void alreadyLocatedInputIsIdempotent() {
        String source = """
                #version 330
                layout(location = 0) in vec2 texCoord;
                layout(location = 1) in vec3 lightDir;
                out vec4 fragColor;
                """;
        IoLocationAdapter.Result first = IoLocationAdapter.locate(ShaderStage.FRAGMENT, source);
        IoLocationAdapter.Result second = IoLocationAdapter.locate(ShaderStage.FRAGMENT, first.text());
        assertEquals(first.text(), second.text(), "第二遍必须逐字节相同");
        assertEquals(0, second.locatedCount());
        assertTrue(second.diagnostics().isEmpty(), () -> "第二遍不该有诊断：" + second.diagnostics());
    }

    @Test
    void multiStatementLineIsLeftAloneWithWarning() {
        String source = "out vec4 frag; float k = 1.0;\n";
        IoLocationAdapter.Result result = IoLocationAdapter.locate(ShaderStage.FRAGMENT, source);
        assertEquals(source, result.text(), "同行多语句不改写（抹行会连带动别的语句）");
        assertEquals(0, result.locatedCount());
        assertTrue(result.diagnostics().stream().anyMatch(d ->
                        d.severity() == TranslateDiagnostic.Severity.WARN
                                && d.line() == 1 && d.message().contains("同行多语句")),
                () -> "必须 WARN 可见（T11）：" + result.diagnostics());
    }

    @Test
    void uniformAndPreprocessorLinesAreUntouched() {
        String source = """
                #define FOO 1
                uniform float rainStrength;
                void main() {}
                """;
        IoLocationAdapter.Result result = IoLocationAdapter.locate(ShaderStage.FRAGMENT, source);
        assertEquals(source, result.text(), "uniform / 预处理行不属于跨阶段 IO");
        assertEquals(0, result.locatedCount());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void leadingQualifierIsPreservedAfterLayout() {
        String source = "flat in vec3 n;\n";
        IoLocationAdapter.Result result = IoLocationAdapter.locate(ShaderStage.FRAGMENT, source);
        assertEquals("layout(location = 0) flat in vec3 n;\n", result.text(),
                "layout 插在插值限定符之前（GLSL 合法顺序），限定符原样保留");
        assertEquals(1, result.locatedCount());
    }

    @Test
    void interfaceBlockOpenGetsLocationButMembersDoNot() {
        String source = """
                in IoBlock {
                    vec3 v;
                };
                """;
        IoLocationAdapter.Result result = IoLocationAdapter.locate(ShaderStage.FRAGMENT, source);
        List<String> lines = List.of(result.text().split("\n", -1));
        assertEquals("layout(location = 0) in IoBlock {", lines.get(0), "块开行整体占一个 location");
        assertEquals("    vec3 v;", lines.get(1), "成员行不重复分配 location");
        assertEquals("};", lines.get(2));
        assertEquals(1, result.locatedCount());
    }

    @Test
    void commentBeforeAndInsideCommaDeclarationIsPreserved() {
        String source = "/*c*/ in vec2 a;\nin vec3 /*note*/ lightDir, upDir;\n";
        IoLocationAdapter.Result result = IoLocationAdapter.locate(ShaderStage.FRAGMENT, source);
        assertEquals("/*c*/ layout(location = 0) in vec2 a;\n"
                        + "layout(location = 1) in vec3 /*note*/ lightDir; layout(location = 2) in vec3 upDir;\n",
                result.text(), "注释字节原位保留（等长视图切片，同下标落在原始行上）");
        assertEquals(2, result.locatedCount());
    }

    @Test
    void wholeLineCommentIsUntouched() {
        String source = "// in vec2 a;\nvoid main() {}\n";
        IoLocationAdapter.Result result = IoLocationAdapter.locate(ShaderStage.FRAGMENT, source);
        assertEquals(source, result.text());
        assertEquals(0, result.locatedCount());
    }

    @Test
    void crlfIsPreservedWithoutBareLf() {
        String source = "in vec2 a;\r\nin vec3 lightDir, upDir;\r\n";
        IoLocationAdapter.Result result = IoLocationAdapter.locate(ShaderStage.FRAGMENT, source);
        assertTrue(result.text().startsWith("layout(location = 0) in vec2 a;\r\n"),
                "CRLF 单名声明补写后行尾原样");
        assertTrue(result.text().contains(
                        "layout(location = 1) in vec3 lightDir; layout(location = 2) in vec3 upDir;\r\n"),
                "CRLF 逗号拆分后行尾原样（\\r 随尾段一起保留）");
        assertTrue(!result.text().replace("\r\n", "").contains("\n"), "不许混入裸 LF");
    }

    @Test
    void unterminatedBlockCommentReportsErrorWithoutThrowing() {
        String source = "in vec2 a;\n/* open\n";
        IoLocationAdapter.Result result = IoLocationAdapter.locate(ShaderStage.FRAGMENT, source);
        assertTrue(result.diagnostics().stream().anyMatch(d ->
                        d.severity() == TranslateDiagnostic.Severity.ERROR
                                && d.message().contains("块注释未闭合")),
                () -> "未闭合块注释必须 ERROR（T11）：" + result.diagnostics());
        assertTrue(result.text().contains("layout(location = 0) in vec2 a;"),
                "首行照常补写，诊断不吞输出");
    }

    @Test
    void nullAndEmptySourceAreSafe() {
        assertEquals("", IoLocationAdapter.locate(ShaderStage.FRAGMENT, null).text());
        assertEquals("", IoLocationAdapter.locate(ShaderStage.VERTEX, "").text());
        assertEquals(0, IoLocationAdapter.locate(ShaderStage.FRAGMENT, null).locatedCount());
        assertTrue(IoLocationAdapter.locate(ShaderStage.FRAGMENT, null).diagnostics().isEmpty());
    }
}
