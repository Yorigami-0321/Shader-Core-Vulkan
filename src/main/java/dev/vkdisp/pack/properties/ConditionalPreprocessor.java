package dev.vkdisp.pack.properties;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * 【参考调研】属性文件条件编译预处理（OF/Iris shaders.properties 的 #if 族）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = OptiFine 官方文档 shaders.properties 的 #ifdef / #ifndef / #if / #elif / #else / #endif 语义
 *    与「行尾反斜杠续行」的 .properties 格式事实（事实性信息，不受版权保护）；Iris 同语义（LGPL-3.0，只读事实）。
 *    零代码复制。表达式求值口径对齐本仓库自有
 *    {@code glsl/preprocess/DefineProcessor.ExprEval}（标识符未定义 → 0、非零为真），非外部来源。
 *    → 能否并入本项目（MIT）：可以（仅含格式事实）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：OF 用基于选项宏的条件编译做整段包含/剔除；本预处理在「已知已定义宏集合」上求值，
 *    把不满足条件的分支整段去掉。shaders.properties 不提供 #define / #include（见 18-PARALLEL 交接 §6.2），
 *    遇到即显式报错（T11），绝不静默吞掉。
 * 2. 备选：无。
 * 3. 我们的差异点：宏集合由调用方（主线/解析层）提供；A 线自身不发现宏（宏发现属 C 线）。
 *    数值宏已补 {@code MC_VERSION} 这一格（本轮实测：只有「定义/未定义」两态时
 *    {@code #if MC_VERSION >= 11800} 永远按 0 判假 ⇒ BSL 生物群集那批 uniform 一直取老数字 ID 那一支）。
 *    取值走 {@link dev.vkdisp.McVersion#numericOf(String)}，编码口径与出处写在那儿。
 *    其余比较式取值环境（选项当前数值等）仍未取证（X9）⇒ 按 0/1 求值且**不猜**，缺哪个补哪个。
 * 4. 许可证核对结论：本项目 MIT；本文件零第三方代码。
 * 5. 性能基线：❄️ 冷路径（加载/重载时跑一次），不做任何性能优化（18-PARALLEL §7.7）。
 */
final class ConditionalPreprocessor {

    private ConditionalPreprocessor() {
    }

    /** 在给定「已定义宏」集合上求值条件编译，返回生效的内容行（已剔除注释/空行/未命中分支）。 */
    static List<String> preprocess(List<String> lines, Set<String> definedMacros) {
        Set<String> macros = definedMacros == null ? Set.of() : definedMacros;
        // .properties 自然行续行（行尾奇数个反斜杠）必须先并成逻辑行再做指令识别，
        // 否则 `key=a \` / `b` 会被拆成两行，后半行缺 '='（BSL block.properties 160 处续行实证）。
        List<String> logicalLines = joinContinuations(lines);
        List<String> out = new ArrayList<>();
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(true, true, true, false));
        int index = 0;
        for (String raw : logicalLines) {
            index++;
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                if (head(line, "#ifdef")) {
                    pushCondition(stack, macros.contains(exprOf(line, "#ifdef", index)));
                } else if (head(line, "#ifndef")) {
                    pushCondition(stack, !macros.contains(exprOf(line, "#ifndef", index)));
                } else if (head(line, "#if")) {
                    pushCondition(stack, BoolExpr.eval(exprOf(line, "#if", index), macros));
                } else if (head(line, "#elif")) {
                    evalElif(stack, exprOf(line, "#elif", index), macros, index);
                } else if (line.equals("#else")) {
                    if (stack.size() <= 1) {
                        throw new IllegalArgumentException("vkdisp: #else 没有匹配的 #if（行 " + index + "）");
                    }
                    Frame f = stack.pop();
                    if (f.afterElse()) {
                        throw new IllegalArgumentException("vkdisp: #else 重复出现（行 " + index + "）");
                    }
                    // else 分支 = 父级生效 && 前面没有任何分支命中（嵌套在被剔除父级下时恒不生效，
                    // 旧实现用 !include 反转会在这种嵌套下错误放行）。
                    stack.push(new Frame(f.parentInclude(), f.taken(),
                            f.parentInclude() && !f.taken(), true));
                } else if (line.equals("#endif")) {
                    if (stack.size() <= 1) {
                        throw new IllegalArgumentException("vkdisp: #endif 没有匹配的 #if（行 " + index + "）");
                    }
                    stack.pop();
                } else if (line.startsWith("#define ") || line.startsWith("#include ")) {
                    throw new IllegalArgumentException(
                            "vkdisp: shaders.properties 不支持 " + line.split("\\s+", 2)[0]
                                    + " 指令（行 " + index + "，见 18-PARALLEL 交接 §6.2）");
                } else {
                    // 其它 # 开头 = 注释行，跳过
                    continue;
                }
            } else if (line.startsWith("!")) {
                // properties 注释（! 前缀），跳过
                continue;
            } else if (stack.peek().include()) {
                out.add(line);
            }
        }
        if (stack.size() != 1) {
            throw new IllegalArgumentException("vkdisp: #if/#endif 未配对（剩余 " + (stack.size() - 1) + " 个未闭合）");
        }
        return out;
    }

    /**
     * 指令头匹配：{@code #if} 后跟空白才算条件指令（裸指令也算，由 {@link #exprOf} 报缺表达式）。
     * {@code #ifdef} / {@code #ifndef} 不会误配进 {@code #if} —— 其后是标识符首字符，非空白。
     */
    private static boolean head(String line, String directive) {
        if (!line.startsWith(directive)) {
            return false;
        }
        return line.length() == directive.length()
                || Character.isWhitespace(line.charAt(directive.length()));
    }

    /** 取指令头之后的表达式；空白表达式 = 显式报错（T11，不静默当注释吞掉）。 */
    private static String exprOf(String line, String directive, int index) {
        String expr = line.substring(directive.length()).strip();
        if (expr.isEmpty()) {
            throw new IllegalArgumentException(
                    "vkdisp: " + directive + " 缺少条件表达式（行 " + index + "）");
        }
        return expr;
    }

    private static void pushCondition(Deque<Frame> stack, boolean condition) {
        Frame parent = stack.peek();
        stack.push(new Frame(parent.include(), condition, parent.include() && condition, false));
    }

    private static void evalElif(Deque<Frame> stack, String expr, Set<String> macros, int index) {
        if (stack.size() <= 1) {
            throw new IllegalArgumentException("vkdisp: #elif 没有匹配的 #if（行 " + index + "）");
        }
        Frame f = stack.pop();
        if (f.afterElse()) {
            throw new IllegalArgumentException("vkdisp: #elif 出现在 #else 之后（行 " + index + "）");
        }
        boolean condition = BoolExpr.eval(expr, macros);
        // 生效条件 = 父级生效 && 之前分支全未命中 && 本分支命中；taken 记录「已命中过」供后续 elif/else 用。
        stack.push(new Frame(f.parentInclude(), f.taken() || condition,
                f.parentInclude() && !f.taken() && condition, false));
    }

    /**
     * 条件帧。
     *
     * @param parentInclude 父级是否生效（本链所有分支的总闸）
     * @param taken         本链中是否已有分支条件为真（命中过就轮不到后面的 elif/else）
     * @param include       本帧对应分支当前是否放行
     * @param afterElse     是否已经走过 #else（之后再出现 #elif 即非法）
     */
    private record Frame(boolean parentInclude, boolean taken, boolean include, boolean afterElse) {
    }

    /** 行尾奇数个反斜杠 = 续行（偶数个是转义出的字面反斜杠，.properties 格式事实）。 */
    private static boolean endsWithOddBackslash(String line) {
        int trailing = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            trailing++;
        }
        return trailing % 2 == 1;
    }

    /** 把行尾续行并成逻辑行（并掉反斜杠本身；文件以续行结尾时按现状落盘，后续指令规则照常把关）。 */
    private static List<String> joinContinuations(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        StringBuilder pending = null;
        for (String raw : lines) {
            // CRLF 归一必须先于续行判定：BSL shaders.properties 全文 283 个 \r，若不先摘掉，
            // `+ \<CR>` 的行尾判定（看最后一个字符）永远看不到反斜杠 → 续行不并 → 后半行缺 '='（p415 run1 实证）。
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            boolean continues = endsWithOddBackslash(line);
            String part = continues ? line.substring(0, line.length() - 1) : line;
            if (pending == null) {
                pending = new StringBuilder();
            }
            pending.append(part);
            if (!continues) {
                out.add(pending.toString());
                pending = null;
            }
        }
        if (pending != null) {
            out.add(pending.toString());
        }
        return out;
    }

    /**
     * 极简表达式求值（口径对齐 {@code DefineProcessor.ExprEval}）：数字字面量（含小数）、
     * 标识符（已定义 → 1，未定义 → 0）、比较 {@code == != < <= > >=}、逻辑 {@code && || !} 与括号。
     * 返回数值，非零为真。两侧操作数**恒定求值**（不用 Java 短路 —— 否则 {@code A || B}
     * 在 A 为真时右侧 token 不被消费，会误报「多余符号」）。
     */
    private static final class BoolExpr {
        private static final Set<String> OPERATORS =
                Set.of("&&", "||", "==", "!=", "<", "<=", ">", ">=");

        static boolean eval(String expr, Set<String> macros) {
            List<String> toks = tokenize(expr);
            int[] pos = {0};
            double v = parseOr(toks, pos, macros);
            if (pos[0] != toks.size()) {
                throw new IllegalArgumentException("vkdisp: #if 表达式有多余符号：" + expr);
            }
            return v != 0.0;
        }

        private static List<String> tokenize(String expr) {
            List<String> toks = new ArrayList<>();
            int i = 0;
            while (i < expr.length()) {
                char c = expr.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (i + 1 < expr.length() && isTwoCharOp(c, expr.charAt(i + 1))) {
                    toks.add(expr.substring(i, i + 2));
                    i += 2;
                } else if (c == '(' || c == ')' || c == '!' || c == '<' || c == '>') {
                    toks.add(String.valueOf(c));
                    i++;
                } else if (Character.isDigit(c)) {
                    int j = i;
                    while (j < expr.length() && Character.isDigit(expr.charAt(j))) {
                        j++;
                    }
                    if (j + 1 < expr.length() && expr.charAt(j) == '.'
                            && Character.isDigit(expr.charAt(j + 1))) {
                        j++;
                        while (j < expr.length() && Character.isDigit(expr.charAt(j))) {
                            j++;
                        }
                    }
                    toks.add(expr.substring(i, j));
                    i = j;
                } else if (Character.isJavaIdentifierStart(c)) {
                    int j = i;
                    while (j < expr.length() && Character.isJavaIdentifierPart(expr.charAt(j))) {
                        j++;
                    }
                    toks.add(expr.substring(i, j));
                    i = j;
                } else {
                    throw new IllegalArgumentException("vkdisp: #if 表达式含非法字符 '" + c + "'");
                }
            }
            return toks;
        }

        private static boolean isTwoCharOp(char a, char b) {
            return (a == '&' && b == '&') || (a == '|' && b == '|')
                    || (a == '=' && b == '=') || (a == '!' && b == '=')
                    || (a == '<' && b == '=') || (a == '>' && b == '=');
        }

        private static double parseOr(List<String> t, int[] p, Set<String> m) {
            double v = parseAnd(t, p, m);
            while (p[0] < t.size() && t.get(p[0]).equals("||")) {
                p[0]++;
                double r = parseAnd(t, p, m);
                v = (v != 0.0 || r != 0.0) ? 1.0 : 0.0;
            }
            return v;
        }

        private static double parseAnd(List<String> t, int[] p, Set<String> m) {
            double v = parseCompare(t, p, m);
            while (p[0] < t.size() && t.get(p[0]).equals("&&")) {
                p[0]++;
                double r = parseCompare(t, p, m);
                v = (v != 0.0 && r != 0.0) ? 1.0 : 0.0;
            }
            return v;
        }

        private static double parseCompare(List<String> t, int[] p, Set<String> m) {
            double v = parseNot(t, p, m);
            while (p[0] < t.size() && OPERATORS.contains(t.get(p[0]))) {
                String op = t.get(p[0]);
                if (op.equals("&&") || op.equals("||")) {
                    break;
                }
                p[0]++;
                double r = parseNot(t, p, m);
                boolean result = switch (op) {
                    case "==" -> v == r;
                    case "!=" -> v != r;
                    case "<" -> v < r;
                    case "<=" -> v <= r;
                    case ">" -> v > r;
                    case ">=" -> v >= r;
                    default -> throw new IllegalStateException("vkdisp: 未知道算符 " + op);
                };
                v = result ? 1.0 : 0.0;
            }
            return v;
        }

        private static double parseNot(List<String> t, int[] p, Set<String> m) {
            if (p[0] < t.size() && t.get(p[0]).equals("!")) {
                p[0]++;
                return parseNot(t, p, m) == 0.0 ? 1.0 : 0.0;
            }
            if (p[0] < t.size() && t.get(p[0]).equals("(")) {
                p[0]++;
                double v = parseOr(t, p, m);
                if (p[0] >= t.size() || !t.get(p[0]).equals(")")) {
                    throw new IllegalArgumentException("vkdisp: #if 表达式括号不匹配");
                }
                p[0]++;
                return v;
            }
            if (p[0] >= t.size()) {
                throw new IllegalArgumentException("vkdisp: #if 表达式不完整");
            }
            String tok = t.get(p[0]++);
            if (tok.equals(")")) {
                throw new IllegalArgumentException("vkdisp: #if 表达式语法错误（多余 ')'）");
            }
            if (OPERATORS.contains(tok)) {
                throw new IllegalArgumentException("vkdisp: #if 表达式语法错误（运算符 '" + tok + "' 缺少操作数）");
            }
            if (!tok.isEmpty() && Character.isDigit(tok.charAt(0))) {
                return Double.parseDouble(tok);
            }
            // 标识符：数值宏优先（本轮补的就是这一格 —— 此前只有「定义/未定义」两态，
            // 于是 `#if MC_VERSION >= 11800` 里 MC_VERSION 按 0 算 ⇒ 永远走老分支）。
            java.util.OptionalInt numeric = dev.vkdisp.McVersion.numericOf(tok);
            if (numeric.isPresent()) {
                return numeric.getAsInt();
            }
            // 其余标识符：已定义 → 1、未定义 → 0（与 GLSL 侧 DefineProcessor.ExprEval 同口径）。
            return m.contains(tok) ? 1.0 : 0.0;
        }
    }
}
