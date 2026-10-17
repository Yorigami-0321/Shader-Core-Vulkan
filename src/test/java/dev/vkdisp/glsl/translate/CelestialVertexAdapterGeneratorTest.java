package dev.vkdisp.glsl.translate;
/**
 * 【自行补充】GAP-027 日月顶点适配层生成器的守卫（含<b>真 BSL 契约</b>那一条）
 * 0. 合规核对：参考对象 = 本仓库自有 {@link PackVertexAdapterGeneratorTest}（同一族断言形状）。
 * 1. 官方/主实现：无。
 * 2. 备选：只跑合成输入 —— 否决：本层砸的是「与包的 in 签名逐位置逐名字对上」，
 *    合成输入证明不了真包的签名（X39：一条包样本不等于所有包，但至少要有真包）。
 * 3. 我们的差异点：三条各自钉住一条失败形状 —— 少供（驱动加载期抛，客户端起不来）、
 *    类型不符（静默错值）、名字不认识（编一个像的）。
 * 4. 许可证：LGPL-3.0-or-later；零第三方代码。
 * 5. 性能基线：❄️ 纯字符串 + 离线生成。
 */
import dev.vkdisp.pack.PackTerrainSource;
import dev.vkdisp.pipeline.model.PackTerrainProgram;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CelestialVertexAdapterGeneratorTest {

    private static final Path INVENTORY = Path.of("run/shaderpacks");

    private static final Pattern OUT = Pattern.compile(
            "layout\\(location\\s*=\\s*(\\d+)\\)\\s+out\\s+(\\w+)\\s+(\\w+)\\s*;");

    private static PackTerrainProgram.Input in(int location, String type, String name) {
        return new PackTerrainProgram.Input(location, type, name);
    }

    @Test
    @DisplayName("🔴 每条 in 必须有同 location 同名同类型的 out（少一条 = 加载期抛，客户端起不来）")
    void everyContractInputGetsAMatchingOutput() {
        List<PackTerrainProgram.Input> inputs = List.of(
                in(0, "vec2", "texCoord"), in(1, "vec3", "sunVec"), in(2, "vec3", "upVec"),
                in(3, "vec4", "color"), in(4, "float", "dist"), in(5, "vec3", "viewVector"));
        CelestialVertexAdapterGenerator.Result result =
                CelestialVertexAdapterGenerator.generate(inputs);
        assertNotNull(result.glsl(), () -> "该成的形被拒了：" + result.diagnostics());
        Matcher matcher = OUT.matcher(result.glsl());
        java.util.Map<String, String> outs = new java.util.LinkedHashMap<>();
        while (matcher.find()) {
            outs.put(matcher.group(3), matcher.group(1) + ":" + matcher.group(2));
        }
        for (PackTerrainProgram.Input input : inputs) {
            assertEquals(input.location() + ":" + input.type(), outs.get(input.name()),
                    "varying '" + input.name() + "' 必须按包声明的 location 与类型原样交出去"
                            + "（引擎只按 location 链接 ⇒ 号错就是静默喂错通道）");
        }
    }

    @Test
    @DisplayName("🔴 color 只能中性 vec4(1.0)，且必须点名（POSITION_TEX 里没有 Color 元素）")
    void colorIsNeutralAndReported() {
        CelestialVertexAdapterGenerator.Result result = CelestialVertexAdapterGenerator.generate(
                List.of(in(0, "vec4", "color")));
        assertTrue(result.glsl().contains("color = vec4(1.0);"),
                () -> "实际:\n" + result.glsl());
        assertTrue(result.constantSupplies().contains("color"),
                "中性供值必须进「常量供值」名单 —— 否则下一轮有人以为日月吃了原版的顶点色");
        assertTrue(result.diagnostics().stream().anyMatch(d -> !d.severity().isError()
                        && String.valueOf(d.message()).contains("color"))
                        || result.constantSupplies().contains("color"),
                "供值分档必须能从自报里读出来");
    }

    @Test
    @DisplayName("🔖 不认识的类型 ⇒ 整条不接线（glsl=null），而不是猜一个能编过的东西")
    void unsupportedTypeRefusesTheWholeProgram() {
        CelestialVertexAdapterGenerator.Result result = CelestialVertexAdapterGenerator.generate(
                List.of(in(0, "sampler2D", "weird")));
        assertNull(result.glsl(), "本层造不出这个通道就必须整条不接（半接线 = 链接期才炸）");
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.severity().isError()),
                () -> "拒绝必须点名：" + result.diagnostics());
    }

    @Test
    @DisplayName("🔬 真包对账：BSL 的 gbuffers_skytextured 契约能被这份生成器逐条满足")
    void realBslSkyTexturedContractIsServable() {
        Assumptions.assumeTrue(Files.isDirectory(INVENTORY), "库存目录不在本地（run/shaderpacks/）");
        PackTerrainSource.Result skyTextured = PackTerrainSource.generate(
                INVENTORY, "", "", null, "gbuffers_skytextured");
        assertTrue(skyTextured.wired(),
                "BSL 带 gbuffers_skytextured（world0/ 与 world1/ 各一份）；诊断："
                        + skyTextured.diagnostics().stream().map(d -> d.message()).toList());
        PackTerrainProgram program = skyTextured.program();
        assertEquals(List.of(0), program.declaredOutputSlots(),
                "日月片元只写 colortex0（DRAWBUFFERS:0）⇒ 派生管线必须单附件（多一个就 setPipeline 抛）");

        CelestialVertexAdapterGenerator.Result result =
                CelestialVertexAdapterGenerator.generate(program.inputs());
        assertNotNull(result.glsl(), () -> "真包签名本层供不了（下一条要补的就是它）："
                + result.diagnostics().stream().map(d -> d.message()).toList());
        Matcher matcher = OUT.matcher(result.glsl());
        java.util.Set<Integer> locations = new java.util.LinkedHashSet<>();
        while (matcher.find()) {
            assertTrue(locations.add(Integer.parseInt(matcher.group(1))),
                    () -> "同一个 location 交了两条 out（编译期就是重复声明）：" + result.glsl());
        }
        for (PackTerrainProgram.Input input : program.inputs()) {
            assertTrue(result.glsl().contains(
                            "layout(location = " + input.location() + ") out " + input.type() + " "
                                    + input.name() + ";"),
                    () -> "真包要 '" + input.name() + "'（" + input.type() + " @location "
                            + input.location() + "）而适配层没交；实际:\n" + result.glsl());
        }
    }

    @Test
    @DisplayName("🔴 POSITION 形状（穹顶）：没有 UV0 ⇒ 不许引用它；alpha 必须中性交 1.0")
    void positionFormatSuppliesAlphaAndNeverReadsUv0() {
        List<PackTerrainProgram.Input> inputs = List.of(
                in(0, "float", "alpha"), in(1, "vec3", "sunVec"), in(2, "vec3", "upVec"));
        CelestialVertexAdapterGenerator.Result result = CelestialVertexAdapterGenerator.generate(
                CelestialVertexAdapterGenerator.Format.POSITION, inputs);
        assertNotNull(result.glsl(), () -> "穹顶形状被拒：" + result.diagnostics());
        // 只有 Position 一条属性：顶点流里没有 UV0 ⇒ 引用它 = 加载期抛，绝不出现。
        assertTrue(result.glsl().contains("alpha = 1.0;"), () -> "穹顶 alpha 必须中性 1.0：\n" + result.glsl());
        assertTrue(result.constantSupplies().contains("alpha"), "中性 alpha 必须进常量供值名单（自报读得出）");
        assertFalse(result.glsl().contains("in vec2 UV0"),
                "POSITION 形状不许声明/引用 UV0（穹顶顶点流根本没有这条属性）");
        assertFalse(result.glsl().contains("= UV0"),
                "穹顶适配层若引用未声明的 UV0 就是加载期抛的半接线");
        // 形状头里只有 Position 一条 in。
        assertEquals(1, countIn(result.glsl()), "POSITION 形状只该有 1 条 in 属性");
    }

    @Test
    @DisplayName("🔴 POSITION 形状遇到 uv 名字也不许绑 UV0：落到零值 + 点名，而不是引用不存在的属性")
    void positionFormatRefusesUvBinding() {
        CelestialVertexAdapterGenerator.Result result = CelestialVertexAdapterGenerator.generate(
                CelestialVertexAdapterGenerator.Format.POSITION, List.of(in(0, "vec2", "texCoord")));
        assertNotNull(result.glsl());
        assertFalse(result.glsl().contains("= UV0"), "对不上形状的通道只能零值，不能引用 UV0 属性");
        assertTrue(result.glsl().contains("texCoord = vec2(0.0);"),
                () -> "实际:\n" + result.glsl());
    }

    @Test
    @DisplayName("🔬 真包对账：BSL 的 gbuffers_skybasic（穹顶）契约能被 POSITION 形状逐条满足")
    void realBslSkyBasicContractIsServableByPositionFormat() {
        Assumptions.assumeTrue(Files.isDirectory(INVENTORY), "库存目录不在本地（run/shaderpacks/）");
        PackTerrainSource.Result skyBasic = PackTerrainSource.generate(
                INVENTORY, "", "", null, "gbuffers_skybasic");
        assertTrue(skyBasic.wired(),
                "BSL 带 gbuffers_skybasic；诊断：" + skyBasic.diagnostics().stream().map(d -> d.message()).toList());
        PackTerrainProgram program = skyBasic.program();
        CelestialVertexAdapterGenerator.Result result = CelestialVertexAdapterGenerator.generate(
                CelestialVertexAdapterGenerator.Format.POSITION, program.inputs());
        assertNotNull(result.glsl(), () -> "真包穹顶契约本层供不了："
                + result.diagnostics().stream().map(d -> d.message()).toList());
        for (PackTerrainProgram.Input input : program.inputs()) {
            assertTrue(result.glsl().contains(
                            "layout(location = " + input.location() + ") out " + input.type() + " "
                                    + input.name() + ";"),
                    () -> "穹顶真包要 '" + input.name() + "'（" + input.type() + " @" + input.location()
                            + "）而 POSITION 适配层没交；实际:\n" + result.glsl());
        }
        assertFalse(result.glsl().contains("= UV0"), "穹顶真包不该逼出 UV0 引用");
    }

    private static int countIn(String glsl) {
        int n = 0;
        java.util.regex.Matcher m = Pattern.compile("\\)\\s+in\\s+\\w+\\s+\\w+\\s*;").matcher(glsl);
        while (m.find()) {
            n++;
        }
        return n;
    }
}
