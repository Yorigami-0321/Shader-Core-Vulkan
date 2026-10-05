package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】像素统计口径单测 / 本仓库自有的两个取证脚本（口径真源）
 * 0. 合规核对（第 0 步闸门）：参考对象 =
 *    ① {@code tools/vulkan-local/flicker_ratio.py}（中心区比例 0.45/0.15/0.65/0.75、黑阈值 THR=8）
 *    ② {@code tools/vulkan-local/p24_luma.py}（luma 系数 0.2126/0.7152/0.0722）
 *    ③ {@code evidence/h28-…}（判据「逐像素恰好 0」）、{@code evidence/h31-…}（「不翻转」判读对象）
 *    全部为本仓库自有脚本与自有证据文件，不受版权保护。
 *    → 能否并入本项目（MIT）：可以（测试代码不进分发 jar）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：无（纯逻辑统计类）。
 * 2. 备选：无。
 * 3. 我们的差异点（每条测试钉一个具体主张，逐条对应 h28/h31/h42 的教训）：
 *    <ul>
 *      <li>🔖 <b>采样区比例必须逐字等于脚本里的那一组</b> —— 否则本机数字与历史证据不可比，
 *      而「不可比」意味着 {@code h31} 的 {@code luma 96.1485} 之类的数字失去参照。</li>
 *      <li>🔖 <b>「逐像素全黑」与「均值≈0」必须能分开</b> —— 这是 h28 排除 {@code color} 因子的
 *      那条判据；只报均值会把「极稀疏非零」误读成「恰好 0」。</li>
 *      <li>🔖 <b>无样本 ≠ 全黑</b> —— 「没采到」与「采到且全黑」必须返回不同的值，
 *      否则日志上「没有数字」会被读成「数字是 0」（h42 刚被自己推翻过一次结论）。</li>
 *    </ul>
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：测试代码不进运行时。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 像素统计口径单测（把「判据能区分什么」钉成红灯）。 */
class PixelStatsTest {

    /** RGBA8_UNORM 的每像素字节数。 */
    private static final int BPP = 4;

    /** 构造一块全 0 的 RGBA 缓冲。 */
    private static byte[] zeros(int width, int height) {
        return new byte[width * height * BPP];
    }

    /** 在指定像素写一个 RGB 值。 */
    private static void put(byte[] buf, int width, int x, int y, int r, int g, int b) {
        int base = (y * width + x) * BPP;
        buf[base] = (byte) r;
        buf[base + 1] = (byte) g;
        buf[base + 2] = (byte) b;
        buf[base + 3] = (byte) 255;
    }

    @Test
    @DisplayName("🔖 采样区比例必须逐字等于 flicker_ratio.py 校准出的那一组")
    void regionMatchesCalibratedRatio() {
        PixelStats.Region region = PixelStats.Region.centered(930, 577);
        // flicker_ratio.py: FX0,FY0,FX1,FY1 = 0.45,0.15,0.65,0.75
        assertEquals((int) Math.round(930 * 0.45), region.x());
        assertEquals((int) Math.round(577 * 0.15), region.y());
        assertEquals((int) Math.round(930 * 0.65) - (int) Math.round(930 * 0.45), region.width());
        assertEquals((int) Math.round(577 * 0.75) - (int) Math.round(577 * 0.15), region.height());
        // h22 文档写的是「约 64000 px」，这个断言防止有人把比例改掉后不自知。
        assertTrue(region.area() > 60_000L && region.area() < 70_000L,
                "中心区面积应约 6.4 万 px（h22 口径），实际 " + region.area());
    }

    @Test
    @DisplayName("🔖 采样区按比例换算 ⇒ 窗口尺寸变了仍与历史数字可比")
    void regionIsResolutionIndependent() {
        PixelStats.Region small = PixelStats.Region.centered(854, 480);
        PixelStats.Region large = PixelStats.Region.centered(1920, 1080);
        double smallRatio = (double) small.area() / (854.0 * 480.0);
        double largeRatio = (double) large.area() / (1920.0 * 1080.0);
        assertEquals(smallRatio, largeRatio, 0.01,
                "采样区占整幅的比例必须在不同分辨率下近似相等 —— 否则改了窗口尺寸，"
                        + "新数字就不能与 h22/h31 的历史数字对比");
    }

    @Test
    @DisplayName("🔖🔖 全黑：逐像素恰好 0 ⇒ allZero=true（h28 的关键判据）")
    void allZeroBufferIsReportedAsAllZero() {
        byte[] buf = zeros(64, 64);
        PixelStats.Stats stats = PixelStats.of(buf, 64, BPP, new PixelStats.Region(0, 0, 64, 64));
        assertTrue(stats.isAllZero(), "全 0 缓冲必须判为逐像素恰好 0");
        assertEquals(0.0, stats.meanLuma(), 1e-9);
        assertEquals(0.0, stats.nonBlackRatio(), 1e-9);
        assertEquals(0, stats.maxR());
        assertEquals(64L * 64L, stats.samples());
    }

    @Test
    @DisplayName("🔖🔖 极稀疏非零 ⇒ allZero=false（这正是 h28 与「输出黑」的分界）")
    void sparseNonZeroIsNotAllZero() {
        // 🔖 这条是 h29 的实测形态：关掉压零项后 57,218 px「逐像素非 0」，
        // 而对照臂是「逐像素恰好 0」。两者必须被本类区分开，否则又是一次误判归因。
        byte[] buf = zeros(64, 64);
        put(buf, 64, 10, 10, 23, 19, 15);
        PixelStats.Stats stats = PixelStats.of(buf, 64, BPP, new PixelStats.Region(0, 0, 64, 64));
        assertFalse(stats.isAllZero(),
                "有一个非零像素就不能算「逐像素恰好 0」—— h29 的实测正是这种形态");
        assertEquals(23, stats.maxR());
        assertTrue(stats.nonBlackRatio() > 0.0 && stats.nonBlackRatio() < 0.01,
                "非黑占比应很小（只有 1 个像素非黑），实际 " + stats.nonBlackRatio());
    }

