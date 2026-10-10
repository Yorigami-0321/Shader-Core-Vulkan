package dev.vkdisp.glsl.preprocess;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.vkdisp.glsl.SourceLineMap;
import dev.vkdisp.glsl.TranslateDiagnostic;

/**
 * 【参考调研】C 线 — 宏 / 条件编译处理器
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = OptiFine / Iris 官方文档对 #define / #undef / #if / #ifdef / #ifndef / #elif /
 *    #else / #endif 的条件编译语义（handover §6.2、§6.3：基于选项宏的条件编译）—— 格式事实，
 *    不受版权保护。外部候选 IrisShaders/glsl-preprocessor（GPL-3.0 + 例外条款）→ 一律按禁止处理
 *    （handover §2.1 / 07-CONSTRAINTS X20/X21），本任务不读其代码、零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 类，不含任何外部项目代码。
 *    → 能否并入本项目（MIT）：可以（仅采纳不受版权保护的事实性条件编译语义）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：C 标准预处理器的条件编译子集 + 宏替换，聚焦"选项宏驱动的条件编译"
 *    （handover §5.3③）；行号映射沿 F3 的 SourceLineMap 契约（docs/18-PARALLEL §4 C 线）。
 * 2. 备选：把 #if 表达式求值交给脚本语言 / 外部库 —— 引入非 MIT 依赖风险且背离"冷路径纯 Java"
 *    （handover §0），故自研一个最小的整数/浮点表达式求值器。
 * 3. 我们的差异点：宏表与条件栈都是进程内纯内存状态；输出只保留"被选中分支"的代码并删掉所有
 *    预处理指令，行号映射记录每条输出行对应"上一层（include 展开后）的输入行号"，由编排入口
 *    与 include 映射 compose 成端到端映射。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径；单次线性扫描 + 宏表 HashMap，清晰优先、无缓存、无性能优化
 *    （18-PARALLEL §7.7、T14 达标即停）。
 */
/**
 * 处理 GLSL 源码里的宏定义与条件编译，产出"已解析条件、宏已展开"的干净 GLSL。
 *
 * <p>支持的指令：
 * <ul>
 *   <li>{@code #define} / {@code #undef}（对象宏完整展开；函数宏做基础参数替换）；</li>
 *   <li>{@code #ifdef} / {@code #ifndef} / {@code #if} / {@code #elif} / {@code #else} /
 *       {@code #endif}（基于宏表的条件编译，整段删掉未被选中的分支与所有指令行）。</li>
 * </ul>
 *
 * <p>不在此处理的指令（{@code #version} / {@code #precision} / {@code #extension} 等）原样保留。
 * 本处理器<b>不</b>改写 {@code attribute}/{@code uniform} 语义（那是 D 线的职责）。
 */
public final class DefineProcessor {

    private DefineProcessor() {
    }

    /** 处理结果：输出文本 + 阶段行号映射（输出行 → include 展开后的输入行号）+ 诊断。 */
    public record Result(
            String text, SourceLineMap lineMap, List<TranslateDiagnostic> diagnostics) {
    }

    /**
     * 处理入口。
     *
     * @param text          来自 IncludeProcessor 的展开后文本
     * @param inputLineMap  IncludeProcessor 产出的行号映射（用于把诊断指回原文件）
     */
    /**
     * 引擎侧预定义宏（<b>不是</b>包里 {@code #define} 出来的那些）。
     *
     * <p>🔴 {@code MC_VERSION}：BSL v10.1.8 里出现 <b>53 次、跨 32 个文件</b>，而我方从来没定义过它
     * ⇒ 本类的 {@link ExprEval} 按「未定义标识符 = 0」求值 ⇒ <b>整包被当成跑在 1.7 之前编译</b>
     * （本轮实测：{@code #if MC_VERSION >= 11800} 一直走老数字那一支）。
     * 取不到值时<b>什么都不塞</b> —— 保持「未定义」而不是喂一个猜的数（X9），
     * 且这件事由生产侧的自报行说清楚（{@code OfUniformManager#reportConventions}）。
     */
    private static Map<String, Macro> engineMacros() {
        Map<String, Macro> macros = new HashMap<>();
        java.util.OptionalInt mcVersion = dev.vkdisp.McVersion.current();
        if (mcVersion.isPresent()) {
            macros.put(dev.vkdisp.McVersion.MACRO,
                    new Macro(MacroKind.OBJECT, List.of(), Integer.toString(mcVersion.getAsInt())));
        }
        // 🔴 GAP-028：`MC_RENDER_STAGE_*` 同样是「包假定加载器会塞」的引擎侧常量。
        //   不塞的后果不是「效果不对」而是**整条 gbuffers_skybasic 编译失败**
        //   （真机逐字：`vsh:243: error: 'MC_RENDER_STAGE_STARS' : undeclared identifier`）。
        //   编号口径是我方自己的 ABI（理由与不受支持面写在 RenderStages 的类注释里）。
        for (Map.Entry<String, String> macro : RenderStages.macros().entrySet()) {
            macros.put(macro.getKey(), new Macro(MacroKind.OBJECT, List.of(), macro.getValue()));
        }
        return macros;
    }

