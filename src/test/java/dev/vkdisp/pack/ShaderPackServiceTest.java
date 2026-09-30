package dev.vkdisp.pack;

import dev.vkdisp.glsl.TranslateDiagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ShaderPackService（A+B 汇合入口 + C 线选项发现）行为测试。
 *
 * <p>全部用例用 {@code @TempDir} 自造包 —— 禁止把第三方 shaderpack 提进仓库当 fixture
 * （docs/18-PARALLEL.md §7.6：会污染本项目的 MIT 授权链）。
 */
class ShaderPackServiceTest {

    @TempDir
    Path inventory;

    // ------------------------------------------------------------------ 目录包

    @Test
    void directoryPackAssemblesProgramsAndSettings() throws IOException {
        Path pack = packDir("demo");
        write(pack, "shaders/composite.vsh", "#version 150\nvoid main() {}\n");
        write(pack, "shaders/composite.fsh", "#version 150\nvoid main() {}\n");
        write(pack, "shaders/shaders.properties",
                "composite.enabled=true\nblend.composite=off\nviewport=0 0 1920 1080\n");

        ShaderPack model = onlyPack(ShaderPackService.loadAll(inventory));

        assertEquals("demo", model.name());
        assertFalse(model.fromArchive());
        assertEquals(1, model.programs().size());

        Program program = model.programs().get(0);
        assertEquals("composite", program.name());
        assertEquals(ProgramStage.COMPOSITE, program.stage());
        assertEquals(0, program.stageIndex());
        assertEquals("", program.dimensionFolder());
        assertEquals("composite.vsh", program.vertexShader());
        assertEquals("composite.fsh", program.fragmentShader());
        assertEquals(Map.of("enabled", "true", "blend", "off"), program.settings());
        assertEquals("0 0 1920 1080", model.properties().get("viewport"));
    }

