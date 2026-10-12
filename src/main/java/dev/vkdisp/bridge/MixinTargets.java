package dev.vkdisp.bridge;
/**
 * 【参考调研】F1 契约冻结 / 06-MIGRATION §2.1 mixin 目标集中管理
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本项目 docs/06-MIGRATION.md §2.1「MixinTargets = 所有 mixin 目标的类名常量」
 *    + 原版 26.3 客户端 jar 内相关类的全限定名（javap 确认存在）。
 *    类名常量为事实性信息（不存在可复制的实现代码）；参考模组零接触。
 *    → 能否并入本项目（MIT）：本文件为本项目原创常量表，可并入
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：06-MIGRATION.md §2.1/§3 的易变点清单（V1–V3）。
 * 2. 备选：无。
 * 3. 我们的差异点：把全部 mixin 目标类名收敛到一个文件，升级时只改这里（V3 排查入口）。
 * 4. 许可证核对：本项目 MIT，零第三方代码。
 * 5. 性能基线：编译期字符串常量，无运行时开销。
 */

/**
 * F1 冻结契约：所有 mixin 目标类的全限定名常量（{@code docs/06-MIGRATION.md} §2.1）。
 *
 * <p><b>为什么集中</b>：升级 MC 版本时目标方法签名/类名一变，mixin 全废。
 * 集中到本文件后，升级排查入口只有一个（配合 §3 的 V3 易变点复查）。
 *
 * <p><b>规矩</b>：mixin 类只允许引用本文件的常量做 {@code @Inject(method = ...)} 的
 * 方法名拼接/校验，不许散落字面量（06 §2.2：mixin 只转发，不写业务）。
 *
 * <p><b>mixin 红线（07-CONSTRAINTS M1，现行口径 = 2026-10-12 用户裁决）</b>：注入<b>类别</b>闸门
 * （旧「只许注入管线装配层」白名单）<b>已废除</b> ⇒ 任意原版类都可注入：管线装配层、
 * {@code LevelRenderer} 内部、{@code SkyRenderer} / {@code CloudRenderer} 等原版渲染器、
 * 帧图 pass 的创建点、GUI 屏幕，以及<b>只为读一个 private 字段</b>的访问器型注入。
 * 保留的是三条：<b>登记制 + 可关闭制 + 逐个开启</b> —— 每个注入点必须登记在
 * {@code docs/04-SPEC.md} §5.0，且各自能一键关闭。
 *
 * <p><b>X24 已作废（2026-10-12）</b>：注入 {@code RenderSystem}·{@code GlStateManager} 不再
 * 是禁令。🔖 但本轮结论<b>一处都没用到这个放开</b> —— {@code RenderSystem} 在
 * {@code getCompiledPipelineNullable} <b>方法体首行</b>就给了公开通道（{@code PipelineModifier}，
 * 见 07 §1.4.2）。⚠️ 注入 {@code GL11}/{@code GL14}/{@code GL15} 这类<b>只在 OpenGL 路存在</b>的
 * 类仍然不该做，但理由已从「红线」降级为<b>工程事实</b>：Vulkan 路上没有那些调用点，
 * 注入即绑死后端（X7）。
 *
 * <p><b>唯一保留的禁令 = X23</b>：永久禁止注入 Sodium / caffeinemc / 第三方区块渲染器的任何类 ——
 * 它属 L11「零集成」支柱，与「注入哪个<b>原版</b>类」不同源，因此<b>不随类别放开</b>。
 *
 * <p><b>2026-10-03 修订</b>：原 javadoc 写的「全局只许 1 个注入点 = {@code renderLayers}」
 * 是 M1 松绑**之前**的口径，已作废。理由（源码级核实，26.3.0.41-beta sources jar）：
 * {@code renderLayers} 的两个 override 形参是<b>整组共用</b>的，而
 * {@code ChunkSectionLayerGroup.OPAQUE = {SOLID, CUTOUT}} 一次就是两个层
 * ⇒ 在 {@code renderLayers} 上设 override 会让 CUTOUT 套用 SOLID 的管线状态
 * （{@code ALPHA_CUTOUT} define 与混合模式不同，源码 RenderPipelines:379-399）。
 * 真正按层解析管线的唯一收口点是 {@link #CHUNK_SECTION_LAYER}#{@link #CHUNK_SECTION_LAYER_PIPELINE}。
 * 两个注入点各自的用途与可关闭键见 {@code 04-SPEC.md} §5.0 的 M-01 / M-01b。
 *
 * <p>当前项目 mixin 配置数 = {@link #MIXIN_CONFIG_COUNT}；每个注入点的代码只允许
 * <b>转发</b>到 {@code bridge/} 或业务包，不许写业务逻辑（X25）。
 * 改动必须走 {@link ContractVersion} §3.2 流程。
 */
