package dev.vkdisp.pack;
/**
 * 【自行补充】GAP-024 · 链门控总开关的<b>读取侧</b>（把 FML 配置与 {@code dev.vkdisp.pack} 解耦）
 *
 * <p>0. 合规核对（第 0 步闸门）：参考对象 = 本仓库既有的同类桥接类
 * {@link PackCapabilityGateSwitch}（它把 h33 实测的「反射用配置键名当 Java 字段名
 * ⇒ {@code NoSuchFieldException} 被 {@code catch (Throwable)} 吞掉 ⇒ 开关恒为默认值
 * 且日志看起来完全正常」这条教训固化成了两个分开的常量）。
 * 全部为仓库内自有代码与自有文档事实 → 可并入本项目（MIT）；例外条款：无。
 *
 * <p>1. 官方/主实现：NeoForge {@code ModConfigSpec}（只用 {@code get()} 读值）。
 *
 * <p>2. 备选：
 * <ul>
 *   <li>① {@code PackPostChain} 直引 {@code VkDispConfig.CHAIN_ENABLE_GATING} ——
 *       <b>否决</b>：{@code dev.vkdisp.pack} 的测试类路径会被拖进整个 FML 配置体系
 *       （{@code PackCompileCache} 实测踩过 {@code NoClassDefFoundError: IConfigSpec}）。</li>
 *   <li>② 调用方把布尔值传进来、本类不存在 —— <b>否决</b>：那样每个调用点都要自己反射一次，
 *       而 h33 那一族的失败正好发生在「每个调用点各自抄一份反射」的地方。</li>
 * </ul>
 *
 * <p>3. 我们的差异点：<b>默认开（true）</b>。与 {@link PackCapabilityGateSwitch} 的默认关
 * 相反，且这个相反是<b>有意的</b>：
 * 本开关做的事是「执行包<b>自己</b>写下的 {@code program.*.enabled}」——
 * 关掉它 = 让关了特性的包继续跑那一级 = <b>违反包的声明</b>，属于错误行为而不是保守行为；
 * {@code pack.capabilityGate} 关的却是「我方替包决定关掉它没声明要关的东西」，
 * 那才是需要用户显式开启的动作（X27 / 支柱①）。
 * 留这个键的唯一用途是 A/B 取证（同一二进制内单变量对照）。
 *
 * <p>4. 许可证核对：本项目 MIT；零第三方代码复制。
 * <p>5. 性能基线：❄️ 冷路径（每次链装配读一次），不做优化（18-PARALLEL §7.7）。
 */
import java.util.function.BooleanSupplier;

/** 链 enabled 门控总开关的读取侧（单点真源）。 */
public final class PackChainGatingSwitch {

    /** 配置键名（与 {@code VkDispConfig} 里的 {@code define} 键逐字一致，单点真源）。 */
    public static final String CONFIG_KEY = "pack.chainEnableGating";

    /**
     * 🔴 {@code VkDispConfig} 里承载它的 <b>Java 字段名</b>（反射用）。
     *
     * <p>🔖 必须与 {@link #CONFIG_KEY} 分成两个常量 —— h33 实测的真 bug 就是把配置键名
     * 当字段名传给 {@code getField}，于是每次 {@link NoSuchFieldException} 被吞掉，
     * 开关写进配置文件、日志照打、<b>永远不生效</b>。
     */
    public static final String FIELD_NAME = "CHAIN_ENABLE_GATING";

    /** 默认值：开（理由见类注释第 3 条 —— 门控执行的是包自己的声明，不门控才是违约）。 */
    public static final boolean DEFAULT_ENABLED = true;

    /** 单测覆盖槽；null = 读 FML 配置（无 FML 环境时回落 {@link #DEFAULT_ENABLED}）。 */
    private static volatile BooleanSupplier override;

    /** 反射失败原文（{@code null} = 没失败过）；只记第一次，见 {@link #noteReflectionFailure}。 */
    private static volatile String reflectionFailure;

    private PackChainGatingSwitch() {
    }

    /**
     * 单测 / A/B 专用：覆盖开关来源（传 {@code null} 恢复读 FML 配置）。
     *
     * <p>⚠️ 生产路径不调它；测试用完<b>必须</b>复位回 null，否则静态槽会漏进别的测试（QD-03）。
     */
    public static void override(BooleanSupplier supplier) {
        override = supplier;
    }

    /** 本次链装配是否执行 {@code program.*.enabled} 门控。 */
    public static boolean enabled() {
        BooleanSupplier supplier = override;
        if (supplier != null) {
            return supplier.getAsBoolean();
        }
        try {
            Class<?> config = Class.forName("dev.vkdisp.VkDispConfig");
            Object value = config.getField(FIELD_NAME).get(null);
            if (value instanceof net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<?> configValue) {
                return Boolean.TRUE.equals(configValue.get());
            }
            // 字段存在但类型不是布尔配置项 = 配置被改坏了 ⇒ 报错 + 按默认（开），不猜。
            noteReflectionFailure("字段 " + FIELD_NAME + " 的类型不是布尔配置项（实际 "
                    + (value == null ? "null" : value.getClass().getName()) + "）");
            return DEFAULT_ENABLED;
        } catch (ClassNotFoundException e) {
            return DEFAULT_ENABLED; // 无 FML 环境（单测）⇒ 默认值，不是错误
        } catch (NoClassDefFoundError e) {
            // 🔖 「类在，但它引用的 FML 类型不在」= **本进程不是游戏进程**（单测类路径里没有
            //   NeoForge：实测 `NoClassDefFoundError: net/neoforged/neoforge/common/ModConfigSpec$ConfigValue`）。
            //   这**不是**开关坏掉。早先它落到下面的 `catch (Throwable)` ⇒ 在单测里留下
            //   `reflectionFailure`，于是 `PackPostChain` 对着一份好端端的配置打 ERROR，
            //   而且这个静态位会漏给同一 JVM 里的后续测试（假报警 + 测试串味，两样）。
            return DEFAULT_ENABLED;
        } catch (NoSuchFieldException e) {
            // 🔴🔴 真错误，不是「默认开」：字段被改名/删除会让开关静默失效（h33 本体）。
            noteReflectionFailure("dev.vkdisp.VkDispConfig 上找不到字段 " + FIELD_NAME
                    + "（配置键 " + CONFIG_KEY + " 的 Java 字段名写错或该字段已被删除）");
            return DEFAULT_ENABLED;
        } catch (Throwable t) {
            noteReflectionFailure("读取字段 " + FIELD_NAME + " 时发生非预期异常：" + t);
            return DEFAULT_ENABLED;
        }
    }

    /** 上一次反射失败的原文；{@code null} = 至今没失败过。调用方<b>必须</b>把它打印出来。 */
    public static String reflectionFailure() {
        return reflectionFailure;
    }

    /** 清空失败原文（测试隔离用；生产不需要 —— 失败是「恒定坏掉」的状态）。 */
    public static void clearReflectionFailure() {
        reflectionFailure = null;
    }

    private static void noteReflectionFailure(String reason) {
        if (reflectionFailure != null) {
            return;
        }
        reflectionFailure = reason;
    }
}
