package dev.vkdisp.glsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.vkdisp.glsl.preprocess.ConstEvaluator;
import dev.vkdisp.glsl.preprocess.GlslPreprocessor;
import dev.vkdisp.glsl.preprocess.IncludeResolver;
import dev.vkdisp.glsl.translate.OfGlslTranslator;
import dev.vkdisp.glsl.translate.ShaderStage;

/**
 * 【参考调研】C+D 汇合管线入口 / 18-PARALLEL §4 C+D 汇合（P2.3 "#include 的 program 编译通过"）
 * + F3 冻结契约 + §7.3 证据规范
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/18-PARALLEL.md §4（C 线与 D 线的完成标准、§6 汇合点"C + D → P2.3"）、
 *    §3 F3（TranslateResult 文本 + 诊断列表 + SourceLineMap 行号映射的冻结签名）、
 *    docs/04-SPEC.md §3.2（OF 内建 uniform 表）/ §3.3（OF GLSL → M GLSL 转译入口）——
 *    仓库内文档事实与本项目自研代码（glsl/preprocess、glsl/translate），不受第三方版权约束。
 *    外部候选 IrisShaders/glsl-preprocessor（GPL-3.0 + 例外条款）与 IrisShaders/glsl-transformer
 *    （自定义传染许可）→ 例外条款是否覆盖本项目未核实，一律按禁止处理
 *    （18-PARALLEL §4 C/D 行已按此口径记录；07-CONSTRAINTS L12 §1.3 陷阱 2 / X20 / X21），
 *    本任务不读其代码、零代码行并入。许可证：本文件为独立编写的纯 Java 编排类，不含任何外部项目代码。
 *    → 能否并入本项目（MIT）：可以 —— 仅串联本仓库 C 线（GlslPreprocessor）与 D 线
 *      （OfGlslTranslator）自研实现，只采纳不受版权保护的事实性信息（OF 格式语义、行号要求）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：18-PARALLEL §4 的两线公开入口 —— C 线 GlslPreprocessor.analyze
 *    （Include → Define → Const 三阶段，产出 TranslateResult + 选项识别）、
 *    D 线 OfGlslTranslator.translate(stage, TranslateResult)（attribute/varying 重写、
 *    内建 uniform 注入，并按 F3 用法 dMap.compose(cMap) 合成端到端行号映射）。
 * 2. 备选：让每个调用方手动两步串联 —— 会把"失败短路 / 上游诊断合并 / null 归一"散落各处，
 *    漏掉任一处都会静默丢诊断（T11 违规），故在 glsl/ 包根收口一个入口。
 * 3. 我们的差异点（= F3 契约的缺口适配，不改冻结契约）：
 *    ① 失败短路：预处理结果含 ERROR（循环包含 / 缺文件 / 条件块未闭合等）→ 不进入转译，
 *       直接返回 TranslateResult.failure（带原文件坐标诊断，T11）；
 *    ② 诊断合并：OfGlslTranslator 只回填本阶段诊断、不透传上游（C 线）诊断 —— 入口把两阶段
 *       诊断合并进同一个 TranslateResult（TranslateResult 字段冻结，故在入口适配而不是改契约）；
 *    ③ null 归一：primaryFile == null 按 ""（#include 相对路径按 shaders/ 顶层解析）、
 *       resolver == null 按空解析器（缺文件显式报错而不是 NPE）、stage == null 交给 D 线
 *       按 UNKNOWN 显式报错（X9：不猜）—— 任何输入都不抛异常；
 *    ④ 幂等口径与 D 线一致：输出文本是管线的不动点（跑两遍文本逐字节一致）；当输入没有
 *       预处理指令需要删除、也没有任何注入时，整个 TranslateResult（文本 + 诊断 + 行号映射）
 *       两轮完全相等。选项识别以首次输入为准（输出已删指令，第二遍不再发现选项）——
 *       幂等断言只针对 TranslateResult。
 * 4. 许可证核对结论：本项目 MIT；GPL-3.0+例外与自定义传染参考按禁止处理，只读思路、零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径（包加载期一次）；纯顺序调用两个已有阶段（三段式预处理 + 五级转译），
 *    无缓存、无预优化（18-PARALLEL §7.7、T14 达标即停；08-TESTING §8 "解析+转译全部 program
 *    ≤ 1 秒" 由 P2 主线实测把关）。
 */
