package dev.vkdisp.pack;
/**
 * 【参考调研】P2.4 composite 源生成编排测试
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/18-PARALLEL.md §5 P2.4 ③（选项覆盖链）、docs/04-SPEC.md §2（虚拟包命名空间）、
 *    docs/08-TESTING.md（P2.4 验收：选项改变画面）与 docs/07-CONSTRAINTS.md T11（降级必须显式）。
 *    全部为仓库内文档事实，不受版权保护。
 *    外部候选 IrisShaders / glsl-transformer（GPL-3.0 + 例外）→ 按禁止处理（X21），零代码并入。
 *    → 能否并入本项目（MIT）：可以（测试用例全部 @TempDir 自造包，18-PARALLEL §7.6）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：无外部实现可参考（两个参考模组都不做 OF 包加载器），按本项目契约自测。
 * 2. 备选：① 直接跑 runClient 验收 —— 否决（太慢且不能覆盖分支）；② 只测 diffAgainstDefaults 私有逻辑
 *    —— 否决（要测的是 generate 端到端编排）。两者都保留：本类冷路径 + runClient A/B（主线验收）。
 * 3. 我们的差异点：测**编排分支**（空库存兜底 / profile 生效 / 空 profile 走默认 / 未知 profile 显式 ERROR /
 *    无 composite 包跳过），不重复断言编译器与改写器自身（各自测试已覆盖）。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，零代码并入（07-CONSTRAINTS X19-X21）。
 * 5. 性能基线：❄️ 冷路径单测（毫秒级），无性能断言（X14）。
 */

import dev.vkdisp.glsl.TranslateDiagnostic;
import dev.vkdisp.glsl.translate.ShaderStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PackCompositeSource}（P2.4 ③ 冷路径编排）行为测试。
 *
 * <p>每个用例用 {@code @TempDir} 自造包（docs/18-PARALLEL.md §7.6：禁止第三方 shaderpack 入库）。
 * fixture 形态与 {@code run/shaderpacks/vkdisp-fixture-dir} 对齐：#version 330 + #include +
 * 选项 define 行 + InSampler/vUv 采样 —— 保证测的就是主线真正会走的源形状。
 */
class PackCompositeSourceTest {

    @TempDir
    Path inventory;

    // ------------------------------------------------------------------ 兜底分支

    @Test
    void nullInventoryFallsBackWithExplicitWarn() {
        PackCompositeSource.Result result = PackCompositeSource.generate(null, "");

        assertTrue(result.fallback(), "空库存必须走内置兜底");
        assertNull(result.packName(), "兜底时没有产出包");
        assertEquals(PackCompositeSource.FALLBACK_GLSL, result.source());
        assertTrue(result.diagnostics().stream().anyMatch(d ->
                        d.severity() == TranslateDiagnostic.Severity.WARN
                                && d.message().contains("内置 passthrough 兜底")),
                () -> "兜底必带 WARN（T11），实际: " + result.diagnostics());
    }

    @Test
    void emptyInventoryFallsBackWithExplicitWarn() {
        PackCompositeSource.Result result = PackCompositeSource.generate(inventory, "");

        assertTrue(result.fallback(), "空目录必须走内置兜底");
        assertEquals(PackCompositeSource.FALLBACK_GLSL, result.source());
        assertTrue(result.diagnostics().stream().anyMatch(d ->
                        d.severity() == TranslateDiagnostic.Severity.WARN
                                && d.message().contains("内置 passthrough 兜底")),
                () -> "兜底必带 WARN（T11），实际: " + result.diagnostics());
    }

    @Test
    void packWithoutCompositeProgramFallsBack() throws IOException {
        Path pack = packDir("nocomposite");
        write(pack, "shaders/gbuffers_textured.fsh",
                "#version 150\nvoid main() { gl_FragColor = vec4(1.0); }\n");
        write(pack, "shaders/gbuffers_textured.vsh",
                "#version 150\nvoid main() { gl_Position = vec4(0.0); }\n");

        PackCompositeSource.Result result = PackCompositeSource.generate(inventory, "");

        assertTrue(result.fallback(), "没有 composite 程序的包不该被选中");
        assertTrue(result.diagnostics().stream().anyMatch(d ->
                        d.message().contains("不含 composite 程序")),
                () -> "跳过原因必须显式 INFO，实际: " + result.diagnostics());
        assertTrue(result.diagnostics().stream().anyMatch(d ->
                        d.severity() == TranslateDiagnostic.Severity.WARN
                                && d.message().contains("内置 passthrough 兜底")),
                () -> "最终兜底必带 WARN（T11），实际: " + result.diagnostics());
    }

