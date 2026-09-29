package dev.vkdisp.glsl;

/**
 * 【参考调研】F3 诊断契约 / 04-SPEC §3.2 与 08-TESTING §4 对 #include 行号与选项识别的事实性要求
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/04-SPEC.md §3.2（glsl/ 组件清单：#include 相对路径可嵌套、
 *    选项常量识别）与 docs/08-TESTING.md §4（解析验收：#include、const int X = 0; // [0 1 2]）。
 *    外部候选 IrisShaders/glsl-preprocessor（GPL-3.0 + 例外条款）与 glsl-transformer
 *    （自定义传染许可）→ 例外条款是否覆盖本项目未核实，一律按禁止处理
 *    （17-NATIVE §1.1.1 陷阱 2、07-CONSTRAINTS X20/X21），本任务不读其代码、零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 契约类，不含任何外部项目代码。
 *    → 能否并入本项目（MIT）：可以 —— 只采纳不受版权保护的事实性信息（格式语义、行号要求）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：本仓库 docs/18-PARALLEL.md §3 F3 与 §4 C/D 线定义 —— 诊断必须携带
 *    severity / message / 原文件 / 原行号 / 可选列号；C 线用它报「循环包含」等指回原文件的错，
 *    D 线用它报 attribute/varying 重写问题（事实性来源 = 04-SPEC §3.2、08-TESTING §4）。
 * 2. 备选：javac / GLSL 校验器的 "文件:行:列: 级别: 消息" 控制台约定 —— 只借 format()
 *    的输出格式这一事实性约定，不引用、不复制任何编译器类型或代码。
 * 3. 我们的差异点：通用编译器诊断只带"当前文件坐标"，#include 展开后无法指回原文件；
 *    本契约配合 SourceLineMap 反查 + locatedAt() 回填，并固定 ERROR/WARN/INFO 三档，
 *    以满足 T11「降级必须显式报错或 WARN」与 D 线幂等自检的可见性要求。
 * 4. 许可证核对结论：本项目 MIT；GPL-3.0+例外与自定义传染参考按禁止处理，只读思路，
 *    零代码并入（07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径（包加载期一次性产生）；08-TESTING §8「解析+转译全部 program ≤ 1 秒」
 *    是 P2 主线验收线。本类为不可变 record，无缓存、无性能优化（18-PARALLEL §7.7、T14 达标即停）。
 */
import java.util.Objects;

/**
 * 一条 GLSL 转译诊断（闸门 F3 冻结契约的一部分）。
 *
 * <p><b>冻结契约，变更走 18-PARALLEL §3.2（经 env-1 统一改）。</b>
 * 本类与 {@link TranslateResult}、{@link SourceLineMap} 一起构成 C 线（预处理）与
 * D 线（转译）的公共接口：两线各自构造诊断、互相消费对方的诊断，谁都不需要改本契约。
 *
 * <p><b>坐标约定</b>：{@code line} 与 {@code column} 均从 1 起；{@code <= 0} 一律表示未知
 * （构造时自动归一为 {@link #UNKNOWN_LINE} / {@link #UNKNOWN_COLUMN}，负值不会泄漏给调用方）。
 * {@code sourceFile == null} 表示原文件未知（整文件级 / 无位置诊断）。
 *
 * <p><b>null 语义</b>：{@code severity} 与 {@code message} 是诊断的本体，为 {@code null}
 * 立即抛 {@link NullPointerException}（快速失败 —— 不许造出"没有内容的诊断"）；
 * {@code sourceFile} 允许为 {@code null}（位置可缺省，见上）。
 *
 * <p>典型用法（C / D 背靠背，双方都只依赖本契约）：
 * <pre>{@code
 * // C 线：循环包含，直接给出原文件坐标
 * TranslateDiagnostic.error("循环包含：a.glsl -> common.glsl -> a.glsl", "shaders/a.glsl", 3);
 *
 * // D 线：先把预处理输出行经上游映射回原文件，再落位
 * SourceLineMap.LineOrigin origin = preprocessResult.originOf(outputLine);
 * TranslateDiagnostic.warn("attribute 已重写为 in")
 *         .locatedAt(origin.sourceFile(), origin.sourceLine());
 * }</pre>
 */
