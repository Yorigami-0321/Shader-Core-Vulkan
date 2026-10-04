package dev.vkdisp.glsl.translate;
/**
 * 【参考调研】OF DRAWBUFFERS 槽位映射 → Vulkan layout(location) / GLSL 官方公开语义
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① GLSL 官方公开语义：gl_FragData[n] 是 GLSL 1.20 及以前的片元内建输出数组，
 *    它与「location n」**没有必然关系** —— 槽位由引擎按包的 DRAWBUFFERS 注释兑现；
 *    ② 本仓库 glsl.translate 其余 8 段的形态（纯文本变换 + 诊断列表 + 行号契约），同为 MIT 自有代码。
 *    **不搬运任何第三方着色器文本，不搬运 Mojang 源码。**
 *    另参考本仓库 docs/13-GAP-REGISTRY.md 的 GAP-003 行与 evidence/h06（BSL 实测口径）。
 *    → 能否并入本项目（MIT）：可以（语义是公开事实，实现为本项目自研）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：原版 GlslCompiler 走 core profile，只认 layout(location = N) out vec4；
 *    「DRAWBUFFERS 是注释里的约定、引擎必须自己兑现」这件事**没有任何官方实现可抄** —— 原版没有包加载器。
 * 2. 备选：① 在 FragmentOutputAdapter 里直接查 DRAWBUFFERS 改目标槽位 —— 否决：
 *    该类 javadoc 已把「gl_FragData[n] ≡ location n」写成既定前提，改它会牵动既有 6 条管线与
 *    全部相关单测的口径；② 渲染侧按附件下标硬绑 —— 否决：**那正是本段要消灭的静默绑错槽**。
 * 3. 我们的差异点：做成**独立的第 7.5 段**，等行数变换（只改已有声明行里的 location 数字），
 *    因此行号映射完全不受影响（同 ①–④ 与 ⑥ 段口径）；且**只认预处理后仍存活的那一条标记** ——
 *    死分支里的标记已被 #if 求值消掉，所以「当前配置下活着的分支」是**结构上唯一**的，
 *    不需要猜，也不需要包选项知识。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：冷路径（每阶段一次线性扫描），不做优化（18-PARALLEL §7.7、T14）。
 */
