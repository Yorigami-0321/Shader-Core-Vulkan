package dev.vkdisp.glsl.lexer;

import dev.vkdisp.glsl.TranslateDiagnostic;
import dev.vkdisp.glsl.translate.GlslTextScan;
import java.util.ArrayList;
import java.util.List;

/**
 * 【参考调研】A1 · L1 单一词法源（无损 token 流）—— `19` §2.5 的 L1 层
 * 0. 合规：参考对象 = ①GLSL 公开词法事实（标识符集、注释两型、双引号字符串带转义、
 *    预处理指令以行尾 {@code \} 续行 —— 这些是**语言规范事实**，不受版权保护）；
 *    ②本仓库自有的三份现成品：{@code translate/CommentState}（跨行块注释状态机、注释**等长**抹平）、
 *    {@code translate/GlslTextScan}（注释+字符串抹平的等长视图 + 标识符/括号原语）、
 *    {@code preprocess/DefineProcessor.ExprEval}（真 token 词法）。
 *    外部候选：ANGLE 的 preprocessor 分层（BSD-3，**只当架构参照**，不并 C++）；
 *    glsl-transformer / glsl-preprocessor（GPL-3.0+例外）按禁止处理（`07` L12 / X20 / X21）——
 *    未读其代码、零行并入。本项目 MIT。
 * 1. 职责：**全仓唯一的词法源**。三条硬性质：
 *    ① **无损** —— {@link #render(List)} 把 {@link #tokenize(String)} 的结果拼回去必须与输入**逐字节相同**
 *      （未改动的区段不许被规范化：注释里承载包语义，如 {@code DRAWBUFFERS:} / {@code const} 指令 /
 *      {@code /*! … *\/}，打印即丢）；
 *    ② **带原文下标** —— 每个 token 记 {@code [start, end)} 与行号，改写只允许「替换一段 offset 区间」；
 *    ③ **等长视图** —— {@link #codeViews(String)} 沿用 {@link GlslTextScan} 的「注释/字符串抹成空格」口径，
 *      与原始行**等长同下标**，所以视图里找到的区间可以直接拿去改原始行。
 * 2. 差异点：本类**不做预处理语义**（那是 L2 / A2 的 jcpp 路），只提供词法事实。
 *    它同时是「续行判定」的**唯一实现**（{@link #endsWithLineContinuation}）——
 *    旧状况是 {@code GlslTextScan.preprocessorSkipLines} 与 {@code DefineProcessor} 各写一份
 *    {@code endsWith("\")}，两边互相在注释里承诺「A1 之后合并」，本类就是那次合并。
 * 3. 非显然约束：块注释跨行**只在同一份文件内**成立 ⇒ {@link #tokenize} 与 {@link #codeViews} 都以
 *    「一整份源文本」为单位；调用方不许把一份文件切成两截分别喂（会丢跨行状态）。
 * 4. 性能：❄️ 冷路径（解析/转译期）；单遍线性扫描，清晰优先、无缓存（`19` §2.7、T14）。
 */
public final class GlslTokens {

    /** token 种类。注释与空白都是**一等公民**（无损的前提）。 */
    public enum Kind {
        /** 空格 / 制表符 / 回车（不含换行）。 */
        BLANK,
        /** 换行（{@code \n} 或 {@code \r\n}，各算一个 token）。 */
        NEWLINE,
        /** 标识符（字母/下划线开头）。 */
        IDENT,
        /** 数字字面量（含 GLSL 的后缀 {@code u/U/f/F} 与浮点/科学计数形态）。 */
        NUMBER,
        /** 字符串字面量（双引号，含转义；未闭合按到行尾处理，不抛）。 */
        STRING,
        /** 行注释 {@code //…}（不含换行）。 */
        LINE_COMMENT,
        /** 块注释 {@code /*…&#47;*}（可跨行；未闭合到文件尾）。 */
        BLOCK_COMMENT,
        /** 其余单字符（运算符 / 括号 / 分号 / 未知的非 ASCII 字符）。 */
        PUNCT
    }

