package dev.vkdisp.bridge;
/**
 * 【参考调研】GAP-003 方案 A 第 2 步：我方自己的多附件地形 pass / 原版 `FrameGraphBuilder` + `ChunkSectionsToRender#renderGroup` + NeoForge `FrameGraphSetupEvent`
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 原版 26.3 {@code com.mojang.blaze3d.framegraph.FrameGraphBuilder#addPass} /
 *    {@code FramePass#disableCulling} / {@code FramePass#executes}
 *    （随 MDG 分发的 sources jar 第 24-27、320-331 行）；
 *    ② 原版 {@code ChunkSectionsToRender#renderGroup(ChunkSectionLayerGroup, RenderPass, GpuSampler, GpuTextureView, boolean)}
 *    （同 jar 第 45 行，**public**）；
 *    ③ NeoForge 26.3 {@code FrameGraphSetupEvent}（LGPL-2.1：只观察事件签名与触发时机）；
 *    ④ 原版 {@code RenderSystem.bindDefaultUniforms} / {@code GpuDevice#createSampler} /
 *    {@code TextureManager#getTexture} / {@code TextureAtlas.LOCATION_BLOCKS}（均 public，签名逐个核实）。
 *    许可证：Mojang EULA + NeoForge LGPL-2.1。**只调用公开 API，零源码搬运、零着色器文本搬运。**
 *    → 能否并入本项目（MIT）：可以 —— 本文件为独立编写的桥接封装
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：NeoForge 官方 {@code FrameGraphSetupEvent} + 原版
 *    {@code FrameGraphBuilder#addPass}（帧图装配期插 pass 的官方路径，{@code SceneCaptureApi} 已在用）。
 * 2. 备选：① 在 {@code addMainPass}（M-04 = 方案 B）里做 ⇒ 要改原版 pass 的附件语义，
 *    且四类 draw 全部受牵连；**否决**（风险高一档，见 {@code 04-SPEC.md} §5.0.4 取舍表）。
 *    ② 复用原版的 {@code chunkLayerSampler} ⇒ **不可行**：它是 {@code LevelRenderer} 的
 *    private 字段（第 146 行），只能自建同款（{@code GpuDevice#createSampler} 是 public）。
 * 3. 我们的差异点（**本类最要紧的设计**）：
 *    <ul>
 *      <li>🔖 <b>写自己的 colortex，不碰主目标</b> ⇒ 原版主 pass 照常把地形画进 main，
 *          两者**互不干扰**。代价是地形被画两遍（诊断开关默认关，且这是本轮的取舍）；
 *          好处是「我方 MRT 地形是否正确」可以**独立**验证，不必等主链改造完成。
 *          ⚠️ 这也意味着**本轮不产出任何画面改进**，只验证「地形能进多附件 pass」。</li>
 *      <li>🔖 <b>只画 OPAQUE 组</b>（{@code renderGroup(OPAQUE, …)}）⇒ 固体方块 + cutout。
 *          特性 / 云 / 半透明地形仍在原版 pass 里 ⇒ 牵连面最小。</li>
 *      <li>🔖 <b>管线切换靠活动标记</b>：M-01 的 {@code layer.pipeline()} 注入点在被调用时
 *          才知道自己在哪个 pass 里 ⇒ 用 {@link #active()} 让它在 MRT pass 内返回
 *          <b>多附件变体</b>，pass 外返回单附件变体。</li>
 *    </ul>
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 可关闭的诊断路径（默认关 ⇒ 常规帧零开销）。**开启时的双绘代价未测。**
 */
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.AddressMode;
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
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.FrameGraphSetupEvent;

