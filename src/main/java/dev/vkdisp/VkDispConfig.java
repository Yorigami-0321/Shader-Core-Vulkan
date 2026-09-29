package dev.vkdisp;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 模组自身配置。用户着色器包自己声明的选项不放这里（那是运行时动态的，见 {@code config/PackOptions}）。
 *
 * <p>Phase 0 只放最小可用的开关；具体条目随功能推进再加。
 */
public final class VkDispConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** 总开关：关闭后本模组完全不介入渲染，用于快速二分定位问题。 */
    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("总开关。关闭后本模组不介入任何渲染，用于快速二分定位问题。")
            .define("enabled", true);

    /** 诊断日志：打开后在各个 pass 与管线构建点输出计数类日志。 */
    public static final ModConfigSpec.BooleanValue DEBUG_LOG = BUILDER
            .comment("诊断日志。打开后输出管线构建/编译计数，便于排查静默失败。")
            .define("debugLog", false);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private VkDispConfig() {
    }
}
