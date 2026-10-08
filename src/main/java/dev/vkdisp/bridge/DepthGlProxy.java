package dev.vkdisp.bridge;
/**
 * 【参考调研】GAP-022 ①：GL 口径深度代理（一张「1 − 引擎深度」的 R32F 离屏图，供链当 depthtex0）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 原版 Minecraft 26.3 客户端 com.mojang.renderpearl.api.*（GpuDevice#createTexture /
 *    GpuDevice#createTextureView / CommandEncoder#createRenderPass）与本仓库
 *    {@code FrameApi#generateMipPyramids}（同一形状：单颜色附件 + {@code InSampler} + 全屏三角形）。
 *    许可证：Mojang EULA（原版）→ 只观察公开 API 签名与调用形状，零源码文本搬运；
 *    参考模组（VulkanMod / Sulkan 等）零接触。
 *    → 能否并入本项目（MIT）：可以 —— 本文件是独立实现的薄封装，不含被参考方代码
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现（本轮逐条核实，全部带行号）：
 *    ① 颜色目标格式是**管线静态状态**，并且**逐附件核对**：
 *       {@code com/mojang/renderpearl/frontend/FrontendRenderPass.java:111-122} ——
 *       先核「pass 附件数 == 管线 color target 数」（:112-113 抛），再核
 *       {@code colorTargetState.format() != attachment...getFormat()} 即抛
 *       「Render pass color attachment N format doesn't match pipeline format.」（:119-121）。
 *       ⇒ 管线的 ColorTargetState 与本类建纹理的格式**必须同源**，所以格式只有
 *       {@link #proxyFormat()} 这一个真源（两处各抄一遍字面量 = X42 那一族的下一例）。
 *    ② 该格式会烧进 VkPipeline：{@code VulkanRenderPipeline.java:286-293} 把每个
 *       {@code ColorTargetState.format()} 经 {@code VulkanConst.toVk} 填进
 *       {@code VkPipelineRenderingCreateInfoKHR.pColorAttachmentFormats}（动态渲染，无 VkRenderPass）；
 *       {@code VulkanConst.java} 的格式表里 {@code R32_FLOAT -> 100}（= VK_FORMAT_R32_SFLOAT）。
 *    ③ 采样位与附件位由 usage 位推导：{@code VulkanConst#textureUsageToVk} ——
 *       {@code RENDER_ATTACHMENT(8)} + 颜色 aspect → COLOR_ATTACHMENT_BIT，
 *       {@code TEXTURE_BINDING(4)} → SAMPLED_BIT；R32_FLOAT 有颜色 aspect
 *       （{@code GpuFormat#hasColorAspect} 只对 D/S 族为假）⇒ 一张纹理同时拿到两位，
 *       正是「本 pass 写它、链里读它」需要的组合。usage=15 的取值口径同
 *       {@code ColortexPool.java:29-30}（与原版 RenderTarget 同值，字节码核实）。
 *    ④ 深度视图当普通 sampler2D 采：本项目**每帧都这么干**（地形包片元的 shadowtex0/1 绑的就是
 *       深度视图 —— 见 {@code TerrainPipelineApi} 的 GAP-015 一次性说明与 {@code SamplerDimensionPlan}），
 *       且 {@code assets/vkdisp/shaders/depthviz.fsh:13} 已是「{@code texture(InSampler, vUv).r}
 *       采深度」的现成写法 ⇒ 不需要 copyTextureToTexture 旁路，也不需要比较采样器
 *       （原版给不出比较采样器 = GAP-015，与本轮无关）。
 * 2. 备选：
 *    ① 在地形 MRT pass 里多挂一个颜色输出直接写 1−z —— <b>否决</b>：派生地形管线的颜色目标数
 *       必须与 pass 附件数恒等（FrontendRenderPass.java:112-113 当场抛，X42 已烧过我们），
 *       动附件集合会连带动 MrtPlan 槽数、链的定宽 8 布局、回读探针三处，代价远大于一次全屏写。
 *    ② {@code copyTextureToTexture} 把 D32 拷进 R32F 再二次翻转 —— <b>否决</b>：
 *       {@code VulkanCommandEncoder#copyTextureToTexture} 把 {@code formatAspectMask(source)}
 *       算出的同一个 aspectMask 同时填给 src 与 dst（源码核实），D32（DEPTH bit）拷进
 *       R32F（COLOR bit）的 dst aspect 不匹配 = 验证层 UB；而且它本来也不做 1−z。
 *    ③ RGBA16F / RGBA32F 顶替单通道 —— <b>本轮不采用</b>（片元已经把值同时写进 .rgb，换格式
 *       只多浪费带宽）；换格式时 {@link #proxyFormat()} 是**唯一**改动点，管线与纹理同步变。
 * 3. 我们的差异点：只补引擎缺的那一层（引擎里没有「深度约定转换」这件事）；
 *    渲染线程独占、无锁（与 {@code ColortexPool} 同口径）；任何前置条件不满足都走
 *    {@link #reportMissingOnce} 的**一次性自报**，绝不静默换绑（X11 / T11）。
 * 4. 许可证核对：本项目 MIT；只调用公开 API 签名，无代码复制（07-CONSTRAINTS §〇 P1、L5-L8）。
 * 5. 性能基线：每帧一次全屏写（分辨率 × 4 B）+ 链内一次采样；开关默认关，
 *    关掉即零开销（连纹理都不留，见 {@link #release()}）。按指令不做性能结论（17-NATIVE.md §3.2）。
 */
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Optional;
import java.util.OptionalDouble;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import dev.vkdisp.VkDisp;
import dev.vkdisp.VkDispConfig;

