package dev.vkdisp.glsl.translate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.vkdisp.glsl.TranslateDiagnostic;

/**
 * 【参考调研】D 线 OF 内建 uniform 注入器 / 04-SPEC §3.2 内建 uniform 表
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/04-SPEC.md §3.2（OF 内建 uniform 必须提供的语义，23 条）与
 *    docs/18-PARALLEL.md §4 D 线（"OF 内建 uniform 声明注入完整"）—— 仓库内文档事实，不受版权保护。
 *    外部候选 IrisShaders/glsl-transformer（GPL-3.0 + 例外条款，18-PARALLEL §4 D 线明示"按禁止处理"）→
 *    按禁止处理（07-CONSTRAINTS L12 §1.3 陷阱 2 / X21），本任务不读其代码、零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 实现，不含任何外部项目代码。
 *    → 能否并入本项目（MIT）：可以 —— 只采纳不受版权保护的事实性信息（uniform 名/类型/语义）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：04-SPEC §3.2 的 uniform 表（经 UniformCatalog 固化）；注入点选在**文件头部区**
 *    （空行 / 注释 / 预处理指令之后、首条代码之前），这是 GLSL 里"全局作用域且在所有使用之前、
 *    同时不破坏 #version / #extension 必须靠前"的公开硬性要求。
 * 2. 备选：无 —— 不做 UBO 打包（那是 pipeline/ 的活，18-PARALLEL §2.1 明确不许提前做），
 *    只产出声明文本；上传语义见 04-SPEC §3.4 OfUniformManager（关键路径）。
 * 3. 我们的差异点：① **只注入缺失项**：包内已声明的一律不重写（尊重包的意图，也保证幂等）；
 *    已声明但类型与 OF 语义不符 → WARN 而不是偷偷覆盖（T11）；② 固定注入顺序（文档表顺序），
 *    第二遍转译时首行代码即已注入的 uniform，注入点前移到它之前且无缺失项 → 文本逐字节不变；
 *    ③ 纯注释 / 空文件不注入（注入了也没有使用方），并显式 WARN 而不是静默跳过；
 *    ④ 注入文本包裹在单个**具名 std140 块**（无实例名）里而不是独立 {@code uniform} 行 ——
 *    Vulkan GLSL 禁止非透明 uniform 游离在块外（P2.3 驱动实测 shaderc 原文
 *    {@code 'non-opaque uniforms outside a block' : not allowed when using GLSL for Vulkan}），
 *    而块必须带名字（同轮实测：匿名 {@code uniform {} 报 {@code syntax error, unexpected LEFT_BRACE}；
 *    原版 89 个 shader 全是具名无实例名块，成员裸引用 —— 两条均为公开语言/原版事实，不受版权保护）。
 *    无实例名块的成员仍在全局作用域，包源码引用字面不变；扫描时把该块成员记作"已声明"
 *    以保证幂等与不撞名。
 * 4. 许可证核对结论：本项目 MIT；GPL-3.0+例外参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径（包加载期一次）；两趟线性扫描，无缓存、无预优化
 *    （18-PARALLEL §7.7、T14 达标即停）。
 */
/**
 * 注入 OF 内建 uniform 声明（04-SPEC §3.2 的 23 条，清单见 {@link UniformCatalog}）。
 *
 * <p><b>只补缺失项</b>：源码里已经出现的 uniform（无论类型是否与 OF 语义一致）一律保留不动 ——
 * 类型不一致时出 WARN，绝不偷偷重写包作者的代码。由此得到幂等性：第二遍转译时全部内建都已存在，
 * 注入集合为空，文本逐字节不变。
 *
 * <p><b>注入点</b>：文件头部区之后（跳过空行 / 注释 / {@code #} 预处理指令），即首条代码之前。
 * 这样既在所有使用之前，又不会插到 {@code #version} / {@code #extension} 之前（GLSL 要求它们靠前）。
 * 头部区之外还有一处约束：包内的 {@code #include} / {@code #define} 由 C 线处理，本类只**跳过读取**，
 * 不解析、不改写（18-PARALLEL §4 D 线"不许做"）。
 *
 * <p><b>注入形态</b>：识别注释 + 单个具名（无实例名）{@link #BLOCK_OPEN} 块 … {@link #BLOCK_CLOSE}，
 * 成员行是 {@link BuiltinUniform#blockMember()} 而非独立 {@code uniform} 行 —— 独立非透明 uniform 行
 * 过不了 Vulkan 驱动编译，匿名块过不了语法（差异点 ④）。扫描侧对应把布局块成员记作"已声明"，
 * 第二遍因此无缺失项 → 文本逐字节不变（幂等）。
 *
 * <p><b>行号契约</b>：诊断的 {@code line} 是**注入前（本阶段输入）的行号**，
 * {@code sourceFile} 留空由 {@link OfGlslTranslator} 经 F3 的 {@code SourceLineMap} 回填原文件。
 * {@link Result#insertIndex()} / {@link Result#insertedLineCount()} 供入口构造输出行号映射。
 */
