package dev.vkdisp.pack;

import dev.vkdisp.config.PackOptions;
import dev.vkdisp.glsl.TranslateDiagnostic;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GAP-024 的**真包端到端**回归（BSL v10.1.8，生产同款 compile → build 链）。
 *
 * <p>🔖 <b>本类守的是 h48u 实测抓到的一条真回归</b>：链的候选按程序名去重时是「先到先得」，
 * 而 BSL 的枚举顺序是 {@code world-1 → world0 → world1} ⇒ 候选里留的是 <b>world-1 那条 Program</b>，
 * 可它的片元源永远不会被选中（{@code selectFragment} 给非偏好维度打 MAX_VALUE）。
 * BSL 逐字写着：
 * <pre>
 *   program.world0 /composite1.enabled = LIGHT_SHAFT                              （真）
 *   program.world-1/composite1.enabled = LIGHT_SHAFT &amp;&amp; MULTICOLORED_BLOCKLIGHT   （假）
 * </pre>
 * ⇒ 门控一接上，<b>光柱就被一个根本不进链的维度的表达式砍掉</b>
 * （运行臂自报 {@code skipped=[composite1, composite2, composite3]}，
 * 而我方选项表同时写着 {@code option name=LIGHT_SHAFT type=BOOLEAN default=true}）。
 *
 * <p>所以这里断言的不是「跳了几级」这种数字，而是<b>包意图</b>：
 * 开着的必须留在链里，关着的必须不在。
 */
class BslChainGatingEvidenceTest {

    private static final Path INVENTORY = Path.of("run/shaderpacks");

    private record Loaded(ShaderPack pack, ShaderPackCompiler.CompileResult compiled,
            PackOptions options) {
    }

    private static Loaded loadBsl() {
        Assumptions.assumeTrue(Files.isDirectory(INVENTORY), "库存目录不在本地（run/shaderpacks/）");
        List<ShaderPackScanner.DiscoveredPack> packs = ShaderPackScanner.scan(INVENTORY).packs();
        ShaderPackScanner.DiscoveredPack bsl = packs.stream()
                .filter(p -> p.name().startsWith("BSL_v10.1.8"))
                .findFirst().orElse(null);
        assertNotNull(bsl, "没扫到 BSL_v10.1.8；扫到的是 "
                + packs.stream().map(ShaderPackScanner.DiscoveredPack::name).toList());
        ShaderPackCompiler.CompileResult compiled = ShaderPackCompiler.compile(bsl, Map.of());
        return new Loaded(compiled.pack(), compiled, PackOptions.of(compiled.pack()));
    }

    @Test
    @DisplayName("🔖 真 BSL 默认档：光柱 composite1 必须留在链里，只跳 MOTION_BLUR/DOF 两级")
    void lightShaftSurvivesGatingOnRealBsl() {
        Loaded loaded = loadBsl();
        assertEquals("true", loaded.options().value("LIGHT_SHAFT"),
                "前提：我方认得 LIGHT_SHAFT 且它的默认值是 true（包 settings.glsl:205 是 #define）");
        assertEquals("false", loaded.options().value("MULTICOLORED_BLOCKLIGHT"),
                "前提：世界-1 那条表达式为假的原因就是这项关着");

        PackPostChain.Chain chain = PackPostChain.build(loaded.pack(), loaded.compiled(),
                "world0", loaded.options().values());
        List<String> names = chain.passes().stream().map(PackPostChain.Pass::programName).toList();

        assertTrue(names.contains("composite1"),
                "🔴 光柱被砍 = 按**不进链的维度**的表达式做决定（h48u 实测的那条回归）。链=" + names);
        assertFalse(names.contains("composite2"), "MOTION_BLUR 关着 ⇒ 该跳，链=" + names);
        assertFalse(names.contains("composite3"), "DOF 关着 ⇒ 该跳，链=" + names);
        assertTrue(names.contains("deferred"), "AO 开着 ⇒ deferred 必须在");
        assertTrue(names.contains("composite6") && names.contains("composite7"),
                "FXAA/TAA 开着且 RETRO_FILTER 关 ⇒ 这两级必须在");
        assertTrue(names.contains("final"), "链必须仍以 final 收尾");
    }

    @Test
    @DisplayName("🔖 门控自报行必须存在，且它报的跳过集与链的实际内容一致")
    void gatingReportMatchesTheChainItProduced() {
        Loaded loaded = loadBsl();
        PackPostChain.Chain chain = PackPostChain.build(loaded.pack(), loaded.compiled(),
                "world0", loaded.options().values());

        String report = chain.diagnostics().stream()
                .map(TranslateDiagnostic::message)
                .filter(m -> m != null && m.contains(ChainEnableGating.REPORT_PREFIX))
                .findFirst().orElse(null);
        assertNotNull(report, "每次装配都必须有一行门控自报（跳过 0 级时也照打），实际诊断数="
                + chain.diagnostics().size());
        assertTrue(report.contains("gating=on"), report);
        assertTrue(report.contains("unresolved=[]"),
                "BSL 的表达式我方应全部认得；认不出就要逐条点名，实际: " + report);
        assertTrue(report.contains("skippedNames=[composite2, composite3]"),
                "自报的跳过集必须与链的实际内容同源，实际: " + report);
    }

    @Test
    @DisplayName("关掉门控 ⇒ 旧行为逐字回来（11 级全进，跳过 0）")
    void gatingOffRestoresLegacyChain() {
        Loaded loaded = loadBsl();
        PackChainGatingSwitch.override(() -> false);
        try {
            PackPostChain.Chain chain = PackPostChain.build(loaded.pack(), loaded.compiled(),
                    "world0", loaded.options().values());
            List<String> names = chain.passes().stream()
                    .map(PackPostChain.Pass::programName).toList();
            assertTrue(names.contains("composite1") && names.contains("composite2")
                            && names.contains("composite3"),
                    "OFF 臂必须连关着的两级也照跑（这才是「与接入前逐字一致」），链=" + names);
            String report = chain.diagnostics().stream()
                    .map(TranslateDiagnostic::message)
                    .filter(m -> m != null && m.contains(ChainEnableGating.REPORT_PREFIX))
                    .findFirst().orElseThrow();
            assertTrue(report.contains("gating=off") && report.contains("skipped=0"), report);
        } finally {
            PackChainGatingSwitch.override(null);
        }
    }

    @Test
    @DisplayName("打印真包的门控输入（判据由人读；跑绿即可，不锁数字）")
    void dumpGatingInputsForHumans() {
        Loaded loaded = loadBsl();
        for (Program program : loaded.pack().programs()) {
            String n = program.name();
            if (!(n.startsWith("composite") || n.startsWith("deferred"))) {
                continue;
            }
            System.out.println("[evidence] " + program.dimensionFolder() + "/" + n
                    + "  enabled=" + program.settings().get("enabled"));
        }
        PackPostChain.Chain chain = PackPostChain.build(loaded.pack(), loaded.compiled(),
                "world0", loaded.options().values());
        System.out.println("[evidence] 链=" + chain.passes().stream()
                .map(PackPostChain.Pass::qualifiedName).toList());
        chain.diagnostics().stream()
                .map(TranslateDiagnostic::message)
                .filter(m -> m != null && m.contains(ChainEnableGating.REPORT_PREFIX))
                .forEach(m -> System.out.println("[evidence] " + m));
        assertEquals("true", loaded.options().value("LIGHT_SHAFT"));
    }
}