    @Test
    void brokenCompositeFragmentFallsBackWithErrorVisible() throws IOException {
        Path pack = packDir("broken");
        write(pack, "shaders/composite.fsh",
                "#version 150\n#include \"/lib/missing.glsl\"\nvoid main() {}\n");

        PackCompositeSource.Result result = PackCompositeSource.generate(inventory, "");

        assertTrue(result.fallback(), "片元阶段失败 → 逐包失败 → 最终兜底");
        assertTrue(result.diagnostics().stream().anyMatch(d ->
                        d.severity() == TranslateDiagnostic.Severity.ERROR),
                () -> "编译失败必须留下 ERROR（T11），实际: " + result.diagnostics());
        assertTrue(result.diagnostics().stream().anyMatch(d ->
                        d.severity() == TranslateDiagnostic.Severity.WARN
                                && d.message().contains("内置 passthrough 兜底")),
                () -> "兜底必带 WARN，实际: " + result.diagnostics());
    }

    // ------------------------------------------------------------------ 正常分支

    @Test
    void blankProfileUsesPackDefaults() throws IOException {
        writeFixturePack();

        PackCompositeSource.Result result = PackCompositeSource.generate(inventory, "");

        assertFalse(result.fallback(), "可用包不该兜底");
        assertEquals("fixture", result.packName());
        assertEquals("", result.profile());
        assertFragmentInlined(result.source());
        assertTrue(result.source().contains("0.10"),
                () -> "空 profile 应保留包默认 0.10，实际输出:\n" + result.source());
        assertFalse(result.source().contains("0.20"),
                () -> "空 profile 不该出现 0.20，实际输出:\n" + result.source());
        assertEquals(0, count(result, TranslateDiagnostic.Severity.WARN),
                () -> "默认值路径不该有告警，实际: " + result.diagnostics());
    }

    @Test
    void profileHighRewritesShadowDarknessInFinalSource() throws IOException {
        writeFixturePack();

        PackCompositeSource.Result result = PackCompositeSource.generate(inventory, "HIGH");

        assertFalse(result.fallback(), "可用包不该兜底");
        assertEquals("HIGH", result.profile());
        assertFragmentInlined(result.source());
        assertTrue(result.source().contains("0.20"),
                () -> "profile HIGH 必须把 0.20 写进最终源，实际输出:\n" + result.source());
        assertFalse(result.source().contains("0.10"),
                () -> "profile HIGH 不该残留默认 0.10，实际输出:\n" + result.source());
        assertEquals(0, count(result, TranslateDiagnostic.Severity.WARN),
                () -> "命中声明行时不该有告警，实际: " + result.diagnostics());
    }

    @Test
    void unknownProfileIsExplicitErrorAndKeepsDefaults() throws IOException {
        writeFixturePack();

        PackCompositeSource.Result result = PackCompositeSource.generate(inventory, "NO_SUCH");

        assertFalse(result.fallback(), "未知 profile 不是致命错：包仍可用（走默认值）");
        assertEquals("NO_SUCH", result.profile());
        assertTrue(result.diagnostics().stream().anyMatch(d ->
                        d.severity() == TranslateDiagnostic.Severity.ERROR
                                && d.message().contains("UNKNOWN_PROFILE")),
                () -> "未知 profile 必须显式 ERROR（T11），实际: " + result.diagnostics());
        assertTrue(result.source().contains("0.10"),
                () -> "未知 profile 应保持默认 0.10，实际输出:\n" + result.source());
    }

