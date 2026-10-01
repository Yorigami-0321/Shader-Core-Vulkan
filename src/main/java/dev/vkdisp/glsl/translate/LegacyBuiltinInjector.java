package dev.vkdisp.glsl.translate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import dev.vkdisp.glsl.TranslateDiagnostic;

/**
 * 【参考调研】D 线旧内建名替换与属性声明注入（141 阶段矩阵修复）/ shaderc 实测原文 + GLSL/OpenGL 公开语义
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 runClient 实测 shaderc 报错取证，两阶段：
 *    ① 141 矩阵首错 —— {@code 'gl_MultiTexCoord0' : undeclared identifier} 33×VERTEX、
 *    {@code 'gl_TextureMatrix' : undeclared identifier} 20×VERTEX、
 *    {@code 'Position' : undeclared identifier} 2×VERTEX（FtransformExpander 冻结字面名展开后用而未声明）；
 *    ② 补声明后的第二跑 —— {@code 'gl_TextureMatrix' / 'gl_MultiTexCoord0' / 'gl_Color' :
 *    identifiers starting with "gl_" are reserved} 91×VERTEX：**声明落地后驱动仍拒绝 gl_ 前缀**
 *    （GLSL 公开词法：gl_ 前缀保留给语言内建，用户代码声明与使用皆非法）→ 注入 gl_ 名不是出路，
 *    必须把旧名替换成语义等价的合法名。这两组原文即本类「替换 + 注入」双职责的直接依据。
 *    语义映射依据：gl_MultiTexCoord0 ↔ UV0 / gl_MultiTexCoord1 ↔ UV2 / gl_Color ↔ Color /
 *    gl_Normal ↔ Normal / gl_Vertex ↔ Position —— 与本仓库 GlslDeclarationExtractor.ATTRIBUTE_ALIASES
 *    同源（Iris「OpenGL Profiles / Attributes」兼容档 ↔ 核心档映射 + 04-SPEC §4 属性表，逐条来源见该表注释）；
 *    gl_ProjectionMatrix / gl_ModelViewMatrix ↔ gbufferProjection / gbufferModelView —— 同一矩阵语义的
 *    两个名字（04-SPEC §3.2 冻结 uniform 就是 OF 的 gbuffer 投影 / 视图矩阵，宿主按该表上传真值）；
 *    gl_ModelViewProjectionMatrix = 投影 × 视图（矩阵乘法定义）；gl_NormalMatrix =
 *    transpose(inverse(mat3(模型视图)))（OpenGL 固定功能法线矩阵的公开定义）；
 *    gl_TextureMatrix[n] = 单位阵（OpenGL 纹理矩阵初值即单位阵；本引擎无固定功能纹理变换，
 *    UV 直接按顶点属性采样 —— 替换成单位阵即本引擎的真实语义）。
 *    外部候选 IrisShaders/glsl-transformer（GPL-3.0 + 例外条款，18-PARALLEL §4 D 线明示
 *    "按禁止处理"）→ 按禁止处理（07-CONSTRAINTS L12 §1.3 陷阱 2 / X21），零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 实现，不含任何外部项目代码。
 *    → 能否并入本项目（MIT）：可以 —— 只采纳不受版权保护的驱动报错事实、公开语言语义与仓库文档事实
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：GLSL 公开词法（gl_ 前缀保留）+ GLSL 1.20 公开类型语义（旧顶点属性
 *    gl_Vertex / gl_Color / gl_MultiTexCoord0..7 为 vec4、gl_Normal 为 vec3；固定功能矩阵
 *    gl_ModelViewMatrix / gl_ProjectionMatrix / gl_ModelViewProjectionMatrix 为 mat4、
 *    gl_NormalMatrix 为 mat3、gl_TextureMatrix 为 mat4[8]）+ 04-SPEC §4（Position 冻结字面名、
 *    vec3f；绑定键是名字，location 由下游补写）。
 * 2. 备选：无 —— 不建 AST、不引入解析框架；{@link GlslTextScan} 等长无注释视图 + token 级改写 +
 *    行级正则声明识别，够用即停（08-TESTING §8.1 达标即停）。
 * 3. 我们的差异点：① **替换 + 注入双职责**：属性与矩阵旧名先做 token 级等行替换（行数不变，
 *    行号映射不被切断），注入只承担「替换后仍无声明的属性名」与 ftransform 展开 / gl_Vertex
 *    替换引入的冻结 {@code Position}；② 替换**使用驱动**：表内旧名按完整标识符 token 精确命中
 *    才替换（注释 / 字符串 / 预处理指令体内不算），未用不动 → 输出是不动点；表外旧名（如
 *    gl_MultiTexCoord2、gl_TextureMatrixOffset、gl_TexCoord）一律不猜（X9），真被使用时由驱动
 *    reserved 显式报错（T11 可见）再扩表；③ **属性类替换与注入仅 VERTEX 阶段**（片元没有顶点
 *    属性语义；片元真用到时驱动显式报错）——注入裸 {@code in} 行、保留**合法的 §4 名字**
 *    （UV0 / UV2 / Color / Normal / Position），{@link IoLocationAdapter} 随后补
 *    {@code layout(location)}（P4.4 起顶点 in 也补；反射表按 element.name() 命名绑定，
 *    替换后的名字正是顶点格式元素名）；④ 矩阵旧名**不注入声明** —— 替换直接落到宿主按
 *    04-SPEC §3.2 上传的 gbuffer 投影 / 视图矩阵或其合成表达式上，天然满足 Vulkan 块约束，
 *    不产生游离 uniform；⑤ **声明行保护**：表达式型替换（vec4(Position, 1.0) /
 *    transpose(inverse(...)) 等）遇到该旧名的显式声明行时不动 token（否则声明名位置会落进
 *    表达式把语法打坏），gl_Vertex 声明行改名成 Position、gl_TextureMatrix 声明行整行保留 ——
 *    残留的 gl_ 声明由驱动显式报错（T11）；⑥ **声明识别容忍三种形态**：裸声明、
 *    {@code layout(...)} 前缀（⑥ 的输出即第二遍输入）、无实例名块成员，外加逗号多声明 ——
 *    GlslDeclaration.parse 对 layout 行返回 null，不能拿它做本级的已声明判定，本类自建正则；
 *    ⑦ 行号契约：与 ⑥/⑦ 级同款 —— {@link Result#insertIndex()} /
 *    {@link Result#insertedLineCount()} 供入口构造 "最终行 → C 线输出行"映射，
 *    本级诊断行号在 C 线输出坐标系（插入之前）；⑧ 成功替换 / 注入不产生诊断（与 ①–④ 级
 *    同口径；未闭合块注释的 ERROR 仍由 {@link GlslTextScan#codeViews} 按各变换类同款口径报出）。
 * 4. 许可证核对结论：本项目 MIT；GPL-3.0+例外参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径（包加载期一次）；候选 × 行的线性扫描 + 行级正则，无缓存、无预优化
 *    （18-PARALLEL §7.7、T14 达标即停）。
 */
