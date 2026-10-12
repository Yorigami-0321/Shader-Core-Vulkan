package dev.vkdisp.bridge;
/**
 * 【参考调研】R7 / M-04 第 ⑤-b 步：「未知管线也补到 N 槽」的通用派生公式 + ⑤-c 要用的记忆层
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 原版 26.3.0.51-beta sources jar：{@code com/mojang/renderpearl/api/pipeline/
 *    ColorTargetState.java}（record：{@code (Optional<BlendFunction>, GpuFormat, int writeMask)}；
 *    常量 {@code WRITE_NONE=0} / {@code DEFAULT = (empty, RGBA8_UNORM, 15)} /
 *    {@code MAX_COLOR_TARGETS=8}）与 {@code RenderPipeline.java} 第 33/90/135/273-276 行
 *    （{@code getColorTargetStates()} 返回可含 {@code null} 的列表、{@code toBuilder()}、
 *    {@code withColorTargetState(index, state)} 会把 {@code activeColorTargetStateCount} 抬到
 *    {@code index+1}）；② {@code FrontendRenderPass} 第 107-121 行那条校验（附件数 == 声明数、
 *    逐条格式相等）；③ {@code RenderSystem} 第 106-120 行那条编译缓存（第一句是
 *    {@code PIPELINE_MODIFIERS.apply}，然后按入参管线查 {@code PipelineCache}）；
 *    ④ 本仓 {@code ColortexPool:179}（池的每一槽都是 {@code RGBA8_UNORM}）。
 *    → 能否并入本项目（MIT）：可以；本文件独立编写
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码（参考模组零接触，07-CONSTRAINTS L12）
 * 1. 官方/主实现：不新造管线描述符的形状 —— 用原版自己的 {@code toBuilder()} +
 *    {@code withColorTargetState(index, …)}，只补没声明的那些槽。
 * 2. 备选（都否决）：① 走 {@code PipelineModifier} —— 否决：modifier 的 key 只能启动期注册，而槽数 N
 *    是「接管之后那条描述符」的数（运行期才有），且六处阶段事件交出去的未知管线根本没有 key 可挂
 *    （{@code GAP-045} 同一条根因）；② 启动期把 {@code RenderPipelines} 全表派生一遍 —— 否决：
 *    同样漏掉未知管线，且 195 条里只有 ~30% 用得到（§5.0.6 ②）；③ 给补槽也写颜色 —— 否决：
 *    包的片元会读 colortex1/2，写进去就是覆盖别人的输出 ⇒ 补槽必须 {@code WRITE_NONE}。
 * 3. 我们的差异点：判定与建表都是纯函数（{@link #padTargets} / {@link #refusalReason}）⇒
 *    不启动客户端就能把「target0 原样、补槽 RGBA8_UNORM/WRITE_NONE/无混合、装不下就交回原版」
 *    钉成单测。设备侧只剩一次 {@code toBuilder} 转发 + 一层按对象身份的记忆表。
 * 4. 许可证核对：本项目 MIT；零第三方代码并入。
 * 5. 性能基线：🔴 热路径 —— ⑤-c 起 {@code derive} 会被<b>每一次 draw</b> 调用
 *    （{@code PreparedRenderType#draw} 是全部 RenderType 取管线的公共通路，本轮从 sources jar 数出来
 *    全游戏共 34 处取管线点，其中 20 余条渲染类型都过这一个方法）。原版的编译缓存
 *    （{@code RenderSystem:106-120}）按<b>入参管线</b>查 ⇒ 命中前提是「同一条派生结果每次是同一个对象」，
 *    所以这里必须自己记一层（{@link #remember} / {@link #cache}）；不记就是每次 draw 都
 *    {@code toBuilder().build()} 建新对象：不但分配，还把 {@code PipelineCache} 变成每帧新增记录。
 *    ⚠️ 更正一条上一轮写错的口径：类头原来写「派生发生在编译那一刻 ⇒ 每条管线一次」—— <b>不成立</b>
 *    （编译在缓存里，而本类的 {@code derive} 在缓存<b>之前</b>被调，每次 draw 一次）。
 */
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.vkdisp.VkDisp;
import org.jspecify.annotations.Nullable;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 主 pass 管线的通用补槽公式（M-04 ⑤-b；登记表 {@code 04-SPEC} §5.0.7 表 ④ 与 §5.0.6 ④）。
 *
 * <p><b>公式</b>：已声明的那些槽逐条原样保留（含 target0 的格式、混合与写掩码），
 * 只在 {@code declared..slotCount-1} 上补 {@link #pad()}；补不动的形状（声明数 &gt; 附件数、
 * 槽数超过 {@code MAX_COLOR_TARGETS}、零附件）⇒ <b>整条交回原版并计数</b>
 * （{@code GAP-046} 收窄后的写法：格式那一维交给 {@code FrontendRenderPass} 的响亮校验，
 * 深度那一维是装配期断言，见 §5.0.6 ⑤ 第 3 条）。
 */
public final class MainPassPipelineDerivation {

    /**
     * 补槽：不混合、{@code RGBA8_UNORM}（= 池的格式）、{@code WRITE_NONE}（不许覆盖包写进去的东西）。
     *
     * <p>⚠️ 放在嵌套持有者里而不是本类的静态字段：本类的 {@link #refusalReason} 是纯 int 判定、
     * 要能在<b>没有 renderpearl 的测试运行时</b>里被调用 —— 静态字段会进 {@code <clinit>}，
     * 一碰就 {@code NoClassDefFoundError}（实测，登记为 {@code GAP-050}）。
     * 嵌套类只在第一次 {@link #pad()} 时才初始化。
     */
    private static final class Pad {
        private static final ColorTargetState VALUE =
                new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE);
    }

    /** 补槽用的那个颜色目标状态（形状见 {@link Pad}）。 */
    public static ColorTargetState pad() {
        return Pad.VALUE;
    }

    /** 观察面 + 记忆表（都装进 {@code static final} 持有者，不动 QD-03 那条静态字段棘轮）。 */
    private static final class Stats {
        private long derived;
        private long refused;
        private long memoHits;
        private boolean namedFirstRefusal;
        private boolean namedFirstDerivation;

        /**
         * 「入参管线（对象身份）→ 补槽后的管线」记忆表。
         *
         * <p>用身份而不是 {@code equals}：主 pass 里取的都是 {@code RenderPipelines} 的静态常量
         * （§5.0.7 表 ④ 的同一条前提），身份即所需语义；而 {@code RenderPipeline} 是条大 record ——
         * 每次 draw 再算一遍 {@code hashCode} 就是把原版的缓存开销翻倍。
         */
        private final Map<RenderPipeline, RenderPipeline> padded = new IdentityHashMap<>();

        /** 上次记表时的槽数；槽数一变（换包档 / 换池尺寸）就整表作废。 */
        private int memoSlots;
    }

    private static final Stats STATS = new Stats();

    private MainPassPipelineDerivation() {
    }

    /**
     * 补槽表（纯函数）：长度 = {@code slotCount}，前 {@code declared.size()} 条逐字沿用，
     * 其余填 {@link #pad()}。调用前必须先用 {@link #refusalReason} 判过能补。
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
     * 能不能补（纯函数）。
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
     * <p>🔴 每次 draw 都会被调（性能口径见类头第 5 条）⇒ 结果按入参对象身份记忆。
     *
     * <p>⚠️ 已有槽的<b>格式</b>这一维不在这里判：{@code FrontendRenderPass:119-121} 会逐条比
     * 「管线声明的格式 == 附件纹理格式」，不匹配就抛 ⇒ 那条校验本身就是我们的闸门。这里只挡
     * 「条数」这种补不出来的形状，避免把一次响亮失败换成一次静默降级（X11）。
     */
    public static RenderPipeline derive(RenderPipeline pipeline, int slotCount) {
        RenderPipeline memoised = remember(pipeline, slotCount);
        if (memoised != null) {
            return memoised;
        }
        int declared = pipeline.getColorTargetStates().size();
        String refusal = refusalReason(declared, slotCount);
        if (refusal != null) {
            return refuse(pipeline, refusal, slotCount);
        }
        var builder = pipeline.toBuilder();
        ColorTargetState[] targets = padTargets(pipeline.getColorTargetStates(), slotCount);
        for (int index = 0; index < targets.length; index++) {
            builder.withColorTargetState(index, targets[index]);
        }
        RenderPipeline padded = builder.build();
        announceFirstDerivation(pipeline, declared, slotCount);
        cache(pipeline, padded, slotCount);
        return padded;
    }

    /** 记忆表读（顺带处理「槽数一变就整表作废」—— 换包档 / 换池尺寸都会换槽数）。 */
    private static @Nullable RenderPipeline remember(RenderPipeline pipeline, int slotCount) {
        if (STATS.memoSlots != slotCount) {
            STATS.padded.clear();
            STATS.memoSlots = slotCount;
        }
        RenderPipeline cached = STATS.padded.get(pipeline);
        if (cached != null) {
            STATS.memoHits++;
        }
        return cached;
    }

    /**
     * 记忆表写。超过 256 条整表清空：清空最坏只是重新派生一次，不清就是把换代后的管线对象钉住不放
     * （{@code GAP-018} / {@code GAP-045} 那一族「缓存没有包代次这一维」的形状，这里用上限代替代次）。
     */
    private static void cache(RenderPipeline pipeline, RenderPipeline padded, int slotCount) {
        STATS.memoSlots = slotCount;
        if (STATS.padded.size() >= 256) {
            STATS.padded.clear();
        }
        STATS.padded.put(pipeline, padded);
    }

    /** {@code GAP-046} 的退路分支：不改任何东西，原样交回入参管线，但必须计数 + 首次点名。 */
    private static RenderPipeline refuse(RenderPipeline pipeline, String refusal, int slotCount) {
        STATS.refused++;
        if (!STATS.namedFirstRefusal) {
            STATS.namedFirstRefusal = true;
            VkDisp.LOGGER.warn("vkdisp: [M-04/⑤-b] 首条交回原版的管线：{}（{}）⇒ 主 pass 附件数={}；"
                            + "这一条不改，画面按今天的画法走（GAP-046 的退路分支，单独计数={}）",
                    nameOf(pipeline), refusal, slotCount, STATS.refused);
        }
        return pipeline;
    }

    /** 首次派生点名一次（之后的量看 {@link #reportPeriodically}）。 */
    private static void announceFirstDerivation(RenderPipeline pipeline, int declared, int slotCount) {
        STATS.derived++;
        if (STATS.namedFirstDerivation) {
            return;
        }
        STATS.namedFirstDerivation = true;
        VkDisp.LOGGER.info("vkdisp: [M-04/⑤-b] 首条补槽管线：{}（{} 条声明 → {} 槽）⇒ 这一族在主 pass 里"
                        + "可以过 setPipeline 了；下一族会在自己的调用点响（⑤-c 逐族放开的顺序就按那个来）",
                nameOf(pipeline), declared, slotCount);
    }

    /** 只给日志用（不参与任何判定）：管线的标识串。放在日志分支里 ⇒ 热路径不建串。 */
    private static String nameOf(RenderPipeline pipeline) {
        return String.valueOf(pipeline.getLocation());
    }

    /**
     * 自报（节流）：派生/拒绝/记忆命中的次数必须看得见，否则「交回原版」这一族会静默累积。
     *
     * <p>🔴 节流的分母<b>必须含 {@code memoHits}</b>：加了记忆层之后 {@code derived} 只在「第一次见到某条
     * 管线」时涨（本机量级 = 几十），拿 {@code derived + refused} 去 % 600 会让这一行<b>永远打不出来</b>
     * —— 而「日志里没有这行」正是本仓反复登记过的那类误读源（{@code GAP-022} 的成对自报同一族）。
     */
    public static void reportPeriodically() {
        long total = STATS.derived + STATS.refused + STATS.memoHits;
        if (total == 0L || total % 600L != 0L) {
            // 🔖 三个都还是 0 时不打 —— 那一行会被读成「公式没跑」，而它其实只是「还没到」
            return;
        }
        VkDisp.LOGGER.info("vkdisp: [M-04/⑤-b] 派生={} 交回原版={} 记忆命中={}（每次 draw 都会走 derive ⇒ "
                + "命中数就是被省掉的建对象次数）", STATS.derived, STATS.refused, STATS.memoHits);
    }
}
