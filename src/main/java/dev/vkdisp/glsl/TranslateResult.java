package dev.vkdisp.glsl;

import java.util.ArrayList;
import java.util.List;

/**
 * 【参考调研】F3 转译结果契约 / 04-SPEC §3.2 与 08-TESTING §4 对 #include 行号映射与选项识别的事实性要求
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/18-PARALLEL.md §3 F3（"TranslateResult 文本 + 诊断列表"签名冻结）
 *    与 docs/08-TESTING.md §4（#include、const int X = 0; // [0 1 2] 选项识别验收）——
 *    仓库内事实性需求，不受版权保护。外部候选 IrisShaders/glsl-preprocessor（GPL-3.0 +
 *    例外条款）与 glsl-transformer（自定义传染许可）→ 例外是否覆盖本项目未核实，一律按
 *    禁止处理（17-NATIVE §1.1.1 陷阱 2、07-CONSTRAINTS X20/X21），本任务不读其代码。
 *    许可证：本文件为独立编写的纯 Java 契约类，不含任何外部项目代码。
 *    → 能否并入本项目（MIT）：可以 —— 只采纳不受版权保护的事实性信息（格式语义、行号要求）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：本仓库 18-PARALLEL §3 F3 / §4 C、D 线完成标准 —— 结果 = 转译文本 +
 *    可空诊断列表 + 原文件行号映射（C 线"行号映射保留：编译报错指回原文件"、D 线"幂等 +
 *    重写诊断"）；事实性来源 = 04-SPEC §3.2（glsl/ 组件清单）、08-TESTING §4（解析验收）。
 * 2. 备选：javac / GLSL 校验器的"结果对象 + 诊断列表"惯用形态 —— 只借"成功与否由诊断级别
 *    决定、文本与诊断同对象返回"这一事实性设计，不引用、不复制任何编译器类型或代码。
 * 3. 我们的差异点：通用编译器结果只回"当前文件 + 行"，#include 展开后指不回原文件；本契约把
 *    SourceLineMap 作为结果的一等字段，C 线产出、D 线消费并 compose，两线背靠背不改契约。
 * 4. 许可证核对结论：本项目 MIT；GPL-3.0+例外与自定义传染参考按禁止处理，只读思路，
 *    零代码并入（07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径（包加载期一次性产生，解析+转译全部 program ≤ 1 秒为 P2 主线验收，
 *    见 08-TESTING §8）；本类为不可变 record，线性判定成败，无缓存、无性能优化
 *    （18-PARALLEL §7.7、T14 达标即停）。
 */
/**
 * GLSL 转译（预处理 / 转译任一阶段）的输出结果：文本 + 诊断列表 + 原文件行号映射。
 *
 * <p><b>冻结契约，变更走 18-PARALLEL §3.2（经 env-1 统一改）。</b>
 * 本类与 {@link TranslateDiagnostic}、{@link SourceLineMap} 一起构成 C 线（预处理）与
 * D 线（转译）的输入输出契约：C 线产出本对象，D 线以本对象为入参、再产出本对象，
 * 双方都只依赖本契约，任何一方都不需要改它。
 *
 * <p><b>null 语义（调用方永远不需要判空）</b>：
 * <ul>
 *   <li>所有静态工厂永不返回 {@code null}；</li>
 *   <li>{@link #text()} 永不为 {@code null} —— 入参为 {@code null} 时归一为空串
 *       {@code ""}；<b>成败请用 {@link #isSuccess()} 判断，不要用 {@code text().isEmpty()}</b>；</li>
 *   <li>{@link #diagnostics()} 永不为 {@code null} 且不可变 —— 入参为 {@code null} 时归一为
 *       空列表（"诊断列表可空"指允许为空，不是允许返回 {@code null}）；诊断元素本身为
 *       {@code null} 则立即抛 {@link NullPointerException}（快速失败）；</li>
 *   <li>{@link #lineMap()} 永不为 {@code null} —— 入参为 {@code null} 时归一为
 *       {@link SourceLineMap#unmapped()}（查询恒未命中，不会 NPE）。</li>
 * </ul>
 *
 * <p><b>坐标约定</b>：行号从 1 起，{@code 0} = 未知（{@link TranslateDiagnostic#UNKNOWN_LINE}）。
 * 结果内的诊断默认携带"该阶段坐标系"的行号；需要指回原文件时经 {@link #originOf(int)} /
 * {@link #outputLineOf(int)} 反查（见 {@link SourceLineMap}）。
 *
 * <p>典型用法（C / D 背靠背）：
 * <pre>{@code
 * // C 线产出
 * TranslateResult pre = TranslateResult.success(expandedText, cMap);
 *
 * // D 线消费 + 再产出（诊断指回原文件、映射端到端合成）
 * SourceLineMap endToEnd = dMap.compose(pre.lineMap());
 * TranslateResult out = TranslateResult.withDiagnostics(dText, endToEnd, diags);
 *
 * // 调用方（编译入口）统一收口：不判空、不猜
 * if (!out.isSuccess()) {
 *     for (TranslateDiagnostic d : out.diagnostics()) {
 *         LOGGER.error("vkdisp: {}", d.format());
 *     }
 * }
 * }</pre>
 *
 * <p><b>成败语义</b>：{@link #isSuccess()} == "不存在 ERROR 级诊断"，是唯一判据；
 * {@link #failure(...)} 系工厂保证结果 {@code isSuccess() == false}（列表缺 ERROR 时补一条
 * 通用 ERROR —— T11：失败不许"像没发生过"）；{@link #withDiagnostics(...)} 不做该保证，
 * 仅含 WARN / INFO 时仍是成功结果。
 */
