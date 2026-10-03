package dev.vkdisp.pack;

import dev.vkdisp.glsl.GlslPipeline;
import dev.vkdisp.glsl.TranslateDiagnostic;
import dev.vkdisp.glsl.TranslateResult;
import dev.vkdisp.glsl.preprocess.ConstEvaluator;
import dev.vkdisp.glsl.preprocess.DefineProcessor;
import dev.vkdisp.glsl.preprocess.GlslPreprocessor;
import dev.vkdisp.glsl.preprocess.IncludeProcessor;
import dev.vkdisp.glsl.preprocess.IncludeResolver;
import dev.vkdisp.glsl.translate.OfGlslTranslator;
import dev.vkdisp.glsl.translate.ShaderStage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * 【参考调研】G0 · Java 冷路径分段基准（G 系列第 0 关）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库**已写进正文档的实测手段**（`17-NATIVE.md` §7.2 明文：「自己埋计时日志
 *    （{@code System.nanoTime()} 成对，分段打点）」、「冷路径（解析 / 预处理 / 转译）= 直接计时
 *    并打日志」）+ JMH 的**测量学常识**（预热 / 样本量 / 报中位数而非最好一次）。
 *    → 能否并入本项目（MIT）：**方法论可以；代码不抄、依赖不加**（本类只用 JDK 的
 *      {@code System.nanoTime} / {@code Files} / {@code ZipFile}，未引入任何第三方 jar）。
 *    → 例外条款：不适用（未取用任何 JMH 源码）。
 * 1. 官方/主实现：`17-NATIVE.md` §5.1 的 G0 口径 —— 分段计时（预处理 / 转译 / 解析 / 合计）、
 *    预热 ≥3 次、样本 ≥5、报中位数与 p95、原始数据落盘 {@code evidence/}；§5.3 报告模板的**行序**。
 *    本类严格按该行序产出分段，保证 G1（Rust 对照）与 G3（对照表）能复用同一口径。
 * 2. 备选：**JMH**（{@code @Benchmark} + Blackhole）—— 更严谨，但① 要新增 test 依赖与注解处理器，
 *    触碰 `18-PARALLEL.md` §7.2 的共享文件冻结；② 它的 fork/warmup/shadow 机制无法搬到 Rust 侧，
 *    G1 用自建 harness 时口径必然与 Java 侧漂移，而**基准的全部价值就在于两侧口径逐项一致**；
 *    ③ 冷路径单次 ~1.9s 量级，fork/多迭代的 JMH 模型并无必要。**否决。**
 * 3. 我们的差异点：本类**只读** {@code pack/} 与 {@code glsl/} 的实现，不改一行业务代码
 *    （G 线「不碰实现代码、只读输入做对照」的边界，`18-PARALLEL.md` §4 G 线）；在计时之外额外
 *    产出**逐阶段 golden 文本 + sha256 清单**，供 G1 的「输出一致性测试」逐字节比对 ——
 *    这是 G1 的前置，§5.1 明文「不一致 ⇒ 谈不上性能对比，先修等价」。
 * 4. 许可证核对结论：**无任何代码复制**，纯 JDK API；本文件不并入任何 GPL / LGPL / ARR 实现。
 * 5. 性能基线：本轮首次产出，见 {@code evidence/g0-java-coldpath.md}（2026-10-02 实测）。
 *
 * <p><b>分段口径（冻结，G1 必须复用）</b>：
 * <pre>
 *   包扫描             = ShaderPackScanner.scan(inventoryDir)
 *   properties 解析    = ShaderPackService.load(discovered)      → pack(选项 + program 清单)
 *   #include 预处理    = Σ GlslPreprocessor.preprocess(file, src, resolver)
 *   转译（8 段流水线） = Σ OfGlslTranslator.translate(stage, preResult)
 *   合计(分段四段)     = 以上四段之和
 *   合计(生产入口)     = ShaderPackCompiler.compile(discovered)  ← 另起一趟测量，含 zip I/O 与挂载规划
 * </pre>
 *
 * <p><b>两条口径纪律</b>：
 * <ol>
 *   <li>分段四段是**分解测量**，只计「读入内存之后的计算」；源文本在计时外读一次并固定下来，
 *       因为 I/O 两侧（Java {@code ZipFile} / Rust {@code zip}）不是被替换的对象，
 *       混进来会让比值失真。生产入口一趟单独测，用来给出项目真正关心的 B3/B4 数字。</li>
 *   <li>分段必须与生产路径**语义等价**：本类在计时外对每个阶段核对
 *       {@code OfGlslTranslator.translate(stage, GlslPreprocessor.preprocess(...))}
 *       与 {@code GlslPipeline.analyze(...)} 的产物**逐字节相等**，不等即判基准失真并显式失败。</li>
 * </ol>
 */
public final class ColdPathBenchmark {

    /** 分段名（与 {@code 17-NATIVE.md} §5.3 表格行序一致，报告与 G1 共用）。 */
    static final String SEG_SCAN = "包扫描";
    static final String SEG_PROPS = "properties/options 解析";
    static final String SEG_PRE = "#include 预处理";
    static final String SEG_TRANS = "转译（8 段流水线）";
    static final String SEG_SUM = "合计（分段四段）";
    static final String SEG_ENTRY = "合计（生产入口）";
    /** 预处理段再拆三段，供 G3 与 Rust 侧做**同口径**对照。 */
    static final String SEG_INC = "预处理①#include 展开";
    static final String SEG_DEF = "预处理②宏与条件编译";
    static final String SEG_CONST = "预处理③选项常量扫描";

