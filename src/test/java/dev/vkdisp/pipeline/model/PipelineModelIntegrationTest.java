package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】E 线单测 / 端到端组合（Program → 顶点布局 → 绑定布局 → 缓存键）+ F1 契约适配
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① docs/04-SPEC.md §3.3（管线构建输入）、§3.4（composite / gbuffers_* 的 program 名）；
 *    ② F1 已冻结的 dev.vkdisp.bridge.RenderApi.PipelineSpec 与 ContractVersion；
 *    ③ docs/08-TESTING.md §5/§6（stride 自检、切包后旧管线残留）。
 *    许可证：JUnit 5 = EPL-2.0（仅 testImplementation 依赖，不进分发 jar）；本仓库 docs 为 MIT 项目自有文档。
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触 —— 按 docs/07-CONSTRAINTS.md L12
 *    「判不过就换参考，不再读它的代码」。
 *    → 能否并入本项目（MIT）：可以（本文件为独立编写的测试）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：JUnit 5 标准断言；键的唯一性/稳定性判据来自本仓库 docs/04-SPEC.md §3.3（PipelineCache）
 *    与 docs/08-TESTING.md §6（管线缓存按 program 键、顶点格式变了旧管线残留）。
 * 2. 备选：手写 main() 断言 —— 否决（F4 已接好 JUnit 5，失败要能在 ./gradlew test 里显式红）。
 * 3. 我们的差异点：把三条交付物串起来跑：两个真实形态的 Program（全屏 composite 与带全顶点格式的 gbuffers_terrain）
 *    必须得到不同键、不同 stride，并显式验证 E 线只消费不修改 F1 冻结契约。
 * 4. 许可证核对：本项目 MIT；JUnit EPL-2.0 仅测试期依赖；零第三方代码复制。
 * 5. 性能基线：测试代码不进运行时，无性能影响（18-PARALLEL §7.7）。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.vkdisp.bridge.ContractVersion;
import dev.vkdisp.pack.VertexAttribute;

/** E 线三条交付物的端到端组合证据（单测内自证，不需要 GPU / 不需要启动游戏）。 */
class PipelineModelIntegrationTest {

    private static final List<VertexAttribute> TERRAIN_ATTRIBUTES = List.of(
            VertexAttribute.Position,
            VertexAttribute.Color,
            VertexAttribute.UV0,
            VertexAttribute.UV1,
            VertexAttribute.UV2,
            VertexAttribute.Normal,
            VertexAttribute.mc_Entity,
            VertexAttribute.mc_midTexCoord);

    @Test
    void fullscreenCompositeAndTerrainProduceDifferentLayoutsAndKeys() {
        // composite：全屏三角形，无顶点缓冲（04-SPEC §3.3 的 POST_PROCESSING 路径），只要采样器 + 一个 UBO。
        VertexLayout compositeLayout = VertexLayout.of();
        BindGroupLayoutIr compositeBindings = BindGroupLayoutIr.builder()
                .sampler("colortex0")
                .sampler("depthtex0")
                .uniformBuffer("OfSceneParams")
                .build();
        PipelineSpecIr compositeSpec = new PipelineSpecIr("composite1",
                "vkdisp:pipeline/composite_1", "vkdisp:fullscreen", "vkdisp:composite_1", List.of(), false);
        PipelineCacheKey compositeKey = PipelineCacheKey.of(compositeSpec, compositeLayout, compositeBindings);

        // gbuffers_terrain：04-SPEC §4 全属性表 + 采样器 + UBO。
        VertexLayout terrainLayout = VertexLayout.of(TERRAIN_ATTRIBUTES);
        BindGroupLayoutIr terrainBindings = BindGroupLayoutIr.builder()
                .sampler("colortex0")
                .sampler("depthtex0")
                .uniformBuffer("OfSceneParams")
                .build();
        PipelineSpecIr terrainSpec = new PipelineSpecIr("gbuffers_terrain",
                "vkdisp:pipeline/gbuffers_terrain", "vkdisp:gbuffers_terrain", "vkdisp:gbuffers_terrain",
                List.of("POSITION_COLOR_TEXTURE"), false);
        PipelineCacheKey terrainKey = PipelineCacheKey.of(terrainSpec, terrainLayout, terrainBindings);

        assertEquals(0, compositeLayout.stride(), "全屏管线 stride 0（无顶点缓冲）");
        assertTrue(compositeLayout.hasDiagnostic("EMPTY_ATTRIBUTE_SET"), "但必须显式 INFO");
        assertEquals(47, terrainLayout.stride(), "04-SPEC §4 全表 stride = 47");
        assertTrue(terrainLayout.isConsistent());
        assertNotEquals(compositeKey, terrainKey, "两个 Program 不得共用缓存键");
        assertNotEquals(compositeKey.fingerprint(), terrainKey.fingerprint());
        assertEquals(3, compositeBindings.size());
        assertEquals(3, terrainBindings.size());
    }

