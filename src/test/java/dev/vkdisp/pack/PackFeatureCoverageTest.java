package dev.vkdisp.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vkdisp.pack.properties.ShaderProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 【参考调研】特性覆盖表的判据
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓库 {@code docs/13-GAP-REGISTRY.md} /
 *    {@code docs/19-IMPROVEMENT-PATHS.md} §7-9 里各族的现状登记（自有文档，LGPL-3.0-or-later 项目自有）；
 *    零外部代码。→ 能否并入本项目（LGPL-3.0-or-later）：可以。
 * 4. 许可证核对结论：本项目 LGPL-3.0-or-later；零第三方代码复制。
 */
final class PackFeatureCoverageTest {

    private static final String PROPERTIES = """
            screen=MAIN
            sliders=A
            profile.P=A:1
            program.composite1.enabled=A > 3
            alphaTest.gbuffers_terrain=GREATER 0.5
            blend.gbuffers_water=SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ONE_MINUS_SRC_ALPHA
            size.buffer.colortex5=0.5 0.5
            autoRes.colortex4=true
            filters.colortex2=Nearest
            shadowMapResolution=2048
            bufferObject.0=4096
            image.foo=none RED R8 UNSIGNED_BYTE false false 8 8
            someBrandNewKey=1
            """;

    private static PackFeatureCoverage.Reading find(List<PackFeatureCoverage.Reading> readings,
            String family) {
        return readings.stream().filter(r -> r.family().equals(family)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("每族按判档归类，计数含结构化视图里的那两族")
    void classifiesEveryDeclaredFamily() {
        List<PackFeatureCoverage.Reading> readings = PackFeatureCoverage.of(
                ShaderProperties.parse(PROPERTIES));

        assertEquals(PackFeatureCoverage.Status.EFFECTIVE,
                find(readings, "program.*.enabled").status());
        assertEquals(1, find(readings, "program.*.enabled").count());
        assertEquals("program.composite1.enabled=A > 3",
                find(readings, "program.*.enabled").firstLine(),
                "program.*.enabled 在分派时就离开通用表 ⇒ 计数必须从结构化视图来");

        assertEquals(3, find(readings,
                "选项与预设（screen / sliders / profile / const）").count());

        assertEquals(1, find(readings, "alphaTest.<程序>").count());
        assertEquals(PackFeatureCoverage.Status.EFFECTIVE,
                find(readings, "alphaTest.<程序>").status());

        assertEquals(PackFeatureCoverage.Status.MODEL_ONLY,
                find(readings, "bufferObject.N（SSBO）").status());
        assertEquals(PackFeatureCoverage.Status.MODEL_ONLY,
                find(readings, "image.NAME（自定义图像）").status());

        // blend 于 2026-10-17（一百九十四）被误判成「变体进不了 setPipeline」，一百九十五用逐字读
        // PipelineCache#get 的结果改判并接上：链上的步按程序取派生管线（半覆盖，见该族 note）。
        assertEquals(PackFeatureCoverage.Status.EFFECTIVE,
                find(readings, "blend.<程序>[.<缓冲>]").status());
        assertEquals(2, find(readings, "size.buffer.* / autoRes.* / relative.*").count(),
                "size. 与 autoRes. 同族");
        assertEquals(1, find(readings, "filters.<目标>").count());
        assertEquals(1, find(readings, "shadow.*（分辨率 / 剔除 / gcolor / voxelization）").count());

        PackFeatureCoverage.Reading unknown = find(readings, "未归类键");
        assertEquals(PackFeatureCoverage.Status.UNCLASSIFIED, unknown.status());
        assertEquals("someBrandNewKey=1", unknown.firstLine());
    }

    @Test
    @DisplayName("汇总串可核对（EFFECTIVE 只算真有消费方的）")
    void summarizeCountsByStatus() {
        String summary = PackFeatureCoverage.summarize(
                PackFeatureCoverage.of(ShaderProperties.parse(PROPERTIES)));
        // 实测值：size/autoRes 2 + filters 1 + shadow 1 = 4 条未兑现；blend 接线后从这条里出去。
        assertEquals("EFFECTIVE=6 MODEL_ONLY=2 UNHONORED=4 UNCLASSIFIED=1", summary, summary);
    }

    @Test
    @DisplayName("🔴 表不许领先于代码：标 EFFECTIVE 的族必须有消费方在源里")
    void effectiveRowsNeedAConsumer() throws Exception {
        java.nio.file.Path compiler =
                java.nio.file.Path.of("src/main/java/dev/vkdisp/pack/ShaderPackCompiler.java");
        java.nio.file.Path postChain =
                java.nio.file.Path.of("src/main/java/dev/vkdisp/pack/PackPostChain.java");
        if (!java.nio.file.Files.isReadable(compiler) || !java.nio.file.Files.isReadable(postChain)) {
            return;
        }
        assertTrue(java.nio.file.Files.readString(compiler).contains("AlphaTestEpilogue.apply("),
                "alphaTest 被标成 EFFECTIVE，却没有片元侧的注入点 ⇒ 表在撒谎");
        assertTrue(java.nio.file.Files.readString(postChain).contains("settings().get(\"enabled\")"),
                "program.*.enabled 被标成 EFFECTIVE，却没有消费方");

        // 正向针（一百九十五，接上之后）：blend 行是 EFFECTIVE ⇒ 链上必须真有「按程序取派生管线」的入口，
        // 而且取回来的是**已编译**对象 —— 走的是原版 PipelineCache#get 的未命中就地编译
        // （blaze3d/pipeline/PipelineCache.java:29-38），不是把 RenderPipeline 直接塞给 setPipeline。
        java.nio.file.Path frameApi =
                java.nio.file.Path.of("src/main/java/dev/vkdisp/bridge/FrameApi.java");
        String chain = java.nio.file.Files.readString(frameApi);
        java.nio.file.Path steps =
                java.nio.file.Path.of("src/main/java/dev/vkdisp/bridge/PackStepPipelines.java");
        String derived = java.nio.file.Files.readString(steps);
        assertTrue(chain.contains("PackStepPipelines.compiled("),
                "blend 被标成 EFFECTIVE，链上却没有按程序取派生管线的入口 ⇒ 表在撒谎");
        assertTrue(derived.contains("RenderSystem.getCompiledPipelineNullable(variant)"),
                "变体必须经原版查缓存拿 CompiledRenderPipeline；自己不调也不注册，靠的是 PipelineCache 的未命中编译");
        // 反向针留着：一百九十四那一次接的就是这个形状（把未编译的变体交给取编译结果的入口再往下走），
        // 当场验明会抛之后撤回。接线只能走 compiled(...)，不许退回 forStep(...)。
        assertFalse(chain.contains("PackStepPipelines.forStep("),
                "forStep 交回的是未编译的 RenderPipeline，链上只能经 compiled(...) 取用它");
    }

    @Test
    @DisplayName("前缀路由：第一个命中的族算数，未知键返回 null")
    void routesByFirstPrefix() {
        assertEquals("blend.<程序>[.<缓冲>]",
                PackFeatureCoverage.familyOf("blend.gbuffers_water").name());
        assertEquals("size.buffer.* / autoRes.* / relative.*",
                PackFeatureCoverage.familyOf("size.buffer.colortex0").name());
        assertEquals("PBR 材质图（normal. / specular. / specularGloss.）",
                PackFeatureCoverage.familyOf("normal.gbuffers_terrain").name());
        assertNull(PackFeatureCoverage.familyOf("someBrandNewKey"));
        assertNotNull(PackFeatureCoverage.familyOf("oldLighting"));
    }

    @Test
    @DisplayName("没有 shaders.properties 时不出任何读数（不造假数据）")
    void nullYieldsEmpty() {
        assertEquals(List.of(), PackFeatureCoverage.of(null));
        assertTrue(PackFeatureCoverage.of(ShaderProperties.parse("sliders=A")).size() >= 1);
    }
}
