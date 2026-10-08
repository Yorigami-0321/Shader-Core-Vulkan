package dev.vkdisp.bridge;
/**
 * 【参考调研】P0.3 全屏管线注册 / 原版 renderpearl 管线 API + NeoForge 注册事件
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 原版 Minecraft 26.3 客户端 com.mojang.renderpearl.api.pipeline.* 与
 *    net.minecraft.client.renderer.RenderPipelines（运行平台与官方 API 提供方），
 *    以及 NeoForge 26.3.0.23-beta 的 RegisterRenderPipelinesEvent（官方注册事件）。
 *    许可证：Mojang EULA（原版）+ NeoForge LGPL-2.1（事件定义）→ 只观察 javap 签名与官方注册用法
 *    （原版 RenderPipelines.java 的 snippet/注册写法、NeoForgeRenderPipelines 的订阅写法），零源码文本搬运；
 *    参考模组（VulkanMod / Sulkan 等）零接触。
 *    → 能否并入本项目（MIT）：可以 —— 本文件是独立实现的薄封装，只调用公开 API，不含被参考方代码
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：原版 RenderPipelines.POST_PROCESSING_SNIPPET（343-345 行：基于 GLOBALS_SNIPPET +
 *    PrimitiveTopology.TRIANGLES，自带 Globals UBO 布局、无顶点绑定）+ 原版 TRACY_BLIT / ENTITY_OUTLINE_BLIT
 *    （1150-1169 行：withVertexShader/withFragmentShader/withColorTargetState 的官方注册范本）；
 *    NeoForge 侧范本 = NeoForgeRenderPipelines（@EventBusSubscriber(Dist.CLIENT) + event.registerPipeline）。
 * 2. 备选：FrameGraphSetupEvent 帧图插 pass —— 调研否决（vanilla clear pass 会随后全清 main target，
 *    图案必被抹掉），本任务不采用；两条路径都是官方 API，无需 GAP 登记。
 * 1b.（P1.1 补充）自定义 uniform 块：BindGroupLayout.builder().withUniform(name, UNIFORM_BUFFER) 的官方用法
 *    （对照原版 BindGroupLayouts 的 GLOBALS = withUniform("Globals", UNIFORM_BUFFER)）；
 *    GLSL 侧块名必须与此处 uniform 名一致，无显式 binding 序号。
 * 3. 我们的差异点：RenderPipeline 的构造与注册整段收在本 bridge 类内，业务包只接触
 *    FULLSCREEN_LOCATION 字符串常量与 registerFullscreenPipeline(...) 调用，零 com.mojang.renderpearl import；
 *    注册成功的管线实例暂存于本类，供 bridge/FrameApi 绘制时取用（业务层拿不到原版类型）；
 *    注册异常不在本类吞掉，原样抛给业务层打 ERROR 原文。
 * 4. 许可证核对：本项目 MIT；只调用公开 API 签名，无代码复制（07-CONSTRAINTS §〇 P1、L5-L8）。
 * 5. 性能基线：启动期一次性注册，冷路径，不做性能优化（17-NATIVE.md §3.2）。
 */
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.util.List;
import dev.vkdisp.VkDisp;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

/**
 * 原版管线 API 唯一入口（06-MIGRATION.md §2.1 的 bridge 红线，07-CONSTRAINTS T5）。
 *
 * <p>本类是允许 import {@code com.mojang.renderpearl.*} 的 bridge 封装之一；
 * 向业务包只暴露字符串常量与无原版类型的方法，隔离原版类型变化。
 */
public final class PipelineApi {
    /** 全屏管线 location（纯字符串视图，业务包用于埋点断言）。 */
    public static final String FULLSCREEN_LOCATION = "vkdisp:pipeline/fullscreen";

    /**
     * P1.1：全屏管线的自定义 uniform 块名（纯字符串视图，业务包用于埋点断言）。
     *
     * <p>与 {@code fullscreen.fsh} 的 {@code layout(std140) uniform VkDispParams { vec4 Params; };}
     * 同名 —— 原版约定是「绑定布局里的 uniform 名 == GLSL 块名」（对照原版 {@code clouds.vsh} 的
     * {@code layout(std140) uniform CloudInfo} 写法，无显式 binding 序号）。
     */
    public static final String PARAMS_UNIFORM = "VkDispParams";

    /**
     * 全屏管线的采样器 uniform 名（纯字符串视图）。
     *
     * <p>与原版 {@code BindGroupLayouts.IN_SAMPLER} 完全同构（字节码核实：
     * {@code BindGroupLayout.builder().withUniform("InSampler", COMBINED_IMAGE_SAMPLER).build()}），
     * GLSL 侧对应 {@code uniform sampler2D InSampler;}（原版 {@code core/blit_depth.fsh} 写法）。
     * 采样器单独成组、不与 uniform 块混用，与原版 Globals/Sampler0 分组方式一致。
     */
    public static final String SAMPLER_UNIFORM = "InSampler";

    /** 传递/合成管线 location（P2 前置：把离屏渲染目标采样进主目标）。 */
    public static final String BLIT_LOCATION = "vkdisp:pipeline/blit";

    /** 合成管线 location（P2 前置：中间目标 → 中间目标，多目标 ping-pong 的中间级）。 */
    public static final String COMPOSITE_LOCATION = "vkdisp:pipeline/composite";

    /**
     * GAP-022 ① 深度代理 location（采引擎 gbuffer 深度 → 写 {@code 1 − z} 的 R32F 单通道图）。
     *
     * <p>它**必须**是独立的一条管线而不是复用 {@link #BLIT_LOCATION}：
     * {@code FrontendRenderPass.java:119-121} 逐附件核对「管线声明的 ColorTargetState 格式
     * == 附件纹理格式」，而 blit 的附件是 {@code ColorTargetState.DEFAULT}（= RGBA8_UNORM）。
     * 把 R32F 视图挂到 blit 上 = 当场抛格式不匹配 ⇒ 整条链每帧死一次（h33 那族的形状）。
     */
    public static final String DEPTH_PROXY_LOCATION = "vkdisp:pipeline/depth_gl_proxy";

    /** P3.2 场景合成管线 location（纯字符串视图，错误信息用）。 */
    public static final String COMPOSITE_SCENE_LOCATION = "vkdisp:pipeline/composite_scene";

    /** P3.3 deferred 步管线 location（纯字符串视图，错误信息用）。 */
    public static final String DEFERRED_LOCATION = "vkdisp:pipeline/deferred";

    /** P4.1.4 final 步管线 location（纯字符串视图，错误信息用）。 */
    public static final String FINAL_LOCATION = "vkdisp:pipeline/final";

    /**
     * P2.4：合成管线的内建 uniform 块名（纯字符串视图）。
     *
     * <p>包 composite 源经 D 线转译后必带 {@code layout(std140) uniform VkDispBuiltins { … };}
     * （P2.3 实测终态）——绑定布局必须声明它（未被片元引用的布局条目合法：
     * 反例对照 {@code blit.fsh} 不声明 Globals 但布局带 Globals，实测可绘制），
     * 否则「片元声明了布局没有的块」这一方向未实测过（X9 不猜）。
     */
    public static final String BUILTINS_UNIFORM = "VkDispBuiltins";