public final class MixinTargets {
    /** 当前项目启用的 mixin 配置数（1 = {@code neoforge.mods.toml} 的 [[mixins]] 已取消注释）。 */
    public static final int MIXIN_CONFIG_COUNT = 1;

    // ── M1 登记的注入点（登记表见 docs/04-SPEC.md §5.0）────────────────────────
    /**
     * {@code net.minecraft.client.renderer.chunk.ChunkSectionLayer} ——
     * M-01 的目标类（按层解析地形管线的唯一收口点）。
     *
     * <p>为什么是它：地形 draw 最终用的是
     * {@code RenderSystem.getCompiledPipeline(renderPipelineOverride != null ? renderPipelineOverride : layer.pipeline(false))}
     * （{@code ChunkSectionsToRender} 的 {@code DrawSeparate}，sources jar 第 176 行；多重绘制分支
     * 在第 121 行用 {@code layer.pipeline(true)}）。{@code layer.pipeline(multiDraw)} 因此是
     * 「派生管线被地形 draw 用上」的唯一必经点，且它<b>按层</b>解析，CUTOUT 不会被套上 SOLID 的状态。
     *
     * <p>⚠️ <b>调用点不止 draw</b>：{@code SectionRenderDispatcher}（第 76 行）与
     * {@code LevelRenderer}（第 796 行）在<b>建网格</b>时也调 {@code layer.pipeline(false).getVertexFormatBinding(0)}。
     * 派生管线沿用同一 snippet ⇒ 顶点绑定逐项相同，该处行为不变（已随证据一起实测）。
     */
    public static final String CHUNK_SECTION_LAYER =
            "net.minecraft.client.renderer.chunk.ChunkSectionLayer";

    /** {@code ChunkSectionLayer#pipeline(boolean)} —— M-01 注入方法（public，非 final）。 */
    public static final String CHUNK_SECTION_LAYER_PIPELINE = "pipeline";

    /**
     * {@code net.minecraft.client.renderer.chunk.ChunkSectionsToRender} ——
     * M-01b 的目标类（地形 draw 的统一入口）。
     *
     * <p>签名（<b>26.3.0.41-beta sources jar 逐行核实</b>，替换此前 26.3.0.23-beta 的记录）：
     * {@code private void renderLayers(ChunkSectionLayer[], GpuSampler, RenderPass,
     * GpuTextureView atlas, GpuTextureView lightmap,
     * @Nullable RenderPipeline renderPipelineOverride,
     * @Nullable RenderPipeline renderPipelineOverrideMultidraw)}
     * —— 共 7 参，末两个是 override。<b>升级时必须重新核对。</b>
     */
    public static final String CHUNK_SECTIONS_TO_RENDER =
            "net.minecraft.client.renderer.chunk.ChunkSectionsToRender";

    /** {@code ChunkSectionsToRender#renderLayers}（private）—— M-01b 注入方法。 */
    public static final String CHUNK_SECTIONS_RENDER_LAYERS = "renderLayers";

