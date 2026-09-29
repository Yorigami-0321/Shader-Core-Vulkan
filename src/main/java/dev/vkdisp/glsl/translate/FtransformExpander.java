package dev.vkdisp.glsl.translate;

import java.util.ArrayList;
import java.util.List;

import dev.vkdisp.glsl.TranslateDiagnostic;

/**
 * 【参考调研】D 线二期顶点内建展开 / GLSL 1.20 ftransform 的公开语义 + 04-SPEC §3.2 / §4
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 ① docs/04-SPEC.md §3.2 的 OF 内建 uniform 表（gbufferProjection / gbufferModelView
 *    两条就是本仓库冻结的投影 / 视图矩阵名），② docs/04-SPEC.md §4 顶点属性表（位置属性字面名 Position，
 *    「字段名必须与 attribute 声明完全一致」），③ src/main/java/dev/vkdisp/pack/VertexAttribute.java
 *    的冻结字面名（只读消费其事实，不改它），④ docs/18-PARALLEL.md §4 D 线与 §7 —— 仓库内事实。
 *    另加 GLSL 官方公开语义：ftransform() 是 GLSL 1.20 顶点阶段的固定功能内建，
 *    等价于 gl_ModelViewProjectionMatrix * gl_Vertex（固定功能矩阵栈 + 顶点位置），
 *    core profile 330 已移除，必须展开成显式矩阵乘。
 *    外部候选 IrisShaders/glsl-transformer（GPL-3.0 + 例外条款）与 glsl-preprocessor（同）→
 *    18-PARALLEL §4 D 线明示「按禁止处理」，一律不读其代码、零代码行并入
 *    （07-CONSTRAINTS L12 §1.3 陷阱 2 / X21、17-NATIVE §1.1.1）。
 *    许可证：本文件为独立编写的纯 Java 实现，不含任何外部项目代码；样本在单测中自造（§7.6）。
 *    → 能否并入本项目（MIT）：可以 —— 只采纳不受版权保护的事实性信息（内建语义、名字字面一致要求）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：展开形态取自「M 原版核心着色器写法」与「OF 内建 uniform 语义」的合成 ——
 *    任务描述给出的形状是 {@code ProjMat * ModelViewMat * vec4(vaPosition, 1)}；其中**矩阵名以本仓库
 *    冻结的 UniformCatalog 为准**（任务原话），即 gbufferProjection / gbufferModelView；
 *    位置属性名以 04-SPEC §4 + pack.VertexAttribute 的冻结字面名 Position 为准，
 *    并兼容包内已声明的 vaPosition（OF 1.17 风格）与 gl_Vertex（老式固定功能名）。
 * 2. 备选：VulkanMod / Sulkan 等参考模组零接触（LGPL / 未核许可）；不引入 glslang / ANTLR；
 *    只做单行括号配对 + 标识符替换（冷路径清晰优先，18-PARALLEL §7.7）。
 * 3. 我们的差异点：① **吃包内已声明的属性名**：源里声明了 Position / vaPosition / gl_Vertex 就按它展开，
 *    类型 vec4 时不再补 {@code (…, 1.0)}（否则 vec4(vec4, 1.0) 是非法参数个数）；
 *    ② 一个都没声明时按冻结字面名 Position 展开并出 WARN（可见、不静默，T11）；
 *    ③ 只展开**真正的调用**且参数为空：{@code ftransform(} 带参数出 ERROR，调用跨行出 WARN 且不改写；
 *    ④ 阶段不是 VERTEX（含 UNKNOWN / null）出 ERROR 且不改写 —— ftransform 只存在于顶点阶段，
 *    拒绝把内建语义套到错误阶段（X9 + T11）；⑤ 展开只改标识符区间，行数不变，
 *    C 线的行号映射不被切断；⑥ 展开会引入 gbufferProjection / gbufferModelView 的**使用**，
 *    其声明由随后运行的 UniformInjector 按 04-SPEC §3.2 补齐（同一条流水线上的分工）。
 * 4. 许可证核对结论：本项目 MIT；GPL-3.0+例外参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径（包加载期一次，08-TESTING §8 解析+转译全部 program ≤ 1 秒 由 P2 主线
 *    实测把关）；两遍线性扫描，无缓存、无预优化（18-PARALLEL §7.7、T14 达标即停）。
 */
