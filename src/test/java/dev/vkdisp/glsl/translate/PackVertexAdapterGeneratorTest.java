package dev.vkdisp.glsl.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vkdisp.pipeline.model.PackTerrainProgram;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 顶点适配层**生成器**的单测（纯文本）。
 *
 * <p>🔖 为什么这组断言是硬门槛：varying 少供一条，驱动层就在资源加载期抛
 * {@code ShaderCompileException: missing output at location N}，<b>客户端根本起不来</b>
 * （本轮 runClient 实测）。而无头单测能在跑客户端之前就把这件事钉住。
 */
class PackVertexAdapterGeneratorTest {

    private static final Pattern OUT_DECL = Pattern.compile(
            "^layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*out\\s+([A-Za-z_]\\w*)\\s+([A-Za-z_]\\w*)\\s*;",
            Pattern.MULTILINE);

    private static List<String> declaredOuts(String glsl) {
        Matcher m = OUT_DECL.matcher(glsl);
        List<String> out = new java.util.ArrayList<>();
        while (m.find()) {
            out.add(m.group(1) + ":" + m.group(2) + ":" + m.group(3));
        }
        return out;
    }

    @Test
    @DisplayName("🔖 生成的适配层必须**逐位置逐类型逐名字**复刻包的 varying 契约")
    void generatedAdapterMatchesContractExactly() {
        List<PackTerrainProgram.Input> inputs = List.of(
                new PackTerrainProgram.Input(0, "float", "mat"),
                new PackTerrainProgram.Input(1, "float", "recolor"),
                new PackTerrainProgram.Input(2, "vec2", "texCoord"),
                new PackTerrainProgram.Input(8, "vec4", "color"));
        PackVertexAdapterGenerator.Result r = PackVertexAdapterGenerator.generate(inputs, false);
        assertEquals(List.of("0:float:mat", "1:float:recolor", "2:vec2:texCoord", "8:vec4:color"),
                declaredOuts(r.glsl()),
                "🔖 少一条就链接失败、多一条无害但会掩盖漏供 —— 必须逐条对齐契约");
    }

    @Test
    @DisplayName("🔖 15 条 varying（BSL 开高级材质后的实测形态）也要全供，且不崩")
    void fifteenVaryingsAreAllSupplied() {
        List<PackTerrainProgram.Input> inputs = List.of(
                new PackTerrainProgram.Input(0, "float", "mat"),
                new PackTerrainProgram.Input(1, "float", "recolor"),
                new PackTerrainProgram.Input(2, "vec2", "texCoord"),
                new PackTerrainProgram.Input(3, "vec2", "lmCoord"),
                new PackTerrainProgram.Input(4, "vec3", "normal"),
                new PackTerrainProgram.Input(5, "vec3", "sunVec"),
                new PackTerrainProgram.Input(6, "vec3", "upVec"),
                new PackTerrainProgram.Input(7, "vec3", "eastVec"),
                new PackTerrainProgram.Input(8, "vec4", "color"),
                new PackTerrainProgram.Input(9, "float", "dist"),
                new PackTerrainProgram.Input(10, "vec3", "binormal"),
                new PackTerrainProgram.Input(11, "vec3", "tangent"),
                new PackTerrainProgram.Input(12, "vec3", "viewVector"),
                new PackTerrainProgram.Input(13, "vec4", "vTexCoord"),
                new PackTerrainProgram.Input(14, "vec4", "vTexCoordAM"));
        PackVertexAdapterGenerator.Result r = PackVertexAdapterGenerator.generate(inputs, false);
        assertEquals(15, declaredOuts(r.glsl()).size(),
                "🔖 **15 条必须全供** —— 本轮 runClient 正是缺第 15 条导致资源加载失败、客户端起不来");
        assertEquals(List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14"),
                declaredOuts(r.glsl()).stream().map(s -> s.split(":")[0]).toList());
    }

    @Test
    @DisplayName("🔖 常量供值逐条记账并出 WARN（不假装、不静默）")
    void constantSuppliesAreAccounted() {
        PackVertexAdapterGenerator.Result r = PackVertexAdapterGenerator.generate(List.of(
                new PackTerrainProgram.Input(0, "float", "mat"),
                new PackTerrainProgram.Input(2, "vec2", "texCoord")), false);
        assertEquals(List.of("mat"), r.constantSupplies(),
                "mat 只能按常量供（GAP-007），必须记账");
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.message().contains("GAP-007")),
                "常量供值必须留可见痕迹，否则画面差异会被当成包本身的效果");
        assertTrue(!r.constantSupplies().contains("texCoord"), "texCoord 是真值，不得记成常量");
    }

    @Test
    @DisplayName("🔖 不认识的 varying ⇒ 类型零值 + WARN（**绝不猜一个像的值**）")
    void unknownVaryingGetsZeroValueAndWarning() {
        PackVertexAdapterGenerator.Result r = PackVertexAdapterGenerator.generate(List.of(
                new PackTerrainProgram.Input(5, "vec3", "somePackSpecificThing")), false);
        assertTrue(r.glsl().contains("somePackSpecificThing = vec3(0.0)"));
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.message().contains("不认识")),
                "不认识的名字必须显式告警（X9 不猜）");
    }

    @Test
    @DisplayName("🔖 生成物必须声明原版地形顶点属性（否则地形根本没有顶点数据）")
    void declaresVanillaTerrainAttributes() {
        String glsl = PackVertexAdapterGenerator.generate(List.of(
                new PackTerrainProgram.Input(0, "float", "mat")), false).glsl();
        assertTrue(glsl.contains("in vec3 Position"));
        assertTrue(glsl.contains("in vec4 Color"));
        assertTrue(glsl.contains("in vec2 UV0"));
        assertTrue(glsl.contains("in ivec2 UV2"));
        assertTrue(glsl.contains("uniform VkDispTerrainParams"),
                "适配层要读 GAP-004 那个块拿眼空间太阳方向（块名必须与绑定布局逐字一致）");
        assertTrue(glsl.contains("gl_Position"), "没有 gl_Position 的顶点着色器没有意义");
    }
}
