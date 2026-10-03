package dev.vkdisp.bridge;
/**
 * 【参考调研】GAP-003 多附件（MRT）能力验证 / 原版 {@code RenderPassDescriptor} + {@code CommandEncoder#createRenderPass}
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 原版 26.3 {@code com.mojang.renderpearl.api.commands.RenderPassDescriptor.Builder}
 *    （{@code withColorAttachment(view)} 可重复调用追加附件；{@code build()} 用最后一个非空附件定
 *    {@code RenderArea}）；② {@code CommandEncoder#createRenderPass(RenderPassDescriptor)}（多附件入口，
 *    与项目既有的 5 参便捷重载并列，同文件第 28/36/53 行）；
 *    ③ 原版 {@code DeviceLimits#maxColorAttachments}（设备能力）。
 *    许可证：Mojang EULA。**只调用公开 API，不复制实现语句、不搬运着色器文本。**
 *    → 能否并入本项目（MIT）：可以 —— 本文件为独立编写的桥接封装
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：原版 {@code RenderPassDescriptor.builder(...)} + 多次 {@code withColorAttachment}，
 *    与项目既有单附件便捷重载（{@code createRenderPass(label, view, clear, depth, clearDepth)}）同族。
 * 2. 备选：① 改原版 {@code FrameGraphBuilder}/{@code RenderTarget} 多挂附件 —— 否决
 *    （侵入原版类型，M1 不允许；且 {@code RenderTarget} 只有单个颜色纹理）；
 *    ② 立刻把地形接进多附件 pass —— 否决（源码级核实：原版主 pass 把地形/实体/特性/云/描边
 *    画在同一个 pass 同一个单附件里 ⇒ 改成多附件会让所有原版管线附件数不匹配而全部 validation
 *    error，证据见 {@code evidence/h01-terrain-pipeline-wire.md} §5.0.2）。**先验原语再谈接管。**
 * 3. 我们的差异点：① 附件数由设备能力收敛（{@link MrtPlan#clampSlots}），超限与「设备不支持」
 *    分开报错（T11）；② 每个附件写**逐槽不同的 R 指纹**，使「分槽生效」成为可量化判据；
 *    ③ 整条路径**默认关闭**（配置项），关闭时常规帧零开销（支柱③）。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：管线注册 ❄️ 冷路径；绘制属可关闭的按需诊断（默认关闭），不做性能优化。
 */
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.vkdisp.VkDisp;
import dev.vkdisp.VkDispConfig;
import dev.vkdisp.pipeline.model.MrtPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import org.joml.Vector4f;

/**
 * GAP-003 的**能力验证件**：在 vkdisp 自己的 pass 里把多附件（MRT）原语跑通并量化。
 *
 * <p><b>它证明什么</b>：本项目 15 条管线此前全是单附件（{@code ColorTargetState.DEFAULT}）。
 * 这个类证明「后端 + 驱动 + 我方管线构造」三者的多附件通道**真的可用**：
 * 一个 render pass 绑 N 个颜色附件、一条管线声明 N 个 {@code ColorTargetState}、
 * 一个片元写 N 个 {@code layout(location=N) out}，且每个附件都拿到**可区分**的内容。
 *
 * <p><b>它不证明什么</b>（不要拿它当 GAP-003 已完成）：
 * <ul>
 *   <li>⛔ <b>地形没被接进来</b> —— 画的是全屏三角形，不是地形。接地形需要拿到地形 pass 的
 *       所有权（M-04），而那一步会与原版其它 draw 冲突（见类注释第 2 条备选②）。</li>
 *   <li>⛔ <b>包的自研 {@code gbuffers_*} 片元没接</b> —— GAP-004 的块仍无消费者。</li>
 * </ul>
 */
public final class MrtProbe {

    /** 离屏 colortex 目标（懒建；尺寸跟随主目标）。 */
    private static TextureTarget[] colortex;

    /** 实际附件数（设备能力收敛后的结果；0 = 尚未初始化）。 */
    private static int actualSlots;

    /** 设备上报的原始上限（首次初始化时的日志口径）。 */
    private static int deviceLimit;

    /** 是否至少成功跑过一帧（{@link #ready()} 的门闩）。 */
    private static boolean probed;

    /** 一次性初始化日志的哨兵。 */
    private static boolean initLogged;

    private MrtProbe() {
    }

