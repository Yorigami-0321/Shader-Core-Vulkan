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
 * <p><b>mixin 红线（07-CONSTRAINTS M1，2026-10-02 成文）</b>：默认零 mixin，
 * <b>全局只许 1 个注入点</b> = {@link #CHUNK_SECTIONS_TO_RENDER}#{@link #CHUNK_SECTIONS_RENDER_LAYERS}。
 * 理由（源码级核实）：地形管线唯一的替换通道就是该方法两个
 * {@code @Nullable RenderPipeline renderPipelineOverride} 形参，而
 * {@code renderGroup} 把 override 硬编码为 {@code WIREFRAME}（仅线框模式），
 * 且 {@code renderLayers} 是 private、无任何官方 setter。
 * <b>开闸五项前提</b>见 07 §1.4；<b>禁止项</b>见 X22–X26。
 * 需要第二个注入点 ⇒ 停下重评路线，不是加第二个 mixin。
 *
 * <p>当前项目 mixin 数 = 0，{@code neoforge.mods.toml} 的 {@code [[mixins]]} 段仍是注释状态
 * （取消注释前必须先满足 M1 五项前提，X26）。
 * 改动必须走 {@link ContractVersion} §3.2 流程。
 */
public final class MixinTargets {
    /** 当前项目启用的 mixin 配置数（0 = neoforge.mods.toml 的 [[mixins]] 被注释）。 */
    public static final int MIXIN_CONFIG_COUNT = 0;

    // ── M1 唯一允许的注入点（07-CONSTRAINTS §1.4）──────────────────────────
    /**
     * {@code net.minecraft.client.renderer.chunk.ChunkSectionsToRender} ——
     **M1 允许的唯一 mixin 目标类**。
     *
     * <p>为什么是它：地形 draw 用的管线由 {@code ChunkSectionLayer.SOLID/CUTOUT/TRANSLUCENT}
     * 写死（{@code RenderPipelines.SOLID_TERRAIN} 等），而该 draw 唯一的替换通道是
     * {@code renderLayers} 的两个 override 形参 —— {@code renderGroup} 把它们硬编码为
     * {@code WIREFRAME}（仅线框模式），且方法是 private、无官方 setter。
     *
     * <p>签名（26.3.0.23-beta 实测）：
     * {@code private void renderLayers(ChunkSectionLayer[], GpuSampler, RenderPass,
     * GpuTextureView, GpuTextureView, @Nullable RenderPipeline, @Nullable RenderPipeline)}
     * —— 共 7 参，末两个是 override。<b>升级时必须重新 javap 核对</b>。
     */
    public static final String CHUNK_SECTIONS_TO_RENDER =
            "net.minecraft.client.renderer.chunk.ChunkSectionsToRender";

    /** {@code ChunkSectionsToRender#renderLayers}（private）—— M1 唯一允许的注入方法。 */
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
