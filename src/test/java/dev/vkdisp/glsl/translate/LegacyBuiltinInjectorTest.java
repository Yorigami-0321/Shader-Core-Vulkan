package dev.vkdisp.glsl.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 【参考调研】D 线旧内建「替换 + 注入」单测（141 阶段矩阵 ① 类 + reserved 第二波）/
 * GLSL 1.20 内建公开语义 + shaderc 实测原文
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 runClient 实测 shaderc 两阶段取证：① 141 阶段矩阵首错
 *    （'gl_MultiTexCoord0' / 'gl_TextureMatrix' / 'Position' : undeclared identifier）；
 *    ② 补声明后的第二跑（identifiers starting with "gl_" are reserved ×91 —— gl_ 前缀
 *    被 GLSL 公开词法保留，注入 gl_ 声明必被驱动拒）与 docs/04-SPEC.md §4
 *    （Position 冻结字面名、名字绑定）、GLSL 1.20 公开内建声明语义 —— 均不受版权保护。
 *    外部候选 IrisShaders/glsl-transformer（GPL-3.0 + 例外条款，18-PARALLEL §4 D 线明示
 *    "按禁止处理"）→ 按禁止处理（07-CONSTRAINTS L12 §1.3 陷阱 2 / X21），零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 测试，样本全部为本任务自造（18-PARALLEL §7.6）。
 *    → 能否并入本项目（MIT）：可以（仅采纳不受版权保护的驱动报错事实与公开语言语义）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：JUnit 5 文本级断言 —— token 级等行替换（属性类仅顶点、矩阵类任何阶段）、
 *    注入的使用驱动门与已声明识别三形态（裸 / layout 前缀 / 块成员）幂等、声明行保护
 *    （表达式型替换不落进声明名位）、注释与预处理不算使用、裸 gl_TextureMatrix 与
 *    gl_ 残留显式可见（T11）、CRLF、确定性注入顺序、边界不崩溃。
 * 2. 备选：无 —— 文本断言最直接，不引入快照框架。
 * 3. 我们的差异点：断言口径把「替换后零 gl_ token」「幂等关键」写死 —— 第二遍输入是
 *    第一遍输出（含 ⑥ 的 location 前缀），必须零替换、零插入、逐字节不变、零诊断。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：测试代码不进运行时；冷路径无性能要求（18-PARALLEL §7.7）。
 */
/**
 * {@link LegacyBuiltinInjector} 的单测：替换表语义、阶段门、声明行保护、注入门与边界输入。
 */
class LegacyBuiltinInjectorTest {

    /**
     * 主路径：属性 / 矩阵旧名等行替换为语义等价合法名，「用而未声明」的属性名注入裸 in 行；
     * 输出零 {@code gl_} token。
     */
    @Test
    void legacyAttributesAreSubstitutedAndUndeclaredOnesInjected() {
        String source = """
                #version 330 core
                // self-made sample (not from any third-party pack)
                void main() {
                    texCoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
                    normal = normalize(gl_NormalMatrix * gl_Normal);
                }
                """;
        LegacyBuiltinInjector.Result result =
                LegacyBuiltinInjector.inject(ShaderStage.VERTEX, source);
        List<String> lines = List.of(result.text().split("\n", -1));
        assertEquals("#version 330 core", lines.get(0), "头部原样保留");
        assertEquals("// self-made sample (not from any third-party pack)", lines.get(1),
                "注释仍属头部区（headerEnd 跳过空行 / 注释 / # 行）");
        assertEquals("in vec4 UV0;", lines.get(2), "用而未声明的属性换名后注入裸 in 行");
        assertEquals("in vec3 Normal;", lines.get(3), "候选表序（属性在前；矩阵不注入）");
        assertEquals("void main() {", lines.get(4), "首条代码行随插入右移");
        assertEquals(2, result.insertIndex(), "插入点 = 头部区末尾（#version + 注释之后）");
        assertEquals(2, result.insertedLineCount());
        assertTrue(result.diagnostics().isEmpty(),
                () -> "成功替换 / 注入零诊断：" + result.diagnostics());
        assertTrue(result.text().contains(
                        "    texCoord = (mat4(1.0) * UV0).xy;"),
                "gl_TextureMatrix[0] → 单位阵（下标随 token 消费）、gl_MultiTexCoord0 → UV0："
                        + result.text());
        assertTrue(result.text().contains(
                        "    normal = normalize((transpose(inverse(mat3(gbufferModelView)))) * Normal);"),
                "gl_NormalMatrix → 法线矩阵公开定义、gl_Normal → Normal");
        assertFalse(result.text().contains("gl_"),
                "替换后不允许残留任何 gl_ token（gl_ 前缀保留，注入声明必被驱动拒）");
    }

