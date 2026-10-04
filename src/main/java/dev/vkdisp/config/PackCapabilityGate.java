package dev.vkdisp.config;
/**
 * 【参考调研】GAP-009 方案 A：**按能力门控**依赖缺失素材的包特性（用户 2026-10-04 已裁决）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/13-GAP-REGISTRY.md GAP-009 条目（裁决落地口径的**权威版本**）、
 *    ② docs/07-CONSTRAINTS.md T11（降级必须显式 WARN）+ X9（无实测不预修、不猜）
 *    + X27（不许为性能砍包特性）、③ docs/08-TESTING.md §10（pack × program 兼容矩阵 = 支柱①唯一度量）。
 *    全部为仓库内自有文档事实，不受版权保护。
 *    外部候选 Iris（LGPL-3.0）—— 本文件**不读其代码**。裁决里提到的
 *    {@code program.<name> = <表达式>} 只是「Iris 从不改写用户配置」这一**行为事实**，
 *    由本文件按本项目契约独立实现，零代码行并入。
 *    → 能否并入本项目（MIT）：可以（本文件为独立编写的纯 Java 逻辑类）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：无（原版没有「按能力门控包特性」这个概念 —— 它没有包加载器）。
 * 2. 备选：
 *    <ul>
 *      <li>① 硬编码 {@code PARALLAX} 一个名字 —— **否决**：裁决明确要求「不必硬编码一个名字」，
 *          且实测 Complementary 同样有视差却<b>零外部依赖</b>（复用原版 terrain atlas 的亮度/高度）
 *          ⇒ 写成「视差一律关闭」会砍掉一个完全可用的包特性，直接违反 X27。</li>
 *      <li>② 改写用户的 {@code optionsv2.txt} —— <b>裁决否决</b>：Iris 自己从不因能力缺失改写用户配置
 *          （只写用户改过的值，且等于默认值的项被 {@code remove()} 掉）⇒ 无先例，
 *          属本项目自担信任成本的设计。</li>
 *      <li>③ 打包一套 LabPBR 材质资源当缺省 —— <b>裁决否决</b>：{@code _n}/{@code _s} 按 LabPBR 规范
 *          由<b>资源包</b>提供（Iris 文档 {@code pbr_standards}），本引擎内部永远补不出来；
 *          且规范页无 license 声明，打包它有许可风险。</li>
 *    </ul>
 * 3. 我们的差异点（本类的全部设计，逐条都有理由）：
 *    <ul>
 *      <li>🔖 <b>门控判据 = 「包自己声明的依赖」+「我们确实缺这个能力」，两者同时成立</b>。
 *          我们<b>不猜</b>哪个选项有问题（X9）—— 只有包自己知道。
 *          BSL 的声明机制实测为：{@code shaders/lang/en_US.lang} 里
 *          {@code option.PARALLAX=Parallax Occlusion Mapping*}（显示名末尾 {@code *}），
 *          同文件 {@code option.ADVANCED_MATERIALS.comment} 明写
 *          「requires a resource pack which contains specular and/or normal maps」。</li>
 *      <li>🔖 <b>星号 → 能力 的映射只在「缺失能力恰好一条」时才是确定的</b>。
 *          这不是猜：候选集唯一时映射唯一。{@code MISSING_CAPABILITIES} 一旦 ≥2 条，
 *          星号路径必须拒绝映射并 WARN（{@link #AMBIGUOUS_CAPABILITY_FOR_STAR}）——
 *          宁可「门控空转 + 可见告警」，也不按猜测关用户的特性。</li>
 *      <li>🔖 <b>门控有作用域</b>（{@link GateRequest#applicable}）：GAP-009 的实测只覆盖
 *          <b>包地形片元</b>这条路径（{@code evidence/h29}/{@code h31}）。包地形片元没接线时门控
 *          就是<b>白白砍特性</b>，与 X27 相悖 ⇒ 不适用时明确 INFO「不干预」。</li>
 *      <li>🔖 <b>只关布尔、且只关当前为真的</b>。非布尔（INTEGER/FLOAT/STRING）选项在缺能力时
 *          <b>值本身无害</b>（少一层材质覆盖 ≠ 画面全黑），关它只会白白砍掉用户调过的数值。
 *          本来就是关的不记进 {@code gated} —— 否则日志会说「我们关了它」而真相是「它本来就关着」，
 *          下次取证会误判归因。</li>
 *      <li>🔖 <b>不写任何用户文件</b>：只改内存里的 {@link PackOptions} 值（走它既有的
 *          {@code set} 通道以产生正规诊断），{@link PackOptionStore} 一个字节都不碰。</li>
 *      <li>🔖 <b>可关闭 + 有诊断</b>：总开关 {@code pack.capabilityGate}；
 *          关掉时产生 INFO 说明「已关闭 ⇒ 保持包内原值，画面可能不正确」，
 *          而不是无声无息（无声无息会让「画面又黑了」查不到原因）。</li>
 *    </ul>
 * 4. 许可证核对：本项目 MIT；零第三方代码复制，只消费本项目自有的 {@link PackOptions} / {@link Option} 契约。
 * 5. 性能基线：❄️ 冷路径（切包 / 改选项时各跑一次；百项级内存运算 + 一次集合闭包），
 *    不做任何性能优化（07-CONSTRAINTS T14 / 18-PARALLEL §7.7）。
 */
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import dev.vkdisp.pack.Option;
import dev.vkdisp.pack.OptionType;

