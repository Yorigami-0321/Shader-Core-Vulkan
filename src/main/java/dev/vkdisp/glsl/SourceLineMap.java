package dev.vkdisp.glsl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 【参考调研】F3 行号映射契约 / 04-SPEC §3.2 与 08-TESTING §4 对 #include 行号映射的事实性要求
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/18-PARALLEL.md §4 C 线完成标准「行号映射保留（编译报错要能指回
 *    原文件）」与 docs/08-TESTING.md §4「#include 按 OF 语义解析（相对路径、可嵌套）」——
 *    仓库内事实性需求，不受版权保护。外部候选 IrisShaders/glsl-preprocessor（GPL-3.0 +
 *    例外条款）→ 例外是否覆盖本项目未核实，一律按禁止处理（17-NATIVE §1.1.1 陷阱 2），
 *    本任务不读其代码、零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 契约类，不含任何外部项目代码。
 *    → 能否并入本项目（MIT）：可以 —— 只采纳不受版权保护的事实性信息（格式语义、行号要求）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：本仓库 18-PARALLEL §3 F3 + §4 C/D 线定义 —— C 线登记"展开后每行的起源"，
 *    D 线拿上游映射把重写诊断指回原文件；事实性来源 = 04-SPEC §3.2（glsl/ 组件清单）、
 *    08-TESTING §4（#include 与选项常量识别的验收要求）。
 * 2. 备选：javac / GLSL 校验器的 "文件:行" 单文件 line map —— 只借"行号从 1 起、
 *    位置用 file:line 表示"这一事实性约定，不用任何编译器类型、不复制任何代码。
 * 3. 我们的差异点：预处理器的"输出行 → 起源"天然是多文件的（#include 展开后一行可能来自
 *    另一个文件），且 C→D 是两级流水线，需要把两级映射 compose 成端到端映射；
 *    通用编译器的单文件 line map 不覆盖这两点，必须自建。
 * 4. 许可证核对结论：同 TranslateDiagnostic —— 参考按禁止处理，只读思路，本文件独立编写，
 *    零代码并入（07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径；正/反向查询均为线性扫描（清晰优先，18-PARALLEL §7.7）。
 *    08-TESTING §8「解析+转译全部 program ≤ 1 秒」由 P2 主线实测把关，本契约不预优化（T14）。
 */
/**
 * 原文件行号 ↔ 输出行号 的映射（闸门 F3 冻结契约的一部分）。
 *
 * <p><b>冻结契约，变更走 18-PARALLEL §3.2（经 env-1 统一改）。</b>
 *
 * <p>每个实例回答两个方向的问题：
 * <ul>
 *   <li>{@link #originOf(int)}：输出行 → 起源（原文件 + 原行号）—— 编译 / 转译报错指回原文件
 *       （C 线"行号映射保留"与 D 线"attribute/varying 重写诊断"都走它）；</li>
 *   <li>{@link #outputLineOf(int)} / {@link #outputLineOf(String, int)}：源行号 → 输出行号
 *       （至少逐行查询，返回首个命中；未命中返回 {@link TranslateDiagnostic#UNKNOWN_LINE}）。</li>
 * </ul>
 *
 * <p><b>坐标约定</b>：行号一律从 1 起；{@code 0}
 * （{@link TranslateDiagnostic#UNKNOWN_LINE}）= 未知 / 合成行 / 查询未命中。
 *
 * <p><b>null 语义</b>：查询方法永不返回 null —— {@link #originOf(int)} 越界时返回
 * {@code (primaryFile, UNKNOWN_LINE)}；{@code primaryFile == null} 表示原文件未知。
 * {@link #unmapped()} 是缺省空映射（0 行，查询恒未命中），{@link TranslateResult} 用它归一
 * {@code null} 入参，调用方无需任何判空。
 *
 * <p><b>C / D 背靠背用法</b>（两线都只依赖本契约，互不修改）：
 * <pre>{@code
 * // C 线：#include 展开时逐行登记起源
 * SourceLineMap cMap = SourceLineMap.builder("shaders/a.glsl")
 *         .addIdentityRange(1, 2)      // 前 2 行原样来自 a.glsl 第 1..2 行
 *         .add("shaders/common.glsl", 1) // 展开行来自被包含文件
 *         .addIdentityRange(4, 5)      // 其余原样
 *         .build();
 *
 * // D 线：登记自己的输出行 →（C 输出行），再与 C 的映射合成端到端映射
 * SourceLineMap dMap = SourceLineMap.builder(null).addIdentityRange(1, 8).build();
 * SourceLineMap endToEnd = dMap.compose(cMap);
 * }</pre>
 */
public final class SourceLineMap {

