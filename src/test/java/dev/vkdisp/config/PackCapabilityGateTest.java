package dev.vkdisp.config;
/**
 * 【参考调研】GAP-009 能力门控单测 / 门控判据与门控行为
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库自有的 {@link PackOptions} / {@link Option} / {@link OptionFixtures}
 *    （MIT 项目自有代码 + 自造最小样本）；判据口径来自 docs/13-GAP-REGISTRY.md GAP-009 条目
 *    与 review/2026-10-04-GAP009-素材缺失裁决简报.md。
 *    第三方 shaderpack 素材**零接触**：本测试用的星号标记样本是**按 BSL 实测事实手写的最小夹具**
 *    （19 条星号中取 3 条代表性项），不复制任何包的 lang 文本（18-PARALLEL §7.6 只允许自造最小样本）。
 *    → 能否并入本项目（MIT）：可以（测试代码不进分发 jar）
 *    → 例外条款：无
 * 1. 官方/主实现：无（门控是本项目自研的编排逻辑）。
 * 2. 备选：无。
 * 3. 我们的差异点（每条测试都在钉一个**具体的设计主张**，不是覆盖率凑数）：
 *    <ul>
 *      <li>🔴 <b>不硬编码选项名</b>：同一个门控类作用在两个「星号长相不同」的包上，
 *          关掉的必须是<b>各自</b>声明了依赖的那几项，且互不干涉。</li>
 *      <li>🔴 <b>Complementary 类包零星号 ⇒ 零门控</b>（它有视差但实测零外部依赖）。</li>
 *      <li>🔴 <b>只关布尔</b>：数值选项（INTEGER/FLOAT/STRING）必须原样保留。</li>
 *      <li>🔴 <b>本来就是关的不记账</b>：否则日志会把「它本来就关着」说成「我们关了它」。</li>
 *      <li>🔴 <b>门控关闭时也要有诊断</b>（不许静默 —— 否则画面又黑了查不到原因）。</li>
 *      <li>🔴 <b>作用域不适用时不干预</b>（X27：不在没执行的地方砍特性）。</li>
 *      <li>🔴 <b>不写用户文件</b>：门控走 {@code PackOptions#set}，存储层完全不被触碰。</li>
 *    </ul>
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：测试代码不进运行时。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vkdisp.pack.Option;
import dev.vkdisp.pack.ShaderPack;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GAP-009 能力门控单测：钉住「按包自己声明的依赖门控、不硬编码选项名」这一整条设计。
 *
 * <p>🔖 <b>样本说明</b>：{@link #BSL_LIKE_LABELS} 是**按 BSL v10.1.8 的实测事实手写**的最小夹具
 * （显示名末尾带 {@code *} 表示「依赖资源包提供的材质贴图」），
 * 不是从包里复制的文本 —— 按 18-PARALLEL §7.6，测试只允许自造最小样本。
 */
class PackCapabilityGateTest {

    /** 本引擎唯一那条缺失能力（与主源码同一条，单测不重新定义语义，只引用）。 */
    private static final String MATERIAL_MAPS = "per-block-material-maps";

    /**
     * 「BSL 型」包的最小星号夹具。
     *
     * <p>🔖 取 3 项代表性：{@code PARALLAX}（实测压零的那一项）、
     * {@code SELF_SHADOW}、{@code REFLECTION_SPECULAR}。
     * 真实包是 19 条，这里只保留能证明「闭包」语义的最少集合。
     */
    private static final Map<String, String> BSL_LIKE_LABELS = Map.of(
            "PARALLAX", "Parallax Occlusion Mapping",
            "SELF_SHADOW", "Self Shadows",
            "REFLECTION_SPECULAR", "Specular Reflection");

    /**
     * 🔖 「BSL 型」包的星号集合（与 {@link #BSL_LIKE_LABELS} 同名项）。
     *
     * <p><b>为什么与标签表<b>分开</b>传</b>：真实链路上
     * {@code PackLangFile} 会把星号从显示名里<b>剥掉</b>、单独收进
     * {@code Result#starMarkedOptions()} ⇒ 门控的星号判据只认那一份集合。
     * <b>标签表里不该再带星号</b> —— 两处同步过一次就一定会漂移，
     * 而漂移的后果是「门控恒空转且看起来完全正常」（本次首版的真实 bug）。
     */
    private static final Set<String> BSL_STARS = Set.of("PARALLAX", "SELF_SHADOW", "REFLECTION_SPECULAR");

