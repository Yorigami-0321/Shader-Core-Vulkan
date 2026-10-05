package dev.vkdisp.pipeline.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 后处理契约与重编号的纯逻辑守卫（附件布局的最后一道「不许猜」闸门）。
 */
class PostOutputRenumberTest {

    private static String compositeSource() {
        return "#version 330\n"
                + "uniform sampler2D colortex0;\n"
                + "uniform sampler2D colortex4;\n"
                + "layout(location = 4) out vec4 vkdispFragOut4;\n"
                + "layout(location = 0) out vec4 vkdispFragOut0;\n"
                + "void main() {\n"
                + "  vec4 c = texture(colortex0, vec2(0.5));\n"
                + "  vkdispFragOut0 = c;\n"
                + "  vkdispFragOut4 = c * texture(colortex4, vec2(0.5));\n"
                + "}\n";
    }

    @Test
    @DisplayName("🔴 稀疏槽 [0,4] → 附件下标 [0,1]；写名引用逐字不动")
    void sparseSlotsBecomeContiguousIndices() {
        String out = PostOutputRenumber.apply(compositeSource(), List.of(0, 4));
        assertTrue(out.contains("layout(location = 0) out vec4 vkdispFragOut0"), "槽 0 → 下标 0");
        assertTrue(out.contains("layout(location = 1) out vec4 vkdispFragOut4"),
                "槽 4 的声明必须落到附件下标 1（变量名保留槽号痕迹，便于对质）");
        assertTrue(out.contains("vkdispFragOut4 = c"), "main 体内的写名引用不受影响");
        assertEquals(countLines(out), countLines(compositeSource()), "等行数（行号映射不受影响）");
    }

    @Test
    @DisplayName("🔴 契约解析：槽集合升序、sampler 名单去重、maxSlot 正确")
    void contractParsesSlotsAndSamplers() {
        PostPassContract contract = PostPassContract.parse("world0/composite", compositeSource());
        assertEquals(List.of(0, 4), contract.outputSlots(), "槽集合必须升序（重编号按它算下标）");
        assertEquals(List.of("colortex0", "colortex4"), contract.samplerNames());
        assertEquals(4, contract.maxSlot());
    }

    @Test
    @DisplayName("🔴 拒绝形态全部响亮：无 out / 槽不在集合 / 声明数≠槽数 / 非升序集合")
    void refusalsAreLoud() {
        assertThrows(IllegalArgumentException.class,
                () -> PostPassContract.parse("world0/x", "#version 330\nvoid main(){}\n"),
                "没有任何 out 声明 = 后处理步不写附件 = 契约不成立");
        assertThrows(IllegalArgumentException.class,
                () -> PostOutputRenumber.apply(compositeSource(), List.of(0, 3)),
                "声明了槽 4 而计划只有 [0,3] ⇒ **不猜附件布局**");
        assertThrows(IllegalArgumentException.class,
                () -> PostOutputRenumber.apply(compositeSource(), List.of(4, 0)),
                "槽集合必须升序");
        assertThrows(IllegalArgumentException.class,
                () -> PostOutputRenumber.apply(compositeSource(), List.of(0, 4, 7)),
                "声明数(2) ≠ 槽数(3) ⇒ 有附件永远没人写，拒绝");
    }

    private static int countLines(String s) {
        return s.split("\n", -1).length;
    }
}