    /** 空映射（共享实例）：没有任何输出行记录，所有查询未命中。 */
    private static final SourceLineMap UNMAPPED = new SourceLineMap(null, new String[0], new int[0]);

    /** 主文件（本阶段入口文件）名；null = 未知。 */
    private final String primaryFile;

    /** 每条输出行的起源文件（与 lines 等长）；元素 null = 文件未知。 */
    private final String[] files;

    /** 每条输出行的起源行号（与 files 等长）；0 = 未知 / 合成行。 */
    private final int[] lines;

    private SourceLineMap(String primaryFile, String[] files, int[] lines) {
        this.primaryFile = primaryFile;
        this.files = files;
        this.lines = lines;
    }

    // ------------------------------------------------------------------ 工厂

    /** 空映射：查询恒未命中（结果对象未携带映射时的归一值）。 */
    public static SourceLineMap unmapped() {
        return UNMAPPED;
    }

    /**
     * 单文件逐行恒等映射：输出第 i 行 ← 原文件第 i 行。
     *
     * <p>无 {@code #include}、行数未被改动时的缺省（C 线无包含、D 线未增删行都可用）。
     *
     * @param sourceFile 原文件名（可为 null = 文件未知）
     * @param lineCount  行数（{@code <= 0} 视为 0 行）
     */
    public static SourceLineMap identity(String sourceFile, int lineCount) {
        int count = Math.max(0, lineCount);
        String[] files = new String[count];
        int[] lines = new int[count];
        for (int i = 0; i < count; i++) {
            files[i] = sourceFile;
            lines[i] = i + 1;
        }
        return new SourceLineMap(sourceFile, files, lines);
    }

    /**
     * 逐行登记器：按输出行顺序追加起源。
     *
     * <p>冷路径，清晰优先：不做预分配、不做去重（18-PARALLEL §7.7）。
     *
     * @param primaryFile 主文件名（本阶段入口文件；D 线处理"C 的输出"时传 null 即可）
     */
    public static Builder builder(String primaryFile) {
        return new Builder(primaryFile);
    }

    // ------------------------------------------------------------------ 查询

    /** 主文件名（本阶段入口文件）；null = 未知。 */
    public String primaryFile() {
        return primaryFile;
    }

    /** 本映射覆盖的输出行数（输出文本的行数）。 */
    public int outputLineCount() {
        return lines.length;
    }

    /**
     * 输出行 → 起源（原文件 + 原行号）。
     *
     * <p>永不返回 null；{@code outputLine} 越界（&lt; 1 或 &gt; {@link #outputLineCount()}）
     * 时返回 {@code (primaryFile, UNKNOWN_LINE)}。
     */
    public LineOrigin originOf(int outputLine) {
        if (outputLine < 1 || outputLine > lines.length) {
            return new LineOrigin(primaryFile, TranslateDiagnostic.UNKNOWN_LINE);
        }
        return new LineOrigin(files[outputLine - 1], lines[outputLine - 1]);
    }

    /**
     * 主文件内：源行号 → 输出行号（逐行查询，返回首个命中）。
     *
     * <p>未命中（含 {@code sourceLine <= 0}）返回 {@link TranslateDiagnostic#UNKNOWN_LINE}。
     */
    public int outputLineOf(int sourceLine) {
        return outputLineOf(primaryFile, sourceLine);
    }