    @Test
    void includeWrappedOptionIsRewrittenToo() throws IOException {
        writeFixturePack();

        PackCompositeSource.Result result = PackCompositeSource.generate(inventory, "HIGH");

        assertFalse(result.fallback());
        // fixture 的 0.20/0.10 定义行就在主源里；被 include 包裹的 const 由
        // ShaderPackCompilerTest.optionOverrideRewritesIncludeFileDeclarations 单独覆盖。
        assertTrue(result.source().contains("0.20"),
                () -> "选项覆盖必须进入最终源，实际输出:\n" + result.source());
    }

    // ------------------------------------------------------------------ P3.3 deferred 分支

    @Test
    void fallbackPathExposesPassthroughDeferredAndFalseFlag() {
        PackCompositeSource.Result result = PackCompositeSource.generate(null, "");

        assertFalse(result.hasDeferredProgram(), "兜底路径不开 deferred 步");
        assertEquals(PackCompositeSource.FALLBACK_GLSL, result.deferredSource(),
                "deferred 源永不 null：兜底 = passthrough（required 管线必须总有源可编）");
    }

    @Test
    void packWithoutDeferredExposesPassthroughAndFalseFlag() throws IOException {
        writeFixturePack();

        PackCompositeSource.Result result = PackCompositeSource.generate(inventory, "");

        assertFalse(result.hasDeferredProgram(), "包无 deferred 程序 → 链路不开 deferred 步");
        assertEquals(PackCompositeSource.FALLBACK_GLSL, result.deferredSource());
        assertTrue(result.diagnostics().stream().anyMatch(d ->
                        d.severity() == TranslateDiagnostic.Severity.INFO
                                && d.message().contains("不含 deferred 程序")),
                () -> "跳过 deferred 必须显式可见（T11），实际: " + result.diagnostics());
        assertFragmentInlined(result.source());
    }

    @Test
    void packWithDeferredExposesTintedDeferredSourceAndTrueFlag() throws IOException {
        writeFixturePack();
        writeDeferredProgram();

        PackCompositeSource.Result result = PackCompositeSource.generate(inventory, "");

        assertFalse(result.fallback(), "可用包不该兜底");
        assertTrue(result.hasDeferredProgram(), "包声明且编出 deferred → 链路开 deferred 步");
        assertFalse(result.deferredSource().contains("vkdisp 内置兜底"),
                () -> "应是包的 deferred 源而非兜底，实际:\n" + result.deferredSource());
        assertTrue(result.deferredSource().contains("vec3(1.0, 0.7, 0.7)"),
                () -> "deferred 源必须带 fixture 色调变换（像素判据），实际:\n" + result.deferredSource());
        assertFragmentInlined(result.source());
    }

    @Test
    void brokenDeferredFragmentFallsBackToNoDeferredWithWarn() throws IOException {
        writeFixturePack();
        // 声明了 deferred 但片元编译必失败 → 按无 deferred 处理 + WARN（T11，不硬开步）。
        write(inventory.resolve("fixture"), "shaders/deferred.fsh",
                "#version 330\n#include \"/lib/missing.glsl\"\nvoid main() {}\n");

        PackCompositeSource.Result result = PackCompositeSource.generate(inventory, "");

        assertFalse(result.fallback(), "deferred 坏掉不影响 composite 可用");
        assertFalse(result.hasDeferredProgram(), "片元无成功产出 → 按无 deferred 处理");
        assertEquals(PackCompositeSource.FALLBACK_GLSL, result.deferredSource());
        assertTrue(result.diagnostics().stream().anyMatch(d ->
                        d.severity() == TranslateDiagnostic.Severity.WARN
                                && d.message().contains("deferred 步按未启用处理")),
                () -> "坏 deferred 必须显式 WARN（T11），实际: " + result.diagnostics());
    }

    // ------------------------------------------------------------------ 维度选择（P4.1）

