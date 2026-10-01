package dev.vkdisp.glsl.translate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.vkdisp.glsl.TranslateDiagnostic;

/**
 * 【参考调研】D 线 in/out location 补写器（P4.1.2 驱动层）/ 04-SPEC §4 顶点属性名字绑定 + shaderc 实测原文
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 runClient 实测 shaderc 原文（2026-09-30，/tmp/p41a_runclient.log）：
 *    {@code 'location' : SPIR-V requires location for user input/output}（片元 in 无 location）
 *    与 {@code 'location' : not supported for this version or the enabled extensions}
 *    （低版本下的 layout 输出）—— 驱动报错事实；docs/04-SPEC.md §4「字段名必须与着色器里的
 *    attribute 声明完全一致」（顶点属性**按名字**绑定 VertexFormat，因此顶点 in 不注入 location）
 *    与 docs/04-SPEC.md §3.3（源码级转译交原版编译）—— 仓库内文档事实；另加 GLSL 公开语义
 *    （layout(location = N) 自 GLSL 130/140 起可用于 in/out；无实例名块成员不重复分配 location）。
 *    外部候选 IrisShaders/glsl-transformer（GPL-3.0 + 例外条款，18-PARALLEL §4 D 线明示
 *    "按禁止处理"）→ 按禁止处理（07-CONSTRAINTS L12 §1.3 陷阱 2 / X21），零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 实现，不含任何外部项目代码。
 *    → 能否并入本项目（MIT）：可以（仅采纳不受版权保护的驱动报错事实与 GLSL 限定符语义）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：GLSL 公开语义 —— SPIR-V 用户 in/out 必须带 location；片元输入的 location
 *    与顶点输出按 location 链接（本仓库全屏 VS 的 {@code vUv} 在 location 0，见
 *    assets/vkdisp/shaders/fullscreen.vsh）；顶点属性另有名字绑定契约（04-SPEC §4，见差异点 ①）。
 * 2. 备选：无 —— 不建 AST、不引入解析框架；GlslDeclaration 行级解析 + 等长无注释视图就地改写，
 *    够用即停（08-TESTING §8.1 达标即停）。
 * 3. 我们的差异点：① **顶点 in（属性）不补**：04-SPEC §4 规定顶点属性按字段名字面匹配绑定，
 *    location 由绑定侧决定，注入任意序号可能与 VertexFormat 的槽位规划冲突（X9 拒绝猜测）；
 *    片元 in/out 与顶点 out（varying 跨阶段接口）不属于名字绑定契约 → 补写（驱动强制）；
 *    ② in / out 各自独立计数，按声明序取"最小未占用号"：已有 layout(location = K) 的声明先
 *    占号，不与后补的撞车；本仓库全屏 VS 的首 varying 在 0，包片元首个 in（首条 varying）
 *    因此落在 0 —— 这是接口契约不是巧合；③ **逗号多声明名同行拆语句**：
 *    {@code in vec3 a, b;} → {@code layout(location = 1) in vec3 a; layout(location = 2) in vec3 b;}
 *    （SPIR-V 每个用户输入要独占 location，单个 layout 管多名字是未定义/报错形态；同行拆语句
 *    保持行数不变，行号映射不被切断）；④ 接口块开行 {@code in Block {} 照补（块成员不重复分配，
 *    成员行经 GlslDeclaration 解析天然跳过）；⑤ 同行多语句 / 跨行半截声明不改写 + 多语句出 WARN
 *    （保留原样交驱动显式报错，T11；跨行静默是因为函数参数折行以 {@code out vec3 x)} 形态出现，
 *    对它出 WARN 是误报）；⑥ 已带 layout 的声明原样跳过 → 第二遍逐字节不变（幂等）；
 *    ⑦ 成功补写不产生诊断（与 ①–③ 级行内变换同口径），行数恒等。
 * 4. 许可证核对结论：本项目 MIT；GPL-3.0+例外参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径（包加载期一次）；两遍线性扫描（种子 + 改写），无缓存、无预优化
 *    （18-PARALLEL §7.7、T14 达标即停）。
 */
