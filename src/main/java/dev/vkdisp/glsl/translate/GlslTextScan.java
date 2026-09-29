package dev.vkdisp.glsl.translate;

import java.util.ArrayList;
import java.util.List;

import dev.vkdisp.glsl.TranslateDiagnostic;

/**
 * 【参考调研】D 线二期共用文本扫描辅助 / GLSL 公开词法事实 + 04-SPEC §3.2 / §3.3
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/04-SPEC.md §3.2（OF 内建 uniform 语义）、§3.3（attribute/varying 老式语法）、
 *    docs/08-TESTING.md §4、docs/18-PARALLEL.md §4 D 线（独占路径 / 完成标准 / 证据）与 §7（硬边界、
 *    证据规范）—— 仓库内文档事实，不受版权保护；另加 GLSL 官方词法公开事实（标识符字符集、
 *    括号配对、双引号字符串、以反斜杠续行的预处理指令、注释先于词法被移除）。
 *    外部候选 IrisShaders/glsl-transformer（GPL-3.0 + 例外条款）与 glsl-preprocessor（同）→
 *    18-PARALLEL §4 D 线明示「按禁止处理」，例外条款是否覆盖本项目未核实，一律不读其代码、
 *    零代码行并入（07-CONSTRAINTS L12 §1.3 陷阱 2 / X21、17-NATIVE §1.1.1）。
 *    许可证：本文件为独立编写的纯 Java 辅助类，不含任何外部项目代码。
 *    → 能否并入本项目（MIT）：可以 —— 只采纳不受版权保护的事实性信息（词法规则、行号从 1 起）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：GLSL 词法公开规则 —— 标识符 = 字母/下划线开头 + 字母数字下划线；函数调用形如
 *    name ( args )，参数可跨行；注释与字符串在词法分析前被移除；预处理器指令以 # 开头，
 *    以行尾反斜杠续行。一期 CommentState「注释替换为等长空格」的做法在本类被沿用并扩展到字符串，
 *    使「无注释无字符串视图」与原始行下标一一对应（改原始行的同一区间）。
 * 2. 备选：无 —— 不引入 ANTLR / JavaCC / glslang，不建 AST；本类只提供行内扫描原语
 *    （冷路径清晰优先，18-PARALLEL §7.7）。
 * 3. 我们的差异点：① 所有返回「视图」的方法都保证与输入**等长同下标**（注释与字符串都替换为空格），
 *    因此调用方可以放心用视图里的下标去改原始行，行数不变、C 线的行号映射不被切断；
 *    ② 括号配对与续行判定都只看**单行**（跨行调用由调用方显式降级并出诊断，不静默截断）；
 *    ③ 预处理指令行与其续行统一由 {@link #preprocessorSkipLines} 标记，避免把 #define 宏体
 *    当成普通代码改写（一期只跳过 # 行本身，二期把续行也纳入，口径一致且更安全）。
 * 4. 许可证核对结论：本项目 MIT；GPL-3.0+例外参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径（包加载期一次）；逐字符线性扫描，无缓存、无预编译表、无性能优化
 *    （18-PARALLEL §7.7、T14 达标即停）。
 */
/**
 * 行内扫描原语（包内可见）：标识符读取、空白跳过、括号配对、注释/字符串等长抹除、
 * 预处理指令行（含续行）标记、文件头部区定位。
 *
 * <p><b>等长同下标不变量</b>：{@link #blankStrings(String)} 与
 * {@link CommentState#stripComments(String, int)} 返回的字符串与输入**等长**，
 * 只是把注释 / 字符串内容替换成空格。调用方因此可以把视图里找到的
 * {@code [start, end)} 区间直接用于改原始行（缩进 / 行尾 / 行数都不变）。
 *
 * <p>所有方法都不接受 {@code null}（调用方在进入本类之前已按 F3 语义完成 null 归一）。
 */
final class GlslTextScan {

    private GlslTextScan() {}

