package dev.vkdisp.glsl.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.vkdisp.glsl.SourceLineMap;
import dev.vkdisp.glsl.TranslateDiagnostic;
import dev.vkdisp.glsl.TranslateResult;

/**
 * 【参考调研】D 线二期单测（端到端转译）/ 04-SPEC §3.3 + 18-PARALLEL §4 D 线 + F3 行号契约
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/04-SPEC.md §3.2 / §3.3、docs/18-PARALLEL.md §3 F3 与 §4 D 线完成标准、
 *    §7.3 证据规范 —— 仓库内文档事实。外部候选 IrisShaders/glsl-transformer（GPL-3.0 + 例外条款）→
 *    按禁止处理（07-CONSTRAINTS L12 §1.3 陷阱 2 / X21），本任务不读其代码、零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 测试；样本全部为本任务自造（18-PARALLEL §7.6）。
 *    → 能否并入本项目（MIT）：可以（仅采纳不受版权保护的事实性信息）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：JUnit 5 字符串逐字比对（D 线证据要求"输入 OF 方言样本 → 输出与预期字符串比对"）
 *    + F3 契约给出的 C/D 背靠背用法（逐级 compose + locatedAt 回填）的落地断言。
 * 2. 备选：无 —— 文本级 golden 断言最直接。
 * 3. 我们的差异点：二期新增两个插入点（合成片元输出声明 + 内建 uniform 注入），因此专门断言
 *    ① 端到端文本 golden（七级流水线串起来仍然逐字可预期）；
 *    ② 幂等：输出文本是不动点，第二遍无新增诊断；
 *    ③ **诊断定位按级取映射**：⑦ 级（注入）运行在 ⑥ 级（合成片元输出）插入之后，若直接拿上游
 *    映射回填会整体错位 —— 这里用"注入阶段产生的 WARN 必须落在原文件正确行号"把它钉死。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：测试代码不进运行时；冷路径无性能要求（18-PARALLEL §7.7）。
 */
/**
 * {@link OfGlslTranslator} 二期能力的端到端单测：gl_ 内建输出 + 旧纹理函数 + ftransform 一起过
 * 七级流水线的 golden、幂等，以及两个插入点叠加后的行号映射与诊断回填。
 */
class OfGlslTranslatorBuiltinsTest {

    /** 自造 OF 方言片元样本（不来自任何第三方着色器包）。 */
    private static final String FRAGMENT_SAMPLE = """
            #version 120
            // self-made OF-dialect fragment sample for unit tests (not from any third-party pack)
            uniform sampler2D gtexture;
            varying vec2 texcoord;
            /* DRAWBUFFERS:0 */
            void main() {
                vec4 color = texture2D(gtexture, texcoord);
                gl_FragColor = color;
            }
            """;

    /** {@link #FRAGMENT_SAMPLE} 的期望输出（内建 uniform 匿名 std140 块 + 合成片元输出声明两个插入点）。 */
    private static final String FRAGMENT_EXPECTED = """
            #version 410
            // self-made OF-dialect fragment sample for unit tests (not from any third-party pack)
            // vkdisp: OF builtin uniforms (04-SPEC 3.2)
            layout(std140) uniform VkDispBuiltins {
            mat4 gbufferModelView;
            mat4 gbufferProjection;
            mat4 gbufferModelViewInverse;
            mat4 gbufferProjectionInverse;
            mat4 shadowModelView;
            mat4 shadowProjection;
            vec3 cameraPosition;
            vec3 sunPosition;
            vec3 moonPosition;
            vec3 shadowLightPosition;
            float frameTimeCounter;
            int frameCounter;
            float viewWidth;
            float viewHeight;
            float near;
            float far;
            float wetness;
            float rainStrength;
            int isEyeInWater;
            int worldTime;
            int worldDay;
            ivec2 atlasSize;
            ivec2 eyeBrightnessSmooth;
            };
            layout(location = 0) out vec4 vkdispFragOut0;
            uniform sampler2D gtexture;
            layout(location = 0) in vec2 texcoord;
            /* DRAWBUFFERS:0 */
            void main() {
                vec4 color = texture(gtexture, texcoord);
                vkdispFragOut0 = color;
            }
            """;