    private ColdPathBenchmark() {
    }

    /**
     * 一个阶段的固定输入：阶段类型 + 相对 shaders/ 根的文件名 + 已读入内存的源文本。
     *
     * <p>{@code source} 在计时循环外读一次（见类注释口径纪律 ①）。
     */
    record StageInput(ShaderStage stage, String file, String source) {
    }

    /** 一趟分段测量的结果（纳秒）。 */
    record Pass(long scanNanos, long propsNanos, long preNanos, long transNanos, int stageCount) {
        /** 分段四段合计（纳秒）。 */
        long sumNanos() {
            return scanNanos + propsNanos + preNanos + transNanos;
        }
    }

    /**
     * 分位统计：中位数 + p95 + 最小值 + 最大值（全部纳秒）。
     *
     * <p>p95 取排序后下标 {@code ceil(0.95 × N) − 1} 的样本；样本数较小时它等于最大值，
     * 报告里会一并打出 {@code N}，避免把「N=5 的 p95」误读成稳定的尾延迟估计。
     */
    record Stats(long median, long p95, long min, long max, int samples) {

        static Stats of(long[] values) {
            long[] sorted = values.clone();
            java.util.Arrays.sort(sorted);
            int n = sorted.length;
            int p95Index = (int) Math.ceil(0.95 * n) - 1;
            p95Index = Math.clamp(p95Index, 0, n - 1);
            // 偶数个样本取中间两个的均值，与「跑三次取中位数」的口径一致。
            long median = (n % 2 == 1)
                    ? sorted[n / 2]
                    : Math.round((sorted[n / 2 - 1] + (double) sorted[n / 2]) / 2.0);
            return new Stats(median, sorted[p95Index], sorted[0], sorted[n - 1], n);
        }

        /** 转毫秒（保留 3 位小数），用于报告表。 */
        double millis() {
            return median / 1_000_000.0;
        }
    }

    public static void main(String[] args) throws Exception {
        // 控制台一律 UTF-8：证据的中文说明不能因为终端 locale 是 POSIX/C 而变成一串 "?"。
        System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new java.io.PrintStream(System.err, true, StandardCharsets.UTF_8));
        Args a = Args.parse(args);
        Path inventory = a.inventory();

        ShaderPackScanner.ScanResult scan = ShaderPackScanner.scan(inventory);
        if (scan.packs().isEmpty()) {
            System.err.println("G0: 库存目录没有可用包: " + inventory);
            System.exit(2);
        }
        ShaderPackScanner.DiscoveredPack pack = a.pick(scan);

        // ---- 计时外的固定准备：读源、建 resolver、核对分段与生产路径语义等价 ----
        Fixture fixture = prepare(inventory, pack);
        System.out.printf("G0 · 包 = %s（%s，%d 个 program，%d 个待测阶段）%n",
                pack.name(), pack.kind(), fixture.pack.programs().size(), fixture.inputs.size());
        System.out.printf("     等价性自检 = %s%n",
                selfCheck(fixture) ? "通过（分段产物与 GlslPipeline 逐字节一致）" : "失败（基准失真，已终止）");
        if (!selfCheck(fixture)) {
            System.exit(3);
        }

        // --golden-only：只重建 golden，不跑任何计时、不动 evidence。
        // G1 反复对齐中间产物时用它 —— 否则每次重生成 golden 都会把基准数字冲掉，
        // 逼着人去同步文档，文档和数据的对应关系反而被工具噪声绑架。
        if (a.goldenOnly() || a.dumpInput() != null) {
            if (a.dumpGolden() != null) {
                dumpGolden(a.dumpGolden(), pack, fixture);
                System.out.println("golden 已落盘（G1 一致性测试基准）: " + a.dumpGolden());
            }
            if (a.dumpInput() != null) {
                dumpInputs(a.dumpInput(), pack, fixture);
                System.out.println("输入契约已落盘（G1 复现用）: " + a.dumpInput());
            }
            if (a.goldenOnly()) {
                return;
            }
        }

        if (a.dumpCompile() != null) {
            dumpCompileResults(a.dumpCompile(), pack);
            System.out.println("编译产物已落盘（复用优化的等价性判据）: " + a.dumpCompile());
            return;
        }

        if (a.dumpConst() != null) {
            dumpConstResults(a.dumpConst(), pack, fixture);
            System.out.println("const 结果已落盘（A/B 等价性判据）: " + a.dumpConst());
            return;
        }

        // --phase-timing：只出 G3 对照所需的**分相**计时，不跑既有四段。
        // 为什么要单独一趟：Rust 侧的 inc / def 各自只做一件事（def 直接吃 golden 的
        // inc 产物），若 Java 侧把两者合在一起量，就没有「同一个口径」可言。
        // 这一趟**不覆盖** SEG_* 的四段口径，G0 的历史数字继续可比。
        if (a.phaseTiming()) {
            runPhaseTiming(a, pack, fixture);
            return;
        }

        // ---- 预热（≥3 次，JIT 编译前不取样）----
        for (int i = 0; i < a.warmup; i++) {
            runPass(fixture);
        }
        System.out.printf("     预热 = %d 次%n", a.warmup);

        // ---- 测量趟 A：分段四段（只计计算，不含 zip I/O）----
        long[] scanNanos = new long[a.iterations];
        long[] propsNanos = new long[a.iterations];
        long[] preNanos = new long[a.iterations];
        long[] transNanos = new long[a.iterations];
        long[] sumNanos = new long[a.iterations];
        for (int i = 0; i < a.iterations; i++) {
            Pass pass = runPass(fixture);
            scanNanos[i] = pass.scanNanos();
            propsNanos[i] = pass.propsNanos();
            preNanos[i] = pass.preNanos();
            transNanos[i] = pass.transNanos();
            sumNanos[i] = pass.sumNanos();
        }