import dev.vkdisp.glsl.TranslateDiagnostic;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 gl_FragData[k] 的输出槽位从「下标 k」改成包用 DRAWBUFFERS 声明的 colortex 编号 ——
 * 即把 ⑦ 段合成的 {@code layout(location = k) out vec4 vkdispFragOutK;} 改写成
 * {@code layout(location = DRAWBUFFERS[k]) out vec4 vkdispFragOutK;}。
 *
 * <p><b>为什么必须做</b>（本轮消灭的静默 bug，h06 预言在此成真）：GLSL 里
 * gl_FragData[n] **不等于** location n。OF 引擎按包源码里的 DRAWBUFFERS:0367 把第 k 个输出写进
 * <b>colortex 3/6/7</b>；原样按下标绑定会把材质写进 colortex1、法线写进 colortex2 ——
 * <b>画面看着「有内容」，但每个通道都是错的</b>，没有任何一行日志会抱怨。
 *
 * <p><b>实测锚点</b>（BSL v10.1.8 program/gbuffers_terrain.glsl 424/428/432/439 行）：
 * <pre>
 *   DRAWBUFFERS:0     gl_FragData[0]        → colortex0
 *   DRAWBUFFERS:08    + gl_FragData[1]      → colortex8
 *   DRAWBUFFERS:0367  + gl_FragData[1..3]   → colortex3 / 6 / 7
 *   DRAWBUFFERS:08367 + gl_FragData[2..4]   → colortex3 / 6 / 7
 * </pre>
 * ⇒ 开高级材质后需要 <b>8 个附件</b>（最大下标 7）；同时开 MCBL_SS 则要 <b>9 个</b>（下标 8）
 * ⇒ <b>超出 Vulkan 的 maxColorAttachments = 8</b>，必须**显式失败**而不是静默夹取。
 *
 * <p><b>三条不做的事</b>（X9 / T11）：多条标记并存（歧义）→ ERROR 且不改写；
 * 槽位表含重复 → ERROR；需要超过 {@link #MAX_SUPPORTED_SLOTS} 个附件 → ERROR 且不改写。
 * 任何一种情况下 {@link Result#failure()} 非空，调用方据此**拒绝接线**（沿用原版 core/terrain），
 * 绝不「夹一夹继续画」—— 那正是静默绑错槽的同义词。
 */
public final class DrawBuffersSlotAdapter {

    /** 本段能兑现的最大附件数 = 原版 ColorTargetState.MAX_COLOR_TARGETS（8）。 */
    public static final int MAX_SUPPORTED_SLOTS = 8;

    /** 标记名（OF 老式注释约定）。 */
    public static final String MARKER = "DRAWBUFFERS";

    private static final Pattern BLOCK_MARKER =
            Pattern.compile("/\\*\\s*" + MARKER + ":\\s*([0-9]+)\\s*\\*/");
    private static final Pattern LINE_MARKER =
            Pattern.compile("//\\s*" + MARKER + ":\\s*([0-9]+)");
    private static final Pattern OUT_DECL =
            Pattern.compile("^layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*out\\b.*;\\s*$");

    /**
     * 适配结果。
     *
     * @param text        改写后文本；未改写时与输入逐字节相同
     * @param diagnostics 诊断（行号 = 本段输入行号；sourceFile 为 null，由入口回填）
     * @param slots       活着的标记给出的槽位表（长度 = gl_FragData 槽位数）；无标记时为空
     * @param remapped    是否真的改写了至少一条声明
     * @param failure     非空 = 本段**拒绝**改写（歧义 / 重复 / 超上限 / 未覆盖的输出），调用方不得接线
     */
    public record Result(String text, List<TranslateDiagnostic> diagnostics, List<Integer> slots,
            boolean remapped, String failure) {

        public Result {
            text = text == null ? "" : text;
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            slots = slots == null ? List.of() : List.copyOf(slots);
        }

        /** 本段是否**兑现了**槽位语义（可用于接线）。 */
        public boolean honored() {
            return failure == null;
        }
    }

    private DrawBuffersSlotAdapter() {}

    /**
     * 按活着的 DRAWBUFFERS 标记改写片元输出声明的 location。
     *
     * <p>非片元阶段、或源里没有 gl_FragData 的，一律**原样返回**（零诊断、零改写）——
     * 对那两种情况「没有标记」是正常状态，不是缺陷。
     */
    public static Result apply(ShaderStage stage, String source) {
        String input = source == null ? "" : source;
        List<TranslateDiagnostic> diagnostics = new ArrayList<>();
        if (stage != ShaderStage.FRAGMENT) {
            return new Result(input, diagnostics, List.of(), false, null);
        }

        // 🔖 **能力口径 vs 生产口径的判别（本段最要紧的一条守卫）**：
        //   转译终稿里 #if 族指令数为 **0**（实测 BSL 三个维度目录的 trans 终稿）。
        //   若仍看得见条件指令，说明喂进来的是**未经预处理求值的原始切片** ——
        //   那时多条 DRAWBUFFERS 分支同时在场（0 / 08 / 08367 / 0367），
        //   累积合并出来的表**必然自相矛盾**（实测得 [0,3,6,7,7]，下标 4 与 3 抢 colortex7）。
        //   ⇒ 此时**不做改写**，按恒等 + INFO 处理（这正是「能力上限」口径，
        //     与 h06/h07 区分能力/生产两套数字的做法一致）；
        //   ⇒ 生产链路上（本段唯一真实输入）则永远只有已求值后的那几条，判定成立。
        if (hasLiveConditionals(input)) {
            diagnostics.add(TranslateDiagnostic.info(
                    "源里仍有未求值的条件编译指令 ⇒ " + MARKER
                            + " 多分支同时在场，按能力口径不改写槽位（生产链路上不会有此情形）",
                    null, 0));
            return new Result(input, diagnostics, List.of(), false, null);
        }

        // 🔖 2026-10-04 实测更正：**标记是累积的，不是单条的**。
        //   BSL 原始源码里同一函数内有两条：/* DRAWBUFFERS:0 */（管 gl_FragData[0]）
        //   与 /* DRAWBUFFERS:0367 */（管 gl_FragData[1..3]）。
        //   OF 语义 = 「一条标记管辖其后、下一条标记之前的那些 gl_FragData 下标」
        //   ⇒ 索引 k 的最终映射来自**最后一条 len > k 的标记**。
        //   （实测结构：0 → 0367 / 0 → 08 → 08367，两条标记的前缀都自洽。）
        List<Integer> slots = effectiveSlots(input);
        if (slots.isEmpty()) {
            // 没有标记 = 这个包不用 DRAWBUFFERS（按下标绑定即可）⇒ 恒等、零诊断。
            return new Result(input, diagnostics, List.of(), false, null);
        }
        Set<Integer> distinct = new LinkedHashSet<>(slots);
        if (distinct.size() != slots.size()) {
            return refuse(input, diagnostics,
                    MARKER + " 槽位表含重复项 " + slots + "（同一 colortex 被两个输出抢占）");
        }
        int maxSlot = slots.stream().mapToInt(Integer::intValue).max().orElse(-1);
        if (maxSlot + 1 > MAX_SUPPORTED_SLOTS) {
            return refuse(input, diagnostics,
                    MARKER + " 需要 " + (maxSlot + 1) + " 个附件（最大下标 " + maxSlot
                            + "），超过 Vulkan maxColorAttachments 上限 " + MAX_SUPPORTED_SLOTS
                            + " ⇒ 本引擎无法兑现该包的槽位语义");
        }

        SourceLines lines = SourceLines.of(input);
        List<String> raw = lines.lines();
        // 声明判定走**代码视图**（无注释无字符串），否则注释里的同形文本会骗到；
        // 标记扫描走**原始行**（标记本身就在注释里）—— 两条路必须分开。
        List<String> code = GlslTextScan.codeViews(raw, diagnostics);
        Map<Integer, Integer> lineOfLocation = new LinkedHashMap<>();
        for (int i = 0; i < raw.size(); i++) {
            Matcher decl = OUT_DECL.matcher(code.get(i).trim());
            if (decl.find()) {
                lineOfLocation.putIfAbsent(Integer.parseInt(decl.group(1)), i);
            }
        }
        if (lineOfLocation.isEmpty()) {
            diagnostics.add(TranslateDiagnostic.info(
                    MARKER + " 标记存在但本段没有可改写的片元输出声明（该文件不产出颜色附件），按恒等处理",
                    null, 0));
            return new Result(input, diagnostics, slots, false, null);
        }
        for (int location : lineOfLocation.keySet()) {
            if (location >= slots.size()) {
                return refuse(input, diagnostics,
                        "存在 location=" + location + " 的片元输出，但 " + MARKER
                                + " 槽位表只有 " + slots.size() + " 项（" + slots
                                + "）⇒ 无法确定它该写哪个 colortex");
            }
        }

        boolean changed = false;
        List<String> rewritten = new ArrayList<>(raw);
        for (int k = 0; k < slots.size(); k++) {
            int target = slots.get(k);
            Integer lineIndex = lineOfLocation.get(k);
            if (lineIndex == null || target == k) {
                continue;
            }
            String before = raw.get(lineIndex);
            String after = before.replaceFirst("(location\\s*=\\s*)" + k + "\\b", "$1" + target);
            if (!after.equals(before)) {
                rewritten.set(lineIndex, after);
                changed = true;
            }
        }
        // 🔖 只在**真的改了东西**时出 INFO：恒等映射（如 DRAWBUFFERS:0）出诊断纯属噪音，
        //   还会破坏「第二遍翻译零新增诊断」的幂等断言（OfGlslTranslatorBuiltinsTest）。
        if (changed) {
            diagnostics.add(TranslateDiagnostic.info(
                    "按 " + MARKER + ":" + join(slots) + " 改写片元输出槽位："
                            + slots.size() + " 个输出 → " + (maxSlot + 1) + " 个颜色附件", null, 0));
        }
        return new Result(SourceLines.join(rewritten, lines.endsWithNewline()),
                diagnostics, slots, changed, null);
    }

    /**
     * 取**活着的那一条**标记。
     *
     * <p>为什么「活着的是唯一一条」是结构保证而不是运气：候选标记分别位于
     * {@code #ifdef MCBL_SS} / {@code #if defined ADVANCED_MATERIALS && …} 的分支里，
     * 预处理求值后**只留下当前配置真正走的那一条**（实测：BSL 默认配置下转译终稿里只剩
     * {@code /* DRAWBUFFERS:0 *}{@code /} 一条）。⇒ 仍见到 0 条或 >1 条都是异常，显式拒绝。
     */
    private static List<Integer> effectiveSlots(String input) {
        List<Integer> effective = new ArrayList<>();
        for (String rawLine : input.split("\\R")) {
            String digits = null;
            Matcher block = BLOCK_MARKER.matcher(rawLine);
            if (block.find()) {
                digits = block.group(1);
            } else {
                Matcher line = LINE_MARKER.matcher(rawLine);
                if (line.find()) {
                    digits = line.group(1);
                }
            }
            if (digits == null) {
                continue;
            }
            List<Integer> table = digits(digits);
            // 累积：这条标记管辖它能覆盖的全部下标（0..len-1），后出现的覆盖先出现的。
            for (int k = 0; k < table.size(); k++) {
                while (effective.size() <= k) {
                    effective.add(-1);
                }
                effective.set(k, table.get(k));
            }
        }
        for (int k = 0; k < effective.size(); k++) {
            if (effective.get(k) < 0) {
                return List.of(); // 有洞（例如只见过 08367 却没见过覆盖下标 0 的标记）⇒ 不猜
            }
        }
        return effective.isEmpty() ? List.of() : List.copyOf(effective);
    }

    /** 源里是否仍看得见未求值的 {@code #if} 族指令（只看行首，且走代码视图排除注释）。 */
    private static boolean hasLiveConditionals(String input) {
        List<String> raw = SourceLines.of(input).lines();
        List<String> code = GlslTextScan.codeViews(raw, new ArrayList<>());
        for (String line : code) {
            String trimmed = line.strip();
            if (trimmed.startsWith("#if") || trimmed.startsWith("#ifdef")
                    || trimmed.startsWith("#ifndef") || trimmed.startsWith("#else")
                    || trimmed.startsWith("#elif") || trimmed.startsWith("#endif")) {
                return true;
            }
        }
        return false;
    }

    /** 标记字符串 → 槽位表（逐字符一位；非数字显式抛，不静默）。 */
    private static List<Integer> digits(String text) {
        List<Integer> slots = new ArrayList<>(text.length());
        for (char c : text.toCharArray()) {
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException(
                        "vkdisp: " + MARKER + " 标记含非数字字符 '" + c + "'（不猜，X9）");
            }
            slots.add(c - '0');
        }
        return List.copyOf(slots);
    }

    /** 统一拒绝出口：文本原样返回（不改写），并给一条 ERROR + 非空 failure。 */
    private static Result refuse(String input, List<TranslateDiagnostic> diagnostics, String reason) {
        diagnostics.add(TranslateDiagnostic.error(
                MARKER + " 槽位映射无法兑现：" + reason + " ⇒ 片元输出保持原状，不接线（X9/T11）",
                null, 0));
        return new Result(input, diagnostics, List.of(), false, reason);
    }

    private static String join(List<Integer> slots) {
        StringBuilder sb = new StringBuilder();
        for (Integer slot : slots) {
            sb.append(slot);
        }
        return sb.toString();
    }
}