/**
 * GL 口径深度代理的生命周期 + 那一趟全屏翻转 pass。
 *
 * <p><b>它补的是什么</b>（{@code docs/13-GAP-REGISTRY.md} GAP-022）：引擎窗口深度是反向 Z
 * （近平面 = 1.0、天空 = 0.0 —— {@code Projection#getMatrix} 里那句
 * {@code float near = this.zFar; float far = this.zNear;}，叠加
 * {@code VulkanDevice.java:91-95} 给 {@code DeviceInfo.isZZeroToOne} 传 true），
 * 而包按「1.0 = 天空」写分支（BSL {@code deferred1.glsl:337 isSky = z == 1.0} 等）。
 * 同一组 (n, f) 下 {@code z_gl = 1 − z_en} 恒等 ⇒ 一次逐像素取反就是全精度正确的换算。
 *
 * <p><b>时序</b>：必须在<b>地形 MRT pass 之后、链第一级之前</b>跑
 * —— 调用点在 {@code FrameApi#drawPostChain} 开头（{@code FullscreenPassHook} 先
 * {@code paintGbufferAndTerrain()} 再调本方法，那个顺序就是本 pass 的顺序保证）。
 */
public final class DepthGlProxy {

    /** 链里一个 depthtex* 名字最终该绑哪一张图（三条互斥出口，见 {@link #chooseDepthSource}）。 */
    public enum DepthSource {
        /** GL 口径代理（1 − 引擎深度）。 */
        PROXY,
        /** 引擎原图：反向 Z，天空 = 0.0（现状，也是代理缺席时的降级目标）。 */
        ENGINE_REVERSED_Z,
        /** 连引擎深度都没有 ⇒ 显式占位（h33 口径：不许绑 null，也不许假装接好了）。 */
        PLACEHOLDER
    }

    // ── 纯判据（单测覆盖的就是这几个，不碰任何原版运行态） ──────────────────────────

    /**
     * 代理纹理 / 管线颜色目标的<b>唯一</b>格式真源 —— 以**纯 Java 名字视图**给出。
     *
     * <p>🔖 为什么这里先是一个字符串再转 {@code GpuFormat}（看着像多余的间接）：
     * 本项目的单测源集里<b>没有</b> renderpearl 类路径（{@code build.gradle} 的 dependencies
     * 只往 {@code testImplementation} 加了 junit 与 joml；本轮实测：测试里 import
     * {@code com.mojang.renderpearl.api.GpuFormat} = 「package does not exist」），
     * 而「选哪个格式」恰恰是本轮唯一必须能被离线证死的决定（选错 = 附件位拿不到
     * COLOR_ATTACHMENT_BIT，或 AO 读到条带）。名字视图两侧同源：{@link #proxyFormat()}
     * 用它，单测也用它，<b>不存在第二个真源</b>。
     *
     * <p>🔖 为什么是 R32F 而不是 RGBA8：代理要喂 AO 与边缘检测，1/255 的量化台阶会把深度梯度
     * 直接啃成条带；R32F 是 32 位浮点、单通道，正是「一个 z 值」的形状，且在
     * {@code VulkanConst.toVk} 的表里有映射（{@code R32_FLOAT -> 100}，不是未支持项）。
     * 🔶 若要换 RGBA16F/RGBA32F：<b>只改这一行</b> —— 管线的 {@code ColorTargetState} 与
     * {@link #ensure} 里的 {@code createTexture} 都从这里取值，改一即改双；
     * FrontendRenderPass.java:119-121 那条格式恒等核对就是这么被满足的。
     */
    public static String proxyFormatName() {
        return "R32_FLOAT";
    }

