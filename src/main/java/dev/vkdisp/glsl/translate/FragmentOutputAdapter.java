package dev.vkdisp.glsl.translate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import dev.vkdisp.glsl.TranslateDiagnostic;
import dev.vkdisp.glsl.lexer.GlslTokens;

/**
 * 【参考调研】D 线二期片元输出适配 / GLSL 1.20 → 330 core 的内建输出差异 + 04-SPEC §3.3
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/04-SPEC.md §3.2 / §3.3（源码级转译后交给原版编译器）、
 *    docs/18-PARALLEL.md §4 D 线（独占路径、完成标准、证据规范）与 §7（硬边界）——
 *    仓库内文档事实；另加 GLSL 官方公开语义（gl_FragColor / gl_FragData 是 GLSL 1.20 及以前的
 *    片元内建输出，core profile 330 移除，改用 layout(location = N) out 变量）。
 *    以上事实均不受版权保护。外部候选 IrisShaders/glsl-transformer（GPL-3.0 + 例外条款）与
 *    glsl-preprocessor（同）→ 18-PARALLEL §4 D 线明示「按禁止处理」，例外条款是否覆盖本项目
 *    未核实，一律不读其代码、零代码行并入（07-CONSTRAINTS L12 §1.3 陷阱 2 / X21、17-NATIVE §1.1.1）。
 *    许可证：本文件为独立编写的纯 Java 实现，不含任何外部项目代码；样本在单测中自造（§7.6）。
 *    → 能否并入本项目（MIT）：可以 —— 只采纳不受版权保护的事实性信息（内建语义、限定符语法）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：GLSL 公开语义 —— ① gl_FragColor 等价于「location 0 的 vec4 输出」；
 *    ② gl_FragData[n] 等价于「location n 的 vec4 输出」，n 由 DRAWBUFFERS 决定，OF 语义最多 8 个
 *    颜色附件（0..7，见 04-SPEC §3.4 RenderTargetPool 的 colortex 规划）；③ core profile 里
 *    fragColor 必须是显式 layout(location = N) out vec4 变量；④ 全局声明必须位于
 *    #version / #extension 之后、首条代码之前。
 * 2. 备选：无 —— 不建 AST、不引入 glslang / ANTLR；只做行内标识符级重写 + 头部区一次整行插入
 *    （冷路径清晰优先，18-PARALLEL §7.7）。
 * 3. 我们的差异点：① **不重复声明**：包内已有 location 0 的 out vec4 时直接复用它的名字，
 *    已有的 layout(location = N) out vec4 也按槽位复用；② 只有包内确实没有对应槽位时才合成
 *    layout(location = N) out vec4 vkdispFragOutN; 并出 INFO（可见但不算降级）；
 *    ③ 拒绝猜测：包内存在未标 location 的 out vec4 时，其隐式槽位由链接器分配，无法安全断言
 *    "该槽位没被占用" —— 除「唯一一个未标 location 的 out 且请求槽位 0」这一确定情形外一律出
 *    ERROR 且不改写（X9 + T11，同时守住"已声明 out 不重复声明"这条硬边界）；
 *    ④ 槽位被非 vec4 / 数组 / 块声明占用时出 ERROR 且不改写（显式失败）；
 *    ⑤ gl_FragData 下标非整数字面量或越界（> 7）时出 ERROR 且不改写，绝不静默；
 *    ⑥ 重写只发生在「无注释无字符串视图」的标识符上，注释 / 字符串 / #define 续行一字不动；
 *    ⑦ 行数除头部区插入外不变，C 线的行号映射不被切断，诊断行号仍是**本阶段输入**行号。
 * 4. 许可证核对结论：本项目 MIT；GPL-3.0+例外参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径（包加载期一次，08-TESTING §8 解析+转译全部 program ≤ 1 秒 由 P2 主线
 *    实测把关）；两遍线性扫描，无缓存、无预优化（18-PARALLEL §7.7、T14 达标即停）。
 */