/**
 * GAP-003 方案 A 第 2 步：在我方自己的帧图 pass 里，把地形画进**多附件**（MRT）render pass。
 *
 * <p><b>它证明什么</b>：地形 draw 数据（M-05 捕获的 {@code ChunkSectionsToRender}）
 * 能在**我方控制的**多附件 pass 里完成渲染 —— 这是 GAP-003 从「原语可用」走向
 * 「地形真的走多附件」的关键一步。
 *
 * <p><b>它不做什么</b>（不要拿它当 GAP-003 已完成）：
 * <ul>
 *   <li>⛔ <b>不产出画面改进</b> —— 写的是自己的 colortex，主目标由原版照常绘制
 *       ⇒ 地形被画两遍（一遍进我方 MRT、一遍进原版 main）。</li>
 *   <li>⛔ <b>不接包的自研 {@code gbuffers_*} 片元</b> —— 用的是原版 {@code core/terrain}
 *       （M-01 的派生管线沿用它），只是让它写 3 个附件。</li>
 *   <li>⛔ <b>只覆盖 OPAQUE 组</b>（固体 + cutout），半透明地形未覆盖。</li>
 * </ul>
 */
@EventBusSubscriber(modid = VkDisp.MOD_ID, value = Dist.CLIENT)
public final class MrtTerrainPass {

    /** 我方 colortex 目标（懒建；尺寸跟随主目标）。 */
    private static TextureTarget[] colortex;

    /** 我方深度目标（地形要深度测试/写深度；与 colortex 同尺寸）。 */
    private static TextureTarget colortexDepth;

    /** 我方图集采样器（懒建；🔖 绝不每帧新建 —— 会泄漏 GPU 对象，见类注释第 3 条）。 */
    private static GpuSampler atlasSampler;

    /** 是否正处于我方 MRT 地形 pass 内（M-01 据此选管线变体；**渲染线程内**使用）。 */
    private static boolean inMrtPass;

    /** 实际附件数（设备能力收敛后）。 */
    private static int actualSlots;

    /** 是否至少跑过一帧。 */
    private static boolean probed;

    /** 画过多少帧（诊断用）。 */
    private static long framesDrawn;

    /** 埋点最多打几次（照 M-01 教训：埋点节流过密会把热路径变成 I/O 瓶颈）。 */
    private static int probeLogs;

    private MrtTerrainPass() {
    }

    /** 本诊断路径开关（默认关 ⇒ 常规帧零开销，支柱③ B1 ≤ +2%）。 */
    public static boolean enabled() {
        return VkDispConfig.MRT_TERRAIN_ENABLED.get();
    }

    /**
     * 「当前正处于我方 MRT 地形 pass 内」标记 —— M-01 的管线选择读它。
     *
     * <p>🔖 <b>为什么需要这个标记</b>：M-01 注入在 {@code ChunkSectionLayer#pipeline(boolean)}，
     * 该方法只知道「谁在调用我」，<b>不知道调用方开的是哪个 pass</b>。
     * 而原版 {@code DrawSeparate#render} 只调它一次、不透传 pass 引用
     * ⇒ 唯一可行的区分方式就是我方在 pass 体内置位/清位。
     *
     * <p>⚠️ 非 volatile 是**有意的**：读写都在渲染线程，不存在跨线程可见性问题。
     */
    static boolean active() {
        return inMrtPass;
    }

    /** 画过的帧数（纯 long 视图，诊断用）。 */
    public static long framesDrawn() {
        return framesDrawn;
    }

    /** 实际附件数（未初始化 = 0）。 */
    public static int actualSlots() {
        return actualSlots;
    }

