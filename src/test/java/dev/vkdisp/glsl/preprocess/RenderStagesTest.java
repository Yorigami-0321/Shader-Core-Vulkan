package dev.vkdisp.glsl.preprocess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vkdisp.glsl.SourceLineMap;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GAP-028：{@code MC_RENDER_STAGE_*} 宏表本身，以及它<b>真的进了 GLSL 预处理</b>这条接线。
 *
 * <p>钉的是两条<b>性质</b>，不是代码形状：
 * ① 宏表<b>自洽</b>（同名同值、值互不相同、名字是合法 GLSL 标识符）——
 *    包侧用法是 {@code renderStage == MC_RENDER_STAGE_X}，只要这两条成立就成立；
 * ② 引擎宏<b>真的</b>进了 {@link DefineProcessor}（此前正是「包假定加载器会塞的常量我方一个
 *    都不塞」让 {@code gbuffers_skybasic} 整条编译失败）。
 */
@DisplayName("GAP-028 MC_RENDER_STAGE_* 宏表与预处理接线")
class RenderStagesTest {

    private static String preprocess(String source) {
        return DefineProcessor.process(source, SourceLineMap.builder(null).build()).text();
    }

    @Test
    @DisplayName("宏名全部带前缀、是合法 GLSL 标识符、且互不重复")
    void macroNamesAreWellFormedAndUnique() {
        Set<String> seen = new HashSet<>();
        for (String name : RenderStages.macroNames()) {
            assertTrue(name.startsWith(RenderStages.MACRO_PREFIX), "缺前缀：" + name);
            String tail = name.substring(RenderStages.MACRO_PREFIX.length());
            assertTrue(tail.matches("[A-Z][A-Z0-9_]*"), "不是合法 GLSL 宏名：" + tail);
            assertTrue(seen.add(name), "宏名重复：" + name);
        }
        assertEquals(RenderStages.STAGES.size(), seen.size(), "阶段数与宏数必须一致");
    }

    @Test
    @DisplayName("NONE = 0 且所有值互不相同（包按值比较的前提）")
    void valuesAreDistinctWithNoneAsZero() {
        assertEquals(0, RenderStages.valueOf("MC_RENDER_STAGE_NONE"));
        Set<String> values = new HashSet<>(RenderStages.macros().values());
        assertEquals(RenderStages.macros().size(), values.size(),
                "值必须互不相同，否则包的两个阶段分支会同时成立");
        assertNotNull(RenderStages.valueOf("MC_RENDER_STAGE_STARS"), "STARS 必须在表里（BSL 用它）");
        assertNotNull(RenderStages.valueOf("MC_RENDER_STAGE_TERRAIN_TRANSLUCENT"),
                "水的阶段也要在表里（voxelMap.glsl 用到 TERRAIN_*）");
        assertNull(RenderStages.valueOf("MC_RENDER_STAGE_NOT_A_STAGE"),
                "不认识的名不许给值（X9 不猜）");
    }

    @Test
    @DisplayName("自报行必须把「编号是我方 ABI」这件事说出来")
    void describeDisclosesTheNumberingCaveat() {
        String text = RenderStages.describe();
        assertTrue(text.contains("NONE=0"), text);
        assertTrue(text.contains("Iris-numbers-unverified"),
                "数值口径未核实这件事必须写在自报里，不许只在注释里：" + text);
    }

    @Test
    @DisplayName("🔴 接线：包按宏名写的分支现在预处理得过去，且宏被展开成数字")
    void engineMacrosReachGlslPreprocessor() {
        // 逐字取自 BSL 的 gbuffers_skybasic.glsl:143-145 与 :169 的形态。
        String source = String.join("\n",
                "#ifndef MC_RENDER_STAGE_MOON",
                "#define MC_RENDER_STAGE_MOON 1",
                "#endif",
                "#ifdef MC_RENDER_STAGE_STARS",
                "if (renderStage == MC_RENDER_STAGE_STARS) { alpha = 0.0; }",
                "#endif",
                "");
        String out = preprocess(source);
        assertTrue(out.contains("alpha = 0.0"),
                "STARS 那一支必须被选中（宏缺席时它整段被删 = 本轮修前的行为），实际：" + out);
        assertFalse(out.contains("#define MC_RENDER_STAGE_MOON"),
                "包自己的兜底 define 必须被 #ifndef 跳过（引擎已供该宏），实际：" + out);
        assertEquals(-1, out.indexOf(RenderStages.MACRO_PREFIX),
                "宏名不该残留在正文里（要展开成数字），实际：" + out);
        assertTrue(out.contains("== " + RenderStages.valueOf("MC_RENDER_STAGE_STARS")),
                "正文里应看到展开后的值，实际：" + out);
    }

    @Test
    @DisplayName("两个不同阶段的值不相等 ⇒ 包的「是 A 还是 B」判据不会同时成立")
    void distinctStagesDoNotCollapse() {
        String out = preprocess("#if MC_RENDER_STAGE_STARS == MC_RENDER_STAGE_MOON\n"
                + "float both = 1.0;\n#endif\n");
        assertFalse(out.contains("both"), "STARS 与 MOON 不许同值，实际：" + out);
    }
}