    @Test
    void goldenFragmentTranslationOfSelfMadeSample() {
        TranslateResult result = OfGlslTranslator.translate(ShaderStage.FRAGMENT, FRAGMENT_SAMPLE);
        assertTrue(result.isSuccess(), "样本无错误，必须成功：" + result.diagnostics());
        assertEquals(FRAGMENT_EXPECTED, result.text(), "七级流水线输出必须与预期字符串逐字一致");
        assertEquals(1, result.diagnostics().size(), result.diagnostics().toString());
        assertEquals(TranslateDiagnostic.Severity.INFO, result.diagnostics().get(0).severity());
        assertTrue(result.diagnostics().get(0).message().contains("合成"));
    }

    @Test
    void goldenVertexTranslationWithFtransform() {
        String source = """
                #version 120
                // self-made OF-dialect vertex sample for unit tests (not from any third-party pack)
                attribute vec3 Position;
                attribute vec2 UV0;
                varying vec2 texcoord;
                void main() {
                    texcoord = UV0;
                    gl_Position = ftransform();
                }
                """;
        TranslateResult result = OfGlslTranslator.translate(ShaderStage.VERTEX, source);
        assertTrue(result.isSuccess(), "样本无错误，必须成功：" + result.diagnostics());
        assertTrue(result.text().startsWith("#version 410\n// self-made OF-dialect vertex sample"),
                result.text());
        assertTrue(result.text().contains("\nmat4 gbufferModelView;\n"),
                "内建 uniform 应以匿名块成员注入：" + result.text());
        assertTrue(result.text().contains("in vec3 Position;"));
        assertTrue(result.text().contains("in vec2 UV0;"));
        assertTrue(result.text().contains("out vec2 texcoord;"));
        assertTrue(result.text().contains(
                "gl_Position = (gbufferProjection * gbufferModelView * vec4(Position, 1.0));"),
                result.text());
        assertTrue(result.diagnostics().isEmpty(), "已声明位置属性时不该有诊断："
                + result.diagnostics());
        for (BuiltinUniform uniform : UniformCatalog.uniforms()) {
            assertEquals(1, count(result.text(), uniform.blockMember()),
                    "23 条内建 uniform 必须齐：" + uniform.name());
        }
        assertEquals(1, count(result.text(), UniformInjector.BLOCK_OPEN), "必须共用一个匿名块");
    }

    @Test
    void translationOfBuiltinSampleIsIdempotentOnText() {
        TranslateResult first = OfGlslTranslator.translate(ShaderStage.FRAGMENT, FRAGMENT_SAMPLE);
        TranslateResult second = OfGlslTranslator.translate(ShaderStage.FRAGMENT, first.text());
        assertEquals(first.text(), second.text(), "输出文本是转译的不动点（跑两遍结果一致）");
        assertTrue(second.diagnostics().isEmpty(), "第二遍没有内建输出可改、没有内建 uniform 要注入，"
                + "不该再有诊断：" + second.diagnostics());
    }

