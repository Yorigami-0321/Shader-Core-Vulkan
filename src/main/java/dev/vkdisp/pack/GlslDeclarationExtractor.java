package dev.vkdisp.pack;

import dev.vkdisp.glsl.TranslateDiagnostic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【参考调研】GLSL 声明提取（顶点属性 / uniform）—— 填充 F2 的 Program#uniforms 与 #vertexAttributes
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §4「顶点格式扩展」（属性表 + 2026-09-29 复核注记）、
 *    §3.2（OF 内建 uniform 语义）、§3.3（OF 老式 attribute/varying 语法）；
 *    ② OptiFine 规范文档 {@code OptiFineDoc/doc/shaders.txt}「Attributes」节（**仅取属性名与类型这类
 *    格式事实，零文本搬用**；sp614x/optifine 无 LICENSE = ARR，按 07-CONSTRAINTS X20 不并入其文本表达）；
 *    ③ Iris 官方文档 {@code shaders.properties}「Reference/Attributes」与「OpenGL Profiles」节
 *    （兼容档 {@code gl_*} ↔ 核心档 {@code va*} 的对应关系；Iris 为 LGPL-3.0，同样只取格式事实）。
 *    许可证：本文件为独立编写的纯 Java 解析器，不含任何第三方项目代码，仅含不受版权保护的格式事实。
 *    → 能否并入本项目（MIT）：可以
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：OF/Iris 的属性命名事实（见下方 {@link #ATTRIBUTE_ALIASES} 的逐条来源注释）；
 *    uniform 声明的 GLSL 词法事实（{@code uniform <类型> <名字>;}，以及 {@code uniform 块名 { ... }} 形态）。
 * 2. 备选：复用 D 线的 {@code glsl/translate/GlslDeclaration} —— **不可行**：它是包级私有 final class
 *    （字段与方法均无 {@code public}），跨包不可见。三者职责也不同：D 线的 `GlslDeclaration` 关心
 *    关键字在行内的**下标**（用于原地改写），本类只关心**类型与名字**（用于建模型）。
 *    改 D 线工具的可见性会触碰 env-2 的独占路径（18-PARALLEL §8.1），故本类自包含。
 *    ⚠️ 已知重复：去注释与行内声明扫描与 D 线同族。若后续 env-1 决定统一，应走 §3.2 或由 D 线
 *    开放一个 public 的“声明视图”入口，本类随之退化为薄适配。
 * 3. 我们的差异点：
 *    ① **只取名字与类型，不建 AST、不做语法校验** —— 半截声明/无法识别的形态一律降级为诊断，不抛异常（T11）；
 *    ② 属性名到 F2 枚举的映射**逐条标注权威来源**，没有来源的名字不猜（07-CONSTRAINTS X9）；
 *    ③ 输入必须是 include 已展开、转译**之前**的文本 —— 这样既覆盖被包含文件里的声明，
 *       又不会把 D 线 {@code UniformInjector} 注入的内建 uniform 误记成"包自己声明的"；
 *    ④ 不解析 uniform 块内的成员（块内名字需块级解析，不在本阶段范围，显式记 INFO）。
 * 4. 许可证核对结论：本项目 MIT；参考一律只取格式事实、零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径（包加载时一次性），清晰优先，不做任何性能优化（18-PARALLEL §7.7、T14/X14）。
 */
final class GlslDeclarationExtractor {

    /**
     * 顶点属性字面名 → F2 枚举的别名表。**每一条都有权威来源**（07-CONSTRAINTS X9：不许猜值）。
     *
     * <p>来源缩写：
     * <ul>
     *   <li><b>OF</b> = OptiFine 规范文档 {@code OptiFineDoc/doc/shaders.txt}「Attributes」节；</li>
     *   <li><b>Iris</b> = Iris 官方文档 {@code shaders.properties}「Reference/Attributes」与
     *       「OpenGL Profiles（兼容档 gl_* ↔ 核心档 va*）」；</li>
     *   <li><b>Ftx</b> = 本仓库 D 线 {@code FtransformExpander.POSITION_CANDIDATES} 既有先例；</li>
     *   <li><b>Spec4</b> = 本仓库 docs/04-SPEC.md §4 表所用的内部命名（枚举常量名本身）。</li>
     * </ul>
     *
     * <p>🔴 刻意**未收录**的名字（无权威来源，不猜）：
     * {@code gl_MultiTexCoord2} —— Iris 文档中它与 {@code gl_MultiTexCoord1} 一同指向 light 语义，
     * 与核心档 {@code vaUV1}（overlay）的对应关系未见权威表述，故不映射；
     * 命中的名字会以 INFO 诊断显式暴露，而不是静默丢弃。
     */
    private static final Map<String, VertexAttribute> ATTRIBUTE_ALIASES = buildAliases();

    /** 声明关键字：我们只关心这三类（`out` / `varying` 是阶段间传递，不属顶点输入）。 */
    private static final Set<String> DECLARATION_KEYWORDS = Set.of("attribute", "in", "uniform");

    /** 允许出现在声明关键字之前的限定符（现代 GLSL 仍保留）。 */
    private static final Set<String> LEADING_QUALIFIERS = Set.of(
            "flat", "smooth", "noperspective", "centroid", "patch", "sample", "invariant", "precise");

    /** 提取结果：本程序源码声明的 uniform 与使用的顶点属性 + 诊断。 */
    record Result(
            List<UniformDecl> uniforms,
            List<VertexAttribute> attributes,
            List<TranslateDiagnostic> diagnostics) {

        /** 归一构造：三个列表冻结为不可变。 */
        Result {
            uniforms = uniforms == null ? List.of() : List.copyOf(uniforms);
            attributes = attributes == null ? List.of() : List.copyOf(attributes);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    private GlslDeclarationExtractor() {}

    /**
     * 从预处理后的 GLSL 文本里提取 uniform 与顶点属性声明。
     *
     * <p>永不抛非受检异常：无法识别的形态降级为诊断（T11）。
     *
     * @param source      已展开 include、尚未转译的 GLSL 文本（{@code null} 按空串处理）
     * @param sourceFile  源文件名（仅用于诊断定位，可为 {@code null}）
     * @param vertexStage 是否为顶点阶段。**只有顶点阶段才提取顶点属性** ——
     *                    片元阶段的 {@code in} 是"顶点阶段传来的插值变量"，语义完全不同，
     *                    混为一谈会把 varying 误记成顶点属性。
     */
    static Result extract(String source, String sourceFile, boolean vertexStage) {
        List<UniformDecl> uniforms = new ArrayList<>();
        List<VertexAttribute> attributes = new ArrayList<>();
        List<TranslateDiagnostic> diagnostics = new ArrayList<>();
        if (source == null || source.isBlank()) {
            return new Result(uniforms, attributes, diagnostics);
        }

        // 名字去重：同一文件里同名声明只记一次（重复声明由 D 线的 AttributeRewriter 出 WARN）。
        Set<String> seenUniforms = new java.util.LinkedHashSet<>();
        Set<String> seenAttributes = new java.util.LinkedHashSet<>();

        List<String> codeLines = stripComments(source);
        for (int index = 0; index < codeLines.size(); index++) {
            int lineNumber = index + 1;
            Scan scan = scanDeclaration(codeLines.get(index));
            if (scan == null) {
                continue;
            }
            if (scan.block) {
                // uniform 块（uniform Foo { ... }）：块内成员需块级解析，不在本阶段范围 —— 显式可见，不静默。
                diagnostics.add(TranslateDiagnostic.of(
                        TranslateDiagnostic.Severity.INFO,
                        "vkdisp: 检测到 uniform 块 '" + scan.type + "'，块内成员解析不在当前阶段范围",
                        sourceFile, lineNumber));
                continue;
            }
            if (scan.type == null || scan.name == null) {
                diagnostics.add(TranslateDiagnostic.of(
                        TranslateDiagnostic.Severity.WARN,
                        "vkdisp: " + scan.keyword + " 声明缺少类型或名字，已忽略（T11 显式可见）",
                        sourceFile, lineNumber));
                continue;
            }
            if ("uniform".equals(scan.keyword)) {
                if (seenUniforms.add(scan.name)) {
                    uniforms.add(new UniformDecl(scan.name, scan.type));
                }
                continue;
            }
            if (!vertexStage) {
                // 片元的 `in` 是插值输入，不是顶点属性 —— 静默跳过（这不是降级，是语义上就不该收）。
                continue;
            }
            handleAttribute(scan, sourceFile, lineNumber, attributes, seenAttributes, diagnostics);
        }
        return new Result(uniforms, attributes, diagnostics);
    }

    /** 顶点属性：映射到 F2 枚举；映射不到时记 INFO（不静默丢弃，也不猜）。 */
    private static void handleAttribute(
            Scan scan,
            String sourceFile,
            int lineNumber,
            List<VertexAttribute> attributes,
            Set<String> seenAttributes,
            List<TranslateDiagnostic> diagnostics) {
        VertexAttribute mapped = ATTRIBUTE_ALIASES.get(scan.name);
        if (mapped == null) {
            diagnostics.add(TranslateDiagnostic.of(
                    TranslateDiagnostic.Severity.INFO,
                    "vkdisp: 顶点属性 '" + scan.name + "' 不在已知别名表内，已忽略（无权威来源的名字不猜，X9）",
                    sourceFile, lineNumber));
            return;
        }
        if (seenAttributes.add(mapped.name())) {
            attributes.add(mapped);
        }
    }

    // ------------------------------------------------------------------ 别名表

    /**
     * 别名表收录的**全部拼写**（C3 一致性报告要拿它和包实际用的属性名对差，§4.1 第 7 项）。
     *
     * <p>🔖 只读快照：本表是 `private static final`，报告不许改它（改了会让「同一名字映射到哪个属性」
     * 随加载顺序变化 —— 上面 {@code alias(...)} 的冲突抛错就是为了挡这件事）。
     */
    static Set<String> attributeAliasNames() {
        return ATTRIBUTE_ALIASES.keySet();
    }

    private static Map<String, VertexAttribute> buildAliases() {
        Map<String, VertexAttribute> aliases = new LinkedHashMap<>();
        // 位置：核心档 vaPosition / 兼容档 gl_Vertex（Iris「OpenGL Profiles」；Ftx 先例同口径）/ §4 内部名
        alias(aliases, VertexAttribute.Position, "vaPosition", "gl_Vertex", "Position");
        // 顶点色：vaColor / gl_Color（Iris）
        alias(aliases, VertexAttribute.Color, "vaColor", "gl_Color", "Color");
        // 法线：核心档真名是 vaNormal（Iris / OF）；§4 内部名 Normal
        alias(aliases, VertexAttribute.Normal, "vaNormal", "gl_Normal", "Normal");
        // 主纹理：vaUV0 / gl_MultiTexCoord0（Iris）
        alias(aliases, VertexAttribute.UV0, "vaUV0", "gl_MultiTexCoord0", "UV0");
        // overlay：核心档 vaUV1（OF / Iris）；兼容档无独立对应名，故只收核心档
        alias(aliases, VertexAttribute.UV1, "vaUV1", "UV1");
        // 光照贴图：核心档 vaUV2 / 兼容档 gl_MultiTexCoord1（Iris 明确：gl_MultiTexCoord1 ↔ vaUV2）
        alias(aliases, VertexAttribute.UV2, "vaUV2", "gl_MultiTexCoord1", "UV2");
        // OF 扩展属性：官方只有一种拼写，与 F2 枚举名一致
        alias(aliases, VertexAttribute.mc_Entity, "mc_Entity");
        alias(aliases, VertexAttribute.mc_midTexCoord, "mc_midTexCoord");
        alias(aliases, VertexAttribute.at_tangent, "at_tangent");
        alias(aliases, VertexAttribute.at_velocity, "at_velocity");
        alias(aliases, VertexAttribute.at_midBlock, "at_midBlock");
        return Map.copyOf(aliases);
    }

    private static void alias(
            Map<String, VertexAttribute> target, VertexAttribute attribute, String... names) {
        for (String name : names) {
            VertexAttribute previous = target.put(name, attribute);
            if (previous != null && previous != attribute) {
                // 别名冲突会让映射结果随插入顺序变化 —— 冷路径上直接暴露成契约级错误，不许静默。
                throw new IllegalStateException(
                        "vkdisp: 顶点属性别名 '" + name + "' 同时映射到 " + previous + " 与 " + attribute);
            }
        }
    }

    // ------------------------------------------------------------------ 词法与扫描

    /**
     * 一行声明的扫描结果（本类只关心关键字 / 类型 / 名字 / 是否块）。
     *
     * @param keyword attribute / in / uniform
     * @param type    声明类型（如 vec3 / mat4 / sampler2D）；null = 缺失
     * @param name    声明名；null = 缺失（或 uniform 块）
     * @param block   是否为 uniform 块
     */
    private record Scan(String keyword, String type, String name, boolean block) {}

    /**
     * 扫描一行（无注释视图）。不是我们关心的声明时返回 {@code null}。
     *
     * <p>跳过前导限定符（flat / smooth / …）；{@code layout(...)} 这种限定行直接跳过
     * （OF 包里不用于顶点属性声明，识别成本高、收益低）。
     */
    private static Scan scanDeclaration(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        int pos = skipWhitespace(code, 0);
        String keyword = null;
        if (code.startsWith("layout", pos)) {
            return null;
        }
        while (true) {
            int[] span = readIdentifier(code, pos);
            if (span == null) {
                return null;
            }
            String token = code.substring(span[0], span[1]);
            if (DECLARATION_KEYWORDS.contains(token)) {
                keyword = token;
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
            return new Scan(keyword, null, null, false);
        }
        String type = code.substring(typeSpan[0], typeSpan[1]);
        pos = skipWhitespace(code, typeSpan[1]);
        if (pos < code.length() && code.charAt(pos) == '{') {
            return new Scan(keyword, type, null, true);
        }
        int[] nameSpan = readIdentifier(code, pos);
        if (nameSpan == null) {
            return new Scan(keyword, type, null, false);
        }
        return new Scan(keyword, type, code.substring(nameSpan[0], nameSpan[1]), false);
    }

    /**
     * 把源码逐行转成**等长**的无注释视图：注释区间替换为空格，行数与列宽都不变。
     *
     * <p>🔴 **A1 的合并点**（2026-10-10）：本方法原先自带**第二套**注释状态机（35 行，
     * 与 {@code glsl/translate} 的那份同义不同源 —— 类注释里自认的重复就是这一处）。
     * 现在它只是 {@link dev.vkdisp.glsl.lexer.GlslTokens#codeViews} 的一层薄封装：
     * **全仓只有一个地方决定什么叫注释/字符串**（`19` §2.2 病根 (a)）。
     * 顺带得到的两处口径统一：① 字符串内容也被抹平（旧实现不抹 ⇒ 字符串里的 {@code uniform ...;} 会被
     * 当成声明）；② 未闭合块注释由词法源出 ERROR —— 本阶**先不把那条诊断接进来**，
     * 免得改变现有包的判定结果（接它属 A2 的语义轮，见 {@code 19} §2.6）。
     */
    private static List<String> stripComments(String source) {
        return dev.vkdisp.glsl.lexer.GlslTokens.codeViews(source, null);
    }

    /** 跳过空格与制表符（注释已在无注释视图里变成空格）。 */
    private static int skipWhitespace(String code, int from) {
        int pos = from;
        while (pos < code.length() && (code.charAt(pos) == ' ' || code.charAt(pos) == '\t')) {
            pos++;
        }
        return pos;
    }

    /** 读取一个 GLSL 标识符（{@code [start, end)}）；当前位置不是标识符起始时返回 {@code null}。 */
    private static int[] readIdentifier(String code, int from) {
        int pos = from;
        if (pos >= code.length()) {
            return null;
        }
        if (!isIdentifierStart(code.charAt(pos))) {
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

    /** 仅供测试与诊断展示：别名表快照（不可变）。 */
    static Map<String, VertexAttribute> aliasSnapshot() {
        return ATTRIBUTE_ALIASES;
    }
}
