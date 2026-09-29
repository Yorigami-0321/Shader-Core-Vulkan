package dev.vkdisp.glsl.translate;

import dev.vkdisp.glsl.TranslateDiagnostic;

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
 * 行级注释状态机：把注释内容替换为等长空格，并跨行跟踪块注释。
 *
 * <p><b>为什么是"替换成空格"而不是"删掉"</b>：返回串与输入行**等长同下标**，
 * 于是 AttributeRewriter 可以先在"无注释视图"里定位 attribute / varying 关键字，
 * 再按同一个下标区间去改原始行 —— 注释、缩进、行尾内容一字不动，行数也不变
 * （C 线的行号映射因此不被切断）。
 *
 * <p><b>边界</b>：GLSL 的块注释不支持嵌套，本状态机按同一规则处理；
 * 文件结束时仍处于块注释 → 由调用方按 {@link #blockCommentStartLine()} 报 ERROR（T11）。
 */
final class CommentState {

    /** 是否位于块注释内部（跨行保持）。 */
    private boolean inBlockComment;

    /** 当前未闭合块注释的起始行（1 起；{@link TranslateDiagnostic#UNKNOWN_LINE} = 当前没有）。 */
    private int blockCommentStartLine = TranslateDiagnostic.UNKNOWN_LINE;

    /** 是否正处于块注释内部（文件结束时为 true 说明块注释未闭合）。 */
    boolean inBlockComment() {
        return this.inBlockComment;
    }

    /** 未闭合块注释的起始行号（1 起）；没有未闭合块注释时为 {@link TranslateDiagnostic#UNKNOWN_LINE}。 */
    int blockCommentStartLine() {
        return this.blockCommentStartLine;
    }

    /**
     * 剥离本行注释：注释字符替换为空格，其余字符原位保留（返回串与入参等长）。
     *
     * @param line       原始行（不含换行符）
     * @param lineNumber 行号（1 起），用于记录块注释起始行
     * @return 等长的"无注释视图"
     */
    String stripComments(String line, int lineNumber) {
        char[] view = line.toCharArray();
        int pos = 0;
        while (pos < line.length()) {
            if (this.inBlockComment) {
                int end = line.indexOf("*/", pos);
                if (end < 0) {
                    blank(view, pos, line.length());
                    return new String(view);
                }
                blank(view, pos, end + 2);
                this.inBlockComment = false;
                this.blockCommentStartLine = TranslateDiagnostic.UNKNOWN_LINE;
                pos = end + 2;
                continue;
            }
            if (line.startsWith("//", pos)) {
                blank(view, pos, line.length());
                return new String(view);
            }
            if (line.startsWith("/*", pos)) {
                this.inBlockComment = true;
                if (this.blockCommentStartLine == TranslateDiagnostic.UNKNOWN_LINE) {
                    this.blockCommentStartLine = lineNumber;
                }
                view[pos] = ' ';
                view[pos + 1] = ' ';
                pos += 2;
                continue;
            }
            pos++;
        }
        return new String(view);
    }

    /** 把 [from, to) 区间全部替换为空格。 */
    private static void blank(char[] view, int from, int to) {
        for (int i = from; i < to && i < view.length; i++) {
            view[i] = ' ';
        }
    }
}
