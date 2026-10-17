package dev.vkdisp.pack.model;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * 【参考调研】颜色目标的名字别名与索引（移植件）
 * 0. 合规核对（第 0 步闸门）：
 *    上游 = Vitrail {@code dev/vitrail/pack/model/TargetName.java:1-137} @ 4a45f44…（LGPL-3.0，
 *    读取日期 2026-10-17）；别名表与 {@code colorimg} 的无别名形状由上游点名 Iris
 *    （{@code samplers/IrisImages.java:21}）⇒ 台账记 Vitrail + Iris 两条。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以，进 {@code NOTICE.md} 移植台账（§〇 P1）。
 * 1. 官方/主实现：OptiFine 的 legacy 别名（{@code gcolor}/{@code gdepth}/{@code gnormal}/
 *    {@code composite}/{@code gaux1..4}）与编号形态 {@code colortexN}；上限取 Iris 的 32。
 * 3. 我们的差异点：包名/缩进/文件头；表体逐字保留。🔴 接线动机 = 我方此前在
 *    {@code bridge/FrameApi} 里自己 parse {@code colortex} 后缀，并把**所有** {@code gaux*}
 *    一律绑到 colortex4（注释写的却是 {@code N+3}），且四个 legacy 名根本不认 ⇒ 按这张表改正。
 * 4. 许可证核对结论：本项目 LGPL-3.0-or-later；本文件为 Vitrail 移植件，其 Iris 来源已在台账注明。
 * 5. 性能基线：❄️ 冷路径（解析与绑定期）。
 *
 * <p>一个颜色目标应答哪些名字，以及名字背后的索引。另有 {@link #bareName}：它不是目标名，
 * 留在这里只因为除了这一处没人被调用。
 *
 * <p>编号格式之前的那八个名字**不是 folklore**：八个语料包里有五个通过其中之一采样，而且不在角落 ——
 * Mellow 在它那 127 个程序里全读 {@code gaux1}，Complementary 读 {@code gaux2} 与 {@code gaux4} 九十九次。
 * 同一个包会在同一个目标上自由混用两种拼写：BSL 声明 {@code gaux1Format} 却在别处采样 {@code colortex4}。
 * ⇒ 别名必须在**名字第一次被读到的地方**解开，而不是到最后再补。
 *
 * <p>有一对值得在脑子里分清，因为它俩只差一个字母、一个目标：{@code gdepth} 是 colortex1（颜色目标），
 * 而 {@code gdepthtex} 是 depthtex0，根本不是颜色目标。
 */
public final class TargetName {

    /** Iris 允许这么多，语料实际到 colortex19。十六是 OptiFine 的习惯，不是上限。 */
    public static final int MAX_TARGETS = 32;

    private static final String PREFIX = "colortex";

    /** 同一个目标在「被 compute 写入而不是被采样」时叫什么。 */
    private static final String IMAGE_PREFIX = "colorimg";

    /** 按位置：索引 0 是 {@code gcolor}，索引 7 是 {@code gaux4}，再往后没有。 */
    private static final List<String> LEGACY = List.of(
            "gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4");

    private TargetName() {
    }

    /** {@code colortex7} 与 {@code gaux4} 都答 7。 */
    public static OptionalInt index(String name) {
        int legacy = LEGACY.indexOf(name);
        if (legacy >= 0) {
            return OptionalInt.of(legacy);
        }

        if (!name.startsWith(PREFIX)) {
            return OptionalInt.empty();
        }

        String digits = name.substring(PREFIX.length());
        if (digits.isEmpty() || digits.length() > 2 || !digits.chars().allMatch(Character::isDigit)) {
            return OptionalInt.empty();
        }

        int index = Integer.parseInt(digits);

        return index < MAX_TARGETS ? OptionalInt.of(index) : OptionalInt.empty();
    }

    /**
     * 同一个目标在 compute <strong>写入</strong>它时用的名字：{@code colorimg7} 答 7。
     *
     * <p>这里不认任何别名，而那就是参考实现自己的形状：Iris 自己构造 image 名集合，
     * 每个目标一个，编号格式之前的八个名字一个都不在里面（{@code samplers/IrisImages.java:21}）
     * ⇒ 包写 {@code gaux4img} 的话，那儿同样没人绑它。两位数字与上限复用 {@link #index}，
     * 免得同一个目标的两种拼写出现一种读得进、一种被拒。
     */
    public static OptionalInt imageIndex(String name) {
        if (!name.startsWith(IMAGE_PREFIX)) {
            return OptionalInt.empty();
        }

        String digits = name.substring(IMAGE_PREFIX.length());
        if (digits.isEmpty() || digits.length() > 2 || !digits.chars().allMatch(Character::isDigit)) {
            return OptionalInt.empty();
        }

        int index = Integer.parseInt(digits);

        return index < MAX_TARGETS ? OptionalInt.of(index) : OptionalInt.empty();
    }

    /** 7 ⇒ {@code colortex7}。 */
    public static String canonical(int index) {
        return PREFIX + index;
    }

    /** 7 ⇒ {@code gaux4}；8 及以上为空。 */
    public static Optional<String> legacyAlias(int index) {
        return index >= 0 && index < LEGACY.size() ? Optional.of(LEGACY.get(index)) : Optional.empty();
    }

    /** {@code gaux2Format} 拆成索引 5 与后缀 {@code Format}。 */
    public static Optional<Suffixed> split(String directiveName) {
        if (directiveName.startsWith(PREFIX)) {
            int cursor = PREFIX.length();
            while (cursor < directiveName.length() && Character.isDigit(directiveName.charAt(cursor))) {
                cursor++;
            }

            OptionalInt index = index(directiveName.substring(0, cursor));
            if (index.isEmpty() || cursor == directiveName.length()) {
                return Optional.empty();
            }

            return Optional.of(new Suffixed(index.getAsInt(), directiveName.substring(cursor)));
        }

        for (int index = 0; index < LEGACY.size(); index++) {
            String alias = LEGACY.get(index);
            if (directiveName.startsWith(alias) && directiveName.length() > alias.length()) {
                return Optional.of(new Suffixed(index, directiveName.substring(alias.length())));
            }
        }

        return Optional.empty();
    }

    public record Suffixed(int index, String suffix) {
    }

    /**
     * 程序在这里是**按它自己**被命名的（{@code composite1}），而引擎其余部分按它住哪儿命名
     * （{@code world0/composite1}）。两种都收，免得任何一边去记对方用哪种形态。
     *
     * <p>这不是目标名（与本类其余部分不同）：它剥的是程序的目录，不是缓冲的别名。
     * 仍留在这里，是因为每个调用它的读者都在同一口气里读目标名 —— 把程序的写入与采样
     * 对上上面这些靶。
     */
    public static String bareName(String program) {
        int slash = program.lastIndexOf('/');

        return slash < 0 ? program : program.substring(slash + 1);
    }
}