    /**
     * 帧图装配钩子（官方事件）：插一个只画 OPAQUE 地形的**多附件** pass。
     *
     * <p>🔖 <b>为什么必须 {@code disableCulling}</b>：原版帧图会剔除「没有产出被后续 pass 消费」
     * 的 pass（{@code FrameGraphBuilder#identifyPassesToKeep}）⇒ 不显式关掉剔除，
     * 本 pass 会被整条丢掉，而且**不报错**（静默失效，T10 的头号坑）。
     */
    @SubscribeEvent
    static void onFrameGraphSetup(FrameGraphSetupEvent event) {
        if (!enabled()) {
            return;
        }
        if (afterLevel()) {
            // A/B 模式：不在帧图里插 pass，改在 AfterLevel 画（见 drawAfterLevel）。
            return;
        }
        FramePass pass = event.getFrameGrapBuilder().addPass("vkdisp_gbuffer_terrain");
        pass.disableCulling();
        // 🔖🔖 **必须在 pass 体里读捕获，不能在这里读**（本轮真踩过，症状极具欺骗性）：
        //   帧图「装配」发生在 LevelRenderer#render 第 249 行（官方事件处），
        //   而 M-05 的捕获发生在第 271-275 行的 prepareChunkRenders —— **装配早于捕获**。
        //   在这里读到的是**上一帧**的 ChunkSectionsToRender，而它持有的
        //   DynamicGpuBuffer 切片（terrainTransformUBO / chunkSectionInfos / 顶点索引缓冲）
        //   已经被本帧的上传**环形复用覆盖** ⇒ 几何退化 ⇒ 一片片元都过不了光栅化。
        //   症状：pass 正常执行、0 validation error、无任何日志异常，但 colortex 里只有清屏色。
        //   ⇒ 捕获必须在**执行期**（第 286 行 frame.execute）读取，那时才是本帧的数据。
        pass.executes(() -> drawTerrain());
    }

    /**
     * 把设备侧调试消息原样打进日志（去重、最多 5 条）。
     *
     * <p>🔖 <b>为什么必须做</b>：本机**没有安装 Vulkan validation layer**
     * （无 {@code VK_LAYER_KHRONOS_validation}）⇒ 管线/render pass 不匹配之类的问题
     * 是**静默未定义行为**，日志全绿而画面全错。此时 {@code GpuDevice#getLastDebugMessages()}
     * 是唯一可能带上驱动/渲染层消息的通道。
     */
    private static void drainDeviceDebugMessages() {
        if (framesDrawn != 300L) {
            return;
        }
        try {
            java.util.List<String> messages = RenderSystem.getDevice().getLastDebugMessages();
            if (messages == null || messages.isEmpty()) {
                VkDisp.LOGGER.info("vkdisp: [GAP-003/A] device debug messages: <none> (channel unavailable or empty)");
                return;
            }
            int n = 0;
            for (String m : messages) {
                if (n++ >= 5) {
                    break;
                }
                VkDisp.LOGGER.warn("vkdisp: [GAP-003/A] device debug message: {}", m);
            }
            VkDisp.LOGGER.info("vkdisp: [GAP-003/A] device debug messages total={}", messages.size());
        } catch (Throwable t) {
            VkDisp.LOGGER.warn("vkdisp: [GAP-003/A] debug message drain failed: {}", t.toString());
        }
    }

    /**
     * 诊断：先在本 pass 里画一个**已知可用**的全屏三角形（采样方块图集）。
     *
     * <p>🔖 <b>它回答什么</b>：「一个片元都没出」有两种完全不同的原因 ——
     * ① 本 pass 根本不工作（附件/管线/视口有问题）；② 本 pass 工作、但地形那批 draw 不出。
     * 全屏三角形若可见 ⇒ ① 排除，问题在地形 draw；不可见 ⇒ 问题在本 pass 本身。
     * 这是一个 pass 内可直接自证的判据，不依赖任何外部假设。
     */
    public static boolean fullscreenProbe() {
        return VkDispConfig.MRT_TERRAIN_FULLSCREEN_PROBE.get();
    }

    private static void drawFullscreenProbe(RenderPass renderPass) {
        // 🔖🔖 探针**必须**用与本 pass 附件数**相同**的颜色目标数，否则必崩：
        //   `FrontendRenderPass#setPipeline` 会校验
        //   「render pass 颜色附件数 == 管线颜色目标数」，不等就抛
        //   IllegalStateException（本轮实测踩到：曾用单目标的 mrtView 管线 ⇒ 直接崩游戏）。
        //   ⇒ 只能用 `PipelineApi.mrtPipeline()`（h02 那条 3 目标全屏管线）。
        //
        // 🔖 顺带一条**重要澄清**：RenderPearl 的 frontend **确实**校验附件/目标数并抛异常 ——
        //   所以「附件数不匹配」这一类是**响亮失败**，不是静默失效。
        //   h04 里真正静默的是**深度清屏值**（没有任何一层会检查它）。
        var compiled = RenderSystem.getCompiledPipelineNullable(PipelineApi.mrtPipeline());
        if (compiled == null) {
            VkDisp.LOGGER.warn("vkdisp: [GAP-003/A] fullscreen probe skipped: vkdisp:mrt pipeline not compiled");
            return;
        }
        // ⚠️ 与 MrtProbe#draw 完全一致：只 bindDefaultUniforms，**不**额外 setUniform ——
        //   `vkdisp:mrt` 管线的 bind group 里没有 Sampler0，多绑会被 validateDraw 拦下。
        renderPass.setPipeline(compiled);
        renderPass.draw(3, 1, 0, 0);
    }

