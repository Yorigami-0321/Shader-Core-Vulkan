package dev.vkdisp.glsl.translate;

import java.util.ArrayList;
import java.util.List;

import dev.vkdisp.glsl.SourceLineMap;
import dev.vkdisp.glsl.TranslateDiagnostic;
import dev.vkdisp.glsl.TranslateResult;

/**
 * 【参考调研】D 线 GLSL 转译入口 / 04-SPEC §3.3 + 18-PARALLEL §4 D 线 + F3 行号契约
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/04-SPEC.md §3.3（OfGlslTranslator：OF GLSL → M GLSL 的入口）、
 *    docs/18-PARALLEL.md §4 D 线（独占路径、完成标准：幂等 + 内建 uniform 注入完整）、
 *    docs/18-PARALLEL.md §3 F3 与 src/main/java/dev/vkdisp/glsl/ 的冻结契约
 *    （TranslateResult / TranslateDiagnostic / SourceLineMap，仅消费、不修改）—— 仓库内文档与
 *    本项目自研契约，不受第三方版权约束。
 *    外部候选 IrisShaders/glsl-transformer（GPL-3.0 + 例外条款，18-PARALLEL §4 D 线明示"按禁止处理"）→
 *    按禁止处理（07-CONSTRAINTS L12 §1.3 陷阱 2 / X21），本任务不读其代码、零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 实现，不含任何外部项目代码。
 *    → 能否并入本项目（MIT）：可以 —— 只采纳不受版权保护的事实性信息（转译范围、行号契约）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：F3 契约类给出的 C / D 背靠背用法（{@code dMap.compose(cMap)} 合成端到端映射、
 *    {@code TranslateDiagnostic.locatedAt(...)} 回填原文件位置）—— 本类是该用法的落地；
 *    转译内容 = AttributeRewriter（04-SPEC §3.3）+ UniformInjector（04-SPEC §3.2）。
 * 2. 备选：无 —— 不引入任何转译框架；两级文本变换 + 一次映射合成，够用即停。
 * 3. 我们的差异点：① 入口只做"编排 + 定位"，两级变换各自独立可测；
 *    ② 诊断统一经上游 SourceLineMap 反查后回填原文件（C 线 → D 线的端到端映射）；
 *    ③ 幂等性口径：**输出文本是转译的不动点**（再转译逐字节不变），且二次转译不产生任何
 *    WARN/ERROR；因为首次转译会插入新行，输出行号映射本身按 F3 语义必须变化，故幂等断言以文本为准
 *    （单测同时断言"全量已声明样本"下整个 TranslateResult 相等，见 OfGlslTranslatorTest）。
 * 4. 许可证核对结论：本项目 MIT；GPL-3.0+例外参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径（包加载期一次）；两级线性扫描 + 一次映射合成，无缓存、无预优化
 *    （18-PARALLEL §7.7、T14 达标即停）。
 */
/**
 * D 线入口：把 OF 方言 GLSL 转译成现代 core profile GLSL（M 语法）。
 *
 * <p><b>转译内容</b>：
 * <ol>
 *   <li>{@link AttributeRewriter}：{@code attribute} / {@code varying} → {@code in} / {@code out}
 *       （按阶段定向，行内替换、行数不变）；</li>
 *   <li>{@link UniformInjector}：补齐 04-SPEC §3.2 的 23 条 OF 内建 uniform 声明（只补缺失项）。</li>
 * </ol>
 * {@code #include} / {@code #define} 属 C 线，本入口既不解析也不改写（18-PARALLEL §4 D 线"不许做"）。
 *
 * <p><b>输入输出都是 F3 契约</b>：C 线产出 {@link TranslateResult}，D 线消费它再产出同类型。
 * 两级变换的诊断行号在**本阶段输入坐标**下产生，出口统一经上游 {@link SourceLineMap} 反查、
 * 用 {@link TranslateDiagnostic#locatedAt} 回填原文件与原始行号；行号映射用
 * {@link SourceLineMap#compose} 合成端到端映射（C 线 → D 线不变契约、不需要改 F3）。
 *
 * <p><b>幂等口径</b>：{@code translate(stage, translate(stage, x).text()).text()}
 * 与 {@code translate(stage, x).text()} 逐字节相同 —— 输出文本是转译的不动点。
 * 注意：**行号映射不参与该断言** —— 首轮插入了新行，按 F3 语义输出行号必须相对首轮输入变化；
 * 若输入本就"内建 uniform 全已声明"（无插入），则整个 {@link TranslateResult} 两轮完全相等
 * （含诊断与映射），单测对此有专门断言。
 *
 * <p><b>失败语义</b>：绝不抛异常、绝不静默降级（T11）。半截声明、片元阶段的 attribute、
 * 阶段未知、块注释未闭合 → ERROR 诊断（{@link TranslateResult#isSuccess()} = false）；
 * 重复声明、跨行声明、内建 uniform 类型不符、无代码行 → WARN 诊断（结果仍可用）。
 */
