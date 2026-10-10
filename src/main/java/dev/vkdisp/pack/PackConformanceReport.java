package dev.vkdisp.pack;

import dev.vkdisp.glsl.translate.BuiltinsBlockLayout;
import dev.vkdisp.glsl.translate.PostVertexLinker;
import dev.vkdisp.glsl.translate.UniformCatalog;
import dev.vkdisp.pipeline.model.PackSamplerSuperset;
import dev.vkdisp.pipeline.model.PostSamplerSuperset;
import dev.vkdisp.render.AtmosphereBuiltins;
import dev.vkdisp.shadow.LightSpaceList;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 【参考调研】C3 · 包一致性报告（`19` §4.4 / QD-11④）
 * 0. 合规：参考对象 = 本仓库自有实现事实 —— `PackPostChain:223-232` 的「派生 + 与超集对差 + WARN +
 * 排除」那条已经跑通的路，与 `SamplerDimensionPlan` 的声明派生；命名规则出处 = OptiFine/Iris 公开
 * Uniforms 表（`04-SPEC` §3.2）。外部候选（glsl-transformer / glsl-preprocessor，GPL-3.0+例外）
 * 按禁止处理（`07` L12 / X20 / X21）：不读其代码、零并入。本项目 MIT。
 * 1. 职责：包加载期把 `19` §4.1 那张表里的**每一张硬编码名单**与「包实际声明了什么」对差，
 *    产出**逐名报告**（命中 / 包有引擎没有 / 引擎有包没用），并把「换个包就漂移」变成可数的事。
 * 2. 差异点：报告**不改任何行为** —— 它只读各子系统已有的真源（超集表 / 快照 / 链 / 目录 / 常量），
 *    不新增第二份名单（QD-02 / X42 那一族：抄第二份就会开始漂）。
 * 3. 🔴 数值纪律（`19` §5.1）：报告里**没有一个是手抄的数字** —— 每条计数都由当场读到的集合算出来；
 *    表本身是 `private` 的部分（如 `PostVertexLinker` 的 UV 名 / 世界向量黑名单）只报「经哪个公开
 *    判定函数」而不复制内容，宁可少说也不编。
 * 4. 覆盖范围（如实登记）：全屏三步（composite / deferred / final）+ 后处理链的每一级。
 *    **gbuffer 各条程序**的内建/sampler 对差不在这里 —— 那条路已在绑定期逐条点名
 *    （`TerrainPipelineApi` 的 {@code [GAP-027] pack gbuffer uniforms bound} 日志）。
 * 5. 性能：❄️ 冷路径（每次包生成一次）；线性扫表，无缓存。
 */
public final class PackConformanceReport {

    // ---------------------------------------------------------------- 名单标识（矩阵的行名）

    public static final String LIST_FRAGMENT_SAMPLERS = "fragment-sampler";
    public static final String LIST_POST_SAMPLERS = "post-sampler";
    public static final String LIST_LIGHT_DIRECTION = "light-direction";
    public static final String LIST_CASCADE_ORTHO = "cascade-ortho";
    public static final String LIST_BUILTIN_CATALOG = "builtin-uniform-catalog";
    public static final String LIST_DECLARED_UNSUPPLIED = "declared-unsupplied";
    public static final String LIST_ATTRIBUTE_ALIASES = "attribute-alias";
    public static final String LIST_SAMPLER_ROUTING = "sampler-name-routing";
    public static final String LIST_POST_VERTEX_ATTRIBUTES = "post-vertex-attributes";
    public static final String LIST_CHAIN_LIMITS = "chain-limits";

    /** 一条名单里一个名字的判定。 */
    public enum Status {
        /** 引擎名单有、包也用了。 */
        HIT,
        /** 包声明/引用了，但引擎名单里没有 ⇒ 通常意味着绑不上或不供值，**必须点名**。 */
        PACK_ONLY,
        /** 引擎名单里有、这张包没用 ⇒ 无害，但报告要数得出来（换包时这一栏的变化就是漂移）。 */
        ENGINE_ONLY,
        /** 引擎侧的硬编码假设（没有「包侧」可对差，只能登记现值与出处）。 */
        ASSUMPTION
    }

    /** 一个名字。 */
    public record Entry(String name, Status status, String note) {
    }

