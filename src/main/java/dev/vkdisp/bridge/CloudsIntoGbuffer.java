package dev.vkdisp.bridge;
/**
 * 【参考调研】GAP-027 第二刀：把原版**云**画进我方 gbuffer / 只依据公开 API 与本仓库实测
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 原版 26.3 {@code net/minecraft/client/renderer/CloudRenderer.java}
 *    的**公开方法签名**（随 MDG 分发的 sources jar 逐字核实）：
 *    {@code public void prepare(int color, CloudStatus, float bottomY, int range, Vec3 cameraPosition,
 *    long gameTime, float partialTicks)}（第 129 行）与
 *    {@code public void render(CloudStatus, RenderPass)}（第 197 行，**自己收 RenderPass**）；
 *    ② 原版 {@code LevelRenderer} 的调用序与取值来源（第 545-568 行 prepare、
 *    第 727 行 {@code renderGroup(TRANSLUCENT)}、第 738 行 clouds ⇒ <b>云在地形之后</b>；
 *    {@code optionsRenderState} 来自 {@code gameRenderer.gameRenderState()} 第 174 行）；
 *    ③ 本仓库自有的 {@code SkyIntoGbuffer}（同一族「把非地形 draw 搬进 gbuffer」的既有实现，MIT）。
 *    许可证：Mojang EULA（只观察签名与调用时机）+ NeoForge LGPL-2.1（同上）。
 *    → 能否并入本项目（MIT）：可以 —— 本文件是独立编写的桥接封装，零源码搬运。
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码。
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）。
 * 1. 官方/主实现：原版自己就是「开一个 pass、把 RenderPass 交给 CloudRenderer」。
 * 2. 备选：① 走 {@code RenderSystem.pushPipelineModifier} 换成包的 {@code gbuffers_clouds} ——
 *    <b>本轮不做</b>（那是下一刀，且它需要为云单独生成顶点适配层与绑定组）；
 *    ② 借 {@code GbufferTarget} 薄壳（天空那一格用的）—— <b>不可行</b>：
 *    {@code CloudRenderer#render} 直接收 {@code RenderPass}，不给 {@code RenderTarget}，
 *    硬套薄壳反而要多造一层。
 * 3. 我们的差异点：
 *    <ul>
 *      <li>🔴 <b>只开一个颜色附件的 pass</b>：云内部用原版 {@code RenderPipelines.CLOUDS/FLAT_CLOUDS}
 *          （1 个颜色目标），而我方地形 pass 接了水之后是 2 个附件 ⇒ 附件数 ≠ 颜色目标数
 *          会被 frontend 的 {@code setPipeline} 校验<b>当场抛</b>（这是本仓库 h02 就量到的响亮失败）。</li>
 *      <li>🔴 <b>必须写「待写那一代」且必须在翻代之前</b>（GAP-018）：{@code advanceWritten} 一翻代，
 *          「待写那一代」就换了 ⇒ 云若翻代后才写，链读到的是「只有地形、没有云」
 *          （h48i 的天空逐字踩过：{@code c0@afterSky=0.0000}）。</li>
 *      <li>🔖 <b>判据不靠画面猜</b>：{@code CloudRenderer} 内部有
 *          {@code texture != null && quadCount != 0} 的<b>静默早退</b>，而 {@code quadCount} 是 private
 *          ⇒ 「云没出现」可能是「一条 quad 都没有」而不是「我方 pass 坏了」。
 *          所以本类自己声明观测面：打出 status / 云色 alpha / 云高 / 云范围，
 *          并在 pass 之后取一次 {@code c0@afterClouds} 探针。</li>
 *    </ul>
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 默认关（{@code mrt.cloudsPass=false} ⇒ 常规帧零开销）；
 *    开启时每帧多一个全屏 pass + 一次 {@code prepare}（原版本来也做这两件事，代价同量级）。
 */
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.vkdisp.VkDisp;
import dev.vkdisp.VkDispConfig;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.oit.OitRenderPassProvider;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.neoforged.neoforge.client.CustomCloudsRenderer;
import net.neoforged.neoforge.client.pipeline.PipelineModifier;
import net.minecraft.resources.ResourceKey;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** 原版云 → gbuffer colortex0 的重放器（渲染线程独占，无锁）。 */
public final class CloudsIntoGbuffer {

    /** 一次性自报哨兵（热路径不刷日志 —— 本仓 h33/h34 那两条节流纪律）。 */
    private static boolean reportedOnce;

    /** 网格状态自报哨兵（与上面那个分开：两条说的是两件不同的事）。 */
    private static boolean meshReported;