    @Test
    void world0CompositePreferredOverNetherDespiteSortOrder() throws IOException {
        // 程序清单按限定名 TreeMap 排序 → "world-1/…" 字典序先于 "world0/…"。
        // 旧的"取第一个成功者"会选中下界；P4.1 维度偏好必须选中主世界。
        writeDimensionPrograms();

        PackCompositeSource.Result result = PackCompositeSource.generate(inventory, "");

        assertFalse(result.fallback(), "多维度包可用时不该兜底");
        assertTrue(result.source().contains("overworldTint"),
                () -> "必须选中 world0/composite，实际:\n" + result.source());
        assertFalse(result.source().contains("netherTint"),
                () -> "不许选中 world-1/composite，实际:\n" + result.source());
        assertTrue(result.diagnostics().stream().anyMatch(d ->
                        d.severity() == TranslateDiagnostic.Severity.INFO
                                && d.message().contains("'world0/composite'")),
                () -> "选中必须显式可见（T11），实际: " + result.diagnostics());
    }

    @Test
    void deferredPairsWithSameDimensionAsComposite() throws IOException {
        writeDimensionPrograms();

        PackCompositeSource.Result result = PackCompositeSource.generate(inventory, "");

        assertTrue(result.hasDeferredProgram(), "维度包的 deferred 应编出成功产出");
        assertTrue(result.deferredSource().contains("overworldDeferredTint"),
                () -> "deferred 必须与 composite 同维度（world0），实际:\n" + result.deferredSource());
        assertFalse(result.deferredSource().contains("netherDeferredTint"),
                () -> "不许链起「world0 composite + world-1 deferred」，实际:\n"
                        + result.deferredSource());
    }

    @Test
    void rootCompositePreferredOverOtherDimensionWhenNoWorld0() throws IOException {
        writeFixturePack();
        write(inventory.resolve("fixture"), "shaders/world-1/composite.fsh", """
                #version 330
                #extension GL_ARB_separate_shader_objects : require
                uniform sampler2D InSampler;
                layout(location = 0) in vec2 vUv;
                layout(location = 0) out vec4 fragColor;
                void main() {
                    float netherTint = 1.0;
                    fragColor = vec4(texture(InSampler, vUv).rgb * netherTint, 1.0);
                }
                """);
        write(inventory.resolve("fixture"), "shaders/world-1/composite.vsh", """
                #version 330
                layout(location = 0) in vec3 vaPosition;
                void main() { gl_Position = vec4(vaPosition, 1.0); }
                """);

        PackCompositeSource.Result result = PackCompositeSource.generate(inventory, "");

        assertFalse(result.fallback());
        assertFalse(result.source().contains("netherTint"),
                () -> "无 world0 时根命名空间优先于其它维度，实际:\n" + result.source());
        assertFragmentInlined(result.source());
    }

    /** 多维度 fixture：world-1 与 world0 各有 composite/deferred，标识符可区分（18-PARALLEL §7.6）。 */
    private void writeDimensionPrograms() throws IOException {
        Path pack = packDir("dim");
        writeDimensionComposite(pack, "world0", "overworldTint", "vec3(1.0)");
        writeDimensionComposite(pack, "world-1", "netherTint", "vec3(1.0, 0.0, 0.0)");
        writeDimensionDeferred(pack, "world0", "overworldDeferredTint", "vec3(0.7, 1.0, 0.7)");
        writeDimensionDeferred(pack, "world-1", "netherDeferredTint", "vec3(1.0, 0.3, 0.1)");
    }

    private void writeDimensionComposite(Path pack, String dimension, String marker, String tint)
            throws IOException {
        write(pack, "shaders/" + dimension + "/composite.fsh", """
                #version 330
                #extension GL_ARB_separate_shader_objects : require
                uniform sampler2D InSampler;
                layout(location = 0) in vec2 vUv;
                layout(location = 0) out vec4 fragColor;
                void main() {
                    vec3 %s = %s;
                    fragColor = vec4(texture(InSampler, vUv).rgb * %s, 1.0);
                }
                """.formatted(marker, tint, marker));
        write(pack, "shaders/" + dimension + "/composite.vsh", """
                #version 330
                layout(location = 0) in vec3 vaPosition;
                void main() { gl_Position = vec4(vaPosition, 1.0); }
                """);
    }

