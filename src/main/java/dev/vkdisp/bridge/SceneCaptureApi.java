package dev.vkdisp.bridge;
/**
 * 【参考调研】P3.2 地形接管 —— FrameGraphSetupEvent 换目标（08-TESTING §5 验收对象）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① NeoForge 26.3.0.23-beta 官方事件 `FrameGraphSetupEvent` 与
 *    `ClientHooks.fireFrameGraphSetup`（LGPL-2.1：只观察事件签名与触发时机，不复制其实现 ——
 *    与 FullscreenPassHook 头注同一口径）；② 本仓库 docs/18-PARALLEL.md §5 P3.2 设计块
 *    ①–⑤ 与 docs/08-TESTING.md §5「地形/实体走自定义目标而非原版目标 | 调试视图」；
 *    ③ 合并 jar 内 vanilla `LevelRenderer` / `LevelTargetBundle` / `MainTarget` 源（Mojang 专有，
 *    仅 javap/源码**读行为事实**：触发时机、字段语义、硬编码清屏位置 —— 零代码复制）。
 *    → 能否并入本项目（MIT）：可以 —— 只调用事件与原版公开 API，本文件独立编写
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：NeoForge 官方 `FrameGraphSetupEvent`（帧图装配期唯一官方扩展点，源
 *    LevelRenderer.java:249 实测触发于 bundle 初始化后、clear/sky/main pass 加入之前）。
 * 2. 备选：① Post 帧把原版主目标 blit 进我方纹理 —— 否决（验收要求「而非原版目标」，
 *    拷贝仍意味着地形先落进原版目标）；② mixin 进 LevelRenderer 改 createRenderPass 参数
 *    —— 否决（官方事件可用时不 mixin；T10 注入点日志成本更高）；③ 维持 Post 覆盖不接管
 *    —— 否决（不满足 P3.2 验收）。
 * 3. 我们的差异点：
 *    ① **自清屏**：vanilla clear pass 硬编码清 `gameRenderer.mainRenderTarget()`（源 256 行），
 *       换目标后不会清我方纹理 → 事件内直接清 color(black)+depth(0.0，反向 Z GEQUAL
 *       语义 = vanilla 同值；清 1.0 会让地形全败 = 首轮实测全黑根因)，帧图执行前完成；
 *    ② **格式逐位对齐**：`TextureTarget(RGBA8_UNORM, D32_FLOAT)` = 原版 `MainTarget` 构造
 *       参数（javap 核实），地形颜色/深度语义不因格式漂移；
 *    ③ **失败回退显式**：事件处理任意 Throwable → ERROR 原文 + 本帧不换目标（原版正常渲染，
 *       T11 不吞）；未启用（config off）或菜单 → 不换目标，帧链回退 fixture（来源埋点见 FrameApi）。
 * 4. 许可证核对结论：本项目 MIT；NeoForge 事件 LGPL-2.1 仅 API 观察（先例 FullscreenPassHook）；
 *    Mojang 源仅行为核实，零代码并入（07-CONSTRAINTS X19-X21 / L5-L8）。
 * 5. 性能基线：每帧 1 次尺寸比对 + 必需清屏（清屏是接管语义的一部分，非优化对象）；
 *    无测量不做任何性能断言（18-PARALLEL §7.7、X14）。
 */

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.vkdisp.VkDisp;
import dev.vkdisp.VkDispConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.FrameGraphSetupEvent;
import org.joml.Vector4f;

/**
 * P3.2：场景捕获 —— 在帧图装配期把 {@code targets.main} 换成我方纹理，
 * 让原版地形（及一切走 bundle 的 pass）的绘制**直接落进 vkdisp 拥有的目标**。
 *
 * <p>调用时序（X9，合并 jar 源核实）：LevelRenderer:210 导入原版主目标 → :249 发本事件 →
 * :252 起才依次加入 clear / sky / main 等 pass，且这些 pass 在**执行期**才读
 * {@code targets.main.get()} → 事件里换 handle 即接管，无需 mixin。
 *
 * <p>必须在 bridge/：事件携带 {@code com.mojang.blaze3d} 类型，业务包禁触（T5 / X10）。
 */
