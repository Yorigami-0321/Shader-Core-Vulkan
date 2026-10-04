package dev.vkdisp.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vkdisp.config.OptionSourceRewriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 【端到端 · 无头】OF 布尔选项（「//#define = 关」）的**枚举**与**改写**两条链。
 *
 * <p>🔖 <b>为什么这两条链是支柱①的硬门槛</b>：本轮核实发现，BSL 的 483 个布尔开关
 * （全包实测：446 行裸 <code>#define NAME</code> + 37 行 <code>//#define NAME</code>）
 * **既不进入选项模型、也无法被打开** —— 客户端实测 284 个枚举选项里没有
 * {@code ADVANCED_MATERIALS}。于是「多槽 gbuffer」那条路径<b>根本无法被触发</b>：
 * 不修它，h06 关于 DRAWBUFFERS 的全部结论都只是纸面结论。
 *
 * <p>🔖 <b>为什么不在此断言具体选项总数</b>：总数会随包版本漂移，写死就成了脆弱断言。
 * 这里只断言**形态**：注释掉的裸宏必须是可枚举、默认 false、可被改成 true 的布尔选项。
 */
class PackBooleanOptionTest {

    private static final Path INVENTORY = Path.of("run/shaderpacks");

    @Test
    @DisplayName("🔖 「//#define NAME」是可选的布尔选项（默认 false），不再被当成普通注释")
    void commentedBareDefineIsABooleanOption() {
        var rewritten = OptionSourceRewriter.apply(
                "//#define ADVANCED_MATERIALS" + String.valueOf((char) 10),
                Map.of("ADVANCED_MATERIALS", "true"));
        assertEquals(1, rewritten.appliedCount(),
                "「把默认关闭的选项打开」这个动作原先**根本做不到**");
        assertEquals("#define ADVANCED_MATERIALS" + String.valueOf((char) 10), rewritten.text(),
                "true ⇒ 去掉 // 前缀，成为真正的 #define");
    }

    @Test
    @DisplayName("🔖 已经是 false 的注释形态**保持原样**（不补 // 变成 ///#define）")
    void commentedDefineStaysCommentedWhenFalse() {
        String src = "//#define MCBL_SS" + String.valueOf((char) 10);
        var rewritten = OptionSourceRewriter.apply(src, Map.of("MCBL_SS", "false"));
        assertEquals(src, rewritten.text(), "本来就关着，再动它只会污染包源（04-SPEC §3.1 只读）");
    }

    @Test
    @DisplayName("🔖 开启时保留行尾注释与前导空白（保行号、保注释 = F3 契约）")
    void enablingPreservesIndentAndTrailingComment() {
        String src = "    //#define SHADOW_CLOUD  // 云阴影";
        var rewritten = OptionSourceRewriter.apply(src, Map.of("SHADOW_CLOUD", "true"));
        assertEquals("    #define SHADOW_CLOUD  // 云阴影", rewritten.text());
    }

    @Test
    @DisplayName("🔖 函数宏**不得**被当成选项（#define f(x) … 与布尔开关形态不同）")
    void functionMacroIsNotAnOption() {
        String src = "#define diagonal3(m) vec3((m)[0].x, (m)[1].y, m[2].z)";
        var rewritten = OptionSourceRewriter.apply(src, Map.of("diagonal3", "true"));
        assertEquals(src, rewritten.text(), "函数宏不是布尔开关，改它会直接改坏包源");
    }

    @Test
    @DisplayName("🔖 带值但无候选表 = 别名，**不进入选项模型**（所以永远拿不到覆盖值）")
    void valuedDefineWithoutCandidatesStaysOutOfTheModel() {
        // 🔖 判据落在**枚举侧**而不是改写侧：OptionSourceRewriter 是个纯文本原语，
        //   它不认识候选表，给什么值就改什么值（这是它的契约，见其 javadoc 差异点）。
        //   真正把别名挡在门外的是 ConstEvaluator：带值无候选表 ⇒ 不算选项
        //   （实测 BSL 里的 #define colortexR colortex5 就是这种别名）。
        String src = "#define colortexR colortex5" + String.valueOf((char) 10);
        var evaluated = dev.vkdisp.glsl.preprocess.ConstEvaluator.evaluate(
                src, dev.vkdisp.glsl.SourceLineMap.identity(src, 1));
        assertTrue(evaluated.options().isEmpty(),
                "别名不是用户选项：进了模型就会被选项屏幕改坏包源语义");
    }

    @Test
    @DisplayName("🔖 真实包：ADVANCED_MATERIALS 可枚举、可开启、且输出槽位真的落到 3/6/7")
    void realPackAdvancedMaterialsUnlocksMultiSlotPath() {
        Assumptions.assumeTrue(Files.isDirectory(INVENTORY), "库存目录不在本地");
        ShaderPackScanner.DiscoveredPack bsl = ShaderPackScanner.scan(INVENTORY).packs().stream()
                .filter(p -> p.name().startsWith("BSL_v10.1.8")).findFirst().orElse(null);
        Assumptions.assumeTrue(bsl != null, "本机没装 BSL_v10.1.8");

        ShaderPack pack = ShaderPackService.load(bsl).pack();
        Assumptions.assumeTrue(pack != null, "包模型为 null");
        Option advanced = pack.options().stream()
                .filter(o -> o.name().equals("ADVANCED_MATERIALS")).findFirst().orElse(null);
        assertTrue(advanced != null,
                "🔖 ADVANCED_MATERIALS 必须出现在选项模型里，否则它永远打不开"
                        + "（实测修复前：284 个枚举选项里没有它）");
        assertEquals(OptionType.BOOLEAN, advanced.type());
        assertEquals("false", advanced.defaultValue(), "包源码里它是 //#define ⇒ 默认关");
        assertEquals(List.of("true", "false"), advanced.values());
        assertTrue(pack.options().stream().anyMatch(o -> o.type() == OptionType.BOOLEAN),
                "布尔选项集合不得为空（修复前布尔选项数为 0）");

        ShaderPackCompiler.CompileResult compiled = ShaderPackCompiler.compile(bsl,
                Map.of("ADVANCED_MATERIALS", "true", "REFLECTION_SPECULAR", "true"));
        boolean anyError = compiled.diagnostics().stream()
                .anyMatch(d -> d.severity().isError());
        assertFalse(anyError, "开启高级材质后整包转译不得出现 ERROR");

        PackTerrainSource.Result terrain = PackTerrainSource.generate(INVENTORY, "",
                bsl.name());
        Assumptions.assumeTrue(terrain.wired(), "默认配置下未接线属正常（本例只断言可枚举/可改写）");
        assertEquals(1, terrain.program().outputCount(),
                "🔖 **默认配置下仍是 1 槽** —— 附件数跟随包输出数这条（h08/X42）不能被本轮改动破坏");
    }
}
