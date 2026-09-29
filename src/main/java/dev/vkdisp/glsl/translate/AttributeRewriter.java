package dev.vkdisp.glsl.translate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.vkdisp.glsl.TranslateDiagnostic;

/**
 * 【参考调研】D 线 attribute/varying 重写器 / 04-SPEC §3.3 与 §4 + GLSL 限定符公开语义
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/04-SPEC.md §3.3（OF 老式 attribute/varying）、§4（顶点属性名字必须与
 *    着色器声明字面一致）、docs/18-PARALLEL.md §4 D 线（"OF 老式 attribute/varying → M 语法"）、
 *    docs/03-DIRECTION.md §3.2（OF 有 attribute/varying 老式语法）—— 仓库内文档事实 +
 *    GLSL 官方限定符语义（公开事实），均不受版权保护。
 *    外部候选 IrisShaders/glsl-transformer（GPL-3.0 + 例外条款，18-PARALLEL §4 D 线明示"按禁止处理"）→
 *    例外条款是否覆盖本项目未核实，按禁止处理（07-CONSTRAINTS L12 §1.3 陷阱 2 / X21），
 *    本任务不读其代码、零代码行并入（07-CONSTRAINTS §〇 P1 / L5 / X19）。
 *    许可证：本文件为独立编写的纯 Java 实现，不含任何外部项目代码。
 *    → 能否并入本项目（MIT）：可以 —— 只采纳不受版权保护的事实性信息（限定符语义、名字字面一致要求）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：core profile GLSL 限定符语义 —— 顶点阶段 in/out、片元阶段 in、attribute 仅限顶点阶段；
 *    转译目标形态取自 04-SPEC §3.3「OF GLSL → M GLSL 的源码级转译后交给原版编译」与 §4 的
 *    "字段名必须与 attribute 声明完全一致"（因此**不注入 layout(location=)**，绑定靠名字匹配）。
 * 2. 备选：无 —— 不建 AST、不引入 glslang/ANTLR；只做行内关键字替换（冷路径清晰优先，18-PARALLEL §7.7）。
 * 3. 我们的差异点：① 只替换限定符关键字本身，缩进 / 注释 / 行尾内容 / 行数全部不变 ——
 *    C 线（预处理）的行号映射不被切断，D 线自己的输出行号映射也因此是纯恒等；
 *    ② 阶段未知或片元阶段出现 attribute 时显式 ERROR 而不猜测（X9 + T11）；
 *    ③ 重复声明、跨行声明、注释未闭合都出诊断而不崩。
 * 4. 许可证核对结论：本项目 MIT；GPL-3.0+例外参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径（包加载期一次）；逐行线性扫描，无缓存、无预优化
 *    （18-PARALLEL §7.7、T14 达标即停）。
 */
/**
 * OF 老式 {@code attribute} / {@code varying} → 现代 core profile GLSL {@code in} / {@code out}。
 *
 * <p><b>映射规则</b>（方向由阶段决定，见 {@link ShaderStage}）：
 * <table border="1">
 *   <caption>限定符映射</caption>
 *   <tr><th>OF 老式</th><th>顶点阶段</th><th>片元阶段</th></tr>
 *   <tr><td>{@code attribute}</td><td>{@code in}</td><td>非法 → ERROR</td></tr>
 *   <tr><td>{@code varying}</td><td>{@code out}</td><td>{@code in}</td></tr>
 * </table>
 *
 * <p><b>不改的东西</b>：不注入 {@code layout(location = N)}（04-SPEC §4 要求属性名与
 * {@code VertexFormat} 字段字面一致，绑定靠名字）；不碰 {@code #include} / {@code #define}
 * （C 线职责，18-PARALLEL §4 D 线"不许做"）；不改 uniform 声明（UniformInjector 只补缺失项）。
 *
 * <p><b>行号契约</b>：重写是**行内等长替换之外的等行数**操作 —— 输出行数与输入逐行对齐，
 * {@code 输出第 N 行 == 输入第 N 行}。诊断的 {@code line} 是**本阶段的输入行号**，
 * {@code sourceFile} 留空由 {@link OfGlslTranslator} 经 F3 的 {@code SourceLineMap} 回填原文件。
 */
public final class AttributeRewriter {

    private AttributeRewriter() {}

