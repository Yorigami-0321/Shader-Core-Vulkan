package dev.vkdisp.pack.model;

import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【参考调研】什么算程序名、什么不算（移植件）
 * 0. 合规核对（第 0 步闸门）：
 *    上游 = Vitrail {@code dev/vitrail/pack/model/ProgramNames.java:1-268} @ 4a45f44…（LGPL-3.0，
 *    读取日期 2026-10-17）；Vitrail {@code NOTICE} 未把该文件记为源自 Iris ⇒ 台账只记 Vitrail 一条。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以，进 {@code NOTICE.md} 移植台账（§〇 P1）。
 *    → 例外条款：无。
 * 1. 官方/主实现：OF/Iris 的程序族与 {@code _a} 计算后缀约定；{@link #shadowGeometry} 那条
 *    {@code dh_shadow} 边由上游点名 Iris {@code IrisRenderingPipeline.java:1366-1368}。
 * 3. 我们的差异点：包名/缩进 + 本注释块；{@code NUMBERED}/{@code SIMPLE}/{@code CHAINED}/
 *    {@code MAX_SLOT}/{@code COMPUTE_SUFFIX} 四张表与两个 rank 序**逐字保留**
 *    （它们与本仓 {@code pack/ProgramStage}、{@code pipeline/model/GbufferProgramPlan} 的自有名单
 *    并存到接线轮，接线时自有名单让位给这张 —— 一个程序族不许有两张表）。
 * 4. 许可证核对结论：本项目 LGPL-3.0-or-later；本文件为 Vitrail 移植件，改动见上。
 * 5. 性能基线：❄️ 冷路径（装配期）。
 *
 * <p>{@code gbuffers_} 名单是**刻意闭合**的。取证原型接受前缀后的任何东西，那在「数文件」时没问题，
 * 但引擎必须知道自己被塞了什么：这张表之外的名字没有「该从哪儿画出去」的位置，
 * 而静默忽略它会让包看起来坏了、日志里却什么都没有。⇒ 这里拒收的一律要**报出来**，不是丢掉。
 */
public final class ProgramNames {

    /**
     * 具名程序恰好就是参与兜底树的那些，所以名单只住一处。
     * 在这里留第二份拷贝，就是两张表漂移、最终某个程序「认得但放不进」或反过来的方式。
     */
    private static Set<String> named() {
        return ProgramFallbacks.names();
    }

    /**
     * 可带一个可选编号的族，如 {@code composite3}。setup 与 begin 也在这里，无论听起来多「单个」：
     * 包可以写 {@code setup1}..{@code setup99} 并期望它们按序运行。
     */
    private static final Set<String> NUMBERED = Set.of(
            "composite", "deferred", "prepare", "shadowcomp", "setup", "begin");

    /** 自立的族。 */
    private static final Set<String> SIMPLE = Set.of("shadow", "final");

    /**
     * 计算文件可以挂靠其上的全屏 pass 族，即链条会画的每一族。setup 是被排除的那一个：
     * Iris 在加载时跑它一次，而本引擎还没有这样一个时刻。
     */
    private static final Set<String> CHAINED = Set.of("begin", "prepare", "deferred", "composite",
            "final");

    /** 格式允许编号族的最大槽位。 */
    private static final int MAX_SLOT = 99;

    /**
     * 下划线加一个字母标记「挂在前一个程序上的计算 pass」，如 {@code composite21_a.csh}。
     * 下划线是必需的：没有它，任何名字恰好以字母结尾的程序都会被读成挂在某个更短名字上的计算 pass。
     */
    private static final Pattern COMPUTE_SUFFIX = Pattern.compile("^(.*?)_([a-z])$");

    /** 世界被画出来的那个时刻，其余一切都被排在它前面或后面。 */
    public static final int GEOMETRY_RANK = 3;

    private ProgramNames() {
    }

    /**
     * 一帧按什么顺序跑这些族 —— 这与它们的指令被折叠进来的顺序**不同**。
     * 放在这里而不是放在两个读者之一旁边，理由一样：调度与计划若对「某 stage 从哪儿开始」意见不合，
     * 不会报错，只会一端读到一半、另一端写在别处。
     */
    public static int frameRank(String family) {
        if (geometry(family)) {
            return GEOMETRY_RANK;
        }

        return switch (family) {
            case "begin" -> 0;
            case "shadowcomp" -> 1;
            case "prepare" -> 2;
            case "deferred" -> 4;
            case "composite" -> 5;
            default -> 6;
        };
    }

    /**
     * 两个**裸**程序名在一帧里的先后：先比族的 rank，再比族内槽位，
     * 于是 {@code composite2} 在 {@code composite10} 前、{@code prepare} 在 {@code prepare1} 前。
     * 解析不成任何程序的名字，排在所有能解析的之后。
     *
     * <p>这是 {@code ProgramSet.sorted} 排布条目的顺序，也是 Iris 填充某 stage 数组的顺序，
     * 因而它就是「帧的顺序」。留在这里的理由同 {@link #frameRank}，而读者更多：计划、调度、链
     * 三者都要给「自己那个程序什么都不画的计算」定位，两个回答不同并不报错，
     * 只是一次发生在错误时刻的 dispatch 读到了还没人写的那一半。
     *
     * <p>计算字母在这里不起作用：{@code prepare_a} 与 {@code prepare} 同族同槽，
     * 所以两种写法都能递进来。
     */
    public static Comparator<String> frameOrder() {
        return Comparator
                .comparingInt((String program) -> frameRank(familyOf(program)))
                .thenComparingInt(program -> parse(program)
                        .map(ProgramName::slot)
                        .orElse(Integer.MAX_VALUE));
    }

    /**
     * 一个程序的时刻是否早于另一个 —— 即把 {@link #frameOrder} 问在两个名字上而不是排一列。
     */
    public static boolean before(String program, String other) {
        return frameOrder().compare(program, other) < 0;
    }

    /**
     * Iris 折叠指令的顺序，出自 {@code ProgramSet.locateDirectives}。setup 程序不在其中：
     * 它们在那里进的是计算列表，且只被读 work group size ⇒ 无论怎么写都不声明格式。
     *
     * <p>与 {@link #frameRank} 比邻而居而非与它自己的任何一个读者比邻，同一理由：
     * 两个读者对「指令在哪儿折叠」意见不合不报错，只在一处给错答案。
     */
    public static int directiveRank(String family) {
        return switch (family) {
            case "shadowcomp" -> 0;
            case "begin" -> 1;
            case "prepare" -> 2;
            case "deferred" -> 4;
            case "composite" -> 5;
            default -> 3;
        };
    }

    /** 画在世界之上而不是画在一个 quad 上的 pass，它从不翻转任何东西。 */
    public static boolean geometry(String family) {
        return family.startsWith("gbuffers") || family.startsWith("dh_")
                || shadowGeometry(family);
    }

    /** 对 shadow 目标的一次全屏 pass —— 那些目标不是「某个地方」的颜色目标。 */
    public static boolean shadowComposite(String family) {
        return family.equals("shadowcomp");
    }

    /**
     * 从光而不是从相机画出的几何：它的 draw buffers 写 {@code shadowcolor}，从不写 {@code colortex}。
     *
     * <p>它存在的唯一理由是：写在那儿的数字索引的是**另一组**目标。一个 shadow 程序被当成颜色程序读，
     * 就会分配某人的 colortex、声称它被写过，并把 shadow map 的 albedo 送进那个索引对链条意味着的东西。
     *
     * <p>🔴 <strong>{@code dh_shadow} 是其中之一，而它不以 {@code shadow} 开头。</strong>
     * 那是从光画出的 Distant Horizons 几何，Iris 把它和其余一起送进 shadow framebuffer
     * （{@code IrisRenderingPipeline.java:1366-1368}），而只匹配前缀的名单会放它过去。
     * Bliss 带了一个，{@code world0/dh_shadow.fsh}，上面没有任何指令：被读成颜色程序就分配了一个没人写的 colortex0。
     */
    public static boolean shadowGeometry(String family) {
        return family.equals("shadow") || family.startsWith("shadow_") || family.equals("dh_shadow");
    }

    /** 程序所属的族：{@code composite4} ⇒ {@code composite}。 */
    public static String familyOf(String baseName) {
        return parse(baseName).map(ProgramName::family).orElse(baseName);
    }

    /**
     * 计算文件挂靠其上的那个全屏 pass：{@code deferred4_a} 与 {@code deferred4} 都得到
     * {@code deferred4} —— Iris 先读无字母的文件、再读带字母的，遇到第一个缺失的字母即停
     * （{@code ProgramSet.readComputeArray}），全都挂在同一个 pass 上。
     * 挂在本引擎不作为 pass 运行的文件返回 empty，setup 在此列。
     * pass 本身跑不跑、字母是否接得上前一个，是计划的说法。
     *
     * @param file 计算文件的去扩展名之名，它自有一个名字
     */
    public static Optional<String> computeBase(String file) {
        return parse(file)
                .filter(name -> CHAINED.contains(name.family()))
                .map(ProgramName::baseName);
    }

    /** 给一个 pass 的计算排序的那个字母：无字母的文件在前，然后 {@code a} 在 {@code b} 前。 */
    public static String computeLetter(String file) {
        return parse(file).map(ProgramName::computeSuffix).orElse("");
    }

    public static Optional<ProgramName> parse(String baseName) {
        if (named().contains(baseName) || SIMPLE.contains(baseName)) {
            return Optional.of(new ProgramName(baseName, -1, null));
        }

        Optional<ProgramName> numbered = parseNumbered(baseName);
        if (numbered.isPresent()) {
            return numbered;
        }

        // 只在普通形态都失败之后：计算 pass 是「挂着一个字母」写的，而那个词本身必须是合法名字。
        Matcher compute = COMPUTE_SUFFIX.matcher(baseName);
        if (compute.matches()) {
            String stem = compute.group(1);
            if (SIMPLE.contains(stem)) {
                return Optional.of(new ProgramName(stem, -1, compute.group(2)));
            }

            Optional<ProgramName> stemNumbered = parseNumbered(stem);
            if (stemNumbered.isPresent()) {
                ProgramName base = stemNumbered.get();
                return Optional.of(new ProgramName(base.family(), base.slot(), compute.group(2)));
            }
        }

        return Optional.empty();
    }

    private static Optional<ProgramName> parseNumbered(String baseName) {
        for (String family : NUMBERED) {
            if (!baseName.startsWith(family)) {
                continue;
            }

            String tail = baseName.substring(family.length());
            if (tail.isEmpty()) {
                return Optional.of(new ProgramName(family, 0, null));
            }

            if (tail.length() > 2 || !tail.chars().allMatch(Character::isDigit)) {
                continue;
            }

            int slot = Integer.parseInt(tail);
            if (slot <= MAX_SLOT) {
                return Optional.of(new ProgramName(family, slot, null));
            }
        }

        return Optional.empty();
    }

    /**
     * 程序的身份：族、编号族的槽位、以及标记「挂在其上的计算 pass」的那个字母。
     */
    public record ProgramName(String family, int slot, String computeSuffix) {

        public boolean isCompute() {
            return this.computeSuffix != null;
        }

        public String baseName() {
            return this.slot < 0 ? this.family : this.family + (this.slot == 0 ? "" : Integer.toString(this.slot));
        }
    }
}
