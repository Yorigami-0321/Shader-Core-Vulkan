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
 * 当前项目 mixin 数 = 0（P0 阶段未启用 mixins.json），常量表随首个 mixin 落地启用。
 *
 * <p>改动必须走 {@link ContractVersion} §3.2 流程。
 */
public final class MixinTargets {
    /** 当前项目启用的 mixin 配置数（0 = neoforge.mods.toml 的 [[mixins]] 被注释）。 */
    public static final int MIXIN_CONFIG_COUNT = 0;

    // ── 帧图插入点（P3 阶段启用；签名见 06-MIGRATION §3 V3）────────────────
    /** {@code net.minecraft.client.renderer.LevelRenderer} —— 影子 pass 插入（addMainPass HEAD）。 */
    public static final String LEVEL_RENDERER = "net.minecraft.client.renderer.LevelRenderer";
    /** {@code LevelRenderer#addMainPass} —— 帧图主 pass 追加点（04-SPEC §3.4）。 */
    public static final String LEVEL_RENDERER_ADD_MAIN_PASS = "addMainPass";
    /** {@code LevelRenderer#render} —— 后处理链 RETURN 插入点（04-SPEC §3.4）。 */
    public static final String LEVEL_RENDERER_RENDER = "render";

    /** 本契约版本（与 {@link ContractVersion#VERSION} 一致）。 */
    public static final int CONTRACT_VERSION = 1;

    private MixinTargets() {}
}
