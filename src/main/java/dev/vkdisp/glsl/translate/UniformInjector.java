package dev.vkdisp.glsl.translate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.vkdisp.glsl.TranslateDiagnostic;

/**
 * 【参考调研】D 线 OF 内建 uniform 注入器 / 04-SPEC §3.2 内建 uniform 表
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/04-SPEC.md §3.2（OF 内建 uniform 必须提供的语义，23 条）与
 *    docs/18-PARALLEL.md §4 D 线（"OF 内建 uniform 声明注入完整"）—— 仓库内文档事实，不受版权保护。
 *    外部候选 IrisShaders/glsl-transformer（GPL-3.0 + 例外条款，18-PARALLEL §4 D 线明示"按禁止处理"）→
 *    按禁止处理（07-CONSTRAINTS L12 §1.3 陷阱 2 / X21），本任务不读其代码、零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 实现，不含任何外部项目代码。
 *    → 能否并入本项目（MIT）：可以 —— 只采纳不受版权保护的事实性信息（uniform 名/类型/语义）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：04-SPEC §3.2 的 uniform 表（经 UniformCatalog 固化）；注入点选在**文件头部区**
 *    （空行 / 注释 / 预处理指令之后、首条代码之前），这是 GLSL 里"全局作用域且在所有使用之前、
 *    同时不破坏 #version / #extension 必须靠前"的公开硬性要求。
 * 2. 备选：无 —— 不做 UBO 打包（那是 pipeline/ 的活，18-PARALLEL §2.1 明确不许提前做），
 *    只产出声明文本；上传语义见 04-SPEC §3.4 OfUniformManager（关键路径）。
 * 3. 我们的差异点：① **只注入缺失项**：包内已声明的一律不重写（尊重包的意图，也保证幂等）；
 *    已声明但类型与 OF 语义不符 → WARN 而不是偷偷覆盖（T11）；② 固定注入顺序（文档表顺序），
 *    第二遍转译时首行代码即已注入的 uniform，注入点前移到它之前且无缺失项 → 文本逐字节不变；
 *    ③ 纯注释 / 空文件不注入（注入了也没有使用方），并显式 WARN 而不是静默跳过。
 * 4. 许可证核对结论：本项目 MIT；GPL-3.0+例外参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径（包加载期一次）；两趟线性扫描，无缓存、无预优化
 *    （18-PARALLEL §7.7、T14 达标即停）。
 */
/**
 * 注入 OF 内建 uniform 声明（04-SPEC §3.2 的 23 条，清单见 {@link UniformCatalog}）。
 *
 * <p><b>只补缺失项</b>：源码里已经出现的 uniform（无论类型是否与 OF 语义一致）一律保留不动 ——
 * 类型不一致时出 WARN，绝不偷偷重写包作者的代码。由此得到幂等性：第二遍转译时全部内建都已存在，
 * 注入集合为空，文本逐字节不变。
 *
 * <p><b>注入点</b>：文件头部区之后（跳过空行 / 注释 / {@code #} 预处理指令），即首条代码之前。
 * 这样既在所有使用之前，又不会插到 {@code #version} / {@code #extension} 之前（GLSL 要求它们靠前）。
 * 头部区之外还有一处约束：包内的 {@code #include} / {@code #define} 由 C 线处理，本类只**跳过读取**，
 * 不解析、不改写（18-PARALLEL §4 D 线"不许做"）。
 *
 * <p><b>行号契约</b>：诊断的 {@code line} 是**注入前（本阶段输入）的行号**，
 * {@code sourceFile} 留空由 {@link OfGlslTranslator} 经 F3 的 {@code SourceLineMap} 回填原文件。
 * {@link Result#insertIndex()} / {@link Result#insertedLineCount()} 供入口构造输出行号映射。
 */
public final class UniformInjector {

    /** 注入块的识别注释（第二遍转译时它是注释，会被注入点扫描跳过；不参与任何语义）。 */
    public static final String BLOCK_HEADER = "// vkdisp: OF builtin uniforms (04-SPEC 3.2)";

    private UniformInjector() {}