    /** 「Complementary 型」包：有视差选项，但 lang 里**零星号**（实测零外部依赖）。 */
    private static final Map<String, String> COMPLEMENTARY_LIKE_LABELS = Map.of(
            "PARALLAX", "Parallax Occlusion Mapping",
            "SPECULAR", "Specular Highlights");

    /** 逐包判定的第二个包：星号项与 BSL 型**部分重叠**（证明没有写死的包特性名单）。 */
    private static Set<String> otherPackStars() {
        return Set.of("PARALLAX", "SSS");
    }

    // ------------------------------------------------------------------ 依赖声明的读取

    @Test
    @DisplayName("星号标记 ⇒ 该选项声明依赖缺失能力")
    void starMarkedOptionDeclaresDependency() {
        Map<String, Set<String>> dependencies =
                PackCapabilityGate.declaredDependencies(BSL_LIKE_LABELS, BSL_STARS, Map.of());
        assertEquals(Set.of(MATERIAL_MAPS), dependencies.get("PARALLAX"),
                "带星号的选项必须声明依赖唯一那条缺失能力");
        assertEquals(Set.of(MATERIAL_MAPS), dependencies.get("SELF_SHADOW"));
        assertEquals(Set.of(MATERIAL_MAPS), dependencies.get("REFLECTION_SPECULAR"));
    }

    @Test
    @DisplayName("无星号 ⇒ 不声明依赖（Complementary 类包零门控的依据）")
    void unstarredOptionDeclaresNothing() {
        Map<String, Set<String>> dependencies =
                PackCapabilityGate.declaredDependencies(COMPLEMENTARY_LIKE_LABELS, Set.of(), Map.of());
        assertTrue(dependencies.isEmpty(),
                "没有星号也没有显式依赖文本 ⇒ 不声明依赖（实测 Complementary 正是此列："
                        + "它有视差但复用原版 atlas，零外部依赖）");
    }

    @Test
    @DisplayName("显式依赖文本可独立成立（星号被本地化时的后备通道）")
    void explicitHintAloneIsEnough() {
        // 🔖 这是「星号不可靠」时的唯一出路：lang 读不出来，但选项注释里写了依赖什么。
        Map<String, Set<String>> dependencies = PackCapabilityGate.declaredDependencies(
                Map.of("PARALLAX", "Parallax Occlusion Mapping"), Set.of("PARALLAX"),
                Map.of("PARALLAX", "Requires specular and normal maps from a resource pack"));
        assertEquals(Set.of(MATERIAL_MAPS), dependencies.get("PARALLAX"),
                "注释里显式提到材质贴图 ⇒ 依赖成立（不依赖本地化星号）");
    }

