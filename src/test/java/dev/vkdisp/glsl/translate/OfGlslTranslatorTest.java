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
 * 【参考调研】D 线单测（转译入口）/ 04-SPEC §3.3 + 18-PARALLEL §4 D 线 + F3 行号契约
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/04-SPEC.md §3.2 / §3.3、docs/18-PARALLEL.md §3 F3 与 §4 D 线完成标准、
 *    docs/18-PARALLEL.md §7.3 证据规范 —— 仓库内文档事实，不受版权保护。
 *    外部候选 IrisShaders/glsl-transformer（GPL-3.0 + 例外条款）→ 按禁止处理
 *    （07-CONSTRAINTS L12 §1.3 陷阱 2 / X21），本任务不读其代码、零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 测试；样本为本任务自造的最小 OF 方言片段
 *    （18-PARALLEL §7.6：禁止把第三方 pack 的 .glsl 片段复制进单测预期值）。
 *    → 能否并入本项目（MIT）：可以（仅采纳不受版权保护的事实性信息）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：JUnit 5 字符串逐字比对（D 线证据要求"输入 OF 方言样本 → 输出与预期字符串比对"）
 *    + F3 契约给出的 C/D 背靠背用法（dMap.compose(cMap) 与 locatedAt 回填）的落地断言。
 * 2. 备选：无 —— 文本级 golden 断言最直接。
 * 3. 我们的差异点：幂等性按两条断言把口径写死 —— ① 输出文本是不动点（任何样本）；
 *    ② 内建 uniform 全已声明（无插入）时整个 TranslateResult（含诊断与行号映射）两轮相等；
 *    并把"首轮插入新行使映射必然变化"这一 F3 语义显式写进注释，避免"跑两遍结果一致"被误读。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：测试代码不进运行时；冷路径无性能要求（18-PARALLEL §7.7）。
 */
/**
 * {@link OfGlslTranslator} 的单测：自造 OF 方言样本的逐字 golden 比对、内建 uniform 注入完整性、
 * 幂等口径、以及 C → D 端到端行号回填（F3 契约）。
 */
class OfGlslTranslatorTest {

    /** 自造 OF 方言样本（不来自任何第三方着色器包）。 */
    private static final String OF_SAMPLE = """
            #version 330 core
            // self-made OF-dialect sample for unit tests (not from any third-party pack)
            attribute vec4 mc_Entity;
            varying vec3 vNormal;
            void main() {
                vNormal = vec3(1.0);
            }
            """;

    /** 与 {@link #OF_SAMPLE} 对应的期望输出（注入块按 04-SPEC §3.2 顺序展开）。 */
    private static final String EXPECTED = """
            #version 330 core
            // self-made OF-dialect sample for unit tests (not from any third-party pack)
            // vkdisp: OF builtin uniforms (04-SPEC 3.2)
            uniform mat4 gbufferModelView;
            uniform mat4 gbufferProjection;
            uniform mat4 gbufferModelViewInverse;
            uniform mat4 gbufferProjectionInverse;
            uniform mat4 shadowModelView;
            uniform mat4 shadowProjection;
            uniform vec3 cameraPosition;
            uniform vec3 sunPosition;
            uniform vec3 moonPosition;
            uniform vec3 shadowLightPosition;
            uniform float frameTimeCounter;
            uniform int frameCounter;
            uniform float viewWidth;
            uniform float viewHeight;
            uniform float near;
            uniform float far;
            uniform float wetness;
            uniform float rainStrength;
            uniform int isEyeInWater;
            uniform int worldTime;
            uniform int worldDay;
            uniform ivec2 atlasSize;
            uniform ivec2 eyeBrightnessSmooth;
            in vec4 mc_Entity;
            out vec3 vNormal;
            void main() {
                vNormal = vec3(1.0);
            }
            """;

    @Test
    void goldenTranslationOfSelfMadeOfSample() {
        TranslateResult result = OfGlslTranslator.translate(ShaderStage.VERTEX, OF_SAMPLE);
        assertTrue(result.isSuccess(), "样本无错误，必须成功：" + result.diagnostics());
        assertEquals(EXPECTED, result.text(), "输出必须与预期字符串逐字一致");
        assertTrue(result.diagnostics().isEmpty(), "成功转译不产生噪声诊断");
    }

