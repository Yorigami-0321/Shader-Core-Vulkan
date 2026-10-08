package dev.vkdisp.pack;
/**
 * 【自行补充】GAP-024 · {@code program.<名>.enabled=<布尔表达式>} 的<b>求值</b>（纯逻辑，可单测）。
 *
 * <p><b>为什么单独一个类</b>：链装配（{@link PackPostChain}）需要「这一级到底跑不跑」，
 * 而决定它的表达式在包的 properties 里。求值必须与编译用<b>同一份</b>选项值，
 * 所以这里<b>不碰</b>任何 GPU / 原版类型，只吃一个 {@code name -> value} 的解析器；
 * 接线由调用方负责（见 {@code PackPostChain} 的门控点）。
 *
 * <p><b>支持面按包的实际写法收口</b>（不照抄 OF 全语法，X9：只支持见过的）：
 * BSL v10.1.8 的 24 条 {@code program.*.enabled} 里只出现
 * <em>标识符</em>、{@code &&}、{@code !}、以及括号；没有 {@code ||}、没有比较、没有函数。
 * 本类仍接受 {@code ||} 与 {@code true}/{@code false}（对称性、成本极低），
 * 但<b>别的</b>记号（比较、算术、函数调用）一律判为 {@link Verdict#UNKNOWN}
 * 而不是「猜一个真值」。
 *
 * <p>🔴 <b>UNKNOWN 的处置是「保留这一级」+ 让调用方自报</b>：
 * 少跑一级 = 画面少一道工序（看得见的错误），多跑一级 = 白烧（隐藏的错误）。
 * 但「表达式看不懂」这件事本身是<b>我方能力缺口</b>，不是包的错 ⇒ 不许静默按真/假处理
 * （07-CONSTRAINTS X9 拒猜、X11 禁静默）。
 */
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** 三值布尔表达式求值器。 */
public final class ProgramEnableGate {

    /** 求值结果。{@link #UNKNOWN} = 表达式里有看不懂的名字或记号。 */
    public enum Verdict {
        TRUE, FALSE, UNKNOWN;

        boolean isTrue() {
            return this == TRUE;
        }

        boolean isFalse() {
            return this == FALSE;
        }
    }

    private ProgramEnableGate() {
    }

    /** 求值输出：结论 + 没认出来的名字（调用方拿它去自报）。 */
    public record Result(Verdict verdict, List<String> unresolvedNames, String expression) {
        public Result {
            unresolvedNames = List.copyOf(unresolvedNames);
        }

        /** 这一级该跑吗？UNKNOWN 按「跑」处理（保守：不因为看不懂就少画一级）。 */
        public boolean keepPass() {
            return verdict != Verdict.FALSE;
        }
    }

    /**
     * 求值一条 {@code enabled} 表达式。
     *
     * @param expression 包写的原文（如 {@code "FXAA && !RETRO_FILTER"}）；null/空白 ⇒
     *                   {@link Verdict#TRUE}（没有开关 = 恒启用）
     * @param resolver   名字 → 选项值；{@code Optional.empty()} 表示我方不掌握这个名字
     */
    public static Result evaluate(String expression, Function<String, Optional<String>> resolver) {
        List<String> unresolved = new ArrayList<>();
        if (expression == null || expression.isBlank()) {
            return new Result(Verdict.TRUE, unresolved, expression == null ? "" : expression.strip());
        }
        String text = expression.strip();
        try {
            Verdict v = new Parser(text, resolver, unresolved).parseAll();
            return new Result(v, unresolved, text);
        } catch (ParseException e) {
            // 语法不认识（比较、算术、括号不配对…）⇒ 判 UNKNOWN 并带上原因，让调用方说出来。
            unresolved.add("<语法:" + e.getMessage() + ">");
            return new Result(Verdict.UNKNOWN, unresolved, text);
        }
    }

