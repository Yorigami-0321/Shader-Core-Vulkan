package dev.vkdisp.glsl.preprocess;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.vkdisp.glsl.SourceLineMap;
import dev.vkdisp.glsl.TranslateDiagnostic;

/**
 * 【参考调研】C 线 — 选项常量识别器
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = OptiFine / Iris 官方文档"选项如何定义"（handover §6.3）：
 *    const int shadowMapResolution = 2048; // [512 1024 2048 4096] 与
 *    #define OPTION 0.10 // 描述 [0.05 0.10] —— 属于格式事实，不受版权保护。
 *    外部候选 IrisShaders/glsl-preprocessor（GPL-3.0 + 例外条款）→ 一律按禁止处理
 *    （handover §2.1 / 07-CONSTRAINTS X20/X21），本任务不读其代码、零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 类，不含任何外部项目代码。
 *    → 能否并入本项目（MIT）：可以（仅采纳不受版权保护的事实性选项语法）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：handover §6.3 的选项定义语法 + §5.3④（白名单 const 才可见，名单外默认不可见）
 *    + F3 的 SourceLineMap 契约（识别出的选项要能指回原文件行）。
 * 2. 备选：把所有 const / #define 都当选项 —— 会污染选项模型（handover §6.3 明确"名单外 const
 *    默认不可见"），故严格按白名单 + 候选值列表识别。
 * 3. 我们的差异点：识别结果以 OptionConstant 列表形式返回（供 C 线内部校验 / 上游选项模型消费），
 *    不修改文本（文本透传），行号经传入的 lineMap 指回原文件。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径；单次线性扫描 + 白名单 HashSet，清晰优先、无缓存、无性能优化
 *    （18-PARALLEL §7.7、T14 达标即停）。
 */
/**
 * 扫描 GLSL 源码，识别"选项常量"：白名单 {@code const} 与带候选值列表的 {@code #define} 选项宏。
 *
 * <p>识别规则（handover §6.3 / §5.3④）：
 * <ul>
 *   <li>白名单 {@code const}：如 {@code const int shadowMapResolution = 2048; // [512 1024 2048]}
 *       —— 仅白名单内的名字才视为可见选项，名单外默认不可见；</li>
 *   <li>选项宏：{@code #define OPTION 0.10 // 描述 [0.05 0.10]} —— 必须带 {@code [候选值]} 列表
 *       才被识别为选项（无候选值列表的 {@code #define} 视为普通宏，不识别）；</li>
 *   <li>同名选项默认值不一致 → 判为歧义并<b>禁用</b>，同时发 WARN 级诊断（T11：降级必须显式可见）。</li>
 * </ul>
 *
 * <p>本处理器<b>不修改</b>文本，文本原样透传；行号经 {@code inputLineMap} 指回原文件。
 */
public final class ConstEvaluator {

    /** 可见的 const 选项白名单（handover §6.3 列出的一部分；非白名单 const 默认不可见）。 */
    private static final java.util.Set<String> CONST_WHITELIST = java.util.Set.of(
            "shadowMapResolution",
            "shadowDistance",
            "sunPathRotation",
            "wetnessHalflife",
            "shadowInterval",
            "shadowSteps",
            "cloudTime",
            "ambientOcclusionLevel",
            "centerDepthSmooth",
            "noiseTextureResolution");

    private ConstEvaluator() {
    }

    /**
     * 「先挡后正则」前缀守卫的开关（{@code -Dvkdisp.const.guard=false} 关闭）。
     *
     * <p>保留开关不是为了留后门，而是为了**能交替测量**：开与关在同一个二进制里，
     * 才可以按 {@code 17-NATIVE.md} §7.3 的红线做交替对照（否则要靠两个不同版本的
     * 产物去比，时间点不同 ⇒ 机器状态不同 ⇒ 比值不可信）。
     *
     * <p>默认开启；关闭后行为与优化前**完全一致**（两条 pattern 的锚定保证了这一点）。
     */
    static final boolean PREFIX_GUARD =
            !"false".equalsIgnoreCase(System.getProperty("vkdisp.const.guard", "true"));

