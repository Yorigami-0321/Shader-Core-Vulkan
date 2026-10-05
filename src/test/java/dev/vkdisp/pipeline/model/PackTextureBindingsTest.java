package dev.vkdisp.pipeline.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link PackTextureBindings} 的解析与拒绝守卫。 */
class PackTextureBindingsTest {

    @Test
    @DisplayName("🔖 两段键 = 按采样器名绑；其它指令一概不收")
    void collectsTwoSegmentTextureDirectivesOnly() {
        Map<String, String> directives = new LinkedHashMap<>();
        directives.put("texture.noise", "tex/noise.png");
        directives.put("texture.composite.colortex7", "tex/dirt.png");
        directives.put("sliders", "a b");
        directives.put("clouds", "false");

        PackTextureBindings.Result result = PackTextureBindings.fromDirectives(directives);

        assertEquals("tex/noise.png", result.bindings().get("noise"));
        // 三段键：程序限定名不能当采样器名收（非法名 → 拒绝清单），
        // 🔖 三段语法（按程序绑 sampler）语义未核实 ⇒ **不收**，登记在未实现清单里。
        assertTrue(result.rejected().stream().anyMatch(s -> s.contains("composite.colortex7")),
                () -> "三段键必须被点名拒绝而不是静默收下，实际: " + result.rejected());
        assertEquals(0, result.bindings().size() - 1, "只有两段键进绑定表");
    }

    @Test
    @DisplayName("🔴 路径穿越与非法名：逐条拒绝、可点名")
    void rejectsUnsafePathsAndNames() {
        Map<String, String> directives = Map.of(
                "texture.two", "../outside.png",
                "texture./bad name", "ok.png",
                "texture.ok", "/abs.png");
        PackTextureBindings.Result result = PackTextureBindings.fromDirectives(directives);
        assertTrue(result.bindings().isEmpty(), () -> "全部非法: " + result.bindings());
        assertEquals(3, result.rejected().size(), () -> "逐条拒绝清单: " + result.rejected());
    }
}
