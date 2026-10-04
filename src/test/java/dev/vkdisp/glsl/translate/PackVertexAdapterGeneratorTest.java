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
        PackVertexAdapterGenerator.Result r = PackVertexAdapterGenerator.generate(inputs, false, false, false);
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
        PackVertexAdapterGenerator.Result r = PackVertexAdapterGenerator.generate(inputs, false, false, false);
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
                new PackTerrainProgram.Input(2, "vec2", "texCoord")), false, false, false);
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
                new PackTerrainProgram.Input(5, "vec3", "somePackSpecificThing")), false, false, false);
        assertTrue(r.glsl().contains("somePackSpecificThing = vec3(0.0)"));
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.message().contains("不认识")),
                "不认识的名字必须显式告警（X9 不猜）");
    }

    @Test
    @DisplayName("🔖 生成物必须声明原版地形顶点属性（否则地形根本没有顶点数据）")
    void declaresVanillaTerrainAttributes() {
        String glsl = PackVertexAdapterGenerator.generate(List.of(
                new PackTerrainProgram.Input(0, "float", "mat")), false, false, false).glsl();
        assertTrue(glsl.contains("in vec3 Position"));
        assertTrue(glsl.contains("in vec4 Color"));
        assertTrue(glsl.contains("in vec2 UV0"));
        assertTrue(glsl.contains("in ivec2 UV2"));
        assertTrue(glsl.contains("uniform VkDispTerrainParams"),
                "适配层要读 GAP-004 那个块拿眼空间太阳方向（块名必须与绑定布局逐字一致）");
        assertTrue(glsl.contains("gl_Position"), "没有 gl_Position 的顶点着色器没有意义");
    }

    @Test
    @DisplayName("🔍 X44 回归：诊断开关产出的赋值行必须以分号收尾（行尾注释会把分号吃掉）")
    void generatedAssignmentsAlwaysTerminated() {
        // 🔖 本轮真踩到：表达里带 `// ...` 注释时，后面拼上的 `;` 被注释吃掉，
        //   生成 `lmCoord = vec2(1.0)  // ...;` ⇒ 驱动层 GLSL 解析失败
        //   ⇒ 6 条地形管线全部加载失败 ⇒ **资源重载抛异常 ⇒ 游戏根本起不来**。
        //   ⇒ 断言「每一条赋值行都以 `;` 结尾」，把这条坑钉死。
        PackVertexAdapterGenerator.Result r = PackVertexAdapterGenerator.generate(List.of(
                new PackTerrainProgram.Input(3, "vec2", "lmCoord"),
                new PackTerrainProgram.Input(5, "vec3", "sunVec"),
                new PackTerrainProgram.Input(0, "float", "mat")), true, false, false);
        int assignments = 0;
        for (String line : r.glsl().lines().toList()) {
            String t = line.strip();
            if (t.startsWith("lmCoord") || t.startsWith("sunVec") || t.startsWith("mat ")) {
                assignments++;
                assertTrue(t.endsWith(";"),
                        "🔍 赋值行必须以分号收尾，实际: <" + t + ">"
                                + "（行尾注释会把分号吃掉 ⇒ 整批管线加载失败）");
            }
        }
        assertEquals(3, assignments, "三条赋值都必须产出");
    }

    @Test
    @DisplayName("🔍 X45 回归：诊断开关必须**在诊断文本里自报状态**")
    void probeSelfReportsItsState() {
        // 🔍 不自报的话，「开关没生效」与「结论不成立」无法区分 ⇒ 实验结论不可信。
        String off = PackVertexAdapterGenerator.generate(
                List.of(new PackTerrainProgram.Input(3, "vec2", "lmCoord")), false, false, false)
                .diagnostics().stream().map(Object::toString).reduce("", (a, b) -> a + b);
        String on = PackVertexAdapterGenerator.generate(
                List.of(new PackTerrainProgram.Input(3, "vec2", "lmCoord")), true, false, false)
                .diagnostics().stream().map(Object::toString).reduce("", (a, b) -> a + b);
        assertTrue(off.contains("lmCoord=满光照诊断开关=关"),
                "🔍 关闭时也要自报，否则无法判断「开关是否生效」");
        assertTrue(on.contains("lmCoord=满光照诊断开关=已开启"),
                "🔍 开启时必须自报已开启");
        assertTrue(on.contains("开关没生效"),
                "🔍 开启时必须给出「若结果与常量一致说明开关没生效」的判读指引");
    }

    @Test
    @DisplayName("`U0001f50d h15 根因探针：color 开启时必须被强制成 vec4(1.0)")
    void colorProbeForcesWhite() {
        PackVertexAdapterGenerator.Result on = PackVertexAdapterGenerator.generate(
                List.of(new PackTerrainProgram.Input(8, "vec4", "color")),
                false, false, true);
        PackVertexAdapterGenerator.Result off = PackVertexAdapterGenerator.generate(
                List.of(new PackTerrainProgram.Input(8, "vec4", "color")),
                false, false, false);
        assertTrue(on.glsl().contains("color = vec4(1.0);"),
                "`U0001f50d 判据是 albedo 首行的乘子：color.rgb 若为 0 则 albedo 恒为 0");
        assertTrue(off.glsl().contains("color = vkdispAdapterColor;"),
                "`U0001f516 默认必须是原版 Color 属性的真值（探针默认关）");
        assertTrue(on.diagnostics().stream().anyMatch(
                d -> d.message().contains("color=强制白诊断开关=已开启")),
                "`U0001f50d X45：必须自报开关状态");
        assertTrue(off.diagnostics().stream().anyMatch(
                d -> d.message().contains("color=强制白诊断开关=关")),
                "`U0001f50d 关闭时也要自报");
    }

    @Test
    @DisplayName("`U0001f50e color 探针必须只改 color 一个 varying（单变量）")
    void colorProbeIsSingleVariable() {
        List<PackTerrainProgram.Input> inputs = List.of(
                new PackTerrainProgram.Input(8, "vec4", "color"),
                new PackTerrainProgram.Input(2, "vec2", "texCoord"),
                new PackTerrainProgram.Input(5, "vec3", "sunVec"));
        String[] x = PackVertexAdapterGenerator.generate(inputs, false, false, true)
                .glsl().split("\n");
        String[] y = PackVertexAdapterGenerator.generate(inputs, false, false, false)
                .glsl().split("\n");
        int differing = 0;
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            if (!x[i].equals(y[i])) {
                differing++;
                assertTrue(x[i].contains("color ="),
                        "`U0001f50e 探针只许改 color 这一行");
            }
        }
        assertEquals(1, differing,
                "`U0001f50e 单变量实验必须只改一行");
    }
}
