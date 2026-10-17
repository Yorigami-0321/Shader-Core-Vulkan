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
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以 —— 只调用事件公开 API 与本方 bridge，不含被参考方代码
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
 *    全部不可见、GUI 层消失）；② FrameGraphSetupEvent 帧图插 pass —— 🔴 **旧记载「调研否决」作废**（见 07 §1.4：
 *    `fireFrameGraphSetup` 早于 `addPass("clear")` ⇒ 本就通；本类不采用只是选型，非能力否决）；
 *    上述事件都是正常选用的官方事件，不构成自行补充，无需 GAP 登记。
 * 3. 我们的差异点：本类只做事件接线与埋点，并且**在客户端资源加载完成之前完全不触碰管线缓存** ——
 *    实测（P0.3 复验）：启动窗口期原版会安装 fallback PipelineCache（GameRenderer.preloadUiShader，
 *    绑定旧 ResourceManager），此时请求我方管线会经 fallback cache 加载失败，原版按 ERROR 记
 *    「Couldn't preload shader vkdisp:shaders/fullscreen.vsh」（每帧一次，实测 24 条）。故本类以
 *    ClientResourceLoadFinishedEvent 为门闩：完成后才轮询/绘制；完成后仍不就绪则打一次 ERROR
 *    （编译可能真失败，T11 不许静默）；绘制期异常 catch Throwable 打 ERROR 原文（不吞）；
 *    总开关关闭走 WARN 降级分支。业务包零 com.mojang.renderpearl import。
 * 4. 许可证核对：本项目 LGPL-3.0-or-later；只调用公开 API，无代码复制（07-CONSTRAINTS §〇 P1、L5-L8）。
 * 5. 性能基线：每帧一次 draw，冷路径（按 task-2 要求）不做性能优化（17-NATIVE.md §3.2）。
 */
