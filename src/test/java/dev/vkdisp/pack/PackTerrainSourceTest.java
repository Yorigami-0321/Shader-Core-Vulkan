package dev.vkdisp.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vkdisp.pipeline.model.PackTerrainProgram;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 【端到端 · 无头】走**生产同款链路**选出包地形片元，并钉死它的契约。
 *
 * <p>🔖 <b>为什么无头也能测</b>：选包 + 转译 + 契约解析全是纯 Java（不含 SPIR-V 编译）——
 * SPIR-V 编译需要设备上下文，那部分由客户端取证覆盖（h07 已实测整包 190/190）。
 * 本类锁的是「接线前必须知道的数字」，即 h07 指出的真正缺口。
 *
 * <p>🔖 <b>为什么每个断言都带具体值</b>：附件数少算/多算 = 崩客户端；绑定组少登记一条 =
 * draw 时抛 Missing uniform；顶点适配层少供一条 varying = 链接失败。
 * 这三类都不是「画面差一点」，因此不能只断言「非空」。
 */
class PackTerrainSourceTest {

    private static final Path INVENTORY = Path.of("run/shaderpacks");
    private static final Pattern OUT_DECL = Pattern.compile(
            "^layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*out\\s+"
                    + "([A-Za-z_]\\w*)\\s+([A-Za-z_]\\w*)\\s*;",
            Pattern.MULTILINE);

    @Test
    @DisplayName("🔖 BSL 默认配置：地形片元契约 = 1 槽 / 5 个自由 sampler / 9 条 varying")
    void realPackContractIsFrozen() {
        Assumptions.assumeTrue(Files.isDirectory(INVENTORY), "库存目录不在本地（run/shaderpacks/）");
        PackTerrainSource.Result result = PackTerrainSource.generate(INVENTORY, "", "");
        assertTrue(result.wired(),
                "应当能选出地形片元；诊断："
                        + result.diagnostics().stream().map(d -> d.message()).toList());
        PackTerrainProgram program = result.program();
        assertNotNull(program);

        assertEquals(1, program.outputCount(),
                "🔖 **BSL 默认配置下地形片元只产出 1 个颜色输出**（h06/h07 实测口径，X42）。"
                        + "附件数必须跟随它，否则 setPipeline 抛 IllegalStateException 崩客户端");
        assertEquals(List.of("texture_0", "noisetex", "shadowtex0", "shadowtex1", "shadowcolor0"),
                program.fragmentSamplers(),
                "🔖 地形片的自由 sampler 收编集与 composite **不同**（X39：不可套用别的程序的清单）；"
                        + "绑定组布局必须按这份名单逐条登记");
        assertEquals(9, program.inputs().size(), "包的片元要 9 条 OF varying（location 0..8）");
        assertEquals(0, program.inputs().getFirst().location());
        assertEquals(8, program.inputs().getLast().location());
        assertEquals("mat", program.inputs().getFirst().name());
        assertEquals("color", program.inputs().getLast().name());
        assertTrue(program.qualifiedName().endsWith("gbuffers_terrain"),
                "选中的应当是 gbuffers_terrain，实际 " + program.qualifiedName());
    }