/**
 * GAP-009 方案 A：按能力门控掉「依赖本引擎没有的素材 / 能力」的包特性。
 *
 * <p><b>它解决什么</b>（实测口径见 {@code docs/13-GAP-REGISTRY.md} GAP-009）：
 * 某些包特性依赖<b>本引擎不具备</b>的能力（LabPBR 逐方块材质贴图集 + 材质 UV 空间），
 * 强行启用会让画面坏掉 —— BSL 上实测是地形 albedo 被压成<b>恰好 0</b>（纯黑剪影，
 * 主目标地形区 luma {@code 0.0000}）。与其让用户看到黑地形，
 * 不如<b>在内存里</b>把那些特性关掉并显式告知。
 *
 * <p><b>为什么不硬编码选项名</b>：Complementary 同样有视差，但实测它复用原版 terrain atlas、
 * <b>零外部依赖</b>（其 {@code parallax.glsl} 里 {@code texture()}/{@code sampler} 引用为 0 处）
 * ⇒ 「视差一律关闭」会砍掉一个完全可用的包特性（违反 X27）。
 * ⇒ 判据必须是「<b>包自己声明</b>的依赖」+「<b>我们确实缺</b>这个能力」两者同时成立。
 *
 * <p><b>调用位置</b>：{@code PackCompositeSource.generate} 里
 * {@code PackOptionsSession.create} 之后、{@code diffAgainstDefaults} 之前 ——
 * 即生效链的<b>最后一道</b>，保证门控结果一定进覆盖表
 * （否则「关掉了但没生效」是最坏的失败形态：日志说关了、画面仍黑）。
 *
 * <p><b>线程模型</b>：非线程安全（与 {@link PackOptions} 同约定，按客户端单线程使用）。
 */
public final class PackCapabilityGate {

    /**
     * 本引擎<b>确实缺失</b>的能力。
     *
     * <p>🔖 <b>每条都必须带实测依据</b>：「缺一个能力」是个会改变用户画面的断言，
     * 没有依据就不能登记（X9）。当前只有一条，因为只有一条被实测过。
     *
     * <p>🔖 <b>为什么这里只有一条、而不是「BSL 那 18 项」</b>：
     * 那 18 项是 <b>BSL 这一个包</b>的依赖闭包，不是「缺哪个能力」的通用清单。
     * 本类只列<b>能力</b>；具体关哪些选项由包自己声明的依赖图决定。
     * 这个区分就是本类不硬编码选项名的<b>全部理由</b>。
     */
    public static final Capability MISSING_PER_BLOCK_MATERIAL_MAPS = new Capability(
            "per-block-material-maps",
            "逐方块材质贴图集 + 材质 UV 空间（LabPBR 规范的 _n / _s 两张额外 atlas）",
            "evidence/h10（两个采样器确实被读到：48.41% 像素变化）"
                    + " + evidence/h29（压零项定位在视差分支）"
                    + " + evidence/h31（关掉后主目标地形区 luma 0.0000 → 96.1485）");

    /** 本引擎缺失的能力（不可变；顺序即判定顺序）。 */
    public static final List<Capability> MISSING_CAPABILITIES =
            List.of(MISSING_PER_BLOCK_MATERIAL_MAPS);

