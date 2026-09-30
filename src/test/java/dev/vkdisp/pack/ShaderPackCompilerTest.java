package dev.vkdisp.pack;

import dev.vkdisp.glsl.TranslateDiagnostic;
import dev.vkdisp.glsl.translate.ShaderStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ShaderPackCompiler 行为测试。
 *
 * <p>本类只测**编排层自己的职责** —— 程序遍历、源读取、阶段划分、逐阶段降级；
 * 预处理与转译的正确性由 C/D 线各自的测试负责，不在此重复断言。
 *
 * <p>全部用例用 {@code @TempDir} 自造包（docs/18-PARALLEL.md §7.6：禁止第三方 shaderpack 入库）。
 */
class ShaderPackCompilerTest {

    @TempDir
    Path inventory;

    @Test
    void compilesBothStagesOfPlainProgram() throws IOException {
        Path pack = packDir("plain");
        write(pack, "shaders/composite.vsh",
                "#version 150\nvoid main() { gl_Position = vec4(0.0); }\n");
        write(pack, "shaders/composite.fsh",
                "#version 150\nvoid main() { gl_FragColor = vec4(1.0); }\n");

        ShaderPackCompiler.CompileResult result = ShaderPackCompiler.compile(onlyDiscovered());

        assertNotNull(result.pack());
        assertEquals(2, result.stages().size());
        assertTrue(result.isSuccess(), () -> "两阶段都应成功，实际诊断: " + result.diagnostics());
        assertTrue(result.failedStages().isEmpty());

        ShaderPackCompiler.CompiledStage vertex = stageOf(result, ShaderStage.VERTEX);
        assertEquals("composite", vertex.programName());
        assertEquals("composite.vsh", vertex.sourceFile());
        assertFalse(vertex.result().text().isBlank(), "顶点阶段应产出非空源文本");

        ShaderPackCompiler.CompiledStage fragment = stageOf(result, ShaderStage.FRAGMENT);
        assertEquals("composite.fsh", fragment.sourceFile());
    }

    @Test
    void includeIsExpandedIntoFinalSource() throws IOException {
        Path pack = packDir("inc");
        write(pack, "shaders/composite.fsh",
                "#version 150\n"
                        + "#include \"/lib/constants.glsl\"\n"
                        + "void main() { gl_FragColor = vec4(MAGIC_VALUE); }\n");
        write(pack, "shaders/lib/constants.glsl", "const float MAGIC_VALUE = 1.5;\n");

        ShaderPackCompiler.CompileResult result = ShaderPackCompiler.compile(onlyDiscovered());

        ShaderPackCompiler.CompiledStage fragment = stageOf(result, ShaderStage.FRAGMENT);
        assertTrue(fragment.isSuccess(), () -> "含 include 的程序应能跑通，实际诊断: " + result.diagnostics());
        assertTrue(fragment.result().text().contains("MAGIC_VALUE"),
                () -> "被包含文件的内容应并入最终源文本，实际输出:\n" + fragment.result().text());
        assertEquals(0, countDiagnostics(result.diagnostics(), TranslateDiagnostic.Severity.ERROR),
                () -> "缺失的 include 不应出现于此用例，实际诊断: " + result.diagnostics());
    }

    @Test
    void dimensionScopedProgramKeepsQualifiedName() throws IOException {
        Path pack = packDir("dims");
        write(pack, "shaders/composite.fsh", "#version 150\nvoid main() {}\n");
        write(pack, "shaders/world0/gbuffers_terrain.vsh", "#version 150\nvoid main() {}\n");

        ShaderPackCompiler.CompileResult result = ShaderPackCompiler.compile(onlyDiscovered());

        assertTrue(result.isSuccess(), () -> "实际诊断: " + result.diagnostics());
        List<String> names = result.stages().stream()
                .map(ShaderPackCompiler.CompiledStage::programName)
                .distinct()
                .toList();
        assertTrue(names.contains("composite"), () -> "缺少根目录程序，实际: " + names);
        assertTrue(names.contains("world0/gbuffers_terrain"),
                () -> "维度目录下的程序应带维度前缀，实际: " + names);
    }

