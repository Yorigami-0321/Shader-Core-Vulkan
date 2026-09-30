import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对截图做主色统计与红/绿主导像素分析，用于 vkdisp 渲染输出的像素级判据。
 * 用法: java ColorStats.java <png路径>
 */
public class ColorStats {
    public static void main(String[] args) throws Exception {
        BufferedImage img = ImageIO.read(new File(args[0]));
        int w = img.getWidth(), h = img.getHeight();

        Map<String, int[]> hist = new LinkedHashMap<>();
        long redN = 0, greenN = 0, otherN = 0;
        long redR = 0, redG = 0, redB = 0;
        long gLightN = 0, gLightSum = 0, gDarkN = 0, gDarkSum = 0;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;

                String key = String.format("r%02x_g%02x_b%02x", (r >> 4) << 4, (g >> 4) << 4, (b >> 4) << 4);
                hist.computeIfAbsent(key, k -> new int[1])[0]++;

                if (r > 60 && r > g * 2 && r > b * 2) {
                    redN++;
                    redR += r; redG += g; redB += b;
                } else if (g > 60 && g > r * 2 && g > b * 2) {
                    greenN++;
                    if (g >= 120) { gLightN++; gLightSum += g; }
                    else { gDarkN++; gDarkSum += g; }
                } else {
                    otherN++;
                }
            }
        }

        long total = (long) w * h;
        System.out.println("SIZE = " + w + "x" + h + "  total=" + total);

        System.out.println("\n--- 主色 TOP 12（量化 16 级）---");
        hist.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
                .limit(12)
                .forEach(e -> System.out.printf("  %-16s %8d  %6.2f%%%n",
                        e.getKey(), e.getValue()[0], 100.0 * e.getValue()[0] / total));

        System.out.println("\n--- 主导色像素分类 ---");
        System.out.printf("  红主导 R>60 且 R>2G,2B : %6d (%5.2f%%)%n", redN, 100.0 * redN / total);
        if (redN > 0) {
            System.out.printf("    平均 RGB = (%.1f, %.1f, %.1f)   R/G=%.4f%n",
                    (double) redR / redN, (double) redG / redN, (double) redB / redN,
                    (double) redR / Math.max(1, redG));
        }
        System.out.printf("  绿主导 G>60 且 G>2R,2B : %6d (%5.2f%%)%n", greenN, 100.0 * greenN / total);
        if (greenN > 0) {
            System.out.printf("    亮绿 G>=120 : %6d  平均G=%.1f%n", gLightN, gLightN > 0 ? (double) gLightSum / gLightN : 0.0);
            System.out.printf("    暗绿 G< 120 : %6d  平均G=%.1f%n", gDarkN, gDarkN > 0 ? (double) gDarkSum / gDarkN : 0.0);
        }
        System.out.printf("  其它（背景/中性）      : %6d (%5.2f%%)%n", otherN, 100.0 * otherN / total);
    }
}