    /** 诊断清屏那条 WARN 的哨兵。 */
    private static boolean clearWarned;

    /** 「{@code mixin.cloudMatrixRedirect=false} 的代价」那条 WARN 的哨兵（M-10，热路径不刷屏）。 */
    private static boolean matrixRedirectOffWarned;

    /** 跳过原因的一次性自报集合（「为什么没画」必须看得见，X11）。 */
    private static final java.util.Set<String> SKIP_NOTED =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    private CloudsIntoGbuffer() {
    }

    /** 本格的开关（默认关 ⇒ 与今天逐字一致）。 */
    public static boolean enabled() {
        return VkDispConfig.MRT_CLOUDS_PASS.get();
    }

    /**
     * 接管原版云 pass 的官方钩子：{@code renderClouds} 回 true = 原版那笔云 draw 本帧不发。
     *
     * <p>🔴 <b>为什么必须有它</b>（2026-10-10 真机截图逐字）：云搬进 gbuffer 之后原版那笔
     * <b>仍然</b>画进 main，而 frameGraph 档里原版 clouds pass 与链无依赖边、排在链之后
     * ⇒ 屏幕上「gbuffer 云 + 原版云」两层同框。管线替换通道救不了这件事（它只换状态、
     * 换不了落点），而本钩子是 NeoForge 在 clouds pass 的 {@code executes} 里先问的那一句
     * （{@code LevelRenderer#addCloudsPass}：{@code customCloudsRenderer.renderClouds(...)}
     * 回 true 即跳过原版 draw）⇒ 零 mixin 的唯一抑制点。
     *
     * <p>🔖 <b>为什么装在装配期而不是这里画</b>：本钩子被调时原版那个 pass 已经开着，
     * 而 RenderPearl 不许 pass 套 pass（h10）⇒ gbuffer 云照旧在地形后画（{@link #render()}），
     * 本钩子只负责「让原版那笔闭嘴」。字段每帧由 {@code LevelExtractor} 重置 ⇒ 无残留态。
     */
    private static final CustomCloudsRenderer VANILLA_CLOUDS_SUPPRESSOR = new CustomCloudsRenderer() {
        @Override
        public boolean renderClouds(LevelRenderState state, CloudStatus status,
                Matrix4fc modelViewMatrix, RenderPass renderPass) {
            noteSuppressedOnce();
            return true;
        }

        @Override
        public boolean renderCloudsOit(LevelRenderState state, CloudStatus status,
                Matrix4fc modelViewMatrix, OitStage stage, GpuTextureView mainDepth,
                OitRenderPassProvider.Parameters params) {
            noteSuppressedOnce();
            return true;
        }
    };

    private static boolean suppressionNoted;

    private static void noteSuppressedOnce() {
        if (!suppressionNoted) {
            suppressionNoted = true;
            VkDisp.LOGGER.info("vkdisp: [GAP-027/clouds] 原版云 draw 已由 customCloudsRenderer 钩子抑制"
                    + "（gbuffer 云在链里 ⇒ 原版那笔必须闭嘴，否则两层同框）");
        }
    }

