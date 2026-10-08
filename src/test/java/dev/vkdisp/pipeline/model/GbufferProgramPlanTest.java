package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】GAP-027「多条 gbuffer 程序接进同一个 MRT pass」决策表的单测 / 纯数据断言
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓库 `docs/13-GAP-REGISTRY.md` GAP-027 条目
 *    与 `evidence/h48w-gap022-real-matrices.md` 实测的水契约（2 输出 / 槽 [0,1]）。
 *    被测类 `GbufferProgramPlan` 是本项目自有的纯数据决策表（MIT，零原版类型依赖）。
 *    → 可并入本项目（MIT）：本文件只断言行为，不含任何第三方代码。
 * 1. 官方/主实现：同被测类（纯数据表）。
 * 2. 备选：无（本就是单测）。
 * 3. 我们的差异点：期望值**独立于实现写死**（附件数取 max、水关着时逐字不变、
 *    只有真挂水的层才关写深度），这样「实现悄悄改错一条语义」会当场变红。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 单测，不适用。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vkdisp.pack.PackTerrainSource;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link GbufferProgramPlan} 的守卫。
 *
 * <p>🔖 本类存在的理由：该决策表是 GAP-027 的**核心纯逻辑**（哪条程序挂哪一层、由此得到
 * 几个附件、换程序后 location 该长什么样），它的 javadoc 明确写着「零原版类型依赖 ⇒
 * 可以在无 GPU、无 FML 的单测里逐条断言」—— 但它在 {@code src/test/} 里曾是**零引用**
 * （声明了可测性却没真的测）。本类补上这个缺口。
 */
class GbufferProgramPlanTest {

    private static final String TERRAIN = PackTerrainSource.TERRAIN_PROGRAM;
    private static final String WATER = PackTerrainSource.WATER_PROGRAM;
    private static final String TRANSLUCENT = GbufferProgramPlan.TRANSLUCENT_LAYER;

    /** 接上了的地形（BSL 默认档实测：1 个输出 / 声明槽 [0]）。 */
    private static GbufferProgramPlan.Entry terrain() {
        return new GbufferProgramPlan.Entry(TERRAIN, contract(TERRAIN, 1, List.of(0)));
    }

    /** 接上了的水（BSL 默认档实测：2 个输出 / 声明槽 [0,1]）。 */
    private static GbufferProgramPlan.Entry water() {
        return new GbufferProgramPlan.Entry(WATER, contract(WATER, 2, List.of(0, 1)));
    }