/**
 * 给缺 location 的跨阶段 {@code in} / {@code out} 声明补写 {@code layout(location = N)}
 * （P4.1.2：片元输入无 location 过不了 Vulkan 的 SPIR-V 编译，shaderc 实测原文见类注释 0）。
 *
 * <p><b>作用范围</b>：片元 in / out、顶点 out（varying 跨阶段接口）；<b>顶点 in（属性）按
 * 04-SPEC §4 名字绑定契约跳过</b>（见类注释差异点 ①）。uniform / attribute / varying 残留 /
 * 预处理指令行不动。
 *
 * <p><b>编号规则</b>：每个方向独立、按声明序取最小未占用号；已有 {@code layout(location = K)}
 * 先占号（含顶点 in —— 即使本类不改它，它的号也不再发给别人）。
 *
 * <p><b>行号契约</b>：等行数改写（逗号拆分同行完成），输出行号与输入一一对应；诊断的
 * {@code line} 是本阶段输入行号，由 {@link OfGlslTranslator} 按 ①–⑤ 级同款方式经上游映射回填。
 */
public final class IoLocationAdapter {

    /** 允许出现在 in/out 之前的插值 / 存储限定符（与 GlslDeclaration 口径一致）。 */
    private static final Set<String> LEADING_QUALIFIERS = Set.of(
            "flat", "smooth", "noperspective", "centroid", "patch", "sample", "invariant", "precise");

    /** 已带 layout 的 in/out 声明（占号种子）：{@code layout(...) [限定符...] in|out}。 */
    private static final Pattern LAYOUT_IO = Pattern.compile(
            "layout\\s*\\(([^)]*)\\)\\s*(?:(?:flat|smooth|noperspective|centroid|patch|sample|invariant|precise)\\s+)*(in|out)\\b");

    /** layout 括号里的 {@code location = N}。 */
    private static final Pattern LOCATION_VALUE = Pattern.compile("location\\s*=\\s*(\\d+)");

    private IoLocationAdapter() {}

    /**
     * 补写结果（纯数据）。
     *
     * @param text         补写后的文本；无需补写时与输入逐字节相同
     * @param diagnostics  诊断（跳过等异常形态的 WARN；位置 = 本阶段输入行号，
     *                     {@code sourceFile} 为 {@code null}，由入口回填）
     * @param locatedCount 本次补写 location 的声明行数（逗号拆语句按 1 行算）
     */
    public record Result(String text, List<TranslateDiagnostic> diagnostics, int locatedCount) {

