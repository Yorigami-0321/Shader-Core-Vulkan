package dev.vkdisp.bridge;
/**
 * 【自行补充】R7 ⑤-d 前置（GAP-049 池尺寸锚点）的守卫 / 纯函数真值表 + 只读工程自身文件
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓库 {@code bridge/GbufferSizeAnchor.java}、
 *    {@code bridge/MainPassTakeover.java}、{@code bridge/MrtTerrainPass.java} 与
 *    {@code docs/13-GAP-REGISTRY.md} 的 {@code GAP-049}（本项目自有文档，MIT）。
 *    → 可并入本项目（MIT）：本文件不引用任何外部代码。
 * 1. 官方/主实现：无。
 * 2. 备选：把 {@code RenderTarget} / {@code GpuTextureView} 拉进单测真建一张图 —— <b>否决</b>：
 *    测试运行时没有 MC 与 renderpearl（{@code GAP-050} 那一族，第三次撞到就是上一轮）。
 *    ⇒ 判定本体是纯 int 函数，能真跑；「什么时候被调、调了会不会改行为」是形状约束 ⇒ 文本钉住。
 * 3. 我们的差异点：这条判据的<b>反面</b>也要钉 —— 「核对不许顺手改成修正」（换池尺寸 = 重建整条池，
 *    那是 ⑤-d 的活，混进来就分不开归因）。
 * 4. 许可证核对：本项目 MIT。
 * 5. 性能基线：❄️ 单测。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code GAP-049} 的处置：池尺寸锚点核对（同尺寸 / 漂移 / 未知三态）+ 它不许越界做的事。
 */
class GbufferSizeAnchorTest {

    private static final Path ANCHOR =
            Path.of("src/main/java/dev/vkdisp/bridge/GbufferSizeAnchor.java");
    private static final Path TAKEOVER =
            Path.of("src/main/java/dev/vkdisp/bridge/MainPassTakeover.java");
    private static final Path MRT =
            Path.of("src/main/java/dev/vkdisp/bridge/MrtTerrainPass.java");

    private static String readOrSkip(Path path) {
        Assumptions.assumeTrue(Files.exists(path), "工程文件缺失（不在工程根目录运行？）: " + path);
        try {
            return Files.readString(path);
        } catch (java.io.IOException e) {
            throw new AssertionError("读取失败: " + path, e);
        }
    }

    @Test
    @DisplayName("核对是三态的：同尺寸=null、尺寸不同=点名两个数、还没看过=点名「不知道」")
    void mismatchCoversThreeStates() {
        assertNull(GbufferSizeAnchor.mismatch(854, 480, 854, 480), "今天实机就是这个形状（两张主目标恰好同尺寸）");
        String drift = GbufferSizeAnchor.mismatch(854, 480, 427, 240);
        assertNotNull(drift, "尺寸不同必须给出原因，不许只回 boolean");
        assertTrue(drift.contains("854x480") && drift.contains("427x240"),
                "原因串要点名两边的数，否则日志里看不出漂了多少：" + drift);
        String unknown = GbufferSizeAnchor.mismatch(854, 480, 0, 0);
        assertNotNull(unknown, "「还没看过主 pass」不是「一致」—— 建池早于第一次看到主 pass（时序铁律）");
        assertTrue(unknown.contains("不知道"), "未知态要说成未知，别伪装成通过：" + unknown);
    }

    @Test
    @DisplayName("核对挂在建池那一步之后，且只在主 pass 看过之后才可能判同/判漂")
    void checkRunsAtPoolBuildTime() {
        String mrt = readOrSkip(MRT);
        int ensure = mrt.indexOf("POOL.ensure(pool, main.width, main.height");
        int check = mrt.indexOf("GbufferSizeAnchor.checkPoolSize(main.width, main.height)");
        assertTrue(ensure > 0 && check > ensure && check - ensure < 700,
                "🔴 核对必须在 POOL.ensure <b>之后</b> —— 之前池还是旧尺寸，比出来的是假漂移");

        String takeover = readOrSkip(TAKEOVER);
        int record = takeover.indexOf("STATS.mainWidth = target0.getWidth(0)");
        int refuse = takeover.indexOf("STATS.refusedSize++");
        assertTrue(record > 0 && refuse > 0 && record < refuse,
                "尺寸要在「拒绝接管」<b>之前</b>就记下：尺寸不合本身就是 GAP-049 要看的信息，"
                        + "不能只在接管成功时看得见");
    }

    @Test
    @DisplayName("核对只许响，不许顺手改：不重建池、不 resize、不静默")
    void checkReportsButNeverRepairs() {
        String anchor = readOrSkip(ANCHOR);
        assertTrue(anchor.contains("STATS.drift++"), "漂移要计数");
        assertTrue(anchor.contains("LOGGER.warn"), "漂移必须 WARN —— 它是 ⑤-d 的红灯，不是可忽略的噪声");
        assertTrue(anchor.contains("STATS.reportedDrift"), "点名一次就够（节流纪律，同 M-01/h34 那一课）");
        assertEquals(-1, anchor.indexOf("resize("),
                "这里不许换池尺寸：重建整条池（mip 链 + 深度）是 ⑤-d 的活，混进来就分不开归因");
        assertEquals(-1, anchor.indexOf("POOL.ensure"), "核对类不碰资源，只读两个数");
    }

    @Test
    @DisplayName("同尺寸也要点名一次：它是巧合，不是约束（⑤-d 之后这张是唯一画布）")
    void agreementIsAlsoNamedOnce() {
        String anchor = readOrSkip(ANCHOR);
        assertTrue(anchor.contains("STATS.agree++") && anchor.contains("reportedAgree"),
                "通过也要有读数 —— 只有「没消息」时，「日志里没有这行」会被读成「没跑」");
        assertTrue(anchor.contains("巧合而不是约束"),
                "口径要写清：今天不出错不等于锚点是对的（GAP-049 的原话是「靠同尺寸侥幸对齐」）");
        assertEquals(-1, anchor.indexOf("LOGGER.info(\"vkdisp: [GAP-049] ok"),
                "不许把通过写成无信息的 ok 行");
    }
}