/**
 * OF 老式片元内建输出 {@code gl_FragColor} / {@code gl_FragData[n]} → core profile 的
 * {@code layout(location = N) out vec4} 声明 + 标识符改写。
 *
 * <p><b>适配规则</b>
 * <ol>
 *   <li>包内已有 {@code layout(location = 0) out vec4 <name>;} → {@code gl_FragColor} 改写成 {@code <name>}；</li>
 *   <li>包内只有一个未标 location 的 {@code out vec4 <name>;} → 视为 location 0（OF/Iris 包最常见形态）；</li>
 *   <li>包内已有 {@code layout(location = N) out vec4 <name>;} → {@code gl_FragData[N]} 改写成 {@code <name>}；</li>
 *   <li>包内没有任何未标 location 的 out vec4 且槽位空闲 → 在文件头部区之后插入
 *       {@code layout(location = N) out vec4 vkdispFragOutN;} 并出 INFO；</li>
 *   <li>包内有未标 location 的 out vec4（隐式槽位由链接器分配）且不能确定归属 → ERROR，且不改写；</li>
 *   <li>槽位被非 {@code vec4} / 数组 / 接口块占用 → ERROR，且**不改写**（显式失败，T11）；</li>
 *   <li>{@code gl_FragData} 下标不是整数字面量、或 &gt; {@link #MAX_OUTPUT_LOCATION} → ERROR，且不改写；</li>
 *   <li>阶段不是片元（含 {@link ShaderStage#UNKNOWN} / {@code null}）→ ERROR，且不改写（拒绝猜测，X9）。</li>
 * </ol>
 *
 * <p><b>与 ⑤ IoLocationAdapter 的分工（P4.1.2）</b>：本类运行在 ⑤ 之后，包内未标 location 的
 * out 声明已被 ⑤ 按声明序补过号 —— 规则 2/5 只剩"⑤ 被边界跳过（同行多语句等）"的罕见形态兜底，
 * 判定语义不变。
 *
 * <p><b>幂等</b>：改写后源码里不再有 {@code gl_FragColor} / {@code gl_FragData}，第二遍扫描
 * 「没有任何内建输出可改」→ 不插入、不改写、无新增诊断，文本逐字节不变。
 *
 * <p><b>行号契约</b>：诊断的 {@code line} 是**本阶段输入**行号（插入之前）；
 * {@link Result#insertIndex()} / {@link Result#insertedLineCount()} 供
 * {@link OfGlslTranslator} 构造输出行号映射（F3 的 {@code SourceLineMap}）。
 */
public final class FragmentOutputAdapter {

    /** 合成输出变量名前缀（与包内标识符风格区分，便于日志与 grep 定位）。 */
    public static final String SYNTHETIC_PREFIX = "vkdispFragOut";

    /** OF 语义下片元输出的最大槽位（DRAWBUFFERS 0..7，见 04-SPEC §3.4 colortex 规划）。 */
    public static final int MAX_OUTPUT_LOCATION = 7;

    /** 允许出现在 {@code out} 之前的插值 / 存储限定符（与一期 GlslDeclaration 口径一致）。 */
    private static final Set<String> LEADING_QUALIFIERS = Set.of(
            "flat", "smooth", "noperspective", "centroid", "patch", "sample", "invariant", "precise");

    private FragmentOutputAdapter() {}

    /**
     * 适配结果（纯数据）。
     *
     * @param text                  适配后的文本；无需插入时与输入逐字节相同
     * @param diagnostics           诊断（位置 = 本阶段输入行号；{@code sourceFile} 为 {@code null}，由入口回填）
     * @param rewrittenCount        实际改写的内建输出引用数（{@code gl_FragColor} / {@code gl_FragData[n]} 各算一次）
     * @param insertIndex           插入点之前原有行数（0 基；无插入时为 0）
     * @param insertedLineCount     本次插入的行数（无插入时为 0）
     * @param insertedDeclarations  本次插入的合成输出声明（按 location 升序；无插入时为空列表）
     */
    public record Result(String text, List<TranslateDiagnostic> diagnostics, int rewrittenCount,
            int insertIndex, int insertedLineCount, List<String> insertedDeclarations) {

        /** 记录构造：null 归一（结果对象永不为 null 语义，同 F3 契约）。 */
        public Result {
            text = text == null ? "" : text;
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            insertedDeclarations = insertedDeclarations == null ? List.of() : List.copyOf(insertedDeclarations);
        }
    }