    /** 格式枚举本体（唯一真源是 {@link #proxyFormatName()}，这里只做一次翻译）。 */
    public static GpuFormat proxyFormat() {
        return GpuFormat.valueOf(proxyFormatName());
    }

    /** COPY_DST|COPY_SRC|TEXTURE_BINDING|RENDER_ATTACHMENT = 15（与 {@code ColortexPool.java:29-30} 同值）。 */
    public static int proxyUsage() {
        return GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC
                | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
    }

    /** 引擎反向 Z → GL 口径（{@code z_gl = 1 − z_en}；代数证明见 GAP-022 的「由上面推出的正确换算」行）。 */
    public static float toGlDepth(float engineDepth) {
        return 1.0F - engineDepth;
    }

    /** OF 的 {@code depthtex<N>} 家族名（别的名字不算 —— 代理不该顶替 shadowtex*）。 */
    public static boolean isDepthTextureName(String samplerName) {
        return samplerName != null && samplerName.startsWith("depthtex");
    }

    /**
     * 决策：这个采样名该绑哪一张深度图。<b>纯函数</b>：不读配置、不碰 GPU。
     *
     * <p>🔖 为什么 {@code depthtex1/2} 也一起给代理：今天这三个名字绑的就是<b>同一张</b>引擎深度
     * （GAP-023 已单独登记），包里所有「比较两个深度层」的逻辑恒等失效。只翻 0 号会让
     * {@code composite.glsl:333 z1 > z0} 从「恒假」变成「拿反向 Z 跟 GL 口径比」——
     * 一个谁也没测过的新数值关系，会把 GAP-023 的 A/B 一起污染。
     * 三名同源翻 = 约定变了、层间恒等这件事<b>不变</b>，GAP-023 仍是那条独立缺陷。
     */
    public static DepthSource chooseDepthSource(String samplerName, boolean proxyEnabled,
            boolean proxyPresent, boolean engineDepthPresent) {
        if (!isDepthTextureName(samplerName)) {
            return DepthSource.PLACEHOLDER;
        }
        if (proxyEnabled && proxyPresent) {
            return DepthSource.PROXY;
        }
        return engineDepthPresent ? DepthSource.ENGINE_REVERSED_Z : DepthSource.PLACEHOLDER;
    }

    /**
     * 决策：本帧要不要跑那趟翻转 pass。<b>纯函数</b>。
     *
     * <p>四个前提缺一不可；缺了哪一个由 {@link #missingReason} 指名道姓打出来
     * （一条 WARN 含三件事 = 等于什么都没说，X11）。
     */
    public static boolean shouldRenderPass(boolean proxyEnabled, boolean proxyPresent,
            boolean engineDepthPresent, boolean pipelineCompiled) {
        return proxyEnabled && proxyPresent && engineDepthPresent && pipelineCompiled;
    }

    // ── 资源本体（渲染线程独占，无锁） ─────────────────────────────────────────────

    private static @Nullable GpuTexture texture;
    private static @Nullable GpuTextureView view;
    private static int width;
    private static int height;