    /**
     * P4.1.2：包片元自由 sampler 注册清单（驱动层反射门控）。
     *
     * <p>取证（javap {@code PipelineBuilder.generateBackendCreateInfo}，:277 抛点）：SPIR-V
     * 反射出的每个 descriptor 名都必须能在 {@code flattenUniforms(绑定组)} 里查到，查不到即
     * {@code Unable to find shader defined uniform (名字)}。P4.1 收编后包片元块外只剩透明
     * sampler，因此库存包 composite/deferred 的 sampler 必须逐一注册；反向（布局条目多于
     * SPIR-V）字节码无校验 —— 多注册无害（fixture/blit 不声明 Globals 仍可绘制的既有实测）。
     *
     * <p>清单来源 = 库存 BSL_v10.1.8 的 world0/composite + world0/deferred include 闭包
     * 实测（18 名，run/shaderpacks/BSL_v10.1.8.zip 程序化扫描；colortex/depthtex/noisetex
     * 等命名与 OptiFine 官方 Uniforms 表同源 —— UniformDecl 的既有参考口径）。换包扩充按
     * 同法闭包扫描（P4.2 切包回归登记）。
     *
     * <p>draw 侧实测（P4.1.2 首轮 runClient，javap {@code FrontendRenderPass.validateDraw}
     * :553 取证）：STRICT_VALIDATION 下 {@code draw()} 传空排除集 → **布局每个条目都必须
     * setUniform**，缺即 {@code Missing uniform 名 (should be 类型)}；COMBINED_IMAGE_SAMPLER
     * 的值须为未关闭的 TextureViewAndSampler（视图 usage 含采样位）。故 {@link
     * #setPackSamplerUniforms} 一并绑定，见该方法 javadoc 的占位口径。
     */
    private static final String[] PACK_FRAGMENT_SAMPLERS = {
            "colortex0", "colortex1", "colortex6", "colortex8", "colortex9",
            "depthtex0", "depthtex1", "noisetex",
            "shadowcolor0", "shadowtex0", "shadowtex1",
            "gaux1", "lighttex0", "lighttex1",
            "vxDepthTexOpaque", "vxDepthTexTrans",
            "dhDepthTex0", "dhDepthTex1",
    };

    /**
     * P4.1.2 draw 侧：把 {@link #PACK_FRAGMENT_SAMPLERS} 全部 setUniform（按名分视图）。
     *
     * <p>必须在包片元管线（composite / composite_scene / deferred）{@code draw()} 之前调用
     * —— validateDraw 按**布局**逐条校验（不是按 SPIR-V 引用），缺一条即抛（首条缺的是
     * colortex0，实测 15k+ 次/帧）。
     *
     * <p>视图映射（OF 合成语义，BSL 源实测定的口径）：
     * <ul>
     *   <li>{@code colortex0} := {@code colorView} —— OF 里 composite 的彩色主输入；
     *       deferred 的 {@code DRAWBUFFERS:4} **不写 0 号**，场景色跨 deferred 步不变
     *       （首跑把 colortex0 绑成 deferred 输出 = AO/NaN 缓冲 → composite 读 color
     *       全黑，p412_world.png 实测根因）；</li>
     *   <li>{@code gaux1} := {@code auxView} —— OF 身份 {@code gaux1 = colortex4}
     *       = deferred 的输出缓冲（我们单输出链里 deferred 的 AO 就落在这里）；</li>
     *   <li>其余 16 名（含 lighttex0/1 sampler3D、shadowtex0/1 sampler2DShadow，
     *       布局均登记 COMBINED_IMAGE_SAMPLER） := {@code colorView} 占位 —— 采到何值不
     *       承诺（真值随 OfUniformManager 上传链 / MRT 后补，18-PARALLEL §5 P4.1 ⑤）；
     *       驱动层对维数/比较采样若报错，按实测原文迭代（X9 不猜）。</li>
     * </ul>
     *
     * @param pass 当前 render pass（包片元管线已 setPipeline）
     * @param colorView 彩色主输入视图（非 null）
     * @param auxView 辅助缓冲视图（非 null；无 deferred 链时与 colorView 同源）
     * @param sampler 原版 clamp-to-edge 采样器（与 InSampler 同一个）
     */
    static void setPackSamplerUniforms(
            com.mojang.renderpearl.api.commands.RenderPass pass,
            com.mojang.renderpearl.api.textures.GpuTextureView colorView,
            com.mojang.renderpearl.api.textures.GpuTextureView auxView,
            com.mojang.renderpearl.api.textures.GpuSampler sampler) {
        for (String name : PACK_FRAGMENT_SAMPLERS) {
            // colortex0 与其余 16 名 → colorView；仅 gaux1 → auxView（OF 身份 colortex4）。
            com.mojang.renderpearl.api.textures.GpuTextureView view =
                    name.equals("gaux1") ? auxView : colorView;
            pass.setUniform(name, view, sampler);
        }
    }

    /**
     * 包片元管线绑定组：{@link #BUILTINS_UNIFORM} + {@link #SAMPLER_UNIFORM} + {@link #PACK_FRAGMENT_SAMPLERS}。
     *
     * <p>composite / composite_scene / deferred 三条管线共用（片元同源 = 虚拟包
     * {@code vkdisp_pack:composite} / {@code :deferred}）。组内顺序沿 P-1f ②：
     * 内建块在源中最靠前 → 块在前、采样器随后。
     */
    private static BindGroupLayout packFragmentLayout() {
        BindGroupLayout.Builder builder = BindGroupLayout.builder()
                .withUniform(BUILTINS_UNIFORM, UniformType.UNIFORM_BUFFER)
                .withUniform(SAMPLER_UNIFORM, UniformType.COMBINED_IMAGE_SAMPLER);
        for (String name : PACK_FRAGMENT_SAMPLERS) {
            builder = builder.withUniform(name, UniformType.COMBINED_IMAGE_SAMPLER);
        }
        return builder.build();
    }

    /** 深度可视化管线 location（P3 前置：采样深度纹理 → 灰度输出，用于验证深度附件链路）。 */
    public static final String DEPTHVIS_LOCATION = "vkdisp:pipeline/depthviz";

    // ────────────────────────────────────────────────────────────────────────────────
    // 🔴 通用多 pass 后处理链（deferred* / composite* / final 全链执行）
    //
    // 与旧三步（composite/deferred/final 各一条）的根本区别：
    // ① **管线定长定宽**：MAX_POST_PASSES 条、每条 FRAME_WIDTH 个颜色目标 ——
    //    注册期一次性完成（注册事件资源重载时**不再触发**，任何「按包注册」都会漂移）；
    //    pass→slot 的映射与「哪些附件是真槽」全在**执行期**决定（未写的槽挂 scratch）。
    // ② **scratch 附件**：Vulkan 要求管线颜色目标数 == pass 附件数；定宽 8 之后，
    //    每个 pass 的附件表 = [写入槽按升序放前面] + [scratch 填满 8]。
    //    🔖 关键安全性质：被采样的 colortex **只出现在「未写」位置 ⇒ 永远不会同时是
    //    本 pass 的附件** —— 这正是 h26 那族「读写附件 + 采样器 = 静默 UB」的机制级封堵。
    // ────────────────────────────────────────────────────────────────────────────────

    /** 后处理槽位管线数（上界；BSL 实测链 10 步 < 16）。 */
    public static final int MAX_POST_PASSES = 16;

    /** 每个后处理管线的颜色目标数（= render pass 定宽附件数，见上方 ②）。 */
    public static final int POST_FRAME_WIDTH = dev.vkdisp.pack.PackPostChain.FRAME_WIDTH;

    /** 第 k 条后处理槽位管线的 location（k ∈ [0, MAX_POST_PASSES)）。 */
    public static String postLocation(int slot) {
        return "vkdisp:pipeline/post" + slot;
    }

    /** 后处理片元命名空间（= 虚拟包）。 */
    public static final String NAMESPACE_POST = "vkdisp_pack";

    /** 第 k 条后处理片元的包内路径（相对 assets/）。 */
    public static String postPath(int slot) {
        return "shaders/post" + slot + ".fsh";
    }

    /**
     * 后处理绑定组的 sampler **超集** —— 单点真源在
     * {@link dev.vkdisp.pipeline.model.PostSamplerSuperset}（编排期与绑定期读同一份，
     * 不各抄一遍）。
     */
    public static final String[] POST_SAMPLER_SUPERSET =
            dev.vkdisp.pipeline.model.PostSamplerSuperset.NAMES.toArray(new String[0]);

    /** 某 sampler 名在当前帧该绑哪个视图 —— 由 bridge 内的执行器实现（业务层不实现）。 */
    interface PostSamplerViewResolver {
        /** 返回非 null（无真值时必须返回**显式的占位视图**，不许 null —— 同 h33 教训）。 */
        com.mojang.renderpearl.api.textures.GpuTextureView view(String samplerName);
    }

