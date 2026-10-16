package dev.vkdisp.glsl.translate;
/**
 * 【自行补充】GAP-007 的替代供值路：包片元里 {@code normal}/{@code tangent}/{@code binormal} 这三条
 * 由<b>屏幕空间导数现算</b>，而不是按常量供，也不是改 chunk 网格的顶点格式
 * 0. 合规核对（第 0 步闸门）：参考对象 = ① 本仓库自有实现 —— 转译段「对终稿文本做定点改写 + 逐条诊断」
 *    这一族形状（{@link SampleFactorProbeAdapter} 的「只动锚定的那几行 + 命中数自报」、
 *    {@link PostVertexLinker} 的「形态不认就不动 + ERROR 点名」）；② 公开的 GLSL/数学事实 ——
 *    {@code dFdx}/{@code dFdy} 在 fragment 阶段对任意表达式合法（GLSL 330 core 内建），
 *    以及「由位置的偏导取面法线、由 uv 的偏导重建切线基」是公开的 derivative mapping 做法。
 *    外部候选（OptiFine 的 vertex format patch / Iris / VulkanMod / Sulkan / Beryl）零接触
 *    （07-CONSTRAINTS L12）：本类<b>不</b>改顶点格式，也不搬任何实现。许可证：独立编写（MIT）。
 * 1. 官方/主实现：OptiFine 系给 chunk 网格加 {@code at_tangent}/{@code mc_Entity}/{@code mc_midBlock}
 *    这几个顶点属性。本前端<b>不走那条</b>，理由是本仓库实测事实（不是偏好）：
 *    ① 26.3 的 {@code DefaultVertexFormat.BLOCK} 只有 Position/Color/UV0/UV2
 *       （{@code DefaultVertexFormat.java:30-35}）；
 *    ② chunk 网格的顶点格式是<b>从管线取的</b> —— {@code ChunkSectionLayer#vertexFormat()}
 *       逐字 {@code pipeline.getVertexFormatBinding(0)}（{@code ChunkSectionLayer.java:55-57}），
 *       而 {@code SectionBufferBuilderPack} 按 layer 各持一块裸缓冲（{@code bufferSize} 是<b>字节</b>上限）。
 *       ⇒ 加一条属性 = 同时改「所有吃这块缓冲的管线声明 + 全部已烘焙 section 的失效重建」，
 *       那是独立的一整格（GAP-007 第二方案），不该混进「水」这一格。
 * 2. 备选（都否决，理由可数）：
 *    ① <b>按常量供</b>（今天的行为：{@code normal=(0,1,0)}、{@code tangent=(0,0,1)}、{@code binormal=(1,0,0)}）
 *       —— 否决：水的波纹法线/菲涅尔/反射全压成「整片水面同一个朝向」，正是 GAP-027 那条
 *       「水进了 gbuffer 但看不出是水」的形状；常量不是供值，是不供。
 *    ② <b>在 main 里声明同名局部变量遮蔽全局 varying</b> —— 否决，且这是本轮<b>真踩到</b>的一课：
 *       BSL 的水不只在 main 里读 {@code normal}，它在<b>文件作用域的辅助函数</b>里读
 *       （{@code world0_gbuffers_water-FRAGMENT.glsl:323 GetWaterNormal(…)} 的第 331 行
 *       {@code dot(normalize(normal), normalize(viewPos))}）⇒ 局部遮蔽管不到那些函数
 *       = 「改了但只改了一半」，画面表现为「一层高光对得上、一层波纹对不上」，比不改更难查。
 *    ③ <b>改名包的三条 in 声明 + 用预处理器把「读」指向我方函数</b> —— ✅ 本类走这条：
 *       声明改名保住 location（与适配层的 out 仍逐位置对得上），读点靠 {@code #define} 全局生效。
 * 3. 我们的差异点：
 *       ① <b>预处理器不认作用域</b> ⇒ 三处必须自己挡：<b>参数表里声明了同名变量</b>的顶层函数
 *          （实测 BSL 有 {@code GetReflectedCameraPos(vec3 worldPos, vec3 normal)} 与
 *          {@code GetLighting(…, vec3 normal, …)}）整段用 {@code #undef}/{@code #define} 包住，
 *          否则 {@code vec3 normal} 这个<b>声明</b>会被换成 {@code vec3 vkdispTbnNormal()} = 语法错；
 *       ② <b>成员访问</b>（{@code data.normal}）与<b>结构体字段 / 别的同名声明</b>同样是预处理器的盲区
 *          ⇒ 见到任一形态就<b>整条不做</b>（fail-closed），退回今天那三条常量；
 *       ③ 面法线 = {@code normalize(cross(dFdx(viewPos), dFdy(viewPos)))} 再翻到<b>朝相机</b>那一侧
 *          —— chunk 几何全是轴对齐平面 ⇒ 这个数就是那一面的真法线，不是近似；
 *       ④ 切线取 uv 偏导，行列式退化（该面 uv 沿一个方向不变）时退到「与法线垂直的任一单位向量」，
 *          并把退化算成一个<b>读数</b>（不自报等于没测）；
 *       ⑤ 一切可退回：闸门 {@code mrt.derivativeTbn} 默认关 ⇒ 片元源文本<b>逐字不变</b>。
 * 4. 许可证核对：本项目 MIT；零第三方代码并入，也不含 Mojang 着色器文本。
 * 5. 性能基线：❄️ 冷路径（生成期一次文本改写）。渲染期 = 每片元两次位置导数 + 两次 uv 导数 + 叉乘，
 *    与包自己在法线贴图路径上做的同量级；不测不下结论（T14 / X55）。
 */
