package dev.vkdisp.pack;

import dev.vkdisp.glsl.TranslateDiagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GlslDeclarationExtractor 单元测试。
 *
 * <p>重点在**别名表的事实正确性** —— 这里的每一条都对应一个能查证的官方来源
 * （OF 的 shaders.txt Attributes 节 / Iris 的 shaders.properties 文档）；
 * 写错任何一条都会让对应属性被静默漏掉，且因为不报错而极难发现。
 */
class GlslDeclarationExtractorTest {

    @Test
    void uniformDeclarationsAreExtracted() {
        GlslDeclarationExtractor.Result result = GlslDeclarationExtractor.extract(
                "uniform mat4 gbufferModelView;\nuniform sampler2D gtexture;\n", "t.fsh", false);

        assertEquals(List.of(
                        new UniformDecl("gbufferModelView", "mat4"),
                        new UniformDecl("gtexture", "sampler2D")),
                result.uniforms());
    }

    @Test
    void coreProfileAttributeNamesMapToEnum() {
        GlslDeclarationExtractor.Result result = GlslDeclarationExtractor.extract(
                "in vec3 vaPosition;\nin vec4 vaColor;\nin vec2 vaUV0;\n", "t.vsh", true);

        assertEquals(List.of(VertexAttribute.Position, VertexAttribute.Color, VertexAttribute.UV0),
                result.attributes());
    }

    @Test
    void legacyGlAttributeNamesAlsoMap() {
        GlslDeclarationExtractor.Result result = GlslDeclarationExtractor.extract(
                "attribute vec4 gl_Vertex;\nattribute vec4 gl_Color;\n", "t.vsh", true);

        assertEquals(List.of(VertexAttribute.Position, VertexAttribute.Color), result.attributes());
    }

    @Test
    void vaNormalMapsToNormal() {
        // 核心档法线的真名是 vaNormal（Iris / OF 文档），不是 Normal —— 写成 Normal 会整项漏掉
        GlslDeclarationExtractor.Result result = GlslDeclarationExtractor.extract(
                "in vec3 vaNormal;\n", "t.vsh", true);

        assertEquals(List.of(VertexAttribute.Normal), result.attributes());
    }

    @Test
    void legacyMultiTexCoord1MapsToUv2NotUv1() {
        // Iris 文档：兼容档 gl_MultiTexCoord1 ↔ 核心档 vaUV2（lightmap）—— 编号并不对应，
        // 想当然映射成 UV1 会把法线/overlay 语义全搞错
        GlslDeclarationExtractor.Result result = GlslDeclarationExtractor.extract(
                "attribute vec4 gl_MultiTexCoord1;\n", "t.vsh", true);

        assertEquals(List.of(VertexAttribute.UV2), result.attributes());
    }

    @Test
    void bothOverlayAndLightmapNamesAreMapped() {
        GlslDeclarationExtractor.Result result = GlslDeclarationExtractor.extract(
                "in ivec2 vaUV1;\nin ivec2 vaUV2;\n", "t.vsh", true);

        assertEquals(List.of(VertexAttribute.UV1, VertexAttribute.UV2), result.attributes());
    }

    @Test
    void fragmentStageInDeclarationsAreNotVertexAttributes() {
        GlslDeclarationExtractor.Result result = GlslDeclarationExtractor.extract(
                "in vec2 vUv;\nin vec4 vColor;\nuniform mat4 projectionMatrix;\n", "t.fsh", false);

        assertTrue(result.attributes().isEmpty(), "片元的 in 是插值输入，不该被当作顶点属性");
        assertEquals(1, result.uniforms().size(), "uniform 与阶段无关，仍应提取");
    }

    @Test
    void unknownAttributeNameIsReportedNotGuessed() {
        GlslDeclarationExtractor.Result result = GlslDeclarationExtractor.extract(
                "in vec3 mysteryAttribute;\n", "t.vsh", true);

        assertTrue(result.attributes().isEmpty(), "无权威来源的名字不许映射（X9 不猜值）");
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                        diagnostic.severity() == TranslateDiagnostic.Severity.INFO
                                && diagnostic.message().contains("mysteryAttribute")),
                () -> "未知属性名必须显式可见，实际: " + result.diagnostics());
    }

    @Test
    void uniformBlockIsReportedAndMembersAreNotParsed() {
        GlslDeclarationExtractor.Result result = GlslDeclarationExtractor.extract(
                "uniform Camera {\n    mat4 projection;\n    mat4 view;\n};\n", "t.fsh", false);

        assertTrue(result.uniforms().isEmpty(), "块内成员不由本阶段解析");
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                        diagnostic.message().contains("uniform 块")),
                () -> "uniform 块必须显式可见，实际: " + result.diagnostics());
    }

    @Test
    void commentsNeverProduceDeclarations() {
        GlslDeclarationExtractor.Result result = GlslDeclarationExtractor.extract(
                "// uniform mat4 fakeLine;\n"
                        + "/* attribute vec3 fakeBlock; */\n"
                        + "/* 跨行块注释\n"
                        + "   in vec3 alsoFake;\n"
                        + "   */\n"
                        + "uniform mat4 realOne;\n",
                "t.vsh", true);

        assertEquals(List.of(new UniformDecl("realOne", "mat4")), result.uniforms());
        assertTrue(result.attributes().isEmpty(), "注释里的伪声明一条都不能被提取");
    }

    @Test
    void duplicateNamesWithinFileAreRecordedOnce() {
        GlslDeclarationExtractor.Result result = GlslDeclarationExtractor.extract(
                "uniform mat4 matrix;\nuniform mat4 matrix;\nin vec3 vaPosition;\nin vec3 vaPosition;\n",
                "t.vsh", true);

        assertEquals(1, result.uniforms().size());
        assertEquals(1, result.attributes().size());
    }

    @Test
    void halfDeclarationIsReportedNotCrashed() {
        GlslDeclarationExtractor.Result result = GlslDeclarationExtractor.extract(
                "uniform ;\nattribute vec3;\n", "t.vsh", true);

        assertTrue(result.uniforms().isEmpty());
        assertTrue(result.attributes().isEmpty());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                        diagnostic.severity() == TranslateDiagnostic.Severity.WARN),
                () -> "半截声明应显式 WARN，实际: " + result.diagnostics());
    }

    @Test
    void nullAndBlankSourceYieldEmptyResult() {
        assertTrue(GlslDeclarationExtractor.extract(null, "t.vsh", true).uniforms().isEmpty());
        assertTrue(GlslDeclarationExtractor.extract("   \n  ", "t.vsh", true).attributes().isEmpty());
        assertTrue(GlslDeclarationExtractor.extract("", null, true).diagnostics().isEmpty());
    }

    @Test
    void aliasTableIsConsistentAndOmitsUndeterminedNames() {
        assertNotNull(GlslDeclarationExtractor.aliasSnapshot());
        assertEquals(VertexAttribute.Position,
                GlslDeclarationExtractor.aliasSnapshot().get("vaPosition"));
        assertEquals(VertexAttribute.UV2,
                GlslDeclarationExtractor.aliasSnapshot().get("gl_MultiTexCoord1"));
        assertFalse(GlslDeclarationExtractor.aliasSnapshot().containsKey("gl_MultiTexCoord2"),
                "对应关系无权威来源的名字不该进表（X9）");
    }
}