    /** 解析失败（记号不认识）。 */
    private static final class ParseException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ParseException(String message) {
            super(message);
        }
    }

    /** 递归下降：or → and → unary → primary。三值逻辑，见 {@link #and}/{@link #or}。 */
    private static final class Parser {
        private final String text;
        private final Function<String, Optional<String>> resolver;
        private final List<String> unresolved;
        private int pos;

        Parser(String text, Function<String, Optional<String>> resolver, List<String> unresolved) {
            this.text = text;
            this.resolver = resolver;
            this.unresolved = unresolved;
        }

        Verdict parseAll() {
            Verdict v = parseOr();
            skipSpace();
            if (pos < text.length()) {
                throw new ParseException("表达式在 \"" + text.substring(pos) + "\" 处有多余记号");
            }
            return v;
        }

        private Verdict parseOr() {
            Verdict left = parseAnd();
            while (consume("||")) {
                Verdict right = parseAnd();
                left = or(left, right);
            }
            return left;
        }

        private Verdict parseAnd() {
            Verdict left = parseUnary();
            while (consume("&&")) {
                Verdict right = parseUnary();
                left = and(left, right);
            }
            return left;
        }

        private Verdict parseUnary() {
            skipSpace();
            if (consume("!")) {
                return not(parseUnary());
            }
            return parsePrimary();
        }

        private Verdict parsePrimary() {
            skipSpace();
            if (consume("(")) {
                Verdict v = parseOr();
                if (!consume(")")) {
                    throw new ParseException("括号没配对");
                }
                return v;
            }
            String name = readIdentifier();
            if (name.isEmpty()) {
                throw new ParseException("期望标识符，实际是 \"" + text.substring(Math.min(pos, text.length())) + "\"");
            }
            return valueOf(name);
        }

        /** 名字 → 三值。{@code true}/非零数字 = TRUE，{@code false}/{@code 0} = FALSE，认不出 = UNKNOWN。 */
        private Verdict valueOf(String name) {
            Optional<String> raw = resolver.apply(name);
            if (raw.isEmpty()) {
                unresolved.add(name);
                return Verdict.UNKNOWN;
            }
            String v = raw.get().strip();
            if (v.equalsIgnoreCase("true")) {
                return Verdict.TRUE;
            }
            if (v.equalsIgnoreCase("false") || v.isEmpty()) {
                return Verdict.FALSE;
            }
            try {
                return Float.parseFloat(v) == 0.0F ? Verdict.FALSE : Verdict.TRUE;
            } catch (NumberFormatException notNumber) {
                // 包用宏名当值（如 const 的字面量文本）⇒ 认不出就是认不出，交调用方报。
                unresolved.add(name);
                return Verdict.UNKNOWN;
            }
        }

        private String readIdentifier() {
            skipSpace();
            int start = pos;
            while (pos < text.length()
                    && (Character.isLetterOrDigit(text.charAt(pos)) || text.charAt(pos) == '_')) {
                pos++;
            }
            return text.substring(start, pos);
        }

        private boolean consume(String token) {
            skipSpace();
            if (text.startsWith(token, pos)) {
                pos += token.length();
                return true;
            }
            return false;
        }

        private void skipSpace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }
    }

    /** Kleene 三值 AND：FALSE 短路吃掉 UNKNOWN（{@code 假 && 未知} 仍是假）。 */
    static Verdict and(Verdict a, Verdict b) {
        if (a.isFalse() || b.isFalse()) {
            return Verdict.FALSE;
        }
        return a == Verdict.UNKNOWN || b == Verdict.UNKNOWN ? Verdict.UNKNOWN : Verdict.TRUE;
    }

    /** Kleene 三值 OR：TRUE 短路吃掉 UNKNOWN。 */
    static Verdict or(Verdict a, Verdict b) {
        if (a.isTrue() || b.isTrue()) {
            return Verdict.TRUE;
        }
        return a == Verdict.UNKNOWN || b == Verdict.UNKNOWN ? Verdict.UNKNOWN : Verdict.FALSE;
    }

    static Verdict not(Verdict a) {
        return a == Verdict.UNKNOWN ? Verdict.UNKNOWN : (a.isTrue() ? Verdict.FALSE : Verdict.TRUE);
    }
}
