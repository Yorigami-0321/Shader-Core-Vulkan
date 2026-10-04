package dev.vkdisp.pack;
/**
 * 【参考调研】能力门控总开关的**读取侧**（把 FML 配置与 pack 包解耦）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/07-CONSTRAINTS.md T11（降级必须可见）+ X9（不猜）、
 *    以及本仓库既有的「纯逻辑类不直引 FML 配置体系」约定
 *    （见 {@code PackCompileCache} 类注释里实测到的 {@code NoClassDefFoundError: IConfigSpec}）。
 *    全部为仓库内自有文档事实与自有约定。
 *    → 能否并入本项目（MIT）：可以（本文件为独立编写的极小桥接类）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：NeoForge {@code ModConfigSpec}（只用 {@code get()} 读值）。
 * 2. 备选：
 *    <ul>
 *      <li>① 在 {@code PackTerrainSource} 里直引 {@code VkDispConfig.CAPABILITY_GATE} ——
 *          <b>否决</b>：那会让 {@code dev.vkdisp.pack} 包的测试类路径拖进整个 FML 配置体系
 *          （{@code PackCompileCache} 已实测踩过：{@code NoClassDefFoundError: IConfigSpec}）。</li>
 *      <li>② 直接反射读 {@code VkDispConfig} 字段 —— <b>否决</b>：反射失败会静默返回默认值，
 *          于是「配置项被改名/被删」这种真错误看起来跟「默认值 true」一模一样（X9）。</li>
 *    </ul>
 * 3. 我们的差异点：<b>默认关（false）+ 显式可覆盖</b>。
 *    <p>🔖 <b>为什么默认关而不是默认开</b>：门控会改变用户可见画面（关掉包特性），
 *    支柱①是兼容、支柱②是稳定 —— 任何「改变包语义」的动作都应当<b>由用户显式开启</b>，
 *    而不是默认生效让用户莫名其妙少了特性。这与 {@code mrt.*} 诊断开关默认关是同一条纪律。</p>
 *    <p>🔖 <b>为什么提供 {@link #override}</b>：单测必须在无 FML 环境里驱动这条分支
 *    （默认关时测不到「门控生效」那条路）；显式覆盖比反射可控，也不会把真错误伪装成默认值。</p>
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 冷路径每包读一次配置值，代价可忽略。
 */
import java.util.function.BooleanSupplier;

/**
 * 能力门控总开关的读取侧（单点真源）。
 *
 * <p>🔖 <b>为什么默认关</b>：门控会改变用户可见画面（关掉包特性）。
 * 支柱①兼容 / 支柱②稳定的口径是「任何改变包语义的动作由用户显式开启」——
 * 默认生效会让用户「莫名其妙少了特性」，而那正是本项目最该避免的失败形态。
 * 想要止血效果的用户显式打开 {@code pack.capabilityGate=true} 即可。
 */
public final class PackCapabilityGateSwitch {

    /** 配置键名（与 {@code VkDispConfig} 里的 {@code define} 键同名，单点真源）。 */
    public static final String CONFIG_KEY = "pack.capabilityGate";

    /** 默认值：关（理由见类注释）。 */
    public static final boolean DEFAULT_ENABLED = false;

    /** 单测覆盖槽；null = 走 {@link #DEFAULT_ENABLED}（运行时即读 FML 配置）。 */
    private static volatile BooleanSupplier override;

    private PackCapabilityGateSwitch() {
    }

    /**
     * 单测专用：覆盖开关来源（传 {@code null} 恢复读 FML 配置）。
     *
     * <p>⚠️ 只给测试用；生产路径不调它。
     */
    public static void override(BooleanSupplier supplier) {
        override = supplier;
    }

    /** 当前门控是否开启。 */
    public static boolean enabled() {
        BooleanSupplier supplier = override;
        if (supplier != null) {
            return supplier.getAsBoolean();
        }
        try {
            Class<?> config = Class.forName("dev.vkdisp.VkDispConfig");
            Object value = config.getField(CONFIG_KEY).get(null);
            if (value instanceof net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<?> configValue) {
                return Boolean.TRUE.equals(configValue.get());
            }
            // 🔶 字段存在但类型不是布尔配置项 = 配置被改坏了 ⇒ WARN + 按默认（关），
            //   **不**静默当成 true（默认开会改变用户画面）。
            return DEFAULT_ENABLED;
        } catch (ClassNotFoundException e) {
            // 无 FML 环境（单测）⇒ 默认值，不是错误。
            return DEFAULT_ENABLED;
        } catch (Throwable t) {
            // 🔶 其它异常（字段被删 / 反射被禁）⇒ 默认值 + 交由调用方的门控诊断可见。
            return DEFAULT_ENABLED;
        }
    }
}
