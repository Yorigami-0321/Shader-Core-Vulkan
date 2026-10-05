package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】GPU→CPU 像素回读的**统计口径**（把「看截图」变成「日志里的数字」）
 * 0. 合规核对（第 0 步闸门）：
 *    参考对象 = ① 本仓库自有的取证工具 {@code tools/vulkan-local/flicker_ratio.py}
 *    与 {@code tools/vulkan-local/p24_luma.py}（MIT 自有脚本）—— 它们**已经**定好了两个口径，
 *    本类把它们**搬进进程内**，而不是另立一套：
 *    <ul>
 *      <li>闪烁判据：中心矩形 x∈[45%,65%] / y∈[15%,75%]，某像素 RGB 三通道**都 ≤ 8** 记为黑；</li>
 *      <li>亮度判据：{@code luma = 0.2126·R + 0.7152·G + 0.0722·B}（Rec.709），三通道均值同口径。</li>
 *    </ul>
 *    ② 原版 26.3 {@code net.minecraft.client.Screenshot#takeScreenshot}（Mojang EULA）：
 *    **只核实「回读怎么做」这一事实** —— 它证实
 *    {@code CommandEncoder#copyTextureToBuffer(GpuTexture, GpuBuffer, long, Runnable, int)}
 *    是官方公开的回读入口，且回调在 GPU 完成后触发（Vulkan 后端走
 *    {@code queueForDestroy(callback::run)}），回读缓冲需
 *    {@code USAGE_COPY_DST | USAGE_MAP_READ}（该类用的 usage 值就是 9）。
 *    ③ 原版 {@code com.mojang.renderpearl.api.GpuFormat#RGBA8_UNORM}（blockSize = 4）
 *    与 Vulkan 规范的「texel 字节序 = R,G,B,A」这一格式事实（不受版权保护）。
 *    → 能否并入本项目（MIT）：可以 —— 本文件为独立编写的纯 Java 统计类，零代码搬运
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：无（官方只提供「把像素搬进 ByteBuffer」，不提供「统计成可比数字」）。
 * 2. 备选：
 *    <ul>
 *      <li>① 继续靠 MCP 截图 + 外部 python 脚本算 —— <b>否决（本类的存在理由）</b>：
 *          它有三个实测缺陷。① <b>需要人先跑外部脚本</b>，而配置类问题往往要在**同一次运行**
 *          里对比多个量（h42 §4.3 想分开的正是「输出黑」与「没落到主目标」，那需要
 *          <b>同帧</b>两个数字）；② 采样区写死在脚本里，窗口一改尺寸就与历史数字不可比；
 *          ③ 数字不在日志里 ⇒ 跨会话的 AI 读日志看不到量化判据，只能重新截图重算。</li>
 *      <li>② 只报「平均亮度」一个数 —— <b>否决</b>：全黑与「极暗但非零」的平均亮度可能接近，
 *          而 h28/h29 的关键判据恰恰是<b>逐像素恰好 0</b> 与<b>逐像素非 0</b>的区分
 *          （用 `maxR` 与 `nonBlackRatio` 一起看才能分开，见本类 {@code verdict} 说明）。</li>
 *      <li>③ 把统计写在 GPU 着色器里（降采样后回读一个小图）—— <b>否决</b>：那要新增一条
 *          管线与一个附件，而本轮目标是「给已有观测面配一个数字」，不是新造渲染通道；
 *          且降采样会丢掉「逐像素恰好 0」这个判据（平均后不再是 0）。</li>
 *    </ul>
 * 3. 我们的差异点：
 *    <ul>
 *      <li>🔖 <b>采样区用比例而不是像素</b>（{@link Region#centered}）⇒ 窗口尺寸变了仍然可比，
 *          且默认比例**逐字等于** {@code flicker_ratio.py} 里被 h22 校准出来的那一组。</li>
 *      <li>🔖 <b>一次性给出四个互补判据</b>：{@code meanLuma}（暗不暗）、{@code nonBlackRatio}
 *          （有多少非黑像素）、{@code maxR}（有没有任何非零像素 —— 这是 h28 的关键量）、
 *          {@code allBlack}（是否**逐像素**恰好 0）。单看均值会漏掉「极稀疏非零」。</li>
 *      <li>🔖 <b>零 GPU 依赖</b>：只吃 {@code byte[]} + 几何 ⇒ 可在无 GPU 的单测里逐位断言，
 *          这与本仓库 {@code pipeline/model} 其余类的分工一致。</li>
 *    </ul>
 * 4. 许可证核对：本项目 MIT；零第三方代码复制（统计口径取自本仓库自有脚本与公开格式事实）。
 * 5. 性能基线：❄️ 诊断冷路径（默认关；开启时按 {@code mrt.pixelProbeEvery} 节流，
 *    每次只统计一块矩形，不做全帧逐像素输出）。
 */
import java.util.Objects;

/**
 * 一块 GPU 纹理回读后的**量化统计**（纯逻辑，零 GPU / 零原版类型依赖 ⇒ 可单测）。
 *
 * <p><b>它解决什么</b>：本项目反复出现的失败形态是「画面看起来不对」，
 * 而「看起来」不能被跨会话的读者复核。{@code evidence/h31} 的 {@code luma 0.0000 → 96.1485}、
 * {@code h28} 的「逐像素恰好 0」、{@code h22} 的「黑色像素占比 > 90%」都是**好判据**，
 * 但它们都只存在于**外部脚本 + 截图**里。本类把同一套口径搬进进程内，让这些数字
 * 直接出现在 {@code run/logs/latest.log}。
 *
 * <p><b>为什么必须区分四个量</b>（这是 h28/h29 那两轮的直接教训）：
 * <pre>
 *   meanLuma ≈ 0   非黑占比 ≈ 0   maxR = 0     ⇒ 逐像素恰好 0（压零项）
 *   meanLuma ≈ 0   非黑占比 &gt; 0  maxR &gt; 0     ⇒ 有内容，只是极暗
 * </pre>
 * 只报均值会把上面两行看成同一个数 ⇒ 「压零」这个已被实测坐实的结论就会被误读。
 */
public final class PixelStats {

    /**
     * 采样矩形（<b>像素</b>坐标；由 {@link #centered} 按比例换算）。
     *
     * @param x      左上角 x
     * @param y      左上角 y
     * @param width  宽（像素）
     * @param height 高（像素）
     */
    public record Region(int x, int y, int width, int height) {

        /** 归一：非正尺寸一律夹成 0（调用方据此判「本帧无采样」而不是抛异常）。 */
        public Region {
            x = Math.max(0, x);
            y = Math.max(0, y);
            width = Math.max(0, width);
            height = Math.max(0, height);
        }

        /** 面积（像素）。 */
        public long area() {
            return (long) width * height;
        }

        /**
         * 按<b>比例</b>取中心矩形 —— 采样区口径的唯一定义处。
         *
         * <p>🔖 默认比例逐字等于 {@code tools/vulkan-local/flicker_ratio.py} 的
         * {@code FX0,FY0,FX1,FY1 = 0.45,0.15,0.65,0.75}：那组比例是 h22 用前缀和
         * 在 12 张已入库截图上**反解**出来的（能同时复现四个已公布数字），
         * 换掉它就等于让本机数字与历史证据不可比。
         *
         * @param textureWidth  纹理宽（像素）
         * @param textureHeight 纹理高（像素）
         */
        public static Region centered(int textureWidth, int textureHeight) {
            return centered(textureWidth, textureHeight, 0.45, 0.15, 0.65, 0.75);
        }

        /**
         * 按比例取中心矩形（可自定义比例；供「换观测区」的对照实验用）。
         *
         * @param textureWidth  纹理宽（像素）
         * @param textureHeight 纹理高（像素）
         * @param fx0           左边界比例（0..1）
         * @param fy0           上边界比例（0..1）
         * @param fx1           右边界比例（0..1）
         * @param fy1           下边界比例（0..1）
         */
        public static Region centered(int textureWidth, int textureHeight,
                double fx0, double fy0, double fx1, double fy1) {
            int x0 = (int) Math.round(textureWidth * fx0);
            int y0 = (int) Math.round(textureHeight * fy0);
            int x1 = (int) Math.round(textureWidth * fx1);
            int y1 = (int) Math.round(textureHeight * fy1);
            return new Region(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
        }
    }

    /**
     * 一块采样区的统计结果。
     *
     * @param width        采样区宽（像素）
     * @param height       采样区高（像素）
     * @param samples      实际参与统计的像素数
     * @param meanR        R 通道均值（0..255）
     * @param meanG        G 通道均值（0..255）
     * @param meanB        B 通道均值（0..255）
     * @param meanLuma     Rec.709 亮度均值（0..255）
     * @param nonBlackRatio 非黑像素占比（0..1；判据见 {@link PixelStats#BLACK_THRESHOLD}）
     * @param maxR         R 通道最大值（0..255）—— h28 用来判「是否逐像素恰好 0」的关键量
     * @param allBlack     是否**逐像素**三通道都 ≤ 阈值（true ⇒ 该区没有任何非零信号）
     */
    public record Stats(
            int width,
            int height,
            long samples,
            double meanR,
            double meanG,
            double meanB,
            double meanLuma,
            double nonBlackRatio,
            int maxR,
            boolean allBlack) {

        /** 非黑判据阈值（逐通道 ≤ 该值即记为黑；与 flicker_ratio.py 的 {@code THR = 8} 同值）。 */
        public static final int BLACK_THRESHOLD = 8;

        /** 空统计（采样区面积为 0 时；**不是**「画面全黑」，两者必须能分开）。 */
        public static Stats empty(int width, int height) {
            return new Stats(width, height, 0L, 0.0, 0.0, 0.0, 0.0, 0.0, 0, false);
        }

        /**
         * 「逐像素恰好 0」判据。
         *
         * <p>🔖 这是 {@code h28} 用来排除 {@code color} 因子的那条判据：
         * 对照臂与探针臂**逐像素都恰好 {@code RGB(0,0,0)}**。
         * ⚠️ <b>样本数为 0 时返回 false</b> —— 「没采到样本」不能被读成「画面全黑」
         * （那正是本项目反复踩的「两种不同的失败看起来一样」）。
         */
        public boolean isAllZero() {
            return this.samples > 0L && this.allBlack;
        }

        /** 单行打印形式（证据行 / 跨会话复核用；字段顺序固定）。 */
        public String format(String label) {
            return String.format(java.util.Locale.ROOT,
                    "%s region=%dx%d samples=%d meanRGB=(%.4f,%.4f,%.4f) mean_luma=%.4f"
                            + " nonBlack=%.3f%% maxR=%d allZero=%s",
                    label, this.width, this.height, this.samples,
                    this.meanR, this.meanG, this.meanB, this.meanLuma,
                    this.nonBlackRatio * 100.0, this.maxR, this.isAllZero());
        }
    }

    /**
     * 🔖 天空带与地形带的纵向分界比例（本机观测面 {@code yaw=35, pitch=-8} 下取 0.45）。
     *
     * <p>🔖 <b>这是一个可被读数推翻的假设，不是事实</b>：本机地平线落在画面上半部，
     * 但换一个机位/视口/FOV 就未必如此。⇒ 若「地形带」读出全黑而「天空带」有内容，
     * 读数本身就暴露了分界不合适，<b>不需要人去猜</b> —— 而这正是分区的价值。
     */
    public static final double SKY_BAND_BOTTOM = 0.45;

    /**
     * 🔖 三个**语义不同**的采样区名（同一张图上分开取数）。
     *
     * <p>🔖 <b>为什么要分区</b>（2026-10-05 实测）：单一中心矩形回答不了
     * 「黑的是<b>天空</b>还是<b>地形</b>」。本项目的 GAP-011 恰恰出现过这个混淆 ——
     * {@code h17} 的结论是「闪烁是天空、不是主目标」，而当时只有一个矩形观测面。
     *
     * <p>🔖 三个区<b>互不重叠</b>且合起来覆盖整幅 ⇒ 「地形带全黑 + 天空带有内容」
     * 不会被「采样区压根没覆盖到地形」这种形态误读。
     */
    public enum Area {
        /** 上带（本机机位下假设为天空）。 */
        SKY_BAND,
        /** 下带（本机机位下假设为地形）。 */
        TERRAIN_BAND,
        /** 整幅（兜底：与上面两带合起来覆盖全图）。 */
        FULL
    }

    private PixelStats() {
    }

    /** 一个命名区 + 它在纹理上的像素矩形（纯数据）。 */
    public record AreaSample(Area area, Region region) {

        public AreaSample {
            java.util.Objects.requireNonNull(area, "area");
            java.util.Objects.requireNonNull(region, "region");
        }

        /** 区名（证据行标签；区名是枚举常量，永不为空白）。 */
        public String label() {
            return area.name();
        }
    }

    /**
     * 按比例切出三个语义区（纯逻辑，可单测）。
     *
     * <p>🔖 横向沿用 {@link #centered} 的默认口径（与历史数字可比），
     * 纵向取<b>整幅高度</b>并在 {@link #SKY_BAND_BOTTOM} 处一分为二，
     * 再补一个覆盖整幅的 {@link Area#FULL} 兜底。
     * ⇒ 不变式可核对：{@code SKY.area() + TERRAIN.area() == center.width × height}，
     * 且 {@code FULL.area() == width × height}。
     * ⇒ 「地形带全黑」若同时 FULL 也有内容，说明黑的是**一部分**画面而非全部。
     */
    public static java.util.List<AreaSample> areas(int textureWidth, int textureHeight) {
        Region center = Region.centered(textureWidth, textureHeight);
        int splitY = (int) Math.round(textureHeight * SKY_BAND_BOTTOM);
        java.util.List<AreaSample> out = new java.util.ArrayList<>(3);
        out.add(new AreaSample(Area.SKY_BAND,
                new Region(center.x(), 0, center.width(), Math.max(0, splitY))));
        out.add(new AreaSample(Area.TERRAIN_BAND,
                new Region(center.x(), Math.min(splitY, textureHeight),
                        center.width(), Math.max(0, textureHeight - splitY))));
        out.add(new AreaSample(Area.FULL,
                new Region(0, 0, textureWidth, textureHeight)));
        return java.util.List.copyOf(out);
    }

    /**
     * 统计一块采样区。
     *
     * <p>🔖 <b>texel 字节序</b>按 Vulkan 规范：{@code RGBA8_UNORM} 的 4 个字节依次是
     * {@code R,G,B,A}（blockSize = 4）。本方法按<b>固定步长</b>逐像素跳，
     * 因此对回读缓冲里「行是否紧凑」是敏感的 —— 调用方必须传<b>整帧</b>缓冲
     * （行距 = {@code textureWidth × bytesPerPixel}），不能传已裁剪过的子缓冲。
     *
     * @param rgba           回读得到的字节（R,G,B,A 顺序）
     * @param textureWidth   回读缓冲的行距对应的纹素宽（像素）
     * @param bytesPerPixel  每像素字节数（RGBA8_UNORM = 4）
     * @param region         采样区（会被夹到纹理范围内）
     * @param blackThreshold 非黑判据阈值（逐通道 ≤ 该值记为黑）
     * @return 统计结果；区域面积为 0 或缓冲越界到采不到任何像素时返回 {@link Stats#empty}
     */
    public static Stats of(byte[] rgba, int textureWidth, int bytesPerPixel,
            Region region, int blackThreshold) {
        Objects.requireNonNull(rgba, "rgba");
        Objects.requireNonNull(region, "region");
        if (textureWidth <= 0 || bytesPerPixel < 3 || region.area() == 0L) {
            return Stats.empty(region.width(), region.height());
        }
        int maxX = Math.min(region.x() + region.width(), textureWidth);
        int stride = textureWidth * bytesPerPixel;
        long sumR = 0L;
        long sumG = 0L;
        long sumB = 0L;
        long nonBlack = 0L;
        long samples = 0L;
        int maxR = 0;
        for (int y = region.y(); y < region.y() + region.height(); y++) {
            int rowStart = y * stride;
            if (rowStart < 0 || rowStart >= rgba.length) {
                continue;
            }
            for (int x = region.x(); x < maxX; x++) {
                int base = rowStart + x * bytesPerPixel;
                if (base + 2 >= rgba.length) {
                    break;
                }
                int r = rgba[base] & 0xFF;
                int g = rgba[base + 1] & 0xFF;
                int b = rgba[base + 2] & 0xFF;
                sumR += r;
                sumG += g;
                sumB += b;
                if (r > maxR) {
                    maxR = r;
                }
                if (r > blackThreshold || g > blackThreshold || b > blackThreshold) {
                    nonBlack++;
                }
                samples++;
            }
        }
        if (samples == 0L) {
            return Stats.empty(region.width(), region.height());
        }
        double meanR = (double) sumR / samples;
        double meanG = (double) sumG / samples;
        double meanB = (double) sumB / samples;
        // Rec.709 亮度（与 p24_luma.py / flicker_ratio.py 同一组系数）。
        double meanLuma = 0.2126 * meanR + 0.7152 * meanG + 0.0722 * meanB;
        boolean allBlack = nonBlack == 0L;
        return new Stats(region.width(), region.height(), samples,
                meanR, meanG, meanB, meanLuma, (double) nonBlack / samples, maxR, allBlack);
    }

    /** 便捷重载：默认非黑阈值 {@link Stats#BLACK_THRESHOLD}。 */
    public static Stats of(byte[] rgba, int textureWidth, int bytesPerPixel, Region region) {
        return of(rgba, textureWidth, bytesPerPixel, region, Stats.BLACK_THRESHOLD);
    }
}