    /**
     * 一个 token。
     *
     * @param text  原文切片（{@code source.substring(start, end)} 逐字相同）
     * @param line  起始行号（1 起）
     * @param start 起始下标（0 基，指向**原始 source**）
     * @param end   结束下标（不含）
     */
    public record Token(Kind kind, String text, int line, int start, int end) {
    }

    /**
     * 一条**逻辑行**：把 {@code \} 续行拼起来之后的结果（C 预处理器的行拼接事实）。
     *
     * @param text            拼接后的内容（已去掉续行反斜杠与其后的换行）
     * @param firstLineNumber 首物理行号（1 起）—— 诊断落位用它
     * @param continued       本逻辑行是否由多条物理行拼成（= 旧实现会截断的那种写法）
     */
    public record LogicalLine(String text, int firstLineNumber, boolean continued) {
    }

    private GlslTokens() {
    }

    /**
     * 无损词法分析。
     *
     * <p>不变量：{@code render(tokenize(s)).equals(s)} 对任意输入成立（含未闭合注释/字符串）。
     */
    public static List<Token> tokenize(String source) {
        List<Token> out = new ArrayList<>();
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        int line = 1;
        int i = 0;
        int n = source.length();
        while (i < n) {
            int start = i;
            Kind kind = kindAt(source, i);
            i = endOfToken(source, i, kind);
            String text = source.substring(start, i);
            out.add(new Token(kind, text, line, start, i));
            line += countChar(text, '\n');
        }
        return List.copyOf(out);
    }

    /** 从 {@code i} 起的这段属于哪一类（单一分派点，便于逐类测试）。 */
    private static Kind kindAt(String source, int i) {
        char c = source.charAt(i);
        if (c == '/' && i + 1 < source.length()) {
            char second = source.charAt(i + 1);
            if (second == '/') {
                return Kind.LINE_COMMENT;
            }
            if (second == '*') {
                return Kind.BLOCK_COMMENT;
            }
        }
        if (c == '"') {
            return Kind.STRING;
        }
        if (c == '\n' || c == '\r') {
            return Kind.NEWLINE;
        }
        if (c == ' ' || c == '\t') {
            return Kind.BLANK;
        }
        if (GlslTextScan.isIdentifierStart(c)) {
            return Kind.IDENT;
        }
        if (isNumberStart(source, i)) {
            return Kind.NUMBER;
        }
        return Kind.PUNCT;
    }

    /** 本 token 的结束下标（不含）。每类一个短函数，便于逐类测试（也守住 QD-04 的方法长度）。 */
    private static int endOfToken(String source, int i, Kind kind) {
        int n = source.length();
        return switch (kind) {
            case LINE_COMMENT -> endOfLineComment(source, i);
            case BLOCK_COMMENT -> endOfBlockComment(source, i);
            case STRING -> endOfString(source, i);
            case NEWLINE -> (source.charAt(i) == '\r' && i + 1 < n && source.charAt(i + 1) == '\n')
                    ? i + 2 : i + 1;
            case BLANK -> endOfBlank(source, i);
            case IDENT -> endOfIdent(source, i);
            case NUMBER -> endOfNumber(source, i);
            default -> i + 1;
        };
    }

    /** 行注释：吃到行尾（**不含**换行符本身，换行是下一个 token）。 */
    private static int endOfLineComment(String source, int i) {
        int n = source.length();
        int j = i + 2;
        while (j < n && source.charAt(j) != '\n') {
            j++;
        }
        return j;
    }

    /** 块注释：吃到 {@code *}{@code /}；未闭合则到文件尾（不抛）。 */
    private static int endOfBlockComment(String source, int i) {
        int n = source.length();
        int j = i + 2;
        while (j < n && !(source.charAt(j) == '*' && j + 1 < n && source.charAt(j + 1) == '/')) {
            j++;
        }
        return Math.min(j + 2, n);
    }

