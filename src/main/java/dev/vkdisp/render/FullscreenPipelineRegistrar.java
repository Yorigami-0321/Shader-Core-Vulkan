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
import dev.vkdisp.bridge.TerrainPipelineApi;
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
                    "vkdisp: pipeline registered (1/9): {}", PipelineApi.FULLSCREEN_LOCATION);
        } catch (Throwable t) {
            VkDisp.LOGGER.error(
                    "vkdisp: pipeline registration failed: {}", PipelineApi.FULLSCREEN_LOCATION, t);
        }
        try {
            PipelineApi.registerCompositePipeline(event);
            VkDisp.LOGGER.info(
                    "vkdisp: pipeline registered (2/9): {}", PipelineApi.COMPOSITE_LOCATION);
        } catch (Throwable t) {
            VkDisp.LOGGER.error(
                    "vkdisp: pipeline registration failed: {}", PipelineApi.COMPOSITE_LOCATION, t);
        }
        try {
            // P3.2：scene 输入专用（无 v 翻转顶点），失败独立可见 —— 不与 composite 混一个 catch。
            PipelineApi.registerCompositeScenePipeline(event);
            VkDisp.LOGGER.info(
                    "vkdisp: pipeline registered (3/9): {}", PipelineApi.COMPOSITE_SCENE_LOCATION);
        } catch (Throwable t) {
            VkDisp.LOGGER.error(
                    "vkdisp: pipeline registration failed: {}", PipelineApi.COMPOSITE_SCENE_LOCATION, t);
        }
        try {
            // P3.3：deferred 步专用（flipv 顶点 + vkdisp_pack:deferred 片元），失败独立可见。
            PipelineApi.registerDeferredPipeline(event);
            VkDisp.LOGGER.info(
                    "vkdisp: pipeline registered (4/9): {}", PipelineApi.DEFERRED_LOCATION);
        } catch (Throwable t) {
            VkDisp.LOGGER.error(
                    "vkdisp: pipeline registration failed: {}", PipelineApi.DEFERRED_LOCATION, t);
        }
        try {
            // P4.1.4：final 步专用（不翻转顶点 + vkdisp_pack:final 片元），失败独立可见。
            PipelineApi.registerFinalPipeline(event);
            VkDisp.LOGGER.info(
                    "vkdisp: pipeline registered (5/9): {}", PipelineApi.FINAL_LOCATION);
        } catch (Throwable t) {
            VkDisp.LOGGER.error(
                    "vkdisp: pipeline registration failed: {}", PipelineApi.FINAL_LOCATION, t);
        }
        try {
            PipelineApi.registerDepthVisPipeline(event);
            VkDisp.LOGGER.info(
                    "vkdisp: pipeline registered (6/9): {}", PipelineApi.DEPTHVIS_LOCATION);
        } catch (Throwable t) {
            VkDisp.LOGGER.error(
                    "vkdisp: pipeline registration failed: {}", PipelineApi.DEPTHVIS_LOCATION, t);
        }
        try {
            PipelineApi.registerGeometryPipeline(event);
            VkDisp.LOGGER.info(
                    "vkdisp: pipeline registered (7/9): {} (vertexStride={})",
                    PipelineApi.GEOMETRY_LOCATION,
                    PipelineApi.geometryVertexStride());
        } catch (Throwable t) {
            VkDisp.LOGGER.error(
                    "vkdisp: pipeline registration failed: {}", PipelineApi.GEOMETRY_LOCATION, t);
        }
        try {
            PipelineApi.registerShadowedPipeline(event);
            VkDisp.LOGGER.info(
                    "vkdisp: pipeline registered (8/9): {}", PipelineApi.SHADOWED_LOCATION);
        } catch (Throwable t) {
            VkDisp.LOGGER.error(
                    "vkdisp: pipeline registration failed: {}", PipelineApi.SHADOWED_LOCATION, t);
        }
        try {
            PipelineApi.registerBlitPipeline(event);
            VkDisp.LOGGER.info(
                    "vkdisp: pipeline registered (9/9): {} (total={})",
                    PipelineApi.BLIT_LOCATION,
                    PipelineApi.registeredPipelineCount());
        } catch (Throwable t) {
            VkDisp.LOGGER.error(
                    "vkdisp: pipeline registration failed: {}", PipelineApi.BLIT_LOCATION, t);
        }

        // H 线 M-01（10–15）：6 条派生地形管线（GAP-003 通道 + GAP-004 自定义块）。
        // 🔴 逐条 try/catch 在 TerrainPipelineApi 内部 —— 一条坏不能把另外 5 条一起吞掉（T11）。
        // 计数口径随之从 9 变 15：registered==compiled 是**相对**断言且两边同源 ⇒ 仍然成立，
        // 且现在**把 6 条派生管线也纳入编译成功数的核对**（编译不过会立刻暴露）。
        TerrainPipelineApi.registerTerrainDerivedPipelines(event);
        VkDisp.LOGGER.info(
                "vkdisp: M-01 terrain derived pipelines registered: {}/6 (total registered={})",
                TerrainPipelineApi.terrainDerivedPipelineCount(),
                PipelineApi.registeredPipelineCount());

        // GAP-003 多附件能力验证件（16/17）：本项目**第一条多附件管线**。
        // ⚠️ 这两条管线**无条件注册**（required）—— 它们是纯能力验证，不参与常规帧绘制
        //（绘制由 MrtProbe 按配置开关驱动，默认关闭 ⇒ 常规帧零开销）。
        // 无条件注册是为了让 registered==compiled 计数断言把它们也纳入核对：
        // 多附件管线编译不过本身就是必须暴露的事实（T11）。
        try {
            PipelineApi.registerMrtPipeline(event);
            VkDisp.LOGGER.info("vkdisp: pipeline registered (16/25): {} [GAP-003 mrt]",
                    PipelineApi.MRT_LOCATION);
        } catch (Throwable t) {
            VkDisp.LOGGER.error("vkdisp: [GAP-003] mrt pipeline registration failed", t);
        }
        // H 线 GAP-003 方案 A（18/25）：6 条**多附件**地形派生管线。
        // 与 M-01 的 6 条单附件版同键不同表，由 MrtTerrainPass.active() 决定取哪条。
        TerrainPipelineApi.registerTerrainDerivedMrtPipelines(event);

        try {
            PipelineApi.registerMrtViewPipeline(event);
            // 🔖 不翻转版回读（2026-10-03）：采样**引擎渲染出的**离屏目标时用它。
            //    翻转版补偿的是包 composite 的 OF vUv 语义，用在这里会把画面上下颠倒。
            PipelineApi.registerMrtViewNoFlipPipeline(event);
            VkDisp.LOGGER.info("vkdisp: pipeline registered (23/25): {} (total={})",
                    PipelineApi.MRT_VIEW_LOCATION, PipelineApi.registeredPipelineCount());
        } catch (Throwable t) {
            VkDisp.LOGGER.error("vkdisp: pipeline registration failed: {}",
                    PipelineApi.MRT_VIEW_LOCATION, t);
        }
    }
}
