package dev.vkdisp.glsl.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 【参考调研】D 线旧内建声明注入单测（141 阶段矩阵 ① 类）/ GLSL 1.20 内建公开语义 + shaderc 实测原文
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 runClient 实测 shaderc 首错取证（141 阶段矩阵：'gl_MultiTexCoord0' /
 *    'gl_TextureMatrix' / 'Position' : undeclared identifier）与 docs/04-SPEC.md §4
 *    （Position 冻结字面名、vec3f、名字绑定）、GLSL 1.20 公开内建声明语义 —— 不受版权保护。
 *    外部候选 IrisShaders/glsl-transformer（GPL-3.0 + 例外条款，18-PARALLEL §4 D 线明示
 *    "按禁止处理"）→ 按禁止处理（07-CONSTRAINTS L12 §1.3 陷阱 2 / X21），零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 测试，样本全部为本任务自造（18-PARALLEL §7.6）。
 *    → 能否并入本项目（MIT）：可以（仅采纳不受版权保护的驱动报错事实与公开语言语义）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：JUnit 5 文本级断言 —— 使用驱动注入、阶段门、已声明三形态（裸 / layout 前缀 /
 *    块成员）幂等、注释与预处理不算使用、CRLF、确定性顺序、边界不崩溃。
 * 2. 备选：无 —— 文本断言最直接，不引入快照框架。
 * 3. 我们的差异点：断言口径把「幂等关键」写死 —— 第二遍输入是第一遍输出（含 ⑥ 的 location
 *    前缀与 ⑦ 的块成员形态），必须零插入、逐字节不变、零诊断。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：测试代码不进运行时；冷路径无性能要求（18-PARALLEL §7.7）。
 */
/**
 * {@link LegacyBuiltinInjector} 的单测：使用驱动注入的范围、阶段门、声明识别三形态与边界输入。
 */
class LegacyBuiltinInjectorTest {

    @Test
    void usedButUndeclaredVertexLegacyIsInjectedAtHeaderEnd() {
        String source = """
                #version 330 core
                // self-made sample (not from any third-party pack)
                void main() {
                    texCoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
                    normal = normalize(gl_NormalMatrix * gl_Normal);
                }
                """;
        LegacyBuiltinInjector.Result result = LegacyBuiltinInjector.inject(ShaderStage.VERTEX, source);
        List<String> lines = List.of(result.text().split("\n", -1));
        assertEquals("#version 330 core", lines.get(0), "头部原样保留");
        assertEquals("// self-made sample (not from any third-party pack)", lines.get(1),
                "注释仍属头部区（headerEnd 跳过空行 / 注释 / # 行）");
        assertEquals("in vec4 gl_MultiTexCoord0;", lines.get(2), "属性类裸 in 行插在首条代码之前");
        assertEquals("in vec3 gl_Normal;", lines.get(3), "候选表序（属性在前）");
        assertEquals("uniform mat4 gl_TextureMatrix[8];", lines.get(4), "矩阵类游离 uniform 行");
        assertEquals("uniform mat3 gl_NormalMatrix;", lines.get(5));
        assertEquals("void main() {", lines.get(6), "首条代码行随插入右移");
        assertEquals(2, result.insertIndex(), "插入点 = 头部区末尾（#version + 注释之后）");
        assertEquals(4, result.insertedLineCount());
        assertTrue(result.diagnostics().isEmpty(), () -> "成功注入零诊断：" + result.diagnostics());
        assertTrue(result.text().contains("    texCoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;"),
                "使用行逐字节保留");
    }

    @Test
    void matrixLegacyIsInjectedForFragmentButAttributesAreNot() {
        String source = """
                #version 330
                void main() {
                    c = gl_ProjectionMatrix[2][2] * gl_Color;
                }
                """;
        LegacyBuiltinInjector.Result result = LegacyBuiltinInjector.inject(ShaderStage.FRAGMENT, source);
        assertTrue(result.text().contains("uniform mat4 gl_ProjectionMatrix;"),
                "矩阵类任何阶段都注入");
        assertFalse(result.text().contains("in vec4 gl_Color;"),
                "片元阶段不注入顶点属性（没有该语义；真被使用时驱动显式报错 T11）");
        assertEquals(1, result.insertedLineCount());
    }