    /**
     * 「材质贴图集」能力在<b>包作者会写出来的文本</b>里的别名（小写匹配）。
     *
     * <p>🔖 <b>这些是包侧的稳定英文标识，不是我们的中文翻译</b> ——
     * BSL 的 {@code ADVANCED_MATERIALS.comment} 原文写的是
     * 「requires a resource pack which contains <b>specular and/or normal maps</b>」，
     * Iris 的文档用 {@code specular} / {@code normals} 这两个 sampler 名字。
     * ⇒ 用包自己的词去匹配，才不会在换包 / 换语言时失效。
     */
    private static final List<String> MATERIAL_MAPS_ALIASES = List.of(
            "specular", "normal map", "normals map", "labpbr", "lab_pbr", "pbr");

    /** 星号标记在「缺失能力 ≥2 条」时无法确定归属的诊断码。 */
    public static final String AMBIGUOUS_CAPABILITY_FOR_STAR = "CAPABILITY_GATE_AMBIGUOUS_STAR";

    /** 一个「本引擎不具备的能力」（附实测依据，避免无据断言）。 */
    public record Capability(String id, String description, String evidence) {
        public Capability {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    /** 一次门控的输入（纯数据，便于单测逐项构造）。 */
    public record GateRequest(
            PackOptions options,
            Map<String, String> optionLabels,
            Set<String> starMarkedOptions,
            Map<String, String> optionHints,
            boolean enabled,
            boolean applicable) {

        public GateRequest {
            Objects.requireNonNull(options, "options");
            optionLabels = optionLabels == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(optionLabels));
            starMarkedOptions = starMarkedOptions == null ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(starMarkedOptions));
            optionHints = optionHints == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(optionHints));
        }