    private void writeDimensionDeferred(Path pack, String dimension, String marker, String tint)
            throws IOException {
        write(pack, "shaders/" + dimension + "/deferred.fsh", """
                #version 330
                #extension GL_ARB_separate_shader_objects : require
                uniform sampler2D InSampler;
                layout(location = 0) in vec2 vUv;
                layout(location = 0) out vec4 fragColor;
                void main() {
                    vec3 %s = %s;
                    fragColor = vec4(texture(InSampler, vUv).rgb * %s, 1.0);
                }
                """.formatted(marker, tint, marker));
        write(pack, "shaders/" + dimension + "/deferred.vsh", """
                #version 330
                layout(location = 0) in vec3 vaPosition;
                void main() { gl_Position = vec4(vaPosition, 1.0); }
                """);
    }

    // ------------------------------------------------------------------ helpers

    /** 断言产出的是被选中的包源（含 fixture 特征），而不是内置兜底。 */
    private static void assertFragmentInlined(String source) {
        assertFalse(source.contains("vkdisp 内置兜底"), "应是包源而非兜底");
        assertTrue(source.contains("void main()"), "源应含主函数");
        assertTrue(source.contains("vUv"), "Pass 3 顶点 fullscreen_flipv 输出 vUv，片元必须消费");
    }

    private static long count(PackCompositeSource.Result result, TranslateDiagnostic.Severity severity) {
        return result.diagnostics().stream().filter(d -> d.severity() == severity).count();
    }

    /** 与 run/shaderpacks/vkdisp-fixture-dir 同形状的自造包（18-PARALLEL §7.6）。 */
    private void writeFixturePack() throws IOException {
        Path pack = packDir("fixture");
        write(pack, "shaders/composite.fsh", """
                #version 330
                #extension GL_ARB_separate_shader_objects : require
                #include "/lib/common.glsl"
                #define SHADOW_DARKNESS 0.10 // 阴影浓度 [0.05 0.10 0.20]
                #define ENABLE_FOG true // [true false]
                uniform sampler2D InSampler;
                layout(location = 0) in vec2 vUv;
                layout(location = 0) out vec4 fragColor;
                void main() {
                    vec3 scene = texture(InSampler, vUv).rgb;
                    float shade = 1.0 - SHADOW_DARKNESS;
                    if (!ENABLE_FOG) {
                        shade = 1.0;
                    }
                    fragColor = vec4(scene * shade, 1.0);
                }
                """);
        write(pack, "shaders/composite.vsh", """
                #version 330
                #extension GL_ARB_separate_shader_objects : require
                layout(location = 0) in vec3 vaPosition;
                layout(location = 0) out vec2 vUv;
                void main() {
                    gl_Position = vec4(vaPosition, 1.0);
                    vUv = vec2(0.0);
                }
                """);
        write(pack, "shaders/lib/common.glsl", """
                vec4 vkdispFixtureTint() {
                    return vec4(0.5, 0.5, 0.5, 1.0);
                }
                """);
        write(pack, "shaders/shaders.properties", """
                sliders=shadowMapResolution
                screen.QUALITY=shadowDistance
                profile.LOW=SHADOW_DARKNESS=0.05
                profile.HIGH=SHADOW_DARKNESS=0.20
                """);
    }

    /** 与 run/shaderpacks/vkdisp-fixture-dir/shaders/deferred.* 同形状（P3.3 像素判据色调变换）。 */
    private void writeDeferredProgram() throws IOException {
        Path pack = inventory.resolve("fixture");
        write(pack, "shaders/deferred.fsh", """
                #version 330
                #extension GL_ARB_separate_shader_objects : require
                uniform sampler2D InSampler;
                layout(location = 0) in vec2 vUv;
                layout(location = 0) out vec4 fragColor;
                void main() {
                    vec3 scene = texture(InSampler, vUv).rgb;
                    fragColor = vec4(scene * vec3(1.0, 0.7, 0.7), 1.0);
                }
                """);
        write(pack, "shaders/deferred.vsh", """
                #version 330
                #extension GL_ARB_separate_shader_objects : require
                layout(location = 0) in vec3 vaPosition;
                void main() {
                    gl_Position = vec4(vaPosition, 1.0);
                }
                """);
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