    /**
     * 装配期挂抑制器（frameGraph 档）。关档 / 链不接管时不挂 ⇒ 原版云是唯一一层。
     *
     * <p>🔖 判据与 {@link #render()} 同源再加一条「链真的接管本帧」：链不接管时 main 就是
     * 原版画面，抑制原版云 = 画面里一条云都没有（比双画更糟）。
     */
    public static void installSuppression() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !enabled() || !FrameApi.isPostChainActive()) {
            return;
        }
        mc.gameRenderer.gameRenderState().levelRenderState.customCloudsRenderer =
                VANILLA_CLOUDS_SUPPRESSOR;
    }

    /**
     * 本帧相机的 modelview（原版云 pass 在 executes 里用的就是它）。
     *
     * <p>🔖 <b>它现在只被 {@link #onCloudModelViewRequest()} 用</b>（M-10，2026-10-12）：
     * 旧实现是「画前把它压进全局栈、画后还原」，代价是多一个 {@code finally} 纪律，
     * 而漏还原会污染同帧后续读这条栈的原版 pass。现在由 {@code @Redirect} 把它作为
     * {@code CloudRenderer} 第 226 行那次取矩阵的**实参**交出 —— 全局栈不再被碰。
     *
     * <p>矩阵取 {@code CameraRenderState#viewRotationMatrix} —— 与原版云 pass 捕获的是同一个
     * 公开字段（{@code GameRenderer} 逐字把它当 modelViewMatrix 传下去）。
     */
    private static Matrix4fc cameraModelView() {
        return Minecraft.getInstance().gameRenderer.gameRenderState()
                .levelRenderState.cameraRenderState.viewRotationMatrix;
    }

    /**
     * M-10 的转发点：原版云取 modelview 时<b>交给它</b>的矩阵（由
     * {@code mixin.CloudRendererMatrixRedirectMixin} 在第 226 行那次调用上调用本方法）。
     *
     * <p>🔖 <b>对原版路径这是恒等变换</b>（源码级核实）：原版只在
     * {@code LevelRenderer} 第 738 行（main pass 体内）与第 646 行（OIT 分支）画云，
     * 这两处栈顶本来就是装配期相机矩阵 ⇒ 交出同一个矩阵，原版云的落点逐字不变。
     *
     * <p>⚠️ 关档（{@code mixin.cloudMatrixRedirect=false}）= 交回原版那句
     * {@code RenderSystem.getModelViewMatrixCopy()}：原版仍不受影响，但我方重放读的是<b>裸栈</b>
     * （非相机位姿）⇒ 云网格锚错、透视拉成放射长条（旧症状复现）。这是 A/B 档，代价只 WARN 一次。
     *
     * @return 交予 {@code writeTransform} 的矩阵（返回类型随原版那句调用 = {@code Matrix4f}）
     */
    public static Matrix4f onCloudModelViewRequest() {
        if (!VkDispConfig.MIXIN_CLOUD_MATRIX_REDIRECT.get()) {
            if (!matrixRedirectOffWarned) {
                matrixRedirectOffWarned = true;
                VkDisp.LOGGER.warn("vkdisp: [GAP-027/clouds] mixin.cloudMatrixRedirect=false ⇒ 云取裸栈的"
                        + " getModelViewMatrixCopy()，而我方重放已不再压栈（M-10 删掉了那次压还原）"
                        + " ⇒ 云网格锚错、放射长条会复现；这是 A/B 档不是产品档");
            }
            return RenderSystem.getModelViewMatrixCopy();
        }
        // 一次拷贝：与原版那句自己那次 `new Matrix4f(stack)` 同量级，不是新增的分配类别。
        return new Matrix4f(cameraModelView());
    }

    /**
     * 把云画进 gbuffer。调用时机：<b>地形那个 render pass 已关闭、且 {@code advanceWritten} 之前</b>
     * （理由见类注释第 3 条）。
     */
    public static void render() {
        if (!enabled()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.levelRenderer == null) {
            skipOnce("not-in-world");
            return;
        }
        GpuTextureView color = MrtTerrainPass.poolWriteView(0);
        if (color == null) {
            skipOnce("gbuffer-view-null");
            return;
        }
        GpuTextureView depth = MrtTerrainPass.depthView();
        if (depth == null) {
            skipOnce("gbuffer-depth-null");
            return;
        }
        CloudRenderer renderer = mc.levelRenderer.cloudRenderer();
        if (renderer == null) {
            skipOnce("cloud-renderer-null");
            return;
        }
        LevelRenderState levelState = mc.gameRenderer.gameRenderState().levelRenderState;
        var optionsState = mc.gameRenderer.gameRenderState().optionsRenderState;
        CloudStatus status = optionsState.cloudStatus;
        int cloudColor = levelState.cloudColor;
        // 🔖 与原版同一个谓词（`LevelRenderer:546`：status != OFF 且云色 alpha > 0）。
        //   自己另造一份「什么时候该画云」= 第二份口径，迟早与原版不一致。
        if (status == CloudStatus.OFF || (cloudColor >>> 24) == 0) {
            skipOnce("clouds-off-or-transparent status=" + status + " alpha=" + (cloudColor >>> 24));
            return;
        }
        reportOnce(status, cloudColor, levelState, optionsState);
        // 🔴 prepare 必须在**开 pass 之前**：它会建/传 buffer 与网格（要新建 encoder），
        //   而 render pass 打开期间新建 encoder 会被 RenderPearl 拒绝
        //   （"Close the existing render pass before creating a new one!"，h10 实测）。
        renderer.prepare(cloudColor, status, levelState.cloudHeight, optionsState.cloudRange,
                levelState.cameraRenderState.pos, levelState.gameTime, levelState.worldPartialTicks);
        // 🔖 自报放在 prepare **之后**：`quadCount` 是 prepare 算出来的，
        //   放在之前读到的是上一帧的（首帧必然 0 ⇒ 会把「正常的首帧」报成「网格坏了」）。
        reportMeshOnce(renderer);

        drawThroughOwnPass(renderer, status, color, depth);
        // 🔬 判据：云写完之后的 colortex0（与 `c0@afterTerrain` 同臂同机位对比才有意义）。
        dev.vkdisp.bridge.TargetReadback.probeAfterClouds();
    }

    /**
     * 一次性把「这一格到底在什么条件下画」打进日志（观测面，不是调试残留）。
     *
     * <p>🔴 <b>为什么要反射原版的 private 字段</b>：h49l 实测 ON 臂自报正常、0 报错，
     * 而 {@code c0@afterClouds} 与 {@code c0@afterTerrain} <b>逐位相同</b>（一个像素都没写）。
     * 这个症状有两种完全不同的原因，而它们在日志与画面上<b>长得一模一样</b>：
     * ① {@code CloudRenderer#render} 内部的 {@code texture != null && quadCount != 0}
     *    静默早退 ⇒ <b>一条 draw 都没发</b>；
     * ② 发了 draw 但全被拒（深度比较口径、状态、几何位置）⇒ <b>发了但没落地</b>。
     * 两者要修的不是同一个东西 ⇒ 只能把数字量出来（与本仓 {@code probeDrawCounts}
     * 反射 draw 条数是同一课：「没有这个数字就只能靠猜」）。
     * ⚠️ 反射失败只 WARN 不抛 —— 取证手段不该把功能拖挂。
     */
    private static void reportOnce(CloudStatus status, int cloudColor,
            LevelRenderState levelState,
            net.minecraft.client.renderer.state.OptionsRenderState optionsState) {
        if (reportedOnce) {
            return;
        }
        reportedOnce = true;
        VkDisp.LOGGER.info("vkdisp: [GAP-027/clouds] 云搬进 gbuffer: status={} cloudColorAlpha={}"
                        + " cloudHeight={} cloudRange={} gameTime={} —— 着色器仍是原版"
                        + " CLOUDS/FLAT_CLOUDS ⇒ 换成包的 gbuffers_clouds 是下一刀",
                status, cloudColor >>> 24, levelState.cloudHeight, optionsState.cloudRange,
                levelState.gameTime);
    }

    /** 网格状态只报一次（它决定「没发 draw」还是「发了没落地」，是本轮判据的核心一格）。 */
    private static void reportMeshOnce(CloudRenderer renderer) {
        if (meshReported) {
            return;
        }
        meshReported = true;
        VkDisp.LOGGER.info("vkdisp: [GAP-027/clouds] 网格状态（prepare 之后、render 之前）: {}",
                meshState(renderer));
    }

    /**
     * 开我方自己的云 pass 并把云画进去。
     *
     * <p>单独成方法只为两件事：① 本仓 QD-04 那条「&gt;60 行方法」棘轮（**靠提取降，不靠放宽基线**）；
     * ② 让「诊断档会毁掉本帧地形内容」这个判断只出现在一处。
     */
    private static void drawThroughOwnPass(CloudRenderer renderer, CloudStatus status,
            GpuTextureView color, GpuTextureView depth) {
        // 🔬 诊断档：按 CLEAR 洋红打开（默认关）。它切的正是「云的 draw 不对」与
        //   「我方挂的 view 和被读的代次不是同一张图」—— 两者在 LOAD 档下读数完全一样。
        boolean diagnosticClear = VkDispConfig.MRT_CLOUDS_DIAGNOSTIC_CLEAR.get();
        if (diagnosticClear && !clearWarned) {
            clearWarned = true;
            VkDisp.LOGGER.warn("vkdisp: [GAP-027/clouds] mrt.cloudsDiagnosticClear=true ⇒ 云 pass 按"
                    + " **CLEAR 洋红** 打开 ⇒ 本帧 colortex0 的**地形内容被毁掉**，这是取证档不是产品档；"
                    + "判读：c0@afterClouds 读到洋红 ⇒ pass/代次口径对、问题在云的 draw；"
                    + "仍读到地形的值 ⇒ 我方挂的 view 与被读的代次不是同一张图");
        }
        RenderPassDescriptor descriptor = RenderPassDescriptor
                .builder(() -> "vkdisp gbuffer clouds (1 color attachment, "
                        + (diagnosticClear ? "DIAGNOSTIC CLEAR" : "LOAD") + ")")
                .withColorAttachment(color, clearValue(diagnosticClear))
                .withDepthAttachment(depth, OptionalDouble.empty())
                .build();
        try (RenderPass renderPass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(descriptor)) {
            // 原版在 CloudRenderer#render 内部自己调 bindDefaultUniforms（第 201 行），
            // 这里**不**重复调：多调一次不报错，但会让人以为云依赖我方绑的东西。
            // 🔖 位姿**不再走全局栈**（M-10，2026-10-12）：原版第 226 行取 modelview 的那次调用被
            //   @Redirect 换成装配期相机矩阵 ⇒ 这里既不压栈也不还原，
            //   「漏还原 ⇒ 污染同帧后续读这条栈的原版 pass」这一族事故类别从代码里消失。
            //   矩阵取法与关档代价见 {@link #onCloudModelViewRequest()}。
            if (VkDispConfig.MRT_CLOUDS_NO_CULL.get()) {
                // 🔌 管线替换（GAP-027 用的官方通道）：
                //   CloudRenderer 内部走 RenderSystem.getCompiledPipeline(...)
                //   → getCompiledPipelineNullable 的**首条语句**就是 PIPELINE_MODIFIERS.apply
                //   ⇒ 在它的调用期间 push 我们的 modifier 就够了，不必 mixin、也不必预注册管线
                //   （PipelineCache#get 未命中会就地编译）。
                //   renderWithPipelineModifier 自带 push/pop 配对 —— 不用手写 finally：
                //   漏 pop 会让 ClientHooks 的 ensurePipelineModifiersEmpty() 在帧尾**抛**。
                // 🔖 GAP-044：配置在**调用点**读，不在 modifier 的 apply 里读 ——
                //   PipelineModifierStack 的结果按 (modifier, 入参管线) 缓存且没有失效路径，
                //   apply 里现读会把「第一次 push 那一刻的值」永久冻结（改设置要重启才生效且无提示）。
                ResourceKey<PipelineModifier> swap = VkDispConfig.MRT_CLOUDS_NO_DEPTH_WRITE.get()
                        ? GbufferPipelineSwaps.CLOUDS_NO_CULL_NO_DEPTH_WRITE
                        : GbufferPipelineSwaps.CLOUDS_NO_CULL;
                RenderSystem.renderWithPipelineModifier(swap,
                        () -> renderer.render(status, renderPass));
            } else {
                renderer.render(status, renderPass);
            }
        }
    }

    /**
     * 云 pass 的颜色附件清屏值：{@code Optional.empty()} = LOAD（保留地形），
     * 洋红 = 诊断档（**毁掉**本帧地形内容）。
     * 单独抽一个方法只为一件事：让「诊断档会毁内容」这句判断只出现在一处。
     */
    private static Optional<org.joml.Vector4fc> clearValue(boolean diagnosticClear) {
        return diagnosticClear
                ? Optional.of(new org.joml.Vector4f(1.0F, 0.0F, 1.0F, 1.0F))
                : Optional.empty();
    }

    /**
     * 云网格状态（M-09：走 {@link IVkdispCloudMeshProbe} 访问器；反射与那条「反射失败」分支
     * 已<b>物理删除</b>，改名从「静默 WARN」变成「注入期硬失败」）。
     *
     * <p>🔖 只交 {@code quadCount} 一个数就够：{@code quadCount != 0} 蕴含 {@code texture != null}
     * （网格只能在纹理就位后算出），而原版第 198 行的早退条件正是这两者的合取 ⇒ 判据等价。
     * 为什么不交出另外两个引用字段，逐字写在 {@link IVkdispCloudMeshProbe} 的类注释第 3 条。
     */
    private static String meshState(CloudRenderer renderer) {
        if (!VkDispConfig.MIXIN_CLOUD_PROBE.get()) {
            return "mixin.cloudProbe=false ⇒ 本臂不读 quadCount（关掉只少这一行自报）";
        }
        int quads = ((IVkdispCloudMeshProbe) renderer).vkdisp$quadCount();
        return "quadCount=" + quads + "（quadCount=0 ⇒ 原版内部静默早退、一条 draw 都不发）";
    }

    /** 跳过原因只报一次（同一原因每帧刷 = 把日志当 printf，本仓反复踩过）。 */

    /** 跳过原因只报一次（同一原因每帧刷 = 把日志当 printf，本仓反复踩过）。 */
    private static void skipOnce(String reason) {
        if (SKIP_NOTED.add(reason)) {
            VkDisp.LOGGER.info("vkdisp: [GAP-027/clouds] 本帧跳过云重放（原因={}）—— "
                    + "「云没出现」与「云 pass 坏了」是两件事，先看这一行", reason);
        }
    }
}
