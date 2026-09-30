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

    /** P3.2 场景合成管线 location（纯字符串视图，错误信息用）。 */
    public static final String COMPOSITE_SCENE_LOCATION = "vkdisp:pipeline/composite_scene";

    /**
     * P2.4：合成管线的内建 uniform 块名（纯字符串视图）。
     *
     * <p>包 composite 源经 D 线转译后必带 {@code layout(std140) uniform VkDispBuiltins { … };}
     * （P2.3 实测终态）——绑定布局必须声明它（未被片元引用的布局条目合法：
     * 反例对照 {@code blit.fsh} 不声明 Globals 但布局带 Globals，实测可绘制），
     * 否则「片元声明了布局没有的块」这一方向未实测过（X9 不猜）。
     */
    public static final String BUILTINS_UNIFORM = "VkDispBuiltins";

    /** 深度可视化管线 location（P3 前置：采样深度纹理 → 灰度输出，用于验证深度附件链路）。 */
    public static final String DEPTHVIS_LOCATION = "vkdisp:pipeline/depthviz";

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

    /** 注册成功后暂存的合成管线实例；未注册时为 null。 */
    private static RenderPipeline compositePipeline;

    /** 注册成功后暂存的 P3.2 场景合成管线实例（无 v 翻转顶点）；未注册时为 null。 */
    private static RenderPipeline compositeScenePipeline;

    /** 注册成功后暂存的深度可视化管线实例；未注册时为 null。 */
    private static RenderPipeline depthVisPipeline;

    /** 注册成功后暂存的几何管线实例；未注册时为 null。 */
    private static RenderPipeline geometryPipeline;

    /** 注册成功后暂存的阴影采样管线实例；未注册时为 null。 */
    private static RenderPipeline shadowedPipeline;

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
     * 构建并注册合成管线（P2.4 起 = Pass 3 主链管线）。
     *
     * <p>片元 = 虚拟包 {@code vkdisp_pack:composite}（库存包源或内置兜底），
     * 顶点 = {@code vkdisp:fullscreen_flipv}（1-v 翻转上移到顶点，P-1f）。
     * 绑定组同一组、顺序与包源 GLSL 声明一致（P-1f ②）：先 {@link #BUILTINS_UNIFORM}
     * （D 线注入的内建块在源中最靠前）后 {@link #SAMPLER_UNIFORM}。
     */
    public static void registerCompositePipeline(RegisterRenderPipelinesEvent event) {
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                .withLocation(COMPOSITE_PIPELINE_ID)
                .withVertexShader(FULLSCREEN_FLIPV_SHADER_ID)
                .withFragmentShader(COMPOSITE_SHADER_ID)
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withUniform(BUILTINS_UNIFORM, UniformType.UNIFORM_BUFFER)
                        .withUniform(SAMPLER_UNIFORM, UniformType.COMBINED_IMAGE_SAMPLER)
                        .build())
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
     * （P-1f 实测需翻转）。片元/绑定组完全一致，按输入源在 FrameApi Pass 3 选择。
     */
    public static void registerCompositeScenePipeline(RegisterRenderPipelinesEvent event) {
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                .withLocation(COMPOSITE_SCENE_PIPELINE_ID)
                .withVertexShader(FULLSCREEN_SHADER_ID)
                .withFragmentShader(COMPOSITE_SHADER_ID)
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withUniform(BUILTINS_UNIFORM, UniformType.UNIFORM_BUFFER)
                        .withUniform(SAMPLER_UNIFORM, UniformType.COMBINED_IMAGE_SAMPLER)
                        .build())
                .withColorTargetState(ColorTargetState.DEFAULT)
                .build();
        event.registerPipeline(pipeline);
        compositeScenePipeline = pipeline;
        REGISTERED_PIPELINES.add(pipeline);
        VkDisp.LOGGER.info(
                "vkdisp: composite scene pipeline wired: fragment={} vertex={} (no v-flip; P3.2 scene input)",
                COMPOSITE_SHADER_ID, FULLSCREEN_SHADER_ID);
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
