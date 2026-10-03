package dev.vkdisp.glsl;

import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * G2 · FFM 跨语言边界开销探针。
 *
 * <p><b>为什么需要它</b>：G1 三相移植完成后，端到端收益算出来是 26.6%–27.7%，
 * 看起来跨过了 {@code 17-NATIVE.md} §5.2 的 20% 阈值。<b>但那个数把 FFI 边界开销当成了 0。</b>
 * §5.1 的闸门顺序是 G0 → G1 → <b>G2（FFM 最小打通 demo）</b> → G3 → G4，
 * G2 从未做过 ⇒ 当前结论里最大的未知就是这一项。
 *
 * <p><b>测两种拿句柄的方式</b>（这是设计上必须选的一个）：① 每次重新取句柄
 * （每次都走 {@code lookup.find + linker.downcallHandle}）；② 缓存 {@code MethodHandle} 复用。
 * 两者可能差一个数量级 —— 本探针要把这个差值摆出来，让人据此做决定，而不是只报一个数。
 *
 * <p><b>🔴 不是 JUnit 测试</b>：微基准，有独立 {@code main} 入口单独跑。理由见
 * {@code 18-PARALLEL.md} §7.7「测试代码不进运行时」—— 微基准会拖慢整个单测套件，
 * 而且判定口径（n≥9、报 p95）与单测（断言通过/失败）根本不是一回事。
 *
 * <p><b>输出</b>：机器可读行 {@code G3DATA\tffi\t<场景>\t<每次调用纳秒>\t<样本数>}。
 */
public final class FfmBoundaryProbe {

    /** 一次跨界里传输的典型负载：单行 GLSL，约 40 字节（按条粒度的真实量级）。 */
    private static final int BUFFER_BYTES = 40;

    /** 每轮的调用次数。太少会被计时精度吃掉，太多会让单轮过长。 */
    private static final int CALLS_PER_ROUND = 1_000;

    /** 预热轮数（§7.1：预热 ≥3）。 */
    private static final int WARMUP_ROUNDS = 5;

    /** 样本轮数（§7.1：样本 ≥9）。 */
    private static final int SAMPLE_ROUNDS = 15;

    /** 按条：311,902 行 × 2 相（inc + def）= 623,804 次跨界 —— T18 明令禁止的粒度。 */
    private static final long CROSSINGS_PER_ITEM = 311_902L * 2L;

    /** 按批：182 阶段 × 约 3 相 = 550 次跨界 —— 设计上会选的粒度。 */
    private static final long CROSSINGS_PER_BATCH = 550L;

    /** G1 三相移植算出的端到端节省（ms），用来算边界开销吃掉它多少。 */
    private static final double G1_SAVING_MS = 248.0;

    /** 口径修正后的 G0 生产入口（ms），§5.2 端到端百分比的分母。 */
    private static final double G0_BASELINE_MS = 895.3;

    /** stderr 显式 UTF-8 —— 否则异常信息里的中文自己先乱码了。 */
    private static final PrintStream ERR =
            new PrintStream(System.err, true, StandardCharsets.UTF_8);

    /** 首次异常只报一次，避免热路径被日志 I/O 主导。 */
    private static final AtomicBoolean FIRST_FAULT = new AtomicBoolean(false);

    private FfmBoundaryProbe() {
    }

    public static void main(String[] args) throws Exception {
        // 控制台显式 UTF-8；且**参数只收 ASCII**（JVM 用 sun.jnu.encoding 解码 argv，
        // POSIX/C locale 下中文必成乱码 —— Round 1 踩过的坑）。
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);

        Path lib = resolveLibrary(args);
        out.printf("FFM 库: %s%n", lib);
        out.printf("JDK   : %s%n%n", System.getProperty("java.version"));

