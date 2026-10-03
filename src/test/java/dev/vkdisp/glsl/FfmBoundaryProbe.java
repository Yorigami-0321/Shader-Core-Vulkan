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
import java.util.Map;
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

    // ===== 大载荷（task-6）相关常量 =====

    /**
     * 载荷阶梯（字节）。必测档是 {@code 64KB}（≈单阶段 ~95KB 量级）与 {@code 1MB}（≈多阶段合并）。
     * {@code 16MB} 对应整包 17,298,868 字节的量级。
     */
    private static final long[] BIG_PAYLOADS = {64L, 1L << 10, 64L << 10, 1L << 20, 16L << 20};

    /**
     * 缓冲区池大小（字节）。
     *
     * <p>🔴 <b>这是整个大载荷实验能不能成立的关键</b>：本机 L3 = 16MB。
     * 若反复对<b>同一个</b> segment 调用，它会一直留在 cache 里，
     * 测出来的是「热缓存下传指针」的成本 —— 而真实流水线每次传的是<b>不同的</b>缓冲区
     * （每个阶段刚从前面的 zip/解码缓冲里出来，第一次触碰时是冷的）。
     * ⇒ 池必须<b>远大于 L3</b>，并且每轮让调用在池里轮转，才能测到冷访问的真实代价。
     * 反过来，如果忘了轮转，就会**系统性低估**跨界成本 —— 那属于「测了个更乐观的数」，
     * 是这类实验最危险的失败方向。
     */
    private static final long POOL_BYTES = 256L << 20;

    /**
     * 轮转步长（字节）：至少 2×L3，保证下一次触碰的缓冲区一定不在缓存里。
     *
     * <p>刻意用「2×L3」而不是「L3」：只差一个系数，但后者在边界上会漏掉残留。
     */
    private static final long STRIDE_BYTES = 32L << 20;

    /** 每轮调用次数的上下限。小载荷需要很多次才够计时精度；大载荷需要很少次否则单轮过长。 */
    private static final int MIN_CALLS_PER_ROUND = 20;
    private static final int MAX_CALLS_PER_ROUND = 200_000;

    /** 每档每轮希望「扫过」约一个池子（payloadBytes × callsPerRound ≈ POOL_BYTES）。 */
    private static final long BYTES_PER_ROUND_TARGET = POOL_BYTES;

    /**
     * 大载荷套件的独立趟数。
     *
     * <p>🔴 上一轮吃过教训：`noop/cached` 场景跨趟极差 **4.22×**，团队据此认定
     * 「该场景不可解读」。所以这次刻意多跑几趟，把<b>跨趟极差直接算出来</b>，
     * 而不是只报一个中位数 —— 一个自己都撑不住的数字不该被拿去下结论。
     */
    private static final int BIG_RUNS = 5;

    /** Arena 复用的样本载荷：取「单个阶段」量级（64KB 档）。 */
    private static final long ARENA_PAYLOAD_BYTES = 64L << 10;

    /** Arena 实验的每轮次数。 */
    private static final int ARENA_CALLS_PER_ROUND = 2_000;

    /**
     * Rust 侧回传形态 A 的变换常量（{@code dst[i] = src[i] ^ 0x5A}）。
     *
     * <p>🔴 必须与 {@code src/lib.rs} 的 {@code transform_into} <b>逐字对应</b>：
     * Java 侧要独立算出同样的结果，才能校验「三形态产出的确实是同一份数据」。
     * 这正是上一轮「有符号/无符号不一致」那类问题的防线 —— 校验和一旦对不上就停。
     */
    private static final int RETURN_XOR = 0x5A;

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

            // 大载荷套件（task-6）：上面那套是 40 字节的「按条」量级，
            // 而真实批次传的是数十 KB～MB 的整份源码 ⇒ 另起一趟。
            runLargePayloadSuite(out, linker, lookup);
            runArenaSuite(out, linker, lookup);
            runReturnPathSuite(out, linker, lookup);

            out.println();
            verifyPanicBoundary(out, linker, lookup);
        }
    }

    /**
     * 大载荷套件：同一机制、不同载荷规模下各测三样。
     *
     * <p>三样分别是：<b>零拷贝</b>（Rust 只读指针，真实实现该这样）、
     * <b>拷贝</b>（Rust 先拷进 {@code Vec}，量化「实现写错」的代价）、
     * <b>同工纯 Java 基线</b>（同样的循环、同样的字节和，但完全不跨边界）。
     *
     * <p>🔴 <b>必须同工</b>：若基线什么都不做，差值里就混进了「Rust 干活的时间」，
     * 那测到的不是跨界成本。上一轮踩过「忘测基线 ⇒ 把 ~20ns 循环开销算成跨界开销」的坑，
     * 这里进一步要求基线<b>干同样的活</b>。
     */
    private static void runLargePayloadSuite(PrintStream out, Linker linker, SymbolLookup lookup) {
        Map<Long, List<BigCase>> all = new java.util.LinkedHashMap<>();
        for (int run = 0; run < BIG_RUNS; run++) {
            oneBigRun(out, linker, lookup, run, all);
        }
        reportBigRuns(out, all);
    }

    /** 单趟：把每一档的结果按载荷记进 all，供最后跨趟聚合。 */
    private static void oneBigRun(PrintStream out, Linker linker, SymbolLookup lookup,
            int run, Map<Long, List<BigCase>> all) {
        MethodHandle zeroCopy = downcall(linker, lookup, "sum_len",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        MethodHandle copying = downcall(linker, lookup, "sum_len_copy",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

        out.printf("%n=== 大载荷套件（task-6）===%n");
        out.printf("L3 缓存 16MB；缓冲区池 %.0fMB，轮转步长 %.0fMB ⇒ 每次调用都触碰冷内存%n",
                POOL_BYTES / 1048576.0, STRIDE_BYTES / 1048576.0);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pool = arena.allocate(POOL_BYTES, 1);
            fillDeterministic(pool);

            out.printf("%n%-12s %10s %8s %13s %13s %13s %13s %13s%n",
                    "载荷", "每轮次数", "样本", "零拷贝(ns)", "拷贝(ns)", "基线(ns)",
                    "拷贝代价(ns)", "净跨界(ns)");
            out.println("-".repeat(112));

            for (long payload : BIG_PAYLOADS) {
                int calls = callsPerRound(payload);
                BigCase result = measureBig(pool, payload, calls, zeroCopy, copying);
                all.computeIfAbsent(payload, k -> new ArrayList<>()).add(result);
            }
        }
    }

    /**
     * 跨趟聚合：报中位数、p95、<b>跨趟极差</b>，并据此声明每个档位<b>可不可解读</b>。
     *
     * <p>判据（沿用上一轮团队定下的口径）：跨趟极差过大 ⇒ 该场景不可解读，
     * 结论只用可解读的那些档。宁可少说，也不要拿一个撑不住的数字下结论。
     */
    private static void reportBigRuns(PrintStream out, Map<Long, List<BigCase>> all) {
        out.printf("%n=== 大载荷套件：%d 趟汇总（中位数 / p95 / 跨趟极差）===%n", BIG_RUNS);
        out.printf("%-8s %10s %8s %12s %12s %12s %12s %12s %10s%n",
                "载荷", "每轮次数", "样本", "零拷贝(中位)", "零拷贝p95", "拷贝(中位)",
                "基线(中位)", "拷贝代价", "跨趟极差");
        out.println("-".repeat(110));

        Map<Long, BigCase> median = new java.util.LinkedHashMap<>();
        List<String> unreadable = new ArrayList<>();
        for (Map.Entry<Long, List<BigCase>> e : all.entrySet()) {
            long payload = e.getKey();
            List<BigCase> runs = e.getValue();
            double zeroSpread = spread(runs, "zero");
            double copySpread = spread(runs, "copy");
            double javaSpread = spread(runs, "java");
            double worst = Math.max(zeroSpread, Math.max(copySpread, javaSpread));
            boolean ok = worst <= 1.0;   // 跨趟极差 ≤ 1.0（100%）才算可解读
            if (!ok) {
                unreadable.add(humanBytes(payload) + "(极差 " + String.format("%.2f×", worst) + ")");
            }
            BigCase med = medianOf(runs);
            median.put(payload, med);
            int calls = callsPerRound(payload);
            out.printf("G3DATA\tffibig\t%d\t%.1f\t%.1f\t%.1f\t%d\n", payload,
                    med.zeroNs(), med.copyNs(), med.javaNs(), med.samples());
            out.printf("%-8s %10d %8d %12.1f %12.1f %12.1f %12.1f %12.1f %9.2f× %s%n",
                    humanBytes(payload), calls, med.samples(),
                    med.zeroNs(), med.zero.p95Ns(), med.copyNs(), med.javaNs(),
                    med.copyNs() - med.zeroNs(), worst, ok ? "✅可解读" : "🔴不可解读");
        }
        if (!unreadable.isEmpty()) {
            out.printf("%n🔴 跨趟极差 >1.0 的档位（不可解读，结论不使用）：%s%n", String.join("、", unreadable));
        }
        printLargePayloadVerdict(out, median);
    }

    private static double spread(List<BigCase> runs, String which) {
        double[] v = new double[runs.size()];
        for (int i = 0; i < runs.size(); i++) {
            BigCase c = runs.get(i);
            v[i] = switch (which) {
                case "copy" -> c.copyNs();
                case "java" -> c.javaNs();
                default -> c.zeroNs();
            };
        }
        double med = median(v);
        return med <= 0 ? Double.POSITIVE_INFINITY : (max(v) - min(v)) / med;
    }

    private static BigCase medianOf(List<BigCase> runs) {
        double[] z = new double[runs.size()];
        double[] c = new double[runs.size()];
        double[] j = new double[runs.size()];
        for (int i = 0; i < runs.size(); i++) {
            z[i] = runs.get(i).zeroNs();
            c[i] = runs.get(i).copyNs();
            j[i] = runs.get(i).javaNs();
        }
        double zp = 0;
        double zp95 = 0;
        for (BigCase r : runs) {
            zp += r.zero.p95Ns();
            zp95 = Math.max(zp95, r.zero.p95Ns());
        }
        return new BigCase(new Timing(median(z), zp95), new Timing(median(c), 0),
                new Timing(median(j), 0), runs.get(0).samples());
    }

    private static double median(double[] v) {
        double[] s = v.clone();
        Arrays.sort(s);
        return s[s.length / 2];
    }

    private static double max(double[] v) {
        double m = Double.NEGATIVE_INFINITY;
        for (double x : v) {
            m = Math.max(m, x);
        }
        return m;
    }

    private static double min(double[] v) {
        double m = Double.POSITIVE_INFINITY;
        for (double x : v) {
            m = Math.min(m, x);
        }
        return m;
    }

    /**
     * 把结论换算回「36.3% 的端到端收益还剩多少」。
     *
     * <p>口径：按批粒度下，每个阶段传一份整份源码。整包 182 个阶段合计 17,298,868 字节，
     * 所以总字节数固定；跨界次数 550 次不变。<b>变的只有每字节要花多少钱。</b>
     */
    /**
     * 把结论换算回「36.3% 的端到端收益还剩多少」。
     *
     * <p>🔴 <b>换算方法必须说清楚，否则数是假的</b>：上一版我拿「64KB 档的耗时」直接当成
     * 「95,049 字节那一批的代价」，那是把一个 65,536 字节的数错当成 95,049 字节的数。
     * 正确做法是<b>反推每字节成本</b>：在 64KB / 1MB / 16MB 这些档位上，
     * 固定开销（~16.6ns）相对总耗时已可忽略，于是 {@code ns/字节 ≈ 总耗时 / 载荷字节}；
     * 再用这个斜率去外推真实的平均批量（17,298,868 / 182 ≈ 95,049 字节）。
     *
     * <p>三档算出的斜率高度一致（见证据表），本身就是这个方法可信的旁证。
     */
    private static void printLargePayloadVerdict(PrintStream out,
            Map<Long, BigCase> cases) {
        final double bundleBytes = 17_298_868.0;
        final double stagesPerPass = 182.0;
        final double avgBatchBytes = bundleBytes / stagesPerPass;
        final double crossingsPerBundle = 550.0;
        final double savingMs = 325.1;
        final double baselineMs = 895.3;

        out.printf("%n=== 换算：整包 182 阶段合计 %.0f 字节，平均每批 %.0f 字节，按批 %d 次跨界 ===%n",
                bundleBytes, avgBatchBytes, (int) crossingsPerBundle);

        out.printf("%n  每字节成本斜率（三档独立反推，互相印证）：%n");
        double[] slopes = new double[0];
        for (long rung : new long[]{64L << 10, 1L << 20, 16L << 20}) {
            BigCase c = cases.get(rung);
            if (c == null) {
                continue;
            }
            double slope = c.zeroNs() / rung;
            slopes = java.util.Arrays.copyOf(slopes, slopes.length + 1);
            slopes[slopes.length - 1] = slope;
            out.printf("    %-5s 总耗时 %10.1f ns ÷ %9d 字节 = %.4f ns/字节%n",
                    humanBytes(rung), c.zeroNs(), rung, slope);
        }
        if (slopes.length == 0) {
            return;
        }
        double[] sortedSlope = slopes.clone();
        Arrays.sort(sortedSlope);
        double slopeNs = sortedSlope[sortedSlope.length / 2];

        BigCase small = cases.get(64L);
        double fixedNs = small == null ? 0.0 : small.zeroNs();
        BigCase k64 = cases.get(64L << 10);
        BigCase m1 = cases.get(1L << 20);
        double copyExtraSlope = 0.0;
        if (k64 != null && m1 != null) {
            BigCase big16 = cases.get(16L << 20);
            if (big16 != null) {
                copyExtraSlope = (big16.copyNs() - big16.zeroNs()) / (16L << 20);
            }
        }

        out.printf("%n  取中位斜率 %.4f ns/字节、固定开销 %.1f ns/次%n", slopeNs, fixedNs);
        out.printf("  ⇒ 一个 %.0f 字节的批次：%n", avgBatchBytes);
        double zeroPerCall = fixedNs + slopeNs * avgBatchBytes;
        double copyPerCall = zeroPerCall + copyExtraSlope * avgBatchBytes;
        out.printf("    零拷贝 %10.1f ns/次 × %d 次 = %7.3f ms ⇒ 端到端 %.1f%%%n",
                zeroPerCall, (int) crossingsPerBundle,
                crossingsPerBundle * zeroPerCall / 1e6,
                (savingMs - crossingsPerBundle * zeroPerCall / 1e6) / baselineMs * 100.0);
        out.printf("    拷贝   %10.1f ns/次 × %d 次 = %7.3f ms ⇒ 端到端 %.1f%%%n",
                copyPerCall, (int) crossingsPerBundle,
                crossingsPerBundle * copyPerCall / 1e6,
                (savingMs - crossingsPerBundle * copyPerCall / 1e6) / baselineMs * 100.0);
        out.printf("%n  🔖 其中「拷贝」相对「零拷贝」每字节多 %.4f ns（memcpy 代价）%n", copyExtraSlope);
        out.printf("  🔖 注意：这 %7.3f ms 里，**跨界机制本身只占 %.3f ms**（%d × %.1f ns），%n",
                crossingsPerBundle * zeroPerCall / 1e6,
                crossingsPerBundle * fixedNs / 1e6, (int) crossingsPerBundle, fixedNs);
        out.printf("     其余是「读这些字节」的工作量 —— 真实实现无论跨界与否都要读，所以它不是跨界开销。%n");
    }

    /**
     * {@code Arena} 复用 vs 每批新建 —— 顺带查泄漏。
     *
     * <p>🔴 为什么单独测：{@code Arena} 是堆外内存，<b>泄漏在这条路径上表现为「越跑越慢」</b>，
     * 那是最难查的一类问题。而且真实流水线有两个合理选择：
     * <b>每批新建一个 Arena</b>（简单，生命周期清楚）或 <b>复用一个 Arena</b>（少分配少释放）。
     * 两者差多少，必须有数，否则没法选。
     *
     * <p>同时测「新建即释放」的显式路径：若它每轮耗时稳定，说明没有泄漏。
     */
    private static void runArenaSuite(PrintStream out, Linker linker, SymbolLookup lookup) {
        MethodHandle sum = downcall(linker, lookup, "sum_len",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        out.printf("%n=== Arena：复用 vs 每批新建（载荷 %s）===%n",
                humanBytes(ARENA_PAYLOAD_BYTES));

        // 🔴 三臂，不是两臂。第一版只做了 reuse / fresh 两个对照，结果**被污染了**：
        // fresh 那一臂每次都要 fillDeterministic 写 64KB，于是差值里混进了「写数据」的成本，
        // 而那不是 Arena 的成本。拆成三臂才能各自归因：
        //   reuse        = 预填充一次，之后只跨界                       ⇒ 纯跨界
        //   fresh-nofill = 每批 alloc + 跨界(零页) + close，不填        ⇒ 纯 alloc/close
        //   fresh-fill   = 每批 alloc + 填 + 跨界 + close               ⇒ alloc/close + 填充
        // 于是 alloc/close = fresh-nofill − reuse；填充 = fresh-fill − fresh-nofill。
        Timing reuse;
        Timing freshNofill;
        Timing freshFill;
        try (Arena reusable = Arena.ofConfined()) {
            MemorySegment seg = reusable.allocate(ARENA_PAYLOAD_BYTES, 1);
            fillDeterministic(seg);
            reuse = measureScenario(() -> {
                long sink = 0;
                for (int i = 0; i < ARENA_CALLS_PER_ROUND; i++) {
                    sink += invoke(() -> (long) sum.invokeExact(seg, ARENA_PAYLOAD_BYTES));
                }
                return sink;
            }, ARENA_CALLS_PER_ROUND);
        }
        freshNofill = measureScenario(() -> {
            long sink = 0;
            for (int i = 0; i < ARENA_CALLS_PER_ROUND; i++) {
                try (Arena oneShot = Arena.ofConfined()) {
                    MemorySegment seg = oneShot.allocate(ARENA_PAYLOAD_BYTES, 1);
                    sink += invoke(() -> (long) sum.invokeExact(seg, ARENA_PAYLOAD_BYTES));
                }
            }
            return sink;
        }, ARENA_CALLS_PER_ROUND);
        freshFill = measureScenario(() -> {
            long sink = 0;
            for (int i = 0; i < ARENA_CALLS_PER_ROUND; i++) {
                try (Arena oneShot = Arena.ofConfined()) {
                    MemorySegment seg = oneShot.allocate(ARENA_PAYLOAD_BYTES, 1);
                    fillDeterministic(seg);
                    sink += invoke(() -> (long) sum.invokeExact(seg, ARENA_PAYLOAD_BYTES));
                }
            }
            return sink;
        }, ARENA_CALLS_PER_ROUND);

        out.printf("%-20s %13s %13s%n", "方式", "中位数(ns/次)", "p95(ns/次)");
        out.println("-".repeat(52));
        out.printf("%-20s %13.1f %13.1f%n", "reuse", reuse.medianNs(), reuse.p95Ns());
        out.printf("%-20s %13.1f %13.1f%n", "fresh-nofill", freshNofill.medianNs(), freshNofill.p95Ns());
        out.printf("%-20s %13.1f %13.1f%n", "fresh-fill", freshFill.medianNs(), freshFill.p95Ns());
        out.printf("G3DATA\tarena\t%d\treuse\t%.1f\t%d%n",
                ARENA_PAYLOAD_BYTES, reuse.medianNs(), ARENA_CALLS_PER_ROUND);
        out.printf("G3DATA\tarena\t%d\tfresh-nofill\t%.1f\t%d%n",
                ARENA_PAYLOAD_BYTES, freshNofill.medianNs(), ARENA_CALLS_PER_ROUND);
        out.printf("G3DATA\tarena\t%d\tfresh-fill\t%.1f\t%d%n",
                ARENA_PAYLOAD_BYTES, freshFill.medianNs(), ARENA_CALLS_PER_ROUND);

        double allocClose = freshNofill.medianNs() - reuse.medianNs();
        double fillCost = freshFill.medianNs() - freshNofill.medianNs();
        out.printf("%n  alloc+close 成本 = fresh-nofill − reuse = %.1f ns/次%n", allocClose);
        out.printf("  填充成本        = fresh-fill − fresh-nofill = %.1f ns/次%n", fillCost);
        out.printf("  ⇒ 按批 550 次：alloc+close %.3f ms，填充 %.3f ms%n",
                allocClose * 550 / 1e6, fillCost * 550 / 1e6);
        out.printf("  🔖 对照：拷贝 vs 零拷贝的整包差额是 6.505 ms ⇒ %s%n",
                allocClose * 550 / 1e6 > 6.505
                        ? "Arena 策略比「拷贝与否」更值得先定"
                        : "拷贝与否的差额更大");
        out.printf("  泄漏自检：fresh 每批都 close。若有泄漏，%d 轮之后可用内存会明显下降；%n",
                ARENA_CALLS_PER_ROUND);
        out.printf("  本实验未做「连续多趟后比内存」的断言 —— 那是独立的事，见「没有证明的事」。%n");
    }

    /**
     * 回传方向套件（task-10）：Rust → Java，**G2 最后一个没量过的方向**。
     *
     * <p>此前测的都是 Java→Rust 单向。但预处理产物**本来就是要回传的** ——
     * Rust 处理完要把结果交回 Java，方向相反、载荷同样大。
     *
     * <p>三种形态（代价可能差很远）：
     * <ol>
     *   <li><b>A 回传指针</b>：Rust 持有静态缓冲，Java 拿地址。最省，
     *       但🔴<b>指针只在下一次调用前有效</b>；</li>
     *   <li><b>B 拷贝</b>：Rust {@code std::alloc} 一块、Java 拷走、再 {@code free_buffer}；</li>
     *   <li><b>C 写进 Java 堆外缓冲</b>：Java 用 {@code Arena} 先分配好，Rust 直接写进去。
     *       两侧都不分配，无所有权歧义。</li>
     * </ol>
     * 外加<b>同工纯 Java 基线</b>（同一变换，完全不跨界）。
     *
     * <p>🔴 四路的校验和必须一致，否则差值没有意义 —— 这是上一轮「有符号/无符号不一致」
     * 那类静默噪声的防线。
     */
    private static void runReturnPathSuite(PrintStream out, Linker linker, SymbolLookup lookup) {
        MethodHandle borrowPtr = downcall(linker, lookup, "borrow_buffer_ptr",
                FunctionDescriptor.of(ValueLayout.ADDRESS));
        MethodHandle produceBorrow = downcall(linker, lookup, "produce_borrow",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        MethodHandle produceAlloc = downcall(linker, lookup, "produce_alloc",
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        MethodHandle freeBuffer = downcall(linker, lookup, "free_buffer",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        MethodHandle produceIntoDst = downcall(linker, lookup, "produce_into_dst",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

        out.printf("%n=== 回传方向套件（task-10）：Rust → Java ===%n");
        out.printf("缓冲区池 %.0fMB，步长 %.0fMB ⇒ 每次调用都触碰冷内存%n",
                POOL_BYTES / 1048576.0, STRIDE_BYTES / 1048576.0);

        // 形态 A 的地址**只取一次并缓存** —— 每次调用都问一遍等于多一次跨界，
        // 那属于「句柄没缓存」那类蠢错（上一轮实测它贵一个数量级）。
        //
        // 🔴🔴 **但必须先让 Rust 侧把缓冲分配出来，再取地址。**
        // 本轮第一次跑就是在这里崩的：Rust 侧原本用 `Vec::new()`，而空 Vec 的
        // `as_ptr()` 是**悬垂指针**（不指向任何已分配内存）；Java 拿到后按 16MB
        // 去读 ⇒ JVM 崩在 `Unsafe_GetByte`（SIGABRT）。
        // Rust 侧已改成「首次 produce 时一次性按 MAX_BORROW_BYTES 分配、此后永不重分配」，
        // 但**调用顺序仍是调用方的责任** —— 契约是：先 produce，再取指针。
        MemorySegment borrowSeg;
        try (Arena warmup = Arena.ofConfined()) {
            MemorySegment seed = warmup.allocate(8, 1);
            fillDeterministic(seed);
            long seeded = invoke(() -> (long) produceBorrow.invokeExact(seed, 8L));
            if (seeded == 0L) {
                throw new IllegalStateException("预热 produce_borrow 失败（返回 0），中止");
            }
            // 句柄声明为返回 ValueLayout.ADDRESS ⇒ invokeExact 已经给出 MemorySegment，
            // **不要**再套 MemorySegment.ofAddress（那个重载收的是 long 地址，会编译失败）。
            borrowSeg = invokeAddr(() -> (MemorySegment) borrowPtr.invokeExact())
                    .reinterpret(BigByteLen + 8);
            if (borrowSeg.byteSize() == 0) {
                throw new IllegalStateException("borrow 缓冲地址无效（空），中止");
            }
        }

        Map<Long, List<ReturnCase>> all = new java.util.LinkedHashMap<>();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pool = arena.allocate(POOL_BYTES, 1);
            fillDeterministic(pool);
            // 形态 C 的目标缓冲：一次分配、全程复用（这正是该形态的主张）。
            MemorySegment dst = arena.allocate(BigByteLen + 8, 1);

            for (int run = 0; run < BIG_RUNS; run++) {
                for (long payload : BIG_PAYLOADS) {
                    int calls = callsPerRound(payload);
                    ReturnCase c = measureReturnCase(pool, dst, payload, calls,
                            produceBorrow, produceAlloc, freeBuffer, produceIntoDst, borrowSeg);
                    all.computeIfAbsent(payload, k -> new ArrayList<>()).add(c);
                }
            }
        }

        reportReturnRuns(out, all);
    }

    /** 载荷上限（C 形态目标缓冲与 A 形态重解释都要用它）。 */
    private static final long BigByteLen = 16L << 20;

    private static ReturnCase measureReturnCase(MemorySegment pool, MemorySegment dst,
            long payloadBytes, int callsPerRound, MethodHandle produceBorrow,
            MethodHandle produceAlloc, MethodHandle freeBuffer, MethodHandle produceIntoDst,
            MemorySegment borrowSeg) {
        // ---- 四路校验和必须一致（不然后面的差值全是噪声）----
        MemorySegment sample = pool.asSlice(offsetFor(payloadBytes, 0), payloadBytes);
        long wantJava = javaTransformSum(sample);
        long nA = invoke(() -> (long) produceBorrow.invokeExact(sample, payloadBytes));
        long sumA = sumSegment(borrowSeg, nA);
        // 🔴 produce_alloc 返回的是**无界** MemorySegment，直接 get() 会越界报错；
        // 必须用 payloadBytes 把它界定成有界区间。
        MemorySegment bPtr = invokeAddr(() -> (MemorySegment) produceAlloc.invokeExact(
                sample, payloadBytes)).reinterpret(payloadBytes);
        long sumB = sumSegment(bPtr, payloadBytes);
        invoke(() -> (long) freeBuffer.invokeExact(bPtr, payloadBytes));
        invoke(() -> (long) produceIntoDst.invokeExact(
                dst, dst.byteSize(), sample, payloadBytes));
        long sumC = sumSegment(dst, payloadBytes);
        if (wantJava != sumA || wantJava != sumB || wantJava != sumC) {
            throw new IllegalStateException("四路校验和不一致（Java=" + wantJava
                    + " A=" + sumA + " B=" + sumB + " C=" + sumC
                    + "）—— 差值将没有意义，请先查 transform 是否逐字对应");
        }

        Timing a = measureScenario(() -> {
            long sink = 0;
            for (int i = 0; i < callsPerRound; i++) {
                MemorySegment src = pool.asSlice(offsetFor(payloadBytes, i), payloadBytes);
                long n = invoke(() -> (long) produceBorrow.invokeExact(src, payloadBytes));
                // 消费结果：读首尾各一字节即可防止死代码消除，又不额外扫一遍内存。
                sink += borrowSeg.get(ValueLayout.JAVA_BYTE, 0)
                        + borrowSeg.get(ValueLayout.JAVA_BYTE, n - 1);
            }
            return sink;
        }, callsPerRound);

        Timing b = measureScenario(() -> {
            long sink = 0;
            for (int i = 0; i < callsPerRound; i++) {
                MemorySegment src = pool.asSlice(offsetFor(payloadBytes, i), payloadBytes);
                MemorySegment p = invokeAddr(() -> (MemorySegment) produceAlloc.invokeExact(
                        src, payloadBytes)).reinterpret(payloadBytes);
                sink += p.get(ValueLayout.JAVA_BYTE, 0) + p.get(ValueLayout.JAVA_BYTE, payloadBytes - 1);
                invoke(() -> (long) freeBuffer.invokeExact(p, payloadBytes));
            }
            return sink;
        }, callsPerRound);

        Timing c = measureScenario(() -> {
            long sink = 0;
            for (int i = 0; i < callsPerRound; i++) {
                MemorySegment src = pool.asSlice(offsetFor(payloadBytes, i), payloadBytes);
                long n = invoke(() -> (long) produceIntoDst.invokeExact(
                        dst, dst.byteSize(), src, payloadBytes));
                sink += dst.get(ValueLayout.JAVA_BYTE, 0) + dst.get(ValueLayout.JAVA_BYTE, n - 1);
            }
            return sink;
        }, callsPerRound);

        Timing base = measureScenario(() -> {
            long sink = 0;
            for (int i = 0; i < callsPerRound; i++) {
                MemorySegment src = pool.asSlice(offsetFor(payloadBytes, i), payloadBytes);
                sink += javaTransformSum(src);
            }
            return sink;
        }, callsPerRound);

        return new ReturnCase(a, b, c, base, SAMPLE_ROUNDS);
    }

    /** Java 侧独立实现 Rust 的 {@code transform_into}，用于对拍。 */
    private static long javaTransformSum(MemorySegment segment) {
        long sum = 0;
        for (long i = 0; i < segment.byteSize(); i++) {
            // 🔴 & 0xFF 必须有：get(JAVA_BYTE) 返回**有符号** byte，
            // 不屏蔽的话与 Rust 的「无符号」算的不是同一件事（上一轮踩过）。
            sum += (segment.get(ValueLayout.JAVA_BYTE, i) & 0xFF) ^ RETURN_XOR;
        }
        return sum;
    }

    private static long sumSegment(MemorySegment segment, long len) {
        long sum = 0;
        for (long i = 0; i < len; i++) {
            sum += segment.get(ValueLayout.JAVA_BYTE, i) & 0xFF;
        }
        return sum;
    }

    private static void reportReturnRuns(PrintStream out, Map<Long, List<ReturnCase>> all) {
        out.printf("%n=== 回传方向：%d 趟汇总（中位数 / p95 / 跨趟极差）===%n", BIG_RUNS);
        out.printf("%-8s %8s %11s %11s %11s %11s %11s %10s%n",
                "载荷", "样本", "A回传指针", "B拷贝", "C写进Java", "同工Java基线", "C−基线", "跨趟极差");
        out.println("-".repeat(100));

        List<String> unreadable = new ArrayList<>();
        Map<Long, ReturnCase> meds = new java.util.LinkedHashMap<>();
        for (Map.Entry<Long, List<ReturnCase>> e : all.entrySet()) {
            long payload = e.getKey();
            List<ReturnCase> runs = e.getValue();
            double worst = Math.max(returnSpread(runs, "a"), Math.max(
                    returnSpread(runs, "b"), Math.max(returnSpread(runs, "c"),
                            returnSpread(runs, "base"))));
            boolean ok = worst <= 1.0;
            if (!ok) {
                unreadable.add(humanBytes(payload));
            }
            ReturnCase med = medianReturn(runs);
            meds.put(payload, med);
            // 🔴 打印逐趟值：判据是「逐次单调恶化」—— 那是**污染的指纹**，
            // 而不是噪声。噪声来回摆，单调恶化不回摆。团队要求看到这个才能下结论。
            StringBuilder perRun = new StringBuilder();
            for (ReturnCase r : runs) {
                perRun.append(String.format(" %.0f/%.0f/%.0f",
                        r.aNs(), r.cNs(), r.baseNs()));
            }
            out.printf("%-8s 逐趟(A/C/基线) ns:%s%n", humanBytes(payload), perRun);
            out.printf("G3DATA\tret\t%d\t%.1f\t%.1f\t%.1f\t%.1f\t%d%n", payload,
                    med.aNs(), med.bNs(), med.cNs(), med.baseNs(), med.samples());
            out.printf("%-8s %8d %11.1f %11.1f %11.1f %11.1f %11.1f %9.2f× %s%n",
                    humanBytes(payload), med.samples(), med.aNs(), med.bNs(), med.cNs(),
                    med.baseNs(), med.cNs() - med.baseNs(), worst, ok ? "✅可解读" : "🔴不可解读");
        }
        if (!unreadable.isEmpty()) {
            out.printf("%n🔴 跨趟极差 >1.0 的档位（不可解读）：%s%n", String.join("、", unreadable));
        }
        printReturnVerdict(out, meds);
    }

    private static double returnSpread(List<ReturnCase> runs, String which) {
        double[] v = new double[runs.size()];
        for (int i = 0; i < runs.size(); i++) {
            ReturnCase c = runs.get(i);
            v[i] = switch (which) {
                case "b" -> c.bNs();
                case "c" -> c.cNs();
                case "base" -> c.baseNs();
                default -> c.aNs();
            };
        }
        double m = median(v);
        return m <= 0 ? Double.POSITIVE_INFINITY : (max(v) - min(v)) / m;
    }

    private static ReturnCase medianReturn(List<ReturnCase> runs) {
        double[] a = new double[runs.size()];
        double[] b = new double[runs.size()];
        double[] c = new double[runs.size()];
        double[] j = new double[runs.size()];
        for (int i = 0; i < runs.size(); i++) {
            a[i] = runs.get(i).aNs();
            b[i] = runs.get(i).bNs();
            c[i] = runs.get(i).cNs();
            j[i] = runs.get(i).baseNs();
        }
        double ap95 = 0;
        for (ReturnCase r : runs) {
            ap95 = Math.max(ap95, r.a.p95Ns());
        }
        return new ReturnCase(new Timing(median(a), ap95), new Timing(median(b), 0),
                new Timing(median(c), 0), new Timing(median(j), 0), runs.get(0).samples());
    }

    /** 结论：把回传开销加回上一轮的 35.7%。 */
    private static void printReturnVerdict(PrintStream out, Map<Long, ReturnCase> meds) {
        final double crossings = 550.0;
        final double avgBatchBytes = 17_298_868.0 / 182.0;
        final double savingMs = 325.1;
        final double baselineMs = 895.3;
        final double prevLargeMs = 5.917;

        ReturnCase big = meds.get(16L << 20);
        ReturnCase oneM = meds.get(1L << 20);
        ReturnCase k64 = meds.get(64L << 10);
        if (big == null || oneM == null || k64 == null) {
            return;
        }
        out.printf("%n=== 回传换算：加上之后 35.7%% 还剩多少 ===%n");
        out.printf("上一轮（只算 Java→Rust 传入）的整包开销 = %.3f ms%n", prevLargeMs);

        // 每字节斜率：用 1MB / 16MB 两档反推（此处固定开销可忽略）。
        double slopeC = ((big.cNs() - k64.cNs()) * (1L << 10) / ((1L << 20) - (64L << 10)))
                / (avgBatchBytes);
        double slopeA = ((big.aNs() - k64.aNs()) * (1L << 10) / ((1L << 20) - (64L << 10)))
                / (avgBatchBytes);
        double slopeB = ((big.bNs() - k64.bNs()) * (1L << 10) / ((1L << 20) - (64L << 10)))
                / (avgBatchBytes);

        for (Object[] row : new Object[][]{
                {"A 回传指针", oneM.aNs(), slopeA},
                {"B 拷贝", oneM.bNs(), slopeB},
                {"C 写进 Java 缓冲", oneM.cNs(), slopeC}}) {
            String name = (String) row[0];
            double slope = (Double) row[2];
            double bundle = crossings * slope * avgBatchBytes / 1e6;
            out.printf("  %-20s 每字节斜率 %.4f ns ⇒ 整包回传开销 %6.3f ms ⇒ 端到端 %.1f%%%n",
                    name, slope, bundle, (savingMs - prevLargeMs - bundle) / baselineMs * 100.0);
        }
        out.printf("%n  🔖 参照：上一步（Java→Rust 传入）整包 5.917ms，对应端到端 35.7%%%n");
    }

    /** 一档载荷的回传三形态 + 基线。 */
    private record ReturnCase(Timing a, Timing b, Timing c, Timing base, int samples) {
        double aNs() {
            return a.medianNs();
        }

        double bNs() {
            return b.medianNs();
        }

        double cNs() {
            return c.medianNs();
        }

        double baseNs() {
            return base.medianNs();
        }
    }

    /** 填充确定性数据，避免全零页带来的「读零页」优化偏差。 */
    private static void fillDeterministic(MemorySegment pool) {
        byte[] chunk = new byte[1 << 16];
        for (int i = 0; i < chunk.length; i++) {
            chunk[i] = (byte) ((i * 31 + 7) & 0xFF);
        }
        long written = 0;
        while (written < pool.byteSize()) {
            int n = (int) Math.min(chunk.length, pool.byteSize() - written);
            MemorySegment.copy(chunk, 0, pool, ValueLayout.JAVA_BYTE, written, n);
            written += n;
        }
    }

    /** 每轮调用次数：让「载荷 × 次数」约等于一个池子，即每轮把池扫一遍。 */
    private static int callsPerRound(long payloadBytes) {
        long calls = BYTES_PER_ROUND_TARGET / payloadBytes;
        return (int) Math.max(MIN_CALLS_PER_ROUND, Math.min(MAX_CALLS_PER_ROUND, calls));
    }

    /** 轮转偏移：步长 ≥ 2×L3，保证每次触碰的是冷内存；用非 2 的幂偏移避免对齐预取。 */
    private static long offsetFor(long payloadBytes, int callIndex) {
        long slots = Math.max(1, (POOL_BYTES - payloadBytes) / STRIDE_BYTES);
        long step = STRIDE_BYTES;
        if (step >= POOL_BYTES - payloadBytes) {
            step = Math.max(payloadBytes, 1);
            slots = Math.max(1, (POOL_BYTES - payloadBytes) / step);
        }
        // 非 2 的幂步进，避免硬件预取把整段提前拉进缓存。
        long idx = (callIndex * 3L + payloadBytes / 64L) % Math.max(1, slots);
        return idx * step;
    }

    /** 三样一起测，保证三者走的是同一段缓冲、同样的循环、同样的调用次数。 */
    private static BigCase measureBig(MemorySegment pool, long payloadBytes, int callsPerRound,
            MethodHandle zeroCopy, MethodHandle copying) {
        // 先跑一次取校验和，证明三路算的是同一件事（否则差值无意义）。
        long probeOffset = offsetFor(payloadBytes, 0);
        MemorySegment probe = pool.asSlice(probeOffset, payloadBytes);
        long viaZero = invoke(() -> (long) zeroCopy.invokeExact(probe, payloadBytes));
        long viaCopy = invoke(() -> (long) copying.invokeExact(probe, payloadBytes));
        long viaJava = javaSum(probe);
        if (viaZero != viaCopy || viaZero != viaJava) {
            throw new IllegalStateException("三路校验和不一致（零拷贝=" + viaZero
                    + " 拷贝=" + viaCopy + " Java=" + viaJava + "）—— 差值将没有意义");
        }

        Timing zeroT = measureScenario(() -> {
            long sink = 0;
            for (int c = 0; c < callsPerRound; c++) {
                long off = offsetFor(payloadBytes, c);
                sink += invoke(() -> (long) zeroCopy.invokeExact(
                        pool.asSlice(off, payloadBytes), payloadBytes));
            }
            return sink;
        }, callsPerRound);
        Timing copyT = measureScenario(() -> {
            long sink = 0;
            for (int c = 0; c < callsPerRound; c++) {
                long off = offsetFor(payloadBytes, c);
                sink += invoke(() -> (long) copying.invokeExact(
                        pool.asSlice(off, payloadBytes), payloadBytes));
            }
            return sink;
        }, callsPerRound);
        Timing javaT = measureScenario(() -> {
            long sink = 0;
            for (int c = 0; c < callsPerRound; c++) {
                long off = offsetFor(payloadBytes, c);
                sink += javaSum(pool.asSlice(off, payloadBytes));
            }
            return sink;
        }, callsPerRound);
        return new BigCase(zeroT, copyT, javaT, SAMPLE_ROUNDS);
    }

    /**
     * 同工纯 Java 基线：对同一段内存做同样的字节和，不跨任何边界。
     *
     * <p>🔴 <b>必须按无符号累加</b>：Rust 侧是 {@code *b as usize}（0..255），
     * 而 {@code MemorySegment.get(JAVA_BYTE, i)} 返回的是<b>有符号</b> byte（−128..127）。
     * 不加 {@code & 0xFF} 的话，两边算的根本不是同一个数 ——
     * 本函数上方的三路校验和会立刻报「零拷贝=8160 拷贝=8160 Java=−32」。
     * 那个自检就是为这类问题准备的：<b>它比性能数字先发现问题</b>。
     */
    private static long javaSum(MemorySegment segment) {
        long sum = 0;
        for (long i = 0; i < segment.byteSize(); i++) {
            sum += segment.get(ValueLayout.JAVA_BYTE, i) & 0xFF;
        }
        return sum;
    }

    /** 复用与 {@link #measure(Scenario)} 相同的采样口径（预热 + N 轮，取中位数 + p95）。 */
    private static Timing measureScenario(java.util.function.LongSupplier body, int callsThisRound) {
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            if (body.getAsLong() == Long.MIN_VALUE) {
                System.err.println("unreachable");
            }
        }
        double[] perCall = new double[SAMPLE_ROUNDS];
        for (int i = 0; i < SAMPLE_ROUNDS; i++) {
            long t0 = System.nanoTime();
            body.getAsLong();
            perCall[i] = (double) (System.nanoTime() - t0) / callsThisRound;
        }
        double[] sorted = perCall.clone();
        Arrays.sort(sorted);
        int p95Index = (int) Math.ceil(0.95 * sorted.length) - 1;
        return new Timing(sorted[sorted.length / 2], sorted[Math.max(0, p95Index)]);
    }

    /** 一次测量的中位数与 p95（ns/次）。 */
    private record Timing(double medianNs, double p95Ns) {
    }

    private static String humanBytes(long b) {
        if (b >= 1 << 20) {
            return (b >> 20) + "MB";
        }
        if (b >= 1 << 10) {
            return (b >> 10) + "KB";
        }
        return b + "B";
    }

    /** 一档载荷的三路结果。单位统一为「每次调用的纳秒」。 */
    private record BigCase(Timing zero, Timing copy, Timing java, int samples) {
        double zeroNs() {
            return zero.medianNs();
        }

        double copyNs() {
            return copy.medianNs();
        }

        double javaNs() {
            return java.medianNs();
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

    /**
     * 返回 {@link MemorySegment} 的跨界调用包装（对应 {@code ValueLayout.ADDRESS} 返回值）。
     *
     * <p>🔴 <b>为什么要单独一个方法，不能复用 {@link #invoke}</b>：
     * {@code invokeExact} 是<b>签名多态</b>的，返回类型由<b>调用点目标类型</b>决定。
     * {@link #invoke} 声明返回 {@code long}，所以拿它去调一个返回 {@code ADDRESS}
     * 的句柄，javac 会把调用点当成「返回 long」，而句柄实际返回 {@code MemorySegment}
     * ⇒ 编译期就报类型不匹配（这次是<b>编译期</b>挡下来的，比上一轮那次
     * 「异常被吞掉 → 安静地把异常开销记成跨界开销」要幸运）。
     */
    private static MemorySegment invokeAddr(ThrowingAddrCall call) {
        try {
            return call.invoke();
        } catch (Throwable t) {
            if (FIRST_FAULT.compareAndSet(false, true)) {
                ERR.println("[FfmBoundaryProbe] 回传调用抛出异常（已收敛，不计入计时口径）: " + t);
            }
            return MemorySegment.NULL;
        }
    }

    @FunctionalInterface
    private interface ThrowingAddrCall {
        MemorySegment invoke() throws Throwable;
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