    /**
     * 把 {@link #POST_SAMPLER_SUPERSET} 全部 {@code setUniform}（按名经 resolver 取视图）。
     *
     * <p>必须在 {@code draw()} 之前逐条绑齐：STRICT_VALIDATION 下 validateDraw 按**布局**校验
     * （同 {@link #setPackSamplerUniforms} 的实测规则）；少绑一条 = 响亮抛 Missing uniform。
     */
    static void setPostSamplerUniforms(
            com.mojang.renderpearl.api.commands.RenderPass pass,
            PostSamplerViewResolver resolver,
            com.mojang.renderpearl.api.textures.GpuSampler sampler) {
        for (String name : POST_SAMPLER_SUPERSET) {
            com.mojang.renderpearl.api.textures.GpuTextureView view = resolver.view(name);
            if (view == null) {
                throw new IllegalStateException(
                        "vkdisp: post sampler '" + name + "' resolver 返回 null —— 占位也必须显式（h33 同族）");
            }
            pass.setUniform(name, view, sampler);
        }
    }

    /** 后处理槽位管线的绑定组：BUILTINS + InSampler + 超集 sampler。 */
    private static BindGroupLayout postBindGroupLayout() {
        BindGroupLayout.Builder builder = BindGroupLayout.builder()
                .withUniform(BUILTINS_UNIFORM, UniformType.UNIFORM_BUFFER)
                .withUniform(SAMPLER_UNIFORM, UniformType.COMBINED_IMAGE_SAMPLER);
        for (String name : POST_SAMPLER_SUPERSET) {
            builder = builder.withUniform(name, UniformType.COMBINED_IMAGE_SAMPLER);
        }
        return builder.build();
    }

    /** 已注册的后处理槽位管线（下标 = slot）。 */
    private static final RenderPipeline[] POST_PIPELINES = new RenderPipeline[MAX_POST_PASSES];

