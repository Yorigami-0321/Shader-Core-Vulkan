package dev.vkdisp.bridge;

import dev.vkdisp.VkDisp;

/**
 * {@code GAP-037} 的判据仪器：天空那批像素的深度<b>这一帧被翻了几次</b>、分别是<b>谁</b>翻的。
 *
 * <p><b>为什么需要它</b>：第 5 步的判据写着「天空深度只被翻一次（{@code GAP-037}）」，
 * 但今天<b>没有任何地方能读出「翻了几次」</b> —— 这条判据和 {@code GAP-038} 那条雾判据同一种空账：
 * <b>没有仪器的判据 = 永远签不掉的账</b>。{@code GAP-037} 的既有结论是<b>代码级</b>的
 * （两侧位置：{@code SkyPackGbuffer#skyVertexBuffer} 把 {@code Position.z} 写死 {@code 1.0}，
 * 与 {@code DepthGlProxy#toGlDepth} 的 {@code z_gl = 1 − z_en}），而「一次」这个数从没被真机读出来过。
 *
 * <p><b>形状</b>：每个「翻深度口径的所有者」在自己的动作那一刻 {@link #note(String)} 一次；
 * 帧首 {@link #beginFrame()} 把上一帧的读数结算 + 节流自报。判定本身是<b>纯函数</b>
 * （{@link #verdict(int)}，只吃一个整数）⇒ 不启动客户端就能单测「两次必须报红」。
 *
 * <p>🔖 <b>计数不参与任何渲染决定</b>（T10）：它只是读数。谁翻、翻几次都由代码结构决定，
 * 本类只负责把它说出来 —— 与 {@code GbufferPassOrder}（定序仪器）同一条纪律。
 */
public final class DepthFlipLedger {

    /** 结算周期（帧）：与 {@code TerrainDrawCapture.FOG_REPORT_PERIOD} 同量级，够真机读到。 */
    private static final long REPORT_PERIOD = 300L;

    /** 全部可变态装在**一个**持有者里（QD-03 静态字段棘轮：静态只留这一个 final 引用）。 */
    private static final class Ledger {
        /** 一帧里翻了几次（0 / 1 / 2…）。 */
        int flips;
        /** 本帧那些次分别是谁干的（顺序 = 发生顺序，帧首结算后清空）。 */
        StringBuilder owners = new StringBuilder();
        long frames;
        /** 累计「翻两次及以上」的帧数（判据的红计数）。 */
        long overOne;
    }

    private static final Ledger STATE = new Ledger();

    private DepthFlipLedger() {
    }

    /**
     * 记一次「对天空那批像素的深度做了口径换算」。
     *
     * @param owner 谁做的（点名到方法级，两个所有者同名就没意义）
     */
    public static void note(String owner) {
        STATE.flips++;
        if (!STATE.owners.isEmpty()) {
            STATE.owners.append(" + ");
        }
        STATE.owners.append(owner);
    }

    /**
     * 帧首结算：把<b>上一帧</b>的读数报出去并清零。
     *
     * <p>🔖 为什么报上一帧：{@code beginFrame} 与 {@code note} 在同一条渲染线程上交错，
     * 报「本帧已记的数」会读到半帧。
     */
    public static void beginFrame() {
        STATE.frames++;
        if (STATE.flips > 1) {
            STATE.overOne++;
        }
        if (STATE.frames % REPORT_PERIOD == 0L) {
            VkDisp.LOGGER.info("vkdisp: [GAP-037/翻] 本帧天空深度被翻 {} 次（判据：恰好 1 次）{}；"
                            + "累计「>1 次」的帧={}（只是读数，不参与任何渲染决定）",
                    STATE.flips, STATE.owners.isEmpty() ? "（无所有者）" : "：" + STATE.owners, STATE.overOne);
        }
        STATE.flips = 0;
        STATE.owners = new StringBuilder();
    }

    /**
     * 「只翻一次」这条判据的判定本体（<b>纯函数</b>，可离线单测）。
     *
     * <p>三态都要有话说：0 次不是「通过」，是<b>这一帧没人翻</b> —— 天空深度就停在引擎口径，
     * 包按「1.0 = 天空」写的分支会把它判成地面（{@code GAP-022} 那一族的形状）。
     */
    public static String verdict(int flips) {
        if (flips == 1) {
            return "只翻一次 ✓";
        }
        if (flips == 0) {
            return "🔴 一次都没翻：天空深度停在引擎口径（反向 Z 下天空 = 0.0），"
                    + "包按 z==1.0 认天空的分支会判否（夜里无星那一族的形状）";
        }
        return "🔴 翻了 " + flips + " 次：偶数次 = 翻回引擎口径，奇数次以上 = 中间那次白做"
                + "（GAP-037 的实测形状：顶点常量 1.0 + 代理的 1−z ⇒ 天空落回近平面）";
    }

    /** 诊断读数：上一帧翻了几次（帧首之后为 0）。 */
    public static int lastFrameFlips() {
        return STATE.flips;
    }

    /** 诊断读数：累计「>1 次」的帧数。 */
    public static long overOneFrameCount() {
        return STATE.overOne;
    }
}