import dev.vkdisp.glsl.TranslateDiagnostic;
import dev.vkdisp.glsl.lexer.GlslTokens;
import dev.vkdisp.pipeline.model.PackTerrainProgram;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 「这条程序的 TBN 能不能由导数现算、怎么算」的判定与片元文本改写（无状态，纯函数）。 */
public final class DerivativeTbn {

    /** 我方合成的两条辅助 varying 名（顶点侧写、片元侧读）。 */
    public static final String VIEW_POSITION = "vkdispAuxViewPos";
    public static final String AUX_UV = "vkdispAuxUv";

    /** 包片元里我方要接管的三条基向量（OF 语义：view 空间的 face normal / tangent / binormal）。 */
    private static final List<String> BASIS = List.of("normal", "tangent", "binormal");

    /** varying location 上限（与 {@code PostVertexLinker} 那条「16 条属性」同口径；越界是注册期硬抛）。 */
    private static final int MAX_VARYING_LOCATIONS = 16;

    /** 一条 {@code layout(location = N) in <类型> <名>;}（一行可能有多条 ⇒ <b>不锚定行首尾</b>）。 */
    private static final Pattern INPUT_DECL = Pattern.compile(
            "layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*in\\s+([A-Za-z_]\\w*)\\s+([A-Za-z_]\\w*)\\s*;");

    /** 顶层函数签名（返回类型 + 名字 + 左括号）。 */
    private static final Pattern FUNCTION_HEAD = Pattern.compile(
            "^[A-Za-z_]\\w*(?:\\s+[A-Za-z_]\\w*)+\\s*\\(");

    /** 长得像函数头但不是函数定义的控制流关键字。 */
    private static final List<String> NOT_A_FUNCTION = List.of(
            "if", "for", "while", "switch", "return", "else", "do");

    /** 成员访问形态（{@code data.normal}）：预处理器会把它一起换掉 ⇒ 见到就整条不做。 */
    private static final Pattern MEMBER_ACCESS = Pattern.compile(
            "\\.\\s*(normal|tangent|binormal)\\b");

