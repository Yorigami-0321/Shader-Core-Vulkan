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

    /**
     * 🔴 {@code VkDispConfig} 里承载它的 **Java 字段名**（反射用）。
     *
     * <p>🔖 <b>为什么必须与 {@link #CONFIG_KEY} 分成两个常量（h33 实测修的真 bug）</b>：
     * 旧版只有一个常量，并把它同时当成「配置键名」与「反射字段名」用 ——
     * {@code getField("pack.capabilityGate")}，而真实字段叫 {@code CAPABILITY_GATE}
     * ⇒ 每次都抛 {@link NoSuchFieldException} ⇒ 被 {@code catch (Throwable)} 吞掉
     * ⇒ <b>永远返回默认值 false</b>。
     *
     * <p>症状极具欺骗性：开关写进配置文件、日志也照打，只是<b>永远不生效</b>，
     * 且<b>看起来完全正常</b>（无异常、无告警）。
     * 这与已闭环的 QD-02「{@code debugLog} 死开关」是同一族失败形态。
     *
     * <p>⇒ 同一个语义不要有两处表示（h32 轮一已因「星号两处表示」踩过一次）。
     */
    public static final String FIELD_NAME = "CAPABILITY_GATE";

    /** 默认值：关（理由见类注释）。 */
    public static final boolean DEFAULT_ENABLED = false;

    /** 单测覆盖槽；null = 走 {@link #DEFAULT_ENABLED}（运行时即读 FML 配置）。 */
    private static volatile BooleanSupplier override;

    /**
     * 反射失败的原文（{@code null} = 没失败过）。
     *
     * <p>🔖 存<b>第一个</b>失败原因即可：这是「恒定坏掉」的状态，不是每帧抖动的状态，
     * 记多条只会让调用方输出重复内容。
     */
    private static volatile String reflectionFailure;

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
            // 🔴 h33：这里曾经把配置键名当字段名传给 getField —— 配置键名不是字段名 ⇒
            //   NoSuchFieldException ⇒ 被下面的 catch(Throwable) 吞掉 ⇒ 开关恒为关。
            Object value = config.getField(FIELD_NAME).get(null);
            if (value instanceof net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<?> configValue) {
                return Boolean.TRUE.equals(configValue.get());
            }
            // 🔶 字段存在但类型不是布尔配置项 = 配置被改坏了 ⇒ 报错 + 按默认（关），
            //   **不**静默当成 true（默认开会改变用户画面）。
            noteReflectionFailure("字段 " + FIELD_NAME + " 的类型不是布尔配置项（实际 "
                    + (value == null ? "null" : value.getClass().getName()) + "）");
            return DEFAULT_ENABLED;
        } catch (ClassNotFoundException e) {
            // 无 FML 环境（单测）⇒ 默认值，不是错误。
            return DEFAULT_ENABLED;
        } catch (NoSuchFieldException e) {
            // 🔴🔴 **这是真错误，不是「默认关」**：字段被改名/被删会让开关静默失效。
            //   h33 实测的正是这个：开关写进配置、日志照打、就是不生效，且毫无异常。
            //   ⇒ 按 X9（不猜）与 T11（降级必须可见）必须吵出来。
            noteReflectionFailure("dev.vkdisp.VkDispConfig 上找不到字段 " + FIELD_NAME
                    + "（配置键 " + CONFIG_KEY + " 的 Java 字段名写错或该字段已被删除）");
            return DEFAULT_ENABLED;
        } catch (Throwable t) {
            // 🔶 其它异常（反射被禁 / 字段不可读）⇒ 默认值，但**原文**必须可见（X9 不猜）。
            noteReflectionFailure("读取字段 " + FIELD_NAME + " 时发生非预期异常：" + t);
            return DEFAULT_ENABLED;
        }
    }

    /**
     * 记录反射失败原因（**只记第一次**），供调用方转成可见诊断。
     *
     * <p>🔖 <b>为什么不自己打日志</b>：本类被刻意做成既不引 FML 配置体系、也不引 MC 类型
     * （见类注释第 0 条；{@code PackCompileCache} 已实测踩过
     * {@code NoClassDefFoundError: IConfigSpec}）。而 h33 实测又发现**单测 classpath 上没有
     * slf4j**（{@code PackBooleanOptionTest} 因此炸成
     * {@code NoClassDefFoundError: org/slf4j/LoggerFactory}）——
     * 在这里打日志会让「诊断手段」本身把无关测试拖挂。
     *
     * <p>⇒ 改为<b>记录</b>原因，由调用方 {@code PackTerrainSource} 走既有的
     * {@code TranslateDiagnostic} 管道输出 —— 那条管道本来就负责把包相关问题
     * 送进用户看得见的包诊断日志。
     */
    private static void noteReflectionFailure(String reason) {
        if (reflectionFailure != null) {
            return;
        }
        reflectionFailure = reason;
    }

    /**
     * 上一次反射失败的原文；{@code null} = 至今没失败过。
     *
     * <p>🔖 调用方<b>必须</b>把它显示出来：开关恒为默认关这件事若不吵出来，
     * 就是 h33 实测的那个形态 —— 用户写进配置、日志照打、就是不生效，且毫无异常。
     */
    public static String reflectionFailure() {
        return reflectionFailure;
    }
}
