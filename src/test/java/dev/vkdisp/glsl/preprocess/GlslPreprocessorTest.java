package dev.vkdisp.glsl.preprocess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.vkdisp.glsl.TranslateDiagnostic;
import dev.vkdisp.glsl.TranslateResult;

/**
 * 【参考调研】C 线单测 — GlslPreprocessor（编排入口）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = docs/18-PARALLEL.md §4 C 线完成标准"C 产出的 TranslateResult 是 D 的输入"
 *    + F3 冻结契约（TranslateResult / TranslateDiagnostic / SourceLineMap，只依赖、不修改）
 *    —— 仓库内事实，不受版权保护。外部候选 IrisShaders/glsl-preprocessor（GPL-3.0 + 例外条款）
 *    → 一律按禁止处理（handover §2.1 / 07-CONSTRAINTS X20/X21），本任务不读其代码、零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 测试，fixture 全部自造于
 *    src/test/resources/packs/preprocess/（18-PARALLEL §7.6：禁止复制第三方 pack 的 .glsl 片段）。
 *    → 能否并入本项目（MIT）：可以（仅依赖仓库内 F3 契约与自研 processor）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：handover §5.3 的串联顺序 "Include → Define → Const"，最终产出 F3 的
 *    TranslateResult；fixture 路径解析以 §6.5 官方 #include 语义为准。
 * 2. 备选：无 —— 端到端文本 + 诊断 + 行号映射断言。
 * 3. 我们的差异点：用自造 fixture 跑通"绝对 + 相对 include + 条件编译 + 选项识别"，并断言
 *    端到端行号映射（输出某行指回 composite.fsh 原行）。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：测试代码不进运行时（18-PARALLEL §7.7）。
 */
class GlslPreprocessorTest {

    private static String readResource(String name) {
        try (InputStream in = GlslPreprocessorTest.class.getClassLoader()
                .getResourceAsStream("packs/preprocess/" + name)) {
            if (in == null) {
                throw new IllegalStateException("缺少 fixture: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static IncludeResolver fixtureResolver() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("lib/common.glsl", readResource("lib/common.glsl"));
        files.put("lib/local.glsl", readResource("lib/local.glsl"));
        return IncludeResolver.of(files);
    }

    @Test
    void endToEndFixturePreprocesses() {
        String src = readResource("composite.fsh");
        TranslateResult r = GlslPreprocessor.preprocess("composite.fsh", src, fixtureResolver());

        assertTrue(r.isSuccess(), "fixture 应预处理成功");
        assertTrue(r.text().contains("commonHelper()"), "绝对 include 已展开");
        assertTrue(r.text().contains("localHelper()"), "相对 include 已展开");
        assertTrue(r.text().contains("uniform float shadowDarkness = 0.10;"),
                "条件编译选中 + 宏已展开");
        assertFalse(r.text().contains("#include"), "include 指令应被移除");
        assertFalse(r.text().contains("#define") || r.text().contains("#ifdef"),
                "宏/条件指令应被移除");
    }

    @Test
    void endToEndRecognizesOptions() {
        String src = readResource("composite.fsh");
        GlslPreprocessor.PreprocessReport report =
                GlslPreprocessor.analyze("composite.fsh", src, fixtureResolver());

        assertEquals(2, report.options().size(), "应识别 2 个选项（白名单 const + 选项宏）");
        assertTrue(report.options().stream()
                .anyMatch(o -> o.name().equals("shadowMapResolution")
                        && o.candidates().equals(List.of("512", "1024", "2048"))));
        assertTrue(report.options().stream()
                .anyMatch(o -> o.name().equals("SHADOW_DARKNESS")
                        && o.candidates().equals(List.of("0.05", "0.10", "0.20"))
                        && o.description().equals("阴影浓度")));
    }

    @Test
    void endToEndLineMapPointsBackToOriginal() {
        String src = readResource("composite.fsh");
        TranslateResult r = GlslPreprocessor.preprocess("composite.fsh", src, fixtureResolver());

        // 动态定位 "uniform float shadowDarkness = 0.10;" 这一输出行
        String[] outLines = r.text().split("\n", -1);
        int uniformOutLine = -1;
        for (int i = 0; i < outLines.length; i++) {
            if (outLines[i].contains("uniform float shadowDarkness = 0.10;")) {
                uniformOutLine = i + 1;
                break;
            }
        }
        assertTrue(uniformOutLine > 0, "应能在输出中找到展开后的 uniform 行");
        // 该 uniform 定义于 composite.fsh 第 9 行（见 fixture：#ifdef/#endif 之间）
        assertEquals("composite.fsh", r.originOf(uniformOutLine).sourceFile());
        assertEquals(9, r.originOf(uniformOutLine).sourceLine());
    }

    @Test
    void cycleAtOrchestratorLevelFailsWithError() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("a.glsl", "#include \"b.glsl\"\n");
        files.put("b.glsl", "#include \"a.glsl\"\n");
        TranslateResult r =
                GlslPreprocessor.preprocess("a.glsl", "#include \"b.glsl\"\n", IncludeResolver.of(files));

        assertFalse(r.isSuccess(), "循环包含必须使整体失败");
        boolean hasError = r.diagnostics().stream()
                .anyMatch(d -> d.severity() == TranslateDiagnostic.Severity.ERROR);
        assertTrue(hasError, "失败结果必须含 ERROR 诊断");
    }
}
