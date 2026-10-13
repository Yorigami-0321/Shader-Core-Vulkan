package dev.vkdisp.bridge;
/**
 * 【自行补充】R7 ⑤-d 的前置：gbuffer 池的尺寸锚点核对（把「巧合相等」变成「看得见的条件」）
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓库自有码 {@code bridge/MainPassTakeover.java}
 *    （主 pass 自己那条 target0 的尺寸从哪来）与 {@code bridge/MrtTerrainPass.java#ensureColortex}
 *    （池按谁的尺寸建），以及本项目文档 {@code docs/13-GAP-REGISTRY.md} 的 {@code GAP-049}
 *    （两条「主目标」handle 不同源）。许可证：本项目 MIT，零第三方代码。
 *    → 能否并入本项目（MIT）：可以，本文件独立编写
 *    → 例外条款：无；不含任何 GPL / ARR 代码（参考模组零接触，07-CONSTRAINTS L12）
 * 1. 官方/主实现：原版没有「两张主目标」这件事 —— 差异来自 NeoForge 对 {@code LevelRenderer} 的补丁
 *    （{@code targets.main} 与 {@code gameRenderer.mainRenderTarget()} 实测连纹理都不是同一张）。
 * 2. 备选：① 直接把池改成按主 pass 的 target0 建 —— <b>现在不行</b>：池在首帧之前就要建
 *    （管线注册早于任何 pass，见 {@code GAP-045} 那条时序铁律），而那一刻 {@code MainPassTakeover}
 *    还没看过任何描述符 ⇒ 数不存在；② 什么都不做，靠「今天两者恰好同尺寸」—— <b>否决</b>
 *    （{@code GAP-049} 的原话：今天靠侥幸对齐；⑤-d 关掉双绘之后这张就是唯一画布）。
 * 3. 我们的差异点：把这条巧合<b>钉成可见条件</b> —— 每次建池核对一次，同尺寸/漂移/未知各计数，
 *    首次漂移点名两个尺寸并说明「⑤-d 之前必须改成以主 pass 的 target0 建池」。判定本体是纯函数
 *    （{@link #mismatch}）⇒ 不启动客户端就能单测。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：每帧 1–2 次（{@code ensureColortex} 的两个入口）：两次整数比较 + 三个计数器，零分配。
 */
import dev.vkdisp.VkDisp;
import org.jspecify.annotations.Nullable;

/**
 * 登记表：{@code docs/13-GAP-REGISTRY.md} 的 {@code GAP-049}（修法行的第二半），
 * 也是 {@code 04-SPEC} §5.0.7 表 ⑤ 行 <b>d</b> 的前置条件。
 *
 * <p><b>它不改变行为</b>：池仍然按 {@code gameRenderer.mainRenderTarget()} 的尺寸建（那是唯一在
 * 建池时刻一定存在的数）。它只回答一个问题：<b>「主 pass 实际画进去的那张图，尺寸和我们的池一样吗」</b>
 * —— 一样就计数并点名一次（说明这条仍是巧合），不一样就响亮 WARN（⑤-d 不能开工）。
 */
public final class GbufferSizeAnchor {

    /** 观察面（装在 {@code static final} 持有者里，不动 QD-03 那条静态字段棘轮）。 */
    private static final class Stats {
        private long agree;
        private long drift;
        private long unknown;
        private boolean reportedAgree;
        private boolean reportedDrift;
    }

    private static final Stats STATS = new Stats();

    private GbufferSizeAnchor() {
    }

    /**
     * 判定本体（<b>纯函数</b>）。
     *
     * @param poolWidth  我方 gbuffer 池的宽（= 建池时用的那张「主目标」的宽）
     * @param poolHeight 我方 gbuffer 池的高
     * @param mainWidth  主 pass 自己那条颜色附件（target0）的宽，0 = 还没看过
     * @param mainHeight 同上，高
     * @return {@code null} = 同尺寸；否则是原因串（直接进日志，别只给 boolean）
     */
    public static @Nullable String mismatch(int poolWidth, int poolHeight, int mainWidth, int mainHeight) {
        if (mainWidth <= 0 || mainHeight <= 0) {
            return "主 pass 的 target0 尺寸还不知道（建池早于第一次看到主 pass ⇒ 这一刻无法核对）";
        }
        if (poolWidth != mainWidth || poolHeight != mainHeight) {
            return "池=" + poolWidth + "x" + poolHeight + " 但主 pass 的 target0="
                    + mainWidth + "x" + mainHeight;
        }
        return null;
    }

    /**
     * 建池那一刻的核对（{@code MrtTerrainPass#ensureColortex} 调）。
     *
     * <p>🔴 漂移<b>不</b>在这里「顺手修正」：修正意味着换池尺寸 = 重建整条池（含 mip 链与深度），
     * 而这条路径今天还没有被 ⑤-d 的所有权改造覆盖。这里的职责只是让它<b>响</b>。
     */
    public static void checkPoolSize(int poolWidth, int poolHeight) {
        String reason = mismatch(poolWidth, poolHeight,
                MainPassTakeover.mainPassTargetWidth(), MainPassTakeover.mainPassTargetHeight());
        if (reason == null) {
            STATS.agree++;
            if (!STATS.reportedAgree) {
                STATS.reportedAgree = true;
                VkDisp.LOGGER.info("vkdisp: [GAP-049] 两张主目标同尺寸（{}x{}）⇒ 池的锚点今天不出错，"
                        + "但这仍是<b>巧合而不是约束</b>：⑤-d 关掉双绘之前，池必须改成以主 pass 自己的"
                        + " target0 尺寸为准（或把这条核对升级成硬约束）", poolWidth, poolHeight);
            }
            return;
        }
        if (MainPassTakeover.mainPassTargetWidth() <= 0) {
            STATS.unknown++;
            return;
        }
        STATS.drift++;
        if (!STATS.reportedDrift) {
            STATS.reportedDrift = true;
            VkDisp.LOGGER.warn("vkdisp: [GAP-049] 池的尺寸与主 pass 的 target0 <b>不一致</b>：{}"
                    + " ⇒ ⑤-d（关双绘，让主 pass 成为唯一画布）不许开工：接管那一侧会按 slotsUsable"
                    + " 拒绝接管（画面退回今天），而我方 pass 画的仍是池的尺寸。修法见 GAP-049 的修法行。",
                    reason);
        }
    }

    /** 同尺寸核对通过次数（诊断/单测读数）。 */
    public static long agreeCount() {
        return STATS.agree;
    }

    /** 尺寸漂移被看到的次数（&gt; 0 就是 ⑤-d 的红灯）。 */
    public static long driftCount() {
        return STATS.drift;
    }

    /** 「还没看过主 pass」的次数（首帧之前正常会涨）。 */
    public static long unknownCount() {
        return STATS.unknown;
    }
}