    /** 诊断：附件 0 改用主目标颜色视图（配 {@link #afterLevel()} 使用，屏幕即证据）。 */
    public static boolean toMain() {
        return VkDispConfig.MRT_TERRAIN_TO_MAIN.get();
    }

    private static GpuTextureView mainColorView(RenderTarget main) {
        GpuTextureView v = main.getColorTextureView();
        if (v == null) {
            throw new IllegalStateException("vkdisp: main color view is null");
        }
        return v;
    }

    /** 顺序标记是否已打过（每轮只打一次，避免日志 I/O 进热路径）。 */
    private static boolean orderMarkerLogged;

    /** 原版主 pass 的顺序标记是否已打过。 */
    private static boolean vanillaMarkerLogged;

    /**
     * 🔖 **帧图执行顺序标记**：本事件在原版 {@code executeSolid} 内部触发
     * （{@code LevelRenderer:536}），也就是**原版主 pass 正在执行中**。
     * 与「我方 pass 执行」那条日志比对行号，就能**直接读出**我方 pass 排在哪一步 ——
     * 这是本轮要区分的核心未知（「帧图内插 pass 从未成功」到底是不是顺序问题）。
     *
     * <p>为什么值得专门做：帧图的执行顺序由 {@code resolvePassOrder} 按资源依赖解析，
     * 我方 pass 不声明任何依赖 ⇒ 只能实测，不能靠读 API 猜。
     */
    @SubscribeEvent
    static void onAfterOpaqueBlocks(net.neoforged.neoforge.client.event.RenderLevelStageEvent.AfterOpaqueBlocks event) {
        if (!enabled() || vanillaMarkerLogged) {
            return;
        }
        vanillaMarkerLogged = true;
        VkDisp.LOGGER.info("vkdisp: [GAP-003/A] ORDER-MARK vanilla-main-pass executing");
    }

    /** A/B 模式开关：把绘制从帧图内挪到帧图执行之后。 */
    public static boolean afterLevel() {
        return VkDispConfig.MRT_TERRAIN_AFTER_LEVEL.get();
    }

    /**
     * 【诊断 A/B】在帧图**执行完之后**（AfterLevel）画同一批地形到同一批 colortex。
     *
     * <p>🔖 <b>为什么需要这个 A/B</b>：帧图 pass 的执行顺序由资源依赖解析，
     * 我方 pass 不声明任何依赖 ⇒ 可能排在原版地形数据上传**之前**执行。
     * 若症状是「pass 跑通、0 报错、colortex 只有清屏色」，本开关能一刀切开
     * 「是顺序/上传时序问题」还是「是绘制本身的问题」。
     */
    public static void drawAfterLevel() {
        drawTerrain();
    }