public final class UniformInjector {

    /** 注入块的识别注释（第二遍转译时它是注释，会被注入点扫描跳过；不参与任何语义）。 */
    public static final String BLOCK_HEADER = "// vkdisp: OF builtin uniforms (04-SPEC 3.2)";

    /** 注入块开行：具名（无实例名）std140 uniform 块，形态与原版 shader 完全同款（差异点 ④）。 */
    public static final String BLOCK_OPEN = "layout(std140) uniform VkDispBuiltins {";

    /** 注入块闭行。 */
    public static final String BLOCK_CLOSE = "};";

    /**
     * 布局限定的具名 uniform 块开头（本类发射的形态，或包源码原版同款写法）：
     * {@code layout(...) uniform <名字> {}，且 {@code {} 必须是行尾 —— 单行整块不进块态
     * （宁可漏记成员走"撞名 → 驱动显式报错"，也不制造假的"块未闭合"）。
     * 块名与 {@link #BLOCK_OPEN} 的名字不必相同 —— 只要是**无实例名**块，成员就在全局作用域。
     */
    private static final java.util.regex.Pattern LAYOUT_UNIFORM_BLOCK_OPEN =
            java.util.regex.Pattern.compile("layout\\s*\\(.*\\)\\s*uniform\\s+\\w+\\s*\\{\\s*$");

    /** 块成员声明行：{@code [精度] 类型 名字[数组];} —— 只识别到名字与类型（幂等记录 + 类型核对）。 */
    private static final java.util.regex.Pattern BLOCK_MEMBER = java.util.regex.Pattern.compile(
            "(?:(?:lowp|mediump|highp)\\s+)?([A-Za-z_]\\w*)\\s+([A-Za-z_]\\w*)\\s*(?:\\[[^]]*\\])?\\s*;");

    private UniformInjector() {}

    /**
     * 注入结果（纯数据）。
     *
     * @param text              注入后的文本；无注入时与输入逐字节相同
     * @param diagnostics       诊断（位置 = 本阶段输入行号；{@code sourceFile} 为 {@code null}，由入口回填）
     * @param injected          本次实际注入的内建 uniform（按 {@link UniformCatalog} 顺序）
     * @param insertIndex       注入点之前原有行数（0 基；0 = 插在文件最前；无注入时无意义）
     * @param insertedLineCount 本次插入的行数（{@link #BLOCK_HEADER} + {@link #BLOCK_OPEN}
     *                          + 收编行 + 缺失内建成员行 + {@link #BLOCK_CLOSE}，即 {@code 收编数 + 缺失数 + 3}）
     */
    public record Result(String text, List<TranslateDiagnostic> diagnostics, List<BuiltinUniform> injected,
            int insertIndex, int insertedLineCount) {

        /** 记录构造：null 归一（结果对象永不为 null 语义，同 F3 契约）。 */
        public Result {
            text = text == null ? "" : text;
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            injected = injected == null ? List.of() : List.copyOf(injected);
        }
    }

