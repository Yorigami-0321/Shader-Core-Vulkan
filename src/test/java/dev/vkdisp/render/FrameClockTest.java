package dev.vkdisp.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OF 语义的 {@code frameCounter}：<b>每帧 +1</b>，而不是「每次 {@code gather()} +1」。
 *
 * <p>钉的是本缺陷的形状：{@code gather} 一帧内可被调多次（链两处 + 地形/水的块环各一次），
 * 而包拿 {@code frameCounter} 做逐帧相位（BSL {@code ambientOcclusion.glsl:64/66}
 * 逐字 {@code fract(dither + frameCounter * 0.5)}）⇒ 每帧跳 2 就等于<b>永远同一相位</b>。
 */
@DisplayName("OF frameCounter 必须每帧恰好 +1")
class FrameClockTest {

    @Test
    @DisplayName("同一帧内重复 gather：帧号恒定，不自增")
    void repeatedCallsWithinOneFrameReturnSameNumber() {
        FrameClock clock = new FrameClock();
        assertEquals(1, clock.advance(100L));
        assertEquals(1, clock.advance(100L), "同一令牌重复来不许 +1（这就是旧 bug 的形状）");
        assertEquals(1, clock.advance(100L));
        assertEquals(2, clock.advance(101L), "新的一帧才 +1");
        assertEquals(2, clock.advance(101L));
        assertTrue(clock.describe().contains("sameTokenHits=3"), clock.describe());
    }

    @Test
    @DisplayName("拿不到令牌时退回「每次调用算一帧」，且这件事被数出来")
    void missingTokenFallsBackAndIsCounted() {
        FrameClock clock = new FrameClock();
        assertEquals(1, clock.advance(0L));
        assertEquals(2, clock.advance(-1L));
        assertTrue(clock.describe().contains("noTokenCalls=2"), clock.describe());
    }

    @Test
    @DisplayName("退回之后又拿到令牌：不会把中间那段当成同一帧重复用")
    void tokenResumeAfterFallbackStillAdvances() {
        FrameClock clock = new FrameClock();
        clock.advance(0L);
        assertEquals(2, clock.advance(7L));
        assertEquals(2, clock.advance(7L));
        assertEquals(3, clock.advance(8L));
    }
}
