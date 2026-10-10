package dev.vkdisp.glsl.preprocess;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.vkdisp.glsl.SourceLineMap;
import dev.vkdisp.glsl.TranslateDiagnostic;
import dev.vkdisp.glsl.translate.CommentState;

/**
 * 【参考调研】C 线 — #include 展开器
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = OptiFine / Iris 官方文档 #include 语义（handover §6.5：绝对路径前导 / → 相对
 *    shaders/ 顶层；相对路径 → 相对当前文件；最大嵌套深度 10；被包含文件可再包含；循环包含必须
 *    检测报错）—— 属于格式事实，不受版权保护。外部候选 IrisShaders/glsl-preprocessor
 *    （GPL-3.0 + 例外条款）→ 一律按禁止处理（handover §2.1 / 07-CONSTRAINTS X20/X21），
 *    本任务不读其代码、零代码行并入；只读其"要做循环检测、每文件每路径只处理一次、保留行号映射"
 *    这一事实性目标。许可证：本文件为独立编写的纯 Java 类，不含任何外部项目代码。
 *    → 能否并入本项目（MIT）：可以（仅采纳不受版权保护的事实性语义）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：handover §6.5 的 #include 语义 + F3 的 SourceLineMap 行号映射契约
 *    （docs/18-PARALLEL §4 C 线完成标准"行号映射保留：编译报错要能指回原文件"）。
 * 2. 备选：把 include 交给下游转译器做 —— 会丢失"未包含前的原文件坐标"，违背 F3 的
 *    lineMap 契约（C 线产出、D 线消费），故 C 线必须在此处展开并登记每行起源。
 * 3. 我们的差异点：循环检测用"当前 include 栈是否含该归一路径"（而非"同文件只处理一次"，
 *    因为不同路径可能指向同文件、同文件也可能被不同父路径多次包含且都合法），
 *    并在展开时逐行登记 (file, line) 起源。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径（包加载期一次性）；递归展开 + 逐行登记，清晰优先、无缓存、无性能优化
 *    （18-PARALLEL §7.7、T14 达标即停）。
 */
/**
 * 展开 GLSL 源码里的 {@code #include} 指令。
 *
 * <p>语义（以官方文档 §6.5 为准）：
 * <ul>
 *   <li>绝对路径：前导 {@code /} → 相对 {@code shaders/} 顶层，如 {@code #include "/bar"} →
 *       {@code shaders/bar}；</li>
 *   <li>相对路径：无前导 {@code /} → 相对<b>当前文件</b>目录；</li>
 *   <li>最大嵌套深度 {@value #MAX_DEPTH}；被包含文件可再包含；</li>
 *   <li><b>循环包含必须检测并报错</b>（ERROR 级，指回触发点原文件与行号）。</li>
 * </ul>
 *
 * <p>展开时对每一行输出登记起源 {@code (file, line)}，产出 {@link SourceLineMap}，
 * 后续 Define / Const 阶段可经 {@link SourceLineMap#compose(compat...)} 合成端到端映射。
 */
public final class IncludeProcessor {

    /** 最大嵌套深度（含文件自身不算，指 include 链最多 10 层）。 */
    public static final int MAX_DEPTH = 10;

    private static final Pattern INCLUDE_PATTERN =
            Pattern.compile("^#\\s*include\\s+[\"']([^\"']*)[\"']");

    private IncludeProcessor() {
    }

    /** 处理结果：展开文本 + 行号映射 + 诊断 + 是否成功（无 ERROR）。 */
    public record Result(
            String text, SourceLineMap lineMap, List<TranslateDiagnostic> diagnostics, boolean success) {
    }

    /**
     * 展开入口。
     *
     * @param primaryFile 顶层文件名（相对 shaders/ 顶层，如 {@code "composite.fsh"}）
     * @param source      顶层文件内容
     * @param resolver    文件来源（注入式，便于单测用内存 Map）
     */
    public static Result process(String primaryFile, String source, IncludeResolver resolver) {
        List<TranslateDiagnostic> diagnostics = new ArrayList<>();
        SourceLineMap.Builder builder = SourceLineMap.builder(primaryFile);
        StringBuilder out = new StringBuilder();
        Deque<String> stack = new ArrayDeque<>();
        Worker worker = new Worker(resolver, diagnostics, builder, out, stack);
        boolean ok = worker.expand(primaryFile, normalize(source), 0);
        boolean success = ok && diagnostics.stream()
                .noneMatch(d -> d.severity() == TranslateDiagnostic.Severity.ERROR);
        return new Result(out.toString(), builder.build(), List.copyOf(diagnostics), success);
    }

    /** 真正的递归展开逻辑（携带可变状态）。 */
    private static final class Worker {
        private final IncludeResolver resolver;
        private final List<TranslateDiagnostic> diagnostics;
        private final SourceLineMap.Builder builder;
        private final StringBuilder out;
        private final Deque<String> stack;

