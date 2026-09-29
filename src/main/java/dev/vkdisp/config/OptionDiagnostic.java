package dev.vkdisp.config;
/**
 * 【参考调研】F 线选项模型 / 诊断条目（纯数据）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §3.5（config/ 组件职责：PackOptions = 用户包声明的选项的运行时值；
 *    OptionBinding = 选项 → 着色器 #define / uniform 的绑定）；② docs/18-PARALLEL.md §4 F 线（独占路径、不许写
 *    PackOptionsScreen、不许碰 GPU）与 §7.3（并行线证据规范）；③ docs/07-CONSTRAINTS.md T11
 *    （任何降级路径必须显式报错或打 WARN，不许静默）；④ docs/08-TESTING.md §4（选项枚举的解析验收）。
 *    许可证：本仓库自有文档（本项目 MIT）→ 可直接消费，不涉及第三方素材。
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）、OptiFine（无 LICENSE = ARR）与 Iris（LGPL-3.0）
 *    的源码一律零接触 —— 按 docs/07-CONSTRAINTS.md L12 / X20「判不过就换参考，不再读它的代码」。
 *    → 能否并入本项目（MIT）：本文件为独立实现，可并入
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：本仓库自身约束（T11）与 F2/F3 已冻结契约的 Javadoc 风格（severity / code / message 三字段）。
 * 2. 备选：直接抛异常 —— 否决（一次 set / 一次 profile 应用会同时产生多条诊断（例如越界钳制 + 未知选项），
 *    异常只能报第一条；且调用方要把全部诊断一起打印比对）。
 *    备选：复用 E 线 dev.vkdisp.pipeline.model.ModelDiagnostic —— 否决（它不在 F2/F3 冻结契约内，
 *    跨并行线 import 非冻结类会把两条线的内部实现绑在一起；18-PARALLEL §4 要求各线在独占路径内自洽）。
 * 3. 我们的差异点：只保留 severity / code / message 三字段，code 为稳定大写诊断码供单测逐条断言；
 *    不引入 i18n、不引入行号映射（F 线输入是内存里的选项值，不是源文件）；INFO 级只进诊断列表，
 *    WARN / ERROR 同时转发给 OptionDiagnosticSink（T11 的"不静默"落点）。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制，仅含本仓库约束文档要求的结构。
 * 5. 性能基线：❄️ 冷路径小对象（切包 / 改选项时一次产出），不做任何性能优化（18-PARALLEL §7.7）。
 */
import java.util.Objects;

/**
 * F 线内部使用的显式诊断条目（{@code 07-CONSTRAINTS.md} T11：降级绝不静默）。
 *
 * <p>纯数据 record，可直接在单测里做逐条比对。{@link Severity#ERROR} 表示该次操作被拒绝且没有可用的降级结果
 * （例如 profile 不存在）；{@link Severity#WARN} 表示已做确定性降级（例如越界钳制到最近的允许值、非法值被丢弃
 * 并保留旧值），调用方必须让它可见（转发给 {@link OptionDiagnosticSink}）。
 *
 * @param severity 严重级
 * @param code     稳定诊断码（大写蛇形；供单测断言，不要当用户文案用）
 * @param message  人类可读说明（英文，含足够上下文供定位）
 */
public record OptionDiagnostic(Severity severity, String code, String message) {

    /** 诊断严重级。 */
    public enum Severity {
        /** 操作被拒绝且无可用的降级结果（例如 profile 未定义、选项名不存在）。 */
        ERROR,
        /** 已做确定性降级（越界钳制 / 非法值丢弃 / 缺省推导），行为可预期但必须可见。 */
        WARN,
        /** 合法但值得说明的边界（例如空选项集）。 */
        INFO
    }

    /** 紧凑构造器：字段全非空（null 视为调用方错误，直接抛，不静默）。 */
    public OptionDiagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }

    /** 构造 ERROR 级诊断。 */
    public static OptionDiagnostic error(String code, String message) {
        return new OptionDiagnostic(Severity.ERROR, code, message);
    }

    /** 构造 WARN 级诊断。 */
    public static OptionDiagnostic warn(String code, String message) {
        return new OptionDiagnostic(Severity.WARN, code, message);
    }

    /** 构造 INFO 级诊断。 */
    public static OptionDiagnostic info(String code, String message) {
        return new OptionDiagnostic(Severity.INFO, code, message);
    }

    /** 是否为 ERROR（调用方据此认定本次操作没有生效）。 */
    public boolean isError() {
        return this.severity == Severity.ERROR;
    }

    /** 单行打印形式，供日志与单测比对：{@code [WARN] CODE: message}。 */
    public String format() {
        return "[" + this.severity + "] " + this.code + ": " + this.message;
    }
}
