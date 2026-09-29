package dev.vkdisp.pack.properties;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * 【参考调研】属性文件条件编译预处理（OF/Iris shaders.properties 的 #if 族）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = OptiFine 官方文档 shaders.properties 的 #ifdef / #ifndef / #if / #else / #endif 语义
 *    （事实性信息，不受版权保护）；Iris 同语义（LGPL-3.0，只读事实）。零代码复制。
 *    → 能否并入本项目（MIT）：可以（仅含格式事实）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：OF 用基于选项宏的条件编译做整段包含/剔除；本预处理在「已知已定义宏集合」上求值，
 *    把不满足条件的分支整段去掉。shaders.properties 不提供 #define / #include（见 18-PARALLEL 交接 §6.2），
 *    遇到即显式报错（T11），绝不静默吞掉。
 * 2. 备选：无。
 * 3. 我们的差异点：宏集合由调用方（主线/解析层）提供；A 线自身不发现宏（宏发现属 C 线）。
 * 4. 许可证核对结论：本项目 MIT；本文件零第三方代码。
 * 5. 性能基线：❄️ 冷路径（加载/重载时跑一次），不做任何性能优化（18-PARALLEL §7.7）。
 */
final class ConditionalPreprocessor {

    private ConditionalPreprocessor() {
    }

    /** 在给定「已定义宏」集合上求值条件编译，返回生效的内容行（已剔除注释/空行/未命中分支）。 */
    static List<String> preprocess(List<String> lines, Set<String> definedMacros) {
        Set<String> macros = definedMacros == null ? Set.of() : definedMacros;
        List<String> out = new ArrayList<>();
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(true, true));
        int index = 0;
        for (String raw : lines) {
            index++;
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                if (line.startsWith("#ifdef ")) {
                    pushFrame(stack, macros.contains(line.substring("#ifdef ".length()).strip()));
                } else if (line.startsWith("#ifndef ")) {
                    pushFrame(stack, !macros.contains(line.substring("#ifndef ".length()).strip()));
                } else if (line.startsWith("#if ")) {
                    pushFrame(stack, BoolExpr.eval(line.substring("#if ".length()).strip(), macros));
                } else if (line.equals("#else")) {
                    if (stack.size() <= 1) {
                        throw new IllegalArgumentException("vkdisp: #else 没有匹配的 #if（行 " + index + "）");
                    }
                    Frame f = stack.pop();
                    stack.push(new Frame(f.condition(), !f.include()));
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

    private static void pushFrame(Deque<Frame> stack, boolean condition) {
        Frame parent = stack.peek();
        stack.push(new Frame(condition, parent.include() && condition));
    }

    private record Frame(boolean condition, boolean include) {
    }

    /** 极简布尔表达式求值：标识符 + && || ! + ()，用于 #if 表达式（如 (BLOOM || SSAO) && !RAIN）。 */
    private static final class BoolExpr {
        static boolean eval(String expr, Set<String> macros) {
            List<String> toks = tokenize(expr);
            int[] pos = {0};
            boolean v = parseOr(toks, pos, macros);
            if (pos[0] != toks.size()) {
                throw new IllegalArgumentException("vkdisp: #if 表达式有多余符号：" + expr);
            }
            return v;
        }

        private static List<String> tokenize(String expr) {
            List<String> toks = new ArrayList<>();
            int i = 0;
            while (i < expr.length()) {
                char c = expr.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (c == '&' && i + 1 < expr.length() && expr.charAt(i + 1) == '&') {
                    toks.add("&&");
                    i += 2;
                } else if (c == '|' && i + 1 < expr.length() && expr.charAt(i + 1) == '|') {
                    toks.add("||");
                    i += 2;
                } else if (c == '(' || c == ')' || c == '!') {
                    toks.add(String.valueOf(c));
                    i++;
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

        private static boolean parseOr(List<String> t, int[] p, Set<String> m) {
            boolean v = parseAnd(t, p, m);
            while (p[0] < t.size() && t.get(p[0]).equals("||")) {
                p[0]++;
                v = v || parseAnd(t, p, m);
            }
            return v;
        }

        private static boolean parseAnd(List<String> t, int[] p, Set<String> m) {
            boolean v = parseNot(t, p, m);
            while (p[0] < t.size() && t.get(p[0]).equals("&&")) {
                p[0]++;
                v = v && parseNot(t, p, m);
            }
            return v;
        }

        private static boolean parseNot(List<String> t, int[] p, Set<String> m) {
            if (p[0] < t.size() && t.get(p[0]).equals("!")) {
                p[0]++;
                return !parseNot(t, p, m);
            }
            if (p[0] < t.size() && t.get(p[0]).equals("(")) {
                p[0]++;
                boolean v = parseOr(t, p, m);
                if (p[0] >= t.size() || !t.get(p[0]).equals(")")) {
                    throw new IllegalArgumentException("vkdisp: #if 表达式括号不匹配");
                }
                p[0]++;
                return v;
            }
            if (p[0] >= t.size()) {
                throw new IllegalArgumentException("vkdisp: #if 表达式不完整");
            }
            String id = t.get(p[0]++);
            if (id.equals(")")) {
                throw new IllegalArgumentException("vkdisp: #if 表达式语法错误（多余 ')'）");
            }
            return m.contains(id);
        }
    }
}