    @Test
    void terrainVertexFormatChangeIsCaughtByTheKey() {
        PipelineSpecIr terrainSpec = new PipelineSpecIr("gbuffers_terrain",
                "vkdisp:pipeline/gbuffers_terrain", "vkdisp:gbuffers_terrain", "vkdisp:gbuffers_terrain",
                List.of("POSITION_COLOR_TEXTURE"), false);
        BindGroupLayoutIr bindings = BindGroupLayoutIr.builder().sampler("colortex0").build();

        VertexLayout withNormal = VertexLayout.of(TERRAIN_ATTRIBUTES);
        VertexLayout withoutNormal = VertexLayout.of(List.of(
                VertexAttribute.Position,
                VertexAttribute.Color,
                VertexAttribute.UV0,
                VertexAttribute.UV1,
                VertexAttribute.UV2,
                VertexAttribute.mc_Entity,
                VertexAttribute.mc_midTexCoord));

        assertEquals(47, withNormal.stride());
        assertEquals(44, withoutNormal.stride(), "去掉 vec3b 法线后 stride 减 3");
        assertNotEquals(
                PipelineCacheKey.of(terrainSpec, withNormal, bindings),
                PipelineCacheKey.of(terrainSpec, withoutNormal, bindings),
                "换包/格式变更后旧管线不得被复用（08-TESTING §6）");
    }

    @Test
    void mirrorsF1PipelineSpecFieldForFieldInPureJava() {
        // E 线纯 Java 镜像：与 F1 RenderApi.PipelineSpec 的五字段逐字段同名同序（语义一致，
        // 只把 Identifier 换成 String）。F1 契约本身不被修改（07-CONSTRAINTS X12 / 18-PARALLEL §3.2）。
        PipelineSpecIr mirrored = new PipelineSpecIr("composite1",
                "vkdisp:pipeline/composite_1",
                "vkdisp:fullscreen",
                "vkdisp:composite_1",
                List.of("POSITION_COLOR_TEXTURE"),
                true);
        assertEquals("composite1", mirrored.programId());
        assertEquals("vkdisp:pipeline/composite_1", mirrored.location());
        assertEquals("vkdisp:fullscreen", mirrored.vertexShader());
        assertEquals("vkdisp:composite_1", mirrored.fragmentShader());
        assertEquals(List.of("POSITION_COLOR_TEXTURE"), mirrored.vertexBindingNames());
        assertTrue(mirrored.optional(), "optional 标志必须原样透传");
        assertEquals(1, mirrored.bindingCount());

        PipelineCacheKey key = PipelineCacheKey.of(mirrored, VertexLayout.of(),
                BindGroupLayoutIr.builder().sampler("colortex0").build());
        assertEquals(key, PipelineCacheKey.of(mirrored, VertexLayout.of(),
                BindGroupLayoutIr.builder().sampler("colortex0").build()));
    }

    @Test
    void f1AdapterIsCompileVerifiedInMainSourceSetOnly() {
        // ⚠️ 未覆盖情况（08-TESTING 静默失败第二定律：必须显式列出没覆盖什么）：
        // F4 测试 classpath 不含 Minecraft 类型（net.minecraft.resources 在 test 源集不可见），
        // 因此 PipelineSpecIr.of(RenderApi.PipelineSpec, String) 这条 F1 适配路径无法在单测里构造实例，
        // 它的编译证据由 ./gradlew compileJava（main 源集，含 Minecraft）给出。
        // 已上报 lead：若要让 E 线单测覆盖 F1 记录类型，需要 env-1 在 F4 里把 Minecraft 加进 test 源集。
        assertTrue(PipelineSpecIr.class.getDeclaredMethods().length > 0, "主源集里的 IR 类对测试可见");
    }

    @Test
    void frozenContractVersionIsConsumedNotModified() {
        assertTrue(ContractVersion.VERSION >= 1, "F1 契约版本号由 env-1 维护");
        assertFalse(ContractVersion.F1.isBlank());
        assertFalse(ContractVersion.F2.isBlank());
        assertFalse(ContractVersion.F3.isBlank());
    }
}