    @Test
    @DisplayName("依赖图不可变（调用方改不动）")
    void dependencyGraphIsImmutable() {
        Map<String, Set<String>> dependencies =
                PackCapabilityGate.declaredDependencies(BSL_LIKE_LABELS, BSL_STARS, Map.of());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> dependencies.put("X", Set.of(MATERIAL_MAPS)));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> dependencies.get("PARALLAX").add("other"));
    }

    // ------------------------------------------------------------------ 门控行为

    @Test
    @DisplayName("门控生效：星号闭包里的布尔项全被关掉，且值进覆盖表")
    void gateTurnsOffStarClosure() {
        PackOptions options = PackOptions.of(OptionFixtures.pack(
                OptionFixtures.bool("PARALLAX", "true"),
                OptionFixtures.bool("SELF_SHADOW", "true"),
                OptionFixtures.bool("AO", "true")));
        PackCapabilityGate.GateResult result = PackCapabilityGate.apply(
                new PackCapabilityGate.GateRequest(options, BSL_LIKE_LABELS, BSL_STARS, Map.of(), true, true));

        assertEquals(Map.of("PARALLAX", "false", "SELF_SHADOW", "false"), result.gated(),
                "只有声明了依赖的 PARALLAX / SELF_SHADOW 被关；AO 未声明依赖必须保留");
        assertEquals("false", options.value("PARALLAX"), "内存值必须真的变成 false");
        assertEquals("false", options.value("SELF_SHADOW"));
        assertEquals("true", options.value("AO"), "未声明依赖的选项不许被碰");
        assertTrue(result.hasDiagnostic("CAPABILITY_GATE_SUMMARY"));
    }

    @Test
    @DisplayName("🔴 逐包判定：另一个包的星号项互不干涉")
    void gateIsPerPack() {
        Map<String, String> otherPackLabels = Map.of(
                "PARALLAX", "Parallax Occlusion Mapping",
                "SSS", "Subsurface Scattering");
        PackOptions options = PackOptions.of(OptionFixtures.pack(
                OptionFixtures.bool("PARALLAX", "true"),
                OptionFixtures.bool("SSS", "true")));
        PackCapabilityGate.GateResult result = PackCapabilityGate.apply(
                new PackCapabilityGate.GateRequest(options, otherPackLabels, otherPackStars(), Map.of(), true, true));
        assertEquals(Map.of("PARALLAX", "false", "SSS", "false"), result.gated(),
                "两个包的声明长得几乎一样，门控照样逐包处理 —— 不存在任何写死的包特性名单");
    }

    @Test
    @DisplayName("Complementary 型包（有视差、零星号）⇒ 零门控")
    void packWithoutDeclarationIsUntouched() {
        PackOptions options = PackOptions.of(OptionFixtures.pack(
                OptionFixtures.bool("PARALLAX", "true")));
        PackCapabilityGate.GateResult result = PackCapabilityGate.apply(
                new PackCapabilityGate.GateRequest(
                        options, COMPLEMENTARY_LIKE_LABELS, Set.of(), Map.of(), true, true));
        assertTrue(result.isEmpty(), "未声明依赖 ⇒ 一个开关都不关（X27：不能砍可用的包特性）");
        assertEquals("true", options.value("PARALLAX"));
        assertTrue(result.hasDiagnostic("CAPABILITY_GATE_NO_DECLARATION"));
    }

    @Test
    @DisplayName("🔴 只关布尔：数值选项原样保留")
    void nonBooleanOptionsAreKept() {
        Map<String, String> labels = Map.of("PARALLAX_DEPTH", "Parallax Depth");
        PackOptions options = PackOptions.of(OptionFixtures.pack(
                OptionFixtures.integer("PARALLAX_DEPTH", "2", "0", "1", "2", "3")));
        PackCapabilityGate.GateResult result = PackCapabilityGate.apply(
                new PackCapabilityGate.GateRequest(options, labels, Set.of("PARALLAX_DEPTH"), Map.of(), true, true));
        assertTrue(result.isEmpty(), "缺能力不会让数值选项本身有害 ⇒ 不关（X27）");
        assertEquals("2", options.value("PARALLAX_DEPTH"));
        assertTrue(result.hasDiagnostic("CAPABILITY_GATE_NON_BOOLEAN_KEPT"));
    }

    @Test
    @DisplayName("🔴 本来就是关的不记账（否则日志会把「本来就关」说成「我们关了」）")
    void alreadyOffIsNotRecorded() {
        PackOptions options = PackOptions.of(OptionFixtures.pack(
                OptionFixtures.bool("PARALLAX", "false")));
        PackCapabilityGate.GateResult result = PackCapabilityGate.apply(
                new PackCapabilityGate.GateRequest(options, BSL_LIKE_LABELS, BSL_STARS, Map.of(), true, true));
        assertTrue(result.isEmpty(),
                "它本来就是关的 ⇒ 不能记成「我们关了它」（否则下一次取证会误判归因）");
    }

    @Test
    @DisplayName("门控关闭 ⇒ 零门控，但必须有 INFO 诊断（不许静默）")
    void gateOffProducesDiagnostic() {
        PackOptions options = PackOptions.of(OptionFixtures.pack(
                OptionFixtures.bool("PARALLAX", "true")));
        PackCapabilityGate.GateResult result = PackCapabilityGate.apply(
                new PackCapabilityGate.GateRequest(options, BSL_LIKE_LABELS, BSL_STARS, Map.of(), false, true));
        assertTrue(result.isEmpty());
        assertEquals("true", options.value("PARALLAX"), "门控关闭 ⇒ 不碰用户的值");
        assertNotNull(result.firstDiagnostic("CAPABILITY_GATE_OFF"),
                "关闭状态必须可见 —— 否则「画面又黑了」根本查不到原因");
    }

    @Test
    @DisplayName("🔴 作用域不适用 ⇒ 不干预（X27：不在没执行的地方砍特性）")
    void notApplicableDoesNotTouchOptions() {
        PackOptions options = PackOptions.of(OptionFixtures.pack(
                OptionFixtures.bool("PARALLAX", "true")));
        PackCapabilityGate.GateResult result = PackCapabilityGate.apply(
                new PackCapabilityGate.GateRequest(options, BSL_LIKE_LABELS, BSL_STARS, Map.of(), true, false));
        assertTrue(result.isEmpty());
        assertEquals("true", options.value("PARALLAX"));
        assertNotNull(result.firstDiagnostic("CAPABILITY_GATE_NOT_APPLICABLE"));
    }

    @Test
    @DisplayName("lang 与选项表不同步 ⇒ 显式 WARN，不静默空转")
    void unknownOptionInDeclarationWarns() {
        // 🔖 这正是「门控静默空转」该被抓住的时刻：lang 说 PARALLAX 依赖，但选项表里没有它。
        PackOptions options = PackOptions.of(OptionFixtures.pack(
                OptionFixtures.bool("SELF_SHADOW", "true")));
        PackCapabilityGate.GateResult result = PackCapabilityGate.apply(
                new PackCapabilityGate.GateRequest(options, BSL_LIKE_LABELS, BSL_STARS, Map.of(), true, true));
        assertNotNull(result.firstDiagnostic("CAPABILITY_GATE_UNKNOWN_OPTION"),
                "声明了但选项表里没有 ⇒ 必须 WARN（lang 与选项表不同步是真实故障）");
        assertEquals(Map.of("SELF_SHADOW", "false"), result.gated(),
                "同一闭包里存在的选项仍要正常门控，不能因为个别失配就整条放弃");
    }

    @Test
    @DisplayName("🔴 门控结果一定进覆盖表（默认值 true → 被关成 false ⇒ 必须出现在差分里）")
    void gatedValuesDifferFromDefaults() {
        PackOptions options = PackOptions.of(OptionFixtures.pack(
                OptionFixtures.bool("PARALLAX", "true")));
        PackCapabilityGate.apply(
                new PackCapabilityGate.GateRequest(options, BSL_LIKE_LABELS, BSL_STARS, Map.of(), true, true));

        Map<String, String> defaults = options.defaults();
        Map<String, String> overrides = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : options.values().entrySet()) {
            if (!entry.getValue().equals(defaults.get(entry.getKey()))) {
                overrides.put(entry.getKey(), entry.getValue());
            }
        }
        assertEquals(Map.of("PARALLAX", "false"), overrides,
                "覆盖表 = 当前值 vs 默认值的差分。门控关掉的项默认是 true，"
                        + "所以它**必须**出现在覆盖表里 —— 否则「关掉了但没生效」且日志说关了");
    }

    @Test
    @DisplayName("🔴 门控不写用户持久化（PackOptionStore 不被触碰）")
    void gateNeverWritesUserFiles() {
        PackOptionStore store = PackOptionStore.empty();
        PackOptions options = PackOptions.of(OptionFixtures.pack(
                OptionFixtures.bool("PARALLAX", "true")));
        PackCapabilityGate.GateResult result = PackCapabilityGate.apply(
                new PackCapabilityGate.GateRequest(options, BSL_LIKE_LABELS, BSL_STARS, Map.of(), true, true));
        // 门控只作用在内存里的 PackOptions 上；存储层从头到尾没被传进来，
        // 结构上就不可能写用户文件（裁决：不采用改写用户配置，无先例）。
        assertTrue(store.isEmpty());
        // 🔖 诊断落在**门控自己的列表**里（PackOptions 只收它自己 set 产生的诊断，
        //   门控那条是包在 SetOutcome 之外另加的）⇒ 断言门控列表，别断言 options。
        assertTrue(result.hasDiagnostic("CAPABILITY_GATE_APPLIED"),
                "门控动作必须留下可见诊断（T11），且诊断在门控结果里");
        assertEquals(1, result.diagnostics().stream()
                        .filter(d -> d.code().equals("CAPABILITY_GATE_APPLIED")).count(),
                "恰好一条（只关了一项）");
    }

    @Test
    @DisplayName("缺失能力登记非空且带实测依据（X9：改变用户画面的断言必须有据）")
    void missingCapabilitiesAreDocumented() {
        assertFalse(PackCapabilityGate.MISSING_CAPABILITIES.isEmpty(),
                "至少要登记一条缺失能力，否则门控永远空转");
        for (PackCapabilityGate.Capability capability : PackCapabilityGate.MISSING_CAPABILITIES) {
            assertFalse(capability.evidence().isBlank(),
                    "能力 '" + capability.id() + "' 必须带实测依据（evidence/…）");
            assertFalse(capability.description().isBlank());
        }
    }

    @Test
    @DisplayName("缺失能力清单可读（诊断与日志用）")
    void missingCapabilitiesDescribe() {
        String text = PackCapabilityGate.describeMissingCapabilities();
        assertTrue(text.contains(MATERIAL_MAPS), "可读文本里应含能力 id: " + text);
    }

    @Test
    @DisplayName("门控请求的标签/提示映射被冻结（调用方改不动）")
    void requestFreezesItsMaps() {
        PackOptions options = PackOptions.of(OptionFixtures.pack(
                OptionFixtures.bool("PARALLAX", "true")));
        PackCapabilityGate.GateRequest request = new PackCapabilityGate.GateRequest(
                options, new LinkedHashMap<>(BSL_LIKE_LABELS), BSL_STARS, Map.of(), true, true);
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> request.optionLabels().put("X", "y"));
    }

    @Test
    @DisplayName("🔴 标签表里仍带星号时也能识别（兜底：调用方自己解析 lang 的情形）")
    void starStillRecognisedWhenLabelsKeepIt() {
        PackOptions options = PackOptions.of(OptionFixtures.pack(
                OptionFixtures.bool("PARALLAX", "true"),
                OptionFixtures.bool("AO", "true")));
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("PARALLAX", "Parallax Occlusion Mapping*");
        labels.put("AO", "Ambient Occlusion");
        PackCapabilityGate.GateResult result = PackCapabilityGate.apply(
                new PackCapabilityGate.GateRequest(options, labels, Set.of(), Map.of(), true, true));
        assertEquals(Map.of("PARALLAX", "false"), result.gated(),
                "即使 starMarked 为空，标签里带星号也应被识别（兜底路径）；AO 不带星号必须保留");
        assertEquals("true", options.value("AO"));
    }

    @Test
    @DisplayName("多个布尔项一起门控时全部生效（闭包不是只关第一项）")
    void wholeClosureIsGated() {
        List<Option> many = List.of(
                OptionFixtures.bool("PARALLAX", "true"),
                OptionFixtures.bool("SELF_SHADOW", "true"),
                OptionFixtures.bool("REFLECTION_SPECULAR", "true"),
                OptionFixtures.bool("REFLECTION_ROUGH", "true"));
        ShaderPack pack = new ShaderPack("fixture", "/tmp/fixture", false,
                List.of(), many, Map.of(), Map.of(), java.util.Set.of());
        PackOptions options = PackOptions.of(pack);
        PackCapabilityGate.GateResult result = PackCapabilityGate.apply(
                new PackCapabilityGate.GateRequest(options, BSL_LIKE_LABELS, BSL_STARS, Map.of(), true, true));
        assertEquals(3, result.gated().size(),
                "三个带星号的项必须一起关（BSL 实测是 18 项闭包，不是单点）");
    }
}
