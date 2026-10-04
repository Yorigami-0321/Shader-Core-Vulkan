package dev.vkdisp.config;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【参考调研】P2.4 选项值 → GLSL 源改写（F 线能力的落地：选项值真正进入着色器）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md（选项语义）与 docs/18-PARALLEL.md §4 F 线
 *    「选项值 → #define 表」完成标准；② OptiFine 官方文档对 options/profiles 的语义描述
 *    （选项值**就地替换**包源里 `#define NAME <默认>` / `const T NAME = <默认>;` 的默认值，
 *    注释里的 `[候选值]` 列表保留）—— 属于格式事实，不受版权保护。
 *    外部候选 IrisShaders/glsl-transformer（GPL-3.0 + 例外条款）→ 按禁止处理
 *    （07-CONSTRAINTS L12 §1.3 陷阱 2 / X21），本任务不读其代码、零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 类，不含任何第三方项目代码。
 *    → 能否并入本项目（MIT）：可以（仅采纳不受版权保护的语义事实）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：无外部实现可参考（两个参考模组都不做 OF 包加载），按本项目契约自行实现。
 * 2. 备选：① 在 #define 表后追加新定义 —— 否决：GLSL 不允许宏重定义为不同体，且
 *    {@code const} 常量无法用 #define 覆盖；② 让 C 线 DefineProcessor 支持覆盖参数 —— 否决：
 *    覆盖发生在"展开后"就太晚了（选项值必须在展开**前**进入源），且会改动 C 线冻结入口签名。
 *    就地逐行改写是最贴近 OF 语义、且保行号保注释的做法。
 * 3. 我们的差异点：
 *    ① **保行数保注释**：只替换值记号本身，`// [候选值]` 尾注与行结构原样保留 —— 行号映射
 *       不因此漂移（F3 契约）；CRLF 行尾按行还原，不混入裸 LF；
 *    ② **裸 #define 的布尔语义**：源里是 `#define NAME`（无值）时，新值 false → 改成
 *       `#undef NAME`（OF 布尔关 = 宏不存在），其它值 → 就地补上值；
 *    ③ **缺失不是错误**：请求覆盖的选项可能只声明在别的文件（或未被本文件使用），
 *       逐文件缺失计入 {@code missingNames} 但不产生诊断 —— 是否需要告警由调用方按包级判断；
 *    ④ 输入输出全是内存数据，不触碰 {@code com.mojang.*}（18-PARALLEL §2 并行判据）。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径（切包/重载时随编译跑一次），单行正则线性扫描，清晰优先不做优化
 *    （18-PARALLEL §7.7、07-CONSTRAINTS T14 / X14）。
 */

/**
 * 把选项值**就地改写**进 OF 方言 GLSL 源（P2.4「开关能改变画面」的文本层实现）。
 *
 * <p>OF 语义：{@code #define SHADOW_DARKNESS 0.10 // [0.05 0.10 0.20]} 这样的行里，
 * {@code 0.10} 是默认值，选项/ profile 选中的新值要**替换**它（尾注保留）。
 * {@code const} 选项常量同理（{@code const float x = 1.0; // [1.0 2.0]}）。
 *
 * <p>改写发生在预处理（IncludeProcessor / DefineProcessor）**之前**：宏值随后由 C 线正常
 * 展开到使用点、指令行被删除 —— 最终 SPIR-V 里的就是新值。
 *
 * <p><b>已知未覆盖</b>（显式登记，不假装完整）：
 * 条件编译未选中分支里的声明行同样会被改写（无害：该分支随后被删除）；
 * 函数宏体内的赋值形态不识别（OF 选项不是该形态）。
 */
public final class OptionSourceRewriter {

    /** {@code #define NAME <值>}（带值；值后可带任意尾部如注释）。 */
    private static final Pattern DEFINE_WITH_VALUE = Pattern.compile(
            "^(\\s*#define\\s+)([A-Za-z_][A-Za-z0-9_]*)(\\s+)(\\S+)(.*)$");

    /** {@code #define NAME}（裸宏，无值）。 */
    private static final Pattern DEFINE_BARE = Pattern.compile(
            "^(\\s*#define\\s+)([A-Za-z_][A-Za-z0-9_]*)(\\s*)$");

    /** 被注释掉的裸宏 {@code //#define NAME}（组 1 = 前导空白，组 2 = 名字，组 3 = 行尾空白+注释）。 */
    private static final Pattern DEFINE_COMMENTED_BARE =
            Pattern.compile("^(\\s*)//#define\\s+([A-Za-z_]\\w*)(\\s*//.*)?$");
    /** {@code const <类型> NAME = <值>;}（值与分号之间不留分号内注释，与 OF 选项常量形态一致）。 */
    private static final Pattern CONST_ASSIGN = Pattern.compile(
            "^(\\s*const\\s+[A-Za-z_][A-Za-z0-9_]*\\s+)([A-Za-z_][A-Za-z0-9_]*)(\\s*=\\s*)([^;]+)(;.*)$");

    private OptionSourceRewriter() {
    }

    /**
     * 改写结果：文本 + 统计。
     *
     * @param text         改写后文本（无命中时与输入逐字节相同；永不 null）
     * @param appliedCount 实际被改动的行数（值相同不算）
     * @param appliedNames 请求覆盖且在文本中**找到声明**的选项名（即使值恰好相同）
     * @param missingNames 请求覆盖但全文未见声明的选项名（由调用方决定是否告警）
     */
    public record Result(String text, int appliedCount, Set<String> appliedNames, Set<String> missingNames) {