    /** 属性类替换与注入仅顶点阶段；矩阵类替换任何阶段都做（片元保留属性旧名交驱动显式报错 T11）。 */
    @Test
    void attributeSubstitutionIsVertexOnlyWhileMatricesAreStageAgnostic() {
        String source = """
                #version 330
                void main() {
                    c = gl_ProjectionMatrix[2][2] * gl_Color;
                }
                """;
        LegacyBuiltinInjector.Result result =
                LegacyBuiltinInjector.inject(ShaderStage.FRAGMENT, source);
        assertTrue(result.text().contains("gbufferProjection[2][2] * gl_Color"),
                "矩阵类任何阶段替换；片元属性旧名保留（无该语义，驱动显式报错 T11）："
                        + result.text());
        assertFalse(result.text().contains("in vec4"),
                "片元阶段零注入（没有顶点属性语义）");
        assertEquals(0, result.insertedLineCount());
        assertEquals(0, result.insertIndex(), "⑤ 零插入 → 行号映射恒等");
        assertTrue(result.diagnostics().isEmpty());
    }

    /** 注释 / 字符串 / 预处理指令体内的旧名不算使用（替换与注入都不触发）。 */
    @Test
    void commentsStringsAndPreprocessorBodiesAreNotUsages() {
        String source = """
                #version 330
                #define LEGACY gl_MultiTexCoord0
                // gl_Color mentioned in a comment
                void main() { x = 1.0; }
                """;
        LegacyBuiltinInjector.Result result =
                LegacyBuiltinInjector.inject(ShaderStage.VERTEX, source);
        assertEquals(source, result.text(), "注释 / 预处理指令体内的旧名不算使用");
        assertEquals(0, result.insertedLineCount());
        assertTrue(result.diagnostics().isEmpty());
    }

    /** 代码里真用了旧名时，注释里的 gl_ 字样原样保留（等行改写只动无注释视图的 token 区间）。 */
    @Test
    void commentBytesArePreservedWhenCodeUsesLegacyNames() {
        String source = """
                #version 330
                void main() {
                    x = gl_Color; // keep gl_Color text in this comment
                }
                """;
        LegacyBuiltinInjector.Result result =
                LegacyBuiltinInjector.inject(ShaderStage.VERTEX, source);
        assertTrue(result.text().contains("// keep gl_Color text in this comment"),
                "注释字节原位保留：" + result.text());
        assertTrue(result.text().contains("x = Color;"), "代码 token 替换：" + result.text());
        assertTrue(result.text().contains("in vec4 Color;"), "Color 用而未声明 → 注入");
    }

    /** 未使用的旧名一律不动（使用驱动：零替换、零插入、逐字节相同）。 */
    @Test
    void unusedLegacyNamesLeaveTextByteIdentical() {
        String source = """
                #version 330
                void main() { gl_Position = vec4(1.0); }
                """;
        LegacyBuiltinInjector.Result result =
                LegacyBuiltinInjector.inject(ShaderStage.VERTEX, source);
        assertEquals(source, result.text(), "未使用的名字一律不改（使用驱动）");
        assertEquals(0, result.insertedLineCount());
        assertEquals(0, result.insertIndex());
    }