    @Test
    void missingVertexShaderYieldsOnlyFragmentStage() throws IOException {
        Path pack = packDir("fsonly");
        write(pack, "shaders/composite.fsh", "#version 150\nvoid main() {}\n");

        ShaderPackCompiler.CompileResult result = ShaderPackCompiler.compile(onlyDiscovered());

        assertEquals(1, result.stages().size());
        assertEquals(ShaderStage.FRAGMENT, result.stages().get(0).stage());
        assertTrue(result.isSuccess(), "缺失的一侧属显式降级，不该算失败");
    }

    @Test
    void brokenIncludeFailsOnlyThatStage() throws IOException {
        Path pack = packDir("brokeninc");
        write(pack, "shaders/composite.vsh", "#version 150\nvoid main() {}\n");
        write(pack, "shaders/composite.fsh",
                "#version 150\n#include \"/lib/does-not-exist.glsl\"\nvoid main() {}\n");

        ShaderPackCompiler.CompileResult result = ShaderPackCompiler.compile(onlyDiscovered());

        assertEquals(2, result.stages().size(), "两阶段都应产出条目，不做株连");
        ShaderPackCompiler.CompiledStage vertex = stageOf(result, ShaderStage.VERTEX);
        ShaderPackCompiler.CompiledStage fragment = stageOf(result, ShaderStage.FRAGMENT);

        assertTrue(vertex.isSuccess(), "顶点阶段与坏 include 无关，应仍然成功");
        assertFalse(fragment.isSuccess(), "缺失 include 的片段阶段必须显式失败（T11）");
        assertEquals(List.of(fragment), result.failedStages());
        assertFalse(result.isSuccess(), "存在失败阶段时整包不算成功");
        assertTrue(countDiagnostics(result.diagnostics(), TranslateDiagnostic.Severity.ERROR) > 0,
                () -> "缺失 include 必须留下 ERROR 诊断，实际: " + result.diagnostics());
    }

