package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】E 线单测 / PipelineCache 键计算（任务 ②：同 Program 稳定、不同 Program 不碰撞）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① docs/04-SPEC.md §3.3「PipelineCache：避免重复构建；着色器重载时清理」；
 *    ② docs/08-TESTING.md §5/§6（stride 不匹配 = 彩色尖刺；管线缓存按 program 键，顶点格式变了旧管线残留）；
 *    ③ docs/18-PARALLEL.md §7.3（并行线证据：键唯一性测试）。
 *    许可证：JUnit 5 = EPL-2.0（仅 testImplementation 依赖，不进分发 jar）；本仓库 docs 为 MIT 项目自有文档。
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触 —— 按 docs/07-CONSTRAINTS.md L12
 *    「判不过就换参考，不再读它的代码」。
 *    → 能否并入本项目（MIT）：可以（本文件为独立编写的测试）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：JUnit 5 标准断言；键的唯一性/稳定性判据来自本仓库 docs/04-SPEC.md §3.3（PipelineCache）
 *    与 docs/08-TESTING.md §6（管线缓存按 program 键、顶点格式变了旧管线残留）。
 * 2. 备选：手写 main() 断言 —— 否决（F4 已接好 JUnit 5，失败要能在 ./gradlew test 里显式红）。
 * 3. 我们的差异点：用「逐字段只改一项，键必须变」的变体矩阵证唯一性；用「同输入两次计算结果相等」证稳定性；
 *    指纹用独立重算的 SHA-256 校验，避免自证循环。
 * 4. 许可证核对：本项目 MIT；JUnit EPL-2.0 仅测试期依赖；零第三方代码复制。
 * 5. 性能基线：测试代码不进运行时，无性能影响（18-PARALLEL §7.7）。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.vkdisp.pack.VertexAttribute;

/** 缓存键：稳定性 + 唯一性 + 单射编码 + 半非法输入拒绝。 */
class PipelineCacheKeyTest {

    private static PipelineSpecIr spec(
            String programId, String location, String vertexShader, String fragmentShader, boolean optional) {
        return new PipelineSpecIr(programId, location, vertexShader, fragmentShader,
                List.of("POSITION_COLOR_TEXTURE"), optional);
    }

    private static PipelineSpecIr compositeSpec(String programId) {
        return new PipelineSpecIr(programId,
                "vkdisp:pipeline/" + programId,
                "vkdisp:fullscreen",
                "vkdisp:" + programId,
                List.of(),
                false);
    }

    private static VertexLayout terrainLayout() {
        return VertexLayout.of(List.of(
                VertexAttribute.Position,
                VertexAttribute.Color,
                VertexAttribute.UV0,
                VertexAttribute.UV1,
                VertexAttribute.UV2,
                VertexAttribute.Normal,
                VertexAttribute.mc_Entity,
                VertexAttribute.mc_midTexCoord));
    }

    private static BindGroupLayoutIr terrainBindings() {
        return BindGroupLayoutIr.builder()
                .sampler("colortex0")
                .sampler("colortex1")
                .uniformBuffer("OfSceneParams")
                .build();
    }

    private static PipelineCacheKey terrainKey(String programId) {
        return PipelineCacheKey.of(spec(programId, "vkdisp:pipeline/" + programId,
                "vkdisp:gbuffers_terrain", "vkdisp:gbuffers_terrain", false), terrainLayout(), terrainBindings());
    }

    @Test
    void sameProgramIsStableAcrossRepeatedComputation() {
        PipelineCacheKey first = terrainKey("gbuffers_terrain");
        PipelineCacheKey second = terrainKey("gbuffers_terrain");
        assertEquals(first, second, "同一 Program 两次计算必须相等");
        assertEquals(first.canonicalText(), second.canonicalText(), "规范文本必须逐字符一致");
        assertEquals(first.fingerprint(), second.fingerprint(), "指纹必须一致");
    }

    @Test
    void distinctProgramsDoNotCollide() {
        Set<String> texts = new LinkedHashSet<>();
        Set<String> fingerprints = new LinkedHashSet<>();
        for (int i = 0; i < 8; i++) {
            PipelineCacheKey key = terrainKey("program" + i);
            texts.add(key.canonicalText());
            fingerprints.add(key.fingerprint());
        }
        assertEquals(8, texts.size(), "8 个不同 Program 必须得到 8 个不同键文本");
        assertEquals(8, fingerprints.size(), "指纹也必须两两不同");
    }