/**
 * C + D 汇合的一站式管线入口：OF 方言 GLSL 源文本 → 预处理 → 转译 → {@link TranslateResult}。
 *
 * <p><b>串联顺序</b>（前一步的输出是后一步的输入，顺序不可颠倒）：
 * <ol>
 *   <li><b>预处理</b>（C 线，{@link GlslPreprocessor}）：{@code #include} 展开 →
 *       {@code #define} / 条件编译 → 选项常量识别；行号映射逐级 compose 保留到
 *       {@link SourceLineMap}，诊断指回原文件；</li>
 *   <li><b>转译</b>（D 线，{@link OfGlslTranslator}）：{@code attribute} / {@code varying} →
 *       {@code in} / {@code out}、旧纹理函数改名、{@code ftransform} 展开、片元输出适配、
 *       OF 内建 uniform 注入；端到端行号映射 = D 级映射 compose C 级映射。</li>
 * </ol>
 *
 * <p><b>失败短路</b>：预处理阶段出现 ERROR（循环包含 / 缺失包含文件 / 条件块未闭合……）时
 * <b>不进入转译</b>，直接返回 {@link TranslateResult#failure} —— 半成品文本只供诊断展示，
 * 不会再被转译加工（T11：失败必须显式可见）。
 *
 * <p><b>诊断合并</b>：{@link OfGlslTranslator} 按 F3 契约只产出本阶段诊断、不透传上游诊断；
 * 本入口把预处理与转译两阶段的诊断合并进同一个结果 —— 这是对冻结契约的入口级适配，
 * 不修改 {@link TranslateResult} 本身。
 *
 * <p><b>幂等性</b>：{@code run(stage, file, run(stage, file, src, r).text(), r).text()} 与
 * {@code run(stage, file, src, r).text()} 逐字节相同 —— 输出文本是整条管线的不动点。
 * 当输入既无预处理指令被删除、也无任何注入时，整个 {@link TranslateResult} 两轮相等
 * （单测 {@code GlslPipelineTest} 对两种口径都有断言）。
 *
 * <p><b>null 语义</b>（任何输入都不抛异常）：{@code source} / {@code primaryFile} /
 * {@code resolver} / {@code stage} 为 {@code null} 都有明确归一与显式诊断，见类注释③。
 *
 * <p>典型用法：
 * <pre>{@code
 * TranslateResult out = GlslPipeline.run(
 *         ShaderStage.FRAGMENT, "composite.fsh", source, IncludeResolver.of(files));
 * if (!out.isSuccess()) {
 *     for (TranslateDiagnostic d : out.diagnostics()) {
 *         LOGGER.error("vkdisp: {}", d.format());   // 诊断已指回原文件与原始行号
 *     }
 * }
 * }</pre>
 */
public final class GlslPipeline {

    private GlslPipeline() {
    }

    /**
     * 管线报告：F3 冻结契约结果 + 预处理阶段识别出的选项常量。
     *
     * <p>选项不进 {@link TranslateResult}（字段冻结），按 {@link GlslPreprocessor.PreprocessReport}
     * 的同款形态作为入口的附加产物交付；调用方只关心转译结果时用 {@link #run} 即可。
     *
     * @param result  管线结果（文本 + 诊断 + 端到端行号映射）；永不为 {@code null}
     * @param options 预处理识别出的选项常量（以首次输入为准）；永不为 {@code null}
     */
    public record PipelineReport(TranslateResult result, List<ConstEvaluator.OptionConstant> options) {

        /** 记录构造：options 为 {@code null} 时归一为空列表（与 F3 的 null 语义一致）。 */
        public PipelineReport {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /**
     * 完整管线（标准入口）：预处理 + 转译，返回 F3 冻结契约 {@link TranslateResult}。
     *
     * @param stage       着色器阶段；{@code null} 按 D 线 UNKNOWN 语义显式报错（不猜方向）
     * @param primaryFile 顶层文件名（相对 shaders/ 顶层，如 {@code "composite.fsh"}）；
     *                    {@code null} 按 {@code ""} 处理（#include 相对路径按 shaders/ 顶层解析）
     * @param source      OF 方言 GLSL 源文本；{@code null} 按空串处理（WARN，不失败）
     * @param resolver    {@code #include} 文件来源；{@code null} 按空解析器处理
     *                    （缺文件时显式报 ERROR，不抛 NPE）
     * @return 管线结果；永不返回 {@code null}
     */
    public static TranslateResult run(
            ShaderStage stage, String primaryFile, String source, IncludeResolver resolver) {
        return analyze(stage, primaryFile, source, resolver).result();
    }

    /**
     * 完整管线（无 {@code #include} 的便捷入口）：等价于传入空解析器。
     *
     * <p>源文本里若含 {@code #include}，会显式报"包含文件不存在" ERROR（T11），
     * 而不是静默跳过。
     */
    public static TranslateResult run(ShaderStage stage, String primaryFile, String source) {
        return run(stage, primaryFile, source, IncludeResolver.of(Map.of()));
    }

    /**
     * 完整管线 + 选项识别：逻辑同 {@link #run}，额外带回预处理识别出的选项常量。
     *
     * <p>注意：选项识别基于"展开后、去指令前"的首次输入；把本结果的文本再跑一遍时
     * 指令已被删除，不再产生选项 —— 这不影响 {@link TranslateResult} 的幂等口径。
     */
    public static PipelineReport analyze(
            ShaderStage stage, String primaryFile, String source, IncludeResolver resolver) {
        // null 归一：primaryFile 空串化避免 IncludeProcessor 解析相对路径时 NPE，
        // resolver 空解析器化让缺文件走显式 ERROR 而不是 NPE（T11 / 不静默）。
        String file = primaryFile == null ? "" : primaryFile;
        IncludeResolver effective = resolver == null ? IncludeResolver.of(Map.of()) : resolver;

        GlslPreprocessor.PreprocessReport pre = GlslPreprocessor.analyze(file, source, effective);
        if (!pre.result().isSuccess()) {
            // 失败短路：预处理已含 ERROR → 不进入转译；failure() 保证结果显式失败（T11）。
            return new PipelineReport(
                    TranslateResult.failure(
                            pre.result().text(), pre.result().lineMap(), pre.result().diagnostics()),
                    pre.options());
        }

        // 预处理成功（可能带 WARN/INFO）→ 进入转译；D 线负责 compose 端到端行号映射。
        TranslateResult translated = OfGlslTranslator.translate(stage, pre.result());

        // 诊断合并（入口级契约适配）：D 线不透传上游诊断，这里把 C 线诊断并回同一结果，
        // 否则"选项歧义 WARN"这类预处理诊断会在汇合后静默丢失（T11 违规）。
        List<TranslateDiagnostic> merged = new ArrayList<>(pre.result().diagnostics());
        merged.addAll(translated.diagnostics());
        return new PipelineReport(
                TranslateResult.withDiagnostics(translated.text(), translated.lineMap(), merged),
                pre.options());
    }
}
