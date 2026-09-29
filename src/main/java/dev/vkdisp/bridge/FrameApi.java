package dev.vkdisp.bridge;
/**
 * 【参考调研】P0.3 全屏绘制 / 原版 renderpearl 渲染通道 API + NeoForge 帧事件时机
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 原版 Minecraft 26.3 客户端 com.mojang.renderpearl.api.commands.*
 *    （CommandEncoder / RenderPass）与 com.mojang.blaze3d.systems.RenderSystem（运行平台与官方 API 提供方）。
 *    许可证：Mojang EULA（原版）→ 只观察 javap 签名与官方调用点（原版 PostPass 的 pass 执行序列），
 *    零源码文本搬运；参考模组（VulkanMod / Sulkan 等）零接触。
 *    → 能否并入本项目（MIT）：可以 —— 本文件是独立实现的薄封装，只调用公开 API，不含被参考方代码
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：原版 PostPass#addToFrame 的 pass 执行序列（createRenderPass(label, colorView,
 *    Optional.empty(), depth|null, OptionalDouble.empty()) → setPipeline(getCompiledPipeline) →
 *    bindDefaultUniforms → draw(3,1,0,0)，无顶点绑定；全屏三角形顶点由 gl_VertexIndex 推出）；
 *    触发时机 = NeoForge ClientHooks.fireRenderFramePost —— Minecraft.renderFrame 中
 *    GameRenderer.render() 之后、swapchainBlit（主目标上屏）之前，此刻写 main target 必然出现在屏幕上。
 * 2. 备选：FrameGraphSetupEvent 帧图插 pass —— 调研否决（vanilla clear pass 会随后全清 main target，
 *    图案必被抹掉），不采用；RenderFrameEvent 属官方事件，无需 GAP 登记。
 * 1b.（P1.1 补充）自定义 uniform 上传：原版 PostPass 用 MappableRingBuffer(usage=MAP_WRITE|UNIFORM=130)
 *    + Std140Builder 写 UBO + setUniform(name, buffer) 的官方序列；GLSL 侧块名与绑定布局 uniform 名一致
 *    （原版范本 assets/minecraft/shaders/core/clouds.vsh 的 layout(std140) uniform CloudInfo）。
 * 1c.（P2 前置）纹理采样链路：原版 BindGroupLayouts.IN_SAMPLER =
 *    BindGroupLayout.builder().withUniform("InSampler", COMBINED_IMAGE_SAMPLER)（字节码核实）；
 *    GLSL 侧 uniform sampler2D InSampler（原版 core/blit_depth.fsh）；纹理创建走
 *    GpuDevice.createTexture(label, TEXTURE_BINDING|COPY_DST, RGBA8_UNORM, w,h,1,1) +
 *    NativeImage + createCommandEncoder().writeToTexture(texture, image) + createTextureView；
 *    采样器取原版 RenderSystem.getSamplerCache().getClampToEdge(FilterMode)。
 * 3. 我们的差异点：只暴露纯 Java 视图 {@link FrameSize}/{@link FrameParams} 与
 *    {@link #drawFullscreen(String, FrameParams)}；
 *    RenderPass / RenderPipeline / CompiledRenderPipeline / GpuTextureView 等原版类型全部封在方法体内，
 *    业务包零 com.mojang.renderpearl import；管线无 depthStencilState，故不挂 depth 附件；
 *    任何失败原样抛异常（不吞），由业务层打 ERROR 原文。
 * 4. 许可证核对：本项目 MIT；只调用公开 API 签名，无代码复制（07-CONSTRAINTS §〇 P1、L5-L8）。
 * 5. 性能基线：每帧一次 draw，冷路径（按 task-2 要求）不做性能优化（17-NATIVE.md §3.2）。
 */
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.joml.Vector4f;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;