/**
 * GLSL 1.20 旧内建的 token 级替换 + 「用而未声明」属性名的声明注入。
 *
 * <p><b>替换表</b>（冻结；等行改写、行数不变）：
 * <ul>
 *   <li>属性类（仅 {@link ShaderStage#VERTEX}）：{@code gl_MultiTexCoord0 → UV0}、
 *       {@code gl_MultiTexCoord1 → UV2}、{@code gl_Color → Color}、{@code gl_Normal → Normal}、
 *       {@code gl_Vertex → 与 ftransform 同口径的操作数}（包内已声明位置属性按声明类型取
 *       {@code Position} / {@code vaPosition} 或 {@code vec4(name, 1.0)}；未声明按冻结字面名
 *       {@code vec4(Position, 1.0)}，随后本级注入 {@code in vec3 Position;}）；</li>
 *   <li>矩阵类（任何阶段）：{@code gl_ProjectionMatrix → gbufferProjection}、
 *       {@code gl_ModelViewMatrix → gbufferModelView}、
 *       {@code gl_ModelViewProjectionMatrix → (gbufferProjection * gbufferModelView)}、
 *       {@code gl_NormalMatrix → (transpose(inverse(mat3(gbufferModelView))))}、
 *       {@code gl_TextureMatrix[下标] → mat4(1.0)}（下标随 token 一起消费；裸名无下标时保留
 *       原样交驱动显式报错 —— 裸数组名只能出现在需要声明数组的上下文里，替换无正确答案）。</li>
 * </ul>
 *
 * <p><b>注入形态</b>（只注入「用而未声明」的属性名；候选表冻结）：
 * {@code in vec4 UV0;} / {@code in vec4 UV2;} / {@code in vec4 Color;} / {@code in vec3 Normal;} /
 * {@code in vec3 Position;}，插在文件头部区末尾（首条代码行之前，04-SPEC §3.3 与
 * {@link GlslTextScan#headerEnd} 同口径），location 由下游 {@link IoLocationAdapter} 补写。
 *
 * <p><b>幂等</b>：第二遍输入是第一遍输出 —— 属性注入的门是「gl_ 旧名仍在用」，替换后自动关闭；
 * {@code Position} 已有 layout 形态声明 → 零替换、零插入、文本逐字节不变、零诊断。
 *
 * <p><b>行号契约</b>：替换等行；本级可能插入属性声明行（流水线里的首个插入级）；诊断的
 * {@code line} 是**本级输入**（= C 线输出）行号，由 {@link OfGlslTranslator} 经上游映射回填
 * 原文件；{@link Result#insertIndex()} / {@link Result#insertedLineCount()} 供入口把本级位移
 * 合成进端到端行号映射（F3 的 {@link dev.vkdisp.glsl.SourceLineMap}）。
 */
