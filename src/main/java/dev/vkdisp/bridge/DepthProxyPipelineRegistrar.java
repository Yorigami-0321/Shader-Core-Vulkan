package dev.vkdisp.bridge;
/**
 * 【参考调研】GAP-022 ①：深度代理管线的注册接线 / NeoForge 官方注册事件
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = NeoForge 26.3 的 RegisterRenderPipelinesEvent（官方事件，LGPL-2.1：
 *    只观察事件签名与订阅写法，不复制其实现）+ 本仓库 {@code render/FullscreenPipelineRegistrar}
 *    与 {@code bridge/MrtTerrainPass} 的 @EventBusSubscriber 用法（bridge 里做订阅**已有先例**，
 *    MrtTerrainPass 就是 bridge 包内的 @EventBusSubscriber）。
 *    → 能否并入本项目（MIT）：可以（独立编写的接线类，零被参考方代码）
 *    → 例外条款：无
 * 1. 官方/主实现：一个 mod 的 mod bus（{@code IModBusEvent} 走的那条）上可以挂**多个**
 *    {@code @EventBusSubscriber} 类，事件 post 时逐个 listener 调用；
 *    {@code RenderPipelines#registerCustomPipelines}（源码 :1310-1316）把注册收进一个
 *    {@code putIfAbsent} 的表 ⇒ 只要 location 不重复，注册来自哪个 handler 都无所谓。
 * 2. 备选：把这一行并进 {@code render/FullscreenPipelineRegistrar#onRegisterRenderPipelines}
 *    —— 那是本项目的常规接线位置，也**本该**是它；本轮不采纳的唯一原因是文件所有权
 *    （该文件正被另一条线占着，同文件并行改 = 互相覆盖）。
 *    ⇒ 后续收编：把下面这一个方法体原样挪进那个 registrar（它已经 catch Throwable + 打
 *      registeredPipelineCount 的那一行埋点），然后删掉本类。除此没有别的差异。
 * 3. 我们的差异点：注册本体仍在 {@link PipelineApi}（07 T5：业务包零 com.mojang.renderpearl import），
 *    本类只做事件接线 + 失败原文（catch Throwable 后把 t 打进 ERROR，不吞，X11）。
 * 4. 许可证核对：本项目 MIT；只调用公开 API，无代码复制。
 * 5. 性能基线：启动期一次性注册，冷路径，不做性能优化（17-NATIVE.md §3.2）。
 */
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import dev.vkdisp.VkDisp;

/**
 * GAP-022 ①：在 mod bus 的 {@link RegisterRenderPipelinesEvent} 上注册深度代理管线。
 *
 * <p>管线是 {@code required}（{@code event.registerPipeline}）—— 片元
 * {@code vkdisp:depth_gl_flip} 编不过就让整次资源重载失败，不静默（T11）。
 */
@EventBusSubscriber(modid = VkDisp.MOD_ID, value = Dist.CLIENT)
public final class DepthProxyPipelineRegistrar {

    private DepthProxyPipelineRegistrar() {
    }

    /** 注册点埋点（01-DEV-LOOP §5.1）：成功打 location，失败打 ERROR 原文。 */
    @SubscribeEvent
    static void onRegisterRenderPipelines(RegisterRenderPipelinesEvent event) {
        try {
            PipelineApi.registerDepthProxyPipeline(event);
            VkDisp.LOGGER.info("vkdisp: pipeline registered [GAP-022]: {} (total={})",
                    PipelineApi.DEPTH_PROXY_LOCATION, PipelineApi.registeredPipelineCount());
        } catch (Throwable t) {
            VkDisp.LOGGER.error("vkdisp: pipeline registration failed: {}",
                    PipelineApi.DEPTH_PROXY_LOCATION, t);
        }
    }
}