    @Test
    void injectsCompleteBuiltinTable() {
        TranslateResult result = OfGlslTranslator.translate(ShaderStage.VERTEX, OF_SAMPLE);
        for (BuiltinUniform uniform : UniformCatalog.uniforms()) {
            assertEquals(1, count(result.text(), uniform.declaration()),
                    "内建 uniform 必须注入且仅一次：" + uniform.name());
        }
    }

    @Test
    void translationIsIdempotentOnText() {
        TranslateResult first = OfGlslTranslator.translate(ShaderStage.VERTEX, OF_SAMPLE);
        TranslateResult second = OfGlslTranslator.translate(ShaderStage.VERTEX, first.text());
        assertEquals(first.text(), second.text(), "输出文本是转译的不动点（跑两遍结果一致）");
        assertTrue(second.diagnostics().isEmpty(), "第二遍没有任何声明可改，不该再产生诊断");
    }

    /**
     * 无插入场景下，整个 TranslateResult（文本 + 诊断 + 行号映射）两轮完全相等 —— 这是"跑两遍结果
     * 一致"的强口径；有插入时映射按 F3 语义必然变化（首轮插入了新行），故只断言文本不动点。
     */
    @Test
    void wholeResultIsIdempotentWhenNothingNeedsInjection() {
        StringBuilder source = new StringBuilder("#version 330 core\n");
        for (BuiltinUniform uniform : UniformCatalog.uniforms()) {
            source.append(uniform.declaration()).append('\n');
        }
        source.append("varying vec3 vNormal;\n").append("void main() {}\n");
        TranslateResult first = OfGlslTranslator.translate(ShaderStage.VERTEX, source.toString());
        TranslateResult second = OfGlslTranslator.translate(ShaderStage.VERTEX, first.text());
        assertEquals(first, second, "无插入时整个结果（含映射与诊断）必须相等");
        assertTrue(first.diagnostics().isEmpty());
        assertTrue(first.text().contains("out vec3 vNormal;"));
    }

    @Test
    void diagnosticsAreLocatedBackToOriginalFileThroughUpstreamMap() {
        String preprocessed = "#version 330 core\nattribute vec4 Color;\n";
        SourceLineMap upstream = SourceLineMap.builder("shaders/a.glsl")
                .add("shaders/a.glsl", 1)
                .add("shaders/lib/common.glsl", 57)
                .build();
        TranslateResult input = TranslateResult.success(preprocessed, upstream);
        TranslateResult result = OfGlslTranslator.translate(ShaderStage.FRAGMENT, input);
        assertFalse(result.isSuccess(), "片元阶段出现 attribute 必须失败");
        TranslateDiagnostic error = result.errors().get(0);
        assertEquals("shaders/lib/common.glsl", error.sourceFile(), "诊断必须回填到被 #include 的原文件");
        assertEquals(57, error.line());
        assertTrue(error.format().startsWith("ERROR: shaders/lib/common.glsl:57:"), error.format());
    }

    @Test
    void outputLineMapComposesEndToEnd() {
        String preprocessed = "#version 330 core\nvarying vec3 vView;\nvoid main() {}\n";
        SourceLineMap upstream = SourceLineMap.builder("shaders/a.glsl")
                .add("shaders/a.glsl", 1)
                .add("shaders/lib/common.glsl", 10)
                .add("shaders/a.glsl", 3)
                .build();
        TranslateResult result = OfGlslTranslator.translate(
                ShaderStage.VERTEX, TranslateResult.success(preprocessed, upstream));
        assertTrue(result.isSuccess());
        assertEquals(new SourceLineMap.LineOrigin("shaders/a.glsl", 1), result.originOf(1));
        assertEquals(TranslateDiagnostic.UNKNOWN_LINE, result.originOf(2).sourceLine(),
                "注入的声明行是合成行（映射未命中）");
        int rewrittenLine = indexOfLine(result.text(), "out vec3 vView;");
        assertEquals(new SourceLineMap.LineOrigin("shaders/lib/common.glsl", 10),
                result.originOf(rewrittenLine), "重写后的行必须仍指回原文件原行号");
        int mainLine = indexOfLine(result.text(), "void main() {}");
        assertEquals(new SourceLineMap.LineOrigin("shaders/a.glsl", 3), result.originOf(mainLine));
    }