    /** 除三条 in 声明之外，别处还有这三条名字的<b>声明</b>形态（参数表除外，参数表按跨度包掉）。 */
    private static final Pattern OTHER_DECLARATOR = Pattern.compile(
            "\\b(?:uniform|in|out)\\s+[A-Za-z_]\\w*\\s+(normal|tangent|binormal)\\b");

    /** 结构体里带这三条字段 ⇒ 字段声明会被宏换掉 ⇒ 整条不做。 */
    private static final Pattern STRUCT_WITH_BASIS = Pattern.compile("\\bstruct\\b");

    /**
     * 判定结果。
     *
     * @param applies        false = 不做导数接管（{@link Plan#refusal()} 给原因）
     * @param substituted    被接管的基向量名（{@code BASIS} 的子集，按声明序）
     * @param viewPositionLocation 合成「视空间位置」的 location
     * @param uvLocation     合成「模型 uv」的 location
     */
    public record Plan(boolean applies, List<String> substituted, int viewPositionLocation,
            int uvLocation, List<Integer> declaredLocations, String refusal) {

        public Plan {
            substituted = substituted == null ? List.of() : List.copyOf(substituted);
            declaredLocations = declaredLocations == null ? List.of() : List.copyOf(declaredLocations);
        }

        /** 关档/不适用时的「原样」判定（调用方据此一行都不改）。 */
        public static Plan off(String reason) {
            return new Plan(false, List.of(), -1, -1, List.of(), reason);
        }
    }

    /** 改写结果。 */
    public record Result(String text, List<TranslateDiagnostic> diagnostics, List<String> substituted,
            int guardedFunctions, boolean applied) {

        public Result {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            substituted = substituted == null ? List.of() : List.copyOf(substituted);
        }
    }

    private DerivativeTbn() {
    }

    /**
     * 由<b>冻结契约</b>判「能不能接管」与两条辅助 varying 该占哪个 location。
     *
     * <p>🔖 location 取「契约已用最大号往后顺延」而不是固定 14/15：BSL 水用满 0..13，
     * 而地形/别的包条数不同 —— 固定号要么撞车（同 location 重复声明 = 编译失败），要么白占。
     */
    public static Plan plan(List<PackTerrainProgram.Input> inputs, boolean enabled) {
        List<Integer> locations = new ArrayList<>();
        List<String> declared = new ArrayList<>();
        int max = -1;
        for (PackTerrainProgram.Input input : inputs == null ? List.<PackTerrainProgram.Input>of() : inputs) {
            locations.add(input.location());
            declared.add(input.name());
            max = Math.max(max, input.location());
        }
        if (!enabled) {
            return Plan.off("mrt.derivativeTbn=false ⇒ 三条基向量仍按常量供（GAP-007 原状）");
        }
        List<String> substituted = BASIS.stream().filter(declared::contains).toList();
        if (substituted.isEmpty()) {
            return Plan.off("该程序的片元没声明 normal/tangent/binormal 中的任何一条 ⇒ 没有可接管的东西");
        }
        if (!substituted.contains("normal")) {
            return Plan.off("导数接管至少要拿到面法线（声明了 " + substituted + " 而没有 normal）⇒ 不做");
        }
        if (max + 2 >= MAX_VARYING_LOCATIONS) {
            return Plan.off("合成两条辅助 varying 会越过 location 上限：" + (max + 1) + "/" + (max + 2)
                    + " ≥ " + MAX_VARYING_LOCATIONS + " ⇒ 不做");
        }
        return new Plan(true, substituted, max + 1, max + 2, locations, null);
    }

    /** 顶点侧（适配层）要补的两条 out 声明；不适用时回空串（<b>不</b>产生空行噪声）。 */
    public static String vertexDeclarations(Plan plan) {
        return plan.applies()
                ? "layout(location = " + plan.viewPositionLocation() + ") out vec3 " + VIEW_POSITION + ";\n"
                        + "layout(location = " + plan.uvLocation() + ") out vec2 " + AUX_UV + ";\n"
                : "";
    }