    /**
     * 单遍实现（不物化行数组、不为每行分配 strip 结果）的开关
     * （{@code -Dvkdisp.const.singlepass=false} 关闭）。
     *
     * <p>与 {@link #PREFIX_GUARD} 同理：留开关是为了**能交替测量**（§7.3 的红线），
     * 而不是留后门。关闭后走 {@link #evaluateSplit}（原实现），两者产物必须逐字节一致。
     */
    static final boolean SINGLE_PASS =
            !"false".equalsIgnoreCase(System.getProperty("vkdisp.const.singlepass", "true"));

    /**
     * 前缀守卫：只有以 {@code const} 或 {@code #define} 开头的行才可能命中
     * {@link #CONST_PATTERN} / {@link #DEFINE_PATTERN}（两者都有 {@code ^} 锚定）。
     *
     * <p>注意：这里用 {@code startsWith} 而不是「先试 {@code indexOf}」——
     * 前者的判定与正则的锚定条件**逐字对应**，因此跳过的一定是原本就要失配的行。
     */
    private static boolean guardAllows(String trimmed) {
        if (!PREFIX_GUARD) {
            return true;
        }
        return trimmed.startsWith("const") || trimmed.startsWith("#define");
    }

    /** 一条被识别出的选项常量的元信息。 */
    public record OptionConstant(
            String name,
            String kind,          // "const-int" / "const-float" / "const-bool" / "define-value"
            String defaultValue,
            List<String> candidates,
            String description,
            String sourceFile,
            int sourceLine,
            boolean visible,
            boolean disabled) {
    }

    /** 识别结果：透传文本 + 识别出的选项列表 + 诊断。 */
    public record Result(
            String text, List<OptionConstant> options, List<TranslateDiagnostic> diagnostics) {
    }

    /**
     * 扫描并识别选项常量。
     *
     * @param text          展开后、去指令前的全量文本（保留 #define 选项宏，便于发现选项）
     * @param inputLineMap  include 阶段的行号映射，用于把选项指回原文件行
     */
    public static Result evaluate(String text, SourceLineMap inputLineMap) {
        return SINGLE_PASS
                ? evaluateSinglePass(text, inputLineMap)
                : evaluateSplit(text, inputLineMap);
    }

    /**
     * 原实现：{@code split("\n", -1)} 物化整份行数组 + 每行 {@code strip()}。
     *
     * <p>保留它是为了 A/B 交替测量（{@code -Dvkdisp.const.singlepass=false} 走这里），
     * 以及在单遍实现出问题时有一个可逐字节对照的参照。
     */
    private static Result evaluateSplit(String text, SourceLineMap inputLineMap) {
        List<OptionConstant> options = new ArrayList<>();
        List<TranslateDiagnostic> diagnostics = new ArrayList<>();
        Map<String, List<Integer>> byName = new HashMap<>();

        String[] rawLines = text.split("\n", -1);
        int count = rawLines.length;
        if (count > 0 && rawLines[count - 1].isEmpty()) {
            count--;
        }

        for (int idx = 0; idx < count; idx++) {
            int outputLineNo = idx + 1;
            String line = rawLines[idx];
            String trimmed = line.strip();
            OptionConstant oc = null;
            // 「先挡后正则」：两条 pattern 都以 ^const / ^#define 锚定（见文件末尾），
            // 所以不满足前缀的行**不可能**命中 ⇒ 跳过是**可证明等价**的，不是有损优化。
            // originOf 只在命中时才需要：它越界返回 UNKNOWN_LINE、永不抛（SourceLineMap 契约），
            // 因此推迟调用不改变任何异常语义。X27：绝不以砍 pack 特性换性能。
            if (guardAllows(trimmed)) {
                SourceLineMap.LineOrigin origin = inputLineMap.originOf(outputLineNo);
                oc = tryConst(trimmed, origin);
                if (oc == null) {
                    oc = tryDefineOption(trimmed, origin);
                }
            }
            if (oc != null) {
                options.add(oc);
                byName.computeIfAbsent(oc.name(), k -> new ArrayList<>()).add(options.size() - 1);
            }
        }
        return finish(text, options, byName, diagnostics);
    }

