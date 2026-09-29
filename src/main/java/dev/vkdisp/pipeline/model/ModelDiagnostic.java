package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】E 线纯计算件 / 诊断条目（纯数据）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/07-CONSTRAINTS.md T11「任何降级路径必须显式报错或打 WARN」；
 *    ② docs/18-PARALLEL.md §7.3（并行线证据要求「降级路径显式报错或 WARN」）；
 *    ③ F3 已冻结的 dev.vkdisp.glsl.TranslateDiagnostic（同仓库既有诊断条目的字段风格，仅参考风格不复制代码）。
 *    许可证：本仓库自有文档（MIT）+ 本项目自己的 F3 文件 → 可直接消费。
 *    → 能否并入本项目（MIT）：本文件为独立实现，可并入
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码；参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 /
 *      Beryl ARR）零接触 —— 按 docs/07-CONSTRAINTS.md L12「判不过就换参考，不再读它的代码」。
 * 1. 官方/主实现：本仓库自身约束（T11）与 F3 诊断风格（severity / code / message）。
 * 2. 备选：抛异常代替诊断 —— 否决（纯计算件要能在一次调用里回报多个问题，异常只能报第一个；
 *    且调用方需要把全部诊断一起打印比对）。
 * 3. 我们的差异点：E 线只用三个字段（severity / code / message），code 是稳定的大写诊断码，
 *    供单测逐条断言（EMPTY_ATTRIBUTE_SET / UNKNOWN_ATTRIBUTE_TYPE 等），不引入 i18n 或行号映射
 *    （E 线输入是内存数据，不是源文件）。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制，仅含本仓库约束文档要求的结构。
 * 5. 性能基线：冷路径上的小对象（构建期一次性产出），不做任何性能优化（18-PARALLEL §7.7）。
 */
import java.util.Objects;

/**
 * E 线内部使用的显式诊断条目（{@code 07-CONSTRAINTS.md} T11：降级绝不静默）。
 *
 * <p>纯数据 record：{@link #severity()} / {@link #code()} / {@link #message()} 三字段，
 * {@code equals} / {@code hashCode} 由 record 给出，可直接在单测里做逐条比对与打印比对。
 *
 * @param severity 严重级（ERROR 必须阻止真实管线构建；WARN 表示已做确定性降级；INFO 表示已说明的合法边界）
 * @param code     稳定诊断码（大写蛇形，供单测断言；不要当用户文案用）
 * @param message  人类可读说明（英文，避免乱码；含足够上下文供定位）
 */
public record ModelDiagnostic(Severity severity, String code, String message) {

    /** 诊断严重级。 */
    public enum Severity {
        /** 输入不合法或信息不足：调用方不得据此构建真实管线。 */
        ERROR,
        /** 已做确定性降级（如丢弃重复项）：行为可预期，但必须让调用方看见。 */
        WARN,
        /** 合法但值得说明的边界（如空属性集 = 无顶点缓冲的全屏管线）。 */
        INFO
    }

    /** 紧凑构造器：字段全部非空（null 视为调用方错误，直接抛，不静默）。 */
    public ModelDiagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }

    /** 构造 ERROR 级诊断。 */
    public static ModelDiagnostic error(String code, String message) {
        return new ModelDiagnostic(Severity.ERROR, code, message);
    }

    /** 构造 WARN 级诊断。 */
    public static ModelDiagnostic warn(String code, String message) {
        return new ModelDiagnostic(Severity.WARN, code, message);
    }

    /** 构造 INFO 级诊断。 */
    public static ModelDiagnostic info(String code, String message) {
        return new ModelDiagnostic(Severity.INFO, code, message);
    }

    /** 是否为 ERROR（调用方用它决定「拒绝构建真实管线」）。 */
    public boolean isError() {
        return this.severity == Severity.ERROR;
    }

    /** 单行打印形式，供日志与单测比对：{@code [ERROR] CODE: message}。 */
    public String format() {
        return "[" + this.severity + "] " + this.code + ": " + this.message;
    }
}