public final class LegacyBuiltinInjector {

    /** 标识符（GLSL 公开词法）。 */
    private static final String IDENT = "[A-Za-z_][A-Za-z0-9_]*";

    /** 可选数组后缀（{@code [8]}）。 */
    private static final String ARRAY_SUFFIX = "(?:\\s*\\[[^\\]]*\\])?";

    /** gl_Vertex 未声明时的冻结操作数（与 {@link FtransformExpander} 展开式同口径）。 */
    private static final String DEFAULT_VERTEX_OPERAND = "vec4(Position, 1.0)";

    /** 纹理矩阵旧名（带下标消费逻辑，不进普通替换表）。 */
    private static final String TEXTURE_MATRIX_LEGACY = "gl_TextureMatrix";

    private static final String IDENTITY_MATRIX = "mat4(1.0)";

    /**
     * 标识符替换（token 精确相等才命中）。
     *
     * @param legacy      旧内建名
     * @param replacement 替换文本；{@code null} = 按包内声明动态计算（gl_Vertex）
     * @param vertexOnly  仅顶点阶段替换（属性语义只在顶点阶段存在）
     */
    private record Rename(String legacy, String replacement, boolean vertexOnly) {}

    /** 替换表（确定性顺序）。gl_TextureMatrix 带下标消费，单独处理。 */
    private static final List<Rename> RENAMES = List.of(
            new Rename("gl_MultiTexCoord0", "UV0", true),
            new Rename("gl_MultiTexCoord1", "UV2", true),
            new Rename("gl_Color", "Color", true),
            new Rename("gl_Normal", "Normal", true),
            new Rename("gl_Vertex", null, true),
            new Rename("gl_ModelViewMatrix", "gbufferModelView", false),
            new Rename("gl_ProjectionMatrix", "gbufferProjection", false),
            new Rename("gl_ModelViewProjectionMatrix", "(gbufferProjection * gbufferModelView)",
                    false),
            new Rename("gl_NormalMatrix", "(transpose(inverse(mat3(gbufferModelView))))", false));

    private static final Map<String, Rename> RENAME_BY_NAME = index(RENAMES);

    /**
     * 属性注入候选（仅 VERTEX）：旧名（使用驱动的门；{@code null} = 门是「合法名已出现」）
     * + 注入的合法名与声明行 + 已声明识别的两种形态。
     */
    private record Injection(String legacy, String name, String declaration,
            Pattern keywordForm, Pattern bareForm) {}

    private static final List<Injection> INJECTIONS = List.of(
            injection("gl_MultiTexCoord0", "UV0", "in vec4 UV0;"),
            injection("gl_MultiTexCoord1", "UV2", "in vec4 UV2;"),
            injection("gl_Color", "Color", "in vec4 Color;"),
            injection("gl_Normal", "Normal", "in vec3 Normal;"),
            // Position：门不是旧名而是「替换后的文本里出现了合法名 Position」——
            // ftransform 展开、gl_Vertex 替换、包内自用都会落到这里；已声明则跳过。
            injection(null, "Position", "in vec3 Position;"));

    /** 旧名的显式声明行正则（声明行保护用；声明识别见 {@link Injection}）。 */
    private static final Map<String, Pattern[]> DECL_FORMS = buildDeclForms();

    private LegacyBuiltinInjector() {}

