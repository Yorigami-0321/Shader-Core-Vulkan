package dev.vkdisp.glsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.vkdisp.glsl.preprocess.ConstEvaluator;
import dev.vkdisp.glsl.preprocess.IncludeResolver;
import dev.vkdisp.glsl.translate.BuiltinUniform;
import dev.vkdisp.glsl.translate.ShaderStage;
import dev.vkdisp.glsl.translate.UniformCatalog;
import dev.vkdisp.glsl.translate.UniformInjector;

/**
 * 【参考调研】C+D 汇合管线单测（GlslPipeline）/ 18-PARALLEL §4 汇合 + §7.3 证据规范
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/18-PARALLEL.md §4（C/D 线完成标准、§6 汇合点"P2.3 #include 的
 *    program 编译通过"）、§7.3（并行线证据规范：JUnit 全绿 + 边界用例清单 + grep 自证 +
 *    【参考调研】第 0 条）、docs/08-TESTING.md §4（#include / 选项识别验收）——
 *    仓库内文档事实，不受第三方版权约束。外部候选 IrisShaders/glsl-preprocessor
 *    （GPL-3.0 + 例外条款）与 IrisShaders/glsl-transformer（自定义传染许可）→ 一律按禁止处理
 *    （07-CONSTRAINTS L12 §1.3 陷阱 2 / X20 / X21），本任务不读其代码、零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 测试；全部 GLSL 样本与 include fixture 均为本任务
 *    自造的最小字符串（18-PARALLEL §7.6：禁止把第三方 pack 的 .glsl 片段复制进单测预期值）。
 *    → 能否并入本项目（MIT）：可以（仅依赖仓库内 F3 契约与自研 C/D 实现）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：JUnit 5 字符串逐字节比对 + TranslateResult.isSuccess()/diagnostics()/originOf()
 *    断言；幂等口径与 D 线一致（文本不动点；无删除无注入时整个结果相等）。
 * 2. 备选：无 —— 端到端文本 + 诊断 + 行号映射断言最直接。
 * 3. 我们的差异点：边界用例按 §7.3 显式列清单 —— 循环 include、缺失文件、超深/未闭合条件块、
 *    条件分支（跳过分支里的 attribute 不进转译）、null 四类入参、敌意输入；另断言入口级适配
 *    （预处理失败短路 + 上游 WARN 诊断合并），这两点是单独测 C/D 时看不到的。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：测试代码不进运行时；冷路径无性能要求（18-PARALLEL §7.7）。
 */
/**
 * {@link GlslPipeline} 的单测：C（预处理）→ D（转译）端到端行为、失败短路、诊断合并、
 * 端到端行号映射、幂等性（两种口径）与全部边界用例。
 */
class GlslPipelineTest {

    // ------------------------------------------------------------------ 自造 fixture
    // （18-PARALLEL §7.6：全部样本自造，不来自任何第三方着色器包）

    /** 主文件：绝对 / 相对 include、宏选项、白名单 const 选项、双层条件编译、OF 方言声明。 */
    private static final String MAIN_FSH = """
            #version 150
            #include "/lib/common.glsl"
            #include "lib/local.glsl"

            #define DARKNESS 0.10 // 阴影浓度 [0.05 0.10 0.20]
            const int shadowMapResolution = 2048; // [512 1024 2048]

            #ifdef USE_LOCAL
            varying vec3 vLocal;
            #else
            varying vec3 vCommon;
            #endif

            void main() {
                float d = DARKNESS;
            }
            """;

    /** 被包含文件（绝对 include）：第 2 行的 varying 要在转译后仍能指回本文件第 2 行。 */
    private static final String COMMON_GLSL = """
            // common.glsl (自造 fixture，非第三方)
            varying vec3 vHelper;
            float commonHelper() {
                return 1.0;
            }
            """;

    /** 被包含文件（相对 include）。 */
    private static final String LOCAL_GLSL = """
            // local.glsl (自造 fixture，非第三方)
            float localHelper() {
                return 2.0;
            }
            """;

