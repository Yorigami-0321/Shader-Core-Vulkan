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
 * <p><b>mixin 红线（07-CONSTRAINTS M1，2026-10-02 松绑后）</b>：允许注入到「管线装配层」，
 * <b>不限数量</b>，但改为<b>登记制 + 可关闭制 + 逐个开启</b>：每个注入点必须登记在
 * {@code docs/04-SPEC.md} §5.0，且各自能一键关闭。仍<b>永久禁止</b>注入 Sodium / caffeinemc /
 * 第三方区块渲染器 / {@code RenderSystem}·{@code GlStateManager}·{@code GL*} 等底层状态类
 * （X23 / X24）。
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
    /** {@code LevelRenderer#addMainPass} —— 帧图主 pass 追加点（04-SPEC §3.4）。**private**，M1 下不得注入。 */
    public static final String LEVEL_RENDERER_ADD_MAIN_PASS = "addMainPass";
    /** {@code LevelRenderer#render} —— 后处理链 RETURN 插入点（04-SPEC §3.4）。**M1 下不得注入**。 */
    public static final String LEVEL_RENDERER_RENDER = "render";

    /** 本契约版本（与 {@link ContractVersion#VERSION} 一致）。 */
    public static final int CONTRACT_VERSION = 1;

    private MixinTargets() {}
}