    @Test
    void attributeTypeChangeChangesKeyEvenWhenNamesAreIdentical() {
        VertexLayout vec3fLayout = VertexLayout.ofDeclarations(List.of(
                new AttributeDecl("Position", "vec3f"),
                new AttributeDecl("UV0", "vec2f")));
        VertexLayout vec2fLayout = VertexLayout.ofDeclarations(List.of(
                new AttributeDecl("Position", "vec2f"),
                new AttributeDecl("UV0", "vec2f")));
        assertEquals(vec3fLayout.attributeCount(), vec2fLayout.attributeCount(), "属性名集合相同");
        assertEquals(20, vec3fLayout.stride());
        assertEquals(16, vec2fLayout.stride());

        PipelineSpecIr pipeline = compositeSpec("gbuffers_terrain");
        PipelineCacheKey vec3fKey = PipelineCacheKey.of(pipeline, vec3fLayout, terrainBindings());
        PipelineCacheKey vec2fKey = PipelineCacheKey.of(pipeline, vec2fLayout, terrainBindings());
        assertNotEquals(vec3fKey, vec2fKey, "顶点格式（类型 → stride）变了必须换键，否则复用旧管线 = 彩色尖刺");
    }

    @Test
    void attributeOrderChangeChangesKey() {
        PipelineSpecIr pipeline = compositeSpec("gbuffers_terrain");
        VertexLayout positionFirst = VertexLayout.of(VertexAttribute.Position, VertexAttribute.UV0);
        VertexLayout uvFirst = VertexLayout.of(VertexAttribute.UV0, VertexAttribute.Position);
        assertNotEquals(
                PipelineCacheKey.of(pipeline, positionFirst, terrainBindings()),
                PipelineCacheKey.of(pipeline, uvFirst, terrainBindings()),
                "offset 布局不同 = 构建结果不同 = 键必须不同");
    }

    @Test
    void optionalFlagChangesKey() {
        VertexLayout layout = terrainLayout();
        BindGroupLayoutIr bindings = terrainBindings();
        PipelineCacheKey required = PipelineCacheKey.of(
                spec("gbuffers_terrain", "vkdisp:pipeline/a", "vkdisp:v", "vkdisp:f", false), layout, bindings);
        PipelineCacheKey optional = PipelineCacheKey.of(
                spec("gbuffers_terrain", "vkdisp:pipeline/a", "vkdisp:v", "vkdisp:f", true), layout, bindings);
        assertNotEquals(required, optional);
    }

    @Test
    void bindGroupLayoutChangeChangesKey() {
        PipelineSpecIr pipeline = compositeSpec("composite1");
        VertexLayout layout = VertexLayout.of();
        PipelineCacheKey withoutDepth = PipelineCacheKey.of(pipeline, layout,
                BindGroupLayoutIr.builder().sampler("colortex0").build());
        PipelineCacheKey withDepth = PipelineCacheKey.of(pipeline, layout,
                BindGroupLayoutIr.builder().sampler("colortex0").sampler("depthtex0").build());
        assertNotEquals(withoutDepth, withDepth);
        assertEquals(0, layout.stride(), "全屏管线无顶点属性 → stride 0");
    }

    @Test
    void shaderLocationAndVertexBindingNameChangesChangeKey() {
        VertexLayout layout = terrainLayout();
        BindGroupLayoutIr bindings = terrainBindings();
        PipelineCacheKey base = PipelineCacheKey.of(
                spec("gbuffers_terrain", "vkdisp:pipeline/a", "vkdisp:v", "vkdisp:f", false), layout, bindings);
        PipelineCacheKey otherShader = PipelineCacheKey.of(
                spec("gbuffers_terrain", "vkdisp:pipeline/a", "vkdisp:v2", "vkdisp:f", false), layout, bindings);
        PipelineCacheKey otherLocation = PipelineCacheKey.of(
                spec("gbuffers_terrain", "vkdisp:pipeline/b", "vkdisp:v", "vkdisp:f", false), layout, bindings);
        PipelineCacheKey otherBindings = PipelineCacheKey.of(
                new PipelineSpecIr("gbuffers_terrain", "vkdisp:pipeline/a", "vkdisp:v", "vkdisp:f",
                        List.of("POSITION_COLOR"), false),
                layout, bindings);
        assertNotEquals(base, otherShader);
        assertNotEquals(base, otherLocation);
        assertNotEquals(base, otherBindings);
    }

    @Test
    void bindingNameListEncodingIsInjective() {
        VertexLayout layout = terrainLayout();
        BindGroupLayoutIr bindings = terrainBindings();
        PipelineCacheKey singleLong = PipelineCacheKey.of(
                new PipelineSpecIr("p", "l", "v", "f", List.of("AB"), false), layout, bindings);
        PipelineCacheKey twoShort = PipelineCacheKey.of(
                new PipelineSpecIr("p", "l", "v", "f", List.of("A", "B"), false), layout, bindings);
        assertNotEquals(singleLong, twoShort, "列表编码必须带元素个数前缀，避免 [AB] 与 [A,B] 碰撞");
    }