    /**
     * 扫描源码并把缺失的 OF 内建 uniform 声明注入到文件头部区之后；同时把**游离在块外的非透明
     * uniform 声明收编进同一个块**（P4.1：Vulkan GLSL 禁止非透明 uniform 在块外，包作者写的
     * {@code uniform float rainStrength;} 这类 OF 方言原生形态必须移动而不是改写 —— 声明文本原样保留，
     * 原行位抹空以保行号契约）。
     *
     * @param source 输入 GLSL（{@code null} 按空串处理）
     * @return 注入结果；永不返回 {@code null}
     */
    public static Result inject(String source) {
        SourceLines lines = SourceLines.of(source);
        List<String> sourceLines = lines.lines();
        List<TranslateDiagnostic> diagnostics = new ArrayList<>();
        Map<String, Integer> declaredAtLine = new LinkedHashMap<>();
        CommentState comments = new CommentState();
        boolean inLayoutBlock = false;
        int layoutBlockStartLine = 0;
        // 收编候选：0 基行下标 → 块内成员文本（原声明去掉 uniform 关键字，声明文本原样）。
        Map<Integer, String> adoptable = new java.util.LinkedHashMap<>();
        java.util.Set<String> adoptedNames = new java.util.HashSet<>();
        for (int index = 0; index < sourceLines.size(); index++) {
            int lineNumber = index + 1;
            String code = comments.stripComments(sourceLines.get(index), lineNumber);
            String trimmed = code.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            // 块内（具名 std140 / 原包自声明块）：无实例名块的成员是全局作用域声明（幂等记录）；} 收尾。
            if (inLayoutBlock) {
                int closeBrace = trimmed.indexOf('}');
                if (closeBrace >= 0) {
                    String before = trimmed.substring(0, closeBrace).strip();
                    java.util.regex.Matcher member = BLOCK_MEMBER.matcher(before);
                    if (member.matches()) {
                        recordDeclaration(member.group(2), member.group(1), lineNumber,
                                declaredAtLine, diagnostics);
                    }
                    inLayoutBlock = false;
                    continue;
                }
                java.util.regex.Matcher member = BLOCK_MEMBER.matcher(trimmed);
                if (member.matches()) {
                    recordDeclaration(member.group(2), member.group(1), lineNumber, declaredAtLine, diagnostics);
                }
                continue;
            }
            if (LAYOUT_UNIFORM_BLOCK_OPEN.matcher(trimmed).matches()) {
                inLayoutBlock = true;
                layoutBlockStartLine = lineNumber;
                continue;
            }
            GlslDeclaration declaration = GlslDeclaration.parse(code);
            if (declaration == null || !"uniform".equals(declaration.keyword)) {
                continue;
            }
            if (declaration.block || declaration.name == null) {
                // 原包自声明的多行块（无 layout 前缀）：进入块态，成员不得被当成游离声明收编。
                if (declaration.block) {
                    inLayoutBlock = true;
                    layoutBlockStartLine = lineNumber;
                }
                continue;
            }
            recordDeclaration(declaration.name, declaration.type, lineNumber, declaredAtLine, diagnostics);
            // 游离非透明 uniform → 收编候选；采样器/图像类型留原位（Vulkan 允许块外，且不能进 UBO）。
            // 边界：① 重名只收首现（重复声明已 WARN，两个同名成员进块会撞车）；
            //      ② 语句前后有别的代码（同行多语句）不收 —— 抹行会连带删掉别的语句，宁可留给驱动报错；
            //      ③ 多行声明（无分号）不收（声明文本跨行，收编会截断）。
            if (declaration.terminated && !isOpaqueType(declaration.type)
                    && !adoptedNames.contains(declaration.name)) {
                int semi = code.indexOf(';', declaration.keywordEnd);
                String prefix = code.substring(0, declaration.keywordStart).strip();
                String suffix = semi >= 0 ? code.substring(semi + 1).strip() : "x";
                if (semi >= 0 && prefix.isEmpty() && suffix.isEmpty()) {
                    adoptedNames.add(declaration.name);
                    adoptable.put(index, code.substring(declaration.keywordEnd, semi + 1).strip());
                }
            }
        }
        if (inLayoutBlock) {
            diagnostics.add(TranslateDiagnostic.error("uniform 块未闭合（起始于第 "
                    + layoutBlockStartLine + " 行）", null, layoutBlockStartLine));
        }
        if (comments.inBlockComment()) {
            diagnostics.add(0, TranslateDiagnostic.error("块注释未闭合（起始于第 "
                    + comments.blockCommentStartLine() + " 行）", null, comments.blockCommentStartLine()));
        }
        int insertIndex = headerEnd(sourceLines);
        if (insertIndex >= sourceLines.size()) {
            diagnostics.add(TranslateDiagnostic.warn(
                    "输入不含任何代码行（仅空行 / 注释 / 预处理指令），跳过 OF 内建 uniform 注入", null, 0));
            return new Result(lines.text(), diagnostics, List.of(), 0, 0);
        }
        List<BuiltinUniform> missing = new ArrayList<>();
        for (BuiltinUniform uniform : UniformCatalog.uniforms()) {
            if (!declaredAtLine.containsKey(uniform.name())) {
                missing.add(uniform);
            }
        }
        if (missing.isEmpty() && adoptable.isEmpty()) {
            return new Result(lines.text(), diagnostics, List.of(), 0, 0);
        }
        if (!adoptable.isEmpty()) {
            // T11 显式：收编是方言适配不是降级，一条 INFO 报数量（逐条刷屏会淹没真正的 WARN）。
            diagnostics.add(TranslateDiagnostic.info(
                    adoptable.size() + " 条游离非透明 uniform 声明收编进 VkDispBuiltins 块"
                            + "（Vulkan 要求非透明 uniform 在块内；声明文本原样，仅移动位置）", null, 0));
        }
        // 发射形态 = 识别注释 + 具名无实例名 std140 块（差异点 ④）：独立非透明 uniform 行过不了 Vulkan 编译。
        // 块内顺序 = 收编的包声明（源码出现序）在前、缺失内建（目录序）在后 —— 确定且幂等。
        List<String> injectedLines = new ArrayList<>(adoptable.size() + missing.size() + 3);
        injectedLines.add(BLOCK_HEADER);
        injectedLines.add(BLOCK_OPEN);
        injectedLines.addAll(adoptable.values());
        for (BuiltinUniform uniform : missing) {
            injectedLines.add(uniform.blockMember());
        }
        injectedLines.add(BLOCK_CLOSE);
        // 收编行**抹空而非删除**（保行号契约：后续行下标不动，insertIndex / insertedLineCount 语义不变）。
        List<String> content = new ArrayList<>(sourceLines);
        for (Integer index : adoptable.keySet()) {
            content.set(index, "");
        }
        int at = Math.max(0, Math.min(insertIndex, content.size()));
        // CRLF 文件：插入行按文件主行尾补 \r（与 SourceLines.insertLines 同款约定，不许混入裸 LF）。
        String carriageReturn = sourceLines.stream().anyMatch(line -> line.endsWith("\r")) ? "\r" : "";
        List<String> merged = new ArrayList<>(content.size() + injectedLines.size());
        merged.addAll(content.subList(0, at));
        for (String line : injectedLines) {
            merged.add(line + carriageReturn);
        }
        merged.addAll(content.subList(at, content.size()));
        String text = SourceLines.join(merged, lines.endsWithNewline() || at >= content.size());
        return new Result(text, diagnostics, missing, insertIndex, injectedLines.size());
    }

