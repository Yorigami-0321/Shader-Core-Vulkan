package dev.vkdisp.pack.target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 【参考调研】可绑定 sampler 类型表的判据
 * 0. 合规核对（第 0 步闸门）：被测物 = 移植件 {@code pack/target/SamplerTypes}
 *    （Vitrail {@code 4a45f44}，LGPL-3.0，台账见 {@code NOTICE.md}）；本文件只写断言，零外部代码。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以（测试代码不进分发 jar）
 * 4. 许可证核对结论：本项目 LGPL-3.0-or-later；零第三方代码复制。
 */
final class SamplerTypesTest {

    @Test
    @DisplayName("2D 家族与 Cube 家族全部可绑（shadow/array/MS 带的还是那两个维度）")
    void twoDimensionalFamiliesBind() {
        for (String type : new String[]{"sampler2D", "sampler2DShadow", "sampler2DArray",
                "sampler2DArrayShadow", "sampler2DMS", "sampler2DMSArray", "samplerCube",
                "samplerCubeShadow", "samplerCubeArray", "samplerCubeArrayShadow",
                "isampler2D", "usampler2DArray"}) {
            assertFalse(SamplerTypes.refused(type), type + " 应当可绑");
        }
    }

    @Test
    @DisplayName("🔴 sampler3D 拒：一处声明就让那个程序的整条管线建不起来")
    void threeDimensionalIsRefused() {
        for (String type : new String[]{"sampler3D", "usampler3D", "isampler3D"}) {
            assertTrue(SamplerTypes.refused(type), type + " 按游戏的维度检查过不了");
        }
        assertEquals("3D", SamplerTypes.shapeOf("usampler3D"));
    }

    @Test
    @DisplayName("2DRect 读起来像 2D，但它是 SpvDimRect ⇒ 一样拒")
    void rectangleIsNotTwoDimensional() {
        assertTrue(SamplerTypes.refused("sampler2DRect"), "名字里的 2D 不是证据");
        assertEquals("2DRect", SamplerTypes.shapeOf("sampler2DRect"));
    }

    @Test
    @DisplayName("非 sampler 类型不回答（storage image 是另一种资源）")
    void nonSamplersAreNotAnswered() {
        // 光秃秃一个 "sampler" 也是 sampler 类型，形状是空串（不是 null）：
        assertEquals("", SamplerTypes.shapeOf("sampler"));
        assertTrue(SamplerTypes.refused("sampler"), "空形状不在可绑表里");
        assertNull(SamplerTypes.shapeOf("uniform"));
        assertFalse(SamplerTypes.refused("image2D"), "image 不在这张表的管辖内");
    }

    @Test
    @DisplayName("名字不是证据：colortex6 拼成 sampler3D 也是 3D")
    void nameCarriesNoEvidence() {
        assertTrue(SamplerTypes.refused("sampler3D"));
        assertEquals("2D", SamplerTypes.shapeOf("sampler2D"));
    }
}