    /**
     * 把片元内建输出适配成 core profile 形式。
     *
     * @param stage  着色器阶段；只有 {@link ShaderStage#FRAGMENT} 允许出现片元内建输出
     * @param source 输入 GLSL（{@code null} 按空串处理）
     * @return 适配结果；永不返回 {@code null}
     */
    public static Result adapt(ShaderStage stage, String source) {
        SourceLines lines = SourceLines.of(source);
        List<String> raw = lines.lines();
        List<TranslateDiagnostic> diagnostics = new ArrayList<>();
        List<String> codes = GlslTokens.codeViews(raw, diagnostics);
        boolean[] skip = GlslTokens.preprocessorSkipLines(raw, codes);

        List<OutDecl> outs = new ArrayList<>();
        Set<String> declaredNames = new LinkedHashSet<>();
        for (int index = 0; index < raw.size(); index++) {
            if (skip[index]) {
                continue;
            }
            GlslDeclaration declaration = GlslDeclaration.parse(codes.get(index));
            if (declaration != null && declaration.name != null) {
                declaredNames.add(declaration.name);
            }
            OutDecl out = parseOutDeclaration(codes.get(index), index + 1);
            if (out != null) {
                outs.add(out);
                if (out.name() != null) {
                    declaredNames.add(out.name());
                }
            }
        }

        Map<Integer, OutDecl> byLocation = new LinkedHashMap<>();
        List<OutDecl> unlocated = new ArrayList<>();
        for (OutDecl out : outs) {
            if (out.block() || out.array()) {
                continue;
            }
            if (out.hasLocation()) {
                byLocation.putIfAbsent(out.location(), out);
            } else if ("vec4".equals(out.type())) {
                unlocated.add(out);
            }
        }

        Targets targets = new Targets(byLocation, unlocated, declaredNames, diagnostics);
        List<String> adapted = new ArrayList<>(raw.size());
        int rewritten = 0;
        for (int index = 0; index < raw.size(); index++) {
            if (skip[index]) {
                adapted.add(raw.get(index));
                continue;
            }
            Rewrite outcome = rewriteLine(stage, raw.get(index), codes.get(index), index + 1, targets, diagnostics);
            adapted.add(outcome.line());
            rewritten += outcome.rewritten();
        }

        List<String> declarations = targets.syntheticDeclarations();
        String adaptedText = SourceLines.join(adapted, lines.endsWithNewline());
        if (declarations.isEmpty()) {
            return new Result(adaptedText, diagnostics, rewritten, 0, 0, List.of());
        }
        int insertIndex = GlslTokens.headerEnd(raw);
        if (insertIndex >= raw.size()) {
            diagnostics.add(TranslateDiagnostic.warn("输入不含任何代码行，无法插入合成的片元输出声明（"
                    + String.join(" ", declarations) + "）", null, 0));
            return new Result(adaptedText, diagnostics, rewritten, 0, 0, List.of());
        }
        String text = SourceLines.of(adaptedText).insertLines(insertIndex, declarations);
        return new Result(text, diagnostics, rewritten, insertIndex, declarations.size(), declarations);
    }

    // ------------------------------------------------------------------ 内部：解析

    /** 一条既有 {@code out} 声明（只保留槽位判定需要的信息）。 */
    private record OutDecl(String name, String type, int location, boolean hasLocation,
            boolean array, boolean block, int lineNumber) {}

    /**
     * 解析一行 {@code out} 声明：支持 {@code layout(...)} 前缀、插值限定符、接口块与数组标注。
     *
     * @return 声明的关键信息；该行不是 {@code out} 声明时返回 {@code null}
     */
    private static OutDecl parseOutDeclaration(String code, int lineNumber) {
        int pos = GlslTextScan.skipWhitespace(code, 0);
        int location = -1;
        boolean hasLocation = false;
        int[] span = GlslTextScan.identifierAt(code, pos);
        if (span != null && "layout".equals(code.substring(span[0], span[1]))) {
            int open = GlslTextScan.skipWhitespace(code, span[1]);
            if (open >= code.length() || code.charAt(open) != '(') {
                return null;
            }
            int close = GlslTextScan.matchCloseParen(code, open);
            if (close < 0) {
                return null;
            }
            Integer parsed = parseLayoutLocation(code.substring(open + 1, close));
            if (parsed != null) {
                location = parsed;
                hasLocation = true;
            }
            pos = GlslTextScan.skipWhitespace(code, close + 1);
        }
        while (true) {
            span = GlslTextScan.identifierAt(code, pos);
            if (span == null) {
                return null;
            }
            String token = code.substring(span[0], span[1]);
            if (!LEADING_QUALIFIERS.contains(token)) {
                break;
            }
            pos = GlslTextScan.skipWhitespace(code, span[1]);
        }
        if (!"out".equals(code.substring(span[0], span[1]))) {
            return null;
        }
        pos = GlslTextScan.skipWhitespace(code, span[1]);
        int[] typeSpan = GlslTextScan.identifierAt(code, pos);
        if (typeSpan == null) {
            return null;
        }
        String type = code.substring(typeSpan[0], typeSpan[1]);
        pos = GlslTextScan.skipWhitespace(code, typeSpan[1]);
        if (pos < code.length() && code.charAt(pos) == '{') {
            return new OutDecl(null, type, location, hasLocation, false, true, lineNumber);
        }
        int[] nameSpan = GlslTextScan.identifierAt(code, pos);
        if (nameSpan == null) {
            return null;
        }
        String name = code.substring(nameSpan[0], nameSpan[1]);
        int afterName = GlslTextScan.skipWhitespace(code, nameSpan[1]);
        boolean array = afterName < code.length() && code.charAt(afterName) == '[';
        return new OutDecl(name, type, location, hasLocation, array, false, lineNumber);
    }

