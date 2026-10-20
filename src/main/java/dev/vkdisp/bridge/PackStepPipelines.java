package dev.vkdisp.bridge;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.vkdisp.VkDisp;
import dev.vkdisp.pack.PackBlendDirectives;
import dev.vkdisp.pack.model.BlendMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 按程序把链每一步的管线派生成「带该程序声明的混合」的那一条。
 *
 * <p><b>合规核对（第 0 步闸门）</b>：只用原版自己的形状 ——
 * {@code renderpearl/api/pipeline/RenderPipeline.java:135} 的 {@code toBuilder()}
 * 加 {@code withColorTargetState(index, state)}，与 {@link MainPassPipelineDerivation} 同源；
 * 混合值来自 {@link PackBlendState}（因子名 → {@code BlendFunction}，已与官方
 * {@code TRANSLUCENT}/{@code OVERLAY} 常量逐字对过）。参考模组零接触：Vitrail 的每程序管线
 * （{@code GeometryProgram.java:742}）是它自己的架构，本项目只采纳「运行期派生 + 按对象身份记忆」
 * 这条<b>事实性做法</b>，不搬其代码（07 L12/L13）。
 *
 * <p>🔴 <b>为什么必须记忆</b>：派生发生在原版编译缓存<b>之前</b>，而链的每一步每帧都过这里；
 * 每次新建一条管线既白分配，又把 {@code PipelineCache} 变成每帧长记录
 * （同 {@code MainPassPipelineDerivation} 类头第 5 条）。原版缓存按<b>入参对象</b>查 ⇒
 * 命中前提是「同一条派生结果每次是同一个对象」，故按 (入参管线身份, 逐格计划) 记一层。
 *
 * <p>🔴 <b>上一轮的「变体进不了 setPipeline」是误判，本轮逐字读源改判</b>（2026-10-17 一百九十五）：
 * {@code RenderSystem.getCompiledPipelineNullable}（{@code blaze3d/systems/RenderSystem.java:106-120}）
 * 先过 {@code PIPELINE_MODIFIERS.apply}，再把管线交给 {@code PipelineCache#get}
 * （{@code blaze3d/pipeline/PipelineCache.java:29-38}）—— 而后者<b>未命中就当场编译</b>：
 * {@code device.compilePipeline(pipeline, this.shaderSource, Util.backgroundExecutor()).join()
 * .finishCompile()}，用的是<b>原版自己的</b> {@code ShaderSource}（缓存构造参数，
 * {@code PipelineCache.java:18-26}），编译结果按<b>入参对象身份</b>记进
 * {@code Reference2ReferenceOpenHashMap}（{@code PipelineCache.java:22}）。
 * ⇒ 变体不需要预先 {@code RegisterRenderPipelinesEvent} 注册，也不需要本类自己调
 * {@code GpuDevice.compilePipeline}；只要「同一条变体每次是同一个对象」，代价就是
 * <b>第一次用到时编译一次</b>，之后每帧都是查表。上一轮只读了 RenderSystem 那五行、
 * 没跟进 PipelineCache 就下了「纯查表」的结论，改判记在 {@code 19} §7-9。
 *
 * <p>🔴 <b>两个真代价留在这里，不藏</b>：① 首次编译是<b>阻塞</b>的（{@code .join()}），发生在链的
 * 某一格里 —— 只在换代后第一次用到时付一次，包自己声明的链族混合去重后通常只有一两条；
 * ② 编译失败时 {@code getCompiledPipelineNullable} 会抛/返回 null ⇒ 本类把那条变体记进
 * {@link #BROKEN} 并退回入参管线（点名一次），<b>不许每帧重试编译</b>。
 *
 * <p>🔴 <b>三种情形分得很开</b>：没声明混合 → 原样交回入参管线（零行为变化）；
 * {@code off} → 只把声明到的那一格派生为不带 BlendFunction（包在要求该格不混合）；
 * 读不懂 → 计数 + 首次点名，<b>不猜</b>（X11 不许静默降级）。
 */
public final class PackStepPipelines {

    /** 记忆表基数上限：超了整表清空 —— 最坏是重新派生一次，不清就是把换代的管线对象钉住不放。 */
    private static final int MEMO_BASE_CAP = 64;

    private static final Map<RenderPipeline, Map<List<Optional<BlendFunction>>, RenderPipeline>> MEMO =
            new IdentityHashMap<>();

    /**
     * 编译失败过的变体（按对象身份）：记下来，之后这一条 (族管线, 混合) 直接走族管线。
     * 没有这张表 = 每帧重试一次阻塞编译（首次那格的代价被摊成每帧）。
     */
    private static final Set<RenderPipeline> BROKEN = Collections.newSetFromMap(new IdentityHashMap<>());

    /** 计数装在 final 容器里（QD-03 棘轮管的是「静态非 final 字段」；形状同 {@code MainPassPipelineDerivation.STATS}）。 */
    private static final class Stats {

        long applied;

        long unreadable;

        long broken;

        boolean namedFirst;

        boolean brokeFirst;

        boolean untargetedFirst;
    }

    private static final Stats STATS = new Stats();

    private PackStepPipelines() {
    }

    /** 资源重载后清表：换代的对象与语义都可能不同（GAP-018/045 那族「缓存没有包代次这一维」的形状）。 */
    public static void clearCache() {
        MEMO.clear();
        BROKEN.clear();
    }

    /**
     * 该步该用的<b>已编译</b>管线 —— 链上唯一的消费入口（{@code FrameApi#runPostPass}）。
     *
     * <p>没声明混合时这条路与改动前<b>逐字相同</b>：直接把入参管线交给
     * {@code RenderSystem.getCompiledPipelineNullable}，异常语义也不套 try（保持「族管线不见了就抛给
     * 调用方」这一条既有硬失败）。只有真要派生变体时才进 try：变体编译失败是<b>包声明的问题</b>，
     * 该退回族管线并点名，不该把一帧链打死。
     */
    public static CompiledRenderPipeline compiled(RenderPipeline base,
            dev.vkdisp.pack.PackPostChain.Pass plan) {
        RenderPipeline variant = forPass(base, plan, dev.vkdisp.pack.ActivePack.get());
        if (variant == base) {
            return RenderSystem.getCompiledPipelineNullable(base);
        }
        CompiledRenderPipeline compiled = tryCompile(variant, plan.programName());
        if (compiled != null) {
            return compiled;
        }
        BROKEN.add(variant);
        STATS.broken++;
        if (!STATS.brokeFirst) {
            STATS.brokeFirst = true;
            VkDisp.LOGGER.warn("vkdisp: [blend] 首条派生管线编译不出来：程序={} 族管线={} ⇒ 该步继续用族管线的混合"
                            + "（原版编译失败的原因在它自己的日志里，本类不重复猜）",
                    plan.programName(), base.getLocation());
        }
        return RenderSystem.getCompiledPipelineNullable(base);
    }

    /**
     * 按该步<b>实际写入的槽</b>逐格算混合，派生出变体（记忆过）。
     *
     * <p>为什么要按槽：{@code blend.<程序>.<缓冲>} 这一形在真实包里是主流（Complementary 35 条里
     * 33 条），而链的 location 与 colortex 号不是一回事 —— 第 i 个 location 写的是
     * {@code plan.attachmentSlots().get(i)} 那一格（见 {@code FrameApi#runPostPass} 的附件布局注释）。
     * 超出被写槽数的 location 是 scratch，本步不写它 ⇒ 一格都不许动。
     */
    public static RenderPipeline forPass(RenderPipeline base, dev.vkdisp.pack.PackPostChain.Pass plan,
            String activePack) {
        List<Optional<BlendFunction>> slots = blendPlan(base, activePack, plan.programName(),
                plan.attachmentSlots());
        if (slots == null) {
            return base;
        }
        Map<List<Optional<BlendFunction>>, RenderPipeline> perBase = MEMO.get(base);
        RenderPipeline hit = perBase == null ? null : perBase.get(slots);
        if (hit != null) {
            return BROKEN.contains(hit) ? base : hit;
        }
        RenderPipeline variant = derive(base, slots, plan.programName(), activePack);
        if (variant == null) {
            return base;
        }
        STATS.applied++;
        if (MEMO.size() > MEMO_BASE_CAP) {
            MEMO.clear();
            BROKEN.clear();
        }
        MEMO.computeIfAbsent(base, key -> new HashMap<>()).put(slots, variant);
        return variant;
    }

    /**
     * 纯函数：这个程序对这步写的每一格声明了什么混合。
     *
     * @return {@code null} = 什么都没声明（调用方原样用族管线）；否则长度 = 管线声明的目标数，
     *         元素 {@code null} = 这一格不动，{@code Optional} = 换成它（{@code empty} = 摘掉混合）
     */
    public static List<Optional<BlendFunction>> blendPlan(RenderPipeline base, String activePack,
            String program, List<Integer> written) {
        List<? extends ColorTargetState> targets = base == null ? null : base.getColorTargetStates();
        if (targets == null || targets.isEmpty() || program == null) {
            return null;
        }
        List<Optional<BlendFunction>> plan = new ArrayList<>(targets.size());
        boolean any = false;
        for (int location = 0; location < targets.size(); location++) {
            if (written == null || location >= written.size()) {
                plan.add(null);
                continue;
            }
            Optional<BlendMode> declared = PackBlendDirectives.forTarget(activePack, program,
                    written.get(location));
            if (declared.isEmpty()) {
                plan.add(null);
                continue;
            }
            PackBlendState.Outcome outcome = PackBlendState.of(declared.get(), declared.get().toString());
            if (!outcome.applies()) {
                STATS.unreadable++;
                if (!STATS.namedFirst) {
                    STATS.namedFirst = true;
                    VkDisp.LOGGER.warn("vkdisp: [blend] 首条读不懂的混合声明被忽略：程序={} 槽=colortex{} 因子名={}"
                                    + "（原文 {}）⇒ 该格继续走族管线的混合，不猜一个上去",
                            program, written.get(location), outcome.detail(), declared.get());
                }
                plan.add(null);
                continue;
            }
            // OFF 与「没声明」在这里分开：OFF 要真的把混合摘掉，所以是 Optional.empty()。
            plan.add(Optional.ofNullable(outcome.blend()));
            any = true;
        }
        return any ? plan : null;
    }

    /** 走原版的「未命中就地编译」（{@code PipelineCache.java:29-38}）；任何异常/ null 都算编译不出来。 */
    private static CompiledRenderPipeline tryCompile(RenderPipeline variant, String program) {
        try {
            return RenderSystem.getCompiledPipelineNullable(variant);
        } catch (Exception e) {
            VkDisp.LOGGER.warn("vkdisp: [blend] 派生管线 {} 编译抛出 {}：程序={} ⇒ 退回族管线",
                    variant.getLocation(), e.getClass().getSimpleName(), program);
            return null;
        }
    }

    /** 按逐格计划派生变体；一格都落不下去时返回 {@code null}（调用方交回入参管线，并点名一次）。 */
    private static RenderPipeline derive(RenderPipeline base, List<Optional<BlendFunction>> slots,
            String program, String activePack) {
        List<Optional<ColorTargetState>> plan = planTargets(base.getColorTargetStates(), slots);
        if (plan == null) {
            if (!STATS.untargetedFirst) {
                STATS.untargetedFirst = true;
                VkDisp.LOGGER.warn("vkdisp: [blend] 族管线 {} 上没有任何一格能落混合（声明了程序 {} 却全是空目标）"
                                + " ⇒ 该步继续用族管线（X11：这条只点一次名，不静默）",
                        base.getLocation(), program);
            }
            return null;
        }
        RenderPipeline.Builder builder = base.toBuilder();
        for (int index = 0; index < plan.size(); index++) {
            builder.withColorTargetState(index, plan.get(index).orElse(null));
        }
        RenderPipeline variant = builder.build();
        if (!STATS.namedFirst) {
            STATS.namedFirst = true;
            VkDisp.LOGGER.info("vkdisp: [blend] 首条按程序派生的管线：{} → 逐格混合={}（程序 {}；"
                            + "本包声明 整程序={} 逐缓冲={}；表里有 {} 张包）；变体被缓存按对象身份记住，之后每帧查表",
                    base.getLocation(), slots, program, PackBlendDirectives.declared(activePack),
                    PackBlendDirectives.declaredTargets(activePack), PackBlendDirectives.packs());
        }
        return variant;
    }

    /**
     * 纯函数：把 {@code slots} 指到的那些格的混合换掉，其余槽一个字不动。
     *
     * <p>元素语义：{@code null} = 这一格不动；{@code Optional} = 换成它（{@code empty} = 摘掉混合）。
     * 格式与写掩码<b>原样留着</b> —— 原版逐条比「管线格式 == 附件纹理格式」，改格式就是当场抛。
     * 返回 {@code null} = 一格都没得换（没有颜色目标，或被指到的格在族管线上本来就是 null）。
     */
    public static List<Optional<ColorTargetState>> planTargets(
            List<? extends ColorTargetState> declared, List<Optional<BlendFunction>> slots) {
        if (declared == null || declared.isEmpty() || slots == null) {
            return null;
        }
        List<Optional<ColorTargetState>> out = new ArrayList<>(declared.size());
        boolean any = false;
        for (int index = 0; index < declared.size(); index++) {
            ColorTargetState target = declared.get(index);
            Optional<BlendFunction> blend = index < slots.size() ? slots.get(index) : null;
            if (blend != null && target != null) {
                out.add(Optional.of(new ColorTargetState(blend, target.format(), target.writeMask())));
                any = true;
                continue;
            }
            out.add(Optional.ofNullable(target));
        }
        return any ? out : null;
    }

    /** 只换第 0 格的旧口径（{@code blend.<程序>} 整程序形态在单附件管线上就是这一形）。 */
    public static List<Optional<ColorTargetState>> planTargets(
            List<? extends ColorTargetState> declared, Optional<BlendFunction> blend) {
        return planTargets(declared, List.of(blend));
    }

    /** 自报口径：应用了多少、忽略了多少、编译不出来多少、记忆表基数（与 {@code MainPassPipelineDerivation.STATS} 同族做法）。 */
    public static String statsLine() {
        return "blend 派生管线 applied=" + STATS.applied + " unreadable=" + STATS.unreadable
                + " broken=" + STATS.broken + " memoBases=" + MEMO.size() + " brokenVariants=" + BROKEN.size();
    }
}
