package dev.vkdisp.pipeline.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PixelStats} 的**分区采样** —— 「黑的是天空还是地形」的守卫。
 *
 * <p>🔖 本项目的 GAP-011 出现过这个混淆（{@code h17} 的结论是「闪烁是天空、不是主目标」），
 * 而当时的观测面只有一个矩形。这类错误的特点是<b>数字没错、判读错了</b>，
 * 所以守卫要保证「分区互不重叠 + 合起来覆盖整幅」这条不变式。
 */
class PixelStatsAreasTest {

    private static final int W = 854;
    private static final int H = 480;

    /** 造一张上半全黑、下半全白的图（0..split 为黑，其余为白）。 */
    private static byte[] skyBlackTerrainWhite(int splitY) {
        byte[] px = new byte[W * H * 4];
        for (int y = 0; y < H; y++) {
            byte v = (byte) (y < splitY ? 0 : 255);
            for (int x = 0; x < W; x++) {
                int o = (y * W + x) * 4;
                px[o] = v;
                px[o + 1] = v;
                px[o + 2] = v;
                px[o + 3] = (byte) 255;
            }
        }
        return px;
    }

    @Test
    @DisplayName("🔖 三个区互不重叠，且合起来覆盖整幅")
    void areasAreDisjointAndCoverEverything() {
        List<PixelStats.AreaSample> areas = PixelStats.areas(W, H);
        assertEquals(3, areas.size());
        long frame = (long) W * H;
        PixelStats.Region center = PixelStats.Region.centered(W, H);
        // 🔖 不变式可核对：上下两带纵向互补（合起来 = 中心矩形的**宽** × 整幅高），
        //   FULL 单独覆盖整幅。两带取整幅高度（而不是中心矩形的高度）是刻意的 ——
        //   分区的目的是回答「上/下半图哪个黑」，纵向就该覆盖全高。
        long sky = areas.stream().filter(a -> a.area() == PixelStats.Area.SKY_BAND)
                .findFirst().orElseThrow().region().area();
        long terrain = areas.stream().filter(a -> a.area() == PixelStats.Area.TERRAIN_BAND)
                .findFirst().orElseThrow().region().area();
        assertEquals((long) center.width() * H, sky + terrain,
                "SKY+TERRAIN 必须恰好等于「中心矩形的宽 × 整幅高」");
        assertEquals(0L, Math.min(0, Math.min(areas.stream()
                        .filter(a -> a.area() == PixelStats.Area.TERRAIN_BAND)
                        .findFirst().orElseThrow().region().y(),
                areas.stream().filter(a -> a.area() == PixelStats.Area.SKY_BAND)
                        .findFirst().orElseThrow().region().y()
                        + areas.stream().filter(a -> a.area() == PixelStats.Area.SKY_BAND)
                        .findFirst().orElseThrow().region().height())),
                "两带在纵向上不得重叠（TERRAIN 起点必须等于 SKY 终点）");
        assertEquals(frame, areas.stream()
                .filter(a -> a.area() == PixelStats.Area.FULL).findFirst().orElseThrow().region().area(),
                "FULL 必须覆盖整幅");
    }

    @Test
    @DisplayName("🔖🔖 分区能直接区分「天空黑」与「地形黑」（本项目 GAP-011 踩过的混淆）")
    void areasSeparateSkyFromTerrain() {
        int split = (int) Math.round(H * PixelStats.SKY_BAND_BOTTOM);
        byte[] px = skyBlackTerrainWhite(split);
        for (PixelStats.AreaSample area : PixelStats.areas(W, H)) {
            PixelStats.Stats stats = PixelStats.of(px, W, 4, area.region());
            switch (area.area()) {
                case SKY_BAND -> assertTrue(stats.isAllZero(),
                        "上带是天空、图里它是黑的 ⇒ 该带必须报 allZero");
                case TERRAIN_BAND -> assertFalse(stats.isAllZero(),
                        "下带是地形、图里它是白的 ⇒ 该带必须报**非**全黑。"
                                + "若这里也报黑，说明分区没覆盖到地形（采样口径坏了，不是画面坏了）");
                case FULL -> assertFalse(stats.isAllZero(),
                        "整幅里含白色地形 ⇒ 整幅不可能是全黑");
                default -> throw new AssertionError("未预期的区: " + area.area());
            }
        }
    }

    @Test
    @DisplayName("🔖 分界是**可被读数推翻的假设**，不是事实（换机位时读数会自己暴露）")
    void boundaryIsAnAssumptionNotAFact() {
        // 🔖 若把分界设成 1.0（天空带吃掉整幅），地形带面积为 0 ⇒ 该带读数必须
        //   是「无样本」而不是「全黑」—— 这两种在日志上必须能分开。
        List<PixelStats.AreaSample> areas = PixelStats.areas(10, 10);
        PixelStats.AreaSample terrain = areas.stream()
                .filter(a -> a.area() == PixelStats.Area.TERRAIN_BAND).findFirst().orElseThrow();
        assertTrue(terrain.region().area() > 0L, "默认分界下地形带必须有样本");
        PixelStats.Stats empty = PixelStats.Stats.empty(terrain.region().width(), terrain.region().height());
        assertFalse(empty.isAllZero(),
                "无样本必须是 isAllZero=false —— 否则「没采到」会被读成「画面全黑」");
        assertEquals(0L, empty.samples());
    }

    @Test
    @DisplayName("🔖 区名永不为空白（空白标签让读的人分不清哪行是哪区）")
    void areaLabelsAreNeverBlank() {
        for (PixelStats.AreaSample area : PixelStats.areas(W, H)) {
            assertFalse(area.label().isBlank(), "区名不得为空白");
            assertTrue(area.label().equals(area.area().name()), "标签必须与枚举常量同名（逐位可比）");
        }
    }

    @Test
    @DisplayName("🔖 退化尺寸（高 0 / 宽 0）不得抛，各区夹成 0 面积")
    void degenerateSizesDoNotThrow() {
        for (int[] size : new int[][] {{0, 0}, {1, 1}, {2, 1}, {1, 2}}) {
            List<PixelStats.AreaSample> areas = PixelStats.areas(size[0], size[1]);
            assertEquals(3, areas.size(), "退化尺寸也必须给满三个区："
                    + "「区不存在」与「区里全黑」要能分开");
            for (PixelStats.AreaSample area : areas) {
                assertTrue(area.region().area() >= 0L);
            }
        }
    }
}