    /** 一张名单的对差结果。 */
    public record Section(String list, String engineSource, List<Entry> entries, String note) {

        public Section {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }

        public int count(Status status) {
            int n = 0;
            for (Entry entry : entries) {
                if (entry.status() == status) {
                    n++;
                }
            }
            return n;
        }

        /** 矩阵用的一行：{@code list=... engine=N hit=... packOnly=... engineOnly=...}。 */
        public String matrixLine() {
            return "list=" + list + " 引擎侧=" + engineSource
                    + " 条目=" + entries.size()
                    + " 命中=" + count(Status.HIT)
                    + " 包有引擎无=" + count(Status.PACK_ONLY)
                    + " 引擎有包未用=" + count(Status.ENGINE_ONLY)
                    + " 假设项=" + count(Status.ASSUMPTION)
                    + (note == null || note.isBlank() ? "" : " | " + note);
        }
    }

    /** 整份报告。 */
    public record Report(String packName, List<Section> sections) {

        public Report {
            sections = sections == null ? List.of() : List.copyOf(sections);
        }

        public Optional<Section> section(String list) {
            for (Section section : sections) {
                if (section.list().equals(list)) {
                    return Optional.of(section);
                }
            }
            return Optional.empty();
        }

        /** 兼容矩阵文本（`08-TESTING` §10）：每单一行，顺序稳定可比。 */
        public String asMatrix() {
            StringBuilder text = new StringBuilder();
            for (Section section : sections) {
                text.append(section.matrixLine()).append('\n');
            }
            return text.toString();
        }

        /**
         * 需要 WARN 的条目（T11：不许静默）。
         *
         * <p>只对差里的「包有引擎无」开火 —— 那一类才是「换个包就漂移」的实际症状；
         * 「引擎有包未用」是超集的常态（占位仍会绑满），逐条 WARN 会把日志冲垮（h33 那 2702 行的教训）。
         */
        public List<String> warnings() {
            List<String> out = new ArrayList<>();
            for (Section section : sections) {
                List<String> packOnly = new ArrayList<>();
                for (Entry entry : section.entries()) {
                    if (entry.status() == Status.PACK_ONLY) {
                        packOnly.add(entry.name());
                    }
                }
                if (!packOnly.isEmpty()) {
                    out.add("vkdisp: [C3] 一致性报告 pack=" + packName + " 名单=" + section.list()
                            + " 包声明了引擎名单之外的 " + packOnly.size() + " 项: " + packOnly
                            + " —— 逐名的判定理由在本节里（Report.section(...).entries()），"
                            + "WARN 只点名不重复长文本（h33 的日志刷屏同族教训）");
                }
            }
            return List.copyOf(out);
        }
    }

    /**
     * 报告的输入：**只给当场拿得到的东西**（三条全屏步的转译终稿 + 已建好的链 + 包的纹理绑定表）。
     *
     * <p>🔖 为什么由调用方传进来而不是本类去读 {@code VkDispVirtualPack}：生成现场（
     * {@link PackCompositeSource#generate}）是三源与链**同时**在手的唯一位置；绕到静态视图去读会
     * 得到「上一张包的」快照（QD-12 那一族）。
     */
    public record Facts(String packName, String compositeSource, String deferredSource,
            String finalSource, PackPostChain.Chain chain, Map<String, String> textureBindings) {
    }

    private PackConformanceReport() {
    }

    /** 建报告（纯函数：同样的输入 ⇒ 同样的输出，可单测）。 */
    public static Report build(Facts facts) {
        List<String> fullscreenSources = new ArrayList<>();
        fullscreenSources.add(facts.compositeSource());
        fullscreenSources.add(facts.deferredSource());
        fullscreenSources.add(facts.finalSource());
        List<PackPostChain.Pass> passes = facts.chain() == null
                ? List.of() : facts.chain().passes();

        List<Section> sections = new ArrayList<>();
        sections.add(fragmentSamplers());
        sections.add(postSamplers(passes));
        sections.add(lightDirection());
        sections.add(cascadeAndOrtho());
        sections.add(builtinCatalog(fullscreenSources, passes));
        sections.add(declaredUnsupplied(fullscreenSources, passes));
        sections.add(attributeAliases(passes));
        sections.add(samplerRouting());
        sections.add(postVertexAttributes(passes));
        sections.add(chainLimits(passes, facts.chain()));
        return new Report(facts.packName(), List.copyOf(sections));
    }

