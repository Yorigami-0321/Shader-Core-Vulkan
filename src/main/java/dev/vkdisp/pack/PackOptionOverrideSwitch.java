package dev.vkdisp.pack;
/**
 * 【参考调研】单选项强制覆盖开关的**读取侧**（把 FML 配置与 pack 包解耦）
 * 0. 合规核对（第 0 步闸门）：
 *    参考对象 = ① 本仓库自有的 {@code PackCapabilityGateSwitch}（同款「不引 FML 配置体系」的
 *    读取侧范式，含 h33 实测的「配置键名 ≠ Java 字段名」那条真实缺陷与它的修法）；
 *    ② {@code docs/07-CONSTRAINTS.md} T11（降级必须可见）/ X9（不猜）。
 *    全部为仓库内自有文档事实与自有代码。
 *    → 能否并入本项目（MIT）：可以（本文件为独立编写的极小桥接类）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：NeoForge {@code ModConfigSpec}（只用 {@code get()} 读值）。
 * 2. 备选：
 *    <ul>
 *      <li>① 在 {@code PackTerrainSource} 里直引 {@code VkDispConfig.OPTION_OVERRIDES} ——
 *          <b>否决</b>：{@code PackCompileCache} 已实测踩过 ——
 *          {@code NoClassDefFoundError: IConfigSpec}（{@code dev.vkdisp.pack} 的测试 classpath
 *          上没有 FML 配置体系）。</li>
 *      <li>② 直接反射读字段、不留失败原因 —— <b>否决</b>：字段被改名会让开关
 *          <b>静默</b>返回空串 ⇒ 「我写了单变量覆盖但它没生效」，且毫无异常
 *          —— 这正是 h33 实测过的形态。</li>
 *    </ul>
 * 3. 我们的差异点：与 {@code PackCapabilityGateSwitch} 完全同构，额外多一条纪律：
 *    <b>本开关是 {@code String} 而非布尔</b> ⇒ 除了「字段找不到」，还多一类失败
 *    「类型不是字符串配置项」，两者都必须<b>分别</b>吵出来（都归到同一个 failure 通道）。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 冷路径每包读一次配置值，代价可忽略。
 */
import java.util.function.Supplier;

/**
 * 单选项强制覆盖串的读取侧（单点真源）。
 *
 * <p>🔖 <b>为什么默认空串</b>：本开关会改变用户可见画面（强制某个包选项的值）。
 * 按支柱①/②的口径，任何「改变包语义」的动作都由用户显式开启；
 * 空串 = 不覆盖，与能力门控默认关是同一条纪律。
 */
public final class PackOptionOverrideSwitch {

    /** 配置键名（与 {@code VkDispConfig} 里的 {@code define} 键同名，单点真源）。 */
    public static final String CONFIG_KEY = "pack.optionOverrides";

    /**
     * 🔴 {@code VkDispConfig} 里承载它的 <b>Java 字段名</b>（反射用）。
     *
     * <p>🔖 <b>必须与 {@link #CONFIG_KEY} 分开</b>：h33 实测过把键名当字段名传给
     * {@code getField} 会导致 {@code NoSuchFieldException} 被吞掉 ⇒ 开关<b>永远</b>取默认值，
     * 症状是「配置写对了、日志照打、就是不生效」且毫无异常。
     */
    public static final String FIELD_NAME = "OPTION_OVERRIDES";

    /** 默认值：空串（= 不覆盖任何选项）。 */
    public static final String DEFAULT_SPEC = "";

    /** 单测覆盖槽；null = 走 FML 配置（运行时）。 */
    private static volatile Supplier<String> override;

    /** 反射失败的原文（{@code null} = 没失败过）。 */
    private static volatile String reflectionFailure;

    private PackOptionOverrideSwitch() {
    }

    /** 单测专用：覆盖开关来源（传 {@code null} 恢复读 FML 配置）。 */
    public static void override(Supplier<String> supplier) {
        override = supplier;
    }

    /** 当前覆盖串（空串 = 不覆盖）。 */
    public static String spec() {
        Supplier<String> supplier = override;
        if (supplier != null) {
            String value = supplier.get();
            return value == null ? DEFAULT_SPEC : value;
        }
        try {
            Class<?> config = Class.forName("dev.vkdisp.VkDispConfig");
            Object value = config.getField(FIELD_NAME).get(null);
            if (value instanceof net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<?> configValue) {
                Object read = configValue.get();
                if (read instanceof String text) {
                    return text;
                }
                noteReflectionFailure("字段 " + FIELD_NAME + " 读出的值不是字符串（实际 "
                        + (read == null ? "null" : read.getClass().getName())
                        + "）⇒ 按空串（= 不覆盖）处理");
                return DEFAULT_SPEC;
            }
            noteReflectionFailure("字段 " + FIELD_NAME + " 的类型不是字符串配置项（实际 "
                    + (value == null ? "null" : value.getClass().getName()) + "）");
            return DEFAULT_SPEC;
        } catch (ClassNotFoundException e) {
            return DEFAULT_SPEC; // 无 FML 环境（单测）⇒ 默认值，不是错误
        } catch (NoSuchFieldException e) {
            noteReflectionFailure("dev.vkdisp.VkDispConfig 上找不到字段 " + FIELD_NAME
                    + "（配置键 " + CONFIG_KEY + " 的 Java 字段名写错或该字段已被删除）");
            return DEFAULT_SPEC;
        } catch (Throwable t) {
            noteReflectionFailure("读取字段 " + FIELD_NAME + " 时发生非预期异常：" + t);
            return DEFAULT_SPEC;
        }
    }

    private static void noteReflectionFailure(String reason) {
        if (reflectionFailure != null) {
            return;
        }
        reflectionFailure = reason;
    }

    /**
     * 上一次反射失败的原文；{@code null} = 至今没失败过。
     *
     * <p>调用方<b>必须</b>把它显示出来 —— 否则「覆盖写了不生效」就成了无声失败。
     */
    public static String reflectionFailure() {
        return reflectionFailure;
    }
}