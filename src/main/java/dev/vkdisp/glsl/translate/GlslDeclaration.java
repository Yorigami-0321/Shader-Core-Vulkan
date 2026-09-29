package dev.vkdisp.glsl.translate;

import java.util.Set;

/**
 * 【参考调研】D 线声明扫描辅助 / 04-SPEC §3.3 与 §4 对 OF 声明语法的事实性要求
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/04-SPEC.md §3.3（OF 老式 attribute/varying 语法）、§3.2（uniform 语义）、
 *    §4（顶点属性名字必须与着色器声明字面一致）—— 仓库内文档事实，不受版权保护。
 *    外部候选 IrisShaders/glsl-transformer（GPL-3.0 + 例外条款）→ 按禁止处理
 *    （07-CONSTRAINTS L12 §1.3 陷阱 2 / X21），本任务不读其代码、零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 辅助类，不含任何外部项目代码。
 *    → 能否并入本项目（MIT）：可以（仅采纳不受版权保护的事实性信息：限定符词法与声明形态）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：GLSL 公开词法事实 —— 限定符与类型/名字之间是空白分隔的标识符序列；
 *    全局声明形如 "qualifiers type name; "；uniform 块形如 "uniform BlockName { ... }"。
 * 2. 备选：无 —— 不建 AST、不引入解析框架；本类只回答"这一行是不是一条我们关心的声明声明"，
 *    够用即停（08-TESTING §8.1 达标即停）。
 * 3. 我们的差异点：解析输入是 CommentState 产出的**等长无注释视图**，所以关键字下标可直接用于
 *    改原始行；缺少类型/名字、缺分号这类"半截声明"不作为解析异常，而是交给调用方出诊断（T11）。
 * 4. 许可证核对结论：本项目 MIT；GPL-3.0+例外参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径；单行逐字符扫描，无缓存、无预优化（18-PARALLEL §7.7、T14 达标即停）。
 */
/**
 * 一行 GLSL 声明的极简解析结果（只覆盖 D 线需要的信息）。
 *
 * <p>输入必须是 {@link CommentState#stripComments} 产出的等长无注释视图：注释已被替换为空格，
 * 因此 {@link #keywordStart} / {@link #keywordEnd} 可直接用于改**原始行**的同一区间。
 *
 * <p>字段可空约定：{@link #type} / {@link #name} 为 {@code null} 表示这一行不是完整的
 * {@code 限定符 类型 名字;} 声明（半截声明），由调用方按 T11 出诊断，而不是在这里抛异常。
 */
final class GlslDeclaration {

    /** 插值/存储限定符：允许出现在 attribute / varying 之前（现代 GLSL 仍保留这些限定符）。 */
    private static final Set<String> LEADING_QUALIFIERS = Set.of(
            "flat", "smooth", "noperspective", "centroid", "patch", "sample", "invariant", "precise");

    /** D 线关心的声明关键字。 */
    private static final Set<String> DECLARATION_KEYWORDS = Set.of(
            "attribute", "varying", "uniform", "in", "out");

    /** 声明关键字（attribute / varying / uniform / in / out）。 */
    final String keyword;

    /** 声明类型（如 vec3 / mat4）；{@code null} = 缺少类型。 */
    final String type;

    /** 声明名；{@code null} = 缺少名字（uniform 块 / 半截声明）。 */
    final String name;

    /** 关键字在无注释视图中的起下标（= 原始行同一下标）。 */
    final int keywordStart;

    /** 关键字在无注释视图中的止下标（不含）。 */
    final int keywordEnd;

    /** 本行是否在名字之后出现分号（false 可能是跨行声明，不一定是语法错误）。 */
    final boolean terminated;

    /** 是否为 uniform 块（{@code uniform Foo { ... }}），块内声明的名字不由本类解析。 */
    final boolean block;

    private GlslDeclaration(String keyword, String type, String name, int keywordStart, int keywordEnd,
            boolean terminated, boolean block) {
        this.keyword = keyword;
        this.type = type;
        this.name = name;
        this.keywordStart = keywordStart;
        this.keywordEnd = keywordEnd;
        this.terminated = terminated;
        this.block = block;
    }

    /**
     * 解析一行（无注释视图）。
     *
     * @param code 等长无注释视图（见类注释）
     * @return 声明结果；该行不是 D 线关心的声明（含空行、预处理指令、函数体内语句、layout 限定行等）时返回 {@code null}
     */
    static GlslDeclaration parse(String code) {
        if (code == null || code.isEmpty()) {
            return null;
        }
        int pos = skipWhitespace(code, 0);
        String keyword = null;
        int keywordStart = -1;
        int keywordEnd = -1;
        while (true) {
            int[] span = readIdentifier(code, pos);
            if (span == null) {
                return null;
            }
            String token = code.substring(span[0], span[1]);
            if (DECLARATION_KEYWORDS.contains(token)) {
                keyword = token;
                keywordStart = span[0];
                keywordEnd = span[1];
                pos = skipWhitespace(code, span[1]);
                break;
            }
            if (!LEADING_QUALIFIERS.contains(token)) {
                return null;
            }
            pos = skipWhitespace(code, span[1]);
        }
        int[] typeSpan = readIdentifier(code, pos);
        if (typeSpan == null) {
            return new GlslDeclaration(keyword, null, null, keywordStart, keywordEnd, false, false);
        }
        String type = code.substring(typeSpan[0], typeSpan[1]);
        pos = skipWhitespace(code, typeSpan[1]);
        if (pos < code.length() && code.charAt(pos) == '{') {
            return new GlslDeclaration(keyword, type, null, keywordStart, keywordEnd, false, true);
        }
        int[] nameSpan = readIdentifier(code, pos);
        if (nameSpan == null) {
            return new GlslDeclaration(keyword, type, null, keywordStart, keywordEnd, false, false);
        }
        String name = code.substring(nameSpan[0], nameSpan[1]);
        boolean terminated = code.indexOf(';', nameSpan[1]) >= 0;
        return new GlslDeclaration(keyword, type, name, keywordStart, keywordEnd, terminated, false);
    }

    /** 跳过空格与制表符（注释已在无注释视图里变成空格）。 */
    private static int skipWhitespace(String code, int from) {
        int pos = from;
        while (pos < code.length() && (code.charAt(pos) == ' ' || code.charAt(pos) == '\t')) {
            pos++;
        }
        return pos;
    }

    /**
     * 读取一个 GLSL 标识符。
     *
     * @return {@code [start, end)} 二元组；当前位置不是标识符起始时返回 {@code null}
     */
    private static int[] readIdentifier(String code, int from) {
        int pos = from;
        if (pos >= code.length()) {
            return null;
        }
        char first = code.charAt(pos);
        if (!isIdentifierStart(first)) {
            return null;
        }
        pos++;
        while (pos < code.length() && isIdentifierPart(code.charAt(pos))) {
            pos++;
        }
        return new int[] {from, pos};
    }

    private static boolean isIdentifierStart(char c) {
        return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isIdentifierPart(char c) {
        return isIdentifierStart(c) || (c >= '0' && c <= '9');
    }
}