    /** 透明性判定：采样器 / 图像类型不能进 UBO 块（Vulkan GLSL 公开语义），留在块外。 */
    private static boolean isOpaqueType(String type) {
        return type != null && (type.matches("(?:u|i)?sampler\\w*") || type.matches("(?:u|i)?image\\w*"));
    }

    /**
     * 记录一条已声明的 uniform：重名 → WARN（T11，不吞）；OF 内建但类型与 §3.2 不符 → WARN
     * （保留包内声明，不注入不重写）。独立声明与匿名块成员共用同一套判定。
     */
    private static void recordDeclaration(String name, String type, int lineNumber,
            Map<String, Integer> declaredAtLine, List<TranslateDiagnostic> diagnostics) {
        Integer firstLine = declaredAtLine.putIfAbsent(name, lineNumber);
        if (firstLine != null) {
            diagnostics.add(TranslateDiagnostic.warn("uniform " + name
                    + " 重复声明（首次出现在第 " + firstLine + " 行）", null, lineNumber));
            return;
        }
        BuiltinUniform builtin = UniformCatalog.find(name);
        if (builtin != null && type != null && !builtin.type().equals(type)) {
            diagnostics.add(TranslateDiagnostic.warn("OF 内建 uniform " + builtin.name()
                    + " 在包内声明为 " + type + "，OF 语义为 " + builtin.type()
                    + "（保留包内声明，不注入、不重写）", null, lineNumber));
        }
    }

    /**
     * 文件头部区的结束下标（0 基，等于"其之前有 index 行"）：跳过空行、注释行与 {@code #} 预处理指令，
     * 停在首条代码行。没有代码行时返回行数（调用方据此跳过注入）。
     */
    private static int headerEnd(List<String> sourceLines) {
        CommentState comments = new CommentState();
        int index = 0;
        while (index < sourceLines.size()) {
            String code = comments.stripComments(sourceLines.get(index), index + 1);
            String trimmed = code.strip();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                break;
            }
            index++;
        }
        return index;
    }
}