    @Test
    void nullPackReportsErrorInsteadOfCrashing() {
        ShaderPackCompiler.CompileResult result = ShaderPackCompiler.compile(null);

        assertNull(result.pack());
        assertTrue(result.stages().isEmpty());
        assertFalse(result.isSuccess());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                        diagnostic.severity() == TranslateDiagnostic.Severity.ERROR),
                () -> "null 入参必须显式 ERROR，实际: " + result.diagnostics());
    }

    @Test
    void packWithoutProgramsCompilesToNothingButDoesNotFail() throws IOException {
        Path pack = packDir("noprog");
        write(pack, "shaders/shaders.properties", "composite.enabled=true\n");

        ShaderPackCompiler.CompileResult result = ShaderPackCompiler.compile(onlyDiscovered());

        assertNotNull(result.pack(), "只有 properties 的包仍应组装出模型");
        assertTrue(result.stages().isEmpty());
        assertTrue(result.isSuccess(), "没有阶段 = 没有失败项，不该判为失败");
    }

    // ---------------------------------------------- P2.4 选项覆盖（compile 的 overrides 入参）

    @Test
    void optionOverrideRewritesPrimarySourceBeforePipeline() throws IOException {
        Path pack = packDir("ovr");
        write(pack, "shaders/composite.fsh",
                "#version 150\n#define SHADOW_DARKNESS 0.10 // [0.05 0.10 0.20]\n"
                        + "void main() { gl_FragColor = vec4(SHADOW_DARKNESS); }\n");

        ShaderPackCompiler.CompileResult result = ShaderPackCompiler.compile(
                onlyDiscovered(), java.util.Map.of("SHADOW_DARKNESS", "0.20"));

        ShaderPackCompiler.CompiledStage fragment = stageOf(result, ShaderStage.FRAGMENT);
        assertTrue(fragment.isSuccess(), () -> "实际诊断: " + result.diagnostics());
        assertFalse(fragment.result().text().contains("0.10 // [0.05 0.10 0.20]"),
                "默认值行应已被改写，实际输出:\n" + fragment.result().text());
        // 预处理会删除 #define 指令行，但把值内联进使用点 —— 断言展开后的使用点带新值。
        assertTrue(fragment.result().text().contains("0.20"),
                () -> "选项新值必须进入最终源，实际输出:\n" + fragment.result().text());
        assertEquals(0, countDiagnostics(result.diagnostics(), TranslateDiagnostic.Severity.WARN),
                () -> "命中声明行时不该有缺失告警，实际: " + result.diagnostics());
    }

    @Test
    void optionOverrideRewritesIncludeFileDeclarations() throws IOException {
        Path pack = packDir("ovrinc");
        write(pack, "shaders/composite.fsh",
                "#version 150\n#include \"/lib/opts.glsl\"\n"
                        + "void main() { gl_FragColor = vec4(DENSITY); }\n");
        write(pack, "shaders/lib/opts.glsl", "const float DENSITY = 1.0; // [1.0 2.0]\n");

        ShaderPackCompiler.CompileResult result = ShaderPackCompiler.compile(
                onlyDiscovered(), java.util.Map.of("DENSITY", "2.0"));

        ShaderPackCompiler.CompiledStage fragment = stageOf(result, ShaderStage.FRAGMENT);
        assertTrue(fragment.isSuccess(), () -> "实际诊断: " + result.diagnostics());
        assertTrue(fragment.result().text().contains("2.0"),
                () -> "被包含文件里的 const 选项也要改写，实际输出:\n" + fragment.result().text());
        assertEquals(0, countDiagnostics(result.diagnostics(), TranslateDiagnostic.Severity.WARN),
                () -> "声明在 include 里同样算命中，不该告警，实际: " + result.diagnostics());
    }

    @Test
    void optionOverrideMissingEverywhereInPackWarnsExplicitly() throws IOException {
        Path pack = packDir("ovrmiss");
        write(pack, "shaders/composite.fsh", "#version 150\nvoid main() {}\n");

        ShaderPackCompiler.CompileResult result = ShaderPackCompiler.compile(
                onlyDiscovered(), java.util.Map.of("NO_SUCH_OPTION", "1"));

        assertTrue(result.isSuccess(), "缺失覆盖不是失败，是告警");
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                        diagnostic.severity() == TranslateDiagnostic.Severity.WARN
                                && diagnostic.message().contains("NO_SUCH_OPTION")),
                () -> "整包未命中的覆盖名必须显式 WARN（T11），实际: " + result.diagnostics());
    }

    @Test
    void nullOverridesBehavesLikeNoOverrides() throws IOException {
        Path pack = packDir("ovrnull");
        write(pack, "shaders/composite.fsh", "#version 150\nvoid main() {}\n");

        ShaderPackCompiler.CompileResult plain = ShaderPackCompiler.compile(onlyDiscovered());
        ShaderPackCompiler.CompileResult withNull = ShaderPackCompiler.compile(onlyDiscovered(), null);

        assertEquals(plain.stages().size(), withNull.stages().size());
        assertEquals(stageOf(plain, ShaderStage.FRAGMENT).result().text(),
                stageOf(withNull, ShaderStage.FRAGMENT).result().text(),
                "null 覆盖表与无覆盖完全等价");
        assertTrue(withNull.diagnostics().stream().noneMatch(d ->
                        d.severity() == TranslateDiagnostic.Severity.WARN && d.message().contains("未在任何源文件")),
                () -> "空覆盖表不该产生缺失告警，实际: " + withNull.diagnostics());
    }

    // ------------------------------------------------------------------ helpers

    private ShaderPackScanner.DiscoveredPack onlyDiscovered() {
        ShaderPackScanner.ScanResult scan = ShaderPackScanner.scan(inventory);
        assertEquals(1, scan.packs().size(),
                () -> "期望恰好 1 个合法包，实际: " + scan.packs() + " 问题: " + scan.problems());
        return scan.packs().get(0);
    }

    private static ShaderPackCompiler.CompiledStage stageOf(
            ShaderPackCompiler.CompileResult result, ShaderStage stage) {
        return result.stages().stream()
                .filter(compiled -> compiled.stage() == stage)
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少阶段 " + stage + "，实际: "
                        + result.stages().stream()
                        .map(compiled -> compiled.programName() + ":" + compiled.stage()).toList()));
    }

    private static long countDiagnostics(
            List<TranslateDiagnostic> diagnostics, TranslateDiagnostic.Severity severity) {
        return diagnostics.stream().filter(diagnostic -> diagnostic.severity() == severity).count();
    }

    private Path packDir(String name) throws IOException {
        Path pack = inventory.resolve(name);
        Files.createDirectories(pack.resolve("shaders"));
        return pack;
    }

    private void write(Path pack, String relative, String content) throws IOException {
        Path target = pack.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }
}