    // ---------------------------------------------------------------- 各名单

    /** §4.1 第 1 项：全屏步的 sampler 布局超集 vs 包声明。 */
    private static Section fragmentSamplers() {
        PackSamplerSuperset.Snapshot snapshot = PackSamplerSuperset.current();
        List<Entry> entries = new ArrayList<>();
        for (String name : PackSamplerSuperset.NAMES) {
            entries.add(new Entry(name,
                    snapshot.declared(name).isPresent() ? Status.HIT : Status.ENGINE_ONLY,
                    snapshot.declared(name).map(d -> d.declaredType() + " @" + d.origin())
                            .orElse("本包未声明（仍按占位绑满）")));
        }
        for (String name : snapshot.outsideSuperset()) {
            entries.add(new Entry(name, Status.PACK_ONLY,
                    snapshot.declared(name).map(d -> d.declaredType() + " @" + d.origin())
                            .orElse("声明来源未知") + "：启动期定死的绑定组里没有这一条"));
        }
        return new Section(LIST_FRAGMENT_SAMPLERS,
                "PackSamplerSuperset.NAMES（OF 命名规则生成）", List.copyOf(entries),
                snapshot.summary());
    }

    /** §4.1 第 2 项：后处理链的 sampler 超集 vs 链各级声明。 */
    private static Section postSamplers(List<PackPostChain.Pass> passes) {
        Set<String> declared = new LinkedHashSet<>();
        for (PackPostChain.Pass pass : passes) {
            for (String name : pass.samplerNames()) {
                if (!name.equals("InSampler")) {
                    declared.add(name);
                }
            }
        }
        List<Entry> entries = new ArrayList<>();
        for (String name : PostSamplerSuperset.NAMES) {
            entries.add(new Entry(name, declared.contains(name) ? Status.HIT : Status.ENGINE_ONLY,
                    "后处理绑定组条目"));
        }
        for (String name : declared) {
            if (!PostSamplerSuperset.NAMES.contains(name)) {
                entries.add(new Entry(name, Status.PACK_ONLY, "链上该级声明了超集之外的名字"));
            }
        }
        return new Section(LIST_POST_SAMPLERS, "PostSamplerSuperset.NAMES", List.copyOf(entries),
                "链级数=" + passes.size());
    }

    /**
     * §4.1 第 3 项：光行进方向是引擎常量。
     *
     * <p>🔖 不复制数值（`19` §5.1）：现值与出处只以「读那个字段的地方」表述，
     * 免得报告与代码各写一份、日后互相打脸。
     */
    private static Section lightDirection() {
        return new Section(LIST_LIGHT_DIRECTION,
                "bridge/FrameApi 的 LIGHT_DIRECTION + LIGHT_SPACE_SOURCE（private，无公开读路径）",
                List.of(new Entry("light-space-list", Status.ASSUMPTION,
                        "引擎常量、包无法覆盖；光空间列表还带懒缓存 ⇒ 真角度接线属 C2a，"
                                + "包 shadow pass 装配属 C2b/GAP-034")),
                "本阶不对差（没有包侧数据）");
    }

