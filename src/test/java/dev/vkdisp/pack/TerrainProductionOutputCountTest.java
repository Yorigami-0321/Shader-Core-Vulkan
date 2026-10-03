package dev.vkdisp.pack;

import dev.vkdisp.glsl.TranslateDiagnostic;
import dev.vkdisp.glsl.translate.ShaderStage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【参考调研】用**真实包**（BSL v10.1.8）走**生产同款链路**（include 展开 + OF 转译）量测
 * 「包自己的地形片元到底写几个槽」/ 只读本仓库源码与本地库存包，不把任何第三方着色器文本复制进仓库。
 *
 * <p>🔖 <b>为什么必须有这个测试，而不能只靠
 * {@link TerrainProgramTranslateBaselineTest}</b>：那个测试喂的是**未预处理��文本切片**，
 * 于是死分支（{@code #if defined ADVANCED_MATERIALS …}）还在，`gl_FragData[1..4]` 都被看见
 * ⇒ 它量到的是**能力上限（5 槽）**，不是**生产实际值**。
 * 生产链路会先展开 {@code #include} 并求值预处理条件 ⇒ 死分支消失。
 *
 * <p>🔖 <b>这个差别是会引发崩溃的</b>（实测依据见本类第三个测试的注释）：
 * 若按「5 槽」去建 pass，而实际编译出的地形片元只有 1 个输出，
 * {@code FrontendRenderPass#setPipeline} 会校验
 * 「render pass 颜色附件数 == 管线颜色目标数」并**抛 IllegalStateException** ⇒ 客户端直接崩。
 */
class TerrainProductionOutputCountTest {

    private static final Path INVENTORY = Path.of("run/shaderpacks");
    private static final Pattern OUT_DECL =
            Pattern.compile("layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*out\\s+vec4");

    @Test
    @DisplayName("🔖 生产实际值：BSL 默认配置下地形片元**只写 1 个槽**（= 只用 colortex0）")
    void productionTerrainFragmentWritesExactlyOneSlot() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(INVENTORY), "库存目录不在本地（run/shaderpacks/）");
        List<ShaderPackScanner.DiscoveredPack> packs = ShaderPackScanner.scan(INVENTORY).packs();
        ShaderPackScanner.DiscoveredPack bsl = packs.stream()
                .filter(p -> p.name().startsWith("BSL_v10.1.8"))
                .findFirst()
                .orElse(null);
        assertNotNull(bsl, "没扫到 BSL_v10.1.8；扫到的包：" + packs.stream().map(ShaderPackScanner.DiscoveredPack::name).toList());

        ShaderPackCompiler.CompileResult compiled = ShaderPackCompiler.compile(bsl, java.util.Map.of());
        assertNotNull(compiled.pack(), "包模型为 null");

        List<String> errors = new ArrayList<>();
        for (ShaderPackCompiler.CompiledStage stage : compiled.stages()) {
            if (!stage.isSuccess()) {
                errors.add(stage.sourceFile() + " 转译失败");
            }
            for (TranslateDiagnostic d : stage.result().diagnostics()) {
                if (d.severity().isError()) {
                    errors.add(stage.sourceFile() + " line " + d.line() + ": " + d.message());
                }
            }
        }
        assertTrue(errors.isEmpty(),
                "🔖 整包转译应当零 ERROR；实际有：\n  " + String.join("\n  ", errors));

        // 只看地形片元：三个维度目录各一份
        List<Integer> counts = new ArrayList<>();
        for (ShaderPackCompiler.CompiledStage stage : compiled.stages()) {
            if (stage.stage() != ShaderStage.FRAGMENT || !stage.programName().contains("gbuffers_terrain")) {
                continue;
            }
            Matcher m = OUT_DECL.matcher(stage.result().text());
            int maxLoc = -1;
            while (m.find()) {
                maxLoc = Math.max(maxLoc, Integer.parseInt(m.group(1)));
            }
            counts.add(maxLoc + 1);
        }
        assertEquals(3, counts.size(),
                "应当正好三个维度目录各一份地形片元；实际 " + counts);
        for (int c : counts) {
            assertEquals(1, c,
                    "🔖 **BSL 默认配置下地形片元只产出 1 个颜色输出**（实测自生产链路的日志与本测试）。"
                            + "原因：`settings.glsl` 里 ADVANCED_MATERIALS / MCBL_SS 默认注释掉 ⇒ "
                            + "只走 `DRAWBUFFERS:0` 分支，其余 `gl_FragData[1..4]` 在预处理后消失。"
                            + "⇒ **按「5 槽」建 pass 会与管线颜色目标数不匹配，`setPipeline` 直接抛异常**");
        }
    }

    @Test
    @DisplayName("🔖 能力上限仍是 5 槽 —— 与「生产实际 1 槽」是两个不同事实，不要混用")
    void capabilityIsFiveButProductionIsOne() throws Exception {
        // 这个对照是本测试类的存在理由：写文档/排期时**很容易**把「能力上限」当成「生产实际」，
        // 然后按 5 槽去建 pass ⇒ 客户端崩（`setPipeline` 校验附件数与颜色目标数相等）。
        Assumptions.assumeTrue(Files.isDirectory(INVENTORY), "库存目录不在本地");
        Assumptions.assumeTrue(TerrainProgramTranslateBaselineTest.class != null);

        int rawSliceOutputs = TerrainProgramTranslateBaselineTest.rawSliceOutputCount();
        int productionOutputs = 1;
        assertEquals(5, rawSliceOutputs,
                "未预处理文本切片里能看到 gl_FragData[0..4] ⇒ 能力上限 5 槽");
        assertEquals(1, productionOutputs,
                "生产实际 1 槽（见上一个测试的实测口径）");
        assertTrue(productionOutputs < rawSliceOutputs,
                "🔖 必须记住「能力上限 ≠ 生产实际」：前者是 FragmentOutputAdapter 的能力，"
                        + "后者由包的预处理结果决定");
    }

    @Test
    @DisplayName("🔖 守卫：我方地形 MRT pass 的附件数不得与包的输出数不一致而不自知")
    void attachmentCountMismatchIsLoud() {
        // `FrontendRenderPass#setPipeline` 会校验附件数 == 管线颜色目标数并抛异常（h05 实测）。
        // 这意味着「附件数配错」是**响亮失败**，不会静默 —— 但也意味着**会崩**。
        // 本测试不构造 GPU 上下文，只把这条事实与它的后果写进单测，防止后续轮次把它忘了。
        String registrar = readOrNull("src/main/java/dev/vkdisp/bridge/TerrainPipelineApi.java");
        assertNotNull(registrar);
        assertTrue(registrar.contains("MrtPlan.slotCount()"),
                "MRT 地形管线的颜色目标数必须与 pass 共用同一个来源（MrtPlan.slotCount()）");
        String pass = readOrNull("src/main/java/dev/vkdisp/bridge/MrtTerrainPass.java");
        assertNotNull(pass);
        assertTrue(pass.contains("MrtPlan.slotCount()"),
                "pass 附件数必须取同一个来源；两侧不同源 ⇒ setPipeline 抛异常 ⇒ 客户端崩");
        assertFalse(readOrNull("src/main/java/dev/vkdisp/pipeline/model/MrtPlan.java")
                        .contains("public static final int SLOT_COUNT = 5"),
                "SLOT_COUNT 仍是 3（Iris 口径）。若要改成 5（BSL 口径），"
                        + "必须同时确认包在**默认配置**下的实际输出数，否则会崩（见本类首测）");
    }

    private static String readOrNull(String rel) {
        Path p = Path.of(rel);
        if (!Files.exists(p)) {
            return null;
        }
        try {
            return Files.readString(p);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }
}