        /** 记录构造：三个集合冻结为不可变（text 归一为 "" 防 null）。 */
        public Result {
            text = text == null ? "" : text;
            appliedNames = appliedNames == null ? Set.of() : Set.copyOf(appliedNames);
            missingNames = missingNames == null ? Set.of() : Set.copyOf(missingNames);
        }
    }

    /**
     * 应用选项覆盖表。
     *
     * @param source 原始 GLSL 源；null 按空串处理（返回空串，不抛异常）
     * @param values 选项名 → 新值；null / 空表 → 源文本逐字节原样返回
     * @return 改写结果；永不 null
     */
    public static Result apply(String source, Map<String, String> values) {
        if (source == null) {
            return new Result("", 0, Set.of(), Set.of());
        }
        if (values == null || values.isEmpty()) {
            return new Result(source, 0, Set.of(), Set.of());
        }

        // 逐行处理但按 \n 切分：CRLF 行的 \r 先摘下、处理完原样接回，避免混入裸 LF（与 D 线口径一致）。
        String[] lines = source.split("\n", -1);
        StringBuilder out = new StringBuilder(source.length());
        int appliedCount = 0;
        Set<String> appliedNames = new LinkedHashSet<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean crlf = line.endsWith("\r");
            String body = crlf ? line.substring(0, line.length() - 1) : line;
            String rewritten = rewriteLine(body, values, appliedNames);
            if (!rewritten.equals(body)) {
                appliedCount++;
            }
            out.append(rewritten);
            if (crlf) {
                out.append('\r');
            }
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }

        Set<String> missing = new LinkedHashSet<>(values.keySet());
        missing.removeAll(appliedNames);
        return new Result(out.toString(), appliedCount, appliedNames, missing);
    }

    /** 单行改写：不命中返回原行；命中但值相同也返回原行（但登记 appliedNames）。 */
    private static String rewriteLine(String line, Map<String, String> values, Set<String> appliedNames) {
        Matcher constMatch = CONST_ASSIGN.matcher(line);
        if (constMatch.matches()) {
            String name = constMatch.group(2);
            String newValue = values.get(name);
            if (newValue == null) {
                return line;
            }
            appliedNames.add(name);
            String oldValue = constMatch.group(4);
            if (newValue.equals(oldValue)) {
                return line;
            }
            return constMatch.group(1) + name + constMatch.group(3) + newValue.trim() + constMatch.group(5);
        }

        Matcher define = DEFINE_WITH_VALUE.matcher(line);
        if (define.matches()) {
            String name = define.group(2);
            String newValue = values.get(name);
            if (newValue == null) {
                return line;
            }
            String valueToken = define.group(4);
            if (valueToken.startsWith("//") || valueToken.startsWith("/*")) {
                // 形如 `#define NAME // [true false]`：注释顶替了值位置 → 按裸宏处理，注释整段保留。
                appliedNames.add(name);
                String comment = define.group(3) + valueToken + define.group(5);
                return rewriteBareDefine(define.group(1), name, comment, newValue);
            }
            appliedNames.add(name);
            if (newValue.equals(valueToken)) {
                return line;
            }
            return define.group(1) + name + define.group(3) + newValue.trim() + define.group(5);
        }

        Matcher bare = DEFINE_BARE.matcher(line);
        if (bare.matches()) {
            String name = bare.group(2);
            String newValue = values.get(name);
            if (newValue == null) {
                return line;
            }
            appliedNames.add(name);
            return rewriteBareDefine(bare.group(1), name, bare.group(3), newValue);
        }

        // 🔖 2026-10-04 新增（实测 BSL：483 个布尔开关写成 //#define NAME）。
        //   原先没有这条路径 ⇒ 「把默认关闭的选项打开」这个动作**根本做不到**。
        //   true  → 去掉 // 前缀，成为真正的 #define（OF 里 //#define 就是「关掉」的写法）；
        //   false → 保持注释形态（它本来就是关的；再补 // 会变成 ///#define 污染源）。
        Matcher commented = DEFINE_COMMENTED_BARE.matcher(line);
        if (commented.matches()) {
            String name = commented.group(2);
            String newValue = values.get(name);
            if (newValue == null) {
                return line;
            }
            appliedNames.add(name);
            if (!"true".equalsIgnoreCase(newValue.trim())) {
                return line;
            }
            // group(1) 只是**前导空白**（// 由 pattern 固定匹配），不能从它身上切字符 ——
            // 首版就是这么写的，结果把行首空白削掉 2 个字符，整行被改坏，
            // 下游预处理器随即报出「Range [0, -2) out of bounds」并丢掉全部阶段（本轮实测）。
            String lead = commented.group(1);
            String suffix = commented.group(3) == null ? "" : commented.group(3);
            return lead + "#define " + name + suffix;
        }
        return line;
    }

    /**
     * 裸 {@code #define NAME} 的新值落地：false → {@code #undef}（OF 布尔关 = 宏不存在）；
     * 其它值 → 就地补上值。{@code suffix} 是原行尾（空白或注释），原样保留。
     */
    private static String rewriteBareDefine(String prefix, String name, String suffix, String newValue) {
        String trimmedSuffix = suffix == null ? "" : suffix;
        if ("false".equals(newValue)) {
            // 保留前导空白（prefix 以 #define 起头，重新拼装时把 #define 换成 #undef）。
            int directive = prefix.indexOf("#define");
            String lead = prefix.substring(0, directive);
            return lead + "#undef " + name + trimmedSuffix;
        }
        // suffix 原样接回（空 → 不补尾巴；" // [true false]" → 注释前的空格保留）。
        return prefix + name + " " + newValue.trim() + trimmedSuffix;
    }
}