/**
 * GLSL 1.20 顶点内建 {@code ftransform()} → 显式矩阵乘展开。
 *
 * <p><b>展开式</b>：{@code (gbufferProjection * gbufferModelView * vec4(<位置属性>, 1.0))}
 * <ul>
 *   <li>矩阵名取自冻结的 {@link UniformCatalog}（04-SPEC §3.2），代码里按名查表取用，
 *       目录改了展开式自动跟随（任务要求「内建名以已冻结的 UniformCatalog 为准」）；</li>
 *   <li>位置属性名优先用包内已声明的 {@code Position} / {@code vaPosition} / {@code gl_Vertex}；
 *       未声明时用 04-SPEC §4 的冻结字面名 {@code Position} 并出 WARN；</li>
 *   <li>属性类型为 {@code vec4} 时直接用名字（不补 {@code 1.0}），否则按 {@code vec4(name, 1.0)}。</li>
 * </ul>
 *
 * <p><b>不改的东西</b>：{@code #include} / {@code #define}（C 线）、位置属性声明本身
 * （不替包作者声明 {@code in vec3 Position;} —— 那属顶点格式绑定职责，见「未覆盖」）、
 * 注释 / 字符串 / 预处理指令续行内的文本。
 *
 * <p><b>幂等</b>：展开后源码里不再有 {@code ftransform} 调用，第二遍扫描无改写 → 文本逐字节不变、
 * 无新增诊断。
 *
 * <p><b>行号契约</b>：诊断的 {@code line} 是**本阶段输入**行号；{@code sourceFile} 留空由
 * {@link OfGlslTranslator} 经 F3 的 {@code SourceLineMap} 回填原文件。
 */
public final class FtransformExpander {

    /** 被展开的内建函数名（GLSL 1.20 顶点阶段固定功能内建）。 */
    public static final String FTRANSFORM = "ftransform";

    /** 未声明位置属性时使用的冻结字面名（04-SPEC §4 + pack.VertexAttribute.Position）。 */
    public static final String DEFAULT_POSITION_ATTRIBUTE = "Position";

    /** 投影 / 视图矩阵的冻结内建名（04-SPEC §3.2，代码按名查 {@link UniformCatalog}）。 */
    public static final String PROJECTION_UNIFORM = "gbufferProjection";

    /** 视图矩阵的冻结内建名（04-SPEC §3.2）。 */
    public static final String MODELVIEW_UNIFORM = "gbufferModelView";

    /** 位置属性候选字面名（04-SPEC §4 的 Position；OF 1.17 风格的 vaPosition；老式 gl_Vertex）。 */
    private static final List<String> POSITION_CANDIDATES = List.of("Position", "vaPosition", "gl_Vertex");

    private FtransformExpander() {}