    /**
     * 替换 + 注入结果（纯数据）。
     *
     * @param text              处理后的文本；无需处理时与输入逐字节相同
     * @param diagnostics       诊断（成功不产生；位置 = 本级输入行号，{@code sourceFile}
     *                          为 {@code null}，由入口回填）
     * @param insertIndex       插入点之前原有行数（0 基；无插入时为 0）
     * @param insertedLineCount 本次插入的行数（无插入时为 0）
     */
    public record Result(String text, List<TranslateDiagnostic> diagnostics,
            int insertIndex, int insertedLineCount) {

        /** 记录构造：null 归一（结果对象永不为 null 语义，同 F3 契约）。 */
        public Result {
            text = text == null ? "" : text;
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            insertIndex = Math.max(0, insertIndex);
            insertedLineCount = Math.max(0, insertedLineCount);
        }
    }

    /**
     * 替换旧内建名并给「用而未声明」的属性名补声明（属性类仅顶点阶段；
     * 行数 = 原行数 + 注入行数）。
     *
     * @param stage  着色器阶段（{@code null} 按非顶点处理：属性类跳过、矩阵类照替换）
     * @param source 输入 GLSL（{@code null} 按空串处理）
     * @return 处理结果；永不返回 {@code null}
     */
    public static Result inject(ShaderStage stage, String source) {
        SourceLines lines = SourceLines.of(source);
        List<String> rawLines = lines.lines();
        List<TranslateDiagnostic> diagnostics = new ArrayList<>();
        List<String> codeLines = GlslTextScan.codeViews(rawLines, diagnostics);
        boolean[] skip = GlslTextScan.preprocessorSkipLines(rawLines, codeLines);

        // 使用驱动的门（替换前统计：替换会消灭旧名 token）。
        boolean vertexStage = stage == ShaderStage.VERTEX;
        Map<String, Boolean> legacyUsed = new HashMap<>();
        for (Rename rename : RENAMES) {
            legacyUsed.put(rename.legacy(), isUsed(rename.legacy(), codeLines, skip));
        }
        legacyUsed.put(TEXTURE_MATRIX_LEGACY,
                isUsed(TEXTURE_MATRIX_LEGACY, codeLines, skip));

        // gl_Vertex 的替换操作数（与 FtransformExpander 同口径：包内声明优先）。
        String glVertexOperand = glVertexReplacement(declaredPosition(codeLines, skip));

        // 等行替换：raw 与无注释视图按同一组 token 区间同步改写（注释 / 字符串原样保留）。
        List<String> subRaw = new ArrayList<>(rawLines.size());
        List<String> subCode = new ArrayList<>(codeLines.size());
        for (int index = 0; index < rawLines.size(); index++) {
            if (skip[index]) {
                subRaw.add(rawLines.get(index));
                subCode.add(codeLines.get(index));
                continue;
            }
            Line rewritten = rewriteLine(rawLines.get(index), codeLines.get(index),
                    vertexStage, glVertexOperand, legacyUsed);
            subRaw.add(rewritten.raw());
            subCode.add(rewritten.code());
        }

        // 注入：只补「用而未声明」的属性名。
        List<String> toInject = new ArrayList<>();
        if (vertexStage) {
            for (Injection injection : INJECTIONS) {
                boolean gate = injection.legacy() == null
                        ? isUsed(injection.name(), subCode, skip)
                        : Boolean.TRUE.equals(legacyUsed.get(injection.legacy()));
                if (!gate || isDeclared(injection, subCode, skip)) {
                    continue;
                }
                toInject.add(injection.declaration());
            }
        }

        String substituted = SourceLines.join(subRaw, lines.endsWithNewline());
        if (toInject.isEmpty()) {
            return new Result(substituted, diagnostics, 0, 0);
        }
        int insertIndex = GlslTextScan.headerEnd(subRaw);
        if (insertIndex >= subRaw.size()) {
            // 没有代码行（用而未声明必须发生在代码行上，此分支理论不可达）：不注入，交驱动报错（T11）。
            return new Result(substituted, diagnostics, 0, 0);
        }
        String text = SourceLines.of(substituted).insertLines(insertIndex, toInject);
        return new Result(text, diagnostics, insertIndex, toInject.size());
    }

    /** 单行改写结果（raw 与无注释视图同步）。 */
    private record Line(String raw, String code) {}

    /** 区间编辑（在无注释视图上取，raw 同下标套用 —— 等长视图契约）。 */
    private record Edit(int start, int end, String replacement) {}