    /**
     * 懒建 / 随主目标尺寸重建代理纹理（尺寸变了整体重建，与 {@code ColortexPool} 同构）。
     *
     * <p>🔴 必须在<b>开任何 pass 之前</b>调（h10 实测规则：pass 开着时新建资源会撞到
     * “Close the existing render pass before creating a new one!”）。
     * <p>🔖 建失败（抛）时不留半个状态：{@code view} 仍是 null ⇒ 下一帧重新建
     * （h33 的教训就是「建到一半 return，此后永远不补建」）。
     */
    public static void ensure(int targetWidth, int targetHeight) {
        if (view != null && width == targetWidth && height == targetHeight) {
            return;
        }
        if (targetWidth <= 0 || targetHeight <= 0) {
            reportMissingOnce("主目标尺寸非法 " + targetWidth + "x" + targetHeight);
            return;
        }
        release();
        GpuTexture created = RenderSystem.getDevice().createTexture(
                () -> "vkdisp depth gl proxy", proxyUsage(), proxyFormat(),
                targetWidth, targetHeight, 1, 1);
        texture = created;
        view = RenderSystem.getDevice().createTextureView(created);
        width = targetWidth;
        height = targetHeight;
        if (!createdLogged) {
            createdLogged = true;
            VkDisp.LOGGER.info("vkdisp: [GAP-022] depth GL proxy texture created: {}x{} format={} usage={}"
                            + " (one fullscreen flip pass per frame)",
                    targetWidth, targetHeight, proxyFormat(), proxyUsage());
        }
    }

    /** 代理视图（= 链里 depthtex* 的绑定源）；未建返回 {@code null}。 */
    @Nullable
    public static GpuTextureView view() {
        return view;
    }

    /** 代理是否在场（决策与自报都读它，字段本身不外露）。 */
    public static boolean hasProxy() {
        return view != null;
    }

    /** 放掉代理（开关关掉时不留一张「还可能被误绑」的旧图；未建 = 空操作）。 */
    public static void release() {
        GpuTextureView oldView = view;
        GpuTexture oldTexture = texture;
        view = null;
        texture = null;
        width = 0;
        height = 0;
        if (oldView != null) {
            oldView.close();
        }
        if (oldTexture != null) {
            oldTexture.close();
        }
    }

    /** 已注册且已编译时给出管线，否则 {@code null}（注册在启动期、编译在资源重载期 —— 两种缺席都算「没编」）。 */
    @Nullable
    private static CompiledRenderPipeline flipPipelineOrNull() {
        if (!PipelineApi.isDepthProxyPipelineRegistered()) {
            return null;
        }
        return RenderSystem.getCompiledPipelineNullable(PipelineApi.depthProxyPipeline());
    }

    /**
     * 跑那一趟全屏翻转 pass：采引擎 gbuffer 深度 → 写 {@code 1 − z} 进代理。
     *
     * <p>🔖 本 pass 的采样用 <b>NEAREST</b>：非比较采样器采 depth aspect 时滤波必须是 NEAREST
     * （Vulkan 规范里采样器/图像的采样有效用法条；本机没有 validation layer ⇒ 这条是规范条文，
     * 不是本机实测）。用 NEAREST 两种情形都不会错，而且深度本来就不该被线性插值。
     * 链那侧读代理时用的仍是链采样器（LINEAR）—— 代理是普通颜色浮点图，没有这个约束。
     */
    public static void renderFlipPass(CommandEncoder encoder, String label,
            @Nullable GpuTextureView engineDepth) {
        boolean enabled = VkDispConfig.MRT_DEPTH_GL_PROXY.get();
        CompiledRenderPipeline pipeline = flipPipelineOrNull();
        boolean engineReady = engineDepth != null;
        if (!shouldRenderPass(enabled, hasProxy(), engineReady, pipeline != null)) {
            if (enabled) {
                reportMissingOnce(missingReason(hasProxy(), engineReady, pipeline != null));
            }
            return;
        }
        drawFlip(encoder, label, engineDepth, pipeline);
    }