import dev.vkdisp.VkDisp;
import dev.vkdisp.VkDispConfig;
import dev.vkdisp.bridge.FrameApi;
import dev.vkdisp.bridge.MrtTerrainPass;
import dev.vkdisp.bridge.PackTextures;
import dev.vkdisp.bridge.PipelineApi;
import dev.vkdisp.bridge.PostChainGate;
import dev.vkdisp.bridge.ShadowStubs;
import dev.vkdisp.bridge.TargetReadback;
import dev.vkdisp.bridge.TerrainDrawCapture;
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

    /** 🔴 链模式「未就绪等待」的自报只打一次（首帧池未建 = 等待，不是失败）。 */
    private static boolean chainReadyWaitLogged;

    /** 上一次自报的渲染路径（变了才打一行，见 [route] 自报）。 */
    private static String lastRoute = "";

    /** 进程启动时刻，用于生成秒级动画相位（P1.1：改数值 → 画面实时变化）。 */
    private static final long START_NANOS = System.nanoTime();

    /** 相位循环周期（秒）：取模避免浮点精度退化，同时保证任意两张间隔截图都可能不同。 */
    private static final double PHASE_PERIOD_SECONDS = 4.0;

    /** 每帧计数（用于按间隔打印 uniform 取值证据）。 */
    private static int frameCounter;

    /** M-06：上次见到的捕获计数（用于节流埋点）。 */
    private static long lastCapturedCount;

    /** M-06：节流埋点最多打几次（3 次足够判定时序，不必刷屏）。 */
    private static int m06ProbeLogs;

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

        // H 线 M-06 埋点（节流）：本行的作用是**时序验证** ——
        // AfterLevel 在帧图执行之后触发，若此处已能看到捕获计数增长，
        // 说明捕获（LevelRenderer#render 第 271-275 行）确实早于 pass 体执行（第 286 行）
        // ⇒ GAP-003 方案 A 的时序前提成立。
        // 只打前 3 次（照 M-01 的教训：埋点过密会把热路径变成 I/O 瓶颈）。
        long capturedNow = TerrainDrawCapture.captureCount();
        if (capturedNow != lastCapturedCount) {
            lastCapturedCount = capturedNow;
            if (m06ProbeLogs < 3) {
                m06ProbeLogs++;
                VkDisp.LOGGER.info(
                        "vkdisp: [M-06] capture visible at AfterLevel (render thread, after frame graph executed):"
                                + " captures={} from={} nonNull={}",
                        lastCapturedCount, TerrainDrawCapture.capturedVariant(),
                        TerrainDrawCapture.hasCaptured());
            }
        }

        try {
            // 🔴 阴影桩要在**本帧任何 pass 打开之前**备好：两条后处理路都会无条件解析 shadowtex*，
            //   而 ShadowStubs#depthView 刻意不懒建（懒建 = pass 内新建 encoder ⇒ 抛）。
            //   幂等；默认档（mrt.terrain=false）下 MrtTerrainPass 不跑，这里就是唯一的建桩点。
            ShadowStubs.init();
            // 🔴 GAP-055：自定义纹理（含内置 noisetex 兜底）与阴影桩同一条纪律 —— **本帧任何 pass
            //   打开之前**上传完。此前 `PackTextures.ensureReady()` 只有三个调用点（drawPostChain /
            //   MrtTerrainPass / SkyPackGbuffer），而**默认档**（`mrt.terrain=false` 且链不跑 ⇒ 本行
            //   下面自报的「旧三步链」）三条都不走 ⇒ 链解析采样器时在 pass 体内懒建内置噪声
            //   ⇒ 每帧 `Close the existing render pass before performing additional commands`
            //   （2026-10-14 真机：代码默认档一趟 106 998 条）。幂等（按绑定表指纹）。
            PackTextures.ensureReady();
            // 🔖 取样判定在**帧首**做（地形后的取点与逐 pass 追踪都读同一个判定），
            //   否则一条曲线里会混着不同帧的数字（h48 实测过这个形状）。
            dev.vkdisp.bridge.TargetReadback.beginFrame();
            // 🔴 链模式（OF 语义的正确时序）：**先**把地形画进 colortex，**再**跑整条后处理链。
            //   旧三步链时代 composite 采的是 scene，时序反了也看不出差别；接进 colortex 之后，
            //   「链先跑」= 链永远采到**上一帧**的 gbuffer（一帧延迟），而 terrain 后画会把
            //   链刚写进 main 的结果再盖掉一次（toMain 诊断档除外）。⇒ 这里换序。
            boolean chainActive = FrameApi.isPostChainActive();
            // 🔖 走哪条路必须**在决策处自报**（只在变了时打一行）：h48 就是缺这条 ——
            //   gate 把「链」与「afterLevel」捆在一起，于是一臂以为在测链、实际在跑旧三步链，
            //   而画面与日志其它行都看起来正常。判读对象不声明自己是谁 = 假证据。
            String route = "chain=" + chainActive + " toMain=" + MrtTerrainPass.toMain();
            if (!route.equals(lastRoute)) {
                lastRoute = route;
                VkDisp.LOGGER.info("vkdisp: [route] 本帧渲染路径 = {} ⇒ {}", route,
                        chainActive ? "整条后处理链（colortex 按名接线）" : "旧三步链（scene 采样）");
                // 🔴 半开档点名（2026-10-12 真机量出来，不是推测）：`mrt.terrain=true` 而链不跑时，
                //   地形只进 colortex、**没有任何东西把它带回主目标** ⇒ 屏幕全黑。
                //   探针读数：`main` = allZero=true，同一帧 `c0@writeGen` mean_luma 59~71 / nonBlack=100%
                //   ⇒ 这是本仓探针自己定义的 **NOT_ON_MAIN** 形状，且**零异常**（不是崩，是没人写）。
                //   旧注释「地形被画两遍、主目标不受影响、屏幕画面不变」已被这组读数否证，
                //   VkDispConfig 那条注释同步改掉了。本档**保留可用**（A/B 取证要它），但不许静默。
                // 🔖 文案口径（2026-10-14，GAP-056 的教训）：旧文案写死「mrt.terrain=true 而
                //   mrt.packTerrainShader=false」，可这一行**从来没读过** packTerrainShader —— 链不跑有六个
                //   可能的因（见 PostChainGate），把其中一个当结论 ⇒ 一整臂的档被误判、一格「已排除」被误签。
                //   现在只打本行真测过的量，「为什么没跑」交给门控自己按判定顺序自报。
                if (MrtTerrainPass.enabled() && !chainActive && !MrtTerrainPass.toMain()) {
                    VkDisp.LOGGER.warn("vkdisp: [GAP-036/半开档] 实测 mrt.terrain={} mrt.terrainToMain={} 而链没跑"
                                    + "（门控按判定顺序：{}）"
                                    + " ⇒ 地形只写进 colortex，没有东西把它带回主目标，**本档画面预期为全黑**"
                                    + "（实测 main allZero + c0 有内容 = NOT_ON_MAIN）。"
                                    + "要看画面必须 terrain 与链一起开；这一档只用来单独测「地形进不进多附件 pass」。"
                                    + "单个键的生效值读 [生效档] 那一块 —— 本行只为上面这几个量作证",
                            MrtTerrainPass.enabled(), MrtTerrainPass.toMain(), PostChainGate.readout());
                }
            }
            // 🔴 h48 修正（这条 gate 曾经把两个**独立**的轴捆在一起）：条件里带 afterLevel()
            //   ⇒ 关诊断档 `mrt.terrainAfterLevel=false` 会**连带把整条链关掉**，于是那一臂跑的
            //   是旧三步链、画面却是「链生效」的样子 —— 我据此下过一次错结论（evidence/h48 §五
            //   已按此更正）。那条 A/B 车道已随 R5 退役，留下的纪律是：<b>链要不要跑只取决于链本身</b>。
            // 🔖🔖 地形与天空都**不再在这里画**：天空在原版 "sky" pass 体内（R4 的官方钩子 + M-11），
            //   地形在我方帧图 pass 体内（M-06 捕获 + M-13 的 requires 边），而 {@code frame.execute}
            //   （LevelRenderer:286）<b>早于</b>本事件（AfterLevel 由 GameRenderer 在 render 返回后 post）
            //   ⇒ 到这一刻 colortex 已经写好，链直接消费它，不需要任何「先补画一次」的调用。
            if (chainActive) {
                // 🔴 1.1（GAP-053 ⑤-d）：单绘档下这一帧的翻代欠在主 pass 那一笔上 ⇒
                //   必须在**链开跑之前**交出去，否则链读到的还是上一帧那一代（= 画面没有地形）。
                //   挂在调用方而不是 FrameApi#drawPostChain 里：FrameApi 正顶着 ClassLine 棘轮上限。
                dev.vkdisp.bridge.TerrainSingleDraw.flipIfOwed();
                if (FrameApi.isPostChainReady()) {
                    FrameApi.drawPostChain(PASS_LABEL);
                } else if (!chainReadyWaitLogged) {
                    chainReadyWaitLogged = true;
                    // 未就绪 = **等待**（首帧池还没建），不是失败；只自报一次避免误导（T11）。
                    VkDisp.LOGGER.info("vkdisp: [chain] post chain active but not ready yet"
                            + " (pool/compile warming up) —— 本帧跳过链执行，旧三步链**不**补位（避免混跑）");
                }
            } else {
                FrameApi.FrameSize size = FrameApi.drawFullscreen(PASS_LABEL, params);
                frameCounter++;
                if (!firstFrameLogged) {
                    firstFrameLogged = true;
                    VkDisp.LOGGER.info(
                            "vkdisp shadow sample chain executed ({}x{}), lightMatrixPhase={} (1: 几何光空间 -> 阴影贴图, 2: 世界视图+阴影采样 -> offscreen1, 3: offscreen1 -> main)",
                            size.width(), size.height(), phase);
                } else if (paramLogs < 5 && frameCounter % 120 == 0
                        && dev.vkdisp.VkDispConfig.DEBUG_LOG.get()) {
                    // 🔖 QD-02：`vkdisp.debugLog` 的**真实消费点之三**。
                    //   原为无条件输出 ⇒ 该开关对它无效。关掉开关时这行消失，**可观察**。
                    paramLogs++;
                    VkDisp.LOGGER.info(
                            "vkdisp: uniform {} phase={} at frame {}", PipelineApi.PARAMS_UNIFORM, phase, frameCounter);
                }
            }
        } catch (Throwable t) {
            // 失败必须打 ERROR 原文（07 X11：禁止吞异常让它看起来能跑）。
            VkDisp.LOGGER.error("vkdisp: fullscreen pass failed", t);
        }

        // 🔖 R5 退役了「地形挪到帧图执行之后画」那条 A/B 车道，但它买到的那条事实留着：
        //   本 mod 的 SceneCaptureApi 会把场景纹理 blit 进 main ⇒ **任何**想在 main 上留下的东西
        //   都必须排在那次 blit 之后，否则症状是「pass 明明跑了、屏幕毫无变化」（误读成「没执行」），
        //   而画两遍则是双重暴露。现在地形只在帧图 pass 里画一次 ⇒ 本钩子里没有任何地形绘制调用。

        // 🔖🔖 像素回读探针（默认关）。**位置有三重约束，本轮全部是实测踩出来的**：
        //  ① 必须在**所有** render pass 关闭之后（原版 FrontendCommandEncoder 在 pass 内
        //     做 copyTextureToBuffer 会抛 "Close the existing render pass before
        //     performing additional commands"）；
        //  ② 必须在**地形 MRT pass 之后** —— 帧图档里那个 pass 在 {@code frame.execute}
        //     （LevelRenderer:286）内就跑完了，本事件在它之后 ⇒ {@code mrt.terrainToMain=true}
        //     （GAP-008 取证那一档）读到的主目标是**写入之后**的内容。那条约束现在由事件时序
        //     天然给出，但**仍不许把探针往前挪**（往前 = 读到的还是写入前的）；
        //  ③ 必须在**诊断视图 blit 之前** —— 那个 blit 会把主目标覆盖成某个 colortex 的
        //     内容，在它之后回读会让「主目标」与「colortex」两个数字指向同一张图，
        //     两源对照静默失效。
        //  ⇒ 「链尾」之后、「地形 pass」之后、「诊断 blit」之前 = 只能是本方法末尾。
        TargetReadback.probeFrameTail();
    }

}
