package dev.vkdisp.pack;

import dev.vkdisp.pack.model.BlendMode;
import dev.vkdisp.pack.properties.ShaderProperties;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 包声明的 {@code blend} 表（加载期按包发布、渲染期按「正在画的包」查）。
 *
 * <p>为什么要一个小 holder 而不是把表顺着参数传下去：{@code FrameApi} 在
 * {@code ClassLineRatchetTest} 的巨类 CAPS 名单里（上限 1675），而 {@code runPostPass} 的参数列
 * 已经排到十个 ⇒ 再穿一层参数就得先从别处省出一行。把「包的声明」做成加载期发布、渲染期查的
 * 只读表，是把消费方接起来的**最小改动面**。
 *
 * <p>🔴 <b>为什么必须按包分表</b>（2026-10-17 真机实测）：{@code ShaderPackService#load} 对
 * <b>扫描到的每一张包</b>各跑一次。整表替换的写法等于「谁最后加载谁说了算」—— 实测到的症状是
 * 链已经按 {@code composite5} 派生了，表却是另一张包的，逐缓冲那一格静默丢掉（日志一切正常）。
 * 这是 GAP-018/045 那一族「缓存/表没有包这一维」的第六次。现在每张包写自己那一格，
 * 消费方拿 {@link ActivePack#get()} 查，包名不在表里 = 没有声明（宁可没有混合，
 * 也不许拿别的包的声明画这一帧）。
 *
 * <p>不变量：{@link #publish} 只在加载/重载线程调用；渲染线程只读
 * {@link #forProgram} / {@link #forTarget}，读到的是整张不可变快照。
 * 没有全局清表入口 —— 「没有包」由 {@link ActivePack#get()} 为空表达；整批抹掉只会在
 * 后台预编译兜底时误伤正在画的包（一百九十六抓到的那一口，别再长回来）。
 */
public final class PackBlendDirectives {

    /** 一张包的两张表：整程序形态、逐缓冲形态（{@code 程序 → (colortex 槽 → 混合)}），加上读不懂的行。 */
    private record Tables(Map<String, BlendMode> whole, Map<String, Map<Integer, BlendMode>> targets,
            List<String> dropped) {

        private static final Tables NONE = new Tables(Map.of(), Map.of(), List.of());
    }

    /** 表里最多记几张包：超了整表作废 —— 旧格本可被下次加载重写，这条只防无界增长。 */
    private static final int PACK_CAP = 32;

    private static final AtomicReference<Map<String, Tables>> BY_PACK =
            new AtomicReference<>(Map.of());

    private PackBlendDirectives() {
    }

    /**
     * 发布<b>这张包</b>的声明（{@code properties} 为 {@code null} = 这张包没有配置，按空表记）。
     *
     * <p>只动这张包自己那一格：别的包稍后加载不影响这里，正在画的包也永远不会读到别人的表。
     */
    public static void publish(String packName, ShaderProperties properties) {
        String pack = packName == null ? "" : packName;
        java.util.LinkedHashMap<String, Tables> next = new java.util.LinkedHashMap<>(BY_PACK.get());
        next.put(pack, properties == null ? Tables.NONE
                // blendTargets() 出来就是两层都不可变的（ShaderProperties#deepCopyTargets 负责内层）。
                : new Tables(Map.copyOf(properties.blends()), properties.blendTargets(),
                        List.copyOf(properties.blendDropped())));
        if (next.size() > PACK_CAP) {
            next.clear();
        }
        BY_PACK.set(Map.copyOf(next));
    }

    /** 表里现在记了几张包（自报用）。 */
    public static int packs() {
        return BY_PACK.get().size();
    }

    /**
     * 该程序声明的整程序形态混合（{@code blend.<程序>}）。
     *
     * <p>包可以写裸名也可以写带维度的限定名，而渲染侧手里的形态不一定与包写的一致
     * ⇒ 三种拼法都试（见 {@link #pick}）。命中不到返回空 —— 那表示「这个程序没有混合声明」，
     * 与「声明了 {@code off}」是两件事。
     *
     * @param pack 正在画的包（{@link ActivePack#get()}）；它没在表里 = 一条都不给
     */
    public static Optional<BlendMode> forProgram(String pack, String program) {
        return pick(tables(pack).whole(), program);
    }

    /**
     * 该程序对<b>某一个 colortex 槽</b>声明的混合：逐缓冲形态 {@code blend.<程序>.<缓冲>} 优先，
     * 没有才退到整程序形态 —— Iris 的优先级（包可以整程序给一种、再单独把某一格改掉，
     * Complementary 的 35 条里 33 条就是这一形）。
     */
    public static Optional<BlendMode> forTarget(String pack, String program, int slot) {
        Tables tables = tables(pack);
        Optional<BlendMode> perBuffer = pick(tables.targets(), program)
                .flatMap(slots -> Optional.ofNullable(slots.get(slot)));
        return perBuffer.isPresent() ? perBuffer : pick(tables.whole(), program);
    }

    /** 这张包有没有声明任何混合（两种形态都算）。 */
    public static boolean isEmpty(String pack) {
        Tables tables = tables(pack);
        return tables.whole().isEmpty() && tables.targets().isEmpty();
    }

    /** 这张包声明了几条整程序混合（日志与判据用）。 */
    public static int declared(String pack) {
        return tables(pack).whole().size();
    }

    /** 这张包声明了几条逐缓冲混合（{@code blend.<程序>.<缓冲>}）。 */
    public static int declaredTargets(String pack) {
        int total = 0;
        for (Map<Integer, BlendMode> slots : tables(pack).targets().values()) {
            total += slots.size();
        }
        return total;
    }

    /** 这张包读不成形、被丢弃的 {@code blend.} 行（逐条带原因）。 */
    public static List<String> dropped(String pack) {
        return tables(pack).dropped();
    }

    private static Tables tables(String pack) {
        return pack == null ? Tables.NONE : BY_PACK.get().getOrDefault(pack, Tables.NONE);
    }

    /** 三种拼法都试的那段查表逻辑，整程序与逐缓冲两张表共用（X9：不写两遍）。 */
    private static <V> Optional<V> pick(Map<String, V> table, String program) {
        if (program == null || program.isEmpty()) {
            return Optional.empty();
        }
        V direct = table.get(program);
        if (direct != null) {
            return Optional.of(direct);
        }
        int slash = program.lastIndexOf('/');
        if (slash >= 0) {
            return Optional.ofNullable(table.get(program.substring(slash + 1)));
        }
        for (Map.Entry<String, V> entry : table.entrySet()) {
            if (entry.getKey().endsWith("/" + program)) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }
}
