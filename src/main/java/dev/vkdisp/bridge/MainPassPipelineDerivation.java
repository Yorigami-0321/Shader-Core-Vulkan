package dev.vkdisp.bridge;
/**
 * 【参考调研】R7 / M-04 第 ⑤-b 步的第一半：「未知管线也补到 N 槽」的<b>通用派生公式</b>
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 原版 26.3.0.51-beta sources jar：{@code com/mojang/renderpearl/api/pipeline/
 *    ColorTargetState.java}（record：{@code (Optional<BlendFunction>, GpuFormat, int writeMask)}；
 *    常量 {@code WRITE_NONE=0} / {@code DEFAULT = (empty, RGBA8_UNORM, 15)} /
 *    {@code MAX_COLOR_TARGETS=8}）与 {@code RenderPipeline.java} 第 33/90/135/273-276 行
 *    （{@code getColorTargetStates()} 返回可含 {@code null} 的列表、{@code toBuilder()}、
 *    {@code withColorTargetState(index, state)} 会把 {@code activeColorTargetStateCount} 抬到
 *    {@code index+1}）；② {@code FrontendRenderPass} 第 107-121 行那条校验（附件数 == 声明数、
 *    逐条格式相等）；③ 本仓 {@code ColortexPool:179}（池的每一槽都是 {@code RGBA8_UNORM}）。
 *    → 能否并入本项目（MIT）：可以；本文件独立编写
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码（参考模组零接触，07-CONSTRAINTS L12）
 * 1. 官方/主实现：不新造管线描述符的<b>形状</b> —— 用原版自己的 {@code toBuilder()} +
 *    {@code withColorTargetState(index, …)}，只<b>补</b>没声明的那些槽。
 * 2. 备选（都否决）：① 走 {@code PipelineModifier} —— <b>否决</b>：modifier 是按管线 {@code ResourceKey}
 *    挂的，而 §5.0.6 ④ 数出来的风险面正是「六处阶段事件把主 renderPass 交给在场任意 mod」⇒
 *    未知管线根本没有 key 可挂；② 启动期把 {@code RenderPipelines} 全表派生一遍 —— <b>否决</b>：
 *    同样漏掉未知管线，且 195 条里只有 ~30% 用得到（§5.0.6 ②）；③ 给补槽也写颜色 —— <b>否决</b>：
 *    包的片元会读 colortex1/2，写进去就是覆盖别人的输出 ⇒ 补槽必须 {@code WRITE_NONE}。
 * 3. 我们的差异点：判定与建表都是<b>纯函数</b>（{@link #padTargets} / {@link #refusalReason}）⇒
 *    不启动客户端就能把「target0 原样、补槽 RGBA8_UNORM/WRITE_NONE/无混合、装不下就交回原版」
 *    钉成单测。设备侧只剩一次 {@code toBuilder} 转发。
 * 4. 许可证核对：本项目 MIT；零第三方代码并入。
 * 5. 性能基线：派生发生在<b>编译</b>那一刻（原版把 compiled 结果按入参管线缓存 ⇒ 每条管线一次），
 *    不是每帧；{@link #derive} 命中缓存后不再建新对象。
 */
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.vkdisp.VkDisp;
import org.jspecify.annotations.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * 主 pass 管线的通用补槽公式（M-04 ⑤-b；登记表 {@code 04-SPEC} §5.0.7 表 ④ 与 §5.0.6 ④）。
 *
 * <p><b>公式</b>：已声明的那些槽<b>逐条原样</b>保留（含 target0 的格式、混合与写掩码），
 * 只在 {@code declared..slotCount-1} 上补 {@link #PAD}；补不动的（声明数 &gt; 附件数、超过
 * {@code MAX_COLOR_TARGETS}、已有槽的格式与池不一致）⇒ <b>整条交回原版并计数</b>
 * （{@code GAP-046} 收窄后的写法：格式这一维只在「我方改不动的地方」判，深度维是装配期断言）。
 */
public final class MainPassPipelineDerivation {

    /**
     * 补槽：不混合、{@code RGBA8_UNORM}（= 池的格式）、{@code WRITE_NONE}（不许覆盖包写进去的东西）。
     *
     * <p>⚠️ 放在<b>嵌套持有者</b>里而不是本类的静态字段：本类的 {@link #refusalReason} 是纯 int 判定、
     * 要能在<b>没有 renderpearl 的测试运行时</b>里被调用 —— 静态字段会进 {@code <clinit>}，
     * 一碰就 {@code NoClassDefFoundError}（实测）。嵌套类只在第一次 {@link #pad()} 时才初始化。
     */
    private static final class Pad {
        private static final ColorTargetState VALUE =
                new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE);
    }

    /** 补槽用的那个颜色目标状态（形状见 {@link Pad}）。 */
    public static ColorTargetState pad() {
        return Pad.VALUE;
    }

    /** 观察面（同样装进 {@code static final} 持有者，不动 QD-03 那条棘轮）。 */
    private static final class Stats {
        private long derived;
        private long refused;
        private boolean namedFirstRefusal;
        private boolean namedFirstDerivation;
    }

    private static final Stats STATS = new Stats();

    private MainPassPipelineDerivation() {
    }

    /**
     * 补槽表（<b>纯函数</b>）：长度 = {@code slotCount}，前 {@code declared.size()} 条<b>逐字沿用</b>，
     * 其余填 {@link #PAD}。调用前必须先用 {@link #refusalReason} 判过能补。
     *
     * @param declared 管线已声明的颜色目标（可含 {@code null} 占位 —— 原版就允许洞）
     */
    public static ColorTargetState[] padTargets(List<@Nullable ColorTargetState> declared, int slotCount) {
        ColorTargetState pad = pad();
        ColorTargetState[] out = new ColorTargetState[slotCount];
        for (int index = 0; index < slotCount; index++) {
            ColorTargetState existing = index < declared.size() ? declared.get(index) : null;
            out[index] = existing == null ? pad : existing;
        }
        return out;
    }

    /**
     * 能不能补（<b>纯函数</b>）。
     *
     * @param declaredCount 管线已声明的颜色目标条数
     * @param slotCount     主 pass 现在的颜色附件条数
     * @return {@code null} = 可以补；否则是拒绝原因（直接进日志，别只给个 boolean）
     */
    public static @Nullable String refusalReason(int declaredCount, int slotCount) {
        if (slotCount <= 0) {
            return "主 pass 没有颜色附件";
        }
        if (slotCount > ColorTargetState.MAX_COLOR_TARGETS) {
            return "槽数 " + slotCount + " 超过管线声明上限 " + ColorTargetState.MAX_COLOR_TARGETS;
        }
        if (declaredCount > slotCount) {
            return "管线声明 " + declaredCount + " 条 > pass 附件 " + slotCount + " 条（补不出来，只能少声明）";
        }
        return null;
    }

    /**
     * 设备侧那一步：把管线补到 {@code slotCount} 槽。补不动 ⇒ 返回入参本身（= 交回原版路径）并计数。
     *
     * <p>⚠️ 已有槽的<b>格式</b>这一维不在这里判：{@code FrontendRenderPass:119-121} 会逐条比
     * 「管线声明的格式 == 附件纹理格式」，不匹配就抛 ⇒ 那条校验本身就是我们的闸门。这里只挡
     * 「条数」这种补不出来的形状，避免把一次响亮失败换成一次静默降级（X11）。
     */
    public static RenderPipeline derive(RenderPipeline pipeline, int slotCount, String nameForLog) {
        int declared = pipeline.getColorTargetStates().size();
        String refusal = refusalReason(declared, slotCount);
        if (refusal != null) {
            STATS.refused++;
            if (!STATS.namedFirstRefusal) {
                STATS.namedFirstRefusal = true;
                VkDisp.LOGGER.warn("vkdisp: [M-04/⑤-b] 首条交回原版的管线：{}（{}）⇒ 主 pass 附件数={}；"
                        + "这一条不改，画面按今天的画法走（GAP-046 的退路分支，单独计数={}）",
                        nameForLog, refusal, slotCount, STATS.refused);
            }
            return pipeline;
        }
        var builder = pipeline.toBuilder();
        ColorTargetState[] targets = padTargets(pipeline.getColorTargetStates(), slotCount);
        for (int index = 0; index < targets.length; index++) {
            builder.withColorTargetState(index, targets[index]);
        }
        STATS.derived++;
        if (!STATS.namedFirstDerivation) {
            STATS.namedFirstDerivation = true;
            VkDisp.LOGGER.info("vkdisp: [M-04/⑤-b] 首条补槽管线：{}（{} 条声明 → {} 槽）⇒ 地形这一族在主 pass 里"
                    + "可以过 setPipeline 了；下一族会在自己的调用点响（⑤-c 逐族放开的顺序就按那个来）",
                    nameForLog, declared, slotCount);
        }
        return builder.build();
    }

    /** 自报（节流）：派生/拒绝的次数必须看得见，否则「交回原版」这一族会静默累积。 */
    public static void reportPeriodically() {
        if (STATS.derived + STATS.refused == 0L || (STATS.derived + STATS.refused) % 600L != 0L) {
            // 🔖 两个都还是 0 时不打 —— 那一行会被读成「公式没跑」，而它其实只是「还没到」
            return;
        }
        VkDisp.LOGGER.info("vkdisp: [M-04/⑤-b] 派生={} 交回原版={}", STATS.derived, STATS.refused);
    }
}