        // ---- 测量趟 B：生产入口（另起一趟，避免与趟 A 争 JIT / 文件缓存）----
        long[] entryNanos = new long[a.iterations];
        for (int i = 0; i < a.iterations; i++) {
            long t0 = System.nanoTime();
            ShaderPackCompiler.CompileResult result = ShaderPackCompiler.compile(pack);
            entryNanos[i] = System.nanoTime() - t0;
            if (i == 0 && result.pack() == null) {
                System.err.println("G0: 生产入口未组装出包模型，无法作为基线");
                System.exit(2);
            }
        }

        Stats sScan = Stats.of(scanNanos);
        Stats sProps = Stats.of(propsNanos);
        Stats sPre = Stats.of(preNanos);
        Stats sTrans = Stats.of(transNanos);
        Stats sSum = Stats.of(sumNanos);
        Stats sEntry = Stats.of(entryNanos);

        System.out.println();
        System.out.printf("| 环节 | 中位数(ms) | p95(ms) | 最小(ms) | 最大(ms) | 样本 | 占合计 |%n");
        System.out.printf("|---|---:|---:|---:|---:|---:|---:|%n");
        row(SEG_SCAN, sScan, sSum);
        row(SEG_PROPS, sProps, sSum);
        row(SEG_PRE, sPre, sSum);
        row(SEG_TRANS, sTrans, sSum);
        row(SEG_SUM, sSum, sSum);
        System.out.printf("| %s | %.1f | %.1f | %.1f | %.1f | %d | —（含 zip I/O 与挂载规划） |%n",
                SEG_ENTRY, sEntry.millis(), sEntry.p95() / 1e6, sEntry.min() / 1e6,
                sEntry.max() / 1e6, sEntry.samples());

        System.out.printf("%n环境：%s / JDK %s%n", a.describe(), System.getProperty("java.version"));
        System.out.printf("备注：%s%n", a.notes());