    /**
     * 单遍实现（P3）：**不物化行数组，也不为每行分配 strip 结果**。
     *
     * <p>动机来自 JFR（见 {@code evidence/p1-const-prefix-guard.md}）：const 相里
     * {@code split} 17.2% + {@code strip} 18.0% ≈ **35%** 花在字符串分配上，
     * 而真正的正则只占一小部分、且守卫已经把它挡到 65k 行以内。
     * 也就是说：**剩下的成本主要是「把 17MB 文本切成几十万个临时 String」**。
     *
     * <p>做法：
     * <ol>
     *   <li>按 {@code indexOf('\n')} 在原文上滚动行区间，<b>不</b>为每行生成子串；</li>
     *   <li>先用下标扫出首个/末个非空白位（复刻 {@code String.strip()} 的
     *       {@code Character.isWhitespace} 语义），<b>不</b>分配；</li>
     *   <li>只有前缀检查通过的行才 {@code substring} 一次、把真串交给正则。</li>
     * </ol>
     *
     * <p>行数口径与 {@code split("\n", -1)} 后「丢掉一个末尾空串」**逐字一致**：
     * 文本以 {@code '\n'} 结尾时，{@code split} 产生的最后一个空串被丢掉 ⇒ 单遍实现
     * 直接把最后一个换行符排除在扫描区间外。空文本两者的行数都是 0。
     */
    private static Result evaluateSinglePass(String text, SourceLineMap inputLineMap) {
        List<OptionConstant> options = new ArrayList<>();
        List<TranslateDiagnostic> diagnostics = new ArrayList<>();
        Map<String, List<Integer>> byName = new HashMap<>();

        final int len = text.length();
        // split 对空串产出 [""]，再被「丢一个末尾空串」抹掉 ⇒ 0 行。
        if (len == 0) {
            return finish(text, options, byName, diagnostics);
        }
        // 文本以 '\n' 结尾 ⇒ 末段是「纯换行终止符产物」，与 split 后被丢掉的那个空串对应。
        final int limit = text.charAt(len - 1) == '\n' ? len - 1 : len;

        int start = 0;
        int outputLineNo = 0;
        while (start <= limit) {
            int nl = text.indexOf('\n', start);
            int end = (nl < 0 || nl > limit) ? limit : nl;

            // 不分配地求出 strip() 的等价区间
            int ws = start;
            while (ws < end && Character.isWhitespace(text.charAt(ws))) {
                ws++;
            }
            int we = end;
            while (we > ws && Character.isWhitespace(text.charAt(we - 1))) {
                we--;
            }
            outputLineNo++;
            if (ws < we && prefixAllows(text, ws, we)) {
                String trimmed = text.substring(ws, we);
                SourceLineMap.LineOrigin origin = inputLineMap.originOf(outputLineNo);
                OptionConstant oc = tryConst(trimmed, origin);
                if (oc == null) {
                    oc = tryDefineOption(trimmed, origin);
                }
                if (oc != null) {
                    options.add(oc);
                    byName.computeIfAbsent(oc.name(), k -> new ArrayList<>()).add(options.size() - 1);
                }
            }

            if (nl < 0 || nl > limit) {
                break;
            }
            start = nl + 1;
        }
        return finish(text, options, byName, diagnostics);
    }

    /**
     * 前缀检查的**无分配**版本：与 {@link #guardAllows(String)} 判的是同一件事，
     * 只是改成在给定区间上比对字符，避免为了判断而先造出子串。
     */
    private static boolean prefixAllows(String text, int from, int to) {
        return startsWithAt(text, from, to, "const") || startsWithAt(text, from, to, "#define");
    }

    private static boolean startsWithAt(String text, int from, int to, String prefix) {
        int n = prefix.length();
        if (to - from < n) {
            return false;
        }
        for (int k = 0; k < n; k++) {
            if (text.charAt(from + k) != prefix.charAt(k)) {
                return false;
            }
        }
        return true;
    }

