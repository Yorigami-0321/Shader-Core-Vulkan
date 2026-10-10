package dev.vkdisp.glsl.translate;

import dev.vkdisp.glsl.TranslateDiagnostic;
import dev.vkdisp.pipeline.model.PostPassContract;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【参考调研】GAP-030：把包的 post 顶点程序接进 required 管线的接口对齐层
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 原版引擎 {@code com.mojang.renderpearl.frontend.shaders.PipelineBuilder}
 *    （Mojang EULA 覆盖）—— 本轮**只观察 API 行为事实**并核到行：顶点输入按 {@code VertexFormat} 的
 *    {@code element.name()} 逐名配对（:107），VS 任一 input 查不到对应元素即抛
 *    {@code does not have a matching vertex buffer element}（:139），基类型必须一致（:154），
 *    「shader 分量数 ≤ 格式分量数」（:167）；跨阶段 varying **只按 location 链接、名字不参与**
 *    （:197-256：片元 input 的 location 上没有顶点 output 就抛 :203，同 location 的向量长度 /
 *    基类型 / 插值限定符不一致各自抛 :215/:229/:243）；同名 uniform 块跨阶段只比
 *    {@code resourceType} 与 {@code dimensions}（:286-292），**块内部布局不比对**。
 *    零源码文本搬运（不抄实现，只把「引擎要求什么」写成下面的三条对齐）。
 *    ② 本仓库自有事实：{@link UniformInjector#BLOCK_OPEN}（VkDispBuiltins 单点）、
 *    {@link BuiltinsBlockLayout}（按声明序算 std140 偏移）、{@link LegacyBuiltinInjector}
 *    （旧内建替换 / 注入后的合法属性名）、{@link PostPassContract.FragmentInput}（片元 in 契约）、
 *    {@code ProgramStage#isPostChain()}、{@link IoLocationAdapter}（location 取「最小未占用号」的口径）。
 *    外部候选 = IrisShaders / glsl-transformer（GPL-3.0 + 例外条款）⇒ 18-PARALLEL §4 D 线明示
 *    按禁止处理：不读其代码、零行并入（07-CONSTRAINTS L12 / X20 / X21）；Vitrail（LGPL-3.0）只作为
 *    「别人确实在跑包的 post 顶点程序」这一事实的参照，本轮未读其源码。
 *    许可证：本文件为独立编写的纯 Java 实现，不含任何外部项目代码，也不含 Mojang 着色器文本。
 *    → 能否并入本项目（MIT）：可以 —— 只采纳不受版权保护的行为事实（链接规则）与仓库内事实
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：原版不存在「把包的顶点程序接进 required 管线」这回事（管线注册期就要顶点格式），
 *    所以本类是本项目的**接口对齐层**；对齐目标是引擎上面那三条硬规则，不是某个加载器的实现细节。
 * 2. 备选：① 直接把包的 VSH 塞进槽位 —— 否决：三条规则任一没过，抛的都是<b>整次资源重载失败</b>
 *    而不是「这一级不跑」（h46 已实测过第二条的原文）；② 只把我方适配层的零值换成真值（方案 1）——
 *    否决：它只能覆盖<b>我方认识的名字</b>，而 BSL 的 {@code sunVec} 是用 {@code timeAngle} 现算太阳
 *    轨道的，逐名兜底永远慢包一步（X27：不许拿能力当借口砍包特性）。
 * 3. 我们的差异点：① <b>属性归一</b>：VS 的 {@code in} 必须落在冻结名单 {@link #servableAttributes()}
 *    内且基类型为浮点；名单外 / 整型 ⇒ 声明整行改写为同类型 {@code const} 零值 + ERROR（<b>不进管线</b>，
 *    所以不会砸重载）；② <b>varying 按名字对齐</b>：把 VS 每个 {@code out} 的 location 改成该片元契约里
 *    <b>同名</b> {@code in} 的 location（引擎只看 location ⇒ 不对齐就是静默喂错值），类型也按片元口径改；
 *    包顶点没产出而片元要读的 ⇒ 逐条合成供值（uv 名取注入属性、世界向量走 {@link #worldVectorFormula}
 *    的<b>我方口径</b>公式、其余零值 + ERROR 点名）；没被片元消费的 VS 输出<b>搬到空闲 location</b>
 *    （不删：赋值右侧可能依赖别处算出的中间量）；③ <b>VkDispBuiltins 块统一</b>：顶点的块整体换成
 *    片元的块文本，只有顶点声明的成员<b>追加</b>到块尾并同步进片元源 ⇒ 两阶段成员集与 std140 偏移
 *    逐字节一致；④ 行数只增不减（追加成员 / 合成声明与赋值），诊断行号 = 本级输入行号。
 * 4. 许可证核对结论：本项目 MIT；GPL-3.0+例外参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径（每次资源重载、每槽一次，纯字符串处理）；不缓存、不预优化
 *    （18-PARALLEL §7.7、T14 达标即停）。
 */
/**
 * 把<b>包自己</b>的后处理顶点程序接进后处理链槽位时的接口对齐层（GAP-030）。
 *
 * <p>🔖 <b>为什么必须有它</b>：链槽管线是 {@code required} 管线 —— 链接期任何一条硬约束没过，
 * 代价都是<b>整次资源重载失败</b>。包的 VSH 是作者按 GL 固定功能管线写的，与 Vulkan 管线的接口
 * 契约正好差三件事（属性名、varying location、跨阶段块布局）；本类逐条对齐，对不齐的一律
 * <b>降级 + 点名</b>，绝不把明知会失败的源交给驱动。
 *
 * <p>🔴 <b>本类不验证 GLSL 真能编译</b>：那是驱动的事。调用方拿到 {@link Result} 后必须再过一次
 * 驱动级编译（{@code bridge/ShaderCompileApi}），失败就整槽回落适配层 —— 静态对齐挡掉的是
 * 「接口对不上」那一族，挡不掉「包自己用了未声明的名字」那一族；两族各有自己的判据，不混。
 */
public final class PostVertexLinker {

    /**
     * 冻结的 post 顶点格式元素名（<b>唯一真源</b>：管线侧建 {@code VertexFormat}、执行侧建顶点缓冲、
     * 本类判定属性能否服务，三处都读这一条 —— 各写一遍就会不一致，QD-02 同族）。
     *
     * <p>名单只有两个来源：{@link LegacyBuiltinInjector} 替换 / 注入后的合法名（前 6 条），
     * 以及 OF 生态在顶点程序里直接声明的常见别名（其后）。<b>全部按浮点供值</b>，每个元素 4 个
     * float 分量 ⇒ 包声明 vec2 / vec3 / vec4 都满足「shader 分量 ≤ 格式分量」那条规则。
     *
     * <p>🔴 <b>上限是 16 条，超了直接抛</b>（{@code VertexFormat.Builder.createAttribute}：
     * {@code "Having more than 16 attributes are not supported"}）。这一条砸的是<b>管线注册期</b>
     * = 客户端起不来 ⇒ 名单宁短不长：没列进来的名字由本层降级成常量并打 ERROR（可见的退化），
     * 列多了则是静默逼近驱动上限。地形色 / 图集类属性（{@code mc_Entity}、{@code mc_atlasOrigin}、
     * {@code at_midBlock}、{@code at_velocity}）刻意不收 —— 它们是 gbuffer 侧的 per-vertex 数据，
     * 出现在 post 顶点程序里本来就无意义（守卫见 {@code PostVertexLinkerTest}）。
     */
    private static final List<String> SERVABLE_ATTRIBUTES = List.of(
            "Position", "Color", "UV0", "UV1", "UV2", "Normal",
            "vaPosition", "vaColor", "vaUV0", "vaUV2", "vaNormal", "at_tangent");

    /** 浮点基类型（整型属性与本格式的浮点元素基类型不符 = 引擎硬抛，见类注释 0 ①）。 */
    private static final Set<String> FLOAT_TYPES = Set.of("float", "vec2", "vec3", "vec4");

    /** 屏幕 uv 语义的 varying 名（与 {@link PackPostVertexAdapter} 同一口径）。 */
    private static final Set<String> UV_NAMES = Set.of("texCoord", "vTexCoords", "coord", "vUv");

    /**
     * 世界向量 varying 的<b>我方口径</b>公式（名字 → 表达式）。
     *
     * <p>🔴 这三条是「OF 公开语义 + 本仓库已在供的 builtins」推出来的，<b>不是核实过的 Iris 实现</b>
     * （声明口径的方式与 GAP-028 的 {@code numbering=vkdisp-own-ABI} 同族）：眼空间单位向量经
     * {@code mat3(gbufferModelViewInverse)} 转到世界空间，太阳方向 = 世界化后的 {@code sunPosition}。
     * 只在<b>包的顶点程序没有产出该 varying</b> 时兜底 —— 包自己怎么算就由包算（那才是方案 2 的目的）。
     */
    private static final Map<String, String> WORLD_VECTOR_FORMULAS = Map.of(
            "sunVec", "normalize(mat3(gbufferModelViewInverse) * sunPosition)",
            "upVec", "normalize(mat3(gbufferModelViewInverse) * vec3(0.0, 1.0, 0.0))",
            "eastVec", "normalize(mat3(gbufferModelViewInverse) * vec3(1.0, 0.0, 0.0))");

    /** 零值占位一旦命中这些名字就必须 ERROR（GAP-030 的反复发闸，适配层共用）。 */
    private static final Set<String> WORLD_VECTOR_BLACKLIST = Set.of(
            "sunVec", "upVec", "eastVec", "westVec", "celestialVec", "sunPathVec", "sunPathRotation");

    /**
     * 简单 in/out 声明行的整行形态：{@code [layout(...)][限定符...] in|out <类型> <名>[下标]?;}。
     * 组 1=layout 参数、2=插值限定符串、3=关键字、4=类型、5=名字。
     *
     * <p>整行匹配是<b>刻意的</b>：逗号多声明 / 跨行声明 / 接口块这些形态本层不改写（改了会把源改坏），
     * 保留原样交驱动显式报错，槽位再由调用方的驱动验证回落适配层。
     */
    private static final Pattern IO = Pattern.compile(
            "^\\s*(?:layout\\s*\\(([^)]*)\\)\\s*)?((?:(?:flat|smooth|noperspective|centroid|patch|sample|"
                    + "invariant|precise)\\s+)*)(in|out)\\s+([A-Za-z_]\\w*)\\s+([A-Za-z_]\\w*)\\s*"
                    + "(?:\\[[^\\]]*\\])?\\s*;\\s*$");

    /** {@code layout} 参数里的 {@code location = N}。 */
    private static final Pattern LOCATION_VALUE = Pattern.compile("location\\s*=\\s*(\\d+)");

    /** 一条 in/out 声明（整行必须匹配 {@link #IO}）。 */
    private record Io(String keyword, String type, String name, int location, String interpolation,
            int lineIndex) {}

    /**
     * 对齐结果。
     *
     * @param packVertexSupplied true = 产出了可交给驱动的包顶点源；false = 调用方必须整槽回落适配层
     * @param vertexSource       对齐后的包 VSH（{@code packVertexSupplied=false} 时为 {@code null}）
     * @param fragmentSource     与 VSH 共用同一 VkDispBuiltins 块的片元源（可能因追加成员而变）
     * @param diagnostics        逐条诊断（降级 / 合成 / 缺块 / 不一致都点名，X11）
     * @param degradedAttributes 被改成常量供值的属性名（包想要、我方缓冲给不了）
     * @param synthesizedVaryings 由我方合成的 varying 名（包顶点没产出、片元却要读）
     */
    public record Result(boolean packVertexSupplied, String vertexSource, String fragmentSource,
            List<TranslateDiagnostic> diagnostics, List<String> degradedAttributes,
            List<String> synthesizedVaryings) {

        public Result {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            degradedAttributes = degradedAttributes == null ? List.of() : List.copyOf(degradedAttributes);
            synthesizedVaryings = synthesizedVaryings == null ? List.of() : List.copyOf(synthesizedVaryings);
        }
    }

    private PostVertexLinker() {}

    /** 冻结属性名单（管线 / 顶点缓冲 / 本类共用的只读视图）。 */
    public static List<String> servableAttributes() {
        return SERVABLE_ATTRIBUTES;
    }

    /** 该名字是否屏幕 uv 语义（适配层与本层兜底供值共用）。 */
    public static boolean isUvName(String name) {
        return UV_NAMES.contains(name);
    }

    /** 该名字是否命中世界向量黑名单（适配层据此把零值占位升级成 ERROR）。 */
    public static boolean isWorldVectorName(String name) {
        return WORLD_VECTOR_BLACKLIST.contains(name);
    }

    /** 世界向量的我方口径公式；不认识的名字返回 {@code null}（不猜，X9）。 */
    public static String worldVectorFormula(String name) {
        return WORLD_VECTOR_FORMULAS.get(name);
    }

    /** 按类型的零值表达式；不认识的类型返回 {@code null}（调用方必须显式处置，不许猜）。 */
    public static String zeroValue(String type) {
        return switch (type) {
            case "float" -> "0.0";
            case "vec2" -> "vec2(0.0)";
            case "vec3" -> "vec3(0.0)";
            case "vec4" -> "vec4(0.0)";
            case "ivec2" -> "ivec2(0)";
            case "ivec3" -> "ivec3(0)";
            case "ivec4" -> "ivec4(0)";
            default -> null;
        };
    }

    /**
     * 对齐一个链槽的顶点 / 片元接口。
     *
     * @param programName    程序名（只用于诊断定位）
     * @param vertexSource   包的 VSH <b>转译终稿</b>（已过 D 线：含 VkDispBuiltins 与 layout location）
     * @param fragmentSource 该槽的片元终稿（重编号后）
     * @param inputs         该片元的输入契约（{@link PostPassContract#inputs()}）
     * @return 结果；永不返回 {@code null}
     */
    public static Result link(String programName, String vertexSource, String fragmentSource,
            List<PostPassContract.FragmentInput> inputs) {
        List<TranslateDiagnostic> diagnostics = new ArrayList<>();
        String fragment = fragmentSource == null ? "" : fragmentSource;
        if (vertexSource == null || vertexSource.isBlank()) {
            diagnostics.add(TranslateDiagnostic.info(
                    "vkdisp: [GAP-030] post 程序 '" + programName + "' 没有可用的包顶点终稿 ⇒ 本槽用顶点"
                            + "适配层（不是错误，但包自己写的顶点算法本轮不参与）", programName, 0));
            return new Result(false, null, fragment, diagnostics, List.of(), List.of());
        }
        String[] unified = unifyBuiltinsBlock(programName, splitIoStatements(vertexSource),
                fragment, diagnostics);
        if (unified == null) {
            return new Result(false, null, fragment, diagnostics, List.of(), List.of());
        }
        String vertex = unified[0];
        fragment = unified[1];

        List<String> degraded = new ArrayList<>();
        vertex = normalizeAttributes(programName, vertex, diagnostics, degraded);

        List<String> synthesized = new ArrayList<>();
        vertex = alignVaryings(programName, vertex,
                inputs == null ? List.of() : inputs, diagnostics, synthesized);
        return new Result(true, vertex, fragment, diagnostics, degraded, synthesized);
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // ③ VkDispBuiltins：两阶段共用同一份块文本（引擎不比对块内部布局 ⇒ 必须自己钉）
    // ────────────────────────────────────────────────────────────────────────────────

    /**
     * 用<b>片元</b>的块替换顶点的块，并把<b>只有顶点声明</b>的成员追加到块尾（同步进片元源）。
     *
     * @return {@code [顶点源, 片元源]}；任一侧缺块、或统一后两阶段布局仍不一致 ⇒ {@code null}
     */
    private static String[] unifyBuiltinsBlock(String programName, String vertexSource,
            String fragmentSource, List<TranslateDiagnostic> diagnostics) {
        Block vertexBlock = Block.find(vertexSource);
        Block fragmentBlock = Block.find(fragmentSource);
        if (vertexBlock == null || fragmentBlock == null) {
            diagnostics.add(TranslateDiagnostic.of(TranslateDiagnostic.Severity.ERROR,
                    "vkdisp: [GAP-030] post 程序 '" + programName + "' 缺 " + UniformInjector.BLOCK_OPEN
                            + " 块（顶点有=" + (vertexBlock != null) + " 片元有=" + (fragmentBlock != null)
                            + "）⇒ 跨阶段块布局无法统一，本槽不接包顶点程序。引擎对同名块只比"
                            + " resourceType/dimensions，硬接进去就是<b>静默喂垃圾</b>", programName, 0));
            return null;
        }
        Set<String> fragmentNames = new LinkedHashSet<>(memberNames(fragmentBlock.members()));
        List<String> union = new ArrayList<>(fragmentBlock.members());
        List<String> appended = new ArrayList<>();
        for (String member : vertexBlock.members()) {
            List<String> names = memberNamesOf(member);
            boolean alreadyThere = !names.isEmpty() && fragmentNames.containsAll(names);
            if (alreadyThere) {
                continue;
            }
            union.add(member);
            appended.addAll(names);
        }
        String rebuiltVertex = vertexBlock.replaceWith(vertexSource, union);
        String rebuiltFragment = fragmentBlock.replaceWith(fragmentSource, union);
        // 🔴 复核：统一后两阶段的成员表必须逐条相等（守卫这一轮真的替换成功了）。
        BuiltinsBlockLayout vertexLayout = BuiltinsBlockLayout.parse(rebuiltVertex);
        BuiltinsBlockLayout fragmentLayout = BuiltinsBlockLayout.parse(rebuiltFragment);
        if (vertexLayout.isEmpty() || fragmentLayout.isEmpty()
                || !vertexLayout.members().equals(fragmentLayout.members())) {
            diagnostics.add(TranslateDiagnostic.of(TranslateDiagnostic.Severity.ERROR,
                    "vkdisp: [GAP-030] post 程序 '" + programName + "' 统一后两阶段块成员仍不一致"
                            + "（顶点 " + vertexLayout.byteSize() + "B / 片元 " + fragmentLayout.byteSize()
                            + "B，failure 顶点='" + vertexLayout.failure() + "' 片元='"
                            + fragmentLayout.failure() + "'）⇒ 本槽不接包顶点程序", programName, 0));
            return null;
        }
        if (!appended.isEmpty()) {
            // 追加只发生在块尾：既有成员的偏移一个都不动 ⇒ 片元侧读到的还是它自己那套偏移。
            diagnostics.add(TranslateDiagnostic.info(
                    "vkdisp: [GAP-030] post 程序 '" + programName + "' 的顶点独有 uniform " + appended
                            + " 已<b>追加</b>进共用 VkDispBuiltins 块（取值走包 uniform 求值路径 GAP-021，"
                            + "取不到即未填零并在 [uniforms] 自报里点名）", programName, 0));
        }
        return new String[] {rebuiltVertex, rebuiltFragment};
    }

    /** 一个 VkDispBuiltins 块的行区间与成员原文行。 */
    private record Block(int openIndex, int closeIndex, List<String> members) {

        /** 在源文本里定位块；没有开行或没有闭行返回 {@code null}。 */
        static Block find(String source) {
            List<String> lines = split(source);
            int open = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (trimmed(lines.get(i)).equals(UniformInjector.BLOCK_OPEN)) {
                    open = i;
                    break;
                }
            }
            if (open < 0) {
                return null;
            }
            List<String> members = new ArrayList<>();
            for (int i = open + 1; i < lines.size(); i++) {
                String line = trimmed(lines.get(i));
                if (line.indexOf('}') >= 0) {
                    return new Block(open, i, List.copyOf(members));
                }
                if (!line.isEmpty()) {
                    members.add(lines.get(i));
                }
            }
            return null;
        }

        /** 把块体换成给定成员行（开行与闭行保持原位）。 */
        String replaceWith(String source, List<String> newMembers) {
            List<String> lines = split(source);
            List<String> rebuilt = new ArrayList<>(lines.subList(0, openIndex + 1));
            rebuilt.addAll(newMembers);
            rebuilt.addAll(lines.subList(closeIndex, lines.size()));
            return String.join("\n", rebuilt);
        }
    }

    private static List<String> memberNames(List<String> members) {
        List<String> names = new ArrayList<>();
        for (String member : members) {
            names.addAll(memberNamesOf(member));
        }
        return names;
    }

    /**
     * 一行块成员里的<b>全部</b>成员名。
     *
     * <p>🔴 不能只取一个：OF 的包源码（BSL 实测）写 {@code uniform float timeAngle, timeBrightness;}
     * 这种逗号多声明，而 {@link BuiltinsBlockLayout} 是按<b>每个名字</b>各占一个成员的
     * （{@code consumeMember} 明确支持逗号多名）。只取一个就会把顶点侧的同名成员<b>再追加一遍</b>，
     * 于是块解析成 {@code duplicate member name} —— 本轮第一版就是这么被自己的复核闸门拦下的。
     */
    private static List<String> memberNamesOf(String memberLine) {
        String body = stripTailComment(memberLine).replace("\r", "").strip();
        if (body.endsWith(";")) {
            body = body.substring(0, body.length() - 1).strip();
        }
        List<String> names = new ArrayList<>();
        for (String part : body.split(",")) {
            String last = null;
            Matcher matcher = IDENT_TOKEN.matcher(part);
            while (matcher.find()) {
                last = matcher.group();
            }
            // 形态 = [限定符...] 类型 名字 —— 数组维是数字（不是标识符），所以最后一个标识符就是名字。
            if (last != null) {
                names.add(last);
            }
        }
        return names;
    }

    /** 标识符 token（成员名提取用）。 */
    private static final Pattern IDENT_TOKEN = Pattern.compile("[A-Za-z_]\\w*");

    // ────────────────────────────────────────────────────────────────────────────────
    // ① 属性归一：名单外 / 整型 ⇒ 整行改常量，绝不进管线
    // ────────────────────────────────────────────────────────────────────────────────

    private static String normalizeAttributes(String programName, String vertexSource,
            List<TranslateDiagnostic> diagnostics, List<String> degraded) {
        List<String> lines = split(vertexSource);
        for (Io io : collectIo(vertexSource, "in")) {
            boolean servable = SERVABLE_ATTRIBUTES.contains(io.name()) && FLOAT_TYPES.contains(io.type());
            if (servable) {
                continue;
            }
            String zero = zeroValue(io.type());
            if (zero == null) {
                diagnostics.add(TranslateDiagnostic.of(TranslateDiagnostic.Severity.ERROR,
                        "vkdisp: [GAP-030] post 程序 '" + programName + "' 的顶点属性 '" + io.name()
                                + "' 类型 '" + io.type() + "' 不是本层认识的类型 ⇒ 保留原样交驱动显式报错"
                                + "（不猜字节，X9；本槽随后由驱动验证回落适配层）", programName, io.lineIndex()));
                continue;
            }
            lines.set(io.lineIndex(), "const " + io.type() + " " + io.name() + " = " + zero + ";");
            degraded.add(io.name());
            diagnostics.add(TranslateDiagnostic.of(TranslateDiagnostic.Severity.ERROR,
                    "vkdisp: [GAP-030] post 程序 '" + programName + "' 要顶点属性 '" + io.name() + "'（"
                            + io.type() + "），冻结 post 顶点格式供不了 ⇒ 按常量 " + zero
                            + " 供值，依赖它的效果本轮<b>不成立</b>（名单见 PostVertexLinker#SERVABLE_ATTRIBUTES）",
                    programName, io.lineIndex()));
        }
        return String.join("\n", lines);
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // ② varying：按名字对齐 location，缺的逐条合成，多的搬家
    // ────────────────────────────────────────────────────────────────────────────────

    private static String alignVaryings(String programName, String vertexSource,
            List<PostPassContract.FragmentInput> inputs, List<TranslateDiagnostic> diagnostics,
            List<String> synthesized) {
        Map<String, PostPassContract.FragmentInput> wantByName = new LinkedHashMap<>();
        Set<Integer> claimedOut = new LinkedHashSet<>();
        for (PostPassContract.FragmentInput input : inputs) {
            wantByName.put(input.name(), input);
            claimedOut.add(input.location());
        }
        List<Io> outputs = collectIo(vertexSource, "out");
        Set<String> produced = new LinkedHashSet<>();
        for (Io io : outputs) {
            produced.add(io.name());
        }
        List<String> lines = split(vertexSource);

        // ②-a 包顶点产出的 varying：location（必要时含类型）改成片元契约里同名那一条。
        for (Io io : outputs) {
            PostPassContract.FragmentInput want = wantByName.get(io.name());
            int target = want == null ? nextFree(claimedOut) : want.location();
            claimedOut.add(target);
            String type = io.type();
            if (want != null && !want.type().equals(type)) {
                diagnostics.add(TranslateDiagnostic.of(TranslateDiagnostic.Severity.WARN,
                        "vkdisp: [GAP-030] post 程序 '" + programName + "' 的 varying '" + io.name()
                                + "' 顶点写 " + type + " 而片元读 " + want.type() + " ⇒ 按片元口径改声明"
                                + "（引擎在同一 location 比向量长度，不改就是编译失败）", programName,
                        io.lineIndex()));
                type = want.type();
            }
            lines.set(io.lineIndex(), "layout(location = " + target + ") " + io.interpolation()
                    + io.keyword() + " " + type + " " + io.name() + ";");
            if (want == null) {
                diagnostics.add(TranslateDiagnostic.info(
                        "vkdisp: [GAP-030] post 程序 '" + programName + "' 的 out '" + io.name()
                                + "' 不被该片元消费 ⇒ 搬到空闲 location " + target
                                + "（不删声明：赋值右侧可能依赖别处算出的中间量）", programName,
                        io.lineIndex()));
            }
        }

        // ②-b 片元要、包顶点没产出 ⇒ 逐条合成声明 + 赋值。
        List<String> declarations = new ArrayList<>();
        List<String> assignments = new ArrayList<>();
        Set<Integer> claimedIn = collectLocations(vertexSource, "in");
        for (PostPassContract.FragmentInput input : inputs) {
            if (produced.contains(input.name())) {
                continue;
            }
            int location = nextFree(claimedOut);
            claimedOut.add(location);
            String expression = synthesizedSupply(input.name(), input.type(), lines, claimedIn);
            declarations.add("layout(location = " + location + ") out " + input.type() + " "
                    + input.name() + ";");
            assignments.add("    " + input.name() + " = " + expression + ";");
            synthesized.add(input.name());
            boolean zeroSupplied = expression.equals(zeroValue(input.type()));
            diagnostics.add(TranslateDiagnostic.of(zeroSupplied
                    ? TranslateDiagnostic.Severity.ERROR : TranslateDiagnostic.Severity.WARN,
                    "vkdisp: [GAP-030] post 程序 '" + programName + "' 的片元要 varying '" + input.name()
                            + "'（" + input.type() + "）而包顶点没产出 ⇒ 我方合成供值 " + expression
                            + " @location " + location + (zeroSupplied
                                    ? " —— <b>零值占位，该通道本轮不成立</b>" : ""),
                    programName, 0));
        }
        return splice(lines, declarations, assignments);
    }

    /**
     * 合成 varying 的供值表达式：uv 名 → 注入属性 {@code UV0.xy}（必要时连属性一起补），
     * 世界向量 → 我方口径公式，其余零值（并由调用方打 ERROR）。
     */
    private static String synthesizedSupply(String name, String type, List<String> lines,
            Set<Integer> claimedIn) {
        if (isUvName(name) && type.equals("vec2")) {
            ensureAttribute(lines, "UV0", "vec4", claimedIn);
            return "UV0.xy";
        }
        String formula = worldVectorFormula(name);
        if (formula != null && type.equals("vec3")) {
            return formula;
        }
        String zero = zeroValue(type);
        return zero == null ? "vec4(0.0)" : zero;
    }

    /** 源里没有该属性声明时补一条（location 取 in 侧最小未占用号）。 */
    private static void ensureAttribute(List<String> lines, String name, String type,
            Set<Integer> claimedIn) {
        for (String line : lines) {
            if (line.matches(".*\\bin\\s+\\w+\\s+" + Pattern.quote(name) + "\\s*;.*")) {
                return;
            }
        }
        int index = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("void main")) {
                index = i;
                break;
            }
        }
        int location = nextFree(claimedIn);
        claimedIn.add(location);
        lines.add(index, "layout(location = " + location + ") in " + type + " " + name + ";");
    }

    /** 把合成的声明 / 赋值接进源文本（声明在 {@code void main} 之前，赋值在 main 开头之后）。 */
    private static String splice(List<String> lines, List<String> declarations,
            List<String> assignments) {
        if (declarations.isEmpty()) {
            return String.join("\n", lines);
        }
        int mainIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("void main")) {
                mainIndex = i;
                break;
            }
        }
        if (mainIndex < 0) {
            // 没有 main（异常形态）：只补声明，不猜赋值位置 —— 驱动会显式报错（T11）。
            List<String> out = new ArrayList<>(lines);
            out.addAll(declarations);
            return String.join("\n", out);
        }
        List<String> out = new ArrayList<>(lines.subList(0, mainIndex));
        out.addAll(declarations);
        out.add(lines.get(mainIndex));
        String opening = lines.get(mainIndex);
        if (opening.contains("{")) {
            out.addAll(assignments);
            out.addAll(lines.subList(mainIndex + 1, lines.size()));
        } else {
            // main 的开括号在下一行（跨行写法）：赋值插到那一行之后，避免插进参数表。
            int brace = mainIndex;
            while (brace + 1 < lines.size() && !lines.get(brace).contains("{")) {
                brace++;
            }
            out.addAll(lines.subList(mainIndex + 1, brace + 1));
            out.addAll(assignments);
            out.addAll(lines.subList(brace + 1, lines.size()));
        }
        return String.join("\n", out);
    }

    /**
     * 把「一行里两条 in/out 声明」拆成一行一条。
     *
     * <p>🔴 <b>BSL 实测形态</b>（本轮第一版被这条坑到）：{@link IoLocationAdapter} ③ 的逗号拆语句
     * 是<b>同行</b>拆的（行数不变是它的契约），于是包的 {@code varying vec3 sunVec, upVec;} 到终稿变成
     * {@code layout(location = 1) out vec3 sunVec; layout(location = 2) out vec3 upVec;} —— 一行两条。
     * 本层按行认声明（改了就要落回同一行），一行两条就会<b>两条都看不见</b> ⇒
     * 把包已经产出的 varying 当成「没产出」再合成一遍 = <b>同名 out 重复声明</b>，
     * 驱动直接编译失败。拆分之后「一行一条」是本层的输入前提，由这里保证。
     *
     * <p>区间取自等长无注释视图（同 {@link LegacyBuiltinInjector} 的做法：视图与原文下标一致），
     * 语句之外的原文（行尾注释等）并入<b>最后</b>一条，不丢文本。
     */
    private static String splitIoStatements(String source) {
        List<String> lines = split(source);
        List<String> codeViews = GlslTextScan.codeViews(lines, new ArrayList<>());
        List<String> out = new ArrayList<>(lines.size());
        for (int index = 0; index < lines.size(); index++) {
            String raw = lines.get(index);
            String code = codeViews.get(index);
            List<int[]> spans = statementSpans(code);
            if (spans.size() < 2) {
                out.add(raw);
                continue;
            }
            List<String> statements = new ArrayList<>(spans.size());
            StringBuilder leftover = new StringBuilder();
            int cursor = 0;
            for (int[] span : spans) {
                leftover.append(raw, cursor, span[0]);
                statements.add(raw.substring(span[0], span[1]).strip());
                cursor = span[1];
            }
            leftover.append(raw, cursor, raw.length());
            String tail = leftover.toString().strip();
            if (!tail.isEmpty()) {
                statements.set(statements.size() - 1, statements.get(statements.size() - 1) + " " + tail);
            }
            out.addAll(statements);
        }
        return String.join("\n", out);
    }

    /** 一行里所有<b>完整</b>的 in/out 声明语句区间（{@code [start, end)}）。 */
    private static List<int[]> statementSpans(String code) {
        List<int[]> spans = new ArrayList<>();
        Matcher matcher = IO_STATEMENT.matcher(code);
        while (matcher.find()) {
            spans.add(new int[] {matcher.start(), matcher.end()});
        }
        return spans;
    }

    /** 完整的一条 in/out 声明（不锚定行首尾 —— 就是要能在<b>一行多条</b>里把它们各自抠出来）。 */
    private static final Pattern IO_STATEMENT = Pattern.compile(
            "layout\\s*\\([^)]*\\)\\s*(?:(?:flat|smooth|noperspective|centroid|patch|sample|invariant|precise)"
                    + "\\s+)*(?:in|out)\\s+[A-Za-z_]\\w*\\s+[A-Za-z_]\\w*\\s*(?:\\[[^\\]]*\\])?\\s*;");

    /** 最小未占用号（0 起；与 {@link IoLocationAdapter} 同口径）。 */
    private static int nextFree(Set<Integer> claimed) {
        int candidate = 0;
        while (claimed.contains(candidate)) {
            candidate++;
        }
        return candidate;
    }

    private static Set<Integer> collectLocations(String source, String keyword) {
        Set<Integer> used = new LinkedHashSet<>();
        for (Io io : collectIo(source, keyword)) {
            used.add(io.location());
        }
        return used;
    }

    /** 收集某一方向（in / out）的简单声明行；其它形态（逗号 / 跨行 / 块）本层不认。 */
    private static List<Io> collectIo(String source, String keyword) {
        List<String> lines = split(source);
        List<Io> found = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            Matcher matcher = IO.matcher(codeView(lines.get(index)));
            if (!matcher.matches() || !keyword.equals(matcher.group(3))) {
                continue;
            }
            int location = -1;
            if (matcher.group(1) != null) {
                Matcher value = LOCATION_VALUE.matcher(matcher.group(1));
                if (value.find()) {
                    location = Integer.parseInt(value.group(1));
                }
            }
            if (location < 0) {
                continue;
            }
            found.add(new Io(matcher.group(3), matcher.group(4), matcher.group(5), location,
                    matcher.group(2) == null ? "" : matcher.group(2), index));
        }
        return found;
    }

    /** 按 \n 拆分（与 {@link BuiltinsBlockLayout#parse} 同一口径：{@code split("\n", -1)}）。 */
    private static List<String> split(String source) {
        return new ArrayList<>(List.of(source.split("\n", -1)));
    }

    /** 去掉行尾 \r 与首尾空白（块开闭行的逐字节比对用）。 */
    private static String trimmed(String line) {
        return line.replace("\r", "").strip();
    }

    /** 单行无注释视图（复用 D 线的扫描器，不自己再写一套注释状态机）。 */
    private static String codeView(String line) {
        return GlslTextScan.codeViews(List.of(line), new ArrayList<>()).get(0);
    }

    /** 去掉行尾注释（成员名提取用）。 */
    private static String stripTailComment(String line) {
        int index = line.indexOf("//");
        return index < 0 ? line : line.substring(0, index);
    }
}
