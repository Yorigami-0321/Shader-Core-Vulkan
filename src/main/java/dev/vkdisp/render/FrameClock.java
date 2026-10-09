package dev.vkdisp.render;
/**
 * OF 语义的 {@code frameCounter}：每<b>帧</b>恰好 +1（GAP-007 内建值线 / GAP-020 排查中定位的独立缺陷）。
 *
 * <p><b>为什么单独一个类</b>：{@code OfUniformManager.gather()} 要 {@code Minecraft} 实例，
 * 离线测不了；而「同一个令牌重复来 = 同一个帧号」这条性质恰恰必须能钉住。
 *
 * <p><b>它修的是什么</b>：{@code gather()} 一帧内可以被调多次（后处理链 + 地形/水的块环各一次），
 * 旧实现是 {@code ++frameCounter} ⇒ {@code frameCounter} 每帧跳 2~4。
 * 包拿它做逐帧相位：BSL {@code ambientOcclusion.glsl:64/66} 逐字
 * {@code dither = fract(dither + frameCounter * 0.618)} 与 {@code * 0.5}
 * —— {@code * 0.5} 对奇偶敏感，每帧跳 2 等于<b>永远同一相位</b>，抖动/TAA 那类效果就退化成常数。
 *
 * <p>🔖 无 {@code net.minecraft} / {@code renderpearl} 类型 ⇒ 可离线单测。
 */
final class FrameClock {

    /** 上一次的令牌；-1 = 还没有（首帧）。 */
    private long lastToken = -1L;

    /** 当前帧号（从 1 开始，与旧实现的 {@code ++} 口径一致）。 */
    private int frame;

    /** 令牌不可得（未进世界 / 捕获关着）而退回「每次调用算一帧」的次数（自报用）。 */
    private long noTokenCalls;

    /** 同一个令牌重复来的次数 = 省下多少次错误自增（自报用）。 */
    private long sameTokenHits;

    /**
     * 报出本帧的帧号。
     *
     * @param token 每帧恰好推进一次的令牌；{@code <= 0} 表示<b>拿不到</b>令牌
     * @return 帧号（同一令牌内恒定）
     */
    int advance(long token) {
        if (token <= 0L) {
            // 退回旧口径：不猜「它大概还是一帧」，但也不停住 —— 包需要一个单调的数。
            noTokenCalls++;
            return ++frame;
        }
        if (token == lastToken) {
            sameTokenHits++;
            return frame;
        }
        lastToken = token;
        return ++frame;
    }

    /** 自报：{@code noToken} 必须说出来，否则「frameCounter 修好了」这句话没有边界。 */
    String describe() {
        return "frame=" + frame + " sameTokenHits=" + sameTokenHits + " noTokenCalls=" + noTokenCalls;
    }
}