    public static Result process(String text, SourceLineMap inputLineMap) {
        Map<String, Macro> macros = engineMacros();
        List<TranslateDiagnostic> diagnostics = new ArrayList<>();
        SourceLineMap.Builder lineBuilder = SourceLineMap.builder(null);
        StringBuilder out = new StringBuilder();

        String[] rawLines = text.split("\n", -1);
        int count = rawLines.length;
        // 去掉 split 产生的"末尾空串"（纯换行终止符产物）
        if (count > 0 && rawLines[count - 1].isEmpty()) {
            count--;
        }

        Deque<Frame> stack = new ArrayDeque<>();
        boolean ok = true;
        // 🔖 A0：复用 translate/CommentState 这**同一套**注释状态机（不造第二套判断逻辑，19 §2.2 病根 (a)）。
        //    本阶只用它把「注释里的指令」变可见，输出与旧实现逐字一致。
        dev.vkdisp.glsl.translate.CommentState comments = new dev.vkdisp.glsl.translate.CommentState();

        for (int idx = 0; idx < count; idx++) {
            int inputLineNo = idx + 1;
            String line = rawLines[idx];
            String trimmed = line.strip();
            String codeView = comments.stripComments(line, inputLineNo).strip();
            if (trimmed.startsWith("#")) {
                warnIfDirectiveInComment(codeView, line, inputLineNo, inputLineMap, diagnostics);
                boolean handled = handleDirective(
                        trimmed, line, inputLineNo, macros, stack, diagnostics, inputLineMap);
                if (!handled) {
                    // 非本处理器管辖的指令（#version 等）：当前处于选中分支才保留
                    if (emitting(stack)) {
                        out.append(line).append('\n');
                        lineBuilder.add(inputLineNo);
                    }
                }
                // 管辖内的指令：不输出、不登记
            } else {
                if (emitting(stack)) {
                    String expanded = expandMacros(line, macros, new HashSet<>());
                    out.append(expanded).append('\n');
                    lineBuilder.add(inputLineNo);
                }
            }
        }

        if (!stack.isEmpty()) {
            diagnostics.add(TranslateDiagnostic.error(
                    "#if / #ifdef / #ifndef 未闭合：存在 " + stack.size() + " 个未匹配的条件块"));
            ok = false;
        }

        SourceLineMap lineMap = lineBuilder.build();
        if (!ok) {
            // 保留诊断即可；成功与否由调用方据 ERROR 判定（这里 Result 不强制 success 字段，
            // 编排入口会统一归并诊断）
        }
        return new Result(out.toString(), lineMap, List.copyOf(diagnostics));
    }

    // ---------------------------------------------------------------- A0 止血：注释内指令可见化

    private static void warnIfDirectiveInComment(
            String codeView, String line, int inputLineNo,
            SourceLineMap inputLineMap, List<TranslateDiagnostic> diagnostics) {
        if (!codeView.startsWith("#")) {
            diagnostics.add(warnAt(
                    "形如预处理指令的行位于注释内 ⇒ 本实现仍按真指令处理（语义修复属 19 §2.6-A2）",
                    line, inputLineNo, inputLineMap));
        }
    }