        try (Arena arena = Arena.ofConfined()) {
            SymbolLookup lookup = SymbolLookup.libraryLookup(lib.toAbsolutePath().toString(), arena);
            Linker linker = Linker.nativeLinker();

            byte[] payload = new byte[BUFFER_BYTES];
            Arrays.fill(payload, (byte) 'A');
            MemorySegment segment = arena.allocateFrom(ValueLayout.JAVA_BYTE, payload);

            // 缓存句柄：取一次、之后复用（正确做法）。
            MethodHandle noopCached = downcall(linker, lookup, "noop",
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG));
            MethodHandle sumCached = downcall(linker, lookup, "sum_len",
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

            Scenario[] scenarios = {
                    // 纯 Java 基线：同一循环、同一累加、不跨任何边界。
                    // 没有它就无法区分「跨界花了多少」与「循环 + 累加花了多少」。
                    new Scenario("java-baseline/noop", () -> javaBaseline(segment)),
                    // 差做法：每次调用都重新解析符号 + 重新生成句柄。
                    new Scenario("noop/fresh-handle",
                            () -> invoke(() -> (long) downcall(linker, lookup, "noop",
                                    FunctionDescriptor.of(ValueLayout.JAVA_LONG)).invokeExact())),
                    new Scenario("noop/cached-handle",
                            () -> invoke(() -> (long) noopCached.invokeExact())),
                    new Scenario("sum_len/fresh-handle",
                            () -> invoke(() -> (long) downcall(linker, lookup, "sum_len",
                                    FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG))
                                    .invokeExact(segment, (long) BUFFER_BYTES))),
                    new Scenario("sum_len/cached-handle",
                            () -> invoke(() -> (long) sumCached.invokeExact(
                                    segment, (long) BUFFER_BYTES))),
            };

            out.printf("%-24s %13s %12s %7s%n", "场景", "中位数(ns/次)", "p95(ns/次)", "样本");
            out.println("-".repeat(60));

            List<Result> raw = new ArrayList<>();
            for (Scenario s : scenarios) {
                Result r = measure(s);
                raw.add(r);
                out.printf("G3DATA\tffi\t%s\t%.1f\t%d%n", s.name, r.medianNs(), r.samples());
            }