    @Test
    void alreadyDeclaredLegacyIsNotRedeclared() {
        String source = """
                #version 330
                in vec4 gl_MultiTexCoord0;
                void main() { x = gl_MultiTexCoord0.x; }
                """;
        LegacyBuiltinInjector.Result result = LegacyBuiltinInjector.inject(ShaderStage.VERTEX, source);
        assertEquals(source, result.text(), "已声明 → 零插入、逐字节相同");
        assertEquals(0, result.insertedLineCount());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void layoutPrefixedDeclarationCountsAsDeclaredForIdempotence() {
        // 第二遍输入 = 第一遍输出：⑥ 已给注入行补上 layout(location = N)。
        String source = """
                #version 410 core
                layout(location = 0) in vec4 gl_MultiTexCoord0;
                layout(location = 1) in vec3 Position;
                void main() { a = gl_MultiTexCoord0.x + vec4(Position, 1.0).y; }
                """;
        LegacyBuiltinInjector.Result result = LegacyBuiltinInjector.inject(ShaderStage.VERTEX, source);
        assertEquals(source, result.text(), "layout 前缀形态必须判已声明（GlslDeclaration.parse 对其返回 null，本级自建正则）");
        assertEquals(0, result.insertedLineCount());
    }

    @Test
    void blockMemberDeclarationCountsAsDeclaredForIdempotence() {
        // 第二遍输入：矩阵已被 ⑦ 收编进无实例名块。
        String source = """
                #version 410 core
                layout(std140) uniform VkDispBuiltins {
                mat4 gl_TextureMatrix[8];
                mat3 gl_NormalMatrix;
                };
                void main() { x = gl_TextureMatrix[0] * gl_NormalMatrix; }
                """;
        LegacyBuiltinInjector.Result result = LegacyBuiltinInjector.inject(ShaderStage.VERTEX, source);
        assertEquals(source, result.text(), "块成员形态必须判已声明");
        assertEquals(0, result.insertedLineCount());
    }

    @Test
    void commaSeparatedPackDeclarationCountsAsDeclared() {
        String source = """
                #version 330
                uniform mat4 gl_ProjectionMatrix, gl_ModelViewMatrix;
                void main() { x = gl_ProjectionMatrix * gl_ModelViewMatrix; }
                """;
        LegacyBuiltinInjector.Result result = LegacyBuiltinInjector.inject(ShaderStage.VERTEX, source);
        assertEquals(source, result.text(), "逗号多声明中的每个名字都算已声明");
        assertEquals(0, result.insertedLineCount());
    }

    @Test
    void unusedLegacyNamesAreNotInjected() {
        String source = """
                #version 330
                void main() { gl_Position = vec4(1.0); }
                """;
        LegacyBuiltinInjector.Result result = LegacyBuiltinInjector.inject(ShaderStage.VERTEX, source);
        assertEquals(source, result.text(), "未使用的名字一律不注入（使用驱动）");
        assertEquals(0, result.insertedLineCount());
        assertEquals(0, result.insertIndex());
    }

    @Test
    void commentsStringsAndPreprocessorBodiesAreNotUsages() {
        String source = """
                #version 330
                #define LEGACY gl_MultiTexCoord0
                // gl_Color mentioned in a comment
                void main() { x = 1.0; }
                """;
        LegacyBuiltinInjector.Result result = LegacyBuiltinInjector.inject(ShaderStage.VERTEX, source);
        assertEquals(source, result.text(), "注释 / 预处理指令体内的旧名不算使用");
        assertEquals(0, result.insertedLineCount());
    }

    @Test
    void longerIdentifiersAndMemberAccessAreNotUsages() {
        String source = """
                #version 330
                void main() {
                    x = vaPosition;
                    y = mygl_Normal;
                    z = object.gl_Color;
                }
                """;
        LegacyBuiltinInjector.Result result = LegacyBuiltinInjector.inject(ShaderStage.VERTEX, source);
        assertEquals(source, result.text(),
                "更长标识符的前缀、成员访问点之后的名字都不是旧内建 token");
        assertEquals(0, result.insertedLineCount());
    }

    @Test
    void frozenPositionOperandFromFtransformExpansionGetsDeclared() {
        // FtransformExpander 展开后的形态（自造样本，等价于 skybasic 首遍输出）。
        String source = """
                #version 410 core
                void main() {
                    gl_Position = (gbufferProjection * gbufferModelView * vec4(Position, 1.0));
                }
                """;
        LegacyBuiltinInjector.Result result = LegacyBuiltinInjector.inject(ShaderStage.VERTEX, source);
        List<String> lines = List.of(result.text().split("\n", -1));
        assertEquals("in vec3 Position;", lines.get(1), "冻结字面名补 vec3（04-SPEC §4）");
        assertEquals(1, result.insertedLineCount());
    }

    @Test
    void injectionOrderMatchesFrozenCandidateTable() {
        String source = """
                #version 330
                void main() { x = gl_Color + gl_MultiTexCoord0 + gl_ProjectionMatrix; }
                """;
        LegacyBuiltinInjector.Result result = LegacyBuiltinInjector.inject(ShaderStage.VERTEX, source);
        List<String> lines = List.of(result.text().split("\n", -1));
        assertEquals("in vec4 gl_MultiTexCoord0;", lines.get(1), "表序：MTEX0 先于 Color");
        assertEquals("in vec4 gl_Color;", lines.get(2));
        assertEquals("uniform mat4 gl_ProjectionMatrix;", lines.get(3), "矩阵在属性之后");
    }

    @Test
    void crlfLineEndingsArePreservedOnInsertedLines() {
        String source = "#version 330\r\nvoid main() { x = gl_MultiTexCoord0.x; }\r\n";
        LegacyBuiltinInjector.Result result = LegacyBuiltinInjector.inject(ShaderStage.VERTEX, source);
        assertTrue(result.text().startsWith("#version 330\r\nin vec4 gl_MultiTexCoord0;\r\n"),
                "插入行按文件主行尾风格补齐 \\r");
        assertFalse(result.text().replace("\r\n", "").contains("\n"), "不许混入裸 LF");
    }

    @Test
    void injectionIsIdempotentOnTextAndDiagnostics() {
        String source = """
                #version 330
                void main() { x = gl_TextureMatrix[0] * gl_Vertex; }
                """;
        LegacyBuiltinInjector.Result first = LegacyBuiltinInjector.inject(ShaderStage.VERTEX, source);
        assertTrue(first.insertedLineCount() > 0, "首遍必须注入");
        LegacyBuiltinInjector.Result second = LegacyBuiltinInjector.inject(ShaderStage.VERTEX, first.text());
        assertEquals(first.text(), second.text(), "第二遍逐字节不变（裸形态判已声明）");
        assertEquals(0, second.insertedLineCount());
        assertTrue(second.diagnostics().isEmpty(), () -> "第二遍零诊断：" + second.diagnostics());
    }

    @Test
    void nullAndHostileInputsNeverThrow() {
        List<String> hostile = List.of("", "\n", "/*", "*/", "in", "uniform",
                "layout(location = 0) in", "attribute vec4", "#", "gl_MultiTexCoord0",
                "    mat3 gl_NormalMatrix", "\\u0000\\u0001");
        for (String source : hostile) {
            LegacyBuiltinInjector.Result result = LegacyBuiltinInjector.inject(ShaderStage.VERTEX, source);
            assertNotNull(result, "任何输入都必须返回结果而不是抛异常：" + source);
        }
        assertEquals("", LegacyBuiltinInjector.inject(ShaderStage.VERTEX, (String) null).text());
    }
}