    // ── 帧图插入点（P3 阶段曾评估启用；现状 = OPT-1 可选，非必需，优先走官方事件）────
    /** {@code net.minecraft.client.renderer.LevelRenderer} —— 影子 pass 插入（addMainPass HEAD）。 */
    public static final String LEVEL_RENDERER = "net.minecraft.client.renderer.LevelRenderer";

    /**
     * {@code com.mojang.blaze3d.framegraph.FrameGraphBuilder#addPass(String)} —— M-13 被包住的调用。
     *
     * <p>公开 API（sources jar 核实）：{@code public FramePass addPass(String name)}，实现按调用序
     * 把 pass 入表；{@code requires} 的边要到 {@code execute} 里的 {@code resolvePassOrder} 才解析
     * ⇒ <b>后创建的 pass 也能被先创建的 pass 引用</b>。这正是 M-13 成立的前提：我方 pass 在第 249 行
     * 的事件里创建，而原版 {@code "sky"} 在第 382 行、{@code "main"} 在第 403 行才创建。
     *
     * <p>🔴 必须<b>限定调用点</b>（原版别处也调 addPass）：本注入点写在 {@code LevelRenderer} 的
     * 两个方法上各包一次（{@link #LEVEL_RENDERER_ADD_SKY_PASS} 与 {@link #LEVEL_RENDERER_ADD_MAIN_PASS}）。
     */
    public static final String FRAME_GRAPH_BUILDER_ADD_PASS =
            "com.mojang.blaze3d.framegraph.FrameGraphBuilder.addPass(Ljava/lang/String;)"
                    + "Lcom/mojang/blaze3d/framegraph/FramePass;";

    /** {@code LevelRenderer#addSkyPass(FrameGraphBuilder, CameraRenderState, GpuBufferSlice)} —— M-13 注入方法之一（private，第 369 行）。 */
    public static final String LEVEL_RENDERER_ADD_SKY_PASS = "addSkyPass";

    /** 原版天空 pass 的 id（第 382 行 {@code frame.addPass("sky")}）。 */
    public static final String VANILLA_SKY_PASS_ID = "sky";

    /** 原版主 pass 的 id（第 403 行 {@code frame.addPass("main")}）。 */
    public static final String VANILLA_MAIN_PASS_ID = "main";
    /**
     * {@code LevelRenderer#addMainPass} —— 帧图主 pass 追加点（04-SPEC §3.4），**private、5 参**。
     *
     * <p>✅ 现已被 <b>M-06</b> 用作只读捕获点（登记在 {@code 04-SPEC} §5.0；它替代旧 M-05 那两个
     * {@code prepareChunkRenders*} 的 RETURN 注入点）。签名（26.3 sources jar 第 396-402 行核实）：
     * {@code private void addMainPass(FrameGraphBuilder, FeatureRenderDispatcher.PreparedFrame,
     * GpuBufferSlice terrainFog, ChunkSectionsToRender, boolean)}
     * —— 唯一调用点在第 **277** 行且<b>无条件</b>执行，而 indirect（272）与常规（274）两支都在它
     * 之前汇合 ⇒ 一个注入点同时拿到 {@code terrainFog}（{@code GAP-038} 要的雾）与地形 draw 数据。
     */
    public static final String LEVEL_RENDERER_ADD_MAIN_PASS = "addMainPass";
    /** {@code LevelRenderer#render} —— 后处理链 RETURN 插入点（04-SPEC §3.4）。⚠️ 现行口径下
     * 目标类本身可注入，但本点**未登记** ⇒ 依 X28 不得注入；要用先登记 04-SPEC §5.0。 */
    public static final String LEVEL_RENDERER_RENDER = "render";

    // ── R3（2026-10-12 第 2 步）新增：M-07 / M-08 / M-09 / M-10 ────────────────────
    /**
     * {@code LevelRenderer#chunkLayerSampler} —— private 字段，sources jar 第 **146** 行
     * （类型 {@code @Nullable GpuSampler}；其按需重建在第 437-449 行的 pass 体内）。M-07 访问器目标。
     */
    public static final String LEVEL_RENDERER_CHUNK_LAYER_SAMPLER = "chunkLayerSampler";