    /**
     * 展开结果（纯数据）。
     *
     * @param text            展开后的文本；无需展开时与输入逐字节相同
     * @param diagnostics     诊断（位置 = 本阶段输入行号；{@code sourceFile} 为 {@code null}，由入口回填）
     * @param expandedCount   实际展开的调用数
     * @param positionOperand 本次使用的位置属性操作数（如 {@code vec4(Position, 1.0)}）；未展开时为 {@code null}
     */
    public record Result(String text, List<TranslateDiagnostic> diagnostics, int expandedCount,
            String positionOperand) {

        /** 记录构造：null 归一（结果对象永不为 null 语义，同 F3 契约）。 */
        public Result {
            text = text == null ? "" : text;
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    /**
     * 展开全部 {@code ftransform()} 调用。
     *
     * @param stage  着色器阶段；只有 {@link ShaderStage#VERTEX} 允许 ftransform
     * @param source 输入 GLSL（{@code null} 按空串处理）
     * @return 展开结果；永不返回 {@code null}
     */
    public static Result expand(ShaderStage stage, String source) {
        SourceLines lines = SourceLines.of(source);
        List<String> raw = lines.lines();
        List<TranslateDiagnostic> diagnostics = new ArrayList<>();
        List<String> codes = GlslTextScan.codeViews(raw, diagnostics);
        boolean[] skip = GlslTextScan.preprocessorSkipLines(raw, codes);

        String[] declared = declaredPosition(codes, skip);
        String operand;
        String declaredType = declared[1];
        if (declared[0] == null) {
            operand = "vec4(" + DEFAULT_POSITION_ATTRIBUTE + ", 1.0)";
        } else if ("vec4".equals(declaredType)) {
            operand = declared[0];
        } else {
            operand = "vec4(" + declared[0] + ", 1.0)";
        }

        BuiltinUniform projection = UniformCatalog.find(PROJECTION_UNIFORM);
        BuiltinUniform modelView = UniformCatalog.find(MODELVIEW_UNIFORM);
        String expression = projection == null || modelView == null
                ? null
                : "(" + projection.name() + " * " + modelView.name() + " * " + operand + ")";

        List<TranslateDiagnostic> expansionDiagnostics = new ArrayList<>();
        List<String> expanded = new ArrayList<>(raw.size());
        int expandedCount = 0;
        for (int index = 0; index < raw.size(); index++) {
            if (skip[index]) {
                expanded.add(raw.get(index));
                continue;
            }
            Rewrite outcome = rewriteLine(stage, raw.get(index), codes.get(index), index + 1,
                    expression, operand, declared[0] == null, declaredType, expansionDiagnostics);
            expanded.add(outcome.line());
            expandedCount += outcome.expanded();
        }
        diagnostics.addAll(expansionDiagnostics);
        return new Result(SourceLines.join(expanded, lines.endsWithNewline()), diagnostics,
                expandedCount, expandedCount == 0 ? null : operand);
    }

    /** 位置属性候选名 / 类型（{@code [0] == null} = 源里没有声明任何候选属性）。 */
    private static String[] declaredPosition(List<String> codes, boolean[] skip) {
        String name = null;
        String type = null;
        for (int index = 0; index < codes.size(); index++) {
            if (skip[index]) {
                continue;
            }
            GlslDeclaration declaration = GlslDeclaration.parse(codes.get(index));
            if (declaration == null || declaration.name == null) {
                continue;
            }
            if (!"attribute".equals(declaration.keyword) && !"in".equals(declaration.keyword)) {
                continue;
            }
            if (!POSITION_CANDIDATES.contains(declaration.name)) {
                continue;
            }
            name = declaration.name;
            type = declaration.type;
            break;
        }
        return new String[] {name, type};
    }

    /** 单行展开结果。 */
    private record Rewrite(String line, int expanded) {}

    /**
     * 展开一行里的 {@code ftransform()}。
     *
     * <p>诊断只在第一处展开时补两条「环境性」说明：未声明位置属性（WARN）、位置属性类型非 vec3/vec4
     * （WARN）；阶段错误与参数错误按行报（定位更准）。诊断行号 = 本阶段输入行号。
     */
    private static Rewrite rewriteLine(ShaderStage stage, String raw, String code, int lineNumber,
            String expression, String operand, boolean positionDefaulted, String declaredType,
            List<TranslateDiagnostic> diagnostics) {
        if (!code.contains(FTRANSFORM)) {
            return new Rewrite(raw, 0);
        }
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        int expanded = 0;
        boolean environmentReported = false;
        int pos = 0;
        while (pos < code.length()) {
            int[] span = GlslTextScan.identifierAt(code, pos);
            if (span == null) {
                pos++;
                continue;
            }
            if (!GlslTextScan.atTokenStart(code, span[0])) {
                pos = span[1];
                continue;
            }
            if (!FTRANSFORM.equals(code.substring(span[0], span[1]))) {
                pos = span[1];
                continue;
            }
            int open = GlslTextScan.skipWhitespace(code, span[1]);
            if (open >= code.length() || code.charAt(open) != '(') {
                // 不是调用（同名变量 / 宏参数）→ 不动
                pos = span[1];
                continue;
            }
            int close = GlslTextScan.matchCloseParen(code, open);
            if (close < 0) {
                diagnostics.add(TranslateDiagnostic.warn(
                        "ftransform() 调用跨行（同行找不到配对右括号），未展开；请把调用写成单行（T11 显式可见）",
                        null, lineNumber));
                pos = span[1];
                continue;
            }
            if (stage != ShaderStage.VERTEX) {
                diagnostics.add(TranslateDiagnostic.error(stageError(stage), null, lineNumber));
                pos = close + 1;
                continue;
            }
            if (!code.substring(open + 1, close).strip().isEmpty()) {
                diagnostics.add(TranslateDiagnostic.error(
                        "ftransform() 不接受参数（GLSL 1.20 内建），拒绝展开非法调用", null, lineNumber));
                pos = close + 1;
                continue;
            }
            if (expression == null) {
                diagnostics.add(TranslateDiagnostic.error("冻结的 OF 内建 uniform 目录缺少 "
                        + PROJECTION_UNIFORM + " / " + MODELVIEW_UNIFORM + "，无法展开 ftransform()",
                        null, lineNumber));
                pos = close + 1;
                continue;
            }
            if (!environmentReported) {
                environmentReported = true;
                if (positionDefaulted) {
                    diagnostics.add(TranslateDiagnostic.warn("输入未声明位置属性（"
                            + String.join(" / ", POSITION_CANDIDATES) + "），ftransform() 按 04-SPEC §4 的冻结字面名 "
                            + DEFAULT_POSITION_ATTRIBUTE + " 展开（T11 显式可见）", null, lineNumber));
                } else if (!"vec3".equals(declaredType) && !"vec4".equals(declaredType)) {
                    diagnostics.add(TranslateDiagnostic.warn("位置属性类型不是 vec3 / vec4，"
                            + "ftransform() 仍按 " + operand + " 展开（T11 显式可见）", null, lineNumber));
                }
            }
            out.append(raw, cursor, span[0]).append(expression);
            cursor = close + 1;
            expanded++;
            pos = close + 1;
        }
        if (expanded == 0) {
            return new Rewrite(raw, 0);
        }
        out.append(raw, cursor, raw.length());
        return new Rewrite(out.toString(), expanded);
    }

    /** 阶段不允许 ftransform 时的显式错误文本（T11：失败必须可见）。 */
    private static String stageError(ShaderStage stage) {
        if (stage == ShaderStage.FRAGMENT) {
            return "片元阶段没有 ftransform()（GLSL 公开语义：仅顶点阶段定义），拒绝展开";
        }
        return "着色器阶段未知，无法确认 ftransform() 的适用范围（仅顶点阶段），拒绝展开";
    }
}
