package dev.vkdisp.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** GAP-053 的定序仪器：判定是纯函数，接线是两条文本针。 */
class GbufferPassOrderTest {

    private static final Path ORDER = Path.of("src/main/java/dev/vkdisp/bridge/GbufferPassOrder.java");
    private static final Path TAKEOVER = Path.of("src/main/java/dev/vkdisp/bridge/MainPassTakeover.java");
    private static final Path TERRAIN = Path.of("src/main/java/dev/vkdisp/bridge/MrtTerrainPass.java");

    private static String readOrSkip(Path path) {
        assumeTrue(Files.exists(path), () -> "源码不在（" + path + "）⇒ 只跑设备侧");
        try {
            return Files.readString(path);
        } catch (java.io.IOException e) {
            assumeTrue(false, "读不到 " + path + "：" + e.getMessage());
            return "";
        }
    }

    @Test
    @DisplayName("verdict 三态：单边占多数就说哪边先；没成对/各半都必须说「未知」，不许猜")
    void verdictIsThreeState() {
        assertTrue(GbufferPassOrder.verdict(0L, 0L).contains("未知"), "一次都没成对 ⇒ 只能是未知");
        assertTrue(GbufferPassOrder.verdict(120L, 3L).contains("主 pass 先"), "主→我方占多数 ⇒ 主 pass 先打开");
        assertTrue(GbufferPassOrder.verdict(120L, 3L).contains("候选①"), "结论必须点名它支持哪个修法（让出清屏权）");
        assertTrue(GbufferPassOrder.verdict(3L, 120L).contains("我方 gbuffer pass 先"), "反向占多数 ⇒ 我方先");
        assertTrue(GbufferPassOrder.verdict(3L, 120L).contains("候选③"), "这条点名的是代次归属，不是清屏");
        assertTrue(GbufferPassOrder.verdict(50L, 50L).contains("未知"), "各半 = 读数不可信，必须落回未知");
    }

    @Test
    @DisplayName("两个记数点必须真的接上：主 pass 那侧在「接管成功」之后，我方那侧在 pass 打开之后")
    void bothSidesAreWired() {
        String takeover = readOrSkip(TAKEOVER);
        assertTrue(takeover.contains("GbufferPassOrder.noteMainPass();"),
                "主 pass 那侧不记 ⇒ 这条仪器永远读「未知」，而「未知」会被误读成「注入没命中」");

        String terrain = readOrSkip(TERRAIN);
        int opened = terrain.indexOf("ownPass = renderPass;");
        assertTrue(opened >= 0 && terrain.indexOf("GbufferPassOrder.noteOwnGbufferPass();", opened) > opened,
                "我方那侧必须记在 pass **打开之后**（与主 pass 记的时刻同语义：createRenderPass 那一刻）");
    }

    @Test
    @DisplayName("计数规则本身：严格交替的一对里，必须先出现的那一侧被记成「先」——第一版按相邻方向数，在交替序列上恒为各半（真机读出「未知」）")
    void alternatingPairsCreditTheStarter() {
        long main0 = GbufferPassOrder.mainFirstCount();
        long own0 = GbufferPassOrder.ownFirstCount();
        for (int pair = 0; pair < 4; pair++) {
            GbufferPassOrder.noteMainPass();
            GbufferPassOrder.noteOwnGbufferPass();
        }
        assertEquals(main0 + 4L, GbufferPassOrder.mainFirstCount(), "主 pass 每对都先 ⇒ 四对都记它");
        assertEquals(own0, GbufferPassOrder.ownFirstCount(), "我方先一次都没发生 ⇒ 这一格不许动");

        long main1 = GbufferPassOrder.mainFirstCount();
        long own1 = GbufferPassOrder.ownFirstCount();
        for (int pair = 0; pair < 4; pair++) {
            GbufferPassOrder.noteOwnGbufferPass();
            GbufferPassOrder.noteMainPass();
        }
        assertEquals(main1, GbufferPassOrder.mainFirstCount(), "反过来 ⇒ 主 pass 那一格不许动");
        assertEquals(own1 + 4L, GbufferPassOrder.ownFirstCount(), "我方每对都先 ⇒ 四对都记它");
    }

    @Test
    @DisplayName("仪器不许读配置标志：判据只用调用本身的数据（口径同 ⑤-a）")
    void instrumentReadsNoConfig() {
        String order = readOrSkip(ORDER);
        assertFalse(order.contains("VkDispConfig"),
                "一旦按配置反推「谁该先」，这条仪器就变成它想回答的那个问题的复读机");
    }
}