    /** 造一条程序契约（{@code outputCount} 至少 1 —— record 自身强制，不接用 {@code null} 表示）。 */
    private static PackTerrainProgram contract(String program, int outputs, List<Integer> slots) {
        return new PackTerrainProgram("BSL_v10.1.8", "world0/" + program,
                "layout(location = 0) out vec4 vkdispFragOut0;", outputs, List.of(), List.of(), slots);
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // ① 路径 / id
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("路径与 id 都是程序名的纯函数；两条程序不共用适配层")
    void pathsArePureFunctorsOfProgramName() {
        assertEquals("water", GbufferProgramPlan.shortName(WATER));
        assertEquals("terrain", GbufferProgramPlan.shortName(TERRAIN));
        // 不带 gbuffers_ 前缀时按原名返回（本方法同时喂路径与着色器 id ⇒ 抛异常会让两条虚拟包资源脱节）
        assertEquals("custom", GbufferProgramPlan.shortName("custom"));

        assertEquals("shaders/gbuffers_water.fsh", GbufferProgramPlan.fragmentPath(WATER));
        assertEquals("shaders/water_pack_adapter.vsh", GbufferProgramPlan.adapterPath(WATER));
        assertEquals("shaders/terrain_pack_adapter.vsh", GbufferProgramPlan.adapterPath(TERRAIN));
        // 🔴 核心：水的 14 条 varying 与地形的 9 条不是同一个签名（GAP-027 实测）
        //   ⇒ 适配层必须逐条各一份。共用一份 = 其中一条的 varying 必然少供 ⇒ 链接失败 ⇒ 客户端起不来。
        assertNotEquals(GbufferProgramPlan.adapterPath(WATER), GbufferProgramPlan.adapterPath(TERRAIN),
                "两条程序必须各有适配层（共用一份 = 其中一条的 varying 少供 = 链接失败）");
        assertEquals(WATER, GbufferProgramPlan.fragmentShaderPath(WATER));
        assertEquals("water_pack_adapter", GbufferProgramPlan.adapterShaderPath(WATER));
    }

    @Test
    @DisplayName("地形保持历史 _mrt 后缀；换程序就换 location")
    void mrtSuffixKeepsTerrainHistorical() {
        assertEquals("_mrt", GbufferProgramPlan.mrtSuffix(TERRAIN, TERRAIN),
                "地形必须保持既有后缀（历史臂与日志对账靠它）");
        assertEquals("_mrt", GbufferProgramPlan.mrtSuffix(null, TERRAIN), "null = 不接 ⇒ 沿用既有后缀");
        assertEquals("_water_mrt", GbufferProgramPlan.mrtSuffix(WATER, TERRAIN));
        // 🔴 同一层不许「换了片元却沿用同名 location」⇒ 两条同名不同内容的管线。
        assertNotEquals(GbufferProgramPlan.mrtSuffix(WATER, TERRAIN),
                GbufferProgramPlan.mrtSuffix(TERRAIN, TERRAIN));
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // ② 层 → 程序 / 深度写入（向后兼容是基线，必须逐层锁住）
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("水没接上时，三层的行为与「只有地形」逐字相同")
    void waterNotWiredLeavesEveryLayerUnchanged() {
        GbufferProgramPlan.Entry terrain = terrain();
        GbufferProgramPlan.Entry waterOff = new GbufferProgramPlan.Entry(WATER, null);
        for (String layer : List.of("SOLID", "CUTOUT", TRANSLUCENT)) {
            assertEquals(TERRAIN, GbufferProgramPlan.programForLayer(layer, terrain, waterOff),
                    "水没接上时 " + layer + " 必须仍走地形 —— GAP-027 之前的行为是回归基线");
            assertEquals(TERRAIN, GbufferProgramPlan.programForLayer(layer, terrain, null),
                    "水那条声明不存在时同样走地形");
        }
        // 地形自己也没接 ⇒ 三层都交给原版（null = 沿用 core/terrain，不是失败）
        GbufferProgramPlan.Entry terrainOff = new GbufferProgramPlan.Entry(TERRAIN, null);
        for (String layer : List.of("SOLID", "CUTOUT", TRANSLUCENT)) {
            assertNull(GbufferProgramPlan.programForLayer(layer, terrainOff, waterOff));
        }
    }

    @Test
    @DisplayName("只有 TRANSLUCENT 会交给水，且必须真的接上了")
    void translucentGoesToWaterOnlyWhenWired() {
        GbufferProgramPlan.Entry terrain = terrain();
        GbufferProgramPlan.Entry water = water();
        assertEquals(WATER, GbufferProgramPlan.programForLayer(TRANSLUCENT, terrain, water));
        assertEquals(TERRAIN, GbufferProgramPlan.programForLayer("SOLID", terrain, water));
        assertEquals(TERRAIN, GbufferProgramPlan.programForLayer("CUTOUT", terrain, water));
        // entryForLayer 必须与 programForLayer 指向**同一份声明**（不是第二份状态）
        assertSame(water, GbufferProgramPlan.entryForLayer(TRANSLUCENT, terrain, water));
        assertSame(terrain, GbufferProgramPlan.entryForLayer("SOLID", terrain, water));
        assertNull(GbufferProgramPlan.entryForLayer(TRANSLUCENT,
                new GbufferProgramPlan.Entry(TERRAIN, null), null));
    }

    @Test
    @DisplayName("只有「真的挂了水」的半透明层才关写深度，其余层与既有臂逐字相同")
    void writesDepthIsOffOnlyForRealWaterLayer() {
        GbufferProgramPlan.Entry terrain = terrain();
        GbufferProgramPlan.Entry water = water();
        assertFalse(GbufferProgramPlan.writesDepth(TRANSLUCENT, terrain, water), "水面本身不写深度");
        assertTrue(GbufferProgramPlan.writesDepth("SOLID", terrain, water));
        assertTrue(GbufferProgramPlan.writesDepth("CUTOUT", terrain, water));
        // 水没接 ⇒ 半透明层照旧写深度（GAP-027 之前的基线；这里错了会静默丢掉深度）
        assertTrue(GbufferProgramPlan.writesDepth(TRANSLUCENT, terrain, null));
        assertTrue(GbufferProgramPlan.writesDepth(TRANSLUCENT, terrain,
                new GbufferProgramPlan.Entry(WATER, null)));
        assertTrue(GbufferProgramPlan.isWaterLayer(TRANSLUCENT, terrain, water));
        assertFalse(GbufferProgramPlan.isWaterLayer("SOLID", terrain, water));
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // ③ 冻结列表 / 自报行
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("冻结列表按传入顺序、保留「不接」的项（交给 MrtPlan 跳过）")
    void packProgramsKeepsOrderAndUnwiredEntries() {
        GbufferProgramPlan.Entry waterOff = new GbufferProgramPlan.Entry(WATER, null);
        List<MrtPlan.PackProgram> programs = GbufferProgramPlan.packPrograms(
                GbufferProgramPlan.entriesOf(terrain(), waterOff));
        assertEquals(2, programs.size(), "「不接」也是一条声明（要让 MrtPlan 看到并跳过）");
        assertEquals(TERRAIN, programs.get(0).name());
        assertEquals(1, programs.get(0).outputs());
        assertEquals(List.of(0), programs.get(0).declaredSlots());
        assertEquals(WATER, programs.get(1).name());
        assertEquals(0, programs.get(1).outputs(), "不接 ⇒ 输出数 0，freezePackPrograms 会跳过它");
        assertEquals(List.of(), programs.get(1).declaredSlots());

        assertEquals(1, GbufferProgramPlan.wiredCount(GbufferProgramPlan.entriesOf(terrain(), waterOff)));
        assertEquals(2, GbufferProgramPlan.wiredCount(GbufferProgramPlan.entriesOf(terrain(), water())));
        assertEquals(0, GbufferProgramPlan.wiredCount(List.of()));
        assertEquals(List.of(), GbufferProgramPlan.packPrograms(List.of()));
    }

    @Test
    @DisplayName("附件数取 max：水的 2 个输出必须撑大 pass，而不是按地形那 1 个定")
    void maxOutputsComesFromTheWidestProgram() {
        List<MrtPlan.PackProgram> programs =
                GbufferProgramPlan.packPrograms(List.of(terrain(), water()));
        // 🔴 这就是 freezePackPrograms 取 max 的输入：若哪天有人把它改成「取第一条」，
        //   水的第二个附件会被裁掉 ⇒ setPipeline 抛（附件数 ≠ 管线颜色目标数）⇒ 崩客户端。
        assertEquals(1, programs.get(0).outputs());
        assertEquals(2, programs.get(1).outputs());
        assertEquals(2, programs.stream().mapToInt(MrtPlan.PackProgram::outputs).max().orElse(0));
    }

    @Test
    @DisplayName("自报行必须同时给出「接了哪几条 / 各几个输出 / 附件数」")
    void wiredReportNamesProgramsAndAttachments() {
        String report = GbufferProgramPlan.wiredReport(
                GbufferProgramPlan.entriesOf(terrain(), water()), 2);
        assertTrue(report.contains(TERRAIN), report);
        assertTrue(report.contains("(1 out)"), report);
        assertTrue(report.contains(WATER), report);
        assertTrue(report.contains("(2 out)"), report);
        assertTrue(report.contains("attachments=2"), report);
        // 没接的不许出现在这一行（否则取证者会把「没接」读成「接上了」）
        String onlyTerrain = GbufferProgramPlan.wiredReport(
                GbufferProgramPlan.entriesOf(terrain(), new GbufferProgramPlan.Entry(WATER, null)), 1);
        assertFalse(onlyTerrain.contains(WATER), onlyTerrain);
    }

    @Test
    @DisplayName("附件数「没被写满」的那半句必须如实点名")
    void attachmentsNoteReportsUnwrittenSlots() {
        String note = GbufferProgramPlan.attachmentsNote(List.of(0, 3), 4);
        assertTrue(note.contains("[0, 3]"), note);
        assertTrue(note.contains("unwritten=[1, 2]"), note);
        // 写满时明确为空 —— 「没写满」与「写满了」必须在同一行里可区分
        assertTrue(GbufferProgramPlan.attachmentsNote(List.of(0, 1), 2).contains("unwritten=[]"));
    }

    @Test
    @DisplayName("「没接」的自报必须说清原因与后果（不许只写一句 not wired）")
    void notWiredReportStatesReasonAndConsequence() {
        String absent = GbufferProgramPlan.notWiredReport(WATER, GbufferProgramPlan.Skip.ABSENT, TERRAIN);
        assertTrue(absent.contains(WATER), absent);
        assertTrue(absent.contains(GbufferProgramPlan.Skip.ABSENT.text()), absent);
        assertTrue(absent.contains(TRANSLUCENT), absent);
        assertTrue(absent.contains(TERRAIN), "必须点名回退到哪条程序: " + absent);
        // 地形自己没接时后果完全不同（没有回退对象 ⇒ 整个 MRT pass 用原版）
        assertTrue(GbufferProgramPlan.notWiredReport(TERRAIN, GbufferProgramPlan.Skip.DISABLED, TERRAIN)
                .contains("core/terrain"));
    }

    @Test
    @DisplayName("Entry 的 wired 只认「契约在且输出数 > 0」；program 不许为 null")
    void entryWiredSemantics() {
        assertTrue(terrain().wired());
        assertTrue(water().wired());
        assertFalse(new GbufferProgramPlan.Entry(WATER, null).wired());
        assertThrows(NullPointerException.class, () -> new GbufferProgramPlan.Entry(null, null));
        MrtPlan.PackProgram unwired = new GbufferProgramPlan.Entry(WATER, null).toPackProgram();
        assertEquals(0, unwired.outputs());
        assertEquals(List.of(), unwired.declaredSlots());
    }
}