    /** §4.1 第 4 项：单级联 + 固定正交范围（这些常量是 public，直接报现值）。 */
    private static Section cascadeAndOrtho() {
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry("CASCADE_FAR_SPLITS", Status.ASSUMPTION,
                "级联数=" + LightSpaceList.CASCADE_FAR_SPLITS.length
                        + "（OF/Iris 无 loader 级 CSM API ⇒ 多‘级’是包在一张图内自切的惯例）"));
        entries.add(new Entry("ORTHO_HALF_X", Status.ASSUMPTION,
                String.valueOf(LightSpaceList.ORTHO_HALF_X)));
        entries.add(new Entry("ORTHO_HALF_Y", Status.ASSUMPTION,
                String.valueOf(LightSpaceList.ORTHO_HALF_Y)));
        entries.add(new Entry("EYE_DISTANCE", Status.ASSUMPTION,
                String.valueOf(LightSpaceList.EYE_DISTANCE)));
        return new Section(LIST_CASCADE_ORTHO, "shadow/LightSpaceList 的公开常量", entries,
                "包侧可对差的数据（shadowDistance / shadowIntervalSize / sunPathRotation）"
                        + "目前**没有读取路径** ⇒ 属 C2a");
    }

    /** §4.1 第 5 项：23 条内建 uniform 目录 vs 各程序实际收编进 VkDispBuiltins 块的成员。 */
    private static Section builtinCatalog(List<String> fullscreenSources,
            List<PackPostChain.Pass> passes) {
        Set<String> catalog = new LinkedHashSet<>();
        for (var uniform : UniformCatalog.uniforms()) {
            catalog.add(uniform.name());
        }
        Set<String> collected = new LinkedHashSet<>();
        for (String source : fullscreenSources) {
            collected.addAll(memberNames(source));
        }
        for (PackPostChain.Pass pass : passes) {
            collected.addAll(memberNames(pass.renumberedSource()));
        }
        List<Entry> entries = new ArrayList<>();
        for (String name : catalog) {
            entries.add(new Entry(name, collected.contains(name) ? Status.HIT : Status.ENGINE_ONLY,
                    collected.contains(name) ? "包引用 ⇒ 已进块" : "本包未引用"));
        }
        for (String name : collected) {
            if (!catalog.contains(name)) {
                entries.add(new Entry(name, Status.PACK_ONLY,
                        "包引用但目录没有 ⇒ 由包自写 uniform 供值（GAP-021）"));
            }
        }
        return new Section(LIST_BUILTIN_CATALOG, "glsl/translate/UniformCatalog.uniforms()", entries,
                "对差范围=三条全屏步 + 链各级（gbuffer 各条见绑定期日志）");
    }

    /** §4.1 第 6 项：声明但不供值的名字（DH / Voxy / blindFactor 一族）。 */
    private static Section declaredUnsupplied(List<String> fullscreenSources,
            List<PackPostChain.Pass> passes) {
        Set<String> seen = new LinkedHashSet<>();
        for (String source : fullscreenSources) {
            seen.addAll(uniformNames(source, false));
        }
        for (PackPostChain.Pass pass : passes) {
            seen.addAll(uniformNames(pass.renumberedSource(), false));
            seen.addAll(uniformNames(pass.vertexSource(), true));
        }
        for (PackSamplerSuperset.Declared declared : PackSamplerSuperset.current().declared().values()) {
            seen.add(declared.name());
        }
        List<Entry> entries = new ArrayList<>();
        for (String name : AtmosphereBuiltins.declaredUnsupplied()) {
            boolean used = seen.contains(name);
            entries.add(new Entry(name, used ? Status.PACK_ONLY : Status.ENGINE_ONLY,
                    used ? "包引用了它，但我方**声明而不供值** ⇒ 包读到未初始化值（假定 OF/DH/Voxy 命名）"
                            : "本包未引用 ⇒ 无害"));
        }
        return new Section(LIST_DECLARED_UNSUPPLIED,
                "render/AtmosphereBuiltins.declaredUnsupplied()", entries,
                "假定第三方 mod 命名 = 硬编码假设（§4.1 第 6 项）");
    }

    /** §4.1 第 7 项：顶点属性别名表 vs 链各级包写顶点程序用到的属性。 */
    private static Section attributeAliases(List<PackPostChain.Pass> passes) {
        Set<String> used = new LinkedHashSet<>();
        for (PackPostChain.Pass pass : passes) {
            String vertex = pass.vertexSource();
            if (vertex == null || vertex.isBlank()) {
                continue;
            }
            for (var attribute
                    : GlslDeclarationExtractor.extract(vertex, pass.qualifiedName(), true).attributes()) {
                used.add(String.valueOf(attribute));
            }
        }
        List<Entry> entries = new ArrayList<>();
        for (String alias : GlslDeclarationExtractor.attributeAliasNames()) {
            entries.add(new Entry(alias, Status.ENGINE_ONLY, "别名表条目（拼写）"));
        }
        for (String attribute : used) {
            entries.add(new Entry(attribute, Status.HIT, "包写顶点程序实际用到的属性"));
        }
        return new Section(LIST_ATTRIBUTE_ALIASES,
                "pack/GlslDeclarationExtractor 的 ATTRIBUTE_ALIASES（经 attributeAliasNames() 只读）",
                entries, "未命中别名的原始名以 INFO 诊断逐条暴露（本阶不在报告里二次解析文本）");
    }

    /** §4.1 第 8 项：按名字路由视图 —— 报告每个声明名的决策理由，让「按名 vs 按类型」可读出来。 */
    private static Section samplerRouting() {
        List<Entry> entries = new ArrayList<>();
        for (PackSamplerSuperset.Declared declared : PackSamplerSuperset.current().declared().values()) {
            entries.add(new Entry(declared.name(), Status.HIT,
                    declared.declaredType() + " → " + declared.kind() + " | " + declared.reason()));
        }
        return new Section(LIST_SAMPLER_ROUTING,
                "pipeline/model/SamplerDimensionPlan（类型优先；名字规则只当 fallback）", entries,
                "每条的 reason 里写明这次判定来自类型还是名字规则");
    }

    /** §4.1 第 9 项：post 顶点链接的可服务属性 / UV 名 / 世界向量黑名单三张表。 */
    private static Section postVertexAttributes(List<PackPostChain.Pass> passes) {
        List<Entry> entries = new ArrayList<>();
        for (String name : PostVertexLinker.servableAttributes()) {
            entries.add(new Entry(name, Status.ENGINE_ONLY, "可服务属性表条目"));
        }
        for (PackPostChain.Pass pass : passes) {
            String vertex = pass.vertexSource();
            if (vertex == null || vertex.isBlank()) {
                continue;
            }
            for (var attribute
                    : GlslDeclarationExtractor.extract(vertex, pass.qualifiedName(), true).attributes()) {
                entries.add(new Entry(String.valueOf(attribute), Status.HIT,
                        pass.qualifiedName() + " 的顶点输入"));
            }
        }
        return new Section(LIST_POST_VERTEX_ATTRIBUTES,
                "glsl/translate/PostVertexLinker.servableAttributes()（UV 名与世界向量黑名单两张表"
                        + "只有公开判定 isUvName/isWorldVectorName，表体是 private ⇒ 本阶不复制其内容）",
                entries, "降级/合成的逐条结果已由链接器出诊断（degradedAttributes/synthesizedVaryings）");
    }

    /** §4.1 第 10 项：链长与帧宽两个上界 vs 这张包的实际值（「BSL 10 < 16」这类断言要能数出来）。 */
    private static Section chainLimits(List<PackPostChain.Pass> passes, PackPostChain.Chain chain) {
        int maxSlot = chain == null ? -1 : chain.maxSlot();
        List<Entry> entries = new ArrayList<>();
        Status lengthStatus = passes.size() > PackPostChain.MAX_POST_PASSES
                ? Status.PACK_ONLY : Status.HIT;
        entries.add(new Entry("passes", lengthStatus,
                "本包链长=" + passes.size() + "，引擎上界 MAX_POST_PASSES="
                        + PackPostChain.MAX_POST_PASSES));
        Status widthStatus = maxSlot >= PackPostChain.FRAME_WIDTH ? Status.PACK_ONLY : Status.HIT;
        entries.add(new Entry("colorSlots", widthStatus,
                "本包最大 DRAWBUFFERS 槽=" + maxSlot + "，引擎定宽 FRAME_WIDTH="
                        + PackPostChain.FRAME_WIDTH));
        return new Section(LIST_CHAIN_LIMITS,
                "pack/PackPostChain 的 MAX_POST_PASSES / FRAME_WIDTH", entries,
                "超界 = 该级会被丢或写不到槽 ⇒ 必须点名");
    }

    // ---------------------------------------------------------------- 小工具

    private static List<String> memberNames(String source) {
        List<String> names = new ArrayList<>();
        if (source == null || source.isBlank()) {
            return names;
        }
        for (var member : BuiltinsBlockLayout.parse(source).members()) {
            names.add(member.name());
        }
        return names;
    }

    /** 一个程序声明的 uniform 名（顶点阶段 = true 时按顶点程序解析）。 */
    private static List<String> uniformNames(String source, boolean vertexStage) {
        List<String> names = new ArrayList<>();
        if (source == null || source.isBlank()) {
            return names;
        }
        for (var uniform : GlslDeclarationExtractor.extract(source, "(report)", vertexStage).uniforms()) {
            names.add(uniform.name());
        }
        return names;
    }
}