    /** 同名默认值不一致 → 歧义禁用 + WARN（两条实现共用，保证口径一致）。 */
    private static Result finish(String text, List<OptionConstant> options,
            Map<String, List<Integer>> byName, List<TranslateDiagnostic> diagnostics) {

        // 同名默认值不一致 → 歧义禁用 + WARN
        // 注意：byName 里存的是 options 的"另一份引用"，而 options 最终会被 copyOf 冻结，
        // 所以冲突时必须在 options 这一份上替换为 disabled 版本，否则返回的列表仍是 enabled。
        List<OptionConstant> resolved = new ArrayList<>(options);
        for (Map.Entry<String, List<Integer>> e : byName.entrySet()) {
            List<Integer> idxs = e.getValue();
            if (idxs.size() > 1) {
                String first = options.get(idxs.get(0)).defaultValue();
                boolean conflict = idxs.stream()
                        .anyMatch(i -> !options.get(i).defaultValue().equals(first));
                if (conflict) {
                    diagnostics.add(TranslateDiagnostic.warn(
                            "选项 " + e.getKey() + " 默认值不一致，已禁用（歧义）"));
                    for (int i : idxs) {
                        OptionConstant o = resolved.get(i);
                        resolved.set(i, new OptionConstant(o.name(), o.kind(), o.defaultValue(),
                                o.candidates(), o.description(), o.sourceFile(), o.sourceLine(),
                                o.visible(), true));
                    }
                }
            }
        }

        return new Result(text, List.copyOf(resolved), List.copyOf(diagnostics));
    }

    private static OptionConstant tryConst(String trimmed, SourceLineMap.LineOrigin origin) {
        Matcher m = CONST_PATTERN.matcher(trimmed);
        if (!m.find()) {
            return null;
        }
        String type = m.group(1);
        String name = m.group(2);
        String value = m.group(3).strip();
        if (!CONST_WHITELIST.contains(name)) {
            return null; // 名单外 const 默认不可见
        }
        String comment = extractComment(trimmed);
        List<String> candidates = parseCandidates(comment);
        String description = parseDescription(comment);
        String kind = switch (type) {
            case "float" -> "const-float";
            case "bool" -> "const-bool";
            default -> "const-int";
        };
        return new OptionConstant(name, kind, value, candidates, description,
                origin.sourceFile(), origin.sourceLine(), true, false);
    }

    private static OptionConstant tryDefineOption(String trimmed, SourceLineMap.LineOrigin origin) {
        Matcher m = DEFINE_PATTERN.matcher(trimmed);
        if (!m.find()) {
            return null;
        }
        String name = m.group(1);
        String value = m.group(2).strip();
        String comment = extractComment(trimmed);
        List<String> candidates = parseCandidates(comment);
        if (candidates.isEmpty()) {
            return null; // 无候选值列表的 #define 不视为选项
        }
        String description = parseDescription(comment);
        return new OptionConstant(name, "define-value", value, candidates, description,
                origin.sourceFile(), origin.sourceLine(), true, false);
    }

    private static String extractComment(String line) {
        int idx = line.indexOf("//");
        return idx >= 0 ? line.substring(idx + 2) : "";
    }

    private static List<String> parseCandidates(String comment) {
        List<String> out = new ArrayList<>();
        Matcher m = CANDIDATE_PATTERN.matcher(comment);
        if (m.find()) {
            for (String piece : m.group(1).split("\\s+")) {
                if (!piece.isEmpty()) {
                    out.add(piece);
                }
            }
        }
        return List.copyOf(out);
    }

    private static String parseDescription(String comment) {
        int bracket = comment.indexOf('[');
        String desc = bracket >= 0 ? comment.substring(0, bracket) : comment;
        return desc.strip();
    }

    private static final Pattern CONST_PATTERN =
            Pattern.compile("^const\\s+(int|float|bool|double)\\s+([A-Za-z_]\\w*)\\s*=\\s*([^;]+);");
    private static final Pattern DEFINE_PATTERN =
            Pattern.compile("^#define\\s+([A-Za-z_]\\w*)\\s+(\\S+)");
    private static final Pattern CANDIDATE_PATTERN = Pattern.compile("\\[\\s*(.*?)\\s*\\]");
}
