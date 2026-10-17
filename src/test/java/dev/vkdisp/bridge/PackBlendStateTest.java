package dev.vkdisp.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.pipeline.BlendFunction;
import dev.vkdisp.pack.model.BlendMode;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 【参考调研】包写因子名 → 官方 BlendFunction 的判据
 * 0. 合规核对（第 0 步闸门）：参考对象 = 移植件 {@code pack/model/BlendMode}（Vitrail LGPL-3.0，
 *    台账已记）与官方 API 事实（{@code BlendFunction}/{@code BlendFactor} 的名字与构造，逐字取自
 *    本机 sources jar）。本文件只写断言与自造样本，零外部代码。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以（测试代码不进分发 jar）
 * 4. 许可证核对结论：本项目 LGPL-3.0-or-later；零第三方代码复制。
 */
final class PackBlendStateTest {

    private static BlendMode mode(String value) {
        return BlendMode.parse(value).orElseThrow(() -> new AssertionError("样本本身不合法: " + value));
    }

    @Test
    @DisplayName("半透明写法逐字对上官方 TRANSLUCENT（record 相等，不是「看起来一样」）")
    void translucentMatchesOfficialConstant() {
        PackBlendState.Outcome outcome = PackBlendState.of(
                mode("SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ONE_MINUS_SRC_ALPHA"));
        assertEquals(PackBlendState.Status.MAPPED, outcome.status());
        assertEquals(BlendFunction.TRANSLUCENT, outcome.blend());
        assertTrue(outcome.applies());
    }

    @Test
    @DisplayName("小写与 GL_ 前缀都收（包两种都写）")
    void caseAndGlPrefixAccepted() {
        assertEquals(BlendFunction.TRANSLUCENT,
                PackBlendState.of(mode("src_alpha one_minus_src_alpha one one_minus_src_alpha")).blend());
        // BlendMode 自己就把值转成大写，GL_ 前缀在本类剥掉：
        PackBlendState.Outcome gl = PackBlendState.of(
                new BlendMode(false, "GL_SRC_ALPHA", "GL_ONE_MINUS_SRC_ALPHA", "GL_ONE",
                        "GL_ONE_MINUS_SRC_ALPHA"));
        assertEquals(PackBlendState.Status.MAPPED, gl.status());
        assertEquals(BlendFunction.TRANSLUCENT, gl.blend());
    }

    @Test
    @DisplayName("OVERLAY 的四因子顺序 = (srcColor, dstColor, srcAlpha, dstAlpha)")
    void overlayOrder() {
        assertEquals(BlendFunction.OVERLAY,
                PackBlendState.of(mode("SRC_ALPHA ONE ONE ZERO")).blend(),
                "顺序写反会安静地得到一个不同的混合，画面是「亮边糊掉」这种难查的形状");
    }

    @Test
    @DisplayName("两因子形（glBlendFunc）在颜色与 alpha 上同用一对")
    void twoFactorFormMirrors() {
        PackBlendState.Outcome outcome = PackBlendState.of(mode("ONE ZERO"));
        assertEquals(PackBlendState.Status.MAPPED, outcome.status());
        assertNotNull(outcome.blend());
        assertEquals(PackBlendState.of(mode("ONE ZERO ONE ZERO")).blend(), outcome.blend());
    }

    @Test
    @DisplayName("off 是「不混合」，MAPPED 之外唯一还能应用的结局")
    void offMeansNoBlending() {
        PackBlendState.Outcome outcome = PackBlendState.of(mode("off"));
        assertEquals(PackBlendState.Status.OFF, outcome.status());
        assertNull(outcome.blend());
        assertTrue(outcome.applies(), "off 要拿去把混合关掉，不是「没读懂」");
    }

    @Test
    @DisplayName("认不出的因子名整条拒收并点名那个词（不猜成 TRANSLUCENT）")
    void unknownFactorIsRefusedByName() {
        PackBlendState.Outcome outcome = PackBlendState.of(mode("SRC_ALPHA BANANA ONE ONE"));
        assertEquals(PackBlendState.Status.UNRECOGNIZED_FACTOR, outcome.status());
        assertEquals("BANANA", outcome.detail());
        assertFalse(outcome.applies(), "猜错的混合是画上一块半透明糊，比不混合更难查");
        assertEquals(Optional.empty(), PackBlendState.factor(null));
        assertEquals(Optional.empty(), PackBlendState.factor("  "));
    }

    @Test
    @DisplayName("双空格这种「参考实现会拒的形态」一路走到 MALFORMED，不被读成 off")
    void malformedIsItsOwnOutcome() {
        assertFalse(BlendMode.parse("ONE  ZERO SRC_ONE ONE").isPresent(),
                "BlendMode 先拒（词数不为 4）");
        PackBlendState.Outcome outcome = PackBlendState.of(null, "ONE  ZERO SRC_ONE ONE");
        assertEquals(PackBlendState.Status.MALFORMED, outcome.status());
        assertEquals("ONE  ZERO SRC_ONE ONE", outcome.detail());
        assertFalse(outcome.applies());
    }
}