    /**
     * {@code net.minecraft.client.renderer.CloudRenderer} —— M-09（访问器）与 M-10（{@code @Redirect}）
     * 的共同目标类。原版由 {@code LevelRenderer} 第 132 行 {@code new CloudRenderer()} 持有，
     * 第 1345 行 {@code public CloudRenderer cloudRenderer()} 交出引用（我方重放用的就是它）。
     */
    public static final String CLOUD_RENDERER = "net.minecraft.client.renderer.CloudRenderer";

    /** {@code CloudRenderer#quadCount} —— private 字段，第 **58** 行。M-09 访问器目标。 */
    public static final String CLOUD_RENDERER_QUAD_COUNT = "quadCount";

    /**
     * {@code CloudRenderer} 的 <b>private</b> 重载 {@code render(RenderPass, RenderPipeline)}
     * （第 225 行）—— M-10 的注入方法。
     *
     * <p>⚠️ 必须写<b>完整描述符</b>：{@code CloudRenderer} 上有三个叫 {@code render} 的重载
     * （第 197 行 public、第 207 行 public {@code renderOit} 内又调它、第 225 行 private），
     * 只写方法名会让 {@code @Redirect} 命中多个方法而报「too many injections」。
     */
    public static final String CLOUD_RENDERER_RENDER_INNER =
            "render(Lcom/mojang/renderpearl/api/commands/RenderPass;"
                    + "Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V";

    /**
     * {@code RenderSystem#getModelViewMatrixCopy()} —— M-10 被替换的那次<b>调用</b>
     * （{@code @At(value="INVOKE", target=…)}）。声明见第 296 行：
     * {@code public static Matrix4f getModelViewMatrixCopy()}，实现是 {@code new Matrix4f(modelViewStack)}
     * ⇒ 替换者的返回类型必须是 {@code Matrix4f}（放宽成 {@code Matrix4fc} 不保证被接受）。
     */
    public static final String RENDER_SYSTEM_GET_MODEL_VIEW_MATRIX_COPY =
            "com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrixCopy()Lorg/joml/Matrix4f;";

    /**
     * {@code ChunkSectionsToRender.DrawIndirect} —— M-08 目标（<b>嵌套变体</b>，不是基类）。
     *
     * <p>为什么写 {@code $} 而不是类字面量：要读的字段 {@code drawGroupsPerLayer} 声明在这个
     * {@code public static final class} 上（第 96-97 行），基类 {@code ChunkSectionsToRender} 没有它。
     */
    public static final String CHUNK_SECTIONS_TO_RENDER_DRAW_INDIRECT =
            "net.minecraft.client.renderer.chunk.ChunkSectionsToRender$DrawIndirect";

    /** {@code DrawIndirect#drawGroupsPerLayer} —— private 字段，第 **97** 行。M-08 访问器目标。 */
    public static final String CHUNK_SECTIONS_TO_RENDER_DRAW_GROUPS = "drawGroupsPerLayer";

    /**
     * {@code ChunkSectionsToRender.DrawSeparate} —— M-15 的第二个目标（非多重绘制变体，第 152 行起）。
     *
     * <p>取管线那一刻在它自己的 {@code render} 第 **176** 行；{@code DrawIndirect} 那一支在第
     * **121** 行。两支的 {@code render} 形参表<b>逐字相同</b>（都是 abstract 的实现）。
     */
    public static final String CHUNK_SECTIONS_TO_RENDER_DRAW_SEPARATE =
            "net.minecraft.client.renderer.chunk.ChunkSectionsToRender$DrawSeparate";