/**
 * 原版绘制 API 唯一入口（06-MIGRATION.md §2.1 的 bridge 红线，07-CONSTRAINTS T5）。
 *
 * <p>对主渲染目标开一个 render pass 并画全屏三角形；全部原版类型封在方法体内，
 * 业务包只看到 {@link FrameSize} 这一纯 Java 视图。
 */
public final class FrameApi {
    /** 主渲染目标尺寸的纯 Java 视图（业务包用于首帧埋点）。 */
    public record FrameSize(int width, int height) {}

    /**
     * P1.1：每帧 uniform 参数的纯 Java 视图（业务包只传裸浮点，原版类型不出 bridge）。
     *
     * <p>对应 GLSL {@code layout(std140) uniform VkDispParams { vec4 Params; };} 的 x/y 分量。
     *
     * @param phase     动画相位（秒级，滑动取模；驱动棋盘平移，用于「改数值画面就变」的验收）
     * @param intensity 强度（0..1，当前用于棋盘亮度，预留）
     */
    public record FrameParams(float phase, float intensity) {}

    /** std140 vec4 = 16 字节（对齐规则：vec4 偏移必须 16 字节对齐）。 */
    private static final int PARAMS_BYTES = 16;

    /** 每帧写入的 uniform 环形缓冲（原版 PostPass 同款 MappableRingBuffer）。 */
    private static MappableRingBuffer paramsRing;

    private FrameApi() {}

    /**
     * 每帧 uniform 环形缓冲（懒创建：必须在渲染线程 / 设备就绪后）。
     *
     * <p>用法照原版 {@code PostPass}：usage = {@code MAP_WRITE | UNIFORM}（实测原版字节码为 130 = 128|2），
     * 每帧 map(false,true) 写入 → close 落盘 → setUniform(name, buffer) → 绘制后 rotate()。
     */
    private static MappableRingBuffer paramsRing() {
        MappableRingBuffer ring = paramsRing;
        if (ring == null) {
            ring = new MappableRingBuffer(
                    () -> "vkdisp params",
                    GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM,
                    PARAMS_BYTES);
            paramsRing = ring;
        }
        return ring;
    }

    /**
     * 离屏渲染目标（P2 前置：图案先画到它上面，再被下一个 pass 采样进主目标）。
     *
     * <p>用原版 {@link TextureTarget}（{@code RenderTarget} 子类，vanilla 内部目标同款）：
     * 自带颜色/深度纹理与需要时的 {@code resize}，避免手工管理 {@code GpuTexture} 生命周期。
     * 之所以需要离屏目标：同一 pass 内既写又采样同一纹理在 Vulkan 属非法反馈回路，
     * 真实 composite 链必须靠中间目标（ping-pong）——这就是最小可运行的 ping-pong 骨架。
     */
    private static TextureTarget offscreenTargetA;
    private static TextureTarget offscreenTargetB;

    /**
     * 按主目标尺寸取第 {@code slot} 个离屏目标（0/1 两个，交替作为 ping-pong 的两端）。
     *
     * <p>尺寸变化时 resize，不每帧重建；两个目标都按主目标尺寸分配，保证中间级分辨率一致。
     */
    private static TextureTarget offscreenTarget(int slot, int width, int height) {
        TextureTarget target = slot == 0 ? offscreenTargetA : offscreenTargetB;
        if (target == null) {
            // 槽 0 带深度附件（P3 前置：图案 pass 写深度、depthviz pass 采样它）；
            // 槽 1 只做颜色 ping-pong，不需要深度。
            target = new TextureTarget(
                    "vkdisp offscreen " + slot,
                    width,
                    height,
                    GpuFormat.RGBA8_UNORM,
                    slot == 0 ? GpuFormat.D32_FLOAT : null);
            if (slot == 0) {
                offscreenTargetA = target;
            } else {
                offscreenTargetB = target;
            }
        } else if (target.width != width || target.height != height) {
            target.resize(width, height);
        }
        return target;
    }