    /**
     * 顶点侧要补的两条赋值。
     *
     * <p>🔖 两条都取适配层<b>已经算过</b>的量（{@code vkdispAdapterViewPos = ModelViewMat * pos}、
     * {@code UV0} = 原版块模型 uv）⇒ 本接管不给渲染期添任何新计算。
     */
    public static String vertexAssignments(Plan plan) {
        return plan.applies()
                ? "    " + VIEW_POSITION + " = vkdispAdapterViewPos;\n"
                        + "    " + AUX_UV + " = UV0;\n"
                : "";
    }

    /**
     * 片元侧改写。任何一条保守闸门不过 ⇒ <b>返回原文</b> + 点名（绝不半改）。
     *
     * @param programName 程序名（只用于诊断定位）
     * @param fragmentSource 该程序的<b>转译终稿</b>（未改写）
     * @param plan {@link #plan} 的判定
     */
    public static Result apply(String programName, String fragmentSource, Plan plan) {
        if (!plan.applies()) {
            return new Result(fragmentSource, List.of(diagnostic(TranslateDiagnostic.Severity.INFO,
                    programName, "导数接管未适用: " + plan.refusal())), List.of(), 0, false);
        }
        if (fragmentSource == null || fragmentSource.isBlank()) {
            return new Result(fragmentSource, List.of(diagnostic(TranslateDiagnostic.Severity.ERROR,
                    programName, "片元源为空 ⇒ 不做导数接管")), List.of(), 0, false);
        }
        List<String> lines = new ArrayList<>(List.of(fragmentSource.split("\n", -1)));
        List<String> code = GlslTokens.codeViews(lines, new ArrayList<>());

        List<String> refusals = riskyShapes(code, plan.substituted());
        if (!refusals.isEmpty()) {
            return new Result(fragmentSource,
                    refusals.stream().map(reason -> diagnostic(TranslateDiagnostic.Severity.ERROR, programName,
                            "源里有预处理器罩不住的形态 ⇒ <b>整条不做</b>，本条程序退回常量供值：" + reason))
                    .toList(), List.of(), 0, false);
        }

        // ① 包的三条 in 声明改名（保住 location 与类型 ⇒ 与适配层 out 的连接关系逐字不变）。
        int renamed = renameInputDeclarations(lines, code, plan.substituted());
        if (renamed != plan.substituted().size()) {
            return new Result(fragmentSource, List.of(diagnostic(TranslateDiagnostic.Severity.ERROR, programName,
                    "契约声明了 " + plan.substituted() + " 但源里只认出 " + renamed + " 条 in 声明"
                            + "（跨行/逗号多声明形态本层不认）⇒ <b>整条不做</b>，退回常量供值")),
                    List.of(), 0, false);
        }

        // ② 参数表里声明了同名变量的顶层函数：整段 [开始行, 闭括号行] 包 #undef/#define。
        List<int[]> guarded = shadowingTopLevelFunctions(code, plan.substituted());

        // ③ 全局块（辅助 in + 三个算基向量的函数 + #define）必须放在<b>第一个读点之前</b>，
        //    否则预处理器是顺序的 ⇒ 上面的读点不会被换（BSL 的 GetWaterNormal 在 323 行，早于 main）。
        int blockAt = insertionPoint(lines, code);

        // 从<b>下往上</b>插，前面的插入才不会把后面的行号挪掉。
        List<Object[]> edits = new ArrayList<>();
        for (int[] span : guarded) {
            edits.add(new Object[] {span[1] + 1, redefineLines(plan.substituted())});
            edits.add(new Object[] {span[0], undefLines(plan.substituted())});
        }
        edits.add(new Object[] {blockAt, globalBlock(plan)});
        edits.sort((a, b) -> Integer.compare((int) b[0], (int) a[0]));
        for (Object[] edit : edits) {
            @SuppressWarnings("unchecked")
            List<String> payload = (List<String>) edit[1];
            lines.addAll((int) edit[0], payload);
        }

        return new Result(String.join("\n", lines),
                List.of(diagnostic(TranslateDiagnostic.Severity.INFO, programName,
                        "导数接管已落地: substituted=" + plan.substituted()
                                + " auxLocations=" + plan.viewPositionLocation() + "/" + plan.uvLocation()
                                + " 屏蔽函数段=" + guarded.size()
                                + "（那些函数自己声明了同名参数 ⇒ 包里怎么算就由包算）"
                                + " 全局块插在源文本第 " + blockAt + " 行（必须在第一个读点之前）")),
                plan.substituted(), guarded.size(), true);
    }