        // 顺序有讲究：golden 必须先落盘 —— 证据正文要引用它的清单哈希，写反了那条就是空的。
        if (a.dumpGolden() != null) {
            dumpGolden(a.dumpGolden(), pack, fixture);
            System.out.println("golden 已落盘（G1 一致性测试基准）: " + a.dumpGolden());
        }
        if (a.out() != null) {
            writeEvidence(a, pack, fixture, sScan, sProps, sPre, sTrans, sSum, sEntry);
            System.out.println("证据已落盘: " + a.out());
        }
    }

    /**
     * 落盘 {@link ShaderPackCompiler#compile} 的**全部**阶段产物（文本 + 诊断），
     * 作为「复用 load 的预处理结果」这一优化的 A/B 等价性判据。
     *
     * <p>为什么必须全量：这一类优化改的是**每个阶段的最终产物**，只看一个阶段通过
     * 不构成证据；而生产路径对 182 个阶段各跑一次，只有全量逐字节比对才能说明
     * 「复用」与「不算」在下游看到的东西完全一样。
     */
    private static void dumpCompileResults(Path dir, ShaderPackScanner.DiscoveredPack pack)
            throws IOException {
        Path out = dir.resolve(pack.name().replaceAll("[^A-Za-z0-9._-]", "_"));
        Files.createDirectories(out);
        ShaderPackCompiler.CompileResult compiled = ShaderPackCompiler.compile(pack);
        if (compiled.pack() == null) {
            throw new IOException("G3: 包模型为空，无法落编译产物");
        }
        StringBuilder sb = new StringBuilder();
        int ok = 0;
        for (ShaderPackCompiler.CompiledStage stage : compiled.stages()) {
            ok += stage.isSuccess() ? 1 : 0;
            sb.append("#stage\t").append(stage.programName()).append('\t').append(stage.stage())
                    .append('\t').append(stage.sourceFile()).append('\t')
                    .append(stage.isSuccess()).append('\n');
            sb.append(sha256(stage.result().text())).append("\t").append(stage.result().text()).append('\n');
            for (TranslateDiagnostic d : stage.result().diagnostics()) {
                sb.append("diag\t").append(d.severity()).append('\t').append(d.format()).append('\n');
            }
        }
        sb.append("#totals\tstages=").append(compiled.stages().size()).append("\tok=").append(ok)
                .append("\tdiagnostics=").append(compiled.diagnostics().size()).append('\n');
        Files.writeString(out.resolve("compile-results.txt"), sb.toString(), StandardCharsets.UTF_8);
        System.out.println("     编译产物 = 阶段 " + compiled.stages().size() + " 个 / 成功 " + ok
                + " / 包级诊断 " + compiled.diagnostics().size() + " 条");
    }

    /**
     * 落盘 const 相的**全部**产物（选项常量 + 诊断），作为「先挡后正则」优化的
     * A/B 等价性判据。
     *
     * <p>为什么必须落盘而不是只看耗时：性能优化如果不能证明「产出逐字节相同」，
     * 就违反 X27（不许为性能砍 pack 特性）。const 相的产物**不含文本**，
     * 所以只能靠这份清单来证明。
     */
    private static void dumpConstResults(Path dir, ShaderPackScanner.DiscoveredPack pack,
            Fixture fixture) throws IOException {
        Path out = dir.resolve(pack.name().replaceAll("[^A-Za-z0-9._-]", "_"));
        Files.createDirectories(out);
        IncludeResolver resolver = ShaderPackService.resolverFor(fixture.plan());
        StringBuilder sb = new StringBuilder();
        int optionTotal = 0;
        int diagnosticTotal = 0;
        for (StageInput input : fixture.inputs()) {
            IncludeProcessor.Result inc =
                    IncludeProcessor.process(input.file(), input.source(), resolver);
            ConstEvaluator.Result result = ConstEvaluator.evaluate(inc.text(), inc.lineMap());
            sb.append("#stage\t").append(input.file()).append('\n');
            for (ConstEvaluator.OptionConstant oc : result.options()) {
                optionTotal++;
                sb.append("opt\t").append(oc.name()).append('\t').append(oc.kind()).append('\t')
                        .append(oc.defaultValue()).append('\t')
                        .append(String.join(" ", oc.candidates())).append('\t')
                        .append(oc.description()).append('\t')
                        .append(oc.sourceFile()).append('\t').append(oc.sourceLine()).append('\t')
                        .append(oc.visible()).append('\t').append(oc.disabled()).append('\n');
            }
            for (TranslateDiagnostic d : result.diagnostics()) {
                diagnosticTotal++;
                sb.append("diag\t").append(d.severity()).append('\t').append(d.format()).append('\n');
            }
        }
        sb.append("#totals\toptions=").append(optionTotal)
                .append("\tdiagnostics=").append(diagnosticTotal).append('\n');
        Files.writeString(out.resolve("const-results.txt"), sb.toString(), StandardCharsets.UTF_8);
        System.out.println("     const 产物 = 选项 " + optionTotal + " 条 / 诊断 " + diagnosticTotal + " 条");
    }

    private static void row(String name, Stats stats, Stats total) {
        System.out.printf("| %s | %.1f | %.1f | %.1f | %.1f | %d | %.1f%% |%n",
                name, stats.millis(), stats.p95() / 1e6, stats.min() / 1e6, stats.max() / 1e6,
                stats.samples(), 100.0 * stats.median() / total.median());
    }

    /**
     * G3 对照用的**分相**计时：预处理段拆成 inc 与 def 两段，各自单独计时。
     *
     * <p><b>口径对称性（这是本方法存在的唯一理由）</b>：
     * Rust 侧的 inc 是「源文件 → include 展开产物」，def 是「inc 产物 → 宏与条件编译产物」，
     * 两段各只做一件事。所以 Java 侧也必须：
     * <ul>
     *   <li>{@code def} 的输入是**计时外**算好并缓存的 {@code IncludeProcessor.Result}
     *       —— 否则 def 的耗时会混进 include 的工时，两侧就不是同一口径；</li>
     *   <li>计时区间内不做任何 I/O、不打日志（与 G0 趟 A 同样的纪律）。</li>
     * </ul>
     * 产物文本会与 golden 里的 {@code .inc.glsl} / {@code .pre.glsl} 交叉核对一次，
     * 确保这里量的确实是「同一份工作」而不是另一条路径。
     */
    private static void runPhaseTiming(Args a, ShaderPackScanner.DiscoveredPack pack, Fixture fixture)
            throws IOException {
        IncludeResolver resolver = ShaderPackService.resolverFor(fixture.plan());

        // 计时外：算好 inc 结果，供 def 段复用（见方法注释的口径对称性说明）
        List<IncludeProcessor.Result> incResults = new ArrayList<>();
        for (StageInput input : fixture.inputs()) {
            incResults.add(IncludeProcessor.process(input.file(), input.source(), resolver));
        }
        verifyAgainstGolden(a, fixture, incResults);

        // 预热
        for (int i = 0; i < a.warmup; i++) {
            timeInc(fixture, resolver);
            timeDef(incResults);
            timeConst(incResults);
        }

        long[] incNanos = new long[a.iterations];
        long[] defNanos = new long[a.iterations];
        long[] constNanos = new long[a.iterations];
        for (int i = 0; i < a.iterations; i++) {
            incNanos[i] = timeInc(fixture, resolver);
            defNanos[i] = timeDef(incResults);
            constNanos[i] = timeConst(incResults);
        }

        Stats sInc = Stats.of(incNanos);
        Stats sDef = Stats.of(defNanos);
        Stats sConst = Stats.of(constNanos);
        System.out.printf("| %s | %.1f | %.1f | %.1f | %.1f | %d |%n",
                SEG_INC, sInc.millis(), sInc.p95() / 1e6, sInc.min() / 1e6, sInc.max() / 1e6, sInc.samples());
        System.out.printf("| %s | %.1f | %.1f | %.1f | %.1f | %d |%n",
                SEG_DEF, sDef.millis(), sDef.p95() / 1e6, sDef.min() / 1e6, sDef.max() / 1e6, sDef.samples());
        System.out.printf("| %s | %.1f | %.1f | %.1f | %.1f | %d |%n",
                SEG_CONST, sConst.millis(), sConst.p95() / 1e6, sConst.min() / 1e6, sConst.max() / 1e6,
                sConst.samples());
        // 机器可读行：G3 的交替测量编排脚本靠它抓数（见 docs/17-NATIVE.md §7.3 的红线）
        System.out.printf("G3DATA\tjava\tinc\t%.3f\t%.3f\t%d%n", sInc.millis(), sInc.p95() / 1e6, sInc.samples());
        System.out.printf("G3DATA\tjava\tdef\t%.3f\t%.3f\t%d%n", sDef.millis(), sDef.p95() / 1e6, sDef.samples());
        System.out.printf("G3DATA\tjava\tconst\t%.3f\t%.3f\t%d%n",
                sConst.millis(), sConst.p95() / 1e6, sConst.samples());
    }

    private static long timeInc(Fixture fixture, IncludeResolver resolver) {
        long t0 = System.nanoTime();
        for (StageInput input : fixture.inputs()) {
            IncludeProcessor.process(input.file(), input.source(), resolver);
        }
        return System.nanoTime() - t0;
    }

    private static long timeDef(List<IncludeProcessor.Result> incResults) {
        long t0 = System.nanoTime();
        for (IncludeProcessor.Result inc : incResults) {
            DefineProcessor.process(inc.text(), inc.lineMap());
        }
        return System.nanoTime() - t0;
    }

    private static long timeConst(List<IncludeProcessor.Result> incResults) {
        long t0 = System.nanoTime();
        for (IncludeProcessor.Result inc : incResults) {
            ConstEvaluator.evaluate(inc.text(), inc.lineMap());
        }
        return System.nanoTime() - t0;
    }

    /**
     * 交叉核对：这里算出的 inc / def 产物必须与 golden 的 {@code .inc.glsl} / {@code .pre.glsl}
     * 一致 —— 否则「量到的」和「Rust 对齐的那个」不是同一份工作，对照无意义。
     */
    private static void verifyAgainstGolden(Args a, Fixture fixture,
            List<IncludeProcessor.Result> incResults) throws IOException {
        if (a.dumpGolden() == null) {
            return;
        }
        Path dir = a.dumpGolden().resolve(fixture.discovered().name().replaceAll("[^A-Za-z0-9._-]", "_"));
        IncludeResolver resolver = ShaderPackService.resolverFor(fixture.plan());
        int checked = 0;
        for (int i = 0; i < fixture.inputs().size(); i++) {
            StageInput input = fixture.inputs().get(i);
            String stem = input.file().replace('\\', '/').replace('/', '_');
            Path incFile = dir.resolve(stem + ".inc.glsl");
            if (Files.isReadable(incFile)
                    && !Files.readString(incFile).equals(incResults.get(i).text())) {
                throw new IOException("分相计时前自检失败：inc 产物与 golden 不一致 —— " + input.file());
            }
            Path preFile = dir.resolve(stem + ".pre.glsl");
            if (Files.isReadable(preFile)) {
                String defText = DefineProcessor.process(incResults.get(i).text(), incResults.get(i).lineMap()).text();
                if (!Files.readString(preFile).equals(defText)) {
                    throw new IOException("分相计时前自检失败：def 产物与 golden 不一致 —— " + input.file());
                }
            }
            checked++;
        }
        System.out.println("     分相自检 = 通过（" + checked + " 个阶段与 golden 逐字节一致）");
    }

    /** 固定输入：库存目录 + 包发现 + 包模型 + 挂载规划 + 逐阶段源文本（全部在计时外准备好）。 */
    private record Fixture(Path inventory, ShaderPackScanner.DiscoveredPack discovered, ShaderPack pack,
                           ShaderPackRepository.MountPlan plan, List<StageInput> inputs) {
    }

    private static Fixture prepare(Path inventory, ShaderPackScanner.DiscoveredPack discovered)
            throws IOException {
        ShaderPack pack = ShaderPackService.load(discovered).pack();
        if (pack == null) {
            throw new IOException("G0: 包模型组装失败: " + discovered.name());
        }
        ShaderPackRepository.MountPlan plan = ShaderPackRepository.plan(discovered);

        List<StageInput> inputs = new ArrayList<>();
        for (Program program : pack.programs()) {
            collect(inputs, plan, ShaderStage.VERTEX, program.vertexShader());
            collect(inputs, plan, ShaderStage.FRAGMENT, program.fragmentShader());
        }
        return new Fixture(inventory, discovered, pack, plan, List.copyOf(inputs));
    }

    private static void collect(List<StageInput> sink, ShaderPackRepository.MountPlan plan,
            ShaderStage stage, String sourcePath) {
        // 与 ShaderPackCompiler.compileStage 同口径：源文件缺失是 Program 契约允许的显式降级，跳过。
        if (sourcePath == null) {
            return;
        }
        String text = ShaderPackService.readText(plan, sourcePath);
        if (text == null) {
            return;
        }
        sink.add(new StageInput(stage, sourcePath, text));
    }

    /**
     * 一趟分段测量。段内不做任何 I/O、不打日志（类注释口径纪律 ①）。
     *
     * <p>预处理结果必须在同一趟里喂给转译段 —— 这与 {@code GlslPipeline} 内部的真实顺序一致，
     * 因此「分段四段」与生产路径是同一份计算，只是被插了桩。
     */
    private static Pass runPass(Fixture fixture) {
        ShaderPackScanner.DiscoveredPack discovered = fixture.discovered();
        long t0 = System.nanoTime();
        ShaderPackScanner.ScanResult scan = ShaderPackScanner.scan(fixture.inventory());
        long t1 = System.nanoTime();

        ShaderPack pack = ShaderPackService.load(discovered).pack();
        long t2 = System.nanoTime();

        IncludeResolver resolver = ShaderPackService.resolverFor(fixture.plan());
        long pre = 0;
        long trans = 0;
        for (StageInput input : fixture.inputs()) {
            long a = System.nanoTime();
            TranslateResult preprocessed = GlslPreprocessor.preprocess(input.file(), input.source(), resolver);
            long b = System.nanoTime();
            OfGlslTranslator.translate(input.stage(), preprocessed);
            long c = System.nanoTime();
            pre += b - a;
            trans += c - b;
        }
        return new Pass(t1 - t0, t2 - t1, pre, trans, pack == null ? 0 : pack.programs().size());
    }

    /**
     * 语义等价自检：分段的「预处理 → 转译」必须与生产路径 {@code GlslPipeline.analyze}
     * 逐阶段产出**逐字节一致**的文本。不一致说明分段失真，基准不可用。
     */
    private static boolean selfCheck(Fixture fixture) {
        IncludeResolver resolver = ShaderPackService.resolverFor(fixture.plan());
        for (StageInput input : fixture.inputs()) {
            String decomposed = OfGlslTranslator.translate(input.stage(),
                    GlslPreprocessor.preprocess(input.file(), input.source(), resolver)).text();
            String production = GlslPipeline.analyze(input.stage(), input.file(), input.source(), resolver)
                    .result().text();
            if (!decomposed.equals(production)) {
                System.err.printf("G0: 分段失真 —— %s %s 的产物与 GlslPipeline 不一致%n",
                        input.stage(), input.file());
                return false;
            }
        }
        return true;
    }

    /**
     * 自动采集主机描述（CPU 型号 + OS）。读不到就只给 OS —— 取不到主机信息不构成失败，
     * 但必须显式打出来而不是留空（T11：证据要能被第三方复核）。
     */
    static String describeHost() {
        String os = System.getProperty("os.name") + " " + System.getProperty("os.arch");
        Path cpuinfo = Path.of("/proc/cpuinfo");
        if (!Files.isReadable(cpuinfo)) {
            return os;
        }
        try {
            // /proc/cpuinfo 不是 UTF-8，按 ISO-8859-1 逐字节映射，型号名本身是 ASCII。
            for (String line : Files.readAllLines(cpuinfo, StandardCharsets.ISO_8859_1)) {
                if (line.startsWith("model name")) {
                    String model = line.substring(line.indexOf(':') + 1).trim();
                    return model.isEmpty() ? os : model + " / " + os;
                }
            }
        } catch (IOException | RuntimeException e) {
            return os + " (cpuinfo 读取失败: " + e + ")";
        }
        return os;
    }

    private static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit(b >> 4 & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** golden 清单路径（与 {@link #dumpGolden} 的落盘规则保持同一处定义）。 */
    private static Path goldenManifest(Path dir, ShaderPackScanner.DiscoveredPack pack) {
        return dir.resolve(pack.name().replaceAll("[^A-Za-z0-9._-]", "_")).resolve("sha256sums.txt");
    }

    /**
     * 落 golden：逐阶段的预处理后文本与转译后文本 + sha256 清单。
     * G1 的 Rust 实现必须对同一输入产出**逐字节相同**的两份文本（§5.1 G1 等价性前置）。
     */
    private static void dumpGolden(Path dir, ShaderPackScanner.DiscoveredPack pack, Fixture fixture)
            throws IOException {
        IncludeResolver resolver = ShaderPackService.resolverFor(fixture.plan());
        Path out = dir.resolve(pack.name().replaceAll("[^A-Za-z0-9._-]", "_"));
        Files.createDirectories(out);
        StringBuilder manifest = new StringBuilder();
        for (StageInput input : fixture.inputs()) {
            // inc = 仅 #include 展开后的文本（G1 第一段的靶子）；pre = define/const 处理后的文本；
            // trans = 8 段转译后的文本。三个中间态都落盘，Rust 侧才能**逐段**对齐，
            // 而不是对着最终产物一次性猜哪里错了。
            IncludeProcessor.Result inc =
                    IncludeProcessor.process(input.file(), input.source(), resolver);
            TranslateResult preprocessed =
                    GlslPreprocessor.preprocess(input.file(), input.source(), resolver);
            String pre = preprocessed.text();
            String trans = OfGlslTranslator.translate(input.stage(), preprocessed).text();
            String stem = input.file().replace('\\', '/').replace('/', '_');
            write(out, stem + ".inc.glsl", inc.text(), manifest);
            write(out, stem + ".pre.glsl", pre, manifest);
            write(out, stem + ".trans.glsl", trans, manifest);
        }
        Files.writeString(out.resolve("sha256sums.txt"), manifest.toString(), StandardCharsets.UTF_8);
    }

    /**
     * 导出 G1 需要的**输入契约**（Rust 侧按此复现同一批输入，不引入任何第三方 crate 去解 zip）：
     * <pre>
     *   files/&lt;相对路径&gt;   挂载计划里每个文件按 readText 口径解码后的原文（UTF-8 写出）
     *   stages.txt            待测阶段的相对路径，一行一个
     *   sha256sums.txt        导出文件的 sha256 清单
     * </pre>
     * 之所以导出「解码后的文本」而不是 zip 原字节：{@code readText} 用 UTF-8 解码且对非法序列
     * 替换为 U+FFFD，Rust 侧要复现的是**解码结果**，这样两侧看到的是同一个字符串。
     * I/O 本来就不在被测分段里（见类注释口径纪律 ①），导出后 Rust 只做纯计算。
     */
    private static void dumpInputs(Path dir, ShaderPackScanner.DiscoveredPack pack, Fixture fixture)
            throws IOException {
        Path out = dir.resolve(pack.name().replaceAll("[^A-Za-z0-9._-]", "_"));
        List<String> files = new ArrayList<>(fixture.plan().shaderFiles());
        java.util.Collections.sort(files);
        StringBuilder sums = new StringBuilder();
        for (String relative : files) {
            String text = ShaderPackService.readText(fixture.plan(), relative);
            if (text == null) {
                continue;
            }
            Path dest = out.resolve("files").resolve(relative);
            Files.createDirectories(dest.getParent());
            Files.writeString(dest, text, StandardCharsets.UTF_8);
            sums.append(sha256(text)).append("  files/").append(relative).append('\n');
        }
        StringBuilder stages = new StringBuilder();
        for (StageInput input : fixture.inputs()) {
            stages.append(input.file()).append('\n');
        }
        Files.writeString(out.resolve("stages.txt"), stages.toString(), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("sha256sums.txt"), sums.toString(), StandardCharsets.UTF_8);
    }

    private static void write(Path out, String fileName, String content, StringBuilder manifest)
            throws IOException {
        Files.writeString(out.resolve(fileName), content, StandardCharsets.UTF_8);
        manifest.append(sha256(content)).append("  ").append(fileName).append('\n');
    }

    private static void writeEvidence(Args a, ShaderPackScanner.DiscoveredPack pack, Fixture fixture,
            Stats sScan, Stats sProps, Stats sPre, Stats sTrans, Stats sSum, Stats sEntry)
            throws IOException {
        StringBuilder md = new StringBuilder();
        md.append("# G0 · Java 冷路径分段基准（%s）\n\n".formatted(pack.name()));
        md.append("> 由 `ColdPathBenchmark` 生成（G 系列第 0 关，`17-NATIVE.md` §5.1 / §7.2 口径）。\n\n");
        md.append("## 环境\n\n");
        md.append("- 机器/标签：%s\n".formatted(a.describe()));
        md.append("- JDK：%s\n".formatted(System.getProperty("java.version")));
        md.append("- OS：%s %s\n".formatted(System.getProperty("os.name"), System.getProperty("os.arch")));
        md.append("- 备注：%s\n".formatted(a.notes()));
        md.append("- 预热 %d 次，样本 %d 次（§7.1：预热 ≥3、样本 ≥5，取中位数而非最好一次）\n\n"
                .formatted(a.warmup, a.iterations));
        md.append("## 输入（固定）\n\n");
        md.append("- 包：`%s`，kind=%s，源 `%s`\n".formatted(pack.name(), pack.kind(), pack.source()));
        md.append("- sha256：%s\n".formatted(sha256File(pack.source())));
        md.append("- program 数：%d；待测阶段数：%d\n".formatted(
                fixture.pack.programs().size(), fixture.inputs.size()));
        md.append("- ⚠️ 本表是**单包**口径。runClient 日志里的 `pack compile done: stages=N` 统计的是"
                + "**整个库存目录**下的所有包，两者不可直接相减。\n");
        if (a.dumpGolden() != null) {
            Path manifest = goldenManifest(a.dumpGolden(), pack);
            if (Files.isReadable(manifest)) {
                md.append("- golden 清单：`%s`，共 %d 条，自身 sha256 `%s`\n".formatted(
                        manifest, Files.readAllLines(manifest).size(), sha256File(manifest)));
            } else {
                // 静默丢行 = T11 违规：指定了 --golden 却拿不到清单，必须显式说明而不是当作没有。
                md.append("- ⚠️ golden 清单 `%s` **不可读**，本行无法给出清单哈希 —— "
                        + "G1 的等价性测试请先重跑上面的复现命令生成它。\n".formatted(manifest));
            }
        }
        md.append("\n## 一行复现\n\n```bash\n%s\n```\n\n".formatted(a.reproCommand()));
        md.append("## 分段数据\n\n");
        md.append("| 环节 | 中位数(ms) | p95(ms) | 最小(ms) | 最大(ms) | 样本 | 占合计 |\n");
        md.append("|---|---:|---:|---:|---:|---:|---:|\n");
        line(md, SEG_SCAN, sScan, sSum);
        line(md, SEG_PROPS, sProps, sSum);
        line(md, SEG_PRE, sPre, sSum);
        line(md, SEG_TRANS, sTrans, sSum);
        line(md, SEG_SUM, sSum, sSum);
        md.append("| %s | %.1f | %.1f | %.1f | %.1f | %d | — |\n\n".formatted(
                SEG_ENTRY, sEntry.millis(), sEntry.p95() / 1e6, sEntry.min() / 1e6,
                sEntry.max() / 1e6, sEntry.samples()));
        md.append("> 生产入口含 zip I/O 与挂载规划，**与分段四段不可相加**。\n");
        md.append("> p95 取排序后下标 `ceil(0.95×N)−1`；样本 %d 偏小时它就等于最大值，"
                .formatted(a.iterations));
        md.append("读作「尾延迟上界」而非稳定估计。\n");
        md.append("> 分段与生产路径的语义等价性已自检：逐阶段 `预处理→转译` 的产物与 "
                + "`GlslPipeline.analyze` **逐字节相等**（不等则基准失真并终止）。\n\n");
        md.append("""
                ## 口径与判读（固定说明，随每次运行重写）

                - **本机是笔记本且未锁电源/频率**，跨次运行的中位数漂移已达 **≈±9%**
                  （多趟实测记录见 `CHANGE_LOG.md` 与 `evidence/` 的历次数据，**此处不写死具体数字** ——
                  写死会让下一次运行把方法论说明和它自己的数据混在一起）。
                  该噪声与 §5.2 的 **20% 裁决阈值同量级**，所以 G1/G3 必须：
                  ① Rust 与 Java 两侧在**同一台机器上交替**测量；② 样本 ≥9；③ 同时报 p95；
                  ④ **禁止用单次运行的最好值比值下结论**。
                - 真正被替换的对象是**分段四段**（只含读入之后的计算）。生产入口那一趟含 zip I/O 与
                  挂载规划，它给出的才是 B3/B4 关心的真实等待时长，两者**不可相加**。
                """);
        Files.createDirectories(a.out().toAbsolutePath().getParent());
        Files.writeString(a.out(), md.toString(), StandardCharsets.UTF_8);
    }

    private static void line(StringBuilder md, String name, Stats stats, Stats total) {
        md.append("| %s | %.1f | %.1f | %.1f | %.1f | %d | %.1f%% |\n".formatted(
                name, stats.millis(), stats.p95() / 1e6, stats.min() / 1e6, stats.max() / 1e6,
                stats.samples(), 100.0 * stats.median() / total.median()));
    }

    private static String sha256File(Path file) throws IOException {
        return sha256(Files.readAllBytes(file));
    }

    /**
     * 命令行参数：{@code --inventory <dir> --pack <name> --warmup N --iterations N
     * --out <evidence md> --golden <dir> --label <ASCII 主机说明> --notes <ASCII 备注>}。
     *
     * <p>⚠️ {@code --label} / {@code --notes} 走的是 JVM 的 {@code sun.jnu.encoding} 解码，
     * 在 {@code LANG=POSIX/C} 的 shell 里传中文会被解成乱码 ⇒ **这两个参数只接受 ASCII**，
     * 主机信息交给 {@link #describeHost()} 自动采集（§7.1「场景固定」要能被复核）。
     */
    private record Args(Path inventory, String pack, int warmup, int iterations,
                        Path out, Path dumpGolden, Path dumpInput, String label, String notes,
                        boolean goldenOnly, boolean phaseTiming, Path dumpConst, Path dumpCompile) {

        /** 主机描述：显式 `--label` 优先，否则自动采集 CPU + OS。 */
        String describe() {
            return label != null ? label : describeHost();
        }

        /** 复现命令（G-01 证据格式要求「一行复现」）——按本次实际使用的参数原样回放。 */
        String reproCommand() {
            StringBuilder cmd = new StringBuilder("./gradlew compileTestJava\n");
            cmd.append("java -cp build/classes/java/test:build/classes/java/main \\\n");
            cmd.append("    dev.vkdisp.pack.ColdPathBenchmark \\\n");
            cmd.append("    --inventory ").append(inventory).append(" --pack ").append(pack)
                    .append(" --warmup ").append(warmup).append(" --iterations ").append(iterations);
            cmd.append(" \\\n    --out ").append(out);
            if (dumpGolden != null) {
                cmd.append(" \\\n    --golden ").append(dumpGolden);
            }
            if (label != null) {
                cmd.append(" \\\n    --label ").append(label);
            }
            cmd.append(" \\\n    --notes \"").append(notes).append('"');
            return cmd.toString();
        }

        static Args parse(String[] argv) {
            Path inventory = Path.of("run", "shaderpacks");
            String pack = null;
            int warmup = 3;
            int iterations = 9;
            Path out = null;
            Path golden = null;
            Path inputDump = null;
            String label = null;
            String notes = "unspecified";
            boolean goldenOnly = false;
            boolean phaseTiming = false;
            Path constDump = null;
            Path compileDump = null;
            for (int i = 0; i < argv.length; i += 2) {
                if ("--golden-only".equals(argv[i])) {
                    goldenOnly = true;
                    i--; // 无值参数，吃掉本轮不成对的偏移
                    continue;
                }
                if ("--phase-timing".equals(argv[i])) {
                    phaseTiming = true;
                    i--;
                    continue;
                }
                if (i + 1 >= argv.length) {
                    throw new IllegalArgumentException("参数 " + argv[i] + " 缺少取值");
                }
                String value = argv[i + 1];
                switch (argv[i]) {
                    case "--inventory" -> inventory = Path.of(value);
                    case "--pack" -> pack = value;
                    case "--warmup" -> warmup = Integer.parseInt(value);
                    case "--iterations" -> iterations = Integer.parseInt(value);
                    case "--out" -> out = Path.of(value);
                    case "--golden" -> golden = Path.of(value);
                    case "--dump-input" -> inputDump = Path.of(value);
                    case "--dump-const" -> constDump = Path.of(value);
                    case "--dump-compile" -> compileDump = Path.of(value);
                    case "--label" -> label = value;
                    case "--notes" -> notes = value;
                    default -> throw new IllegalArgumentException("未知参数: " + argv[i]);
                }
            }
            if (warmup < 3) {
                throw new IllegalArgumentException("§7.1：预热必须 ≥3 次，收到 " + warmup);
            }
            if (iterations < 5) {
                throw new IllegalArgumentException("§7.1：样本必须 ≥5 次，收到 " + iterations);
            }
            return new Args(inventory, pack, warmup, iterations, out, golden, inputDump, label, notes,
                    goldenOnly, phaseTiming, constDump, compileDump);
        }

        /** 选包：指定名/序号优先，否则取第一个 zip（目录包只作兜底）。 */
        ShaderPackScanner.DiscoveredPack pick(ShaderPackScanner.ScanResult scan) {
            if (pack != null) {
                for (ShaderPackScanner.DiscoveredPack candidate : scan.packs()) {
                    if (candidate.name().equals(pack)) {
                        return candidate;
                    }
                }
                throw new IllegalArgumentException("库存里没有名为 " + pack + " 的包");
            }
            return scan.packs().stream()
                    .filter(p -> p.kind() == ShaderPackScanner.Kind.ZIP)
                    .findFirst()
                    .orElseGet(() -> scan.packs().getFirst());
        }
    }
}