public record TranslateResult(
        String text, List<TranslateDiagnostic> diagnostics, SourceLineMap lineMap) {

    /** 记录构造：按类注释的 null 语义归一三个入参（诊断列表同时被冻结为不可变）。 */
    public TranslateResult {
        text = text == null ? "" : text;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        lineMap = lineMap == null ? SourceLineMap.unmapped() : lineMap;
    }

    // ------------------------------------------------------------------ 工厂

    /** 纯成功结果：无诊断、空映射（单文件原样透传场景）。 */
    public static TranslateResult success(String text) {
        return new TranslateResult(text, List.of(), SourceLineMap.unmapped());
    }

    /** 成功结果 + 行号映射（C 线无 #include 但要保留行号时可传 {@link SourceLineMap#identity}）。 */
    public static TranslateResult success(String text, SourceLineMap lineMap) {
        return new TranslateResult(text, List.of(), lineMap);
    }

    /**
     * 带诊断的结果（成败由"是否含 ERROR"决定）。
     *
     * @param diagnostics 诊断列表；{@code null} 归一为空列表
     */
    public static TranslateResult withDiagnostics(
            String text, List<TranslateDiagnostic> diagnostics) {
        return new TranslateResult(text, diagnostics, SourceLineMap.unmapped());
    }

    /**
     * 带诊断与行号映射的结果（C / D 两线产出的标准形态）。
     *
     * @param diagnostics 诊断列表；{@code null} 归一为空列表
     * @param lineMap     原文件行号映射；{@code null} 归一为 {@link SourceLineMap#unmapped()}
     */
    public static TranslateResult withDiagnostics(
            String text, SourceLineMap lineMap, List<TranslateDiagnostic> diagnostics) {
        return new TranslateResult(text, diagnostics, lineMap);
    }

    /**
     * 失败结果（无输出文本）。
     *
     * <p>保证 {@link #isSuccess()} == false：列表不含 ERROR 时自动补一条通用 ERROR（T11）。
     */
    public static TranslateResult failure(List<TranslateDiagnostic> diagnostics) {
        return failure("", SourceLineMap.unmapped(), diagnostics);
    }

    /**
     * 失败结果（保留部分输出文本，仅供诊断展示，<b>不要当作可用转译结果使用</b>）。
     *
     * <p>保证 {@link #isSuccess()} == false：列表不含 ERROR 时自动补一条通用 ERROR（T11）。
     */
    public static TranslateResult failure(
            String text, List<TranslateDiagnostic> diagnostics) {
        return failure(text, SourceLineMap.unmapped(), diagnostics);
    }

    /**
     * 失败结果（部分输出文本 + 行号映射）。
     *
     * <p>保证 {@link #isSuccess()} == false：列表不含 ERROR 时自动补一条通用 ERROR（T11）。
     */
    public static TranslateResult failure(
            String text, SourceLineMap lineMap, List<TranslateDiagnostic> diagnostics) {
        return new TranslateResult(text, ensureError(diagnostics), lineMap);
    }

    /**
     * 便捷失败：单条带原文件行号的 ERROR + 空输出文本
     * （C 线"循环包含 / 缺文件"这类一步报错场景）。
     */
    public static TranslateResult failureAt(String sourceFile, int line, String message) {
        return new TranslateResult(
                "",
                List.of(TranslateDiagnostic.error(message, sourceFile, line)),
                SourceLineMap.unmapped());
    }

    // ------------------------------------------------------------------ 查询

    /**
     * 是否成功 —— 唯一判据：不存在 ERROR 级诊断（WARN / INFO 不影响可用性）。
     *
     * <p>等价于 {@code !hasErrors()}；结果对象本身永不为 {@code null}，直接调用即可。
     */
    public boolean isSuccess() {
        return !hasErrors();
    }

    /** 是否存在 ERROR 级诊断。 */
    public boolean hasErrors() {
        for (TranslateDiagnostic diagnostic : diagnostics) {
            if (diagnostic.severity() == TranslateDiagnostic.Severity.ERROR) {
                return true;
            }
        }
        return false;
    }

    /**
     * 全部 ERROR 级诊断（保持产生顺序）；无 ERROR 时返回空列表，永不为 {@code null}。
     */
    public List<TranslateDiagnostic> errors() {
        List<TranslateDiagnostic> errors = new ArrayList<>();
        for (TranslateDiagnostic diagnostic : diagnostics) {
            if (diagnostic.severity() == TranslateDiagnostic.Severity.ERROR) {
                errors.add(diagnostic);
            }
        }
        return List.copyOf(errors);
    }

    /**
     * 输出行 → 起源（原文件 + 原行号）——"编译 / 转译报错指回原文件"的入口。
     *
     * <p>永不返回 {@code null}（映射缺失时整行返回未知，见 {@link SourceLineMap#originOf(int)}）。
     */
    public SourceLineMap.LineOrigin originOf(int outputLine) {
        return lineMap.originOf(outputLine);
    }

    /**
     * 主文件内：源行号 → 输出行号（逐行查询）。
     *
     * @return 输出行号（1 起）；未命中返回 {@link TranslateDiagnostic#UNKNOWN_LINE}
     */
    public int outputLineOf(int sourceLine) {
        return lineMap.outputLineOf(sourceLine);
    }

    /**
     * 指定文件内：源行号 → 输出行号（文件精确匹配）。
     *
     * @param sourceFile 目标文件；{@code null} = 按主文件查
     * @return 输出行号（1 起）；未命中返回 {@link TranslateDiagnostic#UNKNOWN_LINE}
     */
    public int outputLineOf(String sourceFile, int sourceLine) {
        return lineMap.outputLineOf(sourceFile, sourceLine);
    }

    // ------------------------------------------------------------------ 内部

    /**
     * failure() 的不变量：结果必须 {@code isSuccess() == false}。
     *
     * <p>诊断列表不含 ERROR 时补一条通用 ERROR —— 降级 / 失败必须显式可见（T11）。
     * {@code null} 列表先归一为空列表再补；列表中的 {@code null} 元素跳过扫描，
     * 最终由 {@link TranslateResult} 构造的 {@code List.copyOf} 统一快速失败。
     */
    private static List<TranslateDiagnostic> ensureError(
            List<TranslateDiagnostic> diagnostics) {
        if (diagnostics != null) {
            for (TranslateDiagnostic diagnostic : diagnostics) {
                if (diagnostic != null
                        && diagnostic.severity() == TranslateDiagnostic.Severity.ERROR) {
                    return diagnostics;
                }
            }
        }
        List<TranslateDiagnostic> withError =
                diagnostics == null ? new ArrayList<>() : new ArrayList<>(diagnostics);
        withError.add(TranslateDiagnostic.error("转译失败（调用方未提供具体 ERROR 诊断）"));
        return withError;
    }
}