    /** pass 体：真正画地形（跑在帧图**执行期**，即 M-05 捕获之后 ⇒ 拿到的是本帧数据）。 */
    private static void drawTerrain() {
        Object captured = TerrainDrawCapture.current();
        if (captured == null) {
            // M-05 未开启或尚未捕获 ⇒ 无从画地形。**这不是错误**（是「未启用」），
            // 静默跳过；M-05 自己的埋点负责让「为什么没画」可见。
            return;
        }
        if (!(captured instanceof ChunkSectionsToRender draws)) {
            // 类型不符 = 捕获到别的东西 ⇒ 显式报错，不静默（T11）。
            VkDisp.LOGGER.error("vkdisp: [GAP-003/A] captured terrain draws is not ChunkSectionsToRender but {}",
                    captured == null ? "null" : captured.getClass().getName());
            return;
        }
        RenderSystem.assertOnRenderThread();
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        ensureTargets(main);

        // 🔖 GAP-003：两处 uniform 上传都必须在 **pass 打开之前**（map/close 会落盘到环，
        //   pass 打开期间动 encoder 是已实测到的错误用法，FrameApi 同款纪律）：
        //   ① VkDispTerrainParams = 眼空间太阳方向 → 顶点适配层算 sunVec；
        //   ② VkDispBuiltins = OF 内建值 → 包地形片元读的那 40+ 个成员。
        TerrainPipelineApi.updateTerrainParams();
        TerrainPipelineApi.updateTerrainBuiltins();
        //   ③ 中性材质贴图（specular/normals）：贴图上传走 encoder，**pass 打开期间禁止**
        //      ⇒ 懒建会在 pass 内抛 IllegalStateException 且每帧抛（h10 实测踩到）。
        NeutralMaterialMaps.ensureCreated();

        RenderPassDescriptor.Builder descriptor =
                RenderPassDescriptor.builder(() -> "vkdisp gbuffer terrain (OPAQUE, " + actualSlots + " attachments)");
        // 🔴 诊断「画到主目标」：附件 0 直接用主目标的颜色视图 + 清屏成诊断绿。
        //    必须配 AfterLevel 模式使用 —— 那里帧图已执行完、**没有任何 pass 会重画主目标**，
        //    所以屏幕本身就是证据：绿底上出现地形 = 绘制链路正常，问题在 colortex；
        //    纯绿 = 一个片元都没产生（几何/深度/状态），与 colortex 无关。
        java.util.List<GpuTextureView> views = new ArrayList<>(actualSlots);
        for (int slot = 0; slot < actualSlots; slot++) {
            views.add(toMain() && slot == 0 ? mainColorView(main) : view(slot));
        }
        for (int slot = 0; slot < actualSlots; slot++) {
            // 🔖 清屏色用**逐槽高对比诊断色**（绿/蓝/品红），不用 MrtPlan 的 R 通道指纹 ——
            // 指纹在槽 0 恰好是 0.0（黑），一旦「什么都没画」与「画了但很暗」同时发生，
            // 两者在截图上**无法区分**（本轮实测踩到：黑屏既可能是回读坏，也可能是没画）。
            // 高对比色让「回读链路坏」与「地形没画进来」在一张截图里就能分开。
            descriptor.withColorAttachment(views.get(slot), Optional.of(diagnosticClear(slot)));
        }
        // 🔖🔖 深度必须清到 **0.0**，不是惯例上的 1.0 —— 本引擎是**反向 Z**：
        //   原版的 clear pass 就是 `clearColorAndDepthTextures(..., 0.0)`（LevelRenderer:255），
        //   即 0.0 = 远平面、1.0 = 近平面，深度比较是 GREATER 系。
        //   清成 1.0（= 近平面）会让**每一个**地形片元都过不了深度测试：
        //   症状是「pass 跑通、0 报错、colortex/主目标只剩清屏色」，一个片元都不出。
        //   本轮为这个 0.0/1.0 之差绕了整整 6 趟客户端。
        // ⚠️ 刻意**不**复用原版主目标的深度：那要求本 pass 排在原版 clear 之后并写主深度，
        //   会污染主深度缓冲（后续半透明/特性还要用它）。独立深度 = 牵连面最小，代价是
        //   本 pass 内的地形与主场景**没有互相遮挡**（本轮是诊断，不影响结论）。
        descriptor.withDepthAttachment(colortexDepth.getDepthTextureView(), OptionalDouble.of(0.0));

        var encoder = RenderSystem.getDevice().createCommandEncoder();
        inMrtPass = true;
        try (RenderPass renderPass = encoder.createRenderPass(descriptor.build())) {
            // 原版在 executeSolid 之前同样调它（LevelRenderer:464）—— 缺了 Globals/Projection/Fog
            // 会被 validateDraw 按布局逐条校验拦下。
            RenderSystem.bindDefaultUniforms(renderPass);
            // 🔖 原版在 executeSolid 之前必做的一步（LevelRenderer:450）：新光照系统的每关卡入口。
            // 我方 pass 不在原版序列里，缺这一步时 TerrainUniform/lightmap 相关状态是上一帧的残留。
            try {
                Minecraft.getInstance().gameRenderer.lighting().setupFor(com.mojang.blaze3d.platform.Lighting.Entry.LEVEL);
            } catch (Throwable t) {
                VkDisp.LOGGER.warn("vkdisp: [GAP-003/A] lighting setup unavailable: {}", t.toString());
            }
            GpuTextureView atlas = blockAtlas();
            // 🔖 GAP-003：包地形片元要绑的 uniform（VkDispBuiltins + 它自由声明的 sampler）。
            //   **必须在 renderGroup 之前**：STRICT_VALIDATION 下 validateDraw 按布局逐条校验，
            //   少一条即抛 Missing uniform 名（响亮失败，不是静默）。
            TerrainPipelineApi.bindPackTerrainUniforms(renderPass, atlasSampler, atlas,
                    colortexDepth.getDepthTextureView(), view(0));
            if (fullscreenProbe()) {
                drawFullscreenProbe(renderPass);
            }
            draws.renderGroup(ChunkSectionLayerGroup.OPAQUE, renderPass, atlasSampler, atlas, false);
            drainDeviceDebugMessages();
        } finally {
            // 🔖 必须在 finally 清：漏清会让后续**原版** pass 也拿到多附件管线 ⇒ 立刻 validation error。
            inMrtPass = false;
        }

        framesDrawn++;
        if (!orderMarkerLogged) {
            orderMarkerLogged = true;
            VkDisp.LOGGER.info("vkdisp: [GAP-003/A] ORDER-MARK my-pass executing ({} mode)", afterLevel() ? "afterLevel" : "frameGraph");
        }
        // 🔖 探针**不能**在首帧跑：首帧区块还没网格化，drawGroups 必然是空的
        //（本轮踩过：首帧打出 groups=0，一度被误读成「捕获到的数据是空的」）。
        if (framesDrawn == 300L || framesDrawn == 1200L) {
            probeDrawCounts(draws);
        }
        if (!probed) {
            probed = true;
            VkDisp.LOGGER.info(
                    "vkdisp: [GAP-003/A] terrain drawn into {} attachment(s) pass (group=OPAQUE,"
                            + " main target untouched; draws={})",
                    actualSlots, framesDrawn);
        } else if (probeLogs < 3 && framesDrawn % 600L == 0L) {
            probeLogs++;
            VkDisp.LOGGER.info("vkdisp: [GAP-003/A] terrain MRT pass frames={}", framesDrawn);
        }
    }