    /**
     * 注入结果（纯数据）。
     *
     * @param text              注入后的文本；无注入时与输入逐字节相同
     * @param diagnostics       诊断（位置 = 本阶段输入行号；{@code sourceFile} 为 {@code null}，由入口回填）
     * @param injected          本次实际注入的内建 uniform（按 {@link UniformCatalog} 顺序）
     * @param insertIndex       注入点之前原有行数（0 基；0 = 插在文件最前；无注入时无意义）
     * @param insertedLineCount 本次插入的行数（含 {@link #BLOCK_HEADER} 注释行）
     */
    public record Result(String text, List<TranslateDiagnostic> diagnostics, List<BuiltinUniform> injected,
            int insertIndex, int insertedLineCount) {

        /** 记录构造：null 归一（结果对象永不为 null 语义，同 F3 契约）。 */
        public Result {
            text = text == null ? "" : text;
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            injected = injected == null ? List.of() : List.copyOf(injected);
        }
    }

    /**
     * 扫描源码并把缺失的 OF 内建 uniform 声明注入到文件头部区之后。
     *
     * @param source 输入 GLSL（{@code null} 按空串处理）
     * @return 注入结果；永不返回 {@code null}
     */
    public static Result inject(String source) {
        SourceLines lines = SourceLines.of(source);
        List<String> sourceLines = lines.lines();
        List<TranslateDiagnostic> diagnostics = new ArrayList<>();
        Map<String, Integer> declaredAtLine = new LinkedHashMap<>();
        CommentState comments = new CommentState();
        for (int index = 0; index < sourceLines.size(); index++) {
            int lineNumber = index + 1;
            String code = comments.stripComments(sourceLines.get(index), lineNumber);
            String trimmed = code.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            GlslDeclaration declaration = GlslDeclaration.parse(code);
            if (declaration == null || !"uniform".equals(declaration.keyword)) {
                continue;
            }
            if (declaration.block || declaration.name == null) {
                continue;
            }
            Integer firstLine = declaredAtLine.putIfAbsent(declaration.name, lineNumber);
            if (firstLine != null) {
                diagnostics.add(TranslateDiagnostic.warn("uniform " + declaration.name
                        + " 重复声明（首次出现在第 " + firstLine + " 行）", null, lineNumber));
                continue;
            }
            BuiltinUniform builtin = UniformCatalog.find(declaration.name);
            if (builtin != null && declaration.type != null && !builtin.type().equals(declaration.type)) {
                diagnostics.add(TranslateDiagnostic.warn("OF 内建 uniform " + builtin.name()
                        + " 在包内声明为 " + declaration.type + "，OF 语义为 " + builtin.type()
                        + "（保留包内声明，不注入、不重写）", null, lineNumber));
            }
        }
        if (comments.inBlockComment()) {
            diagnostics.add(0, TranslateDiagnostic.error("块注释未闭合（起始于第 "
                    + comments.blockCommentStartLine() + " 行）", null, comments.blockCommentStartLine()));
        }
        int insertIndex = headerEnd(sourceLines);
        if (insertIndex >= sourceLines.size()) {
            diagnostics.add(TranslateDiagnostic.warn(
                    "输入不含任何代码行（仅空行 / 注释 / 预处理指令），跳过 OF 内建 uniform 注入", null, 0));
            return new Result(lines.text(), diagnostics, List.of(), 0, 0);
        }
        List<BuiltinUniform> missing = new ArrayList<>();
        for (BuiltinUniform uniform : UniformCatalog.uniforms()) {
            if (!declaredAtLine.containsKey(uniform.name())) {
                missing.add(uniform);
            }
        }
        if (missing.isEmpty()) {
            return new Result(lines.text(), diagnostics, List.of(), 0, 0);
        }
        List<String> injectedLines = new ArrayList<>(missing.size() + 1);
        injectedLines.add(BLOCK_HEADER);
        for (BuiltinUniform uniform : missing) {
            injectedLines.add(uniform.declaration());
        }
        return new Result(lines.insertLines(insertIndex, injectedLines), diagnostics, missing,
                insertIndex, injectedLines.size());
    }

    /**
     * 文件头部区的结束下标（0 基，等于"其之前有 index 行"）：跳过空行、注释行与 {@code #} 预处理指令，
     * 停在首条代码行。没有代码行时返回行数（调用方据此跳过注入）。
     */
    private static int headerEnd(List<String> sourceLines) {
        CommentState comments = new CommentState();
        int index = 0;
        while (index < sourceLines.size()) {
            String code = comments.stripComments(sourceLines.get(index), index + 1);
            String trimmed = code.strip();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                break;
            }
            index++;
        }
        return index;
    }
}