    /** 从 {@code layout(...)} 的内容里取 {@code location = N} 的 N；没有则返回 {@code null}。 */
    private static Integer parseLayoutLocation(String inside) {
        for (int pos = 0; pos < inside.length(); pos++) {
            int[] span = GlslTextScan.identifierAt(inside, pos);
            if (span == null || !GlslTextScan.atTokenStart(inside, span[0])) {
                continue;
            }
            if (!"location".equals(inside.substring(span[0], span[1]))) {
                pos = span[1] - 1;
                continue;
            }
            int equals = GlslTextScan.skipWhitespace(inside, span[1]);
            if (equals >= inside.length() || inside.charAt(equals) != '=') {
                pos = span[1] - 1;
                continue;
            }
            int digitStart = GlslTextScan.skipWhitespace(inside, equals + 1);
            int digitEnd = digitStart;
            while (digitEnd < inside.length() && Character.isDigit(inside.charAt(digitEnd))) {
                digitEnd++;
            }
            if (digitEnd == digitStart || digitEnd - digitStart > 9) {
                return null;
            }
            return Integer.parseInt(inside.substring(digitStart, digitEnd));
        }
        return null;
    }

    // ------------------------------------------------------------------ 内部：槽位解析与改写

    /**
     * 槽位 → 输出变量名 的解析器（同一个槽位只解析一次：合成声明与改写名字必须一致，否则就不幂等）。
     *
     * <p>诊断顺序即「第一次需要该槽位的行」，便于直接定位到源码。
     */
    private static final class Targets {

        private final Map<Integer, OutDecl> byLocation;
        private final List<OutDecl> unlocated;
        private final Set<String> declaredNames;
        private final List<TranslateDiagnostic> diagnostics;
        private final Map<Integer, String> resolved = new LinkedHashMap<>();
        private final Map<Integer, String> syntheticByLocation = new TreeMap<>();

        private Targets(Map<Integer, OutDecl> byLocation, List<OutDecl> unlocated,
                Set<String> declaredNames, List<TranslateDiagnostic> diagnostics) {
            this.byLocation = byLocation;
            this.unlocated = unlocated;
            this.declaredNames = declaredNames;
            this.diagnostics = diagnostics;
        }

        /**
         * 解析槽位对应的输出变量名。
         *
         * @return 变量名；槽位被不可用声明占用（非 vec4 / 接口块 / 数组）时返回 {@code null}（调用方不改写）
         */
        private String targetFor(int location, int lineNumber) {
            String known = resolved.get(location);
            if (known != null) {
                return known;
            }
            OutDecl declared = byLocation.get(location);
            if (declared != null) {
                if (declared.name() == null || !"vec4".equals(declared.type())) {
                    diagnostics.add(TranslateDiagnostic.error("片元输出 location = " + location
                            + " 已被 " + declared.type() + " "
                            + (declared.name() == null ? "<接口块>" : declared.name())
                            + " 占用，无法适配 gl_ 内建输出（要求 vec4）", null, lineNumber));
                    return null;
                }
                resolved.put(location, declared.name());
                return declared.name();
            }
            if (location == 0 && unlocated.size() == 1) {
                resolved.put(0, unlocated.get(0).name());
                return unlocated.get(0).name();
            }
            if (!unlocated.isEmpty()) {
                // 包内已有未标 location 的 out vec4：它的隐式槽位由实现决定（GLSL 公开语义：
                // 未标 location 的片元输出由链接器分配）。再合成一个显式 location 可能与之撞车 ——
                // 这正是任务点名的「已声明 out 变量时不重复声明」边界，因此拒绝猜测并显式失败（X9 + T11）。
                diagnostics.add(TranslateDiagnostic.error("包内存在 " + unlocated.size()
                        + " 个未标 location 的 out vec4 声明，无法确定 location " + location
                        + " 是否已被隐式占用（拒绝猜测，X9）；请为该输出显式标注 layout(location = "
                        + location + ")", null, lineNumber));
                return null;
            }
            String name = SYNTHETIC_PREFIX + location;
            while (declaredNames.contains(name)) {
                name = name + "_";
            }
            declaredNames.add(name);
            resolved.put(location, name);
            syntheticByLocation.put(location,
                    "layout(location = " + location + ") out vec4 " + name + ";");
            diagnostics.add(TranslateDiagnostic.info("包内未声明 location " + location
                    + " 的片元输出，已合成声明 layout(location = " + location + ") out vec4 "
                    + name + ";", null, lineNumber));
            return name;
        }