@EventBusSubscriber(modid = VkDisp.MOD_ID, value = Dist.CLIENT)
public final class SceneCaptureApi {

    /** 捕获纹理尺寸/格式语义（RGBA8_UNORM + D32_FLOAT = 原版 MainTarget，javap 核实）。 */
    private static final String SCENE_LABEL = "vkdisp scene";

    /** 捕获目标（懒建；resize 跟随主目标；无深度则地形深度测试必坏 —— 永远带 D32）。 */
    private static TextureTarget sceneTarget;

    /** 是否至少成功捕获过一帧装配（{@link #hasScene()} 的门闩）。 */
    private static boolean sceneCaptured;

    private SceneCaptureApi() {
    }

    /**
     * 帧图装配钩子：建/校验捕获目标 → 导入为帧图外部资源 → 替换 bundle.main → 自清屏。
     *
     * <p>失败路径：任意 Throwable → ERROR 原文且**不换目标**（本帧原版正常渲染，T11）。
     * 已换目标后清屏又失败的最坏情况由下帧重试与 ERROR 日志暴露（不吞）。
     */
    @SubscribeEvent
    static void onFrameGraphSetup(FrameGraphSetupEvent event) {
        if (!VkDispConfig.ENABLED.get()) {
            // 总开关关闭 = 模组不介入任何渲染（config 注释承诺）：不换目标，原版主目标照旧。
            return;
        }
        try {
            RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            int width = main.width;
            int height = main.height;
            if (sceneTarget == null) {
                sceneTarget = new TextureTarget(SCENE_LABEL, width, height,
                        GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
            } else if (sceneTarget.width != width || sceneTarget.height != height) {
                sceneTarget.resize(width, height);
                VkDisp.LOGGER.info("vkdisp: scene capture resized: {}x{}", width, height);
            }

            ResourceHandle<RenderTarget> handle = event.getFrameGrapBuilder()
                    .importExternal("vkdisp_scene", sceneTarget);
            event.getTargetBundle().replace(LevelTargetBundle.MAIN_TARGET_ID, handle);

            // 自清屏（设计块 ③）：vanilla clear 只清原版主目标；深度不清 = 地形读陈旧深度必坏。
            // 深度清 0.0（= vanilla clear pass 同值，源 260 行）：renderpearl 是**反向 Z**
            // （DepthStencilState.DEFAULT = GREATER_THAN_OR_EQUAL，javap 核实）——
            // 清 1.0 会让 GEQUAL 全败、地形零像素（首轮实测全黑的根因）。
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                    sceneTarget.getColorTexture(),
                    new Vector4f(0.0F, 0.0F, 0.0F, 0.0F),
                    sceneTarget.getDepthTexture(),
                    0.0D);

            if (!sceneCaptured) {
                sceneCaptured = true;
                VkDisp.LOGGER.info(
                        "vkdisp: scene capture wired: {}x{} color=RGBA8_UNORM depth=D32_FLOAT (targets.main -> vkdisp_scene)",
                        width, height);
            }
        } catch (Throwable t) {
            VkDisp.LOGGER.error("vkdisp: scene capture setup failed (targets.main unchanged this frame)", t);
        }
    }

    /** 是否已有场景捕获（世界内帧图至少装配过一次且未抛错）。 */
    static boolean hasScene() {
        return sceneCaptured && sceneTarget != null;
    }

    /** 捕获纹理颜色视图（帧链 Pass 3 的输入源；未捕获返回 null）。 */
    static GpuTextureView sceneColorView() {
        return sceneTarget != null ? sceneTarget.getColorTextureView() : null;
    }
}