    @Test
    void fieldsWithSeparatorCharactersDoNotCollide() {
        VertexLayout layout = terrainLayout();
        BindGroupLayoutIr bindings = terrainBindings();
        PipelineCacheKey first = PipelineCacheKey.of(
                new PipelineSpecIr("a", "b:c", "v", "f", List.of(), false), layout, bindings);
        PipelineCacheKey second = PipelineCacheKey.of(
                new PipelineSpecIr("a\nlocation", "c", "v", "f", List.of(), false), layout, bindings);
        assertNotEquals(first, second, "长度前缀编码保证字段边界与值内容无关（单射）");
    }

    @Test
    void fingerprintIsSha256OfCanonicalText() throws Exception {
        PipelineCacheKey key = terrainKey("gbuffers_terrain");
        byte[] expected = MessageDigest.getInstance("SHA-256")
                .digest(key.canonicalText().getBytes(StandardCharsets.UTF_8));
        assertEquals(HexFormat.of().formatHex(expected), key.fingerprint());
        assertEquals(64, key.fingerprint().length());
        assertTrue(key.fingerprint().matches("[0-9a-f]{64}"), "指纹必须是小写十六进制");
    }

    @Test
    void diagnosticsDoNotEnterTheKey() {
        VertexLayout layout = terrainLayout();
        PipelineCacheKey clean = PipelineCacheKey.of(
                compositeSpec("composite1"), layout,
                BindGroupLayoutIr.builder().sampler("colortex0").build());
        PipelineCacheKey warnOnly = PipelineCacheKey.of(
                compositeSpec("composite1"), layout,
                BindGroupLayoutIr.builder().sampler("colortex0").sampler("colortex0").build());
        assertEquals(clean, warnOnly, "WARN 级降级后中间表示内容相同 → 键相同（键只描述内容，不描述过程）");
    }

    @Test
    void blankSpecFieldsAreRejectedExplicitly() {
        VertexLayout layout = terrainLayout();
        BindGroupLayoutIr bindings = terrainBindings();
        assertThrows(IllegalArgumentException.class, () -> PipelineCacheKey.of(
                new PipelineSpecIr("  ", "l", "v", "f", List.of(), false), layout, bindings));
        assertThrows(IllegalArgumentException.class, () -> PipelineCacheKey.of(
                new PipelineSpecIr("p", "", "v", "f", List.of(), false), layout, bindings));
        assertThrows(IllegalArgumentException.class, () -> PipelineCacheKey.of(
                new PipelineSpecIr("p", "l", " ", "f", List.of(), false), layout, bindings));
        assertThrows(IllegalArgumentException.class, () -> PipelineCacheKey.of(
                new PipelineSpecIr("p", "l", "v", "", List.of(), false), layout, bindings));
    }

    @Test
    void unsafeBindingNamesAreRejectedExplicitly() {
        VertexLayout layout = terrainLayout();
        BindGroupLayoutIr bindings = terrainBindings();
        assertThrows(IllegalArgumentException.class, () -> PipelineCacheKey.of(
                new PipelineSpecIr("p", "l", "v", "f", List.of("A|B"), false), layout, bindings));
        assertThrows(IllegalArgumentException.class, () -> PipelineCacheKey.of(
                new PipelineSpecIr("p", "l", "v", "f", List.of("A B"), false), layout, bindings));
        assertThrows(IllegalArgumentException.class, () -> PipelineCacheKey.of(
                new PipelineSpecIr("p", "l", "v", "f", List.of("  "), false), layout, bindings));
    }

    @Test
    void layoutsWithErrorsAreRejectedExplicitly() {
        VertexLayout brokenLayout = VertexLayout.ofDeclarations(List.of(new AttributeDecl("Bogus", "vec5f")));
        BindGroupLayoutIr bindings = terrainBindings();
        IllegalArgumentException layoutFailure = assertThrows(IllegalArgumentException.class,
                () -> PipelineCacheKey.of(compositeSpec("p"), brokenLayout, bindings));
        assertTrue(layoutFailure.getMessage().contains("UNKNOWN_ATTRIBUTE_TYPE"),
                "异常必须带诊断原文：" + layoutFailure.getMessage());

        BindGroupLayoutIr brokenBindings = BindGroupLayoutIr.builder().sampler(" ").build();
        IllegalArgumentException bindingFailure = assertThrows(IllegalArgumentException.class,
                () -> PipelineCacheKey.of(compositeSpec("p"), VertexLayout.of(), brokenBindings));
        assertTrue(bindingFailure.getMessage().contains("BAD_BINDING_NAME"), bindingFailure.getMessage());
    }

    @Test
    void nullInputsAreRejected() {
        assertThrows(NullPointerException.class,
                () -> PipelineCacheKey.of(null, terrainLayout(), terrainBindings()));
        assertThrows(NullPointerException.class,
                () -> PipelineCacheKey.of(compositeSpec("p"), null, terrainBindings()));
        assertThrows(NullPointerException.class,
                () -> PipelineCacheKey.of(compositeSpec("p"), terrainLayout(), null));
    }
}
