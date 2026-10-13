package dev.vkdisp.bridge;

import dev.vkdisp.VkDisp;

/**
 * 【参考调研】GAP-053 的定序仪器：接管后的<b>原版主 pass</b> 与我方 <b>gbuffer pass</b> 在一帧内谁先打开。
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓 {@code MainPassTakeover}（⑤-a 的接管点）与
    {@code MrtTerrainPass#drawTerrain}（我方 pass 的打开点）；无第三方代码。
 * 1. 为什么需要它：⑤-d 单绘档画面里没有地形（{@code GAP-053}）。两个候选机制预测的<b>画面</b>
 *    一模一样（全屏探针实验分不开）：① 我方 pass 在主 pass <b>之后</b> ⇒ 它那次无条件清屏把主 pass
 *    刚写的内容擦掉；③ 代次归属错配 ⇒ 主 pass 写的那一代不是链读的那一代。而<b>修法不同</b>
 *    （① 让出清屏权；③ 换代次）⇒ 必须先定序，不许猜。
 * 2. 判据形状：两个点各自「记一次」，共用一个单调计数 ⇒ 相邻两次记数必然相差 1，
 *    于是「谁先」= 方向计数（{@code 主→我方} 对 {@code 我方→主}）。<b>不需要</b>帧号，
 *    也不读任何配置标志（口径同 ⑤-a：判据只用调用本身的数据）。
 * 3. 与主实现（本仓其它自报）的差异点：判定是<b>纯函数</b>（{@link #verdict}）⇒ 不启动客户端就能把
 *    「哪边占多数就说哪边先、两边都不成数就说未知」钉成单测。
 * 4. 许可证核对：本项目 MIT；零第三方代码并入。
 * 5. 性能基线：两处各一次自增加一次比较，非热路径级别（每帧各一次）。
 */
public final class GbufferPassOrder {

    /** 观察面（装进 {@code static final} 持有者 ⇒ 不动静态可变字段那条棘轮）。 */
    private static final class Stats {
        private static final int MAIN = 0;
        private static final int OWN = 1;

        long seq;
        long mainCount;
        long ownCount;
        long mainSeq;
        long ownSeq;
        long mainFirst;
        long ownFirst;
        boolean reported;
    }

    /** 自报节拍（<b>对</b>数）：一帧一对 ⇒ 600 对约一秒，与 ⑤-a 那条同量。 */
    private static final int REPORT_PERIOD = 600;

    private static final Stats S = new Stats();

    private GbufferPassOrder() {
    }

    /** 原版主 pass（被接管的那条）打开的那一刻。 */
    public static void noteMainPass() {
        note(Stats.MAIN);
    }

    /** 我方 gbuffer pass 打开的那一刻。 */
    public static void noteOwnGbufferPass() {
        note(Stats.OWN);
    }

    /**
     * 记一次：<b>按「对」判先后</b>，不按相邻方向。
     *
     * <p>🔴 第一版在这里数的是「相邻两次记数的方向」（{@code 主→我方} 对 {@code 我方→主}），
     * 真机读回来是<b>逐字各半</b> —— 而那<b>不是</b>「次序不稳定」，是判据自身的缺陷：
     * 两个点各每帧一次 ⇒ 序列严格交替（主、我方、主、我方…），而交替序列里
     * <b>两个方向必然各占一半</b>，无论谁先。⇒ 换成「凑齐一对时比这两次记数的先后」，
     * 谁先才真的落到哪一格。
     */
    private static void note(int who) {
        S.seq++;
        if (who == Stats.MAIN) {
            S.mainCount++;
            S.mainSeq = S.seq;
        } else {
            S.ownCount++;
            S.ownSeq = S.seq;
        }
        if (S.mainCount != S.ownCount) {
            // 这一对还没凑齐 ⇒ 不下结论（凑齐的那一刻才比）
            return;
        }
        if (S.mainSeq < S.ownSeq) {
            S.mainFirst++;
        } else {
            S.ownFirst++;
        }
        if (!S.reported && S.mainFirst + S.ownFirst >= REPORT_PERIOD) {
            S.reported = true;
            VkDisp.LOGGER.info("vkdisp: [GAP-053/序] 成对={} ⇒ {}", S.mainFirst + S.ownFirst,
                    verdict(S.mainFirst, S.ownFirst));
        }
    }

    /**
     * 读数 → 结论（<b>纯函数</b>）。
     *
     * <p>两个方向计数是「相邻一对的方向」，一帧一对 ⇒ 占多数的那边就是<b>帧内先打开</b>的那个。
     * 两边都没数到（只出现过一种记数）⇒ 说「未知」，不猜。
     */
    public static String verdict(long mainFirst, long ownFirst) {
        if (mainFirst == 0L && ownFirst == 0L) {
            return "还没成对（只见到单边或一次都没见到）⇒ 帧内次序未知";
        }
        if (mainFirst > ownFirst) {
            return "主 pass 先打开、我方 gbuffer pass 在后 ⇒ GAP-053 候选①（我方那次无条件清屏擦掉主 pass 的内容）成立";
        }
        if (ownFirst > mainFirst) {
            return "我方 gbuffer pass 先打开、主 pass 在后 ⇒ 候选① 排除，指向候选③（代次归属：主 pass 写的那一代不是链读的那一代）";
        }
        return "两个方向各半 ⇒ 次序不稳定（可能在跨帧边界上计数），先当未知";
    }

    /** 读数（单测/诊断用）。 */
    public static long mainFirstCount() {
        return S.mainFirst;
    }

    /** 同上。 */
    public static long ownFirstCount() {
        return S.ownFirst;
    }
}