    // ---------------------------------------------------------------- 指令分发

    private static boolean handleDirective(
            String trimmed, String originalLine, int inputLineNo,
            Map<String, Macro> macros, Deque<Frame> stack,
            List<TranslateDiagnostic> diagnostics, SourceLineMap inputLineMap) {
        Matcher dm = DIRECTIVE.matcher(trimmed);
        if (!dm.find()) {
            return false; // 形如 "#" 后无关键字的奇怪行：当普通指令保留
        }
        String keyword = dm.group(1);
        String rest = dm.group(2) == null ? "" : dm.group(2).strip();
        // 🔴 A0 止血（QD-09 ① / 19 §2.6-A0）：本处理器**逐行**扫描，行尾 `\` 续行会把宏体/条件在行尾
        //    截断 ⇒ 静默产生错误展开（不报错、不告警）。续行拼接的语义修复属 A2（jcpp 的 JoinReader），
        //    本阶只承诺「不静默」，**不改变**已有输出。
        if (MANAGED_DIRECTIVES.contains(keyword) && endsWithLineContinuation(trimmed)) {
            diagnostics.add(warnAt(
                    "指令以 \\ 结尾（续行）：本处理器逐行扫描，宏体/条件会在行尾被截断并按截断结果继续展开，"
                            + "其后被续行的行会当普通代码输出（语义修复见 19 §2.6-A2）",
                    originalLine, inputLineNo, inputLineMap));
        }
        switch (keyword) {
            case "define":
                handleDefine(rest, macros);
                return true;
            case "undef":
                handleUndef(rest, macros);
                return true;
            case "ifdef":
                pushConditional(stack, macros.containsKey(stripIdent(rest)));
                return true;
            case "ifndef":
                pushConditional(stack, !macros.containsKey(stripIdent(rest)));
                return true;
            case "if":
                pushConditional(stack, evalCondition(rest, macros, diagnostics, originalLine, inputLineNo,
                        inputLineMap));
                return true;
            case "elif":
                handleElif(stack, rest, macros, diagnostics, originalLine, inputLineNo, inputLineMap);
                return true;
            case "else":
                handleElse(stack, diagnostics, originalLine, inputLineNo, inputLineMap);
                return true;
            case "endif":
                handleEndif(stack, diagnostics, originalLine, inputLineNo, inputLineMap);
                return true;
            default:
                return false; // 非管辖指令，交由调用方保留
        }
    }