        private List<String> syntheticDeclarations() {
            return new ArrayList<>(syntheticByLocation.values());
        }
    }

    /** 单行改写结果。 */
    private record Rewrite(String line, int rewritten) {}

    /** 改写一行里的 {@code gl_FragColor} / {@code gl_FragData[n]}（只动标识符，其余字节原位保留）。 */
    private static Rewrite rewriteLine(ShaderStage stage, String raw, String code, int lineNumber,
            Targets targets, List<TranslateDiagnostic> diagnostics) {
        if (!code.contains("gl_FragColor") && !code.contains("gl_FragData")) {
            return new Rewrite(raw, 0);
        }
        if (stage != ShaderStage.FRAGMENT) {
            diagnostics.add(TranslateDiagnostic.error(stageError(stage), null, lineNumber));
            return new Rewrite(raw, 0);
        }
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        int rewritten = 0;
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
            String token = code.substring(span[0], span[1]);
            if ("gl_FragColor".equals(token)) {
                String target = targets.targetFor(0, lineNumber);
                if (target != null) {
                    out.append(raw, cursor, span[0]).append(target);
                    cursor = span[1];
                    rewritten++;
                }
                pos = span[1];
                continue;
            }
            if ("gl_FragData".equals(token)) {
                int bracket = GlslTextScan.skipWhitespace(code, span[1]);
                if (bracket >= code.length() || code.charAt(bracket) != '[') {
                    diagnostics.add(TranslateDiagnostic.error(
                            "gl_FragData 缺少下标（只有 gl_FragData[n] 形式才能确定输出槽位）", null, lineNumber));
                    pos = span[1];
                    continue;
                }
                int digitStart = GlslTextScan.skipWhitespace(code, bracket + 1);
                int digitEnd = digitStart;
                while (digitEnd < code.length() && Character.isDigit(code.charAt(digitEnd))) {
                    digitEnd++;
                }
                int closeBracket = GlslTextScan.skipWhitespace(code, digitEnd);
                if (digitEnd == digitStart || closeBracket >= code.length()
                        || code.charAt(closeBracket) != ']') {
                    diagnostics.add(TranslateDiagnostic.error(
                            "gl_FragData 下标不是整数字面量，无法确定输出槽位（T11 不静默）", null, lineNumber));
                    pos = span[1];
                    continue;
                }
                if (digitEnd - digitStart > 9) {
                    diagnostics.add(TranslateDiagnostic.error("gl_FragData 下标越界：OF DRAWBUFFERS 只支持 0.."
                            + MAX_OUTPUT_LOCATION, null, lineNumber));
                    pos = closeBracket + 1;
                    continue;
                }
                int location = Integer.parseInt(code.substring(digitStart, digitEnd));
                if (location > MAX_OUTPUT_LOCATION) {
                    diagnostics.add(TranslateDiagnostic.error("gl_FragData[" + location
                            + "] 下标越界：OF DRAWBUFFERS 只支持 0.." + MAX_OUTPUT_LOCATION, null, lineNumber));
                    pos = closeBracket + 1;
                    continue;
                }
                String target = targets.targetFor(location, lineNumber);
                if (target != null) {
                    out.append(raw, cursor, span[0]).append(target);
                    cursor = closeBracket + 1;
                    rewritten++;
                }
                pos = closeBracket + 1;
                continue;
            }
            pos = span[1];
        }
        if (rewritten == 0) {
            return new Rewrite(raw, 0);
        }
        out.append(raw, cursor, raw.length());
        return new Rewrite(out.toString(), rewritten);
    }

    /** 阶段不允许片元内建输出时的显式错误文本（T11：失败必须可见）。 */
    private static String stageError(ShaderStage stage) {
        ShaderStage effective = stage == null ? ShaderStage.UNKNOWN : stage;
        if (effective == ShaderStage.VERTEX) {
            return "顶点阶段不允许 gl_FragColor / gl_FragData（GLSL 公开语义：片元阶段内建）";
        }
        return "着色器阶段未知，无法确定 gl_FragColor / gl_FragData 是否适用（片元内建），拒绝改写";
    }
}