    @Test
    void dimensionFoldersIncludeEmptyWorldDirectory() throws IOException {
        Path pack = packDir("dims");
        write(pack, "shaders/composite.fsh", "#version 150\nvoid main() {}\n");
        write(pack, "shaders/world0/gbuffers_terrain.fsh", "#version 150\nvoid main() {}\n");
        // 空的世界目录：OF 语义里意味着该维度禁用着色器，信息只能来自目录本身
        Files.createDirectories(pack.resolve("shaders/world-1"));

        ShaderPack model = onlyPack(ShaderPackService.loadAll(inventory));

        assertEquals(Set.of("world0", "world-1"), model.dimensionFolders());
        assertEquals(2, model.programs().size());

        Program inWorld = model.programs().stream()
                .filter(p -> p.name().equals("gbuffers_terrain"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("world0 下的 gbuffers_terrain 未进入模型"));
        assertEquals("world0", inWorld.dimensionFolder());
        assertEquals("world0/gbuffers_terrain.fsh", inWorld.fragmentShader());
    }

    // ------------------------------------------------------------------ zip 包

    @Test
    void zipPackAssemblesFromArchive() throws IOException {
        packZip("demo.zip", Map.of(
                "shaders/composite.fsh", "#version 150\nvoid main() {}\n",
                "shaders/world0/gbuffers_terrain.fsh", "#version 150\nvoid main() {}\n",
                "shaders/shaders.properties", "flip.composite.colortex1=off\n"));

        ShaderPack model = onlyPack(ShaderPackService.loadAll(inventory));

        assertTrue(model.fromArchive());
        assertEquals(2, model.programs().size());
        assertEquals(Set.of("world0"), model.dimensionFolders());

        Program composite = model.programs().stream()
                .filter(p -> p.name().equals("composite"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("zip 包内 composite 未进入模型"));
        assertEquals("off", composite.settings().get("flip.colortex1"));
    }

    // ------------------------------------------------------------------ C 线选项发现

    @Test
    void constAndDefineOptionsAreDiscovered() throws IOException {
        Path pack = packDir("opts");
        write(pack, "shaders/composite.fsh",
                "#version 150\n"
                        + "const int shadowMapResolution = 2048; // [512 1024 2048]\n"
                        + "#define SHADOW_DARKNESS 0.10 // 阴影浓度 [0.05 0.10 0.20]\n"
                        + "void main() {}\n");

        ShaderPack model = onlyPack(ShaderPackService.loadAll(inventory));

        Option resolution = optionByName(model, "shadowMapResolution");
        assertEquals(OptionType.INTEGER, resolution.type());
        assertEquals("2048", resolution.defaultValue());
        assertEquals(List.of("512", "1024", "2048"), resolution.values());

        Option darkness = optionByName(model, "SHADOW_DARKNESS");
        assertEquals(OptionType.FLOAT, darkness.type());
        assertEquals(List.of("0.05", "0.10", "0.20"), darkness.values());
    }

    @Test
    void conflictingDuplicateOptionIsDisabledWithWarning() throws IOException {
        Path pack = packDir("conflict");
        write(pack, "shaders/composite.fsh",
                "#version 150\nconst int shadowMapResolution = 2048; // [512 2048]\nvoid main() {}\n");
        write(pack, "shaders/composite.vsh",
                "#version 150\nconst int shadowMapResolution = 1024; // [512 2048]\nvoid main() {}\n");

        ShaderPackService.InventoryResult result = ShaderPackService.loadAll(inventory);
        ShaderPack model = onlyPack(result);

        assertTrue(model.options().isEmpty(), "默认值跨文件冲突的选项必须被禁用（OF 语义）");
        assertTrue(hasDiagnostic(result.diagnostics(), TranslateDiagnostic.Severity.WARN, "默认值不一致"),
                () -> "缺少「默认值不一致」WARN，实际诊断: " + result.diagnostics());
    }

    @Test
    void sliderAndScreenAttributionComesFromProperties() throws IOException {
        Path pack = packDir("ui");
        write(pack, "shaders/composite.fsh",
                "#version 150\n"
                        + "const int shadowMapResolution = 2048; // [512 1024 2048]\n"
                        + "const float shadowDistance = 64.0; // [32.0 64.0]\n"
                        + "void main() {}\n");
        write(pack, "shaders/shaders.properties",
                "sliders=shadowMapResolution\nscreen.QUALITY=shadowDistance\n");

        ShaderPack model = onlyPack(ShaderPackService.loadAll(inventory));

        Option resolution = optionByName(model, "shadowMapResolution");
        assertTrue(resolution.slider(), "sliders= 里列出的选项应标记为滑条");
        assertEquals("", resolution.screen());

        Option distance = optionByName(model, "shadowDistance");
        assertFalse(distance.slider());
        assertEquals("QUALITY", distance.screen());
    }

    @Test
    void crossFileIncludesAreResolvedThroughMountPlan() throws IOException {
        Path pack = packDir("inc");
        write(pack, "shaders/composite.fsh",
                "#version 150\n#include \"/lib/opts.glsl\"\nvoid main() {}\n");
        write(pack, "shaders/lib/opts.glsl", "const int shadowDistance = 64; // [32 64 128]\n");

        ShaderPackService.InventoryResult result = ShaderPackService.loadAll(inventory);
        ShaderPack model = onlyPack(result);

        Option distance = optionByName(model, "shadowDistance");
        assertEquals("64", distance.defaultValue());
        assertEquals(List.of("32", "64", "128"), distance.values());
        assertEquals(0, countDiagnostics(result.diagnostics(), TranslateDiagnostic.Severity.ERROR),
                () -> "被包含文件应能解析成功，不应有 ERROR: " + result.diagnostics());
    }

    // ------------------------------------------------------------------ 边界与降级

    @Test
    void missingPropertiesFileIsInfoAndPackStillLoads() throws IOException {
        Path pack = packDir("noprops");
        write(pack, "shaders/composite.fsh", "#version 150\nvoid main() {}\n");

        ShaderPackService.InventoryResult result = ShaderPackService.loadAll(inventory);
        ShaderPack model = onlyPack(result);

        assertTrue(model.properties().isEmpty());
        assertTrue(model.profiles().isEmpty());
        assertTrue(hasDiagnostic(result.diagnostics(), TranslateDiagnostic.Severity.INFO, "没有 shaders.properties"),
                () -> "缺少 INFO 诊断，实际: " + result.diagnostics());
    }

    @Test
    void malformedPropertiesDegradesToWarning() throws IOException {
        Path pack = packDir("badprops");
        write(pack, "shaders/composite.fsh", "#version 150\nvoid main() {}\n");
        write(pack, "shaders/shaders.properties", "这一行没有等号\n");

        ShaderPackService.InventoryResult result = ShaderPackService.loadAll(inventory);
        ShaderPack model = onlyPack(result);

        assertTrue(model.properties().isEmpty(), "解析失败应按无配置继续");
        assertTrue(hasDiagnostic(result.diagnostics(), TranslateDiagnostic.Severity.WARN, "解析失败"),
                () -> "解析失败必须显式 WARN（T11），实际: " + result.diagnostics());
    }

    @Test
    void nonStandardProgramLocationIsWarnedAndIgnored() throws IOException {
        Path pack = packDir("odd");
        write(pack, "shaders/lib/nested.fsh", "#version 150\nvoid main() {}\n");
        write(pack, "shaders/composite.fsh", "#version 150\nvoid main() {}\n");

        ShaderPackService.InventoryResult result = ShaderPackService.loadAll(inventory);
        ShaderPack model = onlyPack(result);

        assertEquals(1, model.programs().size());
        assertEquals("composite", model.programs().get(0).name());
        assertTrue(hasDiagnostic(result.diagnostics(), TranslateDiagnostic.Severity.WARN, "不在标准位置"),
                () -> "非标准位置的程序文件必须显式 WARN，实际: " + result.diagnostics());
    }

    @Test
    void duplicateVertexShaderKeepsFirstAndWarns() throws IOException {
        Path pack = packDir("dup");
        write(pack, "shaders/composite.fsh", "#version 150\nvoid main() {}\n");
        write(pack, "shaders/composite.vsh", "#version 150\nvoid main() {}\n");

        ShaderPackService.InventoryResult result = ShaderPackService.loadAll(inventory);
        ShaderPack model = onlyPack(result);

        assertEquals(1, model.programs().size());
        assertEquals("composite.vsh", model.programs().get(0).vertexShader());
    }

    @Test
    void loadAllPropagatesScanProblems() throws IOException {
        Files.writeString(inventory.resolve("broken.zip"), "this is not a zip archive",
                StandardCharsets.UTF_8);

        ShaderPackService.InventoryResult result = ShaderPackService.loadAll(inventory);

        assertTrue(result.packs().isEmpty());
        assertTrue(result.scanProblems().stream()
                        .anyMatch(problem -> problem.kind() == ShaderPackScanner.ProblemKind.BROKEN_ZIP),
                () -> "损坏 zip 应作为扫描问题上报，实际: " + result.scanProblems());
    }

    @Test
    void loadAllOnMissingInventoryDoesNotThrow() {
        ShaderPackService.InventoryResult result = ShaderPackService.loadAll(inventory.resolve("nope"));

        assertTrue(result.packs().isEmpty());
        assertTrue(result.scanProblems().stream()
                        .anyMatch(problem -> problem.kind() == ShaderPackScanner.ProblemKind.INVENTORY_MISSING),
                () -> "库存目录不存在应降级为问题条目，实际: " + result.scanProblems());
    }

    @Test
    void loadOnNullPackReportsErrorInsteadOfCrashing() {
        ShaderPackService.LoadResult result = ShaderPackService.load(null);

        assertNull(result.pack());
        assertTrue(hasDiagnostic(result.diagnostics(), TranslateDiagnostic.Severity.ERROR, "null"),
                () -> "null 入参必须显式 ERROR，实际: " + result.diagnostics());
    }

    // ------------------------------------------------------------------ helpers

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

    private void packZip(String fileName, Map<String, String> entries) throws IOException {
        Path zip = inventory.resolve(fileName);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
    }

    private static ShaderPack onlyPack(ShaderPackService.InventoryResult result) {
        assertEquals(1, result.packs().size(), () -> "期望恰好 1 个包，实际: " + result.packs().size());
        return result.packs().get(0);
    }

    private static Option optionByName(ShaderPack model, String name) {
        return model.options().stream()
                .filter(option -> option.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "未找到选项 '" + name + "'，实际选项: "
                                + model.options().stream().map(Option::name).toList()));
    }

    private static boolean hasDiagnostic(
            List<TranslateDiagnostic> diagnostics, TranslateDiagnostic.Severity severity, String fragment) {
        return diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == severity
                && diagnostic.message() != null
                && diagnostic.message().contains(fragment));
    }

    private static long countDiagnostics(
            List<TranslateDiagnostic> diagnostics, TranslateDiagnostic.Severity severity) {
        return diagnostics.stream().filter(diagnostic -> diagnostic.severity() == severity).count();
    }
}
