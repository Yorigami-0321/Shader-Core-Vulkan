package dev.vkdisp.render;
/**
 * 【参考调研】P0.3 每帧绘制接线 / NeoForge 官方帧事件 + 资源加载完成事件
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = NeoForge 26.3.0.23-beta RenderLevelStageEvent.AfterLevel /
 *    ClientResourceLoadFinishedEvent（均为官方事件）及其触发点（合并 jar javap 核实：
 *    GameRenderer#renderLevel 在 LevelRenderer.render 返回后 post AfterLevel，
 *    ClientHooks#fireResourceLoadFinishedEvent 发资源事件）。
 *    许可证：NeoForge 事件定义 LGPL-2.1（只观察事件签名与触发时机，不复制其实现）；
 *    本文件为独立编写的接线类。
 *    → 能否并入本项目（MIT）：可以 —— 只调用事件公开 API 与本方 bridge，不含被参考方代码
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：RenderLevelStageEvent.AfterLevel —— 每帧一次（世界内），触发点在
 *    GameRenderer#renderLevel 内、LevelRenderer.render() 返回**之后**（帧图已执行 →
 *    SceneCaptureApi 已捕获本帧地形）、render3dHud 与 GameRenderer.render() 的
 *    guiRenderer.render()（GUI 合成）**之前**；此刻对 main target 的写入先于 GUI 落屏，
 *    GUI 随后合成在其上（X9：合并 jar 字节码顺序实测，2026-10-01 P4.3）。
 *    ClientResourceLoadFinishedEvent —— 「客户端资源加载/重载成功之后」触发（javadoc 原文），
 *    首启时在资源加载之后、初始界面建立之前；GLSL 编译属于资源重载的一部分，故此刻管线缓存已就绪。
 * 2. 备选：① RenderFrameEvent.Post —— **实测否决（P4.3 根因）**：触发点在 GameRenderer.render()
 *    返回之后，GUI 已经画进 main target，我方 final blit（offscreen3 → main）会整屏覆盖 GUI ——
 *    实证：p03_mainmenu_pattern.png（图案盖住整个主菜单）与 P4.3 世界内截图（选项屏幕
 *    全部不可见、GUI 层消失）；② FrameGraphSetupEvent 帧图插 pass —— 调研否决
 *    （vanilla clear pass 会随后全清 main target，图案必被抹掉）；
 *    上述事件都是正常选用的官方事件，不构成自行补充，无需 GAP 登记。
 * 3. 我们的差异点：本类只做事件接线与埋点，并且**在客户端资源加载完成之前完全不触碰管线缓存** ——
 *    实测（P0.3 复验）：启动窗口期原版会安装 fallback PipelineCache（GameRenderer.preloadUiShader，
 *    绑定旧 ResourceManager），此时请求我方管线会经 fallback cache 加载失败，原版按 ERROR 记
 *    「Couldn't preload shader vkdisp:shaders/fullscreen.vsh」（每帧一次，实测 24 条）。故本类以
 *    ClientResourceLoadFinishedEvent 为门闩：完成后才轮询/绘制；完成后仍不就绪则打一次 ERROR
 *    （编译可能真失败，T11 不许静默）；绘制期异常 catch Throwable 打 ERROR 原文（不吞）；
 *    总开关关闭走 WARN 降级分支。业务包零 com.mojang.renderpearl import。
 * 4. 许可证核对：本项目 MIT；只调用公开 API，无代码复制（07-CONSTRAINTS §〇 P1、L5-L8）。
 * 5. 性能基线：每帧一次 draw，冷路径（按 task-2 要求）不做性能优化（17-NATIVE.md §3.2）。
 */
import dev.vkdisp.VkDisp;
import dev.vkdisp.VkDispConfig;
import dev.vkdisp.bridge.FrameApi;
import dev.vkdisp.bridge.PipelineApi;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientResourceLoadFinishedEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * P0.3 第二步：每帧在主渲染目标上画全屏图案（首个可见产物）。
 *
 * <p>选 {@link RenderLevelStageEvent.AfterLevel} 而不是 RenderFrameEvent.Post：
 * Post 在 GameRenderer.render() 返回**之后**触发，此时 GUI 已画进 main target，
 * 我方 final blit 会整屏覆盖 GUI（P4.3 实测根因，见类注释第 2 条备选①）；
 * AfterLevel 在帧图执行完（场景已捕获）、GUI 合成之前触发，我方写入先落、GUI 后合成在其上。
 *
 * <p>选 {@link ClientResourceLoadFinishedEvent} 作为门闩而不是每帧轮询：轮询会在原版
 * fallback PipelineCache 生效期间触发失败加载（详见类注释第 3 条实测）。
 */
@EventBusSubscriber(modid = VkDisp.MOD_ID, value = Dist.CLIENT)
public final class FullscreenPassHook {
    /** render pass 调试标签（Vulkan 调试层 / renderdoc 中可见）。 */
    private static final String PASS_LABEL = "vkdisp fullscreen";

    /** 客户端资源（含 GLSL 编译）是否已完成加载；完成前不触碰管线缓存。 */
    private static volatile boolean clientResourcesLoaded;

    /** 首帧成功埋点只打一次（限频，避免每帧刷屏）。 */
    private static boolean firstFrameLogged;