    /**
     * 指定文件内：源行号 → 输出行号（文件精确匹配，返回首个命中）。
     *
     * @param sourceFile 目标文件；null = 按主文件查
     * @return 输出行号（1 起）；未命中返回 {@link TranslateDiagnostic#UNKNOWN_LINE}
     */
    public int outputLineOf(String sourceFile, int sourceLine) {
        if (sourceLine <= TranslateDiagnostic.UNKNOWN_LINE) {
            return TranslateDiagnostic.UNKNOWN_LINE;
        }
        String file = sourceFile != null ? sourceFile : primaryFile;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i] == sourceLine && Objects.equals(files[i], file)) {
                return i + 1;
            }
        }
        return TranslateDiagnostic.UNKNOWN_LINE;
    }

    /**
     * 链式合成（C → D 背靠背的关键方法）：把"本阶段：输出行 → 本阶段输入行"的映射与
     * "上游：输入行 → 原始文件行"的映射合成端到端映射（本阶段输出行 → 原始文件行）。
     *
     * <p>典型：D 线的 {@code dMap.compose(cMap)}，其中 {@code cMap} 是 C 线的展开映射。
     *
     * <p>退化与边界（全部显式、不静默）：
     * <ul>
     *   <li>{@code upstream == null} → 立即抛 {@link NullPointerException}；</li>
     *   <li>本阶段没有登记任何行（{@code unmapped()}）→ 直接返回上游（视为本阶段未改行）；</li>
     *   <li>本阶段某行是合成行（起源未知）→ 合成结果保留该合成起源；</li>
     *   <li>上游缺对应行记录 → 保留本阶段起源，不猜、不补、不丢行。</li>
     * </ul>
     */
    public SourceLineMap compose(SourceLineMap upstream) {
        Objects.requireNonNull(upstream, "upstream 不许为 null");
        if (lines.length == 0) {
            return upstream;
        }
        String composedPrimary =
                upstream.primaryFile != null ? upstream.primaryFile : primaryFile;
        Builder builder = builder(composedPrimary);
        for (int outputLine = 1; outputLine <= lines.length; outputLine++) {
            LineOrigin mine = originOf(outputLine);
            if (mine.sourceLine() <= TranslateDiagnostic.UNKNOWN_LINE) {
                builder.add(mine.sourceFile(), mine.sourceLine());
                continue;
            }
            LineOrigin up = upstream.originOf(mine.sourceLine());
            if (up.sourceLine() > TranslateDiagnostic.UNKNOWN_LINE) {
                builder.add(up.sourceFile(), up.sourceLine());
            } else {
                builder.add(mine.sourceFile(), mine.sourceLine());
            }
        }
        return builder.build();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SourceLineMap that)) {
            return false;
        }
        return Objects.equals(primaryFile, that.primaryFile)
                && Arrays.equals(files, that.files)
                && Arrays.equals(lines, that.lines);
    }

    @Override
    public int hashCode() {
        return Objects.hash(primaryFile, Arrays.hashCode(files), Arrays.hashCode(lines));
    }

    @Override
    public String toString() {
        return "SourceLineMap[primaryFile=" + primaryFile + ", outputLines=" + lines.length + "]";
    }

    // ------------------------------------------------------------------ 类型

    /** 一行输出的起源：来自 {@code sourceFile} 的第 {@code sourceLine} 行。 */
    public record LineOrigin(String sourceFile, int sourceLine) {

        /** 记录构造：行号归一（{@code <= 0} → {@link TranslateDiagnostic#UNKNOWN_LINE}）。 */
        public LineOrigin {
            if (sourceLine < TranslateDiagnostic.UNKNOWN_LINE) {
                sourceLine = TranslateDiagnostic.UNKNOWN_LINE;
            }
        }

        /** 起源行是否已知（行号 &gt; 0）；文件可能未知而行号已知，反之亦然。 */
        public boolean lineKnown() {
            return sourceLine > TranslateDiagnostic.UNKNOWN_LINE;
        }
    }

    // ------------------------------------------------------------------ 登记器

    /** 逐行登记器：按输出行顺序追加起源，{@link #build()} 产出不可变映射。 */
    public static final class Builder {

        private final String primaryFile;
        private final List<String> files = new ArrayList<>();
        private final List<Integer> lines = new ArrayList<>();

        private Builder(String primaryFile) {
            this.primaryFile = primaryFile;
        }

        /**
         * 追加一条输出行：起源 = {@code (sourceFile, sourceLine)}。
         *
         * <p>{@code sourceFile == null} = 文件未知；{@code sourceLine <= 0} = 合成行
         * （本阶段注入，源文件中不存在，例如 D 线注入的 uniform 声明）。
         */
        public Builder add(String sourceFile, int sourceLine) {
            files.add(sourceFile);
            lines.add(Math.max(sourceLine, TranslateDiagnostic.UNKNOWN_LINE));
            return this;
        }

        /** 追加一条输出行，起源文件 = 主文件（逐行拷贝场景）。 */
        public Builder add(int sourceLine) {
            return add(primaryFile, sourceLine);
        }

        /**
         * 追加 {@code count} 条原样行：起源 = 主文件第 {@code startSourceLine} 行起连续
         * {@code count} 行（{@code #include} 展开时大段原样拷贝的常用形态）。
         */
        public Builder addIdentityRange(int startSourceLine, int count) {
            for (int i = 0; i < Math.max(0, count); i++) {
                add(startSourceLine + i);
            }
            return this;
        }

        /** 追加一条合成行（本阶段注入，源文件中不存在）。 */
        public Builder addSynthetic() {
            return add(null, TranslateDiagnostic.UNKNOWN_LINE);
        }

        /** 构造不可变映射（此后登记器与结果互不影响）。 */
        public SourceLineMap build() {
            int[] builtLines = new int[lines.size()];
            for (int i = 0; i < builtLines.length; i++) {
                builtLines[i] = lines.get(i);
            }
            return new SourceLineMap(primaryFile, files.toArray(new String[0]), builtLines);
        }
    }
}