    /** 懒建 colortex + 深度 + 采样器（尺寸跟随主目标；尺寸变化时 resize，不每帧重建）。 */
    private static void ensureTargets(RenderTarget main) {
        if (colortex != null) {
            if (colortex[0].width != main.width || colortex[0].height != main.height) {
                for (TextureTarget target : colortex) {
                    target.resize(main.width, main.height);
                }
                colortexDepth.resize(main.width, main.height);
                VkDisp.LOGGER.info("vkdisp: [GAP-003/A] colortex resized to {}x{}", main.width, main.height);
            }
            return;
        }
        // 🔖 与管线共用 MrtPlan.slotCount()（单点真源）：本机无 validation layer，
        // 两侧不一致就是**静默失效**（draw 全被丢弃、日志全绿、屏幕只有清屏色）。
        actualSlots = Math.min(MrtPlan.slotCount(),
                RenderSystem.getDevice().getDeviceInfo().limits().maxColorAttachments());
        List<TextureTarget> targets = new ArrayList<>(actualSlots);
        for (int slot = 0; slot < actualSlots; slot++) {
            targets.add(new TextureTarget("vkdisp gbuffer colortex" + slot, main.width, main.height,
                    GpuFormat.RGBA8_UNORM, null));
        }
        colortex = targets.toArray(new TextureTarget[0]);
        colortexDepth = new TextureTarget("vkdisp gbuffer depth", main.width, main.height,
                null, GpuFormat.D32_FLOAT);
        atlasSampler = RenderSystem.getDevice().createSampler(
                AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR,
                1, OptionalDouble.empty());
        VkDisp.LOGGER.info("vkdisp: [GAP-003/A] gbuffer terrain targets ready: {}x{} slots={} depth=D32_FLOAT",
                main.width, main.height, actualSlots);
    }