    private static String stripIdent(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        int j = i;
        while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) {
            j++;
        }
        return s.substring(i, j);
    }

    // ---------------------------------------------------------------- #define / #undef

    private static void handleDefine(String rest, Map<String, Macro> macros) {
        // 去掉行尾 // 注释，避免把注释带进宏体
        String body = stripTrailingComment(rest).strip();
        Matcher fm = FUNC_DEFINE.matcher(body);
        if (fm.find()) {
            String name = fm.group(1);
            // 组号与 FUNC_DEFINE 对齐（g1=名 / g2=参数表 / g3=宏体）：曾按 4 组 pattern 取 3/4 组，
            // 函数宏分支一进就抛 IndexOutOfBoundsException("No group 4")——fixture 无函数宏从未触发，
            // P4.1 载入 BSL（lib/*.glsl 大量 #define f(a) 形态）全线转译失败后修复（X9 实测取证）。
            String paramsStr = fm.group(2) == null ? "" : fm.group(2);
            String defBody = fm.group(3) == null ? "" : fm.group(3).strip();
            List<String> params = new ArrayList<>();
            for (String p : paramsStr.split(",")) {
                p = p.strip();
                if (!p.isEmpty()) {
                    params.add(p);
                }
            }
            macros.put(name, new Macro(MacroKind.FUNCTION, params, defBody));
        } else {
            Matcher om = OBJ_DEFINE.matcher(body);
            if (om.find()) {
                String name = om.group(1);
                String defBody = om.group(2) == null ? "" : om.group(2).strip();
                macros.put(name, new Macro(MacroKind.OBJECT, List.of(), defBody));
            }
        }
    }

    private static void handleUndef(String rest, Map<String, Macro> macros) {
        macros.remove(stripIdent(rest));
    }

    private static String stripTrailingComment(String s) {
        int idx = s.indexOf("//");
        return idx >= 0 ? s.substring(0, idx) : s;
    }

    // ---------------------------------------------------------------- 条件栈

    private static final class Frame {
        boolean parentEmit;       // 进入本块时外层是否处于输出状态
        boolean currentBranchActive; // 当前所在分支是否应被输出
        boolean hasTaken;         // 是否已选中过任一分支
        boolean elseSeen;         // 是否已遇到 #else

        Frame(boolean parentEmit, boolean currentBranchActive) {
            this.parentEmit = parentEmit;
            this.currentBranchActive = currentBranchActive;
        }
    }

    private static boolean emitting(Deque<Frame> stack) {
        for (Frame f : stack) {
            if (!f.currentBranchActive) {
                return false;
            }
        }
        return true;
    }

    private static void pushConditional(Deque<Frame> stack, boolean condition) {
        boolean parentEmit = emitting(stack);
        boolean active = parentEmit && condition;
        Frame f = new Frame(parentEmit, active);
        f.hasTaken = active;
        stack.push(f);
    }

    private static void handleElif(
            Deque<Frame> stack, String rest, Map<String, Macro> macros,
            List<TranslateDiagnostic> diagnostics, String line, int inputLineNo,
            SourceLineMap inputLineMap) {
        if (stack.isEmpty()) {
            diagnostics.add(errorAt("#elif 没有匹配的 #if", line, inputLineNo, inputLineMap));
            return;
        }
        Frame f = stack.peek();
        if (f.elseSeen) {
            diagnostics.add(errorAt("#elif 出现在 #else 之后", line, inputLineNo, inputLineMap));
            return;
        }
        if (!f.hasTaken) {
            boolean cond = evalCondition(rest, macros, diagnostics, line, inputLineNo, inputLineMap);
            boolean take = f.parentEmit && cond;
            f.currentBranchActive = take;
            if (take) {
                f.hasTaken = true;
            }
        } else {
            f.currentBranchActive = false;
        }
    }

    private static void handleElse(
            Deque<Frame> stack, List<TranslateDiagnostic> diagnostics,
            String line, int inputLineNo, SourceLineMap inputLineMap) {
        if (stack.isEmpty()) {
            diagnostics.add(errorAt("#else 没有匹配的 #if", line, inputLineNo, inputLineMap));
            return;
        }
        Frame f = stack.peek();
        if (f.elseSeen) {
            diagnostics.add(errorAt("#else 重复出现", line, inputLineNo, inputLineMap));
            return;
        }
        f.elseSeen = true;
        f.currentBranchActive = f.parentEmit && !f.hasTaken;
        if (f.currentBranchActive) {
            f.hasTaken = true;
        }
    }

    private static void handleEndif(
            Deque<Frame> stack, List<TranslateDiagnostic> diagnostics,
            String line, int inputLineNo, SourceLineMap inputLineMap) {
        if (stack.isEmpty()) {
            diagnostics.add(errorAt("#endif 没有匹配的 #if", line, inputLineNo, inputLineMap));
            return;
        }
        stack.pop();
    }

    private static TranslateDiagnostic errorAt(
            String message, String line, int inputLineNo, SourceLineMap inputLineMap) {
        SourceLineMap.LineOrigin origin = inputLineMap.originOf(inputLineNo);
        return TranslateDiagnostic.error(message, origin.sourceFile(), origin.sourceLine());
    }

    /** 与 {@link #errorAt} 同一条归因路，级别换成 WARN（T11：降级/风险必须可见，不许静默）。 */
    private static TranslateDiagnostic warnAt(
            String message, String line, int inputLineNo, SourceLineMap inputLineMap) {
        SourceLineMap.LineOrigin origin = inputLineMap.originOf(inputLineNo);
        return TranslateDiagnostic.warn(message, origin.sourceFile(), origin.sourceLine());
    }

    // ---------------------------------------------------------------- 宏展开

    private static String expandMacros(String line, Map<String, Macro> macros, Set<String> expanding) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            if (isIdentStart(c)) {
                int j = i + 1;
                while (j < n && isIdentPart(line.charAt(j))) {
                    j++;
                }
                String name = line.substring(i, j);
                int k = j;
                while (k < n && line.charAt(k) == ' ') {
                    k++;
                }
                Macro m = macros.get(name);
                if (m != null && m.kind() == MacroKind.FUNCTION && k < n && line.charAt(k) == '(') {
                    int close = findMatchingParen(line, k);
                    if (close > k) {
                        String argsStr = line.substring(k + 1, close);
                        List<String> args = splitArgs(argsStr);
                        if (args.size() == m.params().size()) {
                            String substituted = substitute(m.body(), m.params(), args, macros, expanding);
                            out.append(substituted);
                            i = close + 1;
                            continue;
                        }
                    }
                    out.append(name);
                    i = j;
                    continue;
                }
                if (m != null && m.kind() == MacroKind.OBJECT && !expanding.contains(name)) {
                    Set<String> next = new HashSet<>(expanding);
                    next.add(name);
                    out.append(expandMacros(m.body(), macros, next));
                    i = j;
                    continue;
                }
                out.append(name);
                i = j;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String substitute(
            String body, List<String> params, List<String> args,
            Map<String, Macro> macros, Set<String> expanding) {
        String result = body;
        for (int p = 0; p < params.size(); p++) {
            result = result.replaceAll("\\b" + Pattern.quote(params.get(p)) + "\\b", args.get(p));
        }
        return expandMacros(result, macros, expanding);
    }

    private static int findMatchingParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static List<String> splitArgs(String s) {
        List<String> args = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                args.add(s.substring(start, i).strip());
                start = i + 1;
            }
        }
        args.add(s.substring(start).strip());
        return args;
    }

    private static boolean isIdentStart(char c) {
        return c == '_' || Character.isLetter(c);
    }

    private static boolean isIdentPart(char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }

    // ---------------------------------------------------------------- #if 表达式求值

    private static boolean evalCondition(
            String expr, Map<String, Macro> macros, List<TranslateDiagnostic> diagnostics,
            String line, int inputLineNo, SourceLineMap inputLineMap) {
        String stripped = stripTrailingComment(expr).strip();
        if (stripped.isEmpty()) {
            return false;
        }
        try {
            ExprEval eval = new ExprEval(stripped, macros);
            return eval.evaluate() != 0.0;
        } catch (RuntimeException ex) {
            diagnostics.add(errorAt(
                    "无法解析 #if 条件表达式: " + stripped, line, inputLineNo, inputLineMap));
            return false;
        }
    }

    /** 最小的整数/浮点表达式求值器，支持 defined()、标识符(→宏值或 0)、算数与比较、逻辑运算。 */
    private static final class ExprEval {
        private final List<Token> tokens;
        private final Map<String, Macro> macros;
        private int pos = 0;

        ExprEval(String expr, Map<String, Macro> macros) {
            this.tokens = tokenize(expr);
            this.macros = macros;
        }

        double evaluate() {
            double v = parseOr();
            if (pos < tokens.size()) {
                throw new RuntimeException("多余的符号");
            }
            return v;
        }

        private double parseOr() {
            double left = parseAnd();
            while (match(TokenType.OROR)) {
                double right = parseAnd();
                left = (left != 0 || right != 0) ? 1 : 0;
            }
            return left;
        }

        private double parseAnd() {
            double left = parseEq();
            while (match(TokenType.ANDAND)) {
                double right = parseEq();
                left = (left != 0 && right != 0) ? 1 : 0;
            }
            return left;
        }

        private double parseEq() {
            double left = parseRel();
            while (true) {
                if (match(TokenType.EQ)) {
                    double right = parseRel();
                    left = (left == right) ? 1 : 0;
                } else if (match(TokenType.NE)) {
                    double right = parseRel();
                    left = (left != right) ? 1 : 0;
                } else {
                    break;
                }
            }
            return left;
        }

        private double parseRel() {
            double left = parseMul();
            while (true) {
                if (match(TokenType.LT)) {
                    left = (left < parseMul()) ? 1 : 0;
                } else if (match(TokenType.GT)) {
                    left = (left > parseMul()) ? 1 : 0;
                } else if (match(TokenType.LE)) {
                    left = (left <= parseMul()) ? 1 : 0;
                } else if (match(TokenType.GE)) {
                    left = (left >= parseMul()) ? 1 : 0;
                } else {
                    break;
                }
            }
            return left;
        }

        private double parseMul() {
            double left = parseUnary();
            while (true) {
                if (match(TokenType.STAR)) {
                    left = left * parseUnary();
                } else if (match(TokenType.SLASH)) {
                    double r = parseUnary();
                    left = r == 0 ? 0 : left / r;
                } else if (match(TokenType.PERCENT)) {
                    double r = parseUnary();
                    left = r == 0 ? 0 : left % r;
                } else {
                    break;
                }
            }
            return left;
        }

        private double parseUnary() {
            if (match(TokenType.NOT)) {
                return parseUnary() != 0 ? 0 : 1;
            }
            if (match(TokenType.MINUS)) {
                return -parseUnary();
            }
            if (match(TokenType.PLUS)) {
                return parseUnary();
            }
            return parsePrimary();
        }

        private double parsePrimary() {
            Token t = peek();
            if (t.type == TokenType.NUM) {
                advance();
                try {
                    return Double.parseDouble(t.text);
                } catch (NumberFormatException ex) {
                    throw new RuntimeException("数字解析失败");
                }
            }
            if (t.type == TokenType.DEFINED) {
                advance();
                boolean paren = match(TokenType.LPAREN);
                if (peek().type != TokenType.IDENT) {
                    throw new RuntimeException("defined 后缺少标识符");
                }
                String name = advance().text;
                if (paren) {
                    expect(TokenType.RPAREN);
                }
                return macros.containsKey(name) ? 1 : 0;
            }
            if (t.type == TokenType.IDENT) {
                advance();
                Macro m = macros.get(t.text);
                if (m != null && m.kind() == MacroKind.OBJECT) {
                    try {
                        return Double.parseDouble(m.body().strip());
                    } catch (NumberFormatException ex) {
                        return 0;
                    }
                }
                return 0;
            }
            if (t.type == TokenType.LPAREN) {
                advance();
                double v = parseOr();
                expect(TokenType.RPAREN);
                return v;
            }
            throw new RuntimeException("无法解析的符号: " + t.text);
        }

        // ---- 词法 / 语法辅助

        private List<Token> tokenize(String s) {
            List<Token> out = new ArrayList<>();
            int i = 0;
            int n = s.length();
            while (i < n) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                    continue;
                }
                if (c == '(') { out.add(new Token(TokenType.LPAREN, "(")); i++; continue; }
                if (c == ')') { out.add(new Token(TokenType.RPAREN, ")")); i++; continue; }
                if (c == '!') {
                    if (i + 1 < n && s.charAt(i + 1) == '=') { out.add(new Token(TokenType.NE, "!=")); i += 2; }
                    else { out.add(new Token(TokenType.NOT, "!")); i++; }
                    continue;
                }
                if (c == '&' && i + 1 < n && s.charAt(i + 1) == '&') { out.add(new Token(TokenType.ANDAND, "&&")); i += 2; continue; }
                if (c == '|' && i + 1 < n && s.charAt(i + 1) == '|') { out.add(new Token(TokenType.OROR, "||")); i += 2; continue; }
                if (c == '=' && i + 1 < n && s.charAt(i + 1) == '=') { out.add(new Token(TokenType.EQ, "==")); i += 2; continue; }
                if (c == '<') {
                    if (i + 1 < n && s.charAt(i + 1) == '=') { out.add(new Token(TokenType.LE, "<=")); i += 2; }
                    else { out.add(new Token(TokenType.LT, "<")); i++; }
                    continue;
                }
                if (c == '>') {
                    if (i + 1 < n && s.charAt(i + 1) == '=') { out.add(new Token(TokenType.GE, ">=")); i += 2; }
                    else { out.add(new Token(TokenType.GT, ">")); i++; }
                    continue;
                }
                if (c == '+') { out.add(new Token(TokenType.PLUS, "+")); i++; continue; }
                if (c == '-') { out.add(new Token(TokenType.MINUS, "-")); i++; continue; }
                if (c == '*') { out.add(new Token(TokenType.STAR, "*")); i++; continue; }
                if (c == '/') { out.add(new Token(TokenType.SLASH, "/")); i++; continue; }
                if (c == '%') { out.add(new Token(TokenType.PERCENT, "%")); i++; continue; }
                if (c == '_' || Character.isLetter(c)) {
                    int j = i + 1;
                    while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) {
                        j++;
                    }
                    String word = s.substring(i, j);
                    if (word.equals("defined")) {
                        out.add(new Token(TokenType.DEFINED, "defined"));
                    } else {
                        out.add(new Token(TokenType.IDENT, word));
                    }
                    i = j;
                    continue;
                }
                if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                    int j = i + 1;
                    while (j < n && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) {
                        j++;
                    }
                    out.add(new Token(TokenType.NUM, s.substring(i, j)));
                    i = j;
                    continue;
                }
                i++; // 未知字符：跳过
            }
            return out;
        }

        private Token peek() {
            return pos < tokens.size() ? tokens.get(pos) : new Token(TokenType.EOF, "");
        }

        private Token advance() {
            Token t = peek();
            if (pos < tokens.size()) {
                pos++;
            }
            return t;
        }

        private boolean match(TokenType type) {
            if (peek().type == type) {
                pos++;
                return true;
            }
            return false;
        }

        private void expect(TokenType type) {
            if (peek().type != type) {
                throw new RuntimeException("缺少预期符号 " + type);
            }
            pos++;
        }
    }

    // ---------------------------------------------------------------- 内部类型

    enum MacroKind { OBJECT, FUNCTION }

    record Macro(MacroKind kind, List<String> params, String body) {
    }

    private enum TokenType {
        NUM, IDENT, DEFINED, LPAREN, RPAREN, NOT, ANDAND, OROR, EQ, NE, LT, LE, GT, GE, PLUS, MINUS, STAR, SLASH, PERCENT, EOF
    }

    private record Token(TokenType type, String text) {
    }

    private static final Pattern DIRECTIVE = Pattern.compile("^#\\s*([A-Za-z_]\\w*)\\s*(.*)$");
    private static final Pattern OBJ_DEFINE = Pattern.compile("^([A-Za-z_]\\w*)\\s*(.*)$");
    /**
     * 函数宏形态：名字与 {@code (} **必须紧邻**（C 预处理器的函数宏语义 —— 公开语言事实）。
     * 曾允许 {@code \s*} 间隔，会把对象宏 {@code #define EXPR (1.0)} 误判成函数宏，
     * 其后 {@code EXPR} 裸引用因无实参列表永不展开（P4.1 BSL 取证后收紧）。
     */
    private static final Pattern FUNC_DEFINE =
            Pattern.compile("^([A-Za-z_]\\w*)\\(([^)]*)\\)\\s*(.*)$");

    /**
     * 本处理器管辖的指令。只有它们的行尾 {@code \} 会造成**静默错展开**（其余指令原样透传，
     * 续行与否都不改变我方行为）。
     */
    private static final Set<String> MANAGED_DIRECTIVES =
            Set.of("define", "undef", "ifdef", "ifndef", "if", "elif", "else", "endif");

    /**
     * 是否为「行尾续行」的指令行。
     *
     * <p>🔴 **A1 的合并点**（2026-10-10）：口径原先在本类与 {@code GlslTextScan.preprocessorSkipLines}
     * 各写一份，两处注释都承诺「A1 建单一词法源后合并」⇒ 现在唯一实现是
     * {@link dev.vkdisp.glsl.lexer.GlslTokens#endsWithLineContinuation}（本类只留这层命名封装）。
     *
     * <p>🔖 已知过度报警的形态：宏体本身以反斜杠字符结尾（GLSL 里写不出这种合法字面量）⇒ 宁可按
     * 「可见」处理（T11），不在本阶做词法级判别（那属于 A2 的职责）。
     */
    private static boolean endsWithLineContinuation(String trimmedDirective) {
        return dev.vkdisp.glsl.lexer.GlslTokens.endsWithLineContinuation(trimmedDirective);
    }
}