    /**
     * 改写一行里的旧内建 token（收集区间 → 对 raw / code 各套一次，注释字节原位保留）。
     *
     * <p>声明行保护：表达式型替换遇到该旧名的显式声明行时不动 token（gl_Vertex 声明行改名
     * 成 Position；gl_TextureMatrix 声明行整行保留；其余表达式型替换保留原样）。
     */
    private static Line rewriteLine(String raw, String code, boolean vertexStage,
            String glVertexOperand, Map<String, Boolean> legacyUsed) {
        if (!code.contains("gl_")) {
            return new Line(raw, code);
        }
        List<Edit> edits = new ArrayList<>();
        int pos = 0;
        while (pos < code.length()) {
            int[] span = GlslTextScan.identifierAt(code, pos);
            if (span == null) {
                pos++;
                continue;
            }
            if (!GlslTextScan.atTokenStart(code, span[0])) {
                pos = span[1];
                continue;
            }
            String token = code.substring(span[0], span[1]);
            int end = span[1];
            String replacement;
            if (TEXTURE_MATRIX_LEGACY.equals(token)) {
                if (!Boolean.TRUE.equals(legacyUsed.get(token))
                        || matchesDeclaration(token, code)) {
                    pos = span[1];
                    continue;
                }
                end = subscriptEnd(code, span[1]);
                if (end == span[1]) {
                    // 裸名（无下标）：替换无正确答案，保留原样交驱动显式报错（T11）。
                    pos = span[1];
                    continue;
                }
                replacement = IDENTITY_MATRIX;
            } else {
                Rename rename = RENAME_BY_NAME.get(token);
                if (rename == null || (rename.vertexOnly() && !vertexStage)
                        || !Boolean.TRUE.equals(legacyUsed.get(token))) {
                    pos = span[1];
                    continue;
                }
                if ("gl_Vertex".equals(token)) {
                    // 声明行是名字上下文：只能改名，不能落表达式。
                    replacement = matchesDeclaration(token, code)
                            ? "Position"
                            : glVertexOperand;
                } else {
                    replacement = rename.replacement();
                    if (!isPlainIdentifier(replacement) && matchesDeclaration(token, code)) {
                        pos = span[1];
                        continue;
                    }
                }
            }
            edits.add(new Edit(span[0], end, replacement));
            pos = end;
        }
        if (edits.isEmpty()) {
            return new Line(raw, code);
        }
        return new Line(apply(raw, edits), apply(code, edits));
    }

    /** 把区间编辑套到一份文本上（区间有序不重叠）。 */
    private static String apply(String source, List<Edit> edits) {
        StringBuilder out = new StringBuilder(source.length());
        int cursor = 0;
        for (Edit edit : edits) {
            out.append(source, cursor, edit.start()).append(edit.replacement());
            cursor = edit.end();
        }
        out.append(source, cursor, source.length());
        return out.toString();
    }

    /** token 对应的旧名是否在本行被显式声明（keyword / bare 形态任一）。 */
    private static boolean matchesDeclaration(String token, String code) {
        Pattern[] forms = DECL_FORMS.get(token);
        if (forms == null) {
            return false;
        }
        return forms[0].matcher(code).find() || forms[1].matcher(code).find();
    }