    /**
     * 反射读出捕获对象里**各层实际要提交的 draw 条数**（只诊断一次）。
     *
     * <p>🔖 <b>为什么必须反射</b>：{@code ChunkSectionsToRender.DrawIndirect.drawGroupsPerLayer}
     * 与 {@code GpuMultiDrawIndexedIndirect.drawCount()} 都是 private，
     * 而「pass 跑通、0 报错、colortex 只有清屏色」这个症状**无法区分**
     * 「一条 draw 都没提交」与「提交了但全被丢弃」。
     * 这是本轮卡得最久的一环 —— 没有这个数字就只能靠猜。
     *
     * <p>⚠️ 反射失败只打 WARN 不抛：诊断手段本身不该把功能拖挂。
     */
    private static void probeDrawCounts(ChunkSectionsToRender draws) {
        try {
            Class<?> cls = draws.getClass();
            java.lang.reflect.Field groupsField = cls.getDeclaredField("drawGroupsPerLayer");
            groupsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<net.minecraft.client.renderer.chunk.ChunkSectionLayer, ? extends java.util.List<?>> groups =
                    (java.util.Map<net.minecraft.client.renderer.chunk.ChunkSectionLayer, ? extends java.util.List<?>>)
                            groupsField.get(draws);
            StringBuilder sb = new StringBuilder();
            for (var e : groups.entrySet()) {
                int items = e.getValue() == null ? -1 : e.getValue().size();
                long drawSum = 0L;
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    java.lang.reflect.Field dc = e.getValue().getFirst().getClass().getDeclaredField("drawCount");
                    dc.setAccessible(true);
                    for (Object o : e.getValue()) {
                        drawSum += ((Number) dc.get(o)).longValue();
                    }
                }
                sb.append(' ').append(e.getKey()).append("{groups=").append(items)
                        .append(",draws=").append(drawSum).append('}');
            }
            VkDisp.LOGGER.info("vkdisp: [GAP-003/A] captured draw groups: {}", sb);
        } catch (Throwable t) {
            VkDisp.LOGGER.warn("vkdisp: [GAP-003/A] draw-count probe unavailable: {}", t.toString());
        }
    }

    /** 逐槽诊断清屏色：槽 0 绿 / 槽 1 蓝 / 槽 2 品红（高对比，便于一眼分辨「哪一槽 + 有没有内容」）。 */
    private static Vector4f diagnosticClear(int slot) {
        return switch (slot) {
            case 0 -> new Vector4f(0.0F, 1.0F, 0.0F, 1.0F);
            case 1 -> new Vector4f(0.0F, 0.0F, 1.0F, 1.0F);
            default -> new Vector4f(1.0F, 0.0F, 1.0F, 1.0F);
        };
    }

    /** 我方 colortex 某一槽的视图（供调试回读）；未建 / 越界返回 {@code null}。 */
    public static GpuTextureView slotView(int slot) {
        if (colortex == null || slot < 0 || slot >= colortex.length || colortex[slot] == null) {
            return null;
        }
        return colortex[slot].getColorTextureView();
    }

    private static GpuTextureView view(int slot) {
        GpuTextureView view = colortex[slot].getColorTextureView();
        if (view == null) {
            throw new IllegalStateException("vkdisp: gbuffer colortex" + slot + " color view is null");
        }
        return view;
    }

    /** 方块图集视图（与原版 {@code LevelRenderer:531} 同一条公开路径）。 */
    private static GpuTextureView blockAtlas() {
        GpuTextureView atlas = Minecraft.getInstance().getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        if (atlas == null) {
            throw new IllegalStateException("vkdisp: block atlas texture view is null");
        }
        return atlas;
    }
}