    /**
     * 注册全部 {@link #MAX_POST_PASSES} 条后处理槽位管线。
     *
     * <p>每条：顶点 = 不翻转 {@code vkdisp:fullscreen}（采样源恒为引擎自身行序的目标，见
     * {@link #MRT_VIEW_NOFLIP_LOCATION} javadoc 的取向规则）；片元 = 虚拟包第 k 槽源
     * （无包/短链时该槽 = 内置 passthrough ⇒ required 编译恒成立）；颜色目标 = 定宽 8。
     */
    public static void registerPostPipelines(RegisterRenderPipelinesEvent event) {
        for (int slot = 0; slot < MAX_POST_PASSES; slot++) {
            RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath(VkDisp.MOD_ID, "pipeline/post" + slot))
                    // 🔴 h46 首轮实测：BSL deferred1 的片元声明第 4 条输入（eastVec@location 3），
                    //   而 vkdisp:fullscreen 只输出 0..2 ⇒ required 管线链接失败**砸整次资源重载**。
                    //   ⇒ 顶点用**按该片元契约生成的适配层**（vkdisp_pack:postK 同 id 解析 .vsh/.fsh，
                    //     屏幕 uv 语义 + 其余零值逐条 WARN，见 PackPostVertexAdapter）。
                    .withVertexShader(dev.vkdisp.VkDispVirtualPack.postShaderId(slot))
                    .withFragmentShader(dev.vkdisp.VkDispVirtualPack.postShaderId(slot))
                    .withBindGroupLayout(postBindGroupLayout())
                    .withColorTargetStates(0, POST_FRAME_WIDTH - 1, () -> ColorTargetState.DEFAULT)
                    .build();
            event.registerPipeline(pipeline);
            POST_PIPELINES[slot] = pipeline;
            REGISTERED_PIPELINES.add(pipeline);
        }
        VkDisp.LOGGER.info(
                "vkdisp: post chain pipelines registered: {} slots x {} colorTargets (layout: builtins"
                        + " + InSampler + superset {} samplers; fragment = vkdisp_pack:shaders/postK.fsh)",
                MAX_POST_PASSES, POST_FRAME_WIDTH, POST_SAMPLER_SUPERSET.length);
    }

    /** 第 slot 条后处理管线（未注册时抛，与其它取用口径一致）。 */
    static RenderPipeline postPipeline(int slot) {
        RenderPipeline pipeline = POST_PIPELINES[slot];
        if (pipeline == null) {
            throw new IllegalStateException(
                    "vkdisp: post pipeline slot " + slot + " not registered yet");
        }
        return pipeline;
    }

    /** 后处理管线是否已注册（就绪判据用）。 */
    public static boolean arePostPipelinesRegistered() {
        return POST_PIPELINES[0] != null;
    }

    /**
     * GAP-003 多附件写入管线 location（能力验证件）。
     *
     * <p>与其它管线的关键差别：**唯一一条声明多个 {@code ColorTargetState} 的管线**
     * （{@code withColorTargetStates(0, N-1, …)}）。项目此前 15 条全是单附件，
     * 多附件通道从未被用过 —— 见 {@code MrtProbe} 的类注释。
     */
    public static final String MRT_LOCATION = "vkdisp:pipeline/mrt";

    /** GAP-003 多附件回读管线 location（把某个 colortex 显示到主目标）。 */
    public static final String MRT_VIEW_LOCATION = "vkdisp:pipeline/mrtview";

    /** MRT 着色器资源 id：vkdisp:mrt → assets/vkdisp/shaders/mrt.fsh（三路 layout(location=N) out）。 */
    private static final Identifier MRT_SHADER_ID =
            Identifier.fromNamespaceAndPath(VkDisp.MOD_ID, "mrt");

    /** MRT 回读着色器资源 id：vkdisp:mrtview → assets/vkdisp/shaders/mrtview.fsh。 */
    private static final Identifier MRT_VIEW_SHADER_ID =
            Identifier.fromNamespaceAndPath(VkDisp.MOD_ID, "mrtview");

    /**
     * 🔖 **不翻转**的回读管线 location。
     *
     * <p>🔖🔖 **为什么需要它（2026-10-03 实测得出，与既有注释相反）**：
     * 既有 P-1f 约定说「中间目标 → 主目标必须 1-v 翻转」，本项目的合成链照此实现。
     * 但那次翻转**补偿的是「包 composite 片元的 OF 原始 vUv 语义」**，不是引擎本身的取向。
     * 本管线采样的是**引擎自己渲染出来的 colortex**（与主目标同一取向）
     * ⇒ 再翻一次就等于**把画面上下颠倒**。
     *
     * <p>实测证据：同一相机（y=90、pitch=0 地平线、正午）下
     * ① 用翻转版回读 ⇒ 地面跑到上半屏（上下颠倒）；
     * ② 让我方 pass 直接写主目标（不经回读）⇒ 地面正确在下半屏。
     * ⇒ 回读路径必须用**不翻转**顶点着色器。
     *
     * <p>⚠️ 这个 bug 之所以躲过 h02：那一轮回读的是**平滑渐变/常量指纹**，
     * 上下翻转在数据上**看不出来**（R 通道指纹与 V 无关）。是地形这种有明确上下关系的
     * 内容才把它暴露出来 —— 教训：**判据内容必须能区分被测的那���性**。
     */
    public static final String MRT_VIEW_NOFLIP_LOCATION = "vkdisp:pipeline/mrtview_noflip";

    /** MRT 管线 id。 */
    private static final Identifier MRT_PIPELINE_ID =
            Identifier.fromNamespaceAndPath(VkDisp.MOD_ID, "pipeline/mrt");

    /** MRT 回读管线 id。 */
    private static final Identifier MRT_VIEW_PIPELINE_ID =
            Identifier.fromNamespaceAndPath(VkDisp.MOD_ID, "pipeline/mrtview");

    /** 几何管线 location（P3 前置：真实顶点缓冲 + 深度剔除验证；本管线渲染阴影贴图）。 */
    public static final String GEOMETRY_LOCATION = "vkdisp:pipeline/geometry";

    /** 阴影采样管线 location（P3.3：世界视图渲染 + 采样阴影贴图）。 */
    public static final String SHADOWED_LOCATION = "vkdisp:pipeline/shadowed";

    /** 透视相机矩阵 uniform 名（P3.2/P3.3：与 geometry.vsh 世界视图分支的 std140 块字面一致）。 */
    public static final String CAMERA_UNIFORM = "Camera";

    /** 光空间矩阵 uniform 名（P3.1 前置：与 geometry.vsh 的 std140 块字面一致）。 */
    public static final String LIGHT_MATRIX_UNIFORM = "LightMatrix";

    /** 光空间矩阵 uniform 名（必须与 GLSL 声明字面一致，04-SPEC §4）。 */
    public static final String POSITION_ATTRIBUTE = "Position";
    /** 顶点色属性名。 */
    public static final String COLOR_ATTRIBUTE = "Color";

    /** 管线 location：vkdisp:pipeline/fullscreen → 注册表键。 */
    private static final Identifier FULLSCREEN_PIPELINE_ID =
            Identifier.fromNamespaceAndPath(VkDisp.MOD_ID, "pipeline/fullscreen");

    /** 着色器资源 id：vkdisp:fullscreen → assets/vkdisp/shaders/fullscreen.vsh / .fsh。 */
    private static final Identifier FULLSCREEN_SHADER_ID =
            Identifier.fromNamespaceAndPath(VkDisp.MOD_ID, "fullscreen");

    /** 传递管线 location id。 */
    private static final Identifier BLIT_PIPELINE_ID =
            Identifier.fromNamespaceAndPath(VkDisp.MOD_ID, "pipeline/blit");

    /** 传递管线片元着色器 id：vkdisp:blit → assets/vkdisp/shaders/blit.fsh（顶点复用 fullscreen.vsh）。 */
    private static final Identifier BLIT_SHADER_ID =
            Identifier.fromNamespaceAndPath(VkDisp.MOD_ID, "blit");

    /** GAP-022 ① 深度代理管线 location id。 */
    private static final Identifier DEPTH_PROXY_PIPELINE_ID =
            Identifier.fromNamespaceAndPath(VkDisp.MOD_ID, "pipeline/depth_gl_proxy");

    /** 深度代理片元着色器 id：vkdisp:depth_gl_flip → assets/vkdisp/shaders/depth_gl_flip.fsh（顶点复用 fullscreen.vsh）。 */
    private static final Identifier DEPTH_PROXY_SHADER_ID =
            Identifier.fromNamespaceAndPath(VkDisp.MOD_ID, "depth_gl_flip");

    /** 合成管线 location id。 */
    private static final Identifier COMPOSITE_PIPELINE_ID =
            Identifier.fromNamespaceAndPath(VkDisp.MOD_ID, "pipeline/composite");

    /**
     * P3.2 场景合成管线 location id（与 {@link #COMPOSITE_PIPELINE_ID} 片元相同、顶点不同）。
     *
     * <p>为什么需要第二条：fixture 中间目标（我方 pass 写）与 vanilla 帧图目标（地形 pass 写）
     * 行序相反 —— Pass 3 对前者要 1-v 翻转（P-1f 实测），对后者**不翻转**（P3.2 首轮实测：
     * 用翻转版采 scene，方块边缘角度 = −yaw 符号 → 镜像，见 18-PARALLEL §5 P3.2 ④）。
     * 顶点是管线静态状态，无法按帧切换 → 两条管线按输入源选。
     */
    private static final Identifier COMPOSITE_SCENE_PIPELINE_ID =
            Identifier.fromNamespaceAndPath(VkDisp.MOD_ID, "pipeline/composite_scene");

    /**
     * P3.3 deferred 步管线 location id。
     *
     * <p>为什么顶点 **不翻转**（p416 方向矫正，推翻 P3.3 首版的「净翻转守恒」推导）：
     * 首版 flipv 的理由是「composite 固定 flipv（+1），deferred 也 +1 才能 1+1 ≡ 0」——
     * 但该推导把 deferred 当成了彩色链上的一跳；P4.1.2 已实测（绑 viewC 首跑全黑的根因）
     * OF 语义下 deferred 不改写 colortex0，链模式 composite 的**彩色主输入仍是场景色**，
     * deferred 只落 gaux1 辅助位 —— 它的 flipv 从不参与彩色净翻转，守恒算式不成立。
     * 顶点选择规则与 P3.2 同源：**采样源是 vanilla 场景 → 不翻转**（deferred 的
     * color/depthtex0 都绑场景），顺带修正 depth 配对（原 flipv 使行 y 写 AO 却读
     * scene 深度行 1−y）与 gaux1 对齐（deferred 输出行序 = 场景行序后，
     * composite 同一 texCoord 采 color/aux 才同行）。p416_run3 实测地平线回正。
     */
    private static final Identifier DEFERRED_PIPELINE_ID =
            Identifier.fromNamespaceAndPath(VkDisp.MOD_ID, "pipeline/deferred");

    /**
     * P4.1.4 final 步管线 location id。
     *
     * <p>为什么顶点**不翻转**（attachment 恒等拷贝推导，p416 复核后维持原判）：
     * final 的输入是 composite 刚写入的我方中间目标 —— 同一 composite pass 换附件不换
     * 光栅化（同 viewport、同帧缓冲位置），故该中间目标的 texel(x,y) 恒等于「composite
     * 直写 main」的 texel(x,y)；final 恒等采样拷回 → 显示与 composite 直写等价。
     * 该推导的前提 = **composite 写出的内容本身是正立行序** —— p416_run1（强制位姿
     * pitch=−15°）实测地平线 row 205（翻转预测 199 / 正立 403）证伪的不是本推导，
     * 而是上游 P3.3 链 composite 的 flipv 顶点（彩色源是场景却被 +1，净翻转守恒的
     * 算式算错了一跳）；p416_run2 以 final flipv 顶点对冲先行回正（纯 V 无 H：
     * run1↔run2 地面带互为垂直镜像 corr=+0.937，V∘H corr=−0.027），
     * 终版 B+ 把矫正落回源头（链 composite 换 composite_scene 不翻转 + deferred 不翻转），
     * final 恢复恒等身份（p416_run3 复测回正）。p414 证据「未镜像」判据基于云团构图、
     * 分辨不出上下，已在 p416 证据更正。
     */
    private static final Identifier FINAL_PIPELINE_ID =
            Identifier.fromNamespaceAndPath(VkDisp.MOD_ID, "pipeline/final");

    /**
     * 合成管线片元着色器 id：vkdisp_pack:composite → {@code assets/vkdisp_pack/shaders/composite.fsh}。
     *
     * <p>P2.4 起指向**虚拟资源包** {@code vkdisp_pack}（04-SPEC §2）——源由
     * {@code dev.vkdisp.VkDispVirtualPack} 在 openResources 时经冷路径生成
     * （库存包 composite + 选项覆盖，或内置 passthrough 兜底），原 mod 内
     * {@code assets/vkdisp/shaders/composite.fsh} 不再被管线引用。
     */
    private static final Identifier COMPOSITE_SHADER_ID =
            Identifier.fromNamespaceAndPath("vkdisp_pack", "composite");

    /**
     * P3.3 deferred 步片元着色器 id：vkdisp_pack:deferred → {@code assets/vkdisp_pack/shaders/deferred.fsh}。
     *
     * <p>与 composite 同款出自**虚拟资源包**：源 = 所选库存包的 deferred 片元（P3.3 ④），
     * 包无 deferred / 兜底路径 = 内置 passthrough（required 管线必须总有源可编）。
     */
    private static final Identifier DEFERRED_SHADER_ID =
            Identifier.fromNamespaceAndPath("vkdisp_pack", "deferred");

    /**
     * P4.1.4 final 步片元着色器 id：vkdisp_pack:final → {@code assets/vkdisp_pack/shaders/final.fsh}。
     *
     * <p>与 composite/deferred 同款出自**虚拟资源包**：源 = 所选库存包的 final 片元
     * （同包同维度配对），包无 final / 兜底路径 = 内置 passthrough（required 管线必须总有源可编）。
     */
    private static final Identifier FINAL_SHADER_ID =
            Identifier.fromNamespaceAndPath("vkdisp_pack", "final");

    /**
     * 合成管线顶点着色器 id：vkdisp:fullscreen_flipv → {@code assets/vkdisp/shaders/fullscreen_flipv.vsh}。
     *
     * <p>P2.4 把「中间目标 → 主目标」的 1-v 翻转从 blit 片元**上移**到本顶点（P-1f），
     * 包片元因此保持 OF 原语义（原始 vUv 采样）。
     */
    private static final Identifier FULLSCREEN_FLIPV_SHADER_ID =
            Identifier.fromNamespaceAndPath(VkDisp.MOD_ID, "fullscreen_flipv");

    /** 深度可视化管线 location id。 */
    private static final Identifier DEPTHVIS_PIPELINE_ID =
            Identifier.fromNamespaceAndPath(VkDisp.MOD_ID, "pipeline/depthviz");

    /** 深度可视化管线片元着色器 id：vkdisp:depthviz → assets/vkdisp/shaders/depthviz.fsh。 */
    private static final Identifier DEPTHVIS_SHADER_ID =
            Identifier.fromNamespaceAndPath(VkDisp.MOD_ID, "depthviz");

    /** 几何管线 location id。 */
    private static final Identifier GEOMETRY_PIPELINE_ID =
            Identifier.fromNamespaceAndPath(VkDisp.MOD_ID, "pipeline/geometry");

    /** 几何着色器 id：vkdisp:geometry → assets/vkdisp/shaders/geometry.vsh / .fsh。 */
    private static final Identifier GEOMETRY_SHADER_ID =
            Identifier.fromNamespaceAndPath(VkDisp.MOD_ID, "geometry");

    /** 阴影采样管线 location id。 */
    private static final Identifier SHADOWED_PIPELINE_ID =
            Identifier.fromNamespaceAndPath(VkDisp.MOD_ID, "pipeline/shadowed");

    /** 阴影采样片元着色器 id：vkdisp:shadowed → assets/vkdisp/shaders/shadowed.fsh。 */
    private static final Identifier SHADOWED_SHADER_ID =
            Identifier.fromNamespaceAndPath(VkDisp.MOD_ID, "shadowed");

    /** 注册成功后暂存的管线实例（供 FrameApi 使用）；未注册时为 null。 */
    private static RenderPipeline fullscreenPipeline;

    /** 注册成功后暂存的传递管线实例；未注册时为 null。 */
    private static RenderPipeline blitPipeline;

    /** GAP-022 ① 注册成功后暂存的深度代理管线实例；未注册时为 null。 */
    private static RenderPipeline depthProxyPipeline;

    /** 注册成功后暂存的合成管线实例；未注册时为 null。 */
    private static RenderPipeline compositePipeline;

    /** 注册成功后暂存的 P3.2 场景合成管线实例（无 v 翻转顶点）；未注册时为 null。 */
    private static RenderPipeline compositeScenePipeline;

    /** 注册成功后暂存的 P3.3 deferred 步管线实例；未注册时为 null。 */
    private static RenderPipeline deferredPipeline;

    /** 注册成功后暂存的 P4.1.4 final 步管线实例；未注册时为 null。 */
    private static RenderPipeline finalPipeline;

    /** 注册成功后暂存的深度可视化管线实例；未注册时为 null。 */
    private static RenderPipeline depthVisPipeline;

    /** 注册成功后暂存的几何管线实例；未注册时为 null。 */
    private static RenderPipeline geometryPipeline;

    /** 注册成功后暂存的阴影采样管线实例；未注册时为 null。 */
    private static RenderPipeline shadowedPipeline;

    /** 注册成功后暂存的 MRT 多附件写入管线实例；未注册时为 null。 */
    private static RenderPipeline mrtPipeline;

    /** 注册成功后暂存的 MRT 回读管线实例；未注册时为 null。 */
    private static RenderPipeline mrtViewPipeline;

    /** 不翻转版回读管线（字段）：采样引擎渲染出的离屏目标时必须用它。 */
    private static RenderPipeline mrtViewNoFlipPipeline;

    /**
     * 几何顶点格式：Position(vec3f) + Color(vec4f)，stride = 28 字节。
     *
     * <p>属性名与 {@code geometry.vsh} 的 {@code in} 声明**字面一致**（04-SPEC §4 的硬要求）；
     * 与 E 线 {@code VertexLayout} 的「紧凑累加、无隐式填充」规则一致（12 + 16 = 28）。
     */
    private static final VertexFormat GEOMETRY_VERTEX_FORMAT = VertexFormat.builder(0)
            .addAttribute(POSITION_ATTRIBUTE, GpuFormat.RGB32_FLOAT)
            .addAttribute(COLOR_ATTRIBUTE, GpuFormat.RGBA32_FLOAT)
            .build();

    // ⚠️ 实测教训：VertexFormat.builder(int) 的参数是 **stepRate**，不是顶点大小
    // （javap 反编译字段名 stepRate；原版 DefaultVertexFormat 一律传 0）。
    // 曾误传 28（按字节数理解），导致属性按每 28 顶点推进一次 → 读出错误偏移 →
    // 画出退化三角形，且**没有任何报错**（静默失败的典型样本）。
    // 顶点大小由 addAttribute 累加得出（Position 12 + Color 16 = 28），不需要在此声明。

    /** 已注册管线集合（P1.2「注册数 == 编译成功数」断言的计数来源）。 */
    private static final List<RenderPipeline> REGISTERED_PIPELINES = new java.util.ArrayList<>();

    private PipelineApi() {}

    /**
     * 构建并注册 P0.3 全屏管线。
     *
     * <p>基于原版 {@code POST_PROCESSING_SNIPPET}：自带 Globals UBO 布局 + TRIANGLES 拓扑；
     * {@code build()} 强校验 location / vertex shader / fragment shader / topology 四项必须齐，
     * 缺任一项抛 {@link IllegalStateException}（由业务层打 ERROR 原文）。
     * 无顶点绑定 —— 全屏三角形由 {@code gl_VertexIndex} 推出，{@code draw(3,...)} 不需要顶点缓冲。
     */
    public static void registerFullscreenPipeline(RegisterRenderPipelinesEvent event) {
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                .withLocation(FULLSCREEN_PIPELINE_ID)
                .withVertexShader(FULLSCREEN_SHADER_ID)
                .withFragmentShader(FULLSCREEN_SHADER_ID)
                // P1.1：自定义 uniform 块（POST_PROCESSING_SNIPPET 已带 GLOBALS 布局，这里是第 2 组）。
                // P1.1：自定义 uniform 块（POST_PROCESSING_SNIPPET 已带 GLOBALS 布局）。
                // 实测约定（P-1f）：自定义 UBO 与 sampler 若同属一条管线，必须放同一绑定组且顺序与 GLSL 一致。
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withUniform(PARAMS_UNIFORM, UniformType.UNIFORM_BUFFER)
                        .build())
                .withColorTargetState(ColorTargetState.DEFAULT)
                // P3 前置：图案管线开深度测试 + 写深度（片段着色器写 gl_FragDepth 水平梯度）。
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
                .build();
        // 官方注册入口：required 管线，随原版 ShaderManager 一起编译（编译失败 = 整次资源重载失败，绝不静默）。
        event.registerPipeline(pipeline);
        fullscreenPipeline = pipeline;
        REGISTERED_PIPELINES.add(pipeline);
    }

    /** 已注册管线数（P1.2 计数对齐断言用；纯整数视图）。 */
    public static int registeredPipelineCount() {
        return REGISTERED_PIPELINES.size();
    }

    /**
     * H 线 M-01：把派生地形管线纳入「注册数 == 编译成功数」的计数口径。
     *
     * <p>为什么必须纳入（01-DEV-LOOP §5.1「计数对得上」）：M-01 的 6 条派生管线若编译失败，
     * 而它们不在计数里，断言就看不出问题 ⇒ 地形 draw 会安静地退回原版管线，
     * 变成**静默失败**（本项目头号坑）。纳入后「派生管线编译不过」会立刻打 ERROR。
     *
     * <p>包私有：只有同包的 {@link TerrainPipelineApi} 能调（T5 的 bridge 红线）。
     */
    static void recordTerrainDerived(RenderPipeline pipeline) {
        REGISTERED_PIPELINES.add(pipeline);
    }

    /**
     * 已注册管线列表（bridge 内部用，供 FrameApi 统计编译成功数）。
     * 用不可变快照返回，业务包拿不到原版类型。
     */
    static List<RenderPipeline> registeredPipelines() {
        return REGISTERED_PIPELINES;
    }

    /** 全屏管线是否已注册完成（纯布尔视图，业务包轮询用；未注册返回 false，不抛异常）。 */
    public static boolean isFullscreenPipelineRegistered() {
        return fullscreenPipeline != null;
    }

    /**
     * 构建并注册 P2 前置的传递管线（采样输入纹理 → 写目标）。
     *
     * <p>与图案管线的区别：只有采样器绑定（无自定义 UBO），片元着色器为 {@code vkdisp:blit}。
     * 顶点着色器复用 {@code vkdisp:fullscreen}（同一全屏三角形）。
     */
    public static void registerBlitPipeline(RegisterRenderPipelinesEvent event) {
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                .withLocation(BLIT_PIPELINE_ID)
                .withVertexShader(FULLSCREEN_SHADER_ID)
                .withFragmentShader(BLIT_SHADER_ID)
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withUniform(SAMPLER_UNIFORM, UniformType.COMBINED_IMAGE_SAMPLER)
                        .build())
                .withColorTargetState(ColorTargetState.DEFAULT)
                .build();
        event.registerPipeline(pipeline);
        blitPipeline = pipeline;
        REGISTERED_PIPELINES.add(pipeline);
    }

    /**
     * 构建并注册 GAP-022 ① 的深度代理管线（采 gbuffer 深度 → 写 {@code 1 − z}）。
     *
     * <p>形状 = {@link #registerBlitPipeline} 那一套（POST_PROCESSING_SNIPPET + 不翻转的
     * {@code vkdisp:fullscreen} 顶点 + 单条 {@code InSampler} 绑定组 + 单颜色目标），
     * **唯一**的差别是颜色目标格式：这里必须是 {@link DepthGlProxy#proxyFormat()}，
     * 因为 {@code FrontendRenderPass.java:119-121} 会把它和附件纹理的格式逐条对账，
     * 而 {@code VulkanRenderPipeline.java:286-293} 又把它烧进 VkPipeline
     * （两条一起决定了「管线格式 == 纹理格式」是硬约束，不是风格问题）。
     *
     * <p>写掩码取 {@code WRITE_RED}：R32F 只有 R 分量，G/B/A 位是空转；
     * 这里不用 {@code WRITE_ALL} 不是为了省带宽，是为了让「这是一张单通道图」这件事
     * 出现在源码里而不只出现在注释里。
     *
     * <p>无条件注册（required）：编译失败 = 整次资源重载失败，绝不静默（T11）；
     * 同时它进入 {@code registered==compiled} 的计数口径（{@link #REGISTERED_PIPELINES}）。
     */
    public static void registerDepthProxyPipeline(RegisterRenderPipelinesEvent event) {
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                .withLocation(DEPTH_PROXY_PIPELINE_ID)
                .withVertexShader(FULLSCREEN_SHADER_ID)
                .withFragmentShader(DEPTH_PROXY_SHADER_ID)
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withUniform(SAMPLER_UNIFORM, UniformType.COMBINED_IMAGE_SAMPLER)
                        .build())
                .withColorTargetState(new ColorTargetState(java.util.Optional.empty(),
                        DepthGlProxy.proxyFormat(), ColorTargetState.WRITE_RED))
                .build();
        event.registerPipeline(pipeline);
        depthProxyPipeline = pipeline;
        REGISTERED_PIPELINES.add(pipeline);
        VkDisp.LOGGER.info("vkdisp: [GAP-022] depth proxy pipeline registered: {}"
                        + " colorTarget={} vertex={} fragment={} (single target, format must equal the"
                        + " proxy texture format —— FrontendRenderPass.java:119-121)",
                DEPTH_PROXY_LOCATION, DepthGlProxy.proxyFormat(),
                FULLSCREEN_SHADER_ID, DEPTH_PROXY_SHADER_ID);
    }

    /**
     * 构建并注册合成管线（P2.4 起 = Pass 3 主链管线）。
     *
     * <p>片元 = 虚拟包 {@code vkdisp_pack:composite}（库存包源或内置兜底），
     * 顶点 = {@code vkdisp:fullscreen_flipv}（1-v 翻转上移到顶点，P-1f）。
     * 绑定组 = {@link #packFragmentLayout()}（P4.1.2 反射门控：BUILTINS + InSampler +
     * 18 包 sampler 超集），组内顺序与 P-1f ② 一致（块在源中最靠前 → 块在前）。
     */
    public static void registerCompositePipeline(RegisterRenderPipelinesEvent event) {
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                .withLocation(COMPOSITE_PIPELINE_ID)
                .withVertexShader(FULLSCREEN_FLIPV_SHADER_ID)
                .withFragmentShader(COMPOSITE_SHADER_ID)
                .withBindGroupLayout(packFragmentLayout())
                .withColorTargetState(ColorTargetState.DEFAULT)
                .build();
        event.registerPipeline(pipeline);
        compositePipeline = pipeline;
        REGISTERED_PIPELINES.add(pipeline);
        // 一次性埋点：P2.4 链接线换血可见（片元命名空间 / 顶点翻转版）。
        VkDisp.LOGGER.info(
                "vkdisp: composite pipeline wired to pack shader: fragment={} vertex={} builtins+sampler same group",
                COMPOSITE_SHADER_ID, FULLSCREEN_FLIPV_SHADER_ID);
    }

    /**
     * 构建并注册 P3.2 场景合成管线（{@link #COMPOSITE_SCENE_LOCATION}）。
     *
     * <p>与 {@link #registerCompositePipeline} 唯一差别 = 顶点用不翻转的 {@code vkdisp:fullscreen}：
     * scene 是 vanilla 帧图目标（P3.2 首轮实测用翻转顶点采样出镜像）；fixture 是我方中间目标
     * （P-1f 实测需翻转）。片元/绑定组完全一致，按输入源在 FrameApi Pass 3 选择 ——
     * **含链模式**（p416 方向矫正后，链 composite 的彩色主输入 = 场景色，按采样源规则
     * 同样走不翻转顶点，见 {@link #DEFERRED_PIPELINE_ID} javadoc 的根因段）。
     */
    public static void registerCompositeScenePipeline(RegisterRenderPipelinesEvent event) {
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                .withLocation(COMPOSITE_SCENE_PIPELINE_ID)
                .withVertexShader(FULLSCREEN_SHADER_ID)
                .withFragmentShader(COMPOSITE_SHADER_ID)
                .withBindGroupLayout(packFragmentLayout())
                .withColorTargetState(ColorTargetState.DEFAULT)
                .build();
        event.registerPipeline(pipeline);
        compositeScenePipeline = pipeline;
        REGISTERED_PIPELINES.add(pipeline);
        VkDisp.LOGGER.info(
                "vkdisp: composite scene pipeline wired: fragment={} vertex={} (no v-flip; P3.2 scene input)",
                COMPOSITE_SHADER_ID, FULLSCREEN_SHADER_ID);
    }

    /**
     * 构建并注册 P3.3 deferred 步管线（{@link #DEFERRED_LOCATION}）。
     *
     * <p>片元 = 虚拟包 {@code vkdisp_pack:deferred}（包源或内置 passthrough），
     * 顶点 = {@code vkdisp:fullscreen}（**不翻转** —— 采样源是场景 ⇒ 同 P3.2 规则不翻转，
     * p416 推翻首版净翻转守恒推导，见 {@link #DEFERRED_PIPELINE_ID}），
     * 绑定组 = {@link #packFragmentLayout()}，与 composite 完全同款（D 线对所有阶段统一
     * 注入 + P4.1.2 反射门控超集 —— deferred 的 noisetex 反射名也在 18 名清单内）。
     * deferred 只在世界内执行，但管线**必须无条件注册**
     * （required：注册缺失会让 registered≠compiled 计数断言失败）。
     */
    public static void registerDeferredPipeline(RegisterRenderPipelinesEvent event) {
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                .withLocation(DEFERRED_PIPELINE_ID)
                .withVertexShader(FULLSCREEN_SHADER_ID)
                .withFragmentShader(DEFERRED_SHADER_ID)
                .withBindGroupLayout(packFragmentLayout())
                .withColorTargetState(ColorTargetState.DEFAULT)
                .build();
        event.registerPipeline(pipeline);
        deferredPipeline = pipeline;
        REGISTERED_PIPELINES.add(pipeline);
        VkDisp.LOGGER.info(
                "vkdisp: deferred pipeline wired: fragment={} vertex={} (P3.3 chain step; no v-flip by scene-source, p416)",
                DEFERRED_SHADER_ID, FULLSCREEN_SHADER_ID);
    }

    /**
     * 构建并注册 P4.1.4 final 步管线（{@link #FINAL_LOCATION}）。
     *
     * <p>片元 = 虚拟包 {@code vkdisp_pack:final}（包源或内置 passthrough），
     * 顶点 = {@code vkdisp:fullscreen}（**不翻转** —— attachment 恒等拷贝推导，
     * p416 复核维持，见 {@link #FINAL_PIPELINE_ID}），绑定组 = {@link #packFragmentLayout()}
     * （与 composite 同款超集）。final 只在包声明 final 片元时执行，但管线**必须无条件注册**
     * （required：注册缺失会让 registered≠compiled 计数断言失败）。
     */
    public static void registerFinalPipeline(RegisterRenderPipelinesEvent event) {
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                .withLocation(FINAL_PIPELINE_ID)
                .withVertexShader(FULLSCREEN_SHADER_ID)
                .withFragmentShader(FINAL_SHADER_ID)
                .withBindGroupLayout(packFragmentLayout())
                .withColorTargetState(ColorTargetState.DEFAULT)
                .build();
        event.registerPipeline(pipeline);
        finalPipeline = pipeline;
        REGISTERED_PIPELINES.add(pipeline);
        VkDisp.LOGGER.info(
                "vkdisp: final pipeline wired: fragment={} vertex={} (P4.1.4 final step; no v-flip by attachment-identity)",
                FINAL_SHADER_ID, FULLSCREEN_SHADER_ID);
    }

    /** P4.1.4 final 步管线是否已注册完成（纯布尔视图）。 */
    public static boolean isFinalPipelineRegistered() {
        return finalPipeline != null;
    }

    /** P3.3 deferred 步管线是否已注册完成（纯布尔视图）。 */
    public static boolean isDeferredPipelineRegistered() {
        return deferredPipeline != null;
    }

    /** 合成管线是否已注册完成（纯布尔视图）。 */
    public static boolean isCompositePipelineRegistered() {
        return compositePipeline != null;
    }

    /** P3.2 场景合成管线是否已注册完成（纯布尔视图）。 */
    public static boolean isCompositeScenePipelineRegistered() {
        return compositeScenePipeline != null;
    }

    /**
     * 构建并注册 P3 前置的深度可视化管线（采样深度纹理 → 灰度）。
     *
     * <p>结构同传递管线（只有采样器绑定），片元为 {@code vkdisp:depthviz}；
     * 用于把「深度附件是否真被写入」变成可量化判读的图像（而非只看深度的存在性）。
     */
    public static void registerDepthVisPipeline(RegisterRenderPipelinesEvent event) {
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                .withLocation(DEPTHVIS_PIPELINE_ID)
                .withVertexShader(FULLSCREEN_SHADER_ID)
                .withFragmentShader(DEPTHVIS_SHADER_ID)
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withUniform(SAMPLER_UNIFORM, UniformType.COMBINED_IMAGE_SAMPLER)
                        .build())
                .withColorTargetState(ColorTargetState.DEFAULT)
                .build();
        event.registerPipeline(pipeline);
        depthVisPipeline = pipeline;
        REGISTERED_PIPELINES.add(pipeline);
    }

    /** 深度可视化管线是否已注册完成（纯布尔视图）。 */
    public static boolean isDepthVisPipelineRegistered() {
        return depthVisPipeline != null;
    }

    /**
     * 构建并注册 P3 前置的几何管线（真实顶点缓冲 + 深度测试/写入）。
     *
     * <p>与其它管线的区别：带**顶点绑定**（{@code withVertexBinding(0, GEOMETRY_VERTEX_FORMAT)}），
     * 片元不再是无绑定的全屏三角形，而是按顶点缓冲绘制；深度状态开测试 + 写深度。
     * 不剔除背面（{@code withCull(false)}），避免绕序问题干扰深度剔除的验证。
     */
    public static void registerGeometryPipeline(RegisterRenderPipelinesEvent event) {
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                .withLocation(GEOMETRY_PIPELINE_ID)
                .withVertexShader(GEOMETRY_SHADER_ID)
                .withFragmentShader(GEOMETRY_SHADER_ID)
                .withVertexBinding(0, GEOMETRY_VERTEX_FORMAT)
                // 本管线用于**渲染阴影贴图** → 让 geometry.vsh 走 SHADOW_MAP_PASS 分支（裁剪空间 = 光空间）。
                .withShaderDefine("SHADOW_MAP_PASS")
                // P3.1 前置：光空间矩阵 UBO（mat4，64B std140）。每帧由 FrameApi 上传。
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withUniform(LIGHT_MATRIX_UNIFORM, UniformType.UNIFORM_BUFFER)
                        .build())
                .withCull(false)
                // 深度测试 LESS_THAN_OR_EQUAL + 写深度：近的先画，远的后画；
                // 重叠区若保持红色 = 深度剔除生效（被后画的远片元被剔除）。
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
                .withColorTargetState(ColorTargetState.DEFAULT)
                .build();
        event.registerPipeline(pipeline);
        geometryPipeline = pipeline;
        REGISTERED_PIPELINES.add(pipeline);
        // 一次性埋点：确认顶点格式在 build() 后保留（getVertexFormatBindings 是定长槽位数组，
        // size 恒为 16，故打印槽 0 的 stride 才有意义）。
        VkDisp.LOGGER.info(
                "vkdisp: geometry pipeline registered: stride={} topology={}",
                GEOMETRY_VERTEX_FORMAT.getVertexSize(),
                pipeline.getPrimitiveTopology());
    }

    /** 几何管线是否已注册完成（纯布尔视图）。 */
    public static boolean isGeometryPipelineRegistered() {
        return geometryPipeline != null;
    }

    /**
     * 构建并注册 P3.3 的阴影采样管线（世界视图渲染 + 采样阴影贴图深度）。
     *
     * <p>与几何管线共用 `geometry.vsh`（**无** SHADOW_MAP_PASS define → 走相机视图分支），
     * 片元为 `shadowed.fsh`（世界坐标回投光空间、采样深度、受阴影者变暗）。
     * 只带采样器绑定 + 光空间矩阵 UBO；**无深度状态**（它渲染到无深度附件的目标）。
     */
    public static void registerShadowedPipeline(RegisterRenderPipelinesEvent event) {
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                .withLocation(SHADOWED_PIPELINE_ID)
                .withVertexShader(GEOMETRY_SHADER_ID)
                .withFragmentShader(SHADOWED_SHADER_ID)
                .withVertexBinding(0, GEOMETRY_VERTEX_FORMAT)
                // 单一绑定组：光空间矩阵（fsh 回投）+ 采样器（阴影贴图）+ 相机矩阵（vsh 世界视图）。
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withUniform(LIGHT_MATRIX_UNIFORM, UniformType.UNIFORM_BUFFER)
                        .withUniform(SAMPLER_UNIFORM, UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform(CAMERA_UNIFORM, UniformType.UNIFORM_BUFFER)
                        .build())
                .withCull(false)
                .withColorTargetState(ColorTargetState.DEFAULT)
                .build();
        event.registerPipeline(pipeline);
        shadowedPipeline = pipeline;
        REGISTERED_PIPELINES.add(pipeline);
    }

    /** 阴影采样管线是否已注册完成（纯布尔视图）。 */
    public static boolean isShadowedPipelineRegistered() {
        return shadowedPipeline != null;
    }

    /**
     * 构建并注册 GAP-003 多附件写入管线（{@link #MRT_LOCATION}）。
     *
     * <p>🔖 <b>本项目第一条多附件管线</b>：{@code withColorTargetStates(0, N-1, …)} 声明
     * N 个附件，与 {@link MrtProbe} 建的多附件 pass 附件数**必须相等** ——
     * Vulkan 要求管线颜色附件数与 render pass 附件数一致，否则 validation error。
     * 片元 {@code mrt.fsh} 对应写 {@code layout(location=0/1/2) out}。
     */
    public static void registerMrtPipeline(RegisterRenderPipelinesEvent event) {
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                .withLocation(MRT_PIPELINE_ID)
                .withVertexShader(FULLSCREEN_FLIPV_SHADER_ID)
                .withFragmentShader(MRT_SHADER_ID)
                .withColorTargetStates(0, dev.vkdisp.pipeline.model.MrtPlan.SLOT_COUNT - 1,
                        () -> ColorTargetState.DEFAULT)
                .build();
        event.registerPipeline(pipeline);
        mrtPipeline = pipeline;
        REGISTERED_PIPELINES.add(pipeline);
        VkDisp.LOGGER.info(
                "vkdisp: [GAP-003] mrt pipeline registered: {} colorTargets={} (first multi-attachment pipeline"
                        + " in this project; vertex={} fragment={})",
                MRT_LOCATION, dev.vkdisp.pipeline.model.MrtPlan.SLOT_COUNT, FULLSCREEN_FLIPV_SHADER_ID, MRT_SHADER_ID);
    }

    /**
     * 构建并注册 GAP-003 多附件回读管线（{@link #MRT_VIEW_LOCATION}）。
     *
     * <p>单附件（回读是「一个纹理显示到屏幕」，本质就是 blit）；顶点用翻转版
     * （采样源是我方离屏 colortex ⇒ 按 P-1f 实测约定需要 1-v 翻转）。
     */
    public static void registerMrtViewPipeline(RegisterRenderPipelinesEvent event) {
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                .withLocation(MRT_VIEW_PIPELINE_ID)
                .withVertexShader(FULLSCREEN_FLIPV_SHADER_ID)
                .withFragmentShader(MRT_VIEW_SHADER_ID)
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withUniform(SAMPLER_UNIFORM, UniformType.COMBINED_IMAGE_SAMPLER)
                        .build())
                .withColorTargetState(ColorTargetState.DEFAULT)
                .build();
        event.registerPipeline(pipeline);
        mrtViewPipeline = pipeline;
        REGISTERED_PIPELINES.add(pipeline);
    }

    /**
     * 构建并注册**不翻转**版回读管线（{@link #MRT_VIEW_NOFLIP_LOCATION}）。
     *
     * <p>与 {@link #registerMrtViewPipeline} 只差顶点着色器（{@code fullscreen} 而非
     * {@code fullscreen_flipv}）。用途单一但不能合并：合成链那条翻转管线**必须保留**，
     * 它补偿的是包 composite 的 OF vUv 语义。
     */
    public static void registerMrtViewNoFlipPipeline(RegisterRenderPipelinesEvent event) {
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath(VkDisp.MOD_ID, "pipeline/mrtview_noflip"))
                .withVertexShader(Identifier.fromNamespaceAndPath(VkDisp.MOD_ID, "fullscreen"))
                .withFragmentShader(MRT_VIEW_SHADER_ID)
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withUniform(SAMPLER_UNIFORM, UniformType.COMBINED_IMAGE_SAMPLER)
                        .build())
                .withColorTargetState(ColorTargetState.DEFAULT)
                .build();
        event.registerPipeline(pipeline);
        mrtViewNoFlipPipeline = pipeline;
        REGISTERED_PIPELINES.add(pipeline);
    }

    /** 已注册管线：不翻转版回读（bridge 内部）。采样引擎渲染目标时用它，别用翻转版。 */
    static RenderPipeline mrtViewNoFlipPipeline() {
        if (mrtViewNoFlipPipeline == null) {
            throw new IllegalStateException("vkdisp: mrt view (noflip) pipeline not registered yet");
        }
        return mrtViewNoFlipPipeline;
    }

    /** 已注册管线：MRT 多附件写入（bridge 内部）。 */
    static RenderPipeline mrtPipeline() {
        if (mrtPipeline == null) {
            throw new IllegalStateException("vkdisp: mrt pipeline not registered yet");
        }
        return mrtPipeline;
    }

    /** 已注册管线：MRT 回读（bridge 内部）。 */
    static RenderPipeline mrtViewPipeline() {
        if (mrtViewPipeline == null) {
            throw new IllegalStateException("vkdisp: mrt view pipeline not registered yet");
        }
        return mrtViewPipeline;
    }

    /** 已注册管线：阴影采样管线（bridge 包内部使用）。 */
    static RenderPipeline shadowedPipeline() {
        RenderPipeline pipeline = shadowedPipeline;
        if (pipeline == null) {
            throw new IllegalStateException("vkdisp: shadowed pipeline not registered yet");
        }
        return pipeline;
    }

    /** 已注册管线：几何管线（bridge 包内部使用）。 */
    static RenderPipeline geometryPipeline() {
        RenderPipeline pipeline = geometryPipeline;
        if (pipeline == null) {
            throw new IllegalStateException("vkdisp: geometry pipeline not registered yet");
        }
        return pipeline;
    }

    /** 几何顶点格式的 stride（字节）——供业务层/埋点核对，与 E 线计算表口径一致。 */
    public static int geometryVertexStride() {
        return GEOMETRY_VERTEX_FORMAT.getVertexSize();
    }

    /** 已注册管线：深度可视化管线（bridge 包内部使用）。 */
    static RenderPipeline depthVisPipeline() {
        RenderPipeline pipeline = depthVisPipeline;
        if (pipeline == null) {
            throw new IllegalStateException("vkdisp: depthviz pipeline not registered yet");
        }
        return pipeline;
    }

    /** 已注册管线：合成管线（bridge 包内部使用）。 */
    static RenderPipeline compositePipeline() {
        RenderPipeline pipeline = compositePipeline;
        if (pipeline == null) {
            throw new IllegalStateException("vkdisp: composite pipeline not registered yet");
        }
        return pipeline;
    }

    /** P3.2 场景合成管线（无 v 翻转）；未注册时抛出（与 {@link #compositePipeline()} 同口径）。 */
    static RenderPipeline compositeScenePipeline() {
        RenderPipeline pipeline = compositeScenePipeline;
        if (pipeline == null) {
            throw new IllegalStateException("vkdisp: composite scene pipeline not registered yet");
        }
        return pipeline;
    }

    /** P3.3 deferred 步管线；未注册时抛出（与 {@link #compositePipeline()} 同口径）。 */
    static RenderPipeline deferredPipeline() {
        RenderPipeline pipeline = deferredPipeline;
        if (pipeline == null) {
            throw new IllegalStateException("vkdisp: deferred pipeline not registered yet");
        }
        return pipeline;
    }

    /** P4.1.4 final 步管线；未注册时抛出（与 {@link #compositePipeline()} 同口径）。 */
    static RenderPipeline finalPipeline() {
        RenderPipeline pipeline = finalPipeline;
        if (pipeline == null) {
            throw new IllegalStateException("vkdisp: final pipeline not registered yet");
        }
        return pipeline;
    }

    /** 传递管线是否已注册完成（纯布尔视图）。 */
    public static boolean isBlitPipelineRegistered() {
        return blitPipeline != null;
    }

    /** 已注册管线：传递管线（bridge 包内部使用）。 */
    static RenderPipeline blitPipeline() {
        RenderPipeline pipeline = blitPipeline;
        if (pipeline == null) {
            throw new IllegalStateException("vkdisp: blit pipeline not registered yet");
        }
        return pipeline;
    }

    /** GAP-022 ① 深度代理管线是否已注册完成（纯布尔视图；未注册时调用方走自报，不抛）。 */
    public static boolean isDepthProxyPipelineRegistered() {
        return depthProxyPipeline != null;
    }

    /** 已注册管线：GAP-022 ① 深度代理管线（bridge 包内部使用）。 */
    static RenderPipeline depthProxyPipeline() {
        RenderPipeline pipeline = depthProxyPipeline;
        if (pipeline == null) {
            throw new IllegalStateException("vkdisp: depth proxy pipeline not registered yet");
        }
        return pipeline;
    }

    /**
     * 取已注册的全屏管线（bridge 包内部使用）。
     *
     * @throws IllegalStateException 注册尚未发生或注册失败时抛出，绝不静默返回 null
     */
    static RenderPipeline fullscreenPipeline() {
        RenderPipeline pipeline = fullscreenPipeline;
        if (pipeline == null) {
            throw new IllegalStateException(
                    "vkdisp: fullscreen pipeline not registered yet (RegisterRenderPipelinesEvent not fired or registration failed)");
        }
        return pipeline;
    }
}
