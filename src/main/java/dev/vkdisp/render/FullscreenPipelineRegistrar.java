package dev.vkdisp.render;
/**
 * 【参考调研】P0.3 管线注册接线 / NeoForge 官方注册事件
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = NeoForge 26.3.0.23-beta RegisterRenderPipelinesEvent 的官方用法
 *    （NeoForgeRenderPipelines）与本仓库 P0.1 骨架的 @EventBusSubscriber 写法。
 *    许可证：NeoForge 事件定义 LGPL-2.1（只观察订阅与调用方式，不复制其实现）；本文件为独立编写的接线类。
 *    → 能否并入本项目（MIT）：可以 —— 只调用事件公开 API 与本方 bridge，不含被参考方代码
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：NeoForgeRenderPipelines 范本（@EventBusSubscriber(value = Dist.CLIENT, modid = ...)
 *    + static @SubscribeEvent 方法 + event.registerPipeline(...)）；事件由原版
 *    RenderPipelines.registerCustomPipelines 经 ModLoader.postEvent 在 mod bus 上触发（IModBusEvent），
 *    时机 = Minecraft 构造期、首次资源加载之前（ClientHooks#init 第 829 行），早于 ShaderManager 编译。
 * 2. 备选：手动 modEventBus.addListener —— 不必要，官方注解订阅即可；本任务全部走官方 API，无需 GAP 登记。
 * 3. 我们的差异点：注册本体在 bridge/PipelineApi（07 T5：业务包零 com.mojang.renderpearl import），
 *    本类只做事件接线 + 埋点：成功打含 location 与计数的 info，失败 catch Throwable 打 ERROR 原文。
 * 4. 许可证核对：本项目 MIT；只调用公开 API，无代码复制（07-CONSTRAINTS §〇 P1、L5-L8）。
 * 5. 性能基线：启动期一次性注册，冷路径，不做性能优化（17-NATIVE.md §3.2）。
 */
import dev.vkdisp.VkDisp;
import dev.vkdisp.bridge.PipelineApi;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

/**
 * P0.3 第一步：在 mod bus 的 {@link RegisterRenderPipelinesEvent} 上注册自定义全屏管线。
 *
 * <p>注册成功后，原版 ShaderManager 会在资源加载时一并编译
 * {@code assets/vkdisp/shaders/fullscreen.vsh / .fsh}（required 管线，编译失败绝不静默）。
 */
@EventBusSubscriber(modid = VkDisp.MOD_ID, value = Dist.CLIENT)
public final class FullscreenPipelineRegistrar {
    private FullscreenPipelineRegistrar() {
    }

    /** 注册点埋点（01-DEV-LOOP §5.1）：成功打注册计数 + 两条 location，失败打 ERROR 原文。 */
    @SubscribeEvent
    static void onRegisterRenderPipelines(RegisterRenderPipelinesEvent event) {
        try {
            PipelineApi.registerFullscreenPipeline(event);
            VkDisp.LOGGER.info(
                    "vkdisp: pipeline registered (1/2): {}", PipelineApi.FULLSCREEN_LOCATION);
        } catch (Throwable t) {
            VkDisp.LOGGER.error(
                    "vkdisp: pipeline registration failed: {}", PipelineApi.FULLSCREEN_LOCATION, t);
        }
        try {
            PipelineApi.registerBlitPipeline(event);
            VkDisp.LOGGER.info(
                    "vkdisp: pipeline registered (2/2): {} (total={})",
                    PipelineApi.BLIT_LOCATION,
                    PipelineApi.registeredPipelineCount());
        } catch (Throwable t) {
            VkDisp.LOGGER.error(
                    "vkdisp: pipeline registration failed: {}", PipelineApi.BLIT_LOCATION, t);
        }
    }
}