    /**
     * ⑦ 级（内建 uniform 注入）运行在 ⑥ 级（合成片元输出）插入之后：注入阶段的诊断行号在
     * "⑥ 输出"坐标系里，必须经 ⑥ 级映射回填，否则插入点之后的诊断会整体错位一行。
     */
    @Test
    void injectorDiagnosticsAreLocatedThroughTheInsertedOutDeclaration() {
        String source = "#version 120\nuniform vec3 gbufferModelView;\nvoid main() {\n"
                + "    gl_FragColor = vec4(1.0);\n}\n";
        SourceLineMap upstream = SourceLineMap.builder("shaders/composite.fsh")
                .add("shaders/composite.fsh", 1)
                .add("shaders/composite.fsh", 2)
                .add("shaders/composite.fsh", 3)
                .add("shaders/composite.fsh", 4)
                .build();
        TranslateResult result = OfGlslTranslator.translate(
                ShaderStage.FRAGMENT, TranslateResult.success(source, upstream));
        assertTrue(result.isSuccess(), "只有 WARN / INFO，结果仍可用：" + result.diagnostics());

        TranslateDiagnostic typeMismatch = null;
        for (TranslateDiagnostic diagnostic : result.diagnostics()) {
            if (diagnostic.message().contains("gbufferModelView")) {
                typeMismatch = diagnostic;
            }
        }
        assertNotNull(typeMismatch, "必须报出内建 uniform 类型不符：" + result.diagnostics());
        assertEquals(TranslateDiagnostic.Severity.WARN, typeMismatch.severity());
        assertEquals("shaders/composite.fsh", typeMismatch.sourceFile(),
                "⑦ 级诊断必须经 ⑥ 级映射后回填原文件（直接用上游映射会错位）：" + typeMismatch.format());
        assertEquals(2, typeMismatch.line(), typeMismatch.format());

        // P4.1 收编：原行文本被抹空（声明原样移进块），"找回原行"靠行号映射而非文本 ——
        // 输出里必须恰好有一行映射回原文件第 2 行（抹空保行号契约）。
        SourceLineMap.LineOrigin declOrigin = new SourceLineMap.LineOrigin("shaders/composite.fsh", 2);
        int builtinLine = -1;
        int lineCount = result.text().split("\n", -1).length;
        for (int line = 1; line <= lineCount; line++) {
            if (declOrigin.equals(result.originOf(line))) {
                assertTrue(builtinLine < 0, "映射回原文件第 2 行的输出行必须唯一：" + line);
                builtinLine = line;
            }
        }
        assertTrue(builtinLine > 0, "抹空后的原行仍在输出里且映射指回原文件原行号");
        assertEquals(1, count(result.text(), "vec3 gbufferModelView;"),
                "收编不改写类型：包声明必须原样保留且仅出现一次（块内）");
        int outLine = indexOfLine(result.text(), "layout(location = 0) out vec4 vkdispFragOut0;");
        assertEquals(TranslateDiagnostic.UNKNOWN_LINE, result.originOf(outLine).sourceLine(),
                "合成声明是合成行（映射未命中）");
    }

    @Test
    void outOfRangeFragDataFailsEndToEnd() {
        TranslateResult result = OfGlslTranslator.translate(
                ShaderStage.FRAGMENT, "void main() {\n    gl_FragData[9] = vec4(1.0);\n}\n");
        assertFalse(result.isSuccess(), "下标越界必须显式失败");
        assertEquals(TranslateDiagnostic.Severity.ERROR, result.errors().get(0).severity());
        assertTrue(result.errors().get(0).message().contains("越界"), result.errors().get(0).format());
    }

    @Test
    void fragmentBuiltinsInUnknownStageFailExplicitly() {
        TranslateResult result = OfGlslTranslator.translate(
                ShaderStage.UNKNOWN, "void main() {\n    gl_FragColor = vec4(1.0);\n}\n");
        assertFalse(result.isSuccess(), "阶段未知时拒绝改写片元内建输出（X9 + T11）");
    }

    @Test
    void existingOutDeclarationIsNotDuplicatedEndToEnd() {
        String source = "out vec4 fragColor;\nvoid main() {\n    gl_FragColor = vec4(1.0);\n}\n";
        TranslateResult result = OfGlslTranslator.translate(ShaderStage.FRAGMENT, source);
        assertTrue(result.isSuccess());
        assertTrue(result.text().contains("fragColor = vec4(1.0);"));
        // P4.1.2：包内已有 out 现在由 ⑤ IoLocationAdapter 补 location（SPIR-V 强制）——
        // ⑥ 合成器仍不得再造第二条 out 声明。
        assertEquals(1, count(result.text(), "layout(location = 0) out vec4 fragColor;"),
                "包内已有 out 由 ⑤ 补 location，恰好一条");
        assertFalse(result.text().contains("vkdispFragOut"), "不该再合成声明");
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
    }

