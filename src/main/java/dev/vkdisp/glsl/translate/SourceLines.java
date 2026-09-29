package dev.vkdisp.glsl.translate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 【参考调研】D 线文本扫描辅助 / 04-SPEC §3.3 与 08-TESTING §4 对行号保留的事实性要求
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/04-SPEC.md §3.3 / §3.2、docs/08-TESTING.md §4、docs/18-PARALLEL.md §4 D 线
 *    「转换幂等 + 诊断回填原文件行号」的要求 —— 仓库内文档事实，不受版权保护。
 *    外部候选 IrisShaders/glsl-transformer（GPL-3.0 + 例外条款）→ 按禁止处理
 *    （07-CONSTRAINTS L12 §1.3 陷阱 2 / X21），本任务不读其代码、零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 辅助类，不含任何外部项目代码。
 *    → 能否并入本项目（MIT）：可以（仅采纳不受版权保护的事实性信息：注释优先剥离、行号从 1 起）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：GLSL 预处理阶段"注释先于语法被移除"这一公开规则（// 行注释、不支持嵌套的块注释），
 *    以及 03-DIRECTION §3.2 记录的 OF 方言转译需求（attribute/varying 行内重写）。
 * 2. 备选：无 —— 为本线自建的最小行扫描器；不引入 ANTLR / JavaCC 等解析框架（冷路径清晰优先，
 *    18-PARALLEL §7.7；X12 不顺手引入依赖）。
 * 3. 我们的差异点：① 注释剥离时**保留字符位置**（注释替换为等长空格），使"剥离后的 code"与原始行
 *    下标一一对应，重写时可直接按列替换，且不破坏 C 线的行号映射；② 行号一律 1 起，0 = 未知，
 *    与 F3 契约（TranslateDiagnostic.UNKNOWN_LINE）一致。
 * 4. 许可证核对结论：本项目 MIT；GPL-3.0+例外参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径（包加载期一次）；逐字符线性扫描，无缓存、无预优化
 *    （18-PARALLEL §7.7、T14 达标即停）。
 */
/**
 * 文本 ↔ 行的无损坏模型：拆行、标记结尾换行、插入若干整行。
 *
 * <p><b>为什么不用 StringBuilder 直接拼接</b>：D 线需要"插入 K 行后，输出第 N 行来自输入第 M 行"
 * 这一事实来构造 {@code SourceLineMap}（F3 契约）。行列表模型让该换算成为一行减法，
 * 而插入点前后的内容（含 CRLF 行尾）保持逐字节不变。
 *
 * <p><b>行尾约定</b>：拆行只按 {@code \n}，因此 CRLF 文件里的 {@code \r} 留在行内容末尾，
 * 拼接时原样保留；新插入的行按文件主行尾风格（{@code \n} / {@code \r\n}）补齐。
 * 行号一律 1 起（0 = 未知，见 F3）。
 */
final class SourceLines {

    /** 行内容（不含换行符；CRLF 的 {@code \r} 属于行内容）。 */
    private final List<String> lines;

    /** 原文本是否以换行符结尾（决定拼接时是否补最后一个换行）。 */
    private final boolean endsWithNewline;

    /** 原文本主行尾风格："\n" 或 "\r\n"（插入新行时使用）。 */
    private final String newline;

    private SourceLines(List<String> lines, boolean endsWithNewline, String newline) {
        this.lines = lines;
        this.endsWithNewline = endsWithNewline;
        this.newline = newline;
    }

    /** 拆分文本（{@code null} 按空串处理）。 */
    static SourceLines of(String text) {
        String source = text == null ? "" : text;
        String detected = source.contains("\r\n") ? "\r\n" : "\n";
        if (source.isEmpty()) {
            return new SourceLines(List.of(), false, detected);
        }
        List<String> raw = new ArrayList<>(Arrays.asList(source.split("\n", -1)));
        boolean ends = raw.size() > 1 && raw.get(raw.size() - 1).isEmpty();
        if (ends) {
            raw.remove(raw.size() - 1);
        }
        return new SourceLines(List.copyOf(raw), ends, detected);
    }

    /** 按源文本行尾风格拼接行列表。 */
    static String join(List<String> lines, boolean endsWithNewline) {
        String joined = String.join("\n", lines);
        return endsWithNewline ? joined + "\n" : joined;
    }

    /** 行内容（不可变，1 起由调用方换算）。 */
    List<String> lines() {
        return this.lines;
    }

    /** 行数（不含"结尾换行造成的空行"）。 */
    int lineCount() {
        return this.lines.size();
    }

    /** 原文本是否以换行符结尾。 */
    boolean endsWithNewline() {
        return this.endsWithNewline;
    }

    /** 还原原文本（与 {@link #of(String)} 互为逆运算）。 */
    String text() {
        return join(this.lines, this.endsWithNewline);
    }

    /**
     * 在 0 基行下标 {@code index} 之前插入若干整行，返回新的完整文本。
     *
     * <p>{@code index == lineCount()} 表示追加到文件末尾（此时结果以换行结尾，避免最后一行并入原末行）。
     * 插入的行按文件主行尾风格补 {@code \r}。
     *
     * @param index    插入点：其之前有 index 行（0 = 文件最前）
     * @param inserted 要插入的行（不含换行符）；空列表 = 原样返回
     */
    String insertLines(int index, List<String> inserted) {
        Objects.requireNonNull(inserted, "inserted 不许为 null");
        if (inserted.isEmpty()) {
            return text();
        }
        int at = Math.max(0, Math.min(index, this.lines.size()));
        String carriageReturn = "\r\n".equals(this.newline) ? "\r" : "";
        List<String> merged = new ArrayList<>(this.lines.size() + inserted.size());
        merged.addAll(this.lines.subList(0, at));
        for (String line : inserted) {
            merged.add(line + carriageReturn);
        }
        merged.addAll(this.lines.subList(at, this.lines.size()));
        boolean trailing = this.endsWithNewline || at >= this.lines.size();
        return join(merged, trailing);
    }
}