    /** MRT 调试视图开关（默认关闭 ⇒ 常规帧零开销）。 */
    public static boolean enabled() {
        return VkDispConfig.MRT_ENABLED.get();
    }

    /** 当前要显示的槽位（纯 int 视图，合法性由 {@link MrtPlan#requireViewSlot} 校验）。 */
    public static int viewSlot() {
        return VkDispConfig.MRT_VIEW_SLOT.get();
    }

    /** 实际附件数（未初始化 = 0）。 */
    public static int actualSlots() {
        return actualSlots;
    }

    /** 设备上报的 {@code maxColorAttachments} 原始值（未初始化 = 0）。 */
    public static int deviceLimit() {
        return deviceLimit;
    }

    /** 是否至少跑过一帧 MRT 绘制（门闩；供日志与单测语义对齐）。 */
    public static boolean ready() {
        return probed;
    }

    /**
     * 把**任意外部**视图回读到主目标（复用 MRT 回读管线）。
     *
     * <p>用途：让我方 MRT 地形 pass（{@link MrtTerrainPass}）自己的 colortex 也能被肉眼/像素判定 ——
     * 否则「pass 跑完没报错」只能证明**没崩**，证明不了**画对了**（本项目反复踩的坑：
     * 「没报错」≠「画对了」）。
     *
     * @param label render pass 调试标签
     * @param view  要回读的视图（调用方保证非 null）
     * @param slot  仅用于日志/错误信息的槽位号
     */
    public static void drawExternalView(String label, GpuTextureView view, int slot) {
        RenderSystem.assertOnRenderThread();
        // 🔖 用**不翻转**版：采样源是引擎自己渲染出来的 colortex（与主目标同取向）。
        //    翻转版是为「包 composite 的 OF 原始 vUv 语义」准备的，用在这里会上下颠倒
        //    （2026-10-03 实测：地形回读曾整体上下颠倒，地面跑到上半屏）。
        var viewCompiled = RenderSystem.getCompiledPipelineNullable(PipelineApi.mrtViewNoFlipPipeline());
        if (viewCompiled == null) {
            throw new IllegalStateException(
                    "vkdisp: mrt view (noflip) pipeline not compiled yet: " + PipelineApi.MRT_VIEW_NOFLIP_LOCATION);
        }
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuTextureView mainView = main.getColorTextureView();
        if (mainView == null) {
            throw new IllegalStateException("vkdisp: mrt external view main color view is null");
        }
        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass pass = encoder.createRenderPass(
                () -> label + " external colortex slot " + slot + " -> main",
                mainView, Optional.empty(), null, OptionalDouble.empty())) {
            pass.setPipeline(viewCompiled);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform(PipelineApi.SAMPLER_UNIFORM, view, sampler);
            pass.draw(3, 1, 0, 0);
        }
    }

    /** 某一槽的颜色视图；越界或未建返回 {@code null}（调用方必须显式处理）。 */
    public static GpuTextureView slotView(int slot) {
        if (colortex == null || slot < 0 || slot >= colortex.length || colortex[slot] == null) {
            return null;
        }
        return colortex[slot].getColorTextureView();
    }

    /**
     * 跑一帧 MRT：① 一个 N 附件 pass 把三个 {@code colortex} 各写一份可区分内容；
     * ② 一个回读 pass 把选定槽显示到主目标。
     *
     * <p>失败一律抛给调用方打 ERROR 原文（T11）—— 静默跳过会让「MRT 不可用」变成看不见的事实。
     */
    public static void draw(String label) {
        RenderSystem.assertOnRenderThread();
        if (colortex == null) {
            init(Minecraft.getInstance().gameRenderer.mainRenderTarget());
        }
        int slot = MrtPlan.requireViewSlot(viewSlot(), actualSlots);

        // 两条管线都必须已编译完成；未完成时抛而不静默跳过（否则「MRT 不可用」变成看不见的事实，T11）。
        var mrtCompiled = RenderSystem.getCompiledPipelineNullable(PipelineApi.mrtPipeline());
        if (mrtCompiled == null) {
            throw new IllegalStateException(
                    "vkdisp: mrt pipeline not compiled yet: " + PipelineApi.MRT_LOCATION);
        }
        // 🔖 用**不翻转**版：采样源是引擎自己渲染出来的 colortex（与主目标同取向）。
        //    翻转版是为「包 composite 的 OF 原始 vUv 语义」准备的，用在这里会上下颠倒
        //    （2026-10-03 实测：地形回读曾整体上下颠倒，地面跑到上半屏）。
        var viewCompiled = RenderSystem.getCompiledPipelineNullable(PipelineApi.mrtViewNoFlipPipeline());
        if (viewCompiled == null) {
            throw new IllegalStateException(
                    "vkdisp: mrt view (noflip) pipeline not compiled yet: " + PipelineApi.MRT_VIEW_NOFLIP_LOCATION);
        }

        var encoder = RenderSystem.getDevice().createCommandEncoder();
        RenderPassDescriptor.Builder descriptor = RenderPassDescriptor.builder(() -> label + " colortex (MRT write)");
        for (int i = 0; i < actualSlots; i++) {
            GpuTextureView view = slotView(i);
            if (view == null) {
                throw new IllegalStateException("vkdisp: mrt slot " + i + " color view is null");
            }
            // ⚠️ 每槽清成不同颜色：若某个附件没真正被写，截图会露出清屏色而不是渐变 ——
            // 「附件绑上了」与「附件被写了」是两件事，只有清屏色能区分。
            descriptor.withColorAttachment(view, Optional.of(
                    new Vector4f(MrtPlan.fingerprintR(i), 0.0F, 0.0F, 1.0F)));
        }

        try (RenderPass pass = encoder.createRenderPass(descriptor.build())) {
            pass.setPipeline(mrtCompiled);
            RenderSystem.bindDefaultUniforms(pass);
            pass.draw(3, 1, 0, 0);
        }

        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuTextureView mainView = main.getColorTextureView();
        if (mainView == null) {
            throw new IllegalStateException("vkdisp: mrt probe main color view is null");
        }
        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (RenderPass pass = encoder.createRenderPass(
                () -> label + " colortex slot " + slot + " -> main",
                mainView, Optional.empty(), null, OptionalDouble.empty())) {
            pass.setPipeline(viewCompiled);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform(PipelineApi.SAMPLER_UNIFORM, slotView(slot), sampler);
            pass.draw(3, 1, 0, 0);
        }

        if (!probed) {
            probed = true;
            VkDisp.LOGGER.info(
                    "vkdisp: [GAP-003] mrt primitive verified: attachments={} deviceMaxColorAttachments={}"
                            + " viewSlot={} fingerprintR={}",
                    actualSlots, deviceLimit, slot, MrtPlan.fingerprintR(slot));
        }
    }

    /** 懒建 N 个 colortex 目标（尺寸跟随主目标；尺寸变化时 resize，不每帧重建）。 */
    private static void init(RenderTarget main) {
        int width = main.width;
        int height = main.height;
        deviceLimit = RenderSystem.getDevice().getDeviceInfo().limits().maxColorAttachments();
        actualSlots = MrtPlan.clampSlots(deviceLimit);
        List<TextureTarget> targets = new ArrayList<>(actualSlots);
        for (int i = 0; i < actualSlots; i++) {
            targets.add(new TextureTarget("vkdisp colortex" + i, width, height, GpuFormat.RGBA8_UNORM, null));
        }
        colortex = targets.toArray(new TextureTarget[0]);
        if (!initLogged) {
            initLogged = true;
            VkDisp.LOGGER.info("vkdisp: [GAP-003] mrt colortex pool ready: {}x{} slots={} (roles={})",
                    width, height, actualSlots, MrtPlan.SLOT_ROLES);
            if (actualSlots < MrtPlan.SLOT_COUNT) {
                // 🔴 分开报：这是「设备能力不足」，不是「我方配置错」（T11）。
                VkDisp.LOGGER.warn(
                        "vkdisp: [GAP-003] device reports maxColorAttachments={} < planned {};"
                                + " running with {} attachment(s) (roles truncated)",
                        deviceLimit, MrtPlan.SLOT_COUNT, actualSlots);
            }
        }
    }

    /** 尺寸变化时重建 colortex 池（由 FrameApi 每帧比对时调用；非本类自愈，避免跨类隐式状态）。 */
    static void resizeIfNeeded(RenderTarget main) {
        if (colortex == null) {
            return;
        }
        if (colortex[0].width != main.width || colortex[0].height != main.height) {
            for (TextureTarget target : colortex) {
                target.resize(main.width, main.height);
            }
            VkDisp.LOGGER.info("vkdisp: [GAP-003] mrt colortex pool resized to {}x{}", main.width, main.height);
        }
    }
}