    /**
     * P4.1.2 驱动层 6 类错误的端到端复刻（自造样本，形态对齐 runClient 实测 shaderc 报错，
     * 标识符全部自造 —— 18-PARALLEL §7.6 不抄任何包源码）：① #version 120（低于 140）、
     * ② 逗号多名字 uniform 的重复注入撞名、③ 片元 in 无 location、④ 逗号多名字 varying、
     * ⑤ 合成片元输出、⑥ 二次转译逐字节幂等。
     */
    @Test
    void driverErrorClassesAreAllGoneEndToEnd() {
        String source = """
                #version 120
                // self-made driver-mirror sample for P4.1.2 (not from any third-party pack)
                uniform float viewWidth, viewHeight, aspectRatio;
                uniform float far, near;
                varying vec2 uv;
                varying vec3 lightDir, upDir;
                void main() {
                    gl_FragColor = vec4(uv, lightDir.xy);
                }
                """;
        TranslateResult result = OfGlslTranslator.translate(ShaderStage.FRAGMENT, source);
        assertTrue(result.isSuccess(), "驱动层 6 类错误必须全部消除：" + result.diagnostics());
        String text = result.text();

        // ① #version 120 → 410（shaderc 140 地板 + glslang 410/SSO location 门控，见 VersionAdapter）。
        assertTrue(text.startsWith("#version 410\n"), "版本升级：" + text);

        // ③④ 片元 in 全部带 location：uv=0、lightDir=1、upDir=2（同行拆语句，单空格）。
        assertTrue(text.contains("layout(location = 0) in vec2 uv;"), text);
        assertTrue(text.contains(
                        "layout(location = 1) in vec3 lightDir; layout(location = 2) in vec3 upDir;"),
                "逗号多声明名各自独占 location：" + text);

        // ② 逗号多名字 uniform 整行收编恰好一次，后续名不再按目录重复注入。
        assertEquals(1, count(text, "float viewWidth, viewHeight, aspectRatio;"),
                "整行收编一次");
        assertEquals(1, count(text, "float far, near;"), "整行收编一次");
        assertEquals(0, count(text, "\nfloat viewWidth;\n"), "viewWidth 不许重复注入");
        assertEquals(0, count(text, "\nfloat far;\n"), "far 不许重复注入");
        assertEquals(0, count(text, "\nfloat near;\n"), "near 不许重复注入");

        // ⑤ 合成片元输出就位。
        assertTrue(text.contains("layout(location = 0) out vec4 vkdispFragOut0;"), text);

        // ⑥ 第二遍逐字节相同且无新诊断（收编行在块内整行登记 + IO 已带 location）。
        TranslateResult second = OfGlslTranslator.translate(ShaderStage.FRAGMENT, text);
        assertEquals(text, second.text(), "输出文本是转译的不动点");
        assertTrue(second.diagnostics().isEmpty(), () -> "第二遍不该再有诊断：" + second.diagnostics());
    }

    @Test
    void builtinPipelineNeverThrowsOnHostileInput() {
        List<String> hostile = List.of("", "\n", "/*", "*/", "gl_FragColor", "gl_FragData[",
                "ftransform", "ftransform()", "texture2D", "texture2D(", "}", "#", "#version 120",
                "attribute", "varying", "out", "layout(location = 0) out vec4 x;", "\\u0000");
        for (ShaderStage stage : List.of(ShaderStage.VERTEX, ShaderStage.FRAGMENT, ShaderStage.UNKNOWN)) {
            for (String source : hostile) {
                TranslateResult result = OfGlslTranslator.translate(stage, source);
                assertNotNull(result, "任何输入都必须返回结果而不是抛异常：" + stage + " / " + source);
            }
        }
    }

    private static int count(String text, String needle) {
        int total = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            total++;
            index = text.indexOf(needle, index + needle.length());
        }
        return total;
    }

    private static int indexOfLine(String text, String exactLine) {
        List<String> lines = List.of(text.split("\n", -1));
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).equals(exactLine)) {
                return index + 1;
            }
        }
        throw new AssertionError("输出里找不到该行：" + exactLine);
    }
}