    /** 更长标识符的前缀、成员访问点之后的名字都不是旧内建 token。 */
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
        LegacyBuiltinInjector.Result result =
                LegacyBuiltinInjector.inject(ShaderStage.VERTEX, source);
        assertEquals(source, result.text(),
                "更长标识符的前缀、成员访问点之后的名字都不是旧内建 token");
        assertEquals(0, result.insertedLineCount());
    }

    /** ftransform 展开（或任何来源）引入的冻结 Position：用而未声明 → 注入 vec3 声明。 */
    @Test
    void frozenPositionOperandFromFtransformExpansionGetsDeclared() {
        // FtransformExpander 展开后的形态（自造样本，等价于 skybasic 首遍输出）。
        String source = """
                #version 410 core
                void main() {
                    gl_Position = (gbufferProjection * gbufferModelView * vec4(Position, 1.0));
                }
                """;
        LegacyBuiltinInjector.Result result =
                LegacyBuiltinInjector.inject(ShaderStage.VERTEX, source);
        List<String> lines = List.of(result.text().split("\n", -1));
        assertEquals("in vec3 Position;", lines.get(1), "冻结字面名补 vec3（04-SPEC §4）");
        assertEquals(1, result.insertedLineCount());
        assertTrue(result.diagnostics().isEmpty());
    }

    /** 包内已声明 vec4 位置属性：gl_Vertex 声明行与使用处都换成合法名 Position，零注入。 */
    @Test
    void glVertexDeclaredAsVec4IsRenamedToPositionNotInjected() {
        String source = """
                #version 330
                in vec4 gl_Vertex;
                void main() { gl_Position = gl_Vertex; }
                """;
        LegacyBuiltinInjector.Result result =
                LegacyBuiltinInjector.inject(ShaderStage.VERTEX, source);
        assertTrue(result.text().contains("in vec4 Position;"),
                "声明行是名字上下文：gl_Vertex → Position（不落表达式）：" + result.text());
        assertTrue(result.text().contains("gl_Position = Position;"),
                "vec4 声明 → 使用处直接用名字（与 ftransform 操作数同口径）");
        assertEquals(0, result.insertedLineCount(), "Position 已声明 → 零注入");
        assertFalse(result.text().contains("gl_Vertex"));
        assertTrue(result.diagnostics().isEmpty());
    }

    /**
     * gl_TextureMatrix：带下标使用替换成单位阵（下标随 token 消费），显式声明行整行保留
     * （T11 可见），裸名无下标不猜 —— 替换无正确答案。
     */
    @Test
    void textureMatrixConsumesSubscriptsButKeepsDeclarationAndBareName() {
        String source = """
                #version 330
                uniform mat4 gl_TextureMatrix[8];
                void main() {
                    uv = (gl_TextureMatrix[0] * gl_TextureMatrix[1] * gl_TextureMatrix).xy;
                }
                """;
        LegacyBuiltinInjector.Result result =
                LegacyBuiltinInjector.inject(ShaderStage.VERTEX, source);
        List<String> lines = List.of(result.text().split("\n", -1));
        assertEquals("uniform mat4 gl_TextureMatrix[8];", lines.get(1),
                "声明行是名字上下文：整行保留（残留 gl_ 交驱动显式报错 T11）");
        assertTrue(lines.get(3).contains("(mat4(1.0) * mat4(1.0) * gl_TextureMatrix).xy"),
                "下标使用 → 单位阵；裸名保留：" + lines.get(3));
        assertEquals(0, result.insertedLineCount(), "矩阵类不注入声明");
        assertTrue(result.diagnostics().isEmpty());
    }

    /** 表达式型替换遇到该旧名的显式声明行 → 保留 token（否则声明名位落进表达式会打坏语法）。 */
    @Test
    void expressionReplacementProtectsDeclarationLines() {
        String source = """
                #version 330
                uniform mat3 gl_NormalMatrix;
                void main() { n = gl_NormalMatrix * v; }
                """;
        LegacyBuiltinInjector.Result result =
                LegacyBuiltinInjector.inject(ShaderStage.VERTEX, source);
        List<String> lines = List.of(result.text().split("\n", -1));
        assertEquals("uniform mat3 gl_NormalMatrix;", lines.get(1),
                "声明行保留（残留 gl_ 交驱动显式报错 T11）");
        assertTrue(lines.get(2).contains(
                        "n = (transpose(inverse(mat3(gbufferModelView)))) * v;"),
                "使用处照常替换：" + lines.get(2));
        assertEquals(0, result.insertedLineCount());
    }

    /** 注入顺序 = 冻结候选表序（UV0 → UV2 → Color → Normal → Position，未用的跳过）。 */
    @Test
    void injectionOrderMatchesFrozenCandidateTable() {
        String source = """
                #version 330
                void main() { x = gl_Color + gl_MultiTexCoord0 + gl_Normal; }
                """;
        LegacyBuiltinInjector.Result result =
                LegacyBuiltinInjector.inject(ShaderStage.VERTEX, source);
        List<String> lines = List.of(result.text().split("\n", -1));
        assertEquals("in vec4 UV0;", lines.get(1), "表序：MTEX0 先于 Color");
        assertEquals("in vec4 Color;", lines.get(2));
        assertEquals("in vec3 Normal;", lines.get(3));
        assertEquals(3, result.insertedLineCount(), "UV2 / Position 未用 → 跳过");
    }

    /** CRLF 文件：插入行按文件主行尾风格补齐 \r，不许混入裸 LF。 */
    @Test
    void crlfLineEndingsArePreservedOnInsertedLines() {
        String source = "#version 330\r\nvoid main() { x = gl_MultiTexCoord0.x; }\r\n";
        LegacyBuiltinInjector.Result result =
                LegacyBuiltinInjector.inject(ShaderStage.VERTEX, source);
        assertTrue(result.text().startsWith("#version 330\r\nin vec4 UV0;\r\n"),
                "插入行按文件主行尾风格补齐 \\r");
        assertFalse(result.text().replace("\r\n", "").contains("\n"), "不许混入裸 LF");
    }

    /** 幂等：第二遍输入是第一遍输出 → 替换门 / 注入门全关，逐字节不变、零诊断。 */
    @Test
    void substitutionAndInjectionAreIdempotentOnTextAndDiagnostics() {
        String source = """
                #version 330
                void main() { x = gl_TextureMatrix[0] * gl_Vertex; }
                """;
        LegacyBuiltinInjector.Result first =
                LegacyBuiltinInjector.inject(ShaderStage.VERTEX, source);
        assertTrue(first.insertedLineCount() > 0, "首遍必须注入 Position");
        assertTrue(first.text().contains("in vec3 Position;"));
        assertFalse(first.text().contains("gl_"), "首遍替换后零 gl_ token");
        LegacyBuiltinInjector.Result second =
                LegacyBuiltinInjector.inject(ShaderStage.VERTEX, first.text());
        assertEquals(first.text(), second.text(), "第二遍逐字节不变（使用门 + 已声明门都关）");
        assertEquals(0, second.insertedLineCount());
        assertTrue(second.diagnostics().isEmpty(), () -> "第二遍零诊断：" + second.diagnostics());
    }

    /** 块成员形态（⑧ 收编后的输出即第二遍输入）也必须判已声明 —— 幂等的另一半。 */
    @Test
    void blockMemberDeclarationCountsAsDeclaredForIdempotence() {
        String source = """
                #version 410 core
                layout(std140) uniform VkDispBuiltins {
                mat4 gbufferModelView;
                };
                in vec4 UV0;
                in vec3 Normal;
                void main() {
                    uv = UV0.xy;
                    n = normalize((transpose(inverse(mat3(gbufferModelView)))) * Normal);
                }
                """;
        LegacyBuiltinInjector.Result result =
                LegacyBuiltinInjector.inject(ShaderStage.VERTEX, source);
        assertEquals(source, result.text(), "第一遍输出的形态必须原样通过（零替换零插入）");
        assertEquals(0, result.insertedLineCount());
        assertTrue(result.diagnostics().isEmpty());
    }

    /** 逗号多声明里的旧名：纯标识符型替换在声明行也换名（与使用处一致；块冲突可见 T11）。 */
    @Test
    void commaSeparatedPackDeclarationIsRenamedConsistently() {
        String source = """
                #version 330
                uniform mat4 gl_ProjectionMatrix, gl_ModelViewMatrix;
                void main() { x = gl_ProjectionMatrix * gl_ModelViewMatrix; }
                """;
        LegacyBuiltinInjector.Result result =
                LegacyBuiltinInjector.inject(ShaderStage.VERTEX, source);
        List<String> lines = List.of(result.text().split("\n", -1));
        assertEquals("uniform mat4 gbufferProjection, gbufferModelView;", lines.get(1),
                "纯标识符替换在声明行同步换名（声明 / 使用一致）：" + lines.get(1));
        assertTrue(lines.get(2).contains("x = gbufferProjection * gbufferModelView;"),
                "使用处照常替换：" + lines.get(2));
        assertEquals(0, result.insertedLineCount());
        assertFalse(result.text().contains("gl_"));
    }

    /** layout 前缀形态（⑥ 的输出即第二遍输入）判已声明 —— GlslDeclaration.parse 对其返回 null，本级自建正则。 */
    @Test
    void layoutPrefixedDeclarationCountsAsDeclaredForIdempotence() {
        String source = """
                #version 410 core
                layout(location = 0) in vec4 UV0;
                layout(location = 1) in vec3 Position;
                void main() { a = UV0.x + vec4(Position, 1.0).y; }
                """;
        LegacyBuiltinInjector.Result result =
                LegacyBuiltinInjector.inject(ShaderStage.VERTEX, source);
        assertEquals(source, result.text(), "layout 前缀形态必须判已声明");
        assertEquals(0, result.insertedLineCount());
        assertTrue(result.diagnostics().isEmpty());
    }

    /** 任何输入都不抛异常（含未闭合注释的 ERROR 诊断路径）。 */
    @Test
    void nullAndHostileInputsNeverThrow() {
        List<String> hostile = List.of("", "\n", "/*", "*/", "in", "uniform",
                "layout(location = 0) in", "attribute vec4", "#", "gl_MultiTexCoord0",
                "    mat3 gl_NormalMatrix", "\\u0000\\u0001");
        for (String source : hostile) {
            LegacyBuiltinInjector.Result result =
                    LegacyBuiltinInjector.inject(ShaderStage.VERTEX, source);
            assertNotNull(result, "任何输入都必须返回结果而不是抛异常：" + source);
        }
        assertEquals("", LegacyBuiltinInjector.inject(ShaderStage.VERTEX, (String) null).text());
    }
}
