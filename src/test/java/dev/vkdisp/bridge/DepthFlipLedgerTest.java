package dev.vkdisp.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** GAP-037「天空深度只被翻一次」的判据仪器：判定是纯函数，接线是文本针。 */
class DepthFlipLedgerTest {

    private static final Path LEDGER = Path.of("src/main/java/dev/vkdisp/bridge/DepthFlipLedger.java");
    private static final Path PROXY = Path.of("src/main/java/dev/vkdisp/bridge/DepthGlProxy.java");
    private static final Path SKY = Path.of("src/main/java/dev/vkdisp/bridge/SkyPackGbuffer.java");

    private static String readOrSkip(Path path) {
        assumeTrue(Files.exists(path), () -> "源码不在（" + path + "）⇒ 只跑行为侧");
        try {
            return Files.readString(path);
        } catch (java.io.IOException e) {
            assumeTrue(false, "读不到 " + path + "：" + e.getMessage());
            return "";
        }
    }

    @Test
    @DisplayName("verdict 三态：1 次才打勾；0 次与 >1 次都必须报红并点名形状")
    void verdictIsThreeState() {
        assertTrue(DepthFlipLedger.verdict(1).contains("只翻一次 ✓"), "判据成立那一格");
        assertTrue(DepthFlipLedger.verdict(0).contains("一次都没翻"),
                "0 次不是「通过」—— 天空深度停在引擎口径，包按 z==1.0 认天空的分支会判否");
        assertTrue(DepthFlipLedger.verdict(2).contains("翻了 2 次"), "GAP-037 的实测形状");
        assertTrue(DepthFlipLedger.verdict(2).contains("GAP-037"), "报红必须点名是哪条账");
    }

    @Test
    @DisplayName("🔴 两个所有者都必须真的被记上：漏一个，这条仪器就永远读「1 次 ✓」")
    void bothOwnersAreWired() {
        String proxy = readOrSkip(PROXY);
        assertTrue(proxy.contains("DepthFlipLedger.beginFrame();"),
                "帧首不结算 ⇒ 计数只涨不报，判据读不到（记在翻转 pass 自己头上：它每帧恰好一次）");
        assertTrue(proxy.contains("DepthFlipLedger.note(\"DepthGlProxy.renderFlipPass(全屏 1−z)\")"),
                "代理那趟全屏翻转是所有者之一");
        String sky = readOrSkip(SKY);
        assertTrue(sky.contains("DepthFlipLedger.note(\"SkyPackGbuffer 顶点常量 Position.z=1.0\")"),
                "🔴 另一个所有者是**顶点常量** —— 漏了它，开代理那一臂会假报「只翻一次 ✓」");
        int draw = sky.indexOf("pass.draw(3, 1, 0, 0);");
        int note = sky.indexOf("DepthFlipLedger.note(", draw - sky.indexOf("pass.draw(3, 1, 0, 0);"));
        assertTrue(draw > 0, "全屏三角那一笔 draw 要在（找不到针会假绿）");
        assertTrue(note > 0 && note < draw, "记点必须就在 draw **之前**（同一次动作，不是别处）");
    }

    @Test
    @DisplayName("仪器不许参与渲染决定，也不许读配置（口径同 GbufferPassOrder）")
    void instrumentDrivesNothing() {
        String ledger = readOrSkip(LEDGER);
        assertFalse(ledger.contains("VkDispConfig"),
                "一旦按开关反推「该翻几次」，仪器就成了它想回答的那个问题的复读机");
        assertFalse(ledger.contains("return verdict("),
                "verdict 只给读数用；谁都不许拿它做分支");
        assertEquals(-1, ledger.indexOf("if (verdict"), "同上（形状检查）");
    }
}