    @Test
    void vertexAndFragmentDirectionsDiffer() {
        String source = "varying vec3 v;\n";
        assertTrue(OfGlslTranslator.translate(ShaderStage.VERTEX, source).text().contains("out vec3 v;"));
        assertTrue(OfGlslTranslator.translate(ShaderStage.FRAGMENT, source).text().contains("in vec3 v;"));
    }

    @Test
    void unknownStageFailsExplicitlyInsteadOfGuessing() {
        TranslateResult result = OfGlslTranslator.translate(ShaderStage.UNKNOWN, "varying vec3 v;\n");
        assertFalse(result.isSuccess());
        assertEquals(TranslateDiagnostic.Severity.ERROR, result.errors().get(0).severity());
    }

    @Test
    void unknownStageWithoutOfDeclarationsStillSucceeds() {
        TranslateResult result = OfGlslTranslator.translate(ShaderStage.UNKNOWN, "void main() {}\n");
        assertTrue(result.isSuccess());
    }

    @Test
    void nullInputsAreHandledWithDiagnosticsNotCrashes() {
        TranslateResult nullText = OfGlslTranslator.translate(ShaderStage.VERTEX, (String) null);
        assertEquals("", nullText.text());
        assertTrue(nullText.isSuccess(), "空输入是 WARN，不是失败");
        assertEquals(TranslateDiagnostic.Severity.WARN, nullText.diagnostics().get(0).severity());

        TranslateResult nullResult = OfGlslTranslator.translate(ShaderStage.VERTEX, (TranslateResult) null);
        assertEquals("", nullResult.text());
        assertEquals(TranslateDiagnostic.Severity.WARN, nullResult.diagnostics().get(0).severity());

        TranslateResult nullStage = OfGlslTranslator.translate(null, "varying vec3 v;\n");
        assertFalse(nullStage.isSuccess(), "阶段为 null 且有 OF 声明 → 显式失败");
    }

    @Test
    void blankInputIsUntouchedAndWarned() {
        String blank = "   \n\n";
        TranslateResult result = OfGlslTranslator.translate(ShaderStage.VERTEX, blank);
        assertEquals(blank, result.text(), "空白输入原样返回，不注入任何声明");
        assertEquals(TranslateDiagnostic.Severity.WARN, result.diagnostics().get(0).severity());
    }

    @Test
    void sampleWithoutOfDeclarationsStillGetsBuiltins() {
        TranslateResult result = OfGlslTranslator.translate(ShaderStage.VERTEX, "void main() {}\n");
        assertTrue(result.isSuccess());
        assertEquals(23, count(result.text(), "uniform "), "没有 OF 声明也要注入内建 uniform");
    }

    @Test
    void injectionGoesToFileStartWhenThereIsNoHeader() {
        TranslateResult result = OfGlslTranslator.translate(ShaderStage.VERTEX, "varying vec3 v;\n");
        assertTrue(result.text().startsWith(UniformInjector.BLOCK_HEADER), "没有 #version 头部时注释放最前");
        assertTrue(result.text().contains("out vec3 v;"));
    }

    @Test
    void hostileInputsNeverThrow() {
        List<String> hostile = List.of("", "\n", "/*", "*/", "attribute", "varying", "attribute;",
                "varying vec3;", "varying vec3 v", "uniform", "}", "#", "#version",
                "attribute vec4 x;;;", "//", "\\u0000\\u0001");
        for (String source : hostile) {
            TranslateResult result = OfGlslTranslator.translate(ShaderStage.VERTEX, source);
            assertNotNull(result, "任何输入都必须返回结果而不是抛异常：" + source);
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