    /** 改名后的内部名（location 不变，只是包读不到了；包的读点全部经 {@code #define} 走到我方函数）。 */
    public static String packLocal(String basisName) {
        return "vkdispInterp" + Character.toUpperCase(basisName.charAt(0)) + basisName.substring(1);
    }

    private static String computerName(String basisName) {
        return switch (basisName) {
            case "tangent" -> "vkdispTbnTangent";
            case "binormal" -> "vkdispTbnBinormal";
            default -> "vkdispTbnNormal";
        };
    }

    /** 预处理器罩不住的形态清单（空 = 可以放心改写）。 */
    private static List<String> riskyShapes(List<String> code, List<String> substituted) {
        List<String> reasons = new ArrayList<>();
        boolean memberAccess = false;
        boolean otherDeclarator = false;
        boolean structField = false;
        int structDepth = -1;
        int depth = 0;
        for (String line : code) {
            if (MEMBER_ACCESS.matcher(line).find()) {
                memberAccess = true;
            }
            if (OTHER_DECLARATOR.matcher(line).find()
                    && !INPUT_DECL.matcher(line).find()) {
                otherDeclarator = true;
            }
            if (structDepth < 0 && STRUCT_WITH_BASIS.matcher(line).find()) {
                structDepth = depth;
            }
            depth += balance(line);
            if (structDepth >= 0 && depth <= structDepth) {
                // struct 块收口后继续扫（一个文件可能有多个 struct）。
                structDepth = -1;
                if (containsAnyBasisDeclarator(line, substituted)) {
                    structField = true;
                }
            }
        }
        if (memberAccess) {
            reasons.add("成员访问（{@code data.normal} 这类）会被 #define 一起换掉");
        }
        if (otherDeclarator) {
            reasons.add("三条名字还出现在别的声明里（uniform/in/out），本层只认那三条 in 声明");
        }
        if (structField) {
            reasons.add("struct 字段里有这三条名字（字段声明会被换掉）");
        }
        return reasons;
    }