        /** 记录构造：null 归一（结果对象永不为 null 语义，同 F3 契约）。 */
        public Result {
            text = text == null ? "" : text;
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    /**
     * 给缺 location 的跨阶段 in/out 声明补写 {@code layout(location = N)}
     * （顶点 in 属性按 04-SPEC §4 跳过；行数不变）。
     *
     * @param stage 着色器阶段；只用于判定"顶点 in 跳过"，{@code null} 按非顶点处理
     * @param source 输入 GLSL（{@code null} 按空串处理）
     * @return 补写结果；永不返回 {@code null}
     */
    public static Result locate(ShaderStage stage, String source) {
        SourceLines lines = SourceLines.of(source);
        List<String> rawLines = lines.lines();
        List<TranslateDiagnostic> diagnostics = new ArrayList<>();
        List<String> codeLines = GlslTextScan.codeViews(rawLines, diagnostics);
        boolean[] skip = GlslTextScan.preprocessorSkipLines(rawLines, codeLines);

        // 占号种子：已有 layout(location = K) 的 in/out 先占号（顶点 in 即使跳过改写也占号）。
        Set<Integer> usedIn = new HashSet<>();
        Set<Integer> usedOut = new HashSet<>();
        for (String code : codeLines) {
            Matcher declared = LAYOUT_IO.matcher(code);
            while (declared.find()) {
                Matcher value = LOCATION_VALUE.matcher(declared.group(1));
                if (value.find()) {
                    ("in".equals(declared.group(2)) ? usedIn : usedOut)
                            .add(Integer.parseInt(value.group(1)));
                }
            }
        }

        int located = 0;
        List<String> adapted = new ArrayList<>(rawLines.size());
        for (int index = 0; index < rawLines.size(); index++) {
            if (skip[index]) {
                adapted.add(rawLines.get(index));
                continue;
            }
            String code = codeLines.get(index);
            String raw = rawLines.get(index);
            GlslDeclaration declaration = GlslDeclaration.parse(code);
            if (declaration == null
                    || (!"in".equals(declaration.keyword) && !"out".equals(declaration.keyword))) {
                adapted.add(raw);
                continue;
            }
            if (stage == ShaderStage.VERTEX && "in".equals(declaration.keyword)) {
                // 04-SPEC §4：顶点属性按字段名绑定，location 归绑定侧，不注入（X9 拒绝猜测）。
                adapted.add(raw);
                continue;
            }
            Set<Integer> used = "in".equals(declaration.keyword) ? usedIn : usedOut;
            int insertPos = GlslTextScan.skipWhitespace(code, 0);
            if (declaration.block) {
                // 接口块开行：块整体占一个 location；成员行不是 in/out 声明，解析天然跳过。
                int location = nextUnused(used);
                used.add(location);
                adapted.add(raw.substring(0, insertPos) + "layout(location = " + location + ") "
                        + raw.substring(insertPos));
                located++;
                continue;
            }
            if (declaration.type == null || declaration.name == null || !declaration.terminated) {
                // 跨行 / 半截声明：不改写、不 WARN —— 函数参数折行（out vec3 x)）走同一分支，
                // 对它出 WARN 是误报；真正漏 location 的残缺声明由驱动显式报错（T11）。
                adapted.add(raw);
                continue;
            }
            int semi = code.indexOf(';', declaration.keywordEnd);
            if (!code.substring(semi + 1).strip().isEmpty()) {
                // 同行还有别的语句：抹/改本行会连带动别的语句 —— 保留原样，WARN 可见（T11）。
                diagnostics.add(TranslateDiagnostic.warn("同行多语句的 " + declaration.keyword
                        + " 声明未补写 layout(location)（保留原样，驱动会显式报错）",
                        null, index + 1));
                adapted.add(raw);
                continue;
            }
            int typeStart = GlslTextScan.skipWhitespace(code, declaration.keywordEnd);
            int typeEnd = typeStart + declaration.type.length();
            int comma = code.indexOf(',', typeEnd);
            if (comma < 0 || comma > semi) {
                int location = nextUnused(used);
                used.add(location);
                adapted.add(raw.substring(0, insertPos) + "layout(location = " + location + ") "
                        + raw.substring(insertPos));
                located++;
                continue;
            }
            // 逗号多声明名：同行拆成带各自 location 的语句（行数不变；逗号位置在无注释视图上取，
            // 注释里的逗号已被剥离成空格，切片落在原始行同下标 → 注释字节原位保留）。段从 typeEnd /
            // 逗号后**连续**切起，type 与名字之间、逗号与名字之间的注释与空白原样落进段内；
            // 段首本身不是空白时才补一个分隔空格（避免 `a, b` 拼出双空格）。
            String head = raw.substring(insertPos, typeEnd);
            StringBuilder rebuilt = new StringBuilder(raw.substring(0, insertPos));
            int segmentStart = typeEnd;
            for (int cursor = typeEnd; cursor <= semi; cursor++) {
                if (cursor != semi && code.charAt(cursor) != ',') {
                    continue;
                }
                int location = nextUnused(used);
                used.add(location);
                if (rebuilt.length() > 0 && rebuilt.charAt(rebuilt.length() - 1) != ' '
                        && rebuilt.charAt(rebuilt.length() - 1) != '\t') {
                    rebuilt.append(' ');
                }
                rebuilt.append("layout(location = ").append(location).append(") ").append(head);
                if (segmentStart < cursor && !Character.isWhitespace(raw.charAt(segmentStart))) {
                    rebuilt.append(' ');
                }
                rebuilt.append(raw, segmentStart, cursor).append(';');
                segmentStart = cursor + 1;
            }
            rebuilt.append(raw, semi + 1, raw.length());
            adapted.add(rebuilt.toString());
            located++;
        }
        if (located == 0 && diagnostics.isEmpty()) {
            return new Result(lines.text(), List.of(), 0);
        }
        String text = SourceLines.join(adapted, lines.endsWithNewline());
        return new Result(text, diagnostics, located);
    }

    /** 最小未占用 location（0 起；已有声明与本类已补的都算占用）。 */
    private static int nextUnused(Set<Integer> used) {
        int candidate = 0;
        while (used.contains(candidate)) {
            candidate++;
        }
        return candidate;
    }
}