    /**
     * {@code ChunkSectionsToRender} 两个变体共同的 private/protected {@code render} 方法描述符
     * （{@code render(ChunkSectionLayer, RenderPass, GpuBuffer, IndexType, RenderPipeline, RenderPipeline)}）。
     *
     * <p>🔖 M-15 要靠它的<b>形参表</b>拿到 {@code renderPass} —— 这是「pass 身份由数据给出」的唯一通路：
     * {@code @WrapOperation} 的 handler 用 MixinExtras 的 {@code @Local(argsOnly = true)} 读实参。
     */
    public static final String CHUNK_SECTIONS_TO_RENDER_RENDER_INNER =
            "render(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;"
                    + "Lcom/mojang/renderpearl/api/commands/RenderPass;"
                    + "Lcom/mojang/renderpearl/api/buffers/GpuBuffer;"
                    + "Lcom/mojang/renderpearl/api/pipeline/IndexType;"
                    + "Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;"
                    + "Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V";

    /**
     * {@code RenderSystem.getCompiledPipeline(RenderPipeline)} —— M-15 被包住的那次<b>调用</b>
     * （声明见第 123 行，返回 {@code CompiledRenderPipeline}；它内部第 107 行第一句就是
     * {@code PIPELINE_MODIFIERS.apply}，见 {@code 07} §1.4.2）。
     */
    public static final String RENDER_SYSTEM_GET_COMPILED_PIPELINE =
            "com.mojang.blaze3d.systems.RenderSystem.getCompiledPipeline("
                    + "Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)"
                    + "Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;";

    /**
     * {@code net.minecraft.client.renderer.SkyRenderer} —— M-11 的目标类。
     *
     * <p>原版在 {@code LevelRenderer} 第 372-378 行按 {@code shouldResetSkyRenderer} 重建它，
     * 构造参数是 {@code gameRenderer.mainRenderTarget()} ⇒ <b>它持有的那张 RenderTarget 就是主目标</b>。
     * M-11 不换个壳给它，而是把它<b>取视图的那两次调用</b>换掉（见下两条常量）。
     */
    public static final String SKY_RENDERER = "net.minecraft.client.renderer.SkyRenderer";

    /**
     * {@code SkyRenderer#render(GpuBufferSlice, SkyRenderState)} —— public，第 **132** 行；
     * M-11 的注入方法。
     *
     * <p>体形（sources jar 逐字核实）：第 133 行 {@code setShaderFog(skyFog)}，第 <b>134/135</b> 行
     * 两次 {@code renderTarget.getXxxTextureView()}，第 137-139 行<b>自己</b>建 "Sky" pass，
     * 两个附件都是 {@code Optional.empty()/OptionalDouble.empty()} = <b>LOAD 语义</b>。
     * 🔖 全仓库里它只有一个调用点（{@code LevelRenderer:386}，在 {@code "sky"} pass 体内、
     * 那一刻没有任何 render pass 打开）⇒ 这两条 @Redirect 的作用域天然只覆盖原版天空那一笔。
     */
    public static final String SKY_RENDERER_RENDER =
            "render(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"
                    + "Lnet/minecraft/client/renderer/state/level/SkyRenderState;)V";

    /** {@code RenderTarget#getColorTextureView()}（第 129 行，返回 {@code @Nullable GpuTextureView}）—— M-11 第一处被替换的调用。 */
    public static final String RENDER_TARGET_GET_COLOR_TEXTURE_VIEW =
            "com.mojang.blaze3d.pipeline.RenderTarget.getColorTextureView()"
                    + "Lcom/mojang/renderpearl/api/textures/GpuTextureView;";

    /** {@code RenderTarget#getDepthTextureView()}（第 137 行）—— M-11 第二处被替换的调用。 */
    public static final String RENDER_TARGET_GET_DEPTH_TEXTURE_VIEW =
            "com.mojang.blaze3d.pipeline.RenderTarget.getDepthTextureView()"
                    + "Lcom/mojang/renderpearl/api/textures/GpuTextureView;";

    /** 本契约版本（与 {@link ContractVersion#VERSION} 一致）。 */
    public static final int CONTRACT_VERSION = 1;

    private MixinTargets() {}
}
