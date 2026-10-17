package dev.vkdisp.bridge;

import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.vkdisp.VkDisp;
import dev.vkdisp.pack.PackBlendDirectives;
import dev.vkdisp.pack.model.BlendMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * 命中前提是「同一条派生结果每次是同一个对象」，故按 (入参管线身份, 混合值) 记一层。
 *
 * <p>🔴 <b>调用方必须先编译，不能查缓存</b>（2026-10-17 实测，接了又撤）：本类交回的是
 * {@link RenderPipeline}（未编译），而 {@code RenderPass.setPipeline} 只收
 * {@code CompiledRenderPipeline}（{@code api/commands/RenderPass.java:32}），
 * {@code RenderSystem.getCompiledPipelineNullable}（{@code blaze3d/systems/RenderSystem.java:106-120}）
 * 又<b>只查缓存、不按需编译</b> ⇒ 直接把变体塞进那两个函数，会让每个声明混合的程序在
 * {@code runPostPass} 那句 {@code IllegalStateException("post pipeline slot … vanished mid-frame")}
 * 上炸。正解：走 {@code GpuDevice.compilePipeline(pipeline, shaderSource, executor)}
 * （{@code api/device/GpuDevice.java:54}）自己编译，并把<b>编译结果</b>记进本类记忆表
 * （上游 {@code GeometryProgram.java:742/1065-1099} 就是这个形状：builder → build → compile(device)）。
 *
 * <p>🔴 <b>三种情形分得很开</b>：没声明混合 → 原样交回入参管线（零行为变化）；
 * {@code off} → 派生一条「目标0 不带 BlendFunction」的变体（包在要求不混合）；
 * 读不懂 → 计数 + 首次点名，<b>不猜</b>（X11 不许静默降级）。
 */
public final class PackStepPipelines {

    /** 记忆表基数上限：超了整表清空 —— 最坏是重新派生一次，不清就是把换代的管线对象钉住不放。 */
    private static final int MEMO_BASE_CAP = 64;

    private static final Map<RenderPipeline, Map<Optional<BlendFunction>, RenderPipeline>> MEMO =
            new IdentityHashMap<>();

    /** 计数装在 final 容器里（QD-03 棘轮管的是「静态非 final 字段」；形状同 {@code MainPassPipelineDerivation.STATS}）。 */
    private static final class Stats {

        long applied;

        long unreadable;

        boolean namedFirst;
    }

    private static final Stats STATS = new Stats();

    private PackStepPipelines() {
    }

    /** 资源重载后清表：换代的对象与语义都可能不同（GAP-018/045 那族「缓存没有包代次这一维」的形状）。 */
    public static void clearCache() {
        MEMO.clear();
    }

    /**
     * 该步要用的管线：没声明就是入参本身；声明可用就是一条按程序派生的变体（记忆过）。
     *
     * @param base    该步所属族的既有管线（composite / compositeScene / deferred / final）
     * @param program 该步的程序名，裸名或 {@code 维度/名} 都收（见 {@link PackBlendDirectives#forProgram}）
     */
    public static RenderPipeline forStep(RenderPipeline base, String program) {
        Optional<BlendMode> declared = PackBlendDirectives.forProgram(program);
        if (base == null || declared.isEmpty()) {
            return base;
        }
        PackBlendState.Outcome outcome = PackBlendState.of(declared.get(), declared.get().toString());
        if (!outcome.applies()) {
            STATS.unreadable++;
            if (!STATS.namedFirst) {
                STATS.namedFirst = true;
                VkDisp.LOGGER.warn("vkdisp: [blend] 首条读不懂的混合声明被忽略：程序={} 因子名={}（原文 {}）"
                                + " ⇒ 该程序继续走族管线的混合，不猜一个上去",
                        program, outcome.detail(), declared.get());
            }
            return base;
        }
        // OFF 与「没声明」在这里分开：OFF 要真的把混合摘掉，所以 blend = Optional.empty()。
        Optional<BlendFunction> blend = Optional.ofNullable(outcome.blend());
        Map<Optional<BlendFunction>, RenderPipeline> perBase = MEMO.get(base);
        RenderPipeline hit = perBase == null ? null : perBase.get(blend);
        if (hit != null) {
            return hit;
        }
        RenderPipeline variant = derive(base, blend, program);
        if (variant == null) {
            return base;
        }
        STATS.applied++;
        if (MEMO.size() > MEMO_BASE_CAP) {
            MEMO.clear();
        }
        MEMO.computeIfAbsent(base, key -> new HashMap<>()).put(blend, variant);
        return variant;
    }

    /** 派生一条「目标0 带该混合」的变体；没有可改的目标时返回 {@code null}（调用方交回入参管线）。 */
    private static RenderPipeline derive(RenderPipeline base, Optional<BlendFunction> blend,
            String program) {
        List<Optional<ColorTargetState>> plan = planTargets(base.getColorTargetStates(), blend);
        if (plan == null) {
            return null;
        }
        RenderPipeline.Builder builder = base.toBuilder();
        for (int index = 0; index < plan.size(); index++) {
            builder.withColorTargetState(index, plan.get(index).orElse(null));
        }
        RenderPipeline variant = builder.build();
        if (!STATS.namedFirst) {
            STATS.namedFirst = true;
            VkDisp.LOGGER.info("vkdisp: [blend] 首条按程序派生的管线：{} → 目标0 混合={}（程序 {}）"
                            + "；变体被原版编译缓存按对象身份记住，之后每帧是查表",
                    base.getLocation(), blend, program);
        }
        return variant;
    }

    /**
     * 纯函数：把目标0 的混合换成 {@code blend}，其余槽一个字不动。
     *
     * <p>返回 {@code null} = 不适用（没有颜色目标、或目标0 本身是 null）⇒ 调用方交回入参管线。
     * 拆成纯函数是为了不启动客户端就能把「只动第 0 槽」钉成单测。
     */
    public static List<Optional<ColorTargetState>> planTargets(
            List<? extends ColorTargetState> declared, Optional<BlendFunction> blend) {
        if (declared == null || declared.isEmpty() || declared.get(0) == null) {
            return null;
        }
        ColorTargetState first = declared.get(0);
        List<Optional<ColorTargetState>> out = new ArrayList<>(declared.size());
        out.add(Optional.of(new ColorTargetState(blend, first.format(), first.writeMask())));
        for (int index = 1; index < declared.size(); index++) {
            out.add(Optional.ofNullable(declared.get(index)));
        }
        return out;
    }

    /** 自报口径：应用了多少、忽略了多少、记忆表基数（与 {@code MainPassPipelineDerivation.STATS} 同族做法）。 */
    public static String statsLine() {
        return "blend 派生管线 applied=" + STATS.applied + " unreadable=" + STATS.unreadable
                + " memoBases=" + MEMO.size();
    }
}