        Worker(IncludeResolver resolver, List<TranslateDiagnostic> diagnostics,
               SourceLineMap.Builder builder, StringBuilder out, Deque<String> stack) {
            this.resolver = resolver;
            this.diagnostics = diagnostics;
            this.builder = builder;
            this.out = out;
            this.stack = stack;
        }

        boolean expand(String currentFile, String source, int depth) {
            boolean ok = true;
            if (source.isEmpty()) {
                return true;
            }
            String[] lines = source.split("\n", -1);
            // 去掉 split 产生的"末尾空串"（纯换行终止符产物，不是真实行）
            int count = lines.length;
            if (count > 0 && lines[count - 1].isEmpty()) {
                count--;
            }
            // 🔖 A0：注释状态机复用 translate/CommentState（同一份实现，不造第二套判断逻辑 ——
            //    19 §2.2 病根 (a)；A1 后随 GlslTokens 收编）。每个文件一份实例：块注释跨行只在本文件内成立。
            CommentState comments = new CommentState();
            for (int i = 0; i < count; i++) {
                int lineNo = i + 1;
                String line = lines[i];
                String trimmed = line.strip();
                // 🔴 A0 止血（19 §2.6-A0）：先看**无注释视图**再判这行是不是指令。
                //    原文形如 #include 而视图里不是 ⇒ 它落在注释里。本阶**只补可见性**，
                //    展开行为与旧实现逐字一致（改语义属 A2 —— 那时由 jcpp 的注释状态机一次性做对，
                //    并带三路差分对表当门）。
                String codeView = comments.stripComments(line, lineNo).strip();
                Matcher m = INCLUDE_PATTERN.matcher(trimmed);
                boolean isDirective = m.find();
                if (isDirective && !INCLUDE_PATTERN.matcher(codeView).find()) {
                    diagnostics.add(TranslateDiagnostic.warn(
                            "形如 #include 的行位于注释内 ⇒ 本实现仍按真指令展开（这是错的，"
                                    + "但语义修复属 19 §2.6-A2；本行只为可见性而报）",
                            currentFile, lineNo));
                }
                if (isDirective) {
                    String includePath = m.group(1);
                    String resolved = resolvePath(includePath, currentFile);
                    if (resolved == null) {
                        diagnostics.add(TranslateDiagnostic.error(
                                "无法解析 #include 路径: " + includePath, currentFile, lineNo));
                        ok = false;
                        continue;
                    }
                    String content = resolver.read(resolved);
                    if (content == null) {
                        diagnostics.add(TranslateDiagnostic.error(
                                "包含文件不存在: " + resolved, currentFile, lineNo));
                        ok = false;
                        continue;
                    }
                    String normalized = normalizePath(resolved);
                    if (stack.contains(normalized)) {
                        List<String> chain = new ArrayList<>(stack);
                        chain.add(normalized);
                        diagnostics.add(TranslateDiagnostic.error(
                                "检测到循环包含: " + String.join(" -> ", chain),
                                currentFile, lineNo));
                        ok = false;
                        continue; // 不展开，跳过该 include 内容
                    }
                    if (depth + 1 > MAX_DEPTH) {
                        diagnostics.add(TranslateDiagnostic.error(
                                "包含嵌套深度超过上限 " + MAX_DEPTH + ": " + normalized,
                                currentFile, lineNo));
                        ok = false;
                        continue;
                    }
                    stack.push(normalized);
                    boolean sub = expand(normalized, normalize(content), depth + 1);
                    stack.pop();
                    if (!sub) {
                        ok = false;
                    }
                } else {
                    out.append(line).append('\n');
                    builder.add(currentFile, lineNo);
                }
            }
            return ok;
        }
    }

    /**
     * 把 include 路径解析为相对 shaders/ 顶层的 key。
     *
     * @return 归一后的相对路径；无法解析返回 {@code null}（理论不会，仅作防御）
     */
    static String resolvePath(String includePath, String currentFile) {
        String resolved;
        if (includePath.startsWith("/")) {
            resolved = includePath.substring(1);
        } else {
            int slash = currentFile.lastIndexOf('/');
            String dir = slash >= 0 ? currentFile.substring(0, slash) : "";
            resolved = dir.isEmpty() ? includePath : dir + "/" + includePath;
        }
        return normalizePath(resolved);
    }

    /** 路径归一：反斜杠转正斜杠、去 {@code ./}、解析 {@code ../}、折叠重复斜杠。 */
    static String normalizePath(String path) {
        String p = path.replace('\\', '/');
        String[] parts = p.split("/");
        java.util.Deque<String> stack = new ArrayDeque<>();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (!stack.isEmpty() && !stack.peekLast().equals("..")) {
                    stack.removeLast();
                } else {
                    stack.addLast("..");
                }
            } else {
                stack.addLast(part);
            }
        }
        return String.join("/", stack);
    }

    /** 换行归一：\r\n 与裸 \r 都转成 \n。 */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String n = text.replace("\r\n", "\n").replace('\r', '\n');
        if (!n.isEmpty() && n.endsWith("\n")) {
            n = n.substring(0, n.length() - 1);
        }
        return n;
    }
}