    private static boolean containsAnyBasisDeclarator(String line, List<String> substituted) {
        for (String name : substituted) {
            if (Pattern.compile("\\b[A-Za-z_]\\w*\\s+" + Pattern.quote(name) + "\\s*;").matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    /** 返回改掉的<b>条数</b>（不是行数 —— 一行可能有多条声明）。 */
    private static int renameInputDeclarations(List<String> lines, List<String> code, List<String> substituted) {
        int count = 0;
        for (int index = 0; index < lines.size(); index++) {
            String raw = lines.get(index);
            Matcher matcher = INPUT_DECL.matcher(code.get(index));
            StringBuilder rebuilt = new StringBuilder();
            int cursor = 0;
            boolean changed = false;
            while (matcher.find()) {
                String name = matcher.group(3);
                rebuilt.append(raw, cursor, matcher.end());
                cursor = matcher.end();
                if (!substituted.contains(name)) {
                    continue;
                }
                // 把刚附上的整条声明里那个名字换掉（末尾的分号保住）。
                String statement = rebuilt.substring(matcher.start());
                rebuilt.setLength(rebuilt.length() - statement.length());
                rebuilt.append(statement, 0, statement.length() - name.length() - 1)
                        .append(packLocal(name)).append(';');
                changed = true;
                count++;
            }
            if (cursor < raw.length()) {
                rebuilt.append(raw.substring(cursor));
            }
            if (changed) {
                lines.set(index, rebuilt.toString());
            }
        }
        return count;
    }

    /**
     * 参数表里声明了这三条名字之一的<b>顶层函数</b>跨度 {@code [开始行, 闭括号行]}。
     *
     * <p>🔖 GLSL 没有嵌套函数 ⇒ 「深度从 0 进、回 0 出」就是一个完整函数体，正好是屏蔽单位。
     */
    private static List<int[]> shadowingTopLevelFunctions(List<String> code, List<String> substituted) {
        List<int[]> spans = new ArrayList<>();
        int depth = 0;
        for (int index = 0; index < code.size(); index++) {
            if (depth != 0) {
                depth += balance(code.get(index));
                continue;
            }
            String stripped = code.get(index).strip();
            if (stripped.isEmpty() || stripped.startsWith("#") || !FUNCTION_HEAD.matcher(stripped).find()
                    || isControlFlow(stripped)) {
                continue;
            }
            int start = index;
            StringBuilder signature = new StringBuilder();
            int body = -1;
            for (int scan = start; scan < code.size(); scan++) {
                signature.append(code.get(scan).strip()).append(' ');
                int delta = balance(code.get(scan));
                if (delta > 0) {
                    body = scan;
                    break;
                }
                if (delta < 0 || (delta == 0 && code.get(scan).contains(";"))) {
                    // 前向声明（没有函数体）⇒ 不是屏蔽单位。
                    break;
                }
            }
            if (body < 0) {
                continue;
            }
            int end = body;
            int walk = 0;
            for (int scan = body; scan < code.size(); scan++) {
                walk += balance(code.get(scan));
                end = scan;
                if (walk == 0) {
                    break;
                }
            }
            if (declaresAny(signature.toString(), substituted)) {
                spans.add(new int[] {start, end});
            }
            depth = 0;
            index = end;
        }
        return spans;
    }

    private static boolean isControlFlow(String stripped) {
        for (String keyword : NOT_A_FUNCTION) {
            if (stripped.startsWith(keyword) && (stripped.length() == keyword.length()
                    || !Character.isLetterOrDigit(stripped.charAt(keyword.length())))) {
                return true;
            }
        }
        return false;
    }

    private static int balance(String line) {
        int delta = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '{') {
                delta++;
            } else if (c == '}') {
                delta--;
            }
        }
        return delta;
    }

    /** 参数表（第一个左括号之内）里是否声明了这三条之一的名字。 */
    private static boolean declaresAny(String signature, List<String> substituted) {
        int open = signature.indexOf('(');
        if (open < 0) {
            return false;
        }
        int close = signature.indexOf(')', open);
        String params = close < 0 ? signature.substring(open) : signature.substring(open, close);
        for (String name : substituted) {
            if (Pattern.compile("\\b[A-Za-z_]\\w*\\s+" + Pattern.quote(name) + "\\b").matcher(params).find()) {
                return true;
            }
        }
        return false;
    }

    /** 全局块的位置：跳过开头的 {@code #version}/{@code #extension}/{@code #include}/{@code #define}/空行。 */
    private static int insertionPoint(List<String> lines, List<String> code) {
        int index = 0;
        while (index < lines.size()) {
            String stripped = code.get(index).strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                index++;
                continue;
            }
            break;
        }
        return index;
    }

    private static List<String> undefLines(List<String> substituted) {
        List<String> out = new ArrayList<>();
        for (String name : substituted) {
            out.add("#undef " + name);
        }
        return out;
    }

    private static List<String> redefineLines(List<String> substituted) {
        List<String> out = new ArrayList<>();
        for (String name : substituted) {
            out.add("#define " + name + " " + computerName(name) + "()");
        }
        return out;
    }

