import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.File;
import java.security.MessageDigest;

/**
 * 本机 Windows 桌面截图取证工具。
 * 用法: java WinShot.java <输出png路径>
 * 输出: 截图文件 + 分辨率 + sha256（打印到 stdout，便于落盘留证）
 */
public class WinShot {
    public static void main(String[] args) throws Exception {
        String out = (args.length > 0) ? args[0] : "shot.png";
        Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        BufferedImage img = new Robot().createScreenCapture(screen);
        File f = new File(out);
        if (f.getParentFile() != null) {
            f.getParentFile().mkdirs();
        }
        ImageIO.write(img, "png", f);

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(java.nio.file.Files.readAllBytes(f.toPath()));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }

        int nonBlack = 0;
        long sum = 0;
        for (int y = 0; y < img.getHeight(); y += 4) {
            for (int x = 0; x < img.getWidth(); x += 4) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
                int luma = (r * 299 + g * 587 + b * 114) / 1000;
                sum += luma;
                if (luma > 8) {
                    nonBlack++;
                }
            }
        }
        long sampled = (long) ((img.getWidth() / 4) + 1) * ((img.getHeight() / 4) + 1);

        System.out.println("PATH      = " + f.getAbsolutePath());
        System.out.println("SIZE      = " + img.getWidth() + "x" + img.getHeight());
        System.out.println("BYTES     = " + f.length());
        System.out.println("SHA256    = " + hex);
        System.out.println("MEAN_LUMA = " + String.format("%.2f", (double) sum / sampled));
        System.out.println("NONBLACK  = " + nonBlack + " / " + sampled
                + " (" + String.format("%.2f", 100.0 * nonBlack / sampled) + "%)");
    }
}
