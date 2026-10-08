package dev.vkdisp.pack.uniform;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 【参考调研】GAP-021 表达式解析器（递归下降，词法+语法合一，纯 Java）
 * 0. 合规核对（第 0 步闸门，通过）：
 *    参考对象 = shaders.properties 官方参考「Custom Uniforms」页列出的**语法事实**：
 *    字面量（数字 / true / false / pi）、标识符、向量分量访问、一元 {@code -} 与 {@code !}、
 *    二元 {@code + - * / %}、比较 {@code == != < <= > >=}、逻辑 {@code && ||}、
 *    函数调用 {@code f(a, b, ...)}、括号。三元的优先级层次按本项目自有口径排列
 *    （与仓库内已有的 {@code ConditionalPreprocessor.BoolExpr} 同一套：比较 < 加减 < 乘除 < 一元）。
 *    零代码复制；Iris/OptiFine 的实现源码禁止阅读（07 L12 / X19-X21），故语法只能照文档搭。
 *    → 能否并入本项目（MIT）：可以
 *    → 例外条款：无
 * 1. 官方/主实现：无外部可读实现 ⇒ 自研。
 * 2. 备选：把表达式交给 GLSL 侧转译（否决：这些表达式按 OF 语义在 **CPU** 上逐帧求值，
 *    且文档明说 `variable.` 根本不上传 GPU，走 GLSL 就等于换了一套语义）。
 * 3. 我们的差异点：① 函数名合法性**不在解析期判定**（函数表在
 *    {@link PackUniformEvaluator}，那里才有点名「未知函数」的能力，解析期拒绝会把
 *    错误信息降级成「语法错」）；② 语法错误抛 {@link SyntaxException}（带原文），
 *    由 {@link PackUniformSet} 逐条转成诊断 —— 一条坏表达式不许连带整个包加载失败（T11）；
 *    ③ 数字一律 {@code double}：int 型 uniform 的取整在输出边界做（见 {@link PackUniformSet}）。
 * 4. 许可证核对结论：本项目 MIT；零第三方代码并入。
 * 5. 性能基线：❄️ 冷路径（包激活时一次），清晰优先（18-PARALLEL §7.7）。
 */
final class UniformExpressionParser {

    private static final Set<String> TWO_CHAR_OPERATORS =
            Set.of("==", "!=", "<=", ">=", "&&", "||");

    private UniformExpressionParser() {
    }

    /** 语法错误（携带表达式原文；不是崩溃，是**这条定义**的跳过理由）。 */
    static final class SyntaxException extends RuntimeException {
        SyntaxException(String message, String source) {
            super("vkdisp: 表达式语法错误：" + message + "（原文=" + source + "）");
        }
    }

    /** 把 `uniform.*` / `variable.*` 的值部分解析成 AST。 */
    static UniformExpr parse(String source) {
        List<String> tokens = tokenize(source);
        int[] cursor = {0};
        UniformExpr result = parseComparison(tokens, cursor, source);
        if (cursor[0] != tokens.size()) {
            throw new SyntaxException("多余的符号 '" + tokens.get(cursor[0]) + "'", source);
        }
        return result;
    }