    /** 字符串：双引号成对，{@code \} 吃掉下一字符；未闭合则到文件尾。 */
    private static int endOfString(String source, int i) {
        int n = source.length();
        int j = i + 1;
        while (j < n) {
            char current = source.charAt(j);
            if (current == '\\') {
                j += 2;
                continue;
            }
            j++;
            if (current == '"') {
                break;
            }
        }
        return Math.min(j, n);
    }

    private static int endOfBlank(String source, int i) {
        int n = source.length();
        int j = i;
        while (j < n && (source.charAt(j) == ' ' || source.charAt(j) == '\t')) {
            j++;
        }
        return j;
    }

    private static int endOfIdent(String source, int i) {
        int n = source.length();
        int j = i;
        while (j < n && GlslTextScan.isIdentifierPart(source.charAt(j))) {
            j++;
        }
        return j;
    }

    private static int endOfNumber(String source, int i) {
        int n = source.length();
        int j = i;
        while (j < n && isNumberPart(source, j)) {
            j++;
        }
        return j;
    }

    private static boolean isNumberStart(String source, int i) {
        char c = source.charAt(i);
        return isDigit(c) || (c == '.' && i + 1 < source.length() && isDigit(source.charAt(i + 1)));
    }

    /** 数字字面量的后续字符：数字/小数点/指数与十六进制字母/类型后缀；{@code +}{@code -} 只在 e/E 之后。 */
    private static boolean isNumberPart(String source, int j) {
        char c = source.charAt(j);
        if (isDigit(c) || c == '.' || isHexLetter(c) || c == 'u' || c == 'U' || c == 'f' || c == 'F') {
            return true;
        }
        if (c == 'e' || c == 'E' || c == 'p' || c == 'P') {
            return true;
        }
        if ((c == '+' || c == '-') && j > 0) {
            char previous = source.charAt(j - 1);
            return previous == 'e' || previous == 'E';
        }
        return false;
    }