    /** 替换文本是否是纯标识符（是 → 声明名字上下文里也合法，不需要声明行保护）。 */
    private static boolean isPlainIdentifier(String replacement) {
        for (int index = 0; index < replacement.length(); index++) {
            if (!GlslTextScan.isIdentifierPart(replacement.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    /**
     * gl_TextureMatrix 下标区间终点（紧随 token 的平衡 {@code [..]}）；没有下标时返回原位。
     * 在无注释视图上扫描（注释已被抹成空白，不会吞区间）。
     */
    private static int subscriptEnd(String code, int from) {
        int open = GlslTextScan.skipWhitespace(code, from);
        if (open >= code.length() || code.charAt(open) != '[') {
            return from;
        }
        int depth = 0;
        for (int index = open; index < code.length(); index++) {
            char c = code.charAt(index);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return index + 1;
                }
            }
        }
        return from;
    }

    /**
     * gl_Vertex 在**使用处**的替换文本（与 {@link FtransformExpander} 的操作数同口径，
     * 含 gl_Vertex → Position 的合法化改名）。
     *
     * <p>{@code positionDecl = [名字, 类型]}（{@code [0] == null} = 源里没声明任何位置属性）。
     */
    private static String glVertexReplacement(String[] positionDecl) {
        String declaredName = positionDecl[0];
        String declaredType = positionDecl[1];
        if (declaredName == null) {
            return DEFAULT_VERTEX_OPERAND;
        }
        String normalized = "gl_Vertex".equals(declaredName) ? "Position" : declaredName;
        if ("vec4".equals(declaredType)) {
            return normalized;
        }
        return "vec4(" + normalized + ", 1.0)";
    }

    /** 位置属性声明 {@code [名字, 类型]}（attribute / in，Position / vaPosition / gl_Vertex 候选）。 */
    private static String[] declaredPosition(List<String> codeLines, boolean[] skip) {
        String name = null;
        String type = null;
        for (int index = 0; index < codeLines.size(); index++) {
            if (skip[index]) {
                continue;
            }
            GlslDeclaration declaration = GlslDeclaration.parse(codeLines.get(index));
            if (declaration == null || declaration.name == null) {
                continue;
            }
            if (!"attribute".equals(declaration.keyword) && !"in".equals(declaration.keyword)) {
                continue;
            }
            if (!FtransformExpander.POSITION_CANDIDATES.contains(declaration.name)) {
                continue;
            }
            name = declaration.name;
            type = declaration.type;
            break;
        }
        return new String[] {name, type};
    }

    /** 名字作为完整标识符 token 出现在某条非预处理代码行上（注释 / 字符串已在视图中被抹除）。 */
    private static boolean isUsed(String name, List<String> codeLines, boolean[] skip) {
        for (int index = 0; index < codeLines.size(); index++) {
            if (skip[index]) {
                continue;
            }
            String line = codeLines.get(index);
            int at = line.indexOf(name);
            while (at >= 0) {
                if (GlslTextScan.atTokenStart(line, at)
                        && (at + name.length() >= line.length()
                                || !GlslTextScan.isIdentifierPart(line.charAt(at + name.length())))) {
                    return true;
                }
                at = line.indexOf(name, at + 1);
            }
        }
        return false;
    }

    /** 名字已被声明（裸声明 / layout 前缀 / 无实例名块成员 / 逗号多声明任一形态）。 */
    private static boolean isDeclared(Injection injection, List<String> codeLines, boolean[] skip) {
        for (int index = 0; index < codeLines.size(); index++) {
            if (skip[index]) {
                continue;
            }
            String line = codeLines.get(index);
            if (injection.keywordForm().matcher(line).find()
                    || injection.bareForm().matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Rename> index(List<Rename> renames) {
        Map<String, Rename> byName = new HashMap<>();
        for (Rename rename : renames) {
            byName.put(rename.legacy(), rename);
        }
        return Map.copyOf(byName);
    }

    /** 构造注入候选：两种已声明形态的行级正则（在无注释无字符串视图上匹配）。 */
    private static Injection injection(String legacy, String name, String declaration) {
        Pattern[] forms = declarationPatterns(name);
        return new Injection(legacy, name, declaration, forms[0], forms[1]);
    }

    /** 每个旧名的声明形态正则（声明行保护用）。 */
    private static Map<String, Pattern[]> buildDeclForms() {
        Map<String, Pattern[]> forms = new HashMap<>();
        for (Rename rename : RENAMES) {
            forms.put(rename.legacy(), declarationPatterns(rename.legacy()));
        }
        forms.put(TEXTURE_MATRIX_LEGACY, declarationPatterns(TEXTURE_MATRIX_LEGACY));
        return Map.copyOf(forms);
    }

    /** 名字的两种声明形态正则：{@code [0]} 关键字形态、{@code [1]} 裸形态（块成员 / 无关键字顶层）。 */
    private static Pattern[] declarationPatterns(String name) {
        String quoted = Pattern.quote(name);
        // 关键字形态：[layout 前缀可有可无] attribute|varying|uniform|in|out [类型] [逗号前缀名, ...] name[数组] ;|=|,
        Pattern keyword = Pattern.compile(
                "\\b(?:attribute|varying|uniform|in|out)\\s+(?:" + IDENT + "\\s+)*(?:"
                        + IDENT + ARRAY_SUFFIX + "\\s*,\\s*)*" + quoted + ARRAY_SUFFIX + "\\s*[;=,]");
        // 裸形态：行首缩进 + 类型 + [逗号前缀名, ...] name[数组] ;|=|, —— 覆盖无实例名块成员
        //（UniformInjector 收编后的输出）与无关键字顶层声明。
        Pattern bare = Pattern.compile(
                "^\\s*" + IDENT + "\\s+(?:"
                        + IDENT + ARRAY_SUFFIX + "\\s*,\\s*)*" + quoted + ARRAY_SUFFIX + "\\s*[;=,]");
        return new Pattern[] {keyword, bare};
    }
}