    /** 标识符首字符：{@code _} 或字母（GLSL 公开词法事实）。 */
    static boolean isIdentifierStart(char c) {
        return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** 标识符后续字符：首字符集 + 数字。 */
    static boolean isIdentifierPart(char c) {
        return isIdentifierStart(c) || (c >= '0' && c <= '9');
    }

    /** 跳过空格与制表符（注释 / 字符串已在视图中变成空格）。 */
    static int skipWhitespace(String code, int from) {
        int pos = Math.max(0, from);
        while (pos < code.length() && (code.charAt(pos) == ' ' || code.charAt(pos) == '\t')) {
            pos++;
        }
        return pos;
    }

    /**
     * 读取从 {@code from} 开始的标识符。
     *
     * @return {@code [start, end)} 二元组；当前位置不是标识符起始（或越界）时返回 {@code null}
     */
    static int[] identifierAt(String code, int from) {
        int pos = Math.max(0, from);
        if (pos >= code.length() || !isIdentifierStart(code.charAt(pos))) {
            return null;
        }
        int end = pos + 1;
        while (end < code.length() && isIdentifierPart(code.charAt(end))) {
            end++;
        }
        return new int[] {pos, end};
    }

    /**
     * {@code index} 处是否是「一个完整标识符的开头」：前一个字符不是标识符字符、也不是成员访问点。
     *
     * <p>用于避免把 {@code xgl_FragColor} / {@code obj.texture2D} 这类更长标识符或成员名
     * 误判成内建 / 旧函数名。
     */
    static boolean atTokenStart(String code, int index) {
        if (index <= 0) {
            return true;
        }
        char previous = code.charAt(index - 1);
        return !isIdentifierPart(previous) && previous != '.';
    }

    /**
     * 把双引号字符串（含转义）替换为等长空格，返回等长视图。
     *
     * <p>GLSL 里字符串只出现在预处理器指令（{@code #error} / {@code #pragma} 等）中，
     * 而本线不碰 {@code #} 行；这里仍然显式抹除，是为了让「注释内 / 字符串内不改写」
     * 这条边界在文本级转译器上是可断言的性质，而不是靠「语法上不该出现」的假设。
     * 未闭合的引号按「到行尾都是字符串」处理（不抛异常）。
     */
    static String blankStrings(String line) {
        char[] view = line.toCharArray();
        int pos = 0;
        while (pos < view.length) {
            if (view[pos] != '"') {
                pos++;
                continue;
            }
            int end = pos + 1;
            while (end < view.length) {
                char current = view[end];
                if (current == '\\') {
                    end += 2;
                    continue;
                }
                end++;
                if (current == '"') {
                    break;
                }
            }
            if (end > view.length) {
                end = view.length;
            }
            for (int index = pos; index < end; index++) {
                view[index] = ' ';
            }
            pos = end;
        }
        return new String(view);
    }

    /**
     * {@code code.charAt(openIndex) == '('} 时返回与之配对的 {@code ')'} 下标。
     *
     * <p>只在本行内查找：调用跨行时返回 {@code -1}，由调用方显式降级并出诊断（不静默截断）。
     *
     * @return 配对右括号下标；未配对 / 起点不是左括号时返回 {@code -1}
     */
    static int matchCloseParen(String code, int openIndex) {
        if (openIndex < 0 || openIndex >= code.length() || code.charAt(openIndex) != '(') {
            return -1;
        }
        int depth = 0;
        for (int pos = openIndex; pos < code.length(); pos++) {
            char current = code.charAt(pos);
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth == 0) {
                    return pos;
                }
            }
        }
        return -1;
    }

    /**
     * 逐行标记「应跳过的预处理指令行」：{@code #} 开头的行**及其反斜杠续行**。
     *
     * <p>与一期口径的关系：一期只跳过 {@code #} 行本身；二期把续行一并跳过，
     * 避免 {@code #define X \} 的宏体被当成普通代码改写。
     *
     * @param rawLines  原始行（不含换行符）
     * @param codeLines 与 {@code rawLines} 等长的无注释视图（用于判断是否 {@code #} 行）
     * @return 与行数等长的标记数组，{@code true} = 本行属于被跳过的预处理指令
     */
    static boolean[] preprocessorSkipLines(List<String> rawLines, List<String> codeLines) {
        boolean[] skip = new boolean[rawLines.size()];
        boolean continuation = false;
        for (int index = 0; index < rawLines.size(); index++) {
            boolean hashLine = codeLines.get(index).strip().startsWith("#");
            skip[index] = continuation || hashLine;
            continuation = (continuation || hashLine) && rawLines.get(index).endsWith("\\");
        }
        return skip;
    }

    /**
     * 逐行构造「无注释无字符串视图」（与原始行等长同下标）。
     *
     * <p>块注释跨行状态由 {@link CommentState} 跟踪；文件结束时仍未闭合 →
     * 在诊断列表头部补一条 ERROR（本线不静默，T11）。各变换类各自调用本方法，
     * 因此每个类单独使用也具备完整的边界诊断能力（与一期各类的独立可测口径一致）。
     *
     * @param rawLines    原始行（不含换行符）
     * @param diagnostics 诊断收集列表（未闭合块注释的 ERROR 插到最前）
     * @return 与 {@code rawLines} 等长的视图列表
     */
    static List<String> codeViews(List<String> rawLines, List<TranslateDiagnostic> diagnostics) {
        CommentState comments = new CommentState();
        List<String> codes = new ArrayList<>(rawLines.size());
        for (int index = 0; index < rawLines.size(); index++) {
            codes.add(blankStrings(comments.stripComments(rawLines.get(index), index + 1)));
        }
        if (comments.inBlockComment()) {
            diagnostics.add(0, TranslateDiagnostic.error("块注释未闭合（起始于第 "
                    + comments.blockCommentStartLine() + " 行）", null, comments.blockCommentStartLine()));
        }
        return codes;
    }

    /**
     * 文件头部区的结束下标（0 基，等于「其之前有这些行」）：跳过空行、注释行、字符串行与
     * {@code #} 预处理指令，停在首条代码行。没有代码行时返回行数（调用方据此跳过插入）。
     *
     * <p>与 04-SPEC §3.3 / GLSL 硬性要求一致：注入的全局声明必须落在 {@code #version} /
     * {@code #extension} 之后、首条代码之前。
     */
    static int headerEnd(List<String> sourceLines) {
        CommentState comments = new CommentState();
        int index = 0;
        while (index < sourceLines.size()) {
            String code = blankStrings(comments.stripComments(sourceLines.get(index), index + 1));
            String trimmed = code.strip();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                break;
            }
            index++;
        }
        return index;
    }
}
