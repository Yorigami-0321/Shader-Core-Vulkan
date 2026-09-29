package dev.vkdisp.glsl.preprocess;

import java.util.ArrayList;
import java.util.List;

import dev.vkdisp.glsl.SourceLineMap;
import dev.vkdisp.glsl.TranslateDiagnostic;
import dev.vkdisp.glsl.TranslateResult;

/**
 * 【参考调研】C 线 — 编排入口
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = docs/18-PARALLEL.md §4 C 线完成标准"C 的产出（TranslateResult）就是 D 的输入"
 *    + F3 的 TranslateResult / TranslateDiagnostic / SourceLineMap 三件套冻结契约（本文件只依赖、
 *    不修改它们）。外部候选 IrisShaders/glsl-preprocessor（GPL-3.0 + 例外条款）→ 一律按禁止处理
 *    （handover §2.1 / 07-CONSTRAINTS X20/X21），本任务不读其代码、零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 类，不含任何外部项目代码。
 *    → 能否并入本项目（MIT）：可以（仅依赖仓库内 F3 契约与自研 processor）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：handover §5.3 的串联顺序 "Include → Define → Const"，最终产出 F3 的
 *    TranslateResult（文本 + 诊断列表 + 行号映射），作为 D 线的输入契约。
 * 2. 备选：让 D 线自己跑 include / 宏 —— 会重复实现行号映射且破坏"单一事实来源"，故 C 线在此收口。
 * 3. 我们的差异点：行号映射逐级 compose（define 阶段映射 compose include 阶段映射 → 端到端），
 *    选项识别结果通过 PreprocessReport 随 TranslateResult 一并交付（TranslateResult 字段冻结、
 *    不改；选项作为同包附加产物，D 线或调用方可按需取用）。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径（包加载期一次性）；三次顺序扫描 + 一次映射 compose，清晰优先、无缓存、
 *    无性能优化（18-PARALLEL §7.7、T14 达标即停）。
 */
/**
 * C 线编排入口：按 <b>Include → Define → Const</b> 顺序把单个 GLSL 文件预处理成
 * {@link TranslateResult}，作为 D 线的输入契约。
 *
 * <p>三阶段说明：
 * <ol>
 *   <li>{@link IncludeProcessor}：展开 {@code #include}，并登记每行起源的 {@link SourceLineMap}；</li>
 *   <li>{@link DefineProcessor}：处理宏与条件编译，产出本阶段"输出行 → include 展开行"的映射，
 *       再与 include 映射 {@link SourceLineMap#compose(compat...) compose}；</li>
 *   <li>{@link ConstEvaluator}：识别选项常量（文本透传，映射沿用上一级）。</li>
 * </ol>
 *
 * <p>{@link #preprocess(String, String, IncludeResolver)} 返回冻结契约 {@link TranslateResult}；
 * {@link #analyze(String, String, IncludeResolver)} 在其外再附上识别出的选项列表（供 C 线内部校验）。
 */
public final class GlslPreprocessor {

    private GlslPreprocessor() {
    }

    /** 编排报告：F3 契约结果 + 识别出的选项常量列表。 */
    public record PreprocessReport(TranslateResult result, List<ConstEvaluator.OptionConstant> options) {
    }

    /**
     * 完整预处理，返回冻结契约 {@link TranslateResult}（即 D 线输入）。
     *
     * @param primaryFile 顶层文件名（相对 shaders/ 顶层）
     * @param source      顶层文件内容
     * @param resolver    #include 文件来源
     */
    public static TranslateResult preprocess(String primaryFile, String source, IncludeResolver resolver) {
        return analyze(primaryFile, source, resolver).result();
    }

    /**
     * 完整预处理，返回含选项识别结果的报告。逻辑同 {@link #preprocess}，但额外带回选项列表。
     */
    public static PreprocessReport analyze(String primaryFile, String source, IncludeResolver resolver) {
        IncludeProcessor.Result inc = IncludeProcessor.process(primaryFile, source, resolver);
        if (!inc.success()) {
            // include 阶段已含 ERROR 诊断（循环包含 / 缺文件 / 超深）→ 直接失败收口
            return new PreprocessReport(
                    TranslateResult.failure(inc.text(), inc.lineMap(), inc.diagnostics()), List.of());
        }

        DefineProcessor.Result def = DefineProcessor.process(inc.text(), inc.lineMap());
        SourceLineMap finalMap = def.lineMap().compose(inc.lineMap());

        // 选项发现必须在 Define 之前扫描：#define 选项宏会被 DefineProcessor 当作指令删掉，
        // 而 const 选项虽保留在 def.text() 中，但在"展开后、去指令前"的全量文本上统一扫描更一致
        // （选项声明即便位于条件分支也应被发现，这正是选项发现（handover §6.3）的语义）。
        ConstEvaluator.Result con = ConstEvaluator.evaluate(inc.text(), inc.lineMap());

        List<TranslateDiagnostic> all = new ArrayList<>(inc.diagnostics());
        all.addAll(def.diagnostics());
        all.addAll(con.diagnostics());

        TranslateResult result = TranslateResult.withDiagnostics(def.text(), finalMap, all);
        return new PreprocessReport(result, con.options());
    }
}
