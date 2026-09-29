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
 * 3. 我们的差异点：只暴露纯 Java 视图 {@link FrameSize} 与 {@link #drawFullscreen(String)}；
 *    RenderPass / RenderPipeline / CompiledRenderPipeline / GpuTextureView 等原版类型全部封在方法体内，
 *    业务包零 com.mojang.renderpearl import；管线无 depthStencilState，故不挂 depth 附件；
 *    任何失败原样抛异常（不吞），由业务层打 ERROR 原文。
 * 4. 许可证核对：本项目 MIT；只调用公开 API 签名，无代码复制（07-CONSTRAINTS §〇 P1、L5-L8）。
 * 5. 性能基线：每帧一次 draw，冷路径（按 task-2 要求）不做性能优化（17-NATIVE.md §3.2）。
 */
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;

/**
 * 原版绘制 API 唯一入口（06-MIGRATION.md §2.1 的 bridge 红线，07-CONSTRAINTS T5）。
 *
 * <p>对主渲染目标开一个 render pass 并画全屏三角形；全部原版类型封在方法体内，
 * 业务包只看到 {@link FrameSize} 这一纯 Java 视图。
 */
public final class FrameApi {
    /** 主渲染目标尺寸的纯 Java 视图（业务包用于首帧埋点）。 */
    public record FrameSize(int width, int height) {}

    private FrameApi() {}

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
        return PipelineApi.isFullscreenPipelineRegistered()
                && RenderSystem.getCompiledPipelineNullable(PipelineApi.fullscreenPipeline()) != null;
    }

    /**
     * 在主渲染目标（main target）上执行一次全屏绘制。
     *
     * <p>必须在渲染线程调用（RenderFrameEvent.Post 即是）；此处位于 swapchain 上屏之前，
     * 写入的颜色会出现在本帧画面上。
     *
     * @param label render pass 调试标签（renderdoc / Vulkan 调试层可读）
     * @return 主目标尺寸，供业务层打首帧埋点
     * <p>调用前应先查 {@link #isPipelineReady()}：未就绪时本方法抛异常（防止静默画不出东西），
     * 就绪状态下的绘制失败同样原样抛出。
     *
     * @throws IllegalStateException 管线未注册 / 尚未编译完成 / 主目标纹理视图不存在时抛出（绝不静默）
     */
    public static FrameSize drawFullscreen(String label) {
        RenderSystem.assertOnRenderThread();
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuTextureView colorView = main.getColorTextureView();
        if (colorView == null) {
            throw new IllegalStateException("vkdisp: main target color texture view is null (can't open render pass)");
        }
        int width = colorView.getWidth(0);
        int height = colorView.getHeight(0);

        // 未编译完成时抛异常（调用方应先用 isPipelineReady() 过滤，见该方法 Javadoc）。
        // 不用 vanilla 的 getCompiledPipeline，是为了给出带管线位置的明确原文，便于定位。
        CompiledRenderPipeline compiled =
                RenderSystem.getCompiledPipelineNullable(PipelineApi.fullscreenPipeline());
        if (compiled == null) {
            throw new IllegalStateException(
                    "vkdisp: fullscreen pipeline not compiled yet: " + PipelineApi.FULLSCREEN_LOCATION);
        }

        // 官方 PostPass 同款序列：无 depth 附件（本管线没有 depthStencilState）、不清屏（loadOp = LOAD）、
        // 无顶点绑定（全屏三角形由 gl_VertexIndex 推出）。
        try (RenderPass pass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(
                        () -> label, colorView, Optional.empty(), null, OptionalDouble.empty())) {
            pass.setPipeline(compiled);
            RenderSystem.bindDefaultUniforms(pass);
            pass.draw(3, 1, 0, 0);
        }
        return new FrameSize(width, height);
    }
}
