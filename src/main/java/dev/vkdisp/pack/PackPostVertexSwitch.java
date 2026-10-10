package dev.vkdisp.pack;

import java.util.function.BooleanSupplier;

/**
 * 【自行补充】GAP-030 · 「接包自己的 post 顶点程序」总开关的<b>读取侧</b>
 *
 * <p>0. 合规核对（第 0 步闸门）：参考对象 = 本仓库既有同类桥接类 {@link PackChainGatingSwitch}
 * —— 它把 h33 实测的「配置键名当 Java 字段名 ⇒ {@code NoSuchFieldException} 被吞 ⇒ 开关恒为默认值
 * 且日志看起来完全正常」这条教训固化成两个分开的常量。全部为仓库内自有代码与自有文档事实
 * → 可并入本项目（MIT）；例外条款：无。
 *
 * <p>1. 官方/主实现：NeoForge {@code ModConfigSpec}（只用 {@code get()} 读值）。
 *
 * <p>2. 备选：{@code PackPostChain} 直引 {@code VkDispConfig} 字段 —— <b>否决</b>：
 * {@code dev.vkdisp.pack} 的测试类路径会被拖进整个 FML 配置体系
 * （{@code PackCompileCache} 实测踩过 {@code NoClassDefFoundError: IConfigSpec}）。
 *
 * <p>3. 我们的差异点：<b>默认开</b>，理由与 {@link PackChainGatingSwitch} 同源且这次更硬：
 * 关着跑的是「我方适配层把 {@code sunVec/upVec/eastVec} 按<b>零向量</b>供」那一档 ——
 * 那是 2026-10-10 真机「屏幕双向镜像虚影 + 固定间隔长条云」的<b>已定案根因</b>（GAP-030），
 * 所以「关」是回到一个<b>已知错</b>的行为，不是保守行为。留这个键的唯一用途是 A/B 取证。
 *
 * <p>4. 许可证核对：本项目 MIT；零第三方代码复制。
 * <p>5. 性能基线：❄️ 冷路径（每次链装配读一次），不做优化（18-PARALLEL §7.7）。
 */
public final class PackPostVertexSwitch {

    /** 配置键名（与 {@code VkDispConfig} 里的 {@code define} 键逐字一致，单点真源）。 */
    public static final String CONFIG_KEY = "pack.postVertexProgram";

    /**
     * 🔴 {@code VkDispConfig} 里承载它的 <b>Java 字段名</b>（反射用）。
     *
     * <p>🔖 必须与 {@link #CONFIG_KEY} 分成两个常量 —— 理由见 h33（{@code PackChainGatingSwitch}
     * 的类注释第 0 条）：混用一个名字时，字段找不到只会被 {@code catch (Throwable)} 吞掉，
     * 于是「开关写了但永远不生效」在日志里完全看不出来。
     */
    public static final String FIELD_NAME = "PACK_POST_VERTEX_PROGRAM";

    /** 默认值：开（关 = 回到零向量占位那档已知错误，见类注释第 3 条）。 */
    public static final boolean DEFAULT_ENABLED = true;

    /** 单测覆盖槽；{@code null} = 读 FML 配置（无 FML 环境时回落 {@link #DEFAULT_ENABLED}）。 */
    private static volatile BooleanSupplier override;

    /** 反射失败原文（{@code null} = 没失败过）；只记第一次。 */
    private static volatile String reflectionFailure;

    private PackPostVertexSwitch() {}

    /**
     * 单测 / A/B 专用：覆盖开关来源（传 {@code null} 恢复读 FML 配置）。
     *
     * <p>⚠️ 生产路径不调它；测试用完<b>必须</b>复位回 null，否则静态槽会漏进别的测试（QD-03）。
     */
    public static void override(BooleanSupplier supplier) {
        override = supplier;
    }

    /** 本次链装配是否接包自己的 post 顶点程序。 */
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
            noteReflectionFailure("字段 " + FIELD_NAME + " 的类型不是布尔配置项（实际 "
                    + (value == null ? "null" : value.getClass().getName()) + "）");
            return DEFAULT_ENABLED;
        } catch (ClassNotFoundException e) {
            return DEFAULT_ENABLED; // 无 FML 环境（单测）⇒ 默认值，不是错误
        } catch (NoClassDefFoundError e) {
            // 「类在、它引用的 FML 类型不在」= 本进程不是游戏进程 ⇒ 与开关坏掉是两件事（h33 同族）。
            return DEFAULT_ENABLED;
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