    private static int countChar(String text, char target) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == target) {
                n++;
            }
        }
        return n;
    }

    /** 把 token 流拼回原文（无损自检用；也是「未改动区原样输出」的实现方式）。 */
    public static String render(List<Token> tokens) {
        StringBuilder out = new StringBuilder();
        for (Token token : tokens) {
            out.append(token.text());
        }
        return out.toString();
    }

    /** 只保留代码 token（丢掉空白/换行/注释/字符串），用于「找下一个标识符/括号」这类查询。 */
    public static List<Token> codeTokens(String source) {
        List<Token> out = new ArrayList<>();
        for (Token token : tokenize(source)) {
            if (token.kind() != Kind.BLANK && token.kind() != Kind.NEWLINE
                    && token.kind() != Kind.LINE_COMMENT && token.kind() != Kind.BLOCK_COMMENT) {
                out.add(token);
            }
        }
        return List.copyOf(out);
    }

    /**
     * 整份源文本的「无注释无字符串等长视图」（逐行）。
     *
     * <p>🔖 直接复用 {@link GlslTextScan#codeViews}，**不另写一份状态机** ——
     * 本类的存在意义就是「只有一个地方决定什么叫注释/字符串」。
     *
     * @param diagnostics 收集未闭合块注释的 ERROR（T11：不静默）
     */
    public static List<String> codeViews(String source, List<TranslateDiagnostic> diagnostics) {
        List<String> rawLines = physicalLines(source);
        return GlslTextScan.codeViews(rawLines, diagnostics == null ? new ArrayList<>() : diagnostics);
    }

    /**
     * 已切好行的等长视图（translate/ 各阶段的入口 —— 它们已经按行工作，不必再拼回整份文本）。
     *
     * <p>🔖 委托 {@link GlslTextScan#codeViews(List, List)}，本类只是**唯一门面**。
     */
    public static List<String> codeViews(List<String> rawLines, List<TranslateDiagnostic> diagnostics) {
        return GlslTextScan.codeViews(rawLines, diagnostics == null ? new ArrayList<>() : diagnostics);
    }

    /**
     * 预处理指令行标记：{@code true} = 本行属于 {@code #} 指令或其续行，translate/ 阶段应跳过不改写。
     *
     * <p>🔖 续行判定走 {@link #endsWithLineContinuation}（全仓唯一实现）——
     * 旧 {@code GlslTextScan.preprocessorSkipLines} 自己 {@code endsWith("\\")}，
     * 对「反斜杠后带尾随空格」的包实测写法**不认**（与 C 预处理规范不符）；本版本修正了这一点。
     *
     * @param rawLines  原始行（不含换行符）
     * @param codeLines 与 {@code rawLines} 等长的无注释视图
     */
    public static boolean[] preprocessorSkipLines(List<String> rawLines, List<String> codeLines) {
        boolean[] skip = new boolean[rawLines.size()];
        boolean continuation = false;
        for (int index = 0; index < rawLines.size(); index++) {
            boolean hashLine = codeLines.get(index).strip().startsWith("#");
            skip[index] = continuation || hashLine;
            continuation = (continuation || hashLine)
                    && endsWithLineContinuation(rawLines.get(index).strip());
        }
        return skip;
    }

    /**
     * 文件头部区的结束下标（0 基）：跳过空行、注释、{@code #} 指令，停在首条代码行。
     *
     * <p>🔖 委托 {@link GlslTextScan#headerEnd}，本类只是门面。
     */
    public static int headerEnd(List<String> sourceLines) {
        return GlslTextScan.headerEnd(sourceLines);
    }

    /** 物理行（不含换行符），口径与 {@link #logicalLines} 的切分一致。 */
    public static List<String> physicalLines(String source) {
        List<String> out = new ArrayList<>();
        if (source == null || source.isEmpty()) {
            return out;
        }
        for (String line : source.split("\n", -1)) {
            out.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
        }
        // 与旧实现一致：末尾换行产生的空尾段不算一条真实行。
        if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    /**
     * 行拼接：把以 {@code \} 收尾的物理行与其下一行拼成一条逻辑行（C 预处理器的规范语义）。
     *
     * <p>🔖 本阶**只用它做判定与诊断**，不用它改写 {@code DefineProcessor} 的行为 ——
     * 真按逻辑行展开属 L2（A2 的 jcpp {@code JoinReader}），那一步要过逐字节 diff 硬门。
     */
    public static List<LogicalLine> logicalLines(String source) {
        List<String> lines = physicalLines(source);
        List<LogicalLine> out = new ArrayList<>();
        int index = 0;
        while (index < lines.size()) {
            StringBuilder text = new StringBuilder();
            int firstLine = index + 1;
            boolean continued = false;
            String current = lines.get(index);
            index++;
            while (endsWithLineContinuation(current.strip()) && index < lines.size()) {
                continued = true;
                text.append(current, 0, current.strip().length() - 1);
                current = lines.get(index);
                index++;
            }
            text.append(current);
            out.add(new LogicalLine(text.toString(), firstLine, continued));
        }
        return List.copyOf(out);
    }

    /**
     * 行尾续行判定 —— **全仓唯一实现**。
     *
     * <p>口径：{@code strip} 之后以 {@code \} 结尾（因此 {@code "\ "} 这种「反斜杠后带尾随空格」的
     * 包实测写法也算续行）。旧状况：{@code GlslTextScan.preprocessorSkipLines} 与
     * {@code DefineProcessor.endsWithLineContinuation} 各写一份，两边注释都写着「A1 之后合并」。
     */
    public static boolean endsWithLineContinuation(String strippedLine) {
        return strippedLine.endsWith("\\");
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHexLetter(char c) {
        return (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