    private static IncludeResolver mainResolver() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("lib/common.glsl", COMMON_GLSL);
        files.put("lib/local.glsl", LOCAL_GLSL);
        return IncludeResolver.of(files);
    }

    // ------------------------------------------------------------------ 端到端

    @Test
    void endToEndPreprocessThenTranslatePipeline() {
        TranslateResult r = GlslPipeline.run(
                ShaderStage.FRAGMENT, "composite.fsh", MAIN_FSH, mainResolver());

        assertTrue(r.isSuccess(), "正常样本必须成功：" + r.diagnostics());
        // ① 预处理：include 展开、宏/条件指令移除、宏体已展开
        assertTrue(r.text().contains("float commonHelper()"), "绝对 include 已展开");
        assertTrue(r.text().contains("float localHelper()"), "相对 include 已展开");
        assertFalse(r.text().contains("#include"), "include 指令应被移除");
        assertFalse(r.text().contains("#define"), "#define 指令应被移除");
        assertFalse(r.text().contains("#ifdef") || r.text().contains("#endif"),
                "条件编译指令应被移除");
        assertTrue(r.text().contains("float d = 0.10;"), "宏 DARKNESS 应已展开");
        assertFalse(r.text().contains("DARKNESS"), "宏名不应残留在代码里");
        // ② 条件分支：#ifdef USE_LOCAL 未定义 → 走 #else 分支
        assertTrue(r.text().contains("in vec3 vCommon;"), "选中分支的 varying 应转译成 in");
        assertFalse(r.text().contains("vLocal"), "未选中分支应被删除");
        // ③ 转译：内建 uniform 注入完整且只注入一次（匿名 std140 块形态，P2.3 驱动编译要求）
        assertTrue(r.text().contains(UniformInjector.BLOCK_HEADER), "注入块头应存在");
        assertTrue(r.text().contains(UniformInjector.BLOCK_OPEN), "匿名 std140 块开行应存在");
        for (BuiltinUniform uniform : UniformCatalog.uniforms()) {
            assertEquals(1, count(r.text(), uniform.blockMember()),
                    "内建 uniform 必须注入且仅一次：" + uniform.name());
        }
    }

    @Test
    void lineMapMapsBackToOriginalFilesThroughBothStages() {
        TranslateResult r = GlslPipeline.run(
                ShaderStage.FRAGMENT, "composite.fsh", MAIN_FSH, mainResolver());
        assertTrue(r.isSuccess(), r.diagnostics().toString());

        // 被包含文件里重写过的行（in vec3 vHelper;）→ lib/common.glsl 第 2 行
        int helperLine = indexOfLine(r.text(), "in vec3 vHelper;");
        assertEquals(new SourceLineMap.LineOrigin("lib/common.glsl", 2), r.originOf(helperLine),
                "经 include 展开 + define 删除 + 注入位移后，行号仍须指回被包含文件原行");
        // 主文件里的行 → composite.fsh 原始行号（void main 在原文件第 14 行）
        int mainLine = indexOfLine(r.text(), "void main() {");
        assertEquals(new SourceLineMap.LineOrigin("composite.fsh", 14), r.originOf(mainLine));
        int codeLine = indexOfLine(r.text(), "    float d = 0.10;");
        assertEquals(new SourceLineMap.LineOrigin("composite.fsh", 15), r.originOf(codeLine));
    }

    @Test
    void errorInIncludedFileIsLocatedBackToItsOrigin() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("lib/bad.glsl", "// bad.glsl (自造 fixture)\nattribute vec4 aVertex;\n");
        String src = """
                #version 150
                #include "lib/bad.glsl"
                void main() {
                }
                """;

        TranslateResult r = GlslPipeline.run(
                ShaderStage.FRAGMENT, "bad.fsh", src, IncludeResolver.of(files));

        assertFalse(r.isSuccess(), "片元阶段出现 attribute 必须失败");
        TranslateDiagnostic error = r.errors().get(0);
        assertEquals("lib/bad.glsl", error.sourceFile(), "诊断必须指回被 include 的原文件");
        assertEquals(2, error.line(), "诊断必须指回原文件原始行号（attribute 在第 2 行）");
        assertTrue(error.format().startsWith("ERROR: lib/bad.glsl:2:"), error.format());
    }

    // ------------------------------------------------------------------ 边界：include

    @Test
    void cycleIncludeFailsExplicitlyAndShortCircuitsTranslation() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("a.glsl", "#include \"b.glsl\"\n");
        files.put("b.glsl", "#include \"a.glsl\"\n");

        TranslateResult r = GlslPipeline.run(
                ShaderStage.VERTEX, "a.glsl", "#include \"b.glsl\"\n", IncludeResolver.of(files));

        assertFalse(r.isSuccess(), "循环包含必须使管线失败");
        assertTrue(r.errors().stream().anyMatch(d -> d.message().contains("循环包含")),
                "必须显式报循环包含 ERROR：" + r.diagnostics());
        // 失败短路：预处理失败后不进入转译 —— 输出里不得出现注入的内建 uniform 块
        assertFalse(r.text().contains(UniformInjector.BLOCK_HEADER),
                "预处理失败时不得再进入转译注入");
    }

    @Test
    void missingIncludeFailsExplicitlyNotSilently() {
        // 三参便捷重载（无 resolver → 空解析器）
        TranslateResult r = GlslPipeline.run(ShaderStage.VERTEX, "x.glsl", "#include \"/nope.glsl\"\n");

        assertFalse(r.isSuccess(), "缺失的包含文件必须失败");
        assertTrue(r.errors().stream().anyMatch(d -> d.message().contains("包含文件不存在")),
                "必须显式报文件不存在 ERROR：" + r.diagnostics());
    }

    @Test
    void nestedIncludesExpandWithLineMapIntact() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("lib/l1.glsl", "// l1\n#include \"l2.glsl\"\nint level1;\n");
        files.put("lib/l2.glsl", "// l2\n#include \"l3.glsl\"\nint level2;\n");
        files.put("lib/l3.glsl", "// l3\nint level3;\n");
        String src = """
                #version 150
                #include "lib/l1.glsl"
                void main() {}
                """;

        TranslateResult r = GlslPipeline.run(
                ShaderStage.VERTEX, "nest.vsh", src, IncludeResolver.of(files));

        assertTrue(r.isSuccess(), r.diagnostics().toString());
        assertTrue(r.text().contains("int level1;"), "第 1 层 include 内容应在");
        assertTrue(r.text().contains("int level2;"), "第 2 层 include 内容应在");
        int deepLine = indexOfLine(r.text(), "int level3;");
        assertTrue(r.text().contains("int level3;"), "第 3 层 include 内容应在");
        assertEquals(new SourceLineMap.LineOrigin("lib/l3.glsl", 2), r.originOf(deepLine),
                "最深层 include 的行仍须指回它自己的文件与行号");
    }

    // ------------------------------------------------------------------ 边界：条件编译

    @Test
    void conditionalCompilationPicksBranchBeforeTranslate() {
        String src = """
                #define LEVEL 2

                #if LEVEL >= 2
                int picked = 1;
                #elif LEVEL >= 1
                int elifPicked = 1;
                #else
                int elsePicked = 1;
                #endif

                #ifdef UNDEFINED_MACRO
                attribute vec4 hidden;
                #else
                int ifdefElse = 1;
                #endif

                void main() {
                }
                """;

        // 片元阶段：被跳过分支里的 attribute 已在预处理阶段删除 → 不该引发转译错误
        TranslateResult r = GlslPipeline.run(ShaderStage.FRAGMENT, "cond.fsh", src, null);

        assertTrue(r.isSuccess(), "跳过分支里的 attribute 不进转译，不该失败：" + r.diagnostics());
        assertTrue(r.text().contains("int picked = 1;"), "#if 命中分支应保留");
        assertTrue(r.text().contains("int ifdefElse = 1;"), "#ifdef 的 #else 分支应保留");
        assertFalse(r.text().contains("elifPicked"), "#elif 未命中分支应删除");
        assertFalse(r.text().contains("elsePicked"), "#else 未命中分支应删除");
        assertFalse(r.text().contains("hidden"), "未选中分支的 attribute 应随分支删除");
        assertFalse(r.text().contains("#if") || r.text().contains("#endif"),
                "条件编译指令应全部移除");
    }

    @Test
    void takenBranchAttributeSurfacesAsLocatedError() {
        String src = """
                #define USE_ATTR 1
                #ifdef USE_ATTR
                attribute vec4 a;
                #endif
                void main() {}
                """;

        TranslateResult r = GlslPipeline.run(ShaderStage.FRAGMENT, "attr.fsh", src, null);

        assertFalse(r.isSuccess(), "命中分支里的 attribute 在片元阶段必须失败");
        TranslateDiagnostic error = r.errors().get(0);
        assertEquals("attr.fsh", error.sourceFile());
        assertEquals(3, error.line(), "诊断行号须越过被删除的指令行，指回 attribute 原行 3");
    }

    @Test
    void unclosedAndUnmatchedConditionalsAreExplicitErrors() {
        TranslateResult unclosed = GlslPipeline.run(
                ShaderStage.VERTEX, "u1.fsh", "#ifdef X\nint a;\n", null);
        assertFalse(unclosed.isSuccess(), "未闭合条件块必须失败");
        assertTrue(unclosed.errors().stream().anyMatch(d -> d.message().contains("未闭合")),
                unclosed.diagnostics().toString());

        TranslateResult unmatched = GlslPipeline.run(
                ShaderStage.VERTEX, "u2.fsh", "int a;\n#endif\n", null);
        assertFalse(unmatched.isSuccess(), "孤立 #endif 必须失败");
        assertTrue(unmatched.errors().stream().anyMatch(d -> d.message().contains("#endif")),
                unmatched.diagnostics().toString());
    }

    // ------------------------------------------------------------------ 边界：诊断合并 / 选项

    @Test
    void preprocessWarningsSurviveDiagnosticMerge() {
        String src = """
                #define OPT 1 // [1 2]
                #define OPT 2 // [1 2]
                void main() {}
                """;

        TranslateResult r = GlslPipeline.run(ShaderStage.VERTEX, "opt.fsh", src, null);

        assertTrue(r.isSuccess(), "歧义选项是 WARN 不是失败");
        assertTrue(r.diagnostics().stream()
                        .anyMatch(d -> d.severity() == TranslateDiagnostic.Severity.WARN
                                && d.message().contains("歧义")),
                "预处理阶段的 WARN 必须合并进管线结果，不许静默丢失：" + r.diagnostics());
    }

    @Test
    void analyzeRecognizesOptionsAlongsideTranslation() {
        GlslPipeline.PipelineReport report = GlslPipeline.analyze(
                ShaderStage.FRAGMENT, "composite.fsh", MAIN_FSH, mainResolver());

        assertTrue(report.result().isSuccess(), report.result().diagnostics().toString());
        assertEquals(2, report.options().size(), "应识别白名单 const + 选项宏各 1 个");
        ConstEvaluator.OptionConstant defineOption = report.options().stream()
                .filter(o -> o.name().equals("DARKNESS")).findFirst().orElseThrow();
        assertEquals("define-value", defineOption.kind());
        assertEquals(List.of("0.05", "0.10", "0.20"), defineOption.candidates());
        assertEquals("阴影浓度", defineOption.description());
        ConstEvaluator.OptionConstant constOption = report.options().stream()
                .filter(o -> o.name().equals("shadowMapResolution")).findFirst().orElseThrow();
        assertEquals("const-int", constOption.kind());
        assertEquals("2048", constOption.defaultValue());
        assertEquals("composite.fsh", constOption.sourceFile());
        assertEquals(6, constOption.sourceLine());
        // analyze 与 run 同一入口：结果必须一致
        assertEquals(report.result(),
                GlslPipeline.run(ShaderStage.FRAGMENT, "composite.fsh", MAIN_FSH, mainResolver()));
    }

    // ------------------------------------------------------------------ 幂等性

    @Test
    void pipelineOutputTextIsFixedPointWhenRunTwice() {
        // 典型样本（含 include + 指令删除 + 注入）
        TranslateResult first = GlslPipeline.run(
                ShaderStage.FRAGMENT, "composite.fsh", MAIN_FSH, mainResolver());
        TranslateResult second = GlslPipeline.run(
                ShaderStage.FRAGMENT, "composite.fsh", first.text(), mainResolver());
        assertTrue(first.isSuccess(), first.diagnostics().toString());
        assertEquals(first.text(), second.text(), "典型样本跑两遍文本必须逐字节一致");
        assertTrue(second.isSuccess(), second.diagnostics().toString());
        assertTrue(second.diagnostics().isEmpty(), "第二遍不该再产生任何诊断：" + second.diagnostics());

        // 片元输出适配样本（首轮会合成 location 声明）
        String fragColor = """
                varying vec2 texcoord;
                void main() {
                    gl_FragColor = vec4(1.0);
                }
                """;
        TranslateResult f1 = GlslPipeline.run(ShaderStage.FRAGMENT, "fc.fsh", fragColor, null);
        TranslateResult f2 = GlslPipeline.run(ShaderStage.FRAGMENT, "fc.fsh", f1.text(), null);
        assertTrue(f1.isSuccess(), f1.diagnostics().toString());
        assertEquals(f1.text(), f2.text(), "合成片元输出后文本仍必须是不动点");
        assertTrue(f2.isSuccess(), f2.diagnostics().toString());
    }

    /**
     * 强口径幂等：输入没有任何预处理指令要删、内建 uniform 全部已声明（无注入）——
     * 此时整个 TranslateResult（文本 + 诊断 + 行号映射）两轮完全相等。
     */
    @Test
    void wholeResultIsIdempotentWhenNothingIsDeletedOrInjected() {
        String source = predeclaredVertexSource();
        TranslateResult first = GlslPipeline.run(ShaderStage.VERTEX, "strong.vsh", source, null);
        TranslateResult second = GlslPipeline.run(ShaderStage.VERTEX, "strong.vsh", first.text(), null);

        assertTrue(first.isSuccess(), first.diagnostics().toString());
        assertTrue(first.diagnostics().isEmpty(), "强口径样本不该有诊断");
        assertTrue(first.text().contains("out vec3 vNormal;"), "varying 已转译成 out");
        assertEquals(first, second, "无删除无注入时整个结果（含行号映射）必须两轮相等");
    }

    /** 内建 uniform 全部预先声明的顶点着色器（保证首轮无注入）。 */
    private static String predeclaredVertexSource() {
        StringBuilder sb = new StringBuilder("#version 330 core\n");
        for (BuiltinUniform uniform : UniformCatalog.uniforms()) {
            sb.append(uniform.declaration()).append('\n');
        }
        sb.append("varying vec3 vNormal;\n");
        sb.append("void main() {\n");
        sb.append("    vNormal = vec3(1.0);\n");
        sb.append("}\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------ 边界：null / 敌意输入

    @Test
    void nullInputsNeverThrow() {
        // source == null → 空文本 + WARN，不失败
        TranslateResult nullSource = GlslPipeline.run(ShaderStage.VERTEX, "x.vsh", null, null);
        assertEquals("", nullSource.text());
        assertTrue(nullSource.isSuccess(), "空输入是 WARN 不是失败：" + nullSource.diagnostics());
        assertEquals(TranslateDiagnostic.Severity.WARN, nullSource.diagnostics().get(0).severity());

        // resolver == null + #include → 显式缺文件 ERROR（不 NPE）
        TranslateResult nullResolver = GlslPipeline.run(
                ShaderStage.VERTEX, "x.vsh", "#include \"lib/l.glsl\"\n", null);
        assertFalse(nullResolver.isSuccess());
        assertTrue(nullResolver.errors().stream()
                .anyMatch(d -> d.message().contains("包含文件不存在")), nullResolver.diagnostics().toString());

        // primaryFile == null → 按 "" 归一，相对 include 按 shaders/ 顶层解析（不 NPE）
        Map<String, String> files = new LinkedHashMap<>();
        files.put("lib/l.glsl", "int fromRoot;\n");
        TranslateResult nullFile = GlslPipeline.run(
                ShaderStage.VERTEX, null, "#include \"lib/l.glsl\"\nvoid main() {}\n",
                IncludeResolver.of(files));
        assertTrue(nullFile.isSuccess(), nullFile.diagnostics().toString());
        assertTrue(nullFile.text().contains("int fromRoot;"), "null 主文件时相对 include 按顶层解析");

        // primaryFile == null + 缺文件 → 显式 ERROR（不 NPE）
        TranslateResult nullFileMissing = GlslPipeline.run(
                ShaderStage.VERTEX, null, "#include \"nope.glsl\"\n", IncludeResolver.of(Map.of()));
        assertFalse(nullFileMissing.isSuccess());
        assertTrue(nullFileMissing.errors().stream()
                .anyMatch(d -> d.message().contains("包含文件不存在")), nullFileMissing.diagnostics().toString());

        // stage == null → 交给 D 线按 UNKNOWN 显式报错（X9 不猜方向）
        TranslateResult nullStage = GlslPipeline.run(null, "x.vsh", "varying vec3 v;\n", null);
        assertFalse(nullStage.isSuccess(), "阶段未知且有 varying 声明 → 显式失败");
    }

    @Test
    void hostileInputsNeverThrow() {
        List<String> hostile = List.of("", "\n", "/*", "*/", "#", "#include", "#include \"",
                "#ifdef", "#endif", "#if", "#define", "attribute", "varying vec3;", "uniform",
                "}", "//", "\u0000\u0001", "gl_FragColor;", "void main() {}");
        for (String source : hostile) {
            for (ShaderStage stage : ShaderStage.values()) {
                TranslateResult result = GlslPipeline.run(stage, "h.fsh", source, null);
                assertNotNull(result, "任何输入都必须返回结果而不是抛异常：" + source);
                TranslateResult withFile = GlslPipeline.run(stage, null, source, null);
                assertNotNull(withFile, "primaryFile 为 null 也不许抛异常：" + source);
            }
        }
    }

    // ------------------------------------------------------------------ 阶段语义

    @Test
    void stageDirectionAppliesToRewrite() {
        String src = "varying vec3 v;\nvoid main() {}\n";
        assertTrue(GlslPipeline.run(ShaderStage.VERTEX, "a.vsh", src, null)
                .text().contains("out vec3 v;"), "顶点阶段 varying → out");
        assertTrue(GlslPipeline.run(ShaderStage.FRAGMENT, "a.fsh", src, null)
                .text().contains("in vec3 v;"), "片元阶段 varying → in");
    }

    // ------------------------------------------------------------------ 工具

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
