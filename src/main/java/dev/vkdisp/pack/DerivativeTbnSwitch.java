package dev.vkdisp.pack;

import java.util.function.BooleanSupplier;

/**
 * 【自行补充】GAP-007 · 「包片元的 normal/tangent/binormal 由屏幕空间导数现算」开关的<b>读取侧</b>
 *
 * <p>0. 合规核对（第 0 步闸门）：参考对象 = 本仓库既有同类桥接类 {@link PackPostVertexSwitch}
 *    与 {@link PackChainGatingSwitch} —— 它们把 h33 实测的「配置键名当 Java 字段名 ⇒
 *    {@code NoSuchFieldException} 被吞 ⇒ 开关恒为默认值且日志看起来完全正常」这条教训固化成
 *    两个分开的常量。全部为仓库内自有代码与自有文档事实 → 可并入本项目（LGPL-3.0-or-later）；例外条款：无。
 *
 * <p>1. 官方/主实现：NeoForge {@code ModConfigSpec}（只用 {@code get()} 读值）。
 *
 * <p>2. 备选：{@code PackTerrainSource} 直引 {@code VkDispConfig.MRT_DERIVATIVE_TBN.get()} ——
 *    <b>否决，且这是本轮真踩到的一次红</b>：首版就是这么写的，{@code PackTerrainSourceTest} 与
 *    本类的真包测试当场炸 {@code IllegalStateException: Cannot get config value before config is loaded}
 *    （{@code dev.vkdisp.pack} 的测试类路径不该把整个 FML 配置体系拖进来，{@link PackPostVertexSwitch}
 *    类注释第 2 条记过同族）。⇒ 走反射桥接 + 「类在、配置没加载」这第三态回落默认值。
 *
 * <p>3. 我们的差异点：<b>默认关</b> —— 与 {@link PackPostVertexSwitch} 的「默认开」相反，理由要说清：
 *    那一条的「关」是回到<b>已知错</b>的行为（零向量供 sunVec/upVec = 真机定案根因），
 *    而本条的「关」是回到今天<b>已登记在案</b>的常量供值档（GAP-007），画面弱但不指鹿为马。
 *    ⇒ 本条按 M1「逐个开启」与 GAP-036 ④「坏包会崩帧」的既有裁决保持默认关，翻默认属 {@code 20} P5.1。
 *
 * <p>4. 许可证核对：本项目 LGPL-3.0-or-later；零第三方代码复制。
 * <p>5. 性能基线：❄️ 冷路径（每条程序生成期读一次），不做优化（18-PARALLEL §7.7）。
 */
public final class DerivativeTbnSwitch {

    /** 配置键名（与 {@code VkDispConfig} 里的 {@code define} 键逐字一致，单点真源）。 */
    public static final String CONFIG_KEY = "mrt.derivativeTbn";

    /** 🔴 {@code VkDispConfig} 里承载它的 <b>Java 字段名</b>（反射用，与键名是两个量）。 */
    public static final String FIELD_NAME = "MRT_DERIVATIVE_TBN";

    /** 默认值：关（关 = 逐字等于今天那三条常量供值，见类注释第 3 条）。 */
    public static final boolean DEFAULT_ENABLED = false;

    /** 单测覆盖槽；{@code null} = 读 FML 配置（无 FML 环境时回落 {@link #DEFAULT_ENABLED}）。 */
    private static volatile BooleanSupplier override;

    /** 反射失败原文（{@code null} = 没失败过）；只记第一次。 */
    private static volatile String reflectionFailure;

    private DerivativeTbnSwitch() {}

    /**
     * 单测 / A/B 专用：覆盖开关来源（传 {@code null} 恢复读 FML 配置）。
     *
     * <p>⚠️ 生产路径不调它；测试用完<b>必须</b>复位回 null，否则静态槽会漏进别的测试（QD-03）。
     */
    public static void override(BooleanSupplier supplier) {
        override = supplier;
    }

    /** 本条程序生成时是否做导数接管。 */
    public static boolean enabled() {
        BooleanSupplier supplier = override;
        if (supplier != null) {
            return supplier.getAsBoolean();
        }
        try {
            Class<?> config = Class.forName("dev.vkdisp.VkDispConfig");
            Object value = config.getField(FIELD_NAME).get(null);
            if (value instanceof net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<?> configValue) {
                if (!SwitchConfigAccess.loaded(config)) {
                    return DEFAULT_ENABLED; // 第三态：类在、配置没加载（单测 / 非游戏 JVM）⇒ 不是开关坏掉
                }
                return Boolean.TRUE.equals(configValue.get());
            }
            noteReflectionFailure("字段 " + FIELD_NAME + " 的类型不是布尔配置项（实际 "
                    + (value == null ? "null" : value.getClass().getName()) + "）");
            return DEFAULT_ENABLED;
        } catch (ClassNotFoundException e) {
            return DEFAULT_ENABLED; // 无 FML 环境（单测）⇒ 默认值，不是错误
        } catch (NoClassDefFoundError e) {
            return DEFAULT_ENABLED; // 「类在、它引用的 FML 类型不在」= 本进程不是游戏进程（h33 同族）
        } catch (NoSuchFieldException e) {
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

    /** 清空失败原文（测试隔离用）。 */
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