    private static List<String> tokenize(String source) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '(' || c == ')' || c == ',' || c == '.') {
                tokens.add(String.valueOf(c));
                i++;
            } else if (i + 1 < source.length()
                    && TWO_CHAR_OPERATORS.contains(source.substring(i, i + 2))) {
                tokens.add(source.substring(i, i + 2));
                i += 2;
            } else if (c == '+' || c == '-' || c == '*' || c == '/' || c == '%'
                    || c == '<' || c == '>' || c == '!' || c == '=') {
                tokens.add(String.valueOf(c));
                i++;
            } else if (Character.isDigit(c)) {
                i = readNumber(source, i, tokens);
            } else if (Character.isJavaIdentifierStart(c)) {
                i = readIdentifier(source, i, tokens);
            } else {
                throw new SyntaxException("非法字符 '" + c + "'", source);
            }
        }
        return tokens;
    }

    private static int readNumber(String source, int start, List<String> tokens) {
        int i = start;
        while (i < source.length() && Character.isDigit(source.charAt(i))) {
            i++;
        }
        // 小数点只在**数字之后**且后面还有数字时属于这个数（`cameraPosition.y` 的那个点
        // 必须留给分量访问，否则 .0/.1 这类下标写法会被吞掉）。
        if (i + 1 < source.length() && source.charAt(i) == '.'
                && Character.isDigit(source.charAt(i + 1))) {
            i++;
            while (i < source.length() && Character.isDigit(source.charAt(i))) {
                i++;
            }
        }
        tokens.add(source.substring(start, i));
        return i;
    }

    private static int readIdentifier(String source, int start, List<String> tokens) {
        int i = start;
        while (i < source.length() && Character.isJavaIdentifierPart(source.charAt(i))) {
            i++;
        }
        tokens.add(source.substring(start, i));
        return i;
    }

    private static boolean at(List<String> tokens, int[] cursor, String token) {
        return cursor[0] < tokens.size() && tokens.get(cursor[0]).equals(token);
    }

    private static UniformExpr parseComparison(List<String> tokens, int[] cursor, String source) {
        UniformExpr left = parseAdditive(tokens, cursor, source);
        while (cursor[0] < tokens.size() && isComparison(tokens.get(cursor[0]))) {
            String operator = tokens.get(cursor[0]++);
            left = new UniformExpr.Binary(operator, left,
                    parseAdditive(tokens, cursor, source));
        }
        return left;
    }

    private static boolean isComparison(String token) {
        return token.equals("<") || token.equals("<=") || token.equals(">") || token.equals(">=")
                || token.equals("==") || token.equals("!=");
    }

    private static UniformExpr parseAdditive(List<String> tokens, int[] cursor, String source) {
        UniformExpr left = parseMultiplicative(tokens, cursor, source);
        while (cursor[0] < tokens.size()
                && (at(tokens, cursor, "+") || at(tokens, cursor, "-"))) {
            String operator = tokens.get(cursor[0]++);
            left = new UniformExpr.Binary(operator, left,
                    parseMultiplicative(tokens, cursor, source));
        }
        return left;
    }

    private static UniformExpr parseMultiplicative(List<String> tokens, int[] cursor, String source) {
        UniformExpr left = parseUnary(tokens, cursor, source);
        while (cursor[0] < tokens.size()
                && (at(tokens, cursor, "*") || at(tokens, cursor, "/") || at(tokens, cursor, "%"))) {
            String operator = tokens.get(cursor[0]++);
            left = new UniformExpr.Binary(operator, left, parseUnary(tokens, cursor, source));
        }
        return left;
    }

    private static UniformExpr parseUnary(List<String> tokens, int[] cursor, String source) {
        if (at(tokens, cursor, "-") || at(tokens, cursor, "!") || at(tokens, cursor, "+")) {
            String operator = tokens.get(cursor[0]++);
            UniformExpr operand = parseUnary(tokens, cursor, source);
            if (operator.equals("+")) {
                return operand;
            }
            if (operand instanceof UniformExpr.Literal literal && operator.equals("-")) {
                return new UniformExpr.Literal(-literal.value());
            }
            return new UniformExpr.Unary(operator, operand);
        }
        return parsePrimary(tokens, cursor, source);
    }

    private static UniformExpr parsePrimary(List<String> tokens, int[] cursor, String source) {
        if (cursor[0] >= tokens.size()) {
            throw new SyntaxException("表达式不完整", source);
        }
        if (at(tokens, cursor, "(")) {
            cursor[0]++;
            UniformExpr inner = parseComparison(tokens, cursor, source);
            if (!at(tokens, cursor, ")")) {
                throw new SyntaxException("括号不匹配", source);
            }
            cursor[0]++;
            return inner;
        }
        String token = tokens.get(cursor[0]++);
        if (token.equals(")") || token.equals(",") || token.equals(".")) {
            throw new SyntaxException("多余的符号 '" + token + "'", source);
        }
        if (Character.isDigit(token.charAt(0))) {
            return new UniformExpr.Literal(Double.parseDouble(token));
        }
        return parseNameOrCall(tokens, cursor, token, source);
    }

    private static UniformExpr parseNameOrCall(List<String> tokens, int[] cursor,
            String name, String source) {
        if (at(tokens, cursor, "(")) {
            cursor[0]++;
            List<UniformExpr> arguments = new ArrayList<>();
            if (!at(tokens, cursor, ")")) {
                parseArguments(tokens, cursor, arguments, source);
            }
            if (!at(tokens, cursor, ")")) {
                throw new SyntaxException("函数 '" + name + "' 的括号不匹配", source);
            }
            cursor[0]++;
            return new UniformExpr.Call(name.strip(), List.copyOf(arguments));
        }
        // 文档里的常量字面量：pi / true / false（它们不能走「未解析输入」那条路）。
        switch (name) {
            case "pi" -> {
                return new UniformExpr.Literal(Math.PI);
            }
            case "true" -> {
                return new UniformExpr.Literal(1.0);
            }
            case "false" -> {
                return new UniformExpr.Literal(0.0);
            }
            default -> {
                // 继续：普通标识符（可能带向量分量访问）。
            }
        }
        int element = UniformExpr.NO_ELEMENT;
        if (at(tokens, cursor, ".")) {
            cursor[0]++;
            element = readElement(tokens, cursor, source);
        }
        return new UniformExpr.Name(name.strip(), element);
    }

    private static void parseArguments(List<String> tokens, int[] cursor,
            List<UniformExpr> arguments, String source) {
        arguments.add(parseComparison(tokens, cursor, source));
        while (at(tokens, cursor, ",")) {
            cursor[0]++;
            arguments.add(parseComparison(tokens, cursor, source));
        }
    }

    /** 分量下标：字母（xyzw / rgba / stpq）或数字 0..3；越界即语法错（宁可报错也不静默取 0）。 */
    private static int readElement(List<String> tokens, int[] cursor, String source) {
        if (cursor[0] >= tokens.size()) {
            throw new SyntaxException("表达式末尾缺少分量名", source);
        }
        String token = tokens.get(cursor[0]++);
        int index = token.length() == 1 ? elementIndex(token.charAt(0)) : -1;
        if (index < 0) {
            throw new SyntaxException("非法的分量名 '" + token + "'", source);
        }
        return index;
    }

    /** 同一个分量在文档里有四种写法，全部映射到 0..3；不是这四种就是错。 */
    private static int elementIndex(char c) {
        char lower = Character.toLowerCase(c);
        if (lower >= '0' && lower <= '3') {
            return lower - '0';
        }
        int index = "xyzw".indexOf(lower);
        if (index >= 0) {
            return index;
        }
        index = "rgba".indexOf(lower);
        return index >= 0 ? index : "stpq".indexOf(lower);
    }
}