    @Test
    @DisplayName("🔖 黑阈值逐字等于 flicker_ratio.py 的 THR=8（含 ≤ 而非 <）")
    void blackThresholdIsEightInclusive() {
        byte[] buf = zeros(32, 32);
        put(buf, 32, 1, 1, 8, 8, 8);
        PixelStats.Stats at8 = PixelStats.of(buf, 32, BPP, new PixelStats.Region(0, 0, 32, 32));
        assertTrue(at8.isAllZero(), "三通道都恰好 = 8 必须记为黑（口径是「≤ 8」）");

        byte[] buf2 = zeros(32, 32);
        put(buf2, 32, 1, 1, 9, 0, 0);
        PixelStats.Stats at9 = PixelStats.of(buf2, 32, BPP, new PixelStats.Region(0, 0, 32, 32));
        assertFalse(at9.isAllZero(), "= 9 已超过阈值，必须记为非黑");
    }

    @Test
    @DisplayName("🔖 luma 系数与 p24_luma.py 一致（Rec.709）")
    void lumaUsesRec709Coefficients() {
        byte[] buf = zeros(16, 16);
        // 纯白：luma 应 = 255（三通道系数和为 1）
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                put(buf, 16, x, y, 255, 255, 255);
            }
        }
        PixelStats.Stats white = PixelStats.of(buf, 16, BPP, new PixelStats.Region(0, 0, 16, 16));
        assertEquals(255.0, white.meanLuma(), 0.5, "纯白像素的 luma 必须约 255");

        // 纯绿：luma = 0.7152 * 255
        byte[] green = zeros(16, 16);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                put(green, 16, x, y, 0, 255, 0);
            }
        }
        PixelStats.Stats stats = PixelStats.of(green, 16, BPP, new PixelStats.Region(0, 0, 16, 16));
        assertEquals(0.7152 * 255.0, stats.meanLuma(), 0.01);
    }

    @Test
    @DisplayName("🔖🔖 无样本 ≠ 全黑（h42 刚被自己推翻过一次结论的那类混淆）")
    void emptySampleIsNotAllBlack() {
        byte[] buf = zeros(64, 64);
        // 采样区整体在缓冲之外 ⇒ 采不到任何像素
        PixelStats.Stats stats = PixelStats.of(buf, 64, BPP, new PixelStats.Region(100, 100, 10, 10));
        assertEquals(0L, stats.samples(), "应当采不到样本");
        assertFalse(stats.isAllZero(),
                "「没采到样本」绝不能被读成「画面全黑」—— 两者在日志上长得几乎一样，"
                        + "所以 isAllZero() 对 samples==0 显式返回 false");
    }

    @Test
    @DisplayName("🔖 零尺寸 / 非法 bytesPerPixel 一律返回空统计，不抛")
    void degenerateInputsReturnEmpty() {
        assertEquals(0L, PixelStats.of(zeros(8, 8), 8, BPP, new PixelStats.Region(0, 0, 0, 0)).samples());
        assertEquals(0L, PixelStats.of(zeros(8, 8), 0, BPP, new PixelStats.Region(0, 0, 4, 4)).samples());
        // bytesPerPixel < 3：无法取 RGB 三通道 ⇒ 空统计（不猜通道顺序）
        assertEquals(0L, PixelStats.of(zeros(8, 8), 8, 2, new PixelStats.Region(0, 0, 4, 4)).samples());
    }

    @Test
    @DisplayName("🔖 采样区被夹在缓冲内（部分越界不应抛，也不应越界读）")
    void regionIsClampedToBuffer() {
        byte[] buf = zeros(16, 16);
        put(buf, 16, 15, 15, 200, 200, 200);
        PixelStats.Stats stats = PixelStats.of(buf, 16, BPP, new PixelStats.Region(10, 10, 20, 20));
        // 有效像素 = (10..15, 10..15) = 36，其中 1 个非黑
        assertEquals(36L, stats.samples());
        assertEquals(200, stats.maxR());
        assertFalse(stats.isAllZero());
    }

    @Test
    @DisplayName("🔖 format() 的字段顺序固定（跨会话读者按位置读数字）")
    void formatHasStableFieldOrder() {
        byte[] buf = zeros(8, 8);
        put(buf, 8, 3, 3, 10, 20, 30);
        String line = PixelStats.of(buf, 8, BPP, new PixelStats.Region(0, 0, 8, 8)).format("main");
        int regionAt = line.indexOf("region=");
        int samplesAt = line.indexOf("samples=");
        int lumaAt = line.indexOf("mean_luma=");
        int nonBlackAt = line.indexOf("nonBlack=");
        int maxRAt = line.indexOf("maxR=");
        int zeroAt = line.indexOf("allZero=");
        assertTrue(regionAt >= 0 && regionAt < samplesAt && samplesAt < lumaAt
                        && lumaAt < nonBlackAt && nonBlackAt < maxRAt && maxRAt < zeroAt,
                "字段顺序必须固定且可被按位置解析，实际输出：" + line);
        assertTrue(line.startsWith("main "), "label 必须在最前，便于 grep");
    }
}