public final class OfGlslTranslator {

    private OfGlslTranslator() {}

    /**
     * 纯文本入口：等价于 {@code translate(stage, TranslateResult.success(source))}。
     *
     * @param stage  着色器阶段；{@code null} 按 UNKNOWN 处理（出现 attribute/varying 即 ERROR）
     * @param source OF 方言 GLSL（{@code null} 按空串处理）
     * @return 转译结果；永不返回 {@code null}
     */
    public static TranslateResult translate(ShaderStage stage, String source) {
        return translate(stage, TranslateResult.success(source));
    }

    /**
     * 契约入口：消费 C 线的 {@link TranslateResult}（文本 + 行号映射 + 诊断），产出新的 {@link TranslateResult}。
     *
     * @param stage 着色器阶段；{@code null} 按 UNKNOWN 处理（出现 attribute/varying 即 ERROR）
     * @param input C 线输出；{@code null} 按空输入处理（不抛异常，出 WARN）
     * @return 转译结果；永不返回 {@code null}
     */
    public static TranslateResult translate(ShaderStage stage, TranslateResult input) {
        TranslateResult upstream = input == null ? TranslateResult.success("") : input;
        List<TranslateDiagnostic> diagnostics = new ArrayList<>();
        if (stage == null) {
            diagnostics.add(TranslateDiagnostic.warn("着色器阶段为 null，按 UNKNOWN 处理", null, 0));
        }
        if (upstream.text().isBlank()) {
            diagnostics.add(TranslateDiagnostic.warn(
                    "输入文本为空或仅含空白（null 亦按此处理），未做任何转译", null, 0));
            return TranslateResult.withDiagnostics(upstream.text(), upstream.lineMap(), diagnostics);
        }
        SourceLines source = SourceLines.of(upstream.text());
        AttributeRewriter.Result rewritten = AttributeRewriter.rewrite(stage, upstream.text());
        UniformInjector.Result injected = UniformInjector.inject(rewritten.text());
        for (TranslateDiagnostic diagnostic : rewritten.diagnostics()) {
            diagnostics.add(locate(diagnostic, upstream.lineMap()));
        }
        for (TranslateDiagnostic diagnostic : injected.diagnostics()) {
            diagnostics.add(locate(diagnostic, upstream.lineMap()));
        }
        SourceLineMap stageMap = buildStageMap(
                source.lineCount(), injected.insertIndex(), injected.insertedLineCount());
        SourceLineMap endToEnd = stageMap.compose(upstream.lineMap());
        return TranslateResult.withDiagnostics(injected.text(), endToEnd, diagnostics);
    }

    /**
     * 本阶段输出行 → 本阶段输入行（插入点之后整体后移 K 行，插入的 K 行是合成行）。
     *
     * <p>与 F3 的用法一致：先建 D 自己的映射，再 {@code compose(上游)} 得到端到端映射。
     */
    private static SourceLineMap buildStageMap(int inputLineCount, int insertIndex, int insertedLineCount) {
        SourceLineMap.Builder builder = SourceLineMap.builder(null);
        for (int line = 1; line <= insertIndex; line++) {
            builder.add(line);
        }
        for (int count = 0; count < insertedLineCount; count++) {
            builder.addSynthetic();
        }
        for (int line = insertIndex + 1; line <= inputLineCount; line++) {
            builder.add(line);
        }
        return builder.build();
    }

    /**
     * 把本阶段坐标的诊断回填成原文件坐标。
     *
     * <p>上游映射未命中（或本阶段行号未知）时原样返回 —— {@link TranslateDiagnostic#format()} 会逐级
     * 省略缺失的位置段，不会 NPE、不会编造位置（不许猜，X9）。
     */
    private static TranslateDiagnostic locate(TranslateDiagnostic diagnostic, SourceLineMap upstream) {
        if (diagnostic.line() <= TranslateDiagnostic.UNKNOWN_LINE) {
            return diagnostic;
        }
        SourceLineMap.LineOrigin origin = upstream.originOf(diagnostic.line());
        if (origin.sourceFile() == null && origin.sourceLine() <= TranslateDiagnostic.UNKNOWN_LINE) {
            return diagnostic;
        }
        return diagnostic.locatedAt(origin.sourceFile(), origin.sourceLine(), diagnostic.column());
    }
}