    /**
     * 全局块：两条辅助 in + 算基向量的几个函数 + 三条 {@code #define}。
     *
     * <p>🔖 为什么是<b>函数</b>而不是「main 里算一次存起来」：包的读点散落在多个函数里，而导数是
     * 每片元的量、在任何从 fragment 调用的函数里都合法 ⇒ 函数形态是唯一能全局生效、
     * 又不必逐个重写读点表达式的形状。GLSL 全局变量不许用非常量初始化，
     * 而「谁先算」的顺序依赖是本仓反复付学费的一类，不值得为省两次叉乘引入。
     */
    private static List<String> globalBlock(Plan plan) {
        List<String> block = new ArrayList<>();
        block.add("// vkdisp GAP-007：normal/tangent/binormal 由屏幕空间导数现算"
                + "（chunk 面是轴对齐平面 ⇒ 面法线是精确值，不是近似）");
        block.add("layout(location = " + plan.viewPositionLocation() + ") in vec3 " + VIEW_POSITION + ";");
        block.add("layout(location = " + plan.uvLocation() + ") in vec2 " + AUX_UV + ";");
        block.add("vec3 vkdispTbnAnyPerp(vec3 n) {");
        block.add("    return abs(n.x) > abs(n.z) ? normalize(vec3(n.y, -n.x, 0.0)) : normalize(vec3(0.0, n.z, -n.y));");
        block.add("}");
        block.add("vec3 vkdispTbnFaceNormal() {");
        block.add("    vec3 n = cross(dFdx(" + VIEW_POSITION + "), dFdy(" + VIEW_POSITION + "));");
        block.add("    if (dot(n, n) <= 0.0) {");
        block.add("        return normalize(-" + VIEW_POSITION + ");");
        block.add("    }");
        block.add("    n = normalize(n);");
        block.add("    if (dot(n, " + VIEW_POSITION + ") > 0.0) {");
        block.add("        n = -n;");
        block.add("    }");
        block.add("    return n;");
        block.add("}");
        block.add("vec3 vkdispTbnNormal() {");
        block.add("    return vkdispTbnFaceNormal();");
        block.add("}");
        block.add("vec3 vkdispTbnTangentRaw() {");
        block.add("    vec2 du = dFdx(" + AUX_UV + ");");
        block.add("    vec2 dv = dFdy(" + AUX_UV + ");");
        block.add("    float det = du.x * dv.y - dv.x * du.y;");
        block.add("    if (det == 0.0) {");
        block.add("        return vkdispTbnAnyPerp(vkdispTbnFaceNormal());");
        block.add("    }");
        block.add("    return (dFdx(" + VIEW_POSITION + ") * dv.y - dFdy(" + VIEW_POSITION + ") * du.y) / det;");
        block.add("}");
        block.add("vec3 vkdispTbnTangent() {");
        block.add("    vec3 n = vkdispTbnFaceNormal();");
        block.add("    vec3 t = vkdispTbnTangentRaw();");
        block.add("    t = normalize(t - n * dot(t, n));");
        block.add("    return dot(t, t) > 0.0 ? t : vkdispTbnAnyPerp(n);");
        block.add("}");
        block.add("vec3 vkdispTbnBinormal() {");
        block.add("    return cross(vkdispTbnTangent(), vkdispTbnFaceNormal());");
        block.add("}");
        for (String name : plan.substituted()) {
            block.add("#define " + name + " " + computerName(name) + "()");
        }
        block.add("");
        return block;
    }

    private static TranslateDiagnostic diagnostic(TranslateDiagnostic.Severity severity,
            String program, String message) {
        return TranslateDiagnostic.of(severity, "vkdisp: [GAP-007/derivativeTbn] " + program + ": "
                + message, program, TranslateDiagnostic.UNKNOWN_LINE);
    }
}
