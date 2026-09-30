package dev.vkdisp.pack;

import dev.vkdisp.config.OptionBinding;
import dev.vkdisp.config.PackOptions;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨线集成验证：本环境汇合产物（{@link ShaderPack}）→ F 线选项模型（{@code PackOptions}）→
 * {@code #define} 绑定表（{@code OptionBinding}）。
 *
 * <p><b>为什么单独测这一条</b>：`docs/18-PARALLEL.md` §6 的汇合表要求 F 线并入主线，
 * 而 F 线早已提供 `PackOptions.of(ShaderPack)` 这样的消费入口 —— 但**从未有人验证过这条链真的跑得通**。
 * 汇合最容易出问题的正是这种「两边各自测试全绿、接口却对不上」的地方（静默失败第二定律）。
 * 本测试只**消费** config/ 的公开 API，不改动它（§4 独占路径纪律）。
 *
 * <p>全部用例用 {@code @TempDir} 自造包（§7.6：禁止第三方 shaderpack 入库）。
 */
class ShaderPackOptionsIntegrationTest {

    @TempDir
    Path inventory;

    @Test
    void discoveredOptionsReachTheDefineTable() throws IOException {
        Path pack = packDir("opts");
        write(pack, "shaders/composite.fsh",
                "#version 150\n"
                        + "#define SHADOW_DARKNESS 0.10 // 阴影浓度 [0.05 0.10 0.20]\n"
                        + "const int shadowMapResolution = 2048; // [512 1024 2048]\n"
                        + "void main() {}\n");

        ShaderPack model = loadOnlyPack();
        PackOptions options = PackOptions.of(model);
        OptionBinding binding = OptionBinding.of(options);

        assertTrue(options.contains("SHADOW_DARKNESS"),
                () -> "C 线发现的 #define 选项应被 F 线接住，实际选项: " + options.values().keySet());
        assertTrue(options.contains("shadowMapResolution"),
                () -> "C 线发现的 const 选项应被 F 线接住，实际选项: " + options.values().keySet());

        assertEquals("0.10", binding.defines().get("SHADOW_DARKNESS"));
        assertEquals("2048", binding.defines().get("shadowMapResolution"));
    }

    @Test
    void profileFromPropertiesDrivesDefineTable() throws IOException {
        Path pack = packDir("profile");
        write(pack, "shaders/composite.fsh",
                "#version 150\n#define SHADOW_DARKNESS 0.10 // [0.05 0.10 0.20]\nvoid main() {}\n");
        write(pack, "shaders/shaders.properties", "profile.PERFORMANCE=SHADOW_DARKNESS=0.05\n");

        ShaderPack model = loadOnlyPack();
        PackOptions options = PackOptions.of(model);

        assertEquals("0.10", OptionBinding.of(options).defines().get("SHADOW_DARKNESS"),
                "应用 profile 之前应是包内默认值");

        options.applyProfile(model, "PERFORMANCE");

        assertEquals("0.05", OptionBinding.of(options).defines().get("SHADOW_DARKNESS"),
                () -> "A 线解析出的 profile 条目应能驱动 F 线的值，实际值: "
                        + options.value("SHADOW_DARKNESS"));
    }

    @Test
    void booleanOptionLandsInTableUnderBothDefineStyles() throws IOException {
        Path pack = packDir("bool");
        write(pack, "shaders/composite.fsh",
                "#version 150\n#define ENABLE_FOG true // [true false]\nvoid main() {}\n");

        ShaderPack model = loadOnlyPack();
        PackOptions options = PackOptions.of(model);

        assertEquals("true",
                OptionBinding.of(options, OptionBinding.DefineStyle.LITERAL).defines().get("ENABLE_FOG"),
                "LITERAL 风格应原样写出布尔词");
        assertEquals("",
                OptionBinding.of(options, OptionBinding.DefineStyle.IFDEF_TRUE).defines().get("ENABLE_FOG"),
                "IFDEF_TRUE 风格下布尔为真应是空替换文本");
        assertFalse(OptionBinding.of(options, OptionBinding.DefineStyle.IFDEF_TRUE)
                        .undefines().contains("ENABLE_FOG"),
                "布尔为真时不该出现在 undefines 里");
    }

    @Test
    void sliderAndScreenMetadataSurvivesTheHandoff() throws IOException {
        Path pack = packDir("ui");
        write(pack, "shaders/composite.fsh",
                "#version 150\nconst int shadowMapResolution = 2048; // [512 1024 2048]\nvoid main() {}\n");
        write(pack, "shaders/shaders.properties",
                "sliders=shadowMapResolution\nscreen.QUALITY=shadowMapResolution\n");

        ShaderPack model = loadOnlyPack();
        Option declared = model.options().get(0);
        assertTrue(declared.slider(), "前置条件：本环境应已从 sliders= 标出滑条");
        assertEquals("QUALITY", declared.screen());

        PackOptions options = PackOptions.of(model);
        Option handedOver = options.definition("shadowMapResolution");

        assertTrue(handedOver.slider(), "F 线转手后不该丢失 slider 元数据（GUI 层要用）");
        assertEquals("QUALITY", handedOver.screen(), "F 线转手后不该丢失 screen 归属");
    }

    @Test
    void defaultValueOutsideCandidatesIsNeverSilent() throws IOException {
        // C 线可能报出一个不在候选列表里的默认值（包本身就写错了）。
        // 无论 F 线选择「钳制」还是「保留并打诊断」，都必须是显式的 —— 不许静默接受（T11）。
        Path pack = packDir("outlier");
        write(pack, "shaders/composite.fsh",
                "#version 150\nconst int shadowMapResolution = 4096; // [512 1024]\nvoid main() {}\n");

        ShaderPack model = loadOnlyPack();
        PackOptions options = PackOptions.of(model);

        String effective = options.value("shadowMapResolution");
        assertNotNull(effective);
        boolean clampedToCandidate = List.of("512", "1024").contains(effective);
        boolean reportedDiagnostic = !options.diagnostics().isEmpty();
        assertTrue(clampedToCandidate || reportedDiagnostic,
                () -> "越界默认值既没被钳制也没留诊断，属于静默接受（T11 违规）。实际值="
                        + effective + "，诊断=" + options.diagnostics());
    }

    @Test
    void packWithoutOptionsYieldsEmptyBindingWithoutFailure() throws IOException {
        Path pack = packDir("noopts");
        write(pack, "shaders/composite.fsh", "#version 150\nvoid main() {}\n");

        ShaderPack model = loadOnlyPack();
        assertTrue(model.options().isEmpty(), "前置条件：该包不应有选项");

        PackOptions options = PackOptions.of(model);
        OptionBinding binding = OptionBinding.of(options);

        assertEquals(0, options.size());
        assertTrue(binding.defines().isEmpty());
        assertTrue(binding.undefines().isEmpty());
    }

    // ------------------------------------------------------------------ helpers

    private ShaderPack loadOnlyPack() {
        ShaderPackService.InventoryResult result = ShaderPackService.loadAll(inventory);
        assertEquals(1, result.packs().size(),
                () -> "期望恰好 1 个包，实际: " + result.packs() + " 诊断: " + result.diagnostics());
        return result.packs().get(0);
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
