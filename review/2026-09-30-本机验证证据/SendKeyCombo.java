import java.awt.Robot;
import java.awt.event.KeyEvent;

/**
 * 通用按键发送工具，用于把游戏窗口带到前台。
 * 用法: java SendKeyCombo.java <动作序列> [截图输出png]
 * 动作序列用逗号分隔，支持: WIN+DOWN / WIN+UP / WIN+TAB / ALT+ESC / ALT+TAB / F2 / ESC / ENTER
 * 末尾自动等待后截图（如给出路径）。
 */
public class SendKeyCombo {
    public static void main(String[] args) throws Exception {
        String seq = args.length > 0 ? args[0] : "";
        String shot = args.length > 1 ? args[1] : null;

        Robot robot = new Robot();
        robot.setAutoDelay(50);

        for (String act : seq.split(",")) {
            act = act.trim().toUpperCase();
            if (act.isEmpty()) {
                continue;
            }
            String[] parts = act.split("\\+");
            int[] keys = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                keys[i] = keyOf(parts[i]);
            }
            for (int k : keys) {
                robot.keyPress(k);
            }
            Thread.sleep(90);
            for (int i = keys.length - 1; i >= 0; i--) {
                robot.keyRelease(keys[i]);
            }
            Thread.sleep(1200);
        }

        Thread.sleep(2500);

        if (shot != null) {
            java.awt.Rectangle screen = new java.awt.Rectangle(
                    java.awt.Toolkit.getDefaultToolkit().getScreenSize());
            java.awt.image.BufferedImage img = robot.createScreenCapture(screen);
            java.io.File f = new java.io.File(shot);
            if (f.getParentFile() != null) {
                f.getParentFile().mkdirs();
            }
            javax.imageio.ImageIO.write(img, "png", f);

            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(java.nio.file.Files.readAllBytes(f.toPath()));
            StringBuilder hex = new StringBuilder();
            for (byte b : d) {
                hex.append(String.format("%02x", b));
            }
            long sum = 0;
            int dark = 0;
            int nonBlack = 0;
            for (int y = 0; y < img.getHeight(); y += 4) {
                for (int x = 0; x < img.getWidth(); x += 4) {
                    int rgb = img.getRGB(x, y);
                    int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
                    int luma = (r * 299 + g * 587 + b * 114) / 1000;
                    sum += luma;
                    if (luma > 8) {
                        nonBlack++;
                    }
                    if (luma < 100) {
                        dark++;
                    }
                }
            }
            long sampled = (long) ((img.getWidth() / 4) + 1) * ((img.getHeight() / 4) + 1);
            System.out.println("PATH      = " + f.getAbsolutePath());
            System.out.println("SHA256    = " + hex);
            System.out.println("MEAN_LUMA = " + String.format("%.2f", (double) sum / sampled));
            System.out.println("NONBLACK  = " + nonBlack + " (" + String.format("%.2f", 100.0 * nonBlack / sampled) + "%)");
            System.out.println("DARK<100  = " + dark + " (" + String.format("%.2f", 100.0 * dark / sampled) + "%)");
        } else {
            System.out.println("DONE seq=" + seq);
        }
    }

    private static int keyOf(String name) {
        switch (name) {
            case "WIN":
            case "WINDOWS":
                return KeyEvent.VK_WINDOWS;
            case "ALT":
                return KeyEvent.VK_ALT;
            case "CTRL":
                return KeyEvent.VK_CONTROL;
            case "SHIFT":
                return KeyEvent.VK_SHIFT;
            case "DOWN":
                return KeyEvent.VK_DOWN;
            case "UP":
                return KeyEvent.VK_UP;
            case "LEFT":
                return KeyEvent.VK_LEFT;
            case "RIGHT":
                return KeyEvent.VK_RIGHT;
            case "TAB":
                return KeyEvent.VK_TAB;
            case "ESC":
            case "ESCAPE":
                return KeyEvent.VK_ESCAPE;
            case "ENTER":
                return KeyEvent.VK_ENTER;
            case "F2":
                return KeyEvent.VK_F2;
            case "F11":
                return KeyEvent.VK_F11;
            default:
                throw new IllegalArgumentException("unknown key: " + name);
        }
    }
}