    /**
     * P1.2 断言用：已注册管线中「编译成功」的数量（纯整数视图）。
     *
     * <p>与 {@link PipelineApi#registeredPipelineCount()} 比较，二者不等说明有管线静默编译失败
     * （{@code 08-TESTING.md} §3 要求「注册数 == 编译成功数（不得静默少）」）。
     */
    public static int compiledPipelineCount() {
        int compiled = 0;
        for (RenderPipeline pipeline : PipelineApi.registeredPipelines()) {
            if (RenderSystem.getCompiledPipelineNullable(pipeline) != null) {
                compiled++;
            }
        }
        return compiled;
    }

    /**
     * 全屏管线是否已编译完成（可以绘制）。
     *
     * <p><b>为什么需要它</b>：管线在 Minecraft 构造期注册（早于资源重载），而 GLSL 编译发生在
     * 资源重载的异步阶段；重载完成前 {@code getCompiledPipelineNullable} 返回 null。
     * 这属于<b>等待</b>而非失败，调用方应跳过该帧而不是打 ERROR（P0.3 实测发现：早期帧按失败处理
     * 会刷出 11 条 ERROR，违反 01-DEV-LOOP §9「日志无 ERROR」）。
     *
     * @return 已编译返回 true；尚未完成编译返回 false（不抛异常，供每帧轮询）
     */
    public static boolean isPipelineReady() {
        // 本帧链用到 pattern / depthviz / blit 三条；composite 已注册但不在本帧链中（见 drawFullscreen 注释）。
        return PipelineApi.isFullscreenPipelineRegistered()
                && PipelineApi.isDepthVisPipelineRegistered()
                && PipelineApi.isBlitPipelineRegistered()
                && RenderSystem.getCompiledPipelineNullable(PipelineApi.fullscreenPipeline()) != null
                && RenderSystem.getCompiledPipelineNullable(PipelineApi.depthVisPipeline()) != null
                && RenderSystem.getCompiledPipelineNullable(PipelineApi.blitPipeline()) != null;
    }

    /**
     * 在主渲染目标（main target）上执行一次全屏绘制。
     *
     * <p>必须在渲染线程调用（RenderFrameEvent.Post 即是）；此处位于 swapchain 上屏之前，
     * 写入的颜色会出现在本帧画面上。
     *
     * @param label  render pass 调试标签（renderdoc / Vulkan 调试层可读）
     * @param params 每帧 uniform 参数（纯 Java 值；写入 {@code VkDispParams} 块后绘制）
     * @return 主目标尺寸，供业务层打首帧埋点
     * <p>调用前应先查 {@link #isPipelineReady()}：未就绪时本方法抛异常（防止静默画不出东西），
     * 就绪状态下的绘制失败同样原样抛出。
     *
     * @throws IllegalStateException 管线未注册 / 尚未编译完成 / 主目标纹理视图不存在时抛出（绝不静默）
     */
    public static FrameSize drawFullscreen(String label, FrameParams params) {
        RenderSystem.assertOnRenderThread();
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuTextureView colorView = main.getColorTextureView();
        if (colorView == null) {
            throw new IllegalStateException("vkdisp: main target color texture view is null (can't open render pass)");
        }
        int width = colorView.getWidth(0);
        int height = colorView.getHeight(0);

        // 离屏目标与采样器必须在开启 render pass **之前**解析：pass 打开期间 encoder 不允许其它命令
        // （实测异常原文："Close the existing render pass before performing additional commands"）。
        TextureTarget targetA = offscreenTarget(0, width, height);
        TextureTarget targetB = offscreenTarget(1, width, height);
        GpuTextureView viewA = targetA.getColorTextureView();
        GpuTextureView depthA = targetA.getDepthTextureView();
        GpuTextureView viewB = targetB.getColorTextureView();
        if (depthA == null) {
            throw new IllegalStateException("vkdisp: offscreen0 depth texture view is null (expected D32_FLOAT)");
        }
        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);