    /** 总开关关闭的降级分支只警告一次（01-DEV-LOOP §5.1 降级点）。 */
    private static boolean disabledWarned;

    /** 资源已加载完成但管线仍不就绪的 ERROR 只打一次（可能是真编译失败）。 */
    private static boolean notReadyLogged;

    /** P1.2 计数对齐断言只打一次。 */
    private static boolean countChecked;

    /** 进程启动时刻，用于生成秒级动画相位（P1.1：改数值 → 画面实时变化）。 */
    private static final long START_NANOS = System.nanoTime();

    /** 相位循环周期（秒）：取模避免浮点精度退化，同时保证任意两张间隔截图都可能不同。 */
    private static final double PHASE_PERIOD_SECONDS = 4.0;

    /** 每帧计数（用于按间隔打印 uniform 取值证据）。 */
    private static int frameCounter;

    /** uniform 取值证据最多打印 5 次（限频，避免刷屏）。 */
    private static int paramLogs;

    private FullscreenPassHook() {
    }

    /**
     * 资源加载/重载完成门闩：GLSL 编译属于资源重载的一部分，此刻管线缓存已就绪，
     * 之后才允许请求我方管线（避免启动窗口期经 fallback cache 触发失败加载）。
     */
    @SubscribeEvent
    static void onClientResourceLoadFinished(ClientResourceLoadFinishedEvent event) {
        clientResourcesLoaded = true;
        VkDisp.LOGGER.info(
                "vkdisp: client resources loaded (initial={}), fullscreen pass enabled", event.isInitial());
    }

    /**
     * 绘制点埋点：首帧 info（含 WxH），失败 ERROR 原文，禁用分支 WARN 一次。
     *
     * <p>触发位形（X9，合并 jar 字节码）：GameRenderer#renderLevel 在 LevelRenderer.render()
     * 返回后 post 本事件 —— 此刻帧图已执行（SceneCaptureApi 已捕获本帧地形）、
     * render3dHud / guiRenderer.render()（GUI 合成）尚未开始，故我方对 main 的写入
     * 先落屏、GUI 与手部渲染随后合成在其上（不再覆盖 GUI，见类注释第 2 条备选①）。
     */
    @SubscribeEvent
    static void onAfterLevel(RenderLevelStageEvent.AfterLevel event) {
        if (!VkDispConfig.ENABLED.get()) {
            if (!disabledWarned) {
                disabledWarned = true;
                VkDisp.LOGGER.warn(
                        "vkdisp: fullscreen pass skipped (fallback branch: config vkdisp.enabled=false)");
            }
            return;
        }

        // 资源加载完成前：管线尚未编译（注册早于重载），且此刻请求会走原版 fallback cache 而失败。
        // 这属于「等待」而非失败，直接跳过本帧（不打日志，避免刷屏）。
        if (!clientResourcesLoaded) {
            return;
        }

        if (!FrameApi.isPipelineReady()) {
            if (!notReadyLogged) {
                notReadyLogged = true;
                VkDisp.LOGGER.error(
                        "vkdisp: pipelines not compiled after client resources loaded: {} + {} + {}",
                        PipelineApi.FULLSCREEN_LOCATION,
                        PipelineApi.COMPOSITE_LOCATION,
                        PipelineApi.BLIT_LOCATION);
            }
            return;
        }

        // P1.2：注册数 == 编译成功数（08-TESTING.md §3），不等即 ERROR，不静默少。
        if (!countChecked) {
            countChecked = true;
            int registered = PipelineApi.registeredPipelineCount();
            int compiled = FrameApi.compiledPipelineCount();
            if (registered == compiled) {
                VkDisp.LOGGER.info(
                        "vkdisp: pipeline count check: registered={}, compiled={} (aligned)", registered, compiled);
            } else {
                VkDisp.LOGGER.error(
                        "vkdisp: pipeline count mismatch: registered={}, compiled={}", registered, compiled);
            }
        }

        // P1.1：每帧推进相位 → uniform 数值变化 → 画面实时变化（不是只在启动时生效）。
        double seconds = (System.nanoTime() - START_NANOS) / 1_000_000_000.0;
        float phase = (float) (seconds % PHASE_PERIOD_SECONDS);
        FrameApi.FrameParams params = new FrameApi.FrameParams(phase, 1.0F);

        try {
            FrameApi.FrameSize size = FrameApi.drawFullscreen(PASS_LABEL, params);
            frameCounter++;
            if (!firstFrameLogged) {
                firstFrameLogged = true;
                VkDisp.LOGGER.info(
                        "vkdisp shadow sample chain executed ({}x{}), lightMatrixPhase={} (1: 几何光空间 -> 阴影贴图, 2: 世界视图+阴影采样 -> offscreen1, 3: offscreen1 -> main)",
                        size.width(), size.height(), phase);
            } else if (paramLogs < 5 && frameCounter % 120 == 0) {
                paramLogs++;
                VkDisp.LOGGER.info(
                        "vkdisp: uniform {} phase={} at frame {}", PipelineApi.PARAMS_UNIFORM, phase, frameCounter);
            }
        } catch (Throwable t) {
            // 失败必须打 ERROR 原文（07 X11：禁止吞异常让它看起来能跑）。
            VkDisp.LOGGER.error("vkdisp: fullscreen pass failed", t);
        }
    }
}
