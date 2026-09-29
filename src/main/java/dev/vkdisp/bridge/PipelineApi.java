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
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
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

    /** 管线 location：vkdisp:pipeline/fullscreen → 注册表键。 */
    private static final Identifier FULLSCREEN_PIPELINE_ID =
            Identifier.fromNamespaceAndPath(VkDisp.MOD_ID, "pipeline/fullscreen");

    /** 着色器资源 id：vkdisp:fullscreen → assets/vkdisp/shaders/fullscreen.vsh / .fsh。 */
    private static final Identifier FULLSCREEN_SHADER_ID =
            Identifier.fromNamespaceAndPath(VkDisp.MOD_ID, "fullscreen");

    /** 注册成功后暂存的管线实例（供 FrameApi 使用）；未注册时为 null。 */
    private static RenderPipeline fullscreenPipeline;

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
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withUniform(PARAMS_UNIFORM, UniformType.UNIFORM_BUFFER)
                        .build())
                .withColorTargetState(ColorTargetState.DEFAULT)
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
