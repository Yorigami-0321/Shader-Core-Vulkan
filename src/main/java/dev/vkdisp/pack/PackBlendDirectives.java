package dev.vkdisp.pack;

import dev.vkdisp.pack.model.BlendMode;
import dev.vkdisp.pack.model.ProgramNames;
import dev.vkdisp.pack.model.TargetName;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import dev.vkdisp.pack.properties.ShaderProperties;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 包声明的 {@code blend.<程序>} 表（加载期发布、渲染期查表）。
 *
 * <p>为什么要一个小 holder 而不是把表顺着参数传下去：{@code FrameApi} 在
 * {@code ClassLineRatchetTest} 的巨类 CAPS 名单里（上限 1675），而 {@code runPostPass} 的参数列
 * 已经排到十个 ⇒ 再穿一层参数就得先从别处省出一行。把「包的声明」做成加载期发布、渲染期查的
 * 只读表，是把消费方接起来的**最小改动面**；代价是它必须跟着包代次清，写在 {@link #clear()} 上。
 *
 * <p>不变量：{@link #publish} 与 {@link #clear} 只在加载/重载线程调用；渲染线程只读
 * {@link #forProgram}，读到的是整表替换后的快照（{@code volatile} 引用，不做增量改）。
 */
public final class PackBlendDirectives {

    /** 不可变快照 + 一个 final 引用：换表是整表替换（QD-03 管「静态非 final 字段」，这里没有那种字段）。 */
    private record Snapshot(Map<String, BlendMode> table, List<String> dropped) {

        private static final Snapshot EMPTY = new Snapshot(Map.of(), List.of());
    }

    private static final java.util.concurrent.atomic.AtomicReference<Snapshot> SNAPSHOT =
            new java.util.concurrent.atomic.AtomicReference<>(Snapshot.EMPTY);

    private PackBlendDirectives() {
    }

    /** 包加载/重载成功后发布本包的声明（{@code null} 按清空处理）。 */
    public static void publish(ShaderProperties properties) {
        if (properties == null) {
            clear();
            return;
        }
        SNAPSHOT.set(new Snapshot(Map.copyOf(properties.blends()),
                List.copyOf(properties.blendDropped())));
    }

    /** 换包 / 关包 / 加载失败都要走这里：宁可回到「没有混合声明」，不许拿上一包的表画这一包。 */
    public static void clear() {
        SNAPSHOT.set(Snapshot.EMPTY);
    }

    /**
     * 按程序名查它声明的混合。
     *
     * <p>包可以写裸名（{@code blend.gbuffers_water}）也可以写带维度的限定名
     * （{@code blend.world0/gbuffers_water}），而渲染侧手里的形态不一定与包写的一致
     * ⇒ 三种拼法都试：原样、去掉目录前缀后的裸名、把裸名补成限定名查不到就算了。
     * 命中不到返回空 —— 那表示「这个程序没有混合声明」，与「声明了 {@code off}」是两件事。
     */
    public static Optional<BlendMode> forProgram(String program) {
        if (program == null || program.isEmpty()) {
            return Optional.empty();
        }
        Map<String, BlendMode> snapshot = SNAPSHOT.get().table();
        BlendMode direct = snapshot.get(program);
        if (direct != null) {
            return Optional.of(direct);
        }
        int slash = program.lastIndexOf('/');
        if (slash >= 0) {
            BlendMode bare = snapshot.get(program.substring(slash + 1));
            return Optional.ofNullable(bare);
        }
        for (Map.Entry<String, BlendMode> entry : snapshot.entrySet()) {
            if (entry.getKey().endsWith("/" + program)) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    /** 本包有没有声明任何混合。 */
    public static boolean isEmpty() {
        return SNAPSHOT.get().table().isEmpty();
    }

    /**
     * 去重后的、属于后处理链程序（composite / deferred / final）的混合声明。
     *
     * <p>为什么要按族筛：post 槽的变体是「16 槽 × 每种混合」一条。给一个
     * {@code gbuffers_water} 的声明建 16 条管线，是白烧 16 次着色器编译，而且那条声明
     * 在这条路上根本走不到 —— gbuffers 各族的混合要等地形/实体路径走同一套派生通道，
     * 那是另一半，别把账记到这一半上（{@code PackFeatureCoverage} 的 blend 行同此口径）。
     */
    public static List<BlendMode> chainModes() {
        Set<BlendMode> distinct = new LinkedHashSet<>();
        for (Map.Entry<String, BlendMode> entry : SNAPSHOT.get().table().entrySet()) {
            String family = ProgramNames.familyOf(TargetName.bareName(entry.getKey()));
            if (family.equals("composite") || family.equals("deferred") || family.equals("final")) {
                distinct.add(entry.getValue());
            }
        }
        return new ArrayList<>(distinct);
    }

    /** 本包声明了几条混合（日志与判据用）。 */
    public static int declared() {
        return SNAPSHOT.get().table().size();
    }

    /** 读不成形、被丢弃的 {@code blend.} 行（逐条带原因）。 */
    public static List<String> dropped() {
        return SNAPSHOT.get().dropped();
    }
}
