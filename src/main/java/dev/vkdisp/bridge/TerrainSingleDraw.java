package dev.vkdisp.bridge;
/**
 * 【自行补充】R7 ⑤-d 第一刀：「地形由谁画」的判定 + 关掉双绘的自报
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓库自有码 {@code bridge/MrtTerrainPass.java}
 *    （今天自己开 pass 发 {@code renderGroup(OPAQUE/TRANSLUCENT)}）、
 *    {@code bridge/MainPassTakeover.java}（主 pass 有没有真被我方接管），以及本项目文档
 *    {@code docs/04-SPEC.md} §5.0.7 表 ⑤ 行 d（登记：{@code GAP-003} 的 M-04 分支）。
 *    许可证：本项目 MIT，零第三方代码。
 *    → 能否并入本项目（MIT）：可以，本文件独立编写
 *    → 例外条款：无；不含任何 GPL / ARR 代码（参考模组零接触，07-CONSTRAINTS L12）
 * 1. 官方/主实现：原版不需要这个判定 —— 地形本来就只有主 pass 画一次。「画两遍」是我方
 *    走 {@code GAP-003} 方案 A（自己的多附件 pass）时欠下的债，⑤-d 才还得掉。
 * 2. 备选：① 只看配置键就跳过我方那两笔 —— <b>否决</b>：主 pass 没被接管时「谁都不画地形」= 空屏，
 *    那是自伤不是优化；② 直接删 {@code MrtTerrainPass} 的自绘路径 —— <b>还没到那一步</b>：
 *    本步只先把「跳过」做成可关闭、可观测的一刀，删代码留给判据过掉之后（同一提交里既删又换锚点
 *    就分不开归因，{@code 19} §3.1 / h48 那一族教训的形状）。
 * 3. 我们的差异点：判定是<b>两个条件</b>的与（开关 ∧ 真接管），且三种情形各有读数：
 *    跳过 / 开关开着但没接管（WARN 一次，仍照今天画） / 开关关着（默认档，逐字等于今天）。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：每帧 2 次（两组各一次）：一次配置读 + 一次对象判空，零分配。
 */
import dev.vkdisp.VkDisp;
import dev.vkdisp.VkDispConfig;

/**
 * 登记表：{@code docs/04-SPEC.md} §5.0.7 表 ⑤ 行 <b>d</b>（关掉地形双绘）。
 *
 * <p><b>它解决的问题</b>：接管之后地形被画了两遍 —— 一遍在原版主 pass（附件已换成我方 colortex 阵列），
 * 一遍在我方 {@code MrtTerrainPass} 自己的 pass 里。第二遍不但白花一遍顶点，还让
 * {@code ColortexPool} 的双代轮转、{@code DepthSnapshots} 的三个手工取点、AfterLevel 第二档
 * 这些「图外同步」的债全部留着。本类是砍掉第二遍的<b>第一刀</b>：先只做到「可关闭地跳过」，
 * 判据在真机上过了再删代码。
 */
public final class TerrainSingleDraw {

    /** 观察面（装在 {@code static final} 持有者里，不动 QD-03 那条静态字段棘轮）。 */
    private static final class Stats {
        private long skippedGroups;
        private long flagWithoutTakeover;
        private boolean reportedSkip;
        private boolean warnedFlagWithoutTakeover;
    }

    private static final Stats STATS = new Stats();

    private TerrainSingleDraw() {
    }

    /**
     * 「这一组地形是不是已经由主 pass 画了」⇒ 我方 {@code renderGroup} 该不该跳过。
     *
     * <p>🔴 两个条件缺一不可：{@code mrt.terrainSingleDraw} 开着 <b>且</b>
     * {@link MainPassTakeover#takeoverHappened()}（我方附件真的挂在原版主 pass 上）。
     * 只有前者成立时<b>不跳过</b>（照今天画），并 WARN 一次说明「这一档等于谁都不画地形」——
     * 那是配错档，不是优化。
     */
    public static boolean drawnByMainPass() {
        if (!VkDispConfig.MRT_TERRAIN_SINGLE_DRAW.get()) {
            return false;
        }
        if (!MainPassTakeover.takeoverHappened()) {
            STATS.flagWithoutTakeover++;
            if (!STATS.warnedFlagWithoutTakeover) {
                STATS.warnedFlagWithoutTakeover = true;
                VkDisp.LOGGER.warn("vkdisp: [M-04/⑤-d] mrt.terrainSingleDraw=true 但主 pass 还没被接管过"
                        + "（mixin.ownTerrainPass 关着？slots=1 被拒？）⇒ <b>照今天双绘</b>，不跳过："
                        + "跳过就会谁都不画地形（空屏）。这一格计数={}", STATS.flagWithoutTakeover);
            }
            return false;
        }
        STATS.skippedGroups++;
        if (!STATS.reportedSkip) {
            STATS.reportedSkip = true;
            VkDisp.LOGGER.info("vkdisp: [M-04/⑤-d] 地形不再由我方 pass 画（主 pass 已接管 ⇒ 那一笔就是画进"
                    + "我方 colortex 阵列的那一笔）⇒ 双绘关掉。<b>判据</b>：画面仍有地形/水；"
                    + "若缺，就是主 pass 那一笔没走到或晚了一帧（先查 M-13 的边与 ⑤-e 的深度所有权）");
        }
        return true;
    }

    /** 跳过的 {@code renderGroup} 次数（诊断/单测读数；每帧上限 2）。 */
    public static long skippedGroupCount() {
        return STATS.skippedGroups;
    }

    /** 「开关开着但没接管」的次数（&gt; 0 就是取证档配错了的读数）。 */
    public static long flagWithoutTakeoverCount() {
        return STATS.flagWithoutTakeover;
    }
}