        // 三条管线都要就绪（图案 + 合成 + 传递）；任一未编译完成都抛异常，绝不静默少画一个 pass。
        CompiledRenderPipeline pattern = RenderSystem.getCompiledPipelineNullable(PipelineApi.fullscreenPipeline());
        if (pattern == null) {
            throw new IllegalStateException(
                    "vkdisp: fullscreen pipeline not compiled yet: " + PipelineApi.FULLSCREEN_LOCATION);
        }
        CompiledRenderPipeline depthVis = RenderSystem.getCompiledPipelineNullable(PipelineApi.depthVisPipeline());
        if (depthVis == null) {
            throw new IllegalStateException(
                    "vkdisp: depthviz pipeline not compiled yet: " + PipelineApi.DEPTHVIS_LOCATION);
        }
        CompiledRenderPipeline blit = RenderSystem.getCompiledPipelineNullable(PipelineApi.blitPipeline());
        if (blit == null) {
            throw new IllegalStateException(
                    "vkdisp: blit pipeline not compiled yet: " + PipelineApi.BLIT_LOCATION);
        }

        // P1.1：把本帧参数写进环形缓冲的当前槽（std140：vec4 Params = {phase, intensity, 0, 0}）。
        MappableRingBuffer ring = paramsRing();
        try (GpuBufferSlice.MappedView view = ring.currentBuffer().map(false, true)) {
            Std140Builder.intoBuffer(view.data()).putVec4(params.phase(), params.intensity(), 0.0F, 0.0F);
        }

        // 官方 PostPass 同款序列：无 depth 附件（本管线没有 depthStencilState）、不清屏（loadOp = LOAD）、
        // 无顶点绑定（全屏三角形由 gl_VertexIndex 推出）。
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        // Pass A：图案 → 中间目标 A（颜色清为不透明黑、深度清为 1.0），同时写深度。
        try (RenderPass pass = encoder.createRenderPass(
                () -> label + " A (pattern -> offscreen0)",
                viewA,
                Optional.of(new Vector4f(0.0F, 0.0F, 0.0F, 1.0F)),
                depthA,
                OptionalDouble.of(1.0D))) {
            pass.setPipeline(pattern);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform(PipelineApi.PARAMS_UNIFORM, ring.currentBuffer());
            pass.draw(3, 1, 0, 0);
        }
        // Pass B（P3 前置）：采样 offscreen0 的**深度纹理** → offscreen1 灰度图。
        // 这一步证明「深度附件真被写入且可被采样」，而不只是「深度附件分配了」。
        // 说明：合成管线（R/B 交换，上一轮已验证）本轮**不在本帧链里**——它与本 pass 争用同一目标，
        // 而本轮要验证的是深度链路；两级的合并留待 pack 链落地（P2.4）时统一编排。
        try (RenderPass pass = encoder.createRenderPass(
                () -> label + " B (depth sample -> offscreen1)",
                viewB,
                Optional.of(new Vector4f(0.0F, 0.0F, 0.0F, 1.0F)),
                null,
                OptionalDouble.empty())) {
            pass.setPipeline(depthVis);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform(PipelineApi.SAMPLER_UNIFORM, depthA, sampler);
            pass.draw(3, 1, 0, 0);
        }
        // Pass C：中间目标 B → 主目标（最后一级；主目标只由本 pass 写入）。
        try (RenderPass pass = encoder.createRenderPass(
                () -> label + " C (offscreen1 -> main)", colorView, Optional.empty(), null, OptionalDouble.empty())) {
            pass.setPipeline(blit);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform(PipelineApi.SAMPLER_UNIFORM, viewB, sampler);
            pass.draw(3, 1, 0, 0);
        }
        // 原版 PostPass 同款：绘制后再 rotate，保证本帧写入的槽在 GPU 用完前不被复用。
        ring.rotate();
        return new FrameSize(width, height);
    }
}