    /** 真正的绘制（拆出来只为让上面那半保持「判据一眼读完」）。 */
    private static void drawFlip(CommandEncoder encoder, String label,
            GpuTextureView engineDepth, CompiledRenderPipeline pipeline) {
        GpuTextureView proxyView = view;
        if (proxyView == null) {
            reportMissingOnce("判据通过后代理视图为 null（同一帧内被 release）");
            return;
        }
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        // 清 0 = 故意的：GL 口径里 0.0 是「贴脸」而不是天空 ⇒ 真没写进去的像素会显形成
        // 一片近处几何，而不是伪装成「到处是天空」这种看起来像没生效的样子。
        try (RenderPass pass = encoder.createRenderPass(
                () -> label + " depthGlProxy (1 - engineDepth)",
                proxyView, Optional.of(new Vector4f(0.0F, 0.0F, 0.0F, 1.0F)),
                null, OptionalDouble.empty())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform(PipelineApi.SAMPLER_UNIFORM, engineDepth, nearest);
            pass.draw(3, 1, 0, 0);
        }
        if (!executedLogged) {
            executedLogged = true;
            // 🔖 把映射的**两个端点**打进同一行：取证时这一行的作用是「代理语义声明」——
            //   没有它，「天空到底是 1.0 还是 0.0」这件事只能靠再去读一遍 GLSL 才能确定，
            //   而一臂跑了几百帧之后没人会去读（QD-02 那一族：判据要出现在读数现场）。
            //   这也顺手让 {@link #toGlDepth} 有真实消费点，而不是「测试里才用的镜像」。
            VkDisp.LOGGER.info("vkdisp: [GAP-022] depth GL proxy flip pass executed:"
                    + " depthtex* 改绑 z_gl = 1 - z_en（引擎 {} → GL {} = 天空；引擎 {} → GL {} = 近平面）",
                    0.0F, toGlDepth(0.0F), 1.0F, toGlDepth(1.0F));
        }
    }

    /**
     * 链里某个 {@code depthtex*} 名字 → 视图（决策 + 缺席自报；占位也必须是<b>显式</b>占位）。
     *
     * <p>🔴 开关开着而代理不在 ⇒ 一定打一行 WARN（一次性），此时绑过去的是反向 Z 原图。
     * 「静默换绑成另一个东西」正是 GAP-022 最难查的形态：画面看着像生效了，读到的还是原图。
     */
    public static GpuTextureView chainDepthView(String samplerName,
            @Nullable GpuTextureView engineDepth, GpuTextureView fallbackView) {
        boolean enabled = VkDispConfig.MRT_DEPTH_GL_PROXY.get();
        DepthSource source = chooseDepthSource(samplerName, enabled, hasProxy(), engineDepth != null);
        if (source == DepthSource.PROXY && view != null) {
            return view;
        }
        if (source == DepthSource.ENGINE_REVERSED_Z && engineDepth != null) {
            if (enabled) {
                reportMissingOnce("代理缺席 ⇒ 本帧 depthtex* 仍是引擎反向 Z");
            }
            return engineDepth;
        }
        return fallbackView;
    }

    /** 缺了哪个前提就说哪个（一条 WARN 里同时写「代理有没有、深度有没有、管线编没编」）。 */
    private static String missingReason(boolean proxyPresent, boolean engineDepthPresent,
            boolean pipelineCompiled) {
        if (!proxyPresent) {
            return "代理纹理未建（ensure 没跑到 / 主目标尺寸非法）";
        }
        if (!engineDepthPresent) {
            return "MrtTerrainPass.depthView()=null（地形 MRT pass 的深度没建出来）";
        }
        return pipelineCompiled ? "?" : "vkdisp:pipeline/depth_gl_proxy 未注册或未编译";
    }

    /** 前置条件不满足的一次性自报（热路径日志 I/O 纪律：每帧一条就是 h34 那 499 行）。 */
    public static void reportMissingOnce(String detail) {
        if (missingWarned) {
            return;
        }
        missingWarned = true;
        VkDisp.LOGGER.warn("vkdisp: [GAP-022] mrt.depthGlProxy=true 但深度代理没跑成 —— 原因={}。"
                + " 后果：链里 depthtex* 读到的仍是反向 Z（天空=0.0），包的 isSky / hand 分支照旧走错。"
                + "（本行只打一次）", detail);
    }

    /** 「代理纹理已建」自报一次（热路径 I/O 纪律，同 mipGenLogged 那条线）。 */
    private static boolean createdLogged;

    /** 「翻转 pass 真的执行过」自报一次 —— 日志里区分「跑了」与「静默跳过」的唯一判据。 */
    private static boolean executedLogged;

    /** 「开关开着但代理缺席」自报一次。 */
    private static boolean missingWarned;

    private DepthGlProxy() {
    }
}