    @Test
    @DisplayName("🔖 GAP-027 第一条：BSL 默认档的 gbuffers_water 契约 = **2 槽** [0,1]（不是地形的 1 槽）")
    void waterContractNeedsMoreAttachmentsThanTerrain() {
        Assumptions.assumeTrue(Files.isDirectory(INVENTORY), "库存目录不在本地（run/shaderpacks/）");
        PackTerrainSource.Result water =
                PackTerrainSource.generate(INVENTORY, "", "", null, PackTerrainSource.WATER_PROGRAM);
        assertTrue(water.wired(),
                "BSL 带 gbuffers_water，按名字应当选得出；诊断："
                        + water.diagnostics().stream().map(d -> d.message()).toList());
        PackTerrainProgram program = water.program();
        assertTrue(program.qualifiedName().endsWith("gbuffers_water"),
                "选中的必须是水，实际 " + program.qualifiedName());
        // 依据（逐行取自包）：program/gbuffers_water.glsl:726-728 的 DRAWBUFFERS:01 在
        // **任何 #ifdef 之外**，无条件写 gl_FragData[0] 与 [1]；MCBL_SS 默认 false、
        // REFRACTION 默认 0 ⇒ 018/0186/016 三条分支全死。
        assertEquals(2, program.outputCount(),
                "🔴 水**无条件**写两个槽 ⇒ MRT pass 的附件数不能按地形那一条定（会崩 setPipeline）");
        assertEquals(List.of(0, 1), program.declaredOutputSlots(),
                "水声明写 colortex0 + colortex1");

        // 🔖 这条才是本测试的重点：证明「按程序各自取契约」不是把地形的清单换个名字。
        PackTerrainSource.Result terrain = PackTerrainSource.generate(INVENTORY, "", "");
        assertTrue(terrain.wired(), "对照项：地形契约应当仍在");
        assertEquals(1, terrain.program().outputCount(), "对照：地形默认档只有 1 个输出");
        assertNotEquals(terrain.program().fragmentSamplers(), program.fragmentSamplers(),
                "水的自由 sampler 清单必须与地形**不同**（X39：不可套用别的程序的清单；"
                        + "相同就说明参数化没生效）：" + program.fragmentSamplers());
        System.out.println("[GAP-027] water samplers=" + program.fragmentSamplers()
                + " varyings=" + program.inputs().size()
                + " 首条=" + program.inputs().getFirst().name()
                + " 末条=" + program.inputs().getLast().name());
    }

    @Test
    @DisplayName("🔖 生成的顶点适配层必须与包的片元 in 签名**逐位置逐名字对齐**")
    void generatedAdapterMatchesPackFragmentInputs() {
        Assumptions.assumeTrue(Files.isDirectory(INVENTORY), "库存目录不在本地");
        PackTerrainSource.Result result = PackTerrainSource.generate(INVENTORY, "", "");
        Assumptions.assumeTrue(result.wired(), "本机没选出地形片元，跳过签名对账");
        var adapter = dev.vkdisp.glsl.translate.PackVertexAdapterGenerator.generate(result.program().inputs(), false, false, false);
        java.util.Map<Integer, String> adapterOuts = new java.util.LinkedHashMap<>();
        Matcher m = OUT_DECL.matcher(adapter.glsl());
        while (m.find()) {
            adapterOuts.put(Integer.parseInt(m.group(1)), m.group(3));
        }
        java.util.Map<Integer, String> packIns = new java.util.LinkedHashMap<>();
        for (PackTerrainProgram.Input input : result.program().inputs()) {
            packIns.put(input.location(), input.name());
        }
        assertEquals(packIns, adapterOuts,
                "🔖 **适配层产出的 varying 必须与包片元要的逐位置逐名字一致**。"
                        + "少一条 = 驱动层在资源加载期抛 ShaderCompileException，**客户端起不来**"
                        + "（本轮实测：开 ADVANCED_MATERIALS 后要 15 条，静态适配层只供 9 条）");
    }

    @Test
    @DisplayName("🔖 shaderPack=none ⇒ 明确**不接线**（不是兜底 passthrough）")
    void selectionNoneMeansNotWired() {
        PackTerrainSource.Result result =
                PackTerrainSource.generate(Path.of("run", "shaderpacks"), "", "none");
        assertFalse(result.wired(), "保留名 none 必须不接线");
        assertNull(result.program(), "不接线的 program 必须为 null（不能是兜底文本）");
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.severity() == dev.vkdisp.glsl.TranslateDiagnostic.Severity.WARN),
                "「不接线」必须显式 WARN，否则用户以为开了却什么都没发生（T11）");
    }

    @Test
    @DisplayName("🔖 指定不存在的包 ⇒ 不接线且**不落到别的包**")
    void namedMissingPackDoesNotFallBack() {
        PackTerrainSource.Result result = PackTerrainSource.generate(INVENTORY, "", "no-such-pack");
        assertFalse(result.wired(), "指定包不存在时不得悄悄换包（否则 §6 的 A/B 判据失去意义）");
        assertNull(result.packName());
    }
}