public record TranslateDiagnostic(
        Severity severity, String message, String sourceFile, int line, int column) {

    /** 诊断严重级别；至少 ERROR / WARN / INFO 三档（T11：降级必须显式报错或 WARN）。 */
    public enum Severity {
        /** 致命错误：结果不可用；存在任一 ERROR 时 {@link TranslateResult#isSuccess()} = false。 */
        ERROR,
        /** 警告：结果仍可用，但有降级 / 风险，必须显式可见，不许静默（T11）。 */
        WARN,
        /** 提示信息：不影响结果可用性。 */
        INFO;

        /** 是否为 ERROR 级。 */
        public boolean isError() {
            return this == ERROR;
        }
    }

    /** 行号未知的取值（行号从 1 起，{@code <= 0} 归一为此值）。 */
    public static final int UNKNOWN_LINE = 0;

    /** 列号未知的取值（列号从 1 起，{@code <= 0} 归一为此值）。 */
    public static final int UNKNOWN_COLUMN = 0;

    /** 记录构造：校验本体字段、归一位置字段（语义见类注释）。 */
    public TranslateDiagnostic {
        Objects.requireNonNull(severity, "severity 不许为 null");
        Objects.requireNonNull(message, "message 不许为 null");
        if (line < UNKNOWN_LINE) {
            line = UNKNOWN_LINE;
        }
        if (column < UNKNOWN_COLUMN) {
            column = UNKNOWN_COLUMN;
        }
    }

    // ------------------------------------------------------------------ 工厂

    /** 无位置诊断（整文件级 / 位置未知，例如"整个阶段转译失败"）。 */
    public static TranslateDiagnostic of(Severity severity, String message) {
        return new TranslateDiagnostic(severity, message, null, UNKNOWN_LINE, UNKNOWN_COLUMN);
    }

    /** 带文件与行号的诊断（无列号）。 */
    public static TranslateDiagnostic of(
            Severity severity, String message, String sourceFile, int line) {
        return new TranslateDiagnostic(severity, message, sourceFile, line, UNKNOWN_COLUMN);
    }

    /** 带完整位置（文件 + 行 + 列）的诊断。 */
    public static TranslateDiagnostic of(
            Severity severity, String message, String sourceFile, int line, int column) {
        return new TranslateDiagnostic(severity, message, sourceFile, line, column);
    }

    /** ERROR 级、无位置。 */
    public static TranslateDiagnostic error(String message) {
        return of(Severity.ERROR, message);
    }

    /** ERROR 级、原文件 + 行号（C 线"循环包含报错"的默认形态）。 */
    public static TranslateDiagnostic error(String message, String sourceFile, int line) {
        return of(Severity.ERROR, message, sourceFile, line);
    }

    /** ERROR 级、完整位置。 */
    public static TranslateDiagnostic error(
            String message, String sourceFile, int line, int column) {
        return of(Severity.ERROR, message, sourceFile, line, column);
    }

    /** WARN 级、无位置。 */
    public static TranslateDiagnostic warn(String message) {
        return of(Severity.WARN, message);
    }

    /** WARN 级、原文件 + 行号。 */
    public static TranslateDiagnostic warn(String message, String sourceFile, int line) {
        return of(Severity.WARN, message, sourceFile, line);
    }

    /** INFO 级、无位置。 */
    public static TranslateDiagnostic info(String message) {
        return of(Severity.INFO, message);
    }

    /** INFO 级、原文件 + 行号。 */
    public static TranslateDiagnostic info(String message, String sourceFile, int line) {
        return of(Severity.INFO, message, sourceFile, line);
    }

    // ------------------------------------------------------------------ 查询

    /** 是否携带可用位置（原文件与原行号都已知）。 */
    public boolean hasLocation() {
        return sourceFile != null && line > UNKNOWN_LINE;
    }

    /**
     * 回填位置：返回位置为 {@code sourceFile:line} 的副本（列号归为未知）。
     *
     * <p>D 线标准动作：先建无位置诊断，经 {@link SourceLineMap#originOf(int)} 反查原文件后落位。
     */
    public TranslateDiagnostic locatedAt(String sourceFile, int line) {
        return new TranslateDiagnostic(severity, message, sourceFile, line, UNKNOWN_COLUMN);
    }

    /** 回填位置：返回位置为 {@code sourceFile:line:column} 的副本。 */
    public TranslateDiagnostic locatedAt(String sourceFile, int line, int column) {
        return new TranslateDiagnostic(severity, message, sourceFile, line, column);
    }

    /**
     * 编译器风格的单行文本，便于直接打日志（T11：失败不许静默）。
     *
     * <p>格式：{@code ERROR: shaders/a.glsl:3:5: 消息}；位置不全时逐级省略段。
     */
    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append(severity).append(": ");
        if (sourceFile != null) {
            sb.append(sourceFile);
            if (line > UNKNOWN_LINE) {
                sb.append(':').append(line);
                if (column > UNKNOWN_COLUMN) {
                    sb.append(':').append(column);
                }
            }
            sb.append(": ");
        }
        return sb.append(message).toString();
    }
}