    /**
     * 重写结果（纯数据）。
     *
     * @param text           重写后的文本（行数与输入一致）
     * @param diagnostics    诊断（位置 = 本阶段输入行号；{@code sourceFile} 为 {@code null}，由入口回填）
     * @param rewrittenCount 实际改写的声明条数
     */
    public record Result(String text, List<TranslateDiagnostic> diagnostics, int rewrittenCount) {

        /** 记录构造：null 归一（结果对象永不为 null 语义，同 F3 契约）。 */
        public Result {
            text = text == null ? "" : text;
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    /**
     * 按阶段重写全部 {@code attribute} / {@code varying} 声明。
     *
     * @param stage  着色器阶段；{@code null} 按 {@link ShaderStage#UNKNOWN} 处理（出现声明即 ERROR）
     * @param source 输入 GLSL（{@code null} 按空串处理）
     * @return 重写结果；永不返回 {@code null}
     */
    public static Result rewrite(ShaderStage stage, String source) {
        SourceLines lines = SourceLines.of(source);
        List<String> rewrittenLines = new ArrayList<>(lines.lines());
        List<TranslateDiagnostic> diagnostics = new ArrayList<>();
        Map<String, Integer> declaredAtLine = new LinkedHashMap<>();
        CommentState comments = new CommentState();
        int rewrittenCount = 0;
        for (int index = 0; index < rewrittenLines.size(); index++) {
            int lineNumber = index + 1;
            String raw = rewrittenLines.get(index);
            String code = comments.stripComments(raw, lineNumber);
            String trimmed = code.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            GlslDeclaration declaration = GlslDeclaration.parse(code);
            if (declaration == null
                    || (!"attribute".equals(declaration.keyword) && !"varying".equals(declaration.keyword))) {
                continue;
            }
            if (declaration.type == null || declaration.name == null) {
                diagnostics.add(TranslateDiagnostic.error(
                        declaration.keyword + " 声明缺少类型或名称，无法重写", null, lineNumber));
                continue;
            }
            Integer firstLine = declaredAtLine.putIfAbsent(declaration.name, lineNumber);
            if (firstLine != null) {
                diagnostics.add(TranslateDiagnostic.warn(declaration.name + " 重复声明（首次出现在第 "
                        + firstLine + " 行）", null, lineNumber));
            }
            String replacement = replacementKeyword(stage, declaration.keyword);
            if (replacement == null) {
                diagnostics.add(TranslateDiagnostic.error(
                        stageError(stage, declaration.keyword), null, lineNumber));
                continue;
            }
            if (!declaration.terminated) {
                diagnostics.add(TranslateDiagnostic.warn(declaration.keyword
                        + " 声明未在本行以分号结尾（可能跨行），已按行内关键字重写", null, lineNumber));
            }
            rewrittenLines.set(index, raw.substring(0, declaration.keywordStart) + replacement
                    + raw.substring(declaration.keywordEnd));
            rewrittenCount++;
        }
        if (comments.inBlockComment()) {
            diagnostics.add(0, TranslateDiagnostic.error("块注释未闭合（起始于第 "
                    + comments.blockCommentStartLine() + " 行）", null, comments.blockCommentStartLine()));
        }
        return new Result(SourceLines.join(rewrittenLines, lines.endsWithNewline()), diagnostics, rewrittenCount);
    }

    /**
     * 关键字 → 现代限定符；阶段无法决定方向时返回 {@code null}（调用方出 ERROR，拒绝猜测）。
     */
    private static String replacementKeyword(ShaderStage stage, String keyword) {
        ShaderStage effective = stage == null ? ShaderStage.UNKNOWN : stage;
        return switch (effective) {
            case VERTEX -> "attribute".equals(keyword) ? "in" : "out";
            case FRAGMENT -> "varying".equals(keyword) ? "in" : null;
            case UNKNOWN -> null;
        };
    }

    /** 无法决定方向时的显式错误文本（T11：降级 / 失败必须可见）。 */
    private static String stageError(ShaderStage stage, String keyword) {
        ShaderStage effective = stage == null ? ShaderStage.UNKNOWN : stage;
        if (effective == ShaderStage.FRAGMENT) {
            return "片元阶段不允许 " + keyword + " 声明（OF 的 attribute 仅存在于顶点阶段）";
        }
        return "着色器阶段未知，无法确定 " + keyword + " 的现代 GLSL 语义（in / out 取决于阶段），拒绝猜测";
    }
}