        /** 便捷构造（判据皆空 —— 单测只关心门控行为本身时用）。 */
        public static GateRequest of(PackOptions options, boolean enabled, boolean applicable) {
            return new GateRequest(options, Map.of(), Set.of(), Map.of(), enabled, applicable);
        }
    }

    /** 门控结果：真正被覆盖的选项（选项名 → 被门控成的值）+ 全部诊断。 */
    public record GateResult(Map<String, String> gated, List<OptionDiagnostic> diagnostics) {

        /** 归一构造：映射与列表皆冻结为不可变。 */
        public GateResult {
            gated = gated == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(gated));
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }

        /** 什么都没门控（= 无缺失能力命中 / 门控关闭 / 不适用 / 无依赖声明）。 */
        public boolean isEmpty() {
            return this.gated.isEmpty();
        }

        /** 是否出现过指定诊断码（单测断言用）。 */
        public boolean hasDiagnostic(String code) {
            return this.diagnostics.stream().anyMatch(d -> d.code().equals(code));
        }

        /** 取第一条指定诊断码的诊断（单测逐条比对文案用；无则 null）。 */
        public OptionDiagnostic firstDiagnostic(String code) {
            for (OptionDiagnostic diagnostic : this.diagnostics) {
                if (diagnostic.code().equals(code)) {
                    return diagnostic;
                }
            }
            return null;
        }
    }

    private PackCapabilityGate() {
    }

    // ---------------------------------------------------------------- 依赖声明的读取

    /**
     * 读取包<b>自己声明</b>的选项依赖：选项名 → 它依赖的<b>能力 id 集合</b>。
     *
     * <p>🔖 <b>星号从哪来</b>：{@code dev.vkdisp.pack.properties.PackLangFile#parse}
     * 已把显示名末尾的 {@code *} <b>剥掉</b>（星号是依赖标记，不是文案），
     * 并单独收进 {@code Result#starMarkedOptions()}。⇒ 本方法的星号判据是<b>那一份集合</b>，
     * 标签表里<b>不再</b>带星号。
     * 🔶 这条契约写成这样是刻意的：让「星号」在整条链上只有<b>一个</b>表示，
     * 而不是「标签里带星号」这种需要两处同步的约定 ——
     * 两处同步过一次就一定会漂移（本类首版就是这么错的：门控按「标签带星号」判定，
     * 而解析器已把星号剥了 ⇒ 门控恒空转，且<b>看起来完全正常</b>，正是最该消灭的静默失效）。
     *
     * <p>🔖 <b>两种声明机制，缺一不可</b>：
     * <ul>
     *   <li>① <b>显示名末尾 {@code *}</b>（{@code starMarked}）—— BSL v10.1.8 实测的唯一机制，
     *       {@code en_US.lang} 共 19 条以 {@code *} 结尾（18 个 {@code option.*}
     *       + 1 个 {@code value.*}；后者被解析器<b>刻意不收</b>，见其类注释）。</li>
     *   <li>② <b>选项说明文本里的显式引用</b>（{@code optionHints}）—— 后备通道。
     *       ⚠️ 星号一旦被本地化显示名承载就<b>不可靠</b>（非 ASCII 资源包、改过 lang 的包读不出来）
     *       ⇒ 必须有第二条路，否则「读不到星号」会静默退化成「不门控」= 黑地形回归。</li>
     * </ul>
     *
     * @param optionLabels 选项名 → 本地化显示名（<b>已剥星号</b>；无 lang 文件则空 map）
     * @param starMarked   星号标记的选项名集合（判据①；可为 null）
     * @param optionHints  选项名 → 说明 / 注释文本（判据②；可为 null）
     * @return 选项名 → 依赖的能力 id 集合（不可变；无声明的选项不在表里）
     */
    public static Map<String, Set<String>> declaredDependencies(
            Map<String, String> optionLabels, Set<String> starMarked, Map<String, String> optionHints) {
        Map<String, String> labels = optionLabels == null ? Map.of() : optionLabels;
        Map<String, String> hints = optionHints == null ? Map.of() : optionHints;
        Set<String> starred = starMarked == null ? Set.of() : starMarked;
        // 只归一到「唯一那条缺失能力」：候选集 >1 时星号无法确定归属，不映射（见 matchOne）。
        String soleMissingId = MISSING_CAPABILITIES.size() == 1
                ? MISSING_CAPABILITIES.getFirst().id()
                : null;

        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        for (String optionName : starred) {
            // 星号只说「依赖某能力」，不说依赖哪个 ⇒ 优先用该选项自己的说明文本消歧，
            // 消歧不出来且缺失能力唯一 ⇒ 确定地归到那一条（候选集唯一 ⇒ 不是猜）。
            String capabilityId = matchOne(hints.getOrDefault(optionName, ""));
            if (capabilityId == null) {
                capabilityId = soleMissingId;
            }
            if (capabilityId == null) {
                continue;
            }
            dependencies.computeIfAbsent(optionName, key -> new LinkedHashSet<>()).add(capabilityId);
        }
        // ② 显式引用（不依赖本地化，任何包里出现就生效）
        for (Map.Entry<String, String> entry : hints.entrySet()) {
            String capabilityId = matchOne(entry.getValue());
            if (capabilityId == null) {
                continue;
            }
            dependencies.computeIfAbsent(entry.getKey(), key -> new LinkedHashSet<>()).add(capabilityId);
        }
        // 🔖 兜底：标签表里若还留着带星号的显示名，说明调用方自己解析了 lang（或用了旧契约）
        //   ⇒ 接受它，而不是静默漏掉整个闭包（那会让门控看起来「跑过了」却什么都没做）。
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            String value = entry.getValue();
            if (value == null || !value.strip().endsWith("*")) {
                continue;
            }
            String capabilityId = matchOne(hints.getOrDefault(entry.getKey(), ""));
            if (capabilityId == null) {
                capabilityId = soleMissingId;
            }
            if (capabilityId != null) {
                dependencies.computeIfAbsent(entry.getKey(), key -> new LinkedHashSet<>())
                        .add(capabilityId);
            }
        }
        return freezeDependencies(dependencies);
    }

    /**
     * 在一段包侧文本里匹配出<b>唯一一条</b>缺失能力；匹配不到或匹配到多条返回 {@code null}。
     *
     * <p>🔖 返回 {@code null} 有两种含义，调用方必须区分：
     * 「文本没提任何缺失能力」（正常，走别的路）与「提了多条但我们只有一条登记」（歧义，不猜）。
     * 这里用「只登记一条缺失能力」的前提把两者合并 —— 一旦 {@code MISSING_CAPABILITIES} ≥2，
     * 本方法必须升级成返回集合，否则星号路径会静默按第一条匹配。
     */
    private static String matchOne(String text) {
        if (text == null || text.isEmpty() || MISSING_CAPABILITIES.isEmpty()) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        boolean hit = false;
        for (String alias : MATERIAL_MAPS_ALIASES) {
            if (lower.contains(alias)) {
                hit = true;
                break;
            }
        }
        return hit ? MISSING_CAPABILITIES.getFirst().id() : null;
    }

    private static Map<String, Set<String>> freezeDependencies(Map<String, Set<String>> raw) {
        Map<String, Set<String>> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : raw.entrySet()) {
            frozen.put(entry.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(frozen);
    }

    // ---------------------------------------------------------------- 门控

    /**
     * 执行门控：把「声明依赖缺失能力」的<b>布尔</b>选项在内存里强制为关。
     *
     * @param request 输入（选项容器 + 包侧声明 + 两个开关）
     * @return 门控结果（真正被覆盖的项 + 全部诊断）
     */
    public static GateResult apply(GateRequest request) {
        Objects.requireNonNull(request, "request");
        PackOptions options = request.options();
        List<OptionDiagnostic> diagnostics = new ArrayList<>();
        LinkedHashMap<String, String> gated = new LinkedHashMap<>();

        if (!request.enabled()) {
            diagnostics.add(OptionDiagnostic.info("CAPABILITY_GATE_OFF",
                    "能力门控已由配置关闭（pack.capabilityGate=false）⇒ 依赖缺失素材的特性保持包内原值"
                            + "（画面可能不正确 —— 这是用户的显式选择，不是静默降级）"));
            return new GateResult(gated, diagnostics);
        }
        if (!request.applicable()) {
            diagnostics.add(OptionDiagnostic.info("CAPABILITY_GATE_NOT_APPLICABLE",
                    "包地形片元未接线（mrt.packTerrainShader=false 或无可用 gbuffers_terrain）"
                            + " ⇒ 缺能力的那条渲染路径根本不会执行 ⇒ 门控不适用，"
                            + "不干预任何包特性（X27：不在无影响处砍特性）"));
            return new GateResult(gated, diagnostics);
        }
        if (MISSING_CAPABILITIES.isEmpty()) {
            diagnostics.add(OptionDiagnostic.info("CAPABILITY_GATE_NO_GAP",
                    "本引擎当前无已登记的缺失能力（" + describeMissingCapabilities() + "）⇒ 不门控"));
            return new GateResult(gated, diagnostics);
        }

        Map<String, Set<String>> dependencies = declaredDependencies(
                request.optionLabels(), request.starMarkedOptions(), request.optionHints());
        if (dependencies.isEmpty()) {
            diagnostics.add(OptionDiagnostic.info("CAPABILITY_GATE_NO_DECLARATION",
                    "本包未声明任何依赖关系（无星号标记、无显式依赖文本）⇒ 能力门控不干预"
                            + "（逐包判定：不硬编码任何包特性名。Complementary 即属此列 —— "
                            + "它有视差但复用原版 atlas、零外部依赖）"));
            return new GateResult(gated, diagnostics);
        }
        if (MISSING_CAPABILITIES.size() > 1) {
            diagnostics.add(OptionDiagnostic.warn(AMBIGUOUS_CAPABILITY_FOR_STAR,
                    "本引擎已登记 " + MISSING_CAPABILITIES.size() + " 条缺失能力，"
                            + "显示名末尾的 '*' 标记无法确定归属 ⇒ 依赖图可能不完整，"
                            + "门控结果按实际命中的能力计算（不按星号猜）"));
        }

        Set<String> missingIds = new LinkedHashSet<>();
        for (Capability capability : MISSING_CAPABILITIES) {
            missingIds.add(capability.id());
        }
        // 闭包 = 「声明依赖任一缺失能力」的全部选项。
        // 本类的依赖图是「选项 → 能力」的一层（不是「选项 → 选项」），
        // 闭包体现在这里：命中一项能力 ⇒ 声明依赖它的所有开关一起关 ——
        // 这与 BSL 的 18 项闭包形态一致（它们都依赖同一个 ADVANCED_MATERIALS）。
        Set<String> closure = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
            if (!Collections.disjoint(entry.getValue(), missingIds)) {
                closure.add(entry.getKey());
            }
        }

        for (String optionName : closure) {
            Option option = options.findDefinition(optionName).orElse(null);
            if (option == null) {
                // 包声明了依赖，但这个选项不在本包的选项定义里（lang 与实际声明不同步）
                // ⇒ 显式 WARN。「门控静默空转」正是这一刻该被抓住的。
                diagnostics.add(OptionDiagnostic.warn("CAPABILITY_GATE_UNKNOWN_OPTION",
                        "包声明选项 '" + optionName + "' 依赖缺失能力，但它不在本包的选项定义中"
                                + " ⇒ 未门控（lang 与选项表不同步？T11 不静默）"));
                continue;
            }
            if (option.type() != OptionType.BOOLEAN) {
                diagnostics.add(OptionDiagnostic.info("CAPABILITY_GATE_NON_BOOLEAN_KEPT",
                        "选项 '" + optionName + "' 依赖缺失能力，但类型是 " + option.type()
                                + " ⇒ 保留用户值（缺能力只会让布尔开关产生坏画面，"
                                + "数值选项的值本身无害；X27 反对无谓砍特性）"));
                continue;
            }
            String current = options.findValue(optionName).orElse(null);
            if (current == null) {
                diagnostics.add(OptionDiagnostic.warn("CAPABILITY_GATE_NO_VALUE",
                        "选项 '" + optionName + "' 没有当前值 ⇒ 未门控（T11 不静默）"));
                continue;
            }
            if (isOffToken(current)) {
                // 本来就是关的 ⇒ 不记进 gated（否则日志会说「我们关了它」，
                // 而真相是「它本来就是关的」—— 下一次取证会误判归因）。
                continue;
            }
            PackOptions.SetOutcome outcome = options.set(optionName, "false");
            if (!outcome.applied()) {
                diagnostics.add(OptionDiagnostic.warn("CAPABILITY_GATE_SET_REJECTED",
                        "试图门控 '" + optionName + "' 为 false 但被拒绝（" + outcome.status()
                                + "）⇒ 保持原值，画面可能不正确"));
                continue;
            }
            gated.put(optionName, outcome.appliedValue());
            diagnostics.add(OptionDiagnostic.warn("CAPABILITY_GATE_APPLIED",
                    "选项 '" + optionName + "' 声明依赖本引擎缺失的能力（"
                            + describeCapabilities(dependencies.get(optionName))
                            + "）⇒ 已在内存中关闭为 false。"
                            + "🔖 未写你的 optionsv2.txt（裁决：不改写用户配置，无先例）；"
                            + "你重新打开后，下次加载仍会被门控。依据：" + evidenceOf()));
        }
        if (!gated.isEmpty()) {
            diagnostics.add(OptionDiagnostic.warn("CAPABILITY_GATE_SUMMARY",
                    "能力门控共关闭 " + gated.size() + " 个包特性：" + gated.keySet()
                            + "（缺失能力：" + describeMissingCapabilities() + "）"));
        }
        return new GateResult(gated, diagnostics);
    }

    /** 是否为「关」的布尔取值 token（口径与 {@code PackOptions} 的推导默认值一致）。 */
    private static boolean isOffToken(String value) {
        return value.equalsIgnoreCase("false") || value.equalsIgnoreCase("off")
                || value.equalsIgnoreCase("no") || "0".equals(value);
    }

    private static String describeCapabilities(Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return "<未指明>";
        }
        List<String> parts = new ArrayList<>();
        for (String id : ids) {
            Capability found = findCapability(id);
            parts.add(found == null
                    ? id + "（未登记的缺失能力）"
                    : id + "（" + found.description() + "）");
        }
        return String.join("；", parts);
    }

    private static Capability findCapability(String id) {
        for (Capability capability : MISSING_CAPABILITIES) {
            if (capability.id().equals(id)) {
                return capability;
            }
        }
        return null;
    }

    private static String evidenceOf() {
        List<String> parts = new ArrayList<>();
        for (Capability capability : MISSING_CAPABILITIES) {
            parts.add(capability.evidence());
        }
        return parts.isEmpty() ? "<无登记证据>" : String.join(" | ", parts);
    }

    /** 缺失能力清单的可读文本（诊断与日志用）。 */
    public static String describeMissingCapabilities() {
        if (MISSING_CAPABILITIES.isEmpty()) {
            return "<无>";
        }
        List<String> parts = new ArrayList<>();
        for (Capability capability : MISSING_CAPABILITIES) {
            parts.add(capability.id() + " = " + capability.description());
        }
        return String.join("; ", parts);
    }
}