            printBoundedSummary(out, raw);
            out.println();
            verifyPanicBoundary(out, linker, lookup);
        }
    }

    /** 定位 cdylib：优先用命令行给的路径，其次按约定路径找。 */
    private static Path resolveLibrary(String[] args) {
        if (args.length > 0) {
            return Path.of(args[0]);
        }
        Path candidate = Path.of("../g2-ffi-demo/target/release/libg2_ffi_demo.so");
        if (Files.isReadable(candidate)) {
            return candidate;
        }
        candidate = Path.of("/home/yorigami/Minecraft/g2-ffi-demo/target/release/libg2_ffi_demo.so");
        if (Files.isReadable(candidate)) {
            return candidate;
        }
        throw new IllegalStateException(
                "找不到 libg2_ffi_demo.so —— 先 cargo build --release，或用第一个参数显式指定");
    }

    /** 纯 Java 对照：取一次不可预测的值算一算，防止整段被常量折叠。 */
    private static long javaBaseline(Object escape) {
        return System.identityHashCode(escape);
    }

    /**
     * 解析符号并生成下行句柄。
     *
     * <p>单独抽出来，是因为<b>「每次重新取」与「缓存复用」的唯一差别就是这个调用
     * 放在循环内还是循环外</b>。两个场景必须共用同一份实现，否则测出来的差值里会混进
     * 「两份实现写得不一样」的噪声 —— 那这个数就没法用来做决定了。
     */
    private static MethodHandle downcall(Linker linker, SymbolLookup lookup,
            String symbol, FunctionDescriptor descriptor) {
        return linker.downcallHandle(lookup.find(symbol).orElseThrow(), descriptor);
    }

    /**
     * {@code MethodHandle.invokeExact} 声明 {@code throws Throwable}。
     *
     * <p>刻意不让异常成为热路径的控制流：异常构造会把「跨界开销」和「异常开销」
     * 混在一起，测出来的数就不纯了。所以异常统一收敛成返回值，
     * 并且<b>只在首见时</b>报一行（每 1000 次打一行的话，计时直接被 I/O 主导）。
     */
    private static long invoke(ThrowingCall call) {
        try {
            return call.invoke();
        } catch (Throwable t) {
            if (FIRST_FAULT.compareAndSet(false, true)) {
                ERR.println("[FfmBoundaryProbe] 跨界调用抛出异常（已收敛，不计入计时口径）: " + t);
            }
            return -1L;
        }
    }

    /**
     * 🔴 返回类型<b>必须是 {@code long}</b>，不能是 {@code Object}。
     *
     * <p>原因：{@code invokeExact} 是<b>签名多态</b>的 —— 编译器按<b>调用点的目标类型</b>
     * 决定生成哪种调用。若这里声明返回 {@code Object}，javac 会把调用点推断成 {@code ()Object}，
     * 而句柄的实际类型是 {@code ()long} ⇒ 每次调用都抛 {@code WrongMethodTypeException}。
     *
     * <p>这个坑很阴险：异常被 {@link #invoke} 吞掉后不会崩，只会<b>安静地把「异常开销」
     * 记成「跨界开销」</b> —— 实测里就出现过明显不对的数，是靠纯 Java 基线对照才发现的。
     */
    @FunctionalInterface
    private interface ThrowingCall {
        long invoke() throws Throwable;
    }

    /** 预热 + 多轮采样，报中位数与 p95（口径与 ColdPathBenchmark / g1-check 一致）。 */
    private static Result measure(Scenario scenario) {
        long sink = 0;
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            for (int c = 0; c < CALLS_PER_ROUND; c++) {
                sink += scenario.invoke();
            }
        }
        double[] perCall = new double[SAMPLE_ROUNDS];
        for (int i = 0; i < SAMPLE_ROUNDS; i++) {
            long t0 = System.nanoTime();
            for (int c = 0; c < CALLS_PER_ROUND; c++) {
                sink += scenario.invoke();
            }
            long elapsed = System.nanoTime() - t0;
            perCall[i] = (double) elapsed / CALLS_PER_ROUND;
        }
        // 防死代码消除：结果必须被用一次。
        if (sink == Long.MIN_VALUE) {
            System.err.println("unreachable " + sink);
        }
        double[] sorted = perCall.clone();
        Arrays.sort(sorted);
        double median = sorted[sorted.length / 2];
        // p95：下标 ceil(0.95×N)−1，与 ColdPathBenchmark / g1-check 同一口径
        int p95Index = (int) Math.ceil(0.95 * sorted.length) - 1;
        double p95 = sorted[Math.max(0, p95Index)];
        return new Result(scenario.name, median, p95, sorted.length, Double.NaN);
    }

    /**
     * 给<b>两个界</b>，不是一个数 —— 这决定结论怎么用。
     * 同一个「每次调用纳秒」，在不同跨界粒度下换算出的总开销差三个数量级。
     */
    private static void printBoundedSummary(PrintStream out, List<Result> raw) {
        double base = baseline(raw);

        out.printf("%n=== 换算成总开销（净跨界成本 = 实测 − 纯 Java 基线 %.1f ns）===%n", base);
        out.printf("%-24s %12s %13s %14s %16s%n",
                "场景", "实测(ns/次)", "净(ns/次)", "按批(550 次)", "按条(623,804 次)");
        out.println("-".repeat(84));

        List<Result> results = new ArrayList<>();
        for (Result r : raw) {
            double net = r.name().startsWith("java-baseline") ? r.medianNs() : r.medianNs() - base;
            Result enriched = r.withNet(net);
            results.add(enriched);
            out.printf("%-24s %12.1f %13.1f %12.3f ms %12.1f ms%n",
                    enriched.name(), enriched.medianNs(), enriched.netNs(),
                    enriched.batchMs(), enriched.itemMs());
        }

        out.printf("%n参照：G1 三相移植的端到端节省 = %.1f ms；G0 口径修正后基线 = %.1f ms%n",
                G1_SAVING_MS, G0_BASELINE_MS);
        out.println();
        out.println("=== 结论：26.6%–27.7% 的收益，两种粒度下各剩多少 ===");

        for (String name : new String[]{"sum_len/cached-handle", "sum_len/fresh-handle"}) {
            Result r = pick(results, name);
            if (r == null) {
                continue;
            }
            out.printf("%s：%n", name);
            out.printf("  按批(550 次)     开销 %9.3f ms → 净收益 %6.1f ms ⇒ 端到端 %5.1f%%%n",
                    r.batchMs(), G1_SAVING_MS - r.batchMs(),
                    (G1_SAVING_MS - r.batchMs()) / G0_BASELINE_MS * 100.0);
            out.printf("  按条(623,804 次) 开销 %9.1f ms → 净收益 %6.1f ms ⇒ 端到端 %5.1f%%%n",
                    r.itemMs(), G1_SAVING_MS - r.itemMs(),
                    (G1_SAVING_MS - r.itemMs()) / G0_BASELINE_MS * 100.0);
        }
    }

    private static double baseline(List<Result> results) {
        Result b = pick(results, "java-baseline/noop");
        return b == null ? 0.0 : b.medianNs();
    }

    private static Result pick(List<Result> results, String name) {
        for (Result r : results) {
            if (r.name().equals(name)) {
                return r;
            }
        }
        return null;
    }

    /**
     * panic 边界实测（N5 / T17 / N6 / X30 的举证）。
     *
     * <p>这不是计时项，是<b>正确性项</b>：调用 Rust 侧故意 panic 的 {@code always_panics}，
     * 验证它返回错误码 {@code -1} 而不是让 unwind 穿过 Java 帧。
     *
     * <p>判据是「返回值 = -1 且 JVM 活着」。若 {@code Cargo.toml} 里被误加了
     * {@code panic = "abort"}，这里不会返回 -1，而是整个进程直接终止 ——
     * 也就是说<b>这个探针能在运行时抓出那条铁律被违反</b>，而不只靠读配置。
     */
    private static void verifyPanicBoundary(PrintStream out, Linker linker, SymbolLookup lookup) {
        MethodHandle handle = downcall(linker, lookup, "always_panics",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG));
        long result = invoke(() -> (long) handle.invokeExact());
        boolean codeOk = result == -1L;
        out.println();
        out.printf("panic 边界实测：always_panics() 返回 %d（期望 -1）→ %s%n",
                result, codeOk ? "已被 catch_unwind 兜住" : "未按错误码返回");
        out.println("  Rust 侧真的 panic 了；能返回 -1 就证明 unwind 没有穿过 Java 帧。");
        out.println("  若 Cargo.toml 出现 panic = abort，这里会让整个 JVM 进程终止。");
    }

    /**
     * 一条场景的测量结果。
     *
     * @param medianNs 中位数，含循环开销
     * @param netNs    扣掉纯 Java 基线后的<b>净跨界成本</b>
     */
    private record Result(String name, double medianNs, double p95Ns, int samples, double netNs) {
        Result withNet(double net) {
            return new Result(name, medianNs, p95Ns, samples, net);
        }

        /** 按批：550 次跨界（182 阶段 × 约 3 相）。 */
        double batchMs() {
            return netNs * CROSSINGS_PER_BATCH / 1e6;
        }

        /** 按条：623,804 次跨界（311,902 行 × 2 相）—— T18 明令禁止的粒度。 */
        double itemMs() {
            return netNs * CROSSINGS_PER_ITEM / 1e6;
        }
    }

    private record Scenario(String name, Call call) {
        long invoke() {
            return call.invoke();
        }
    }

    @FunctionalInterface
    private interface Call {
        long invoke();
    }
}
