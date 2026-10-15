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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        // 🔖 1.2（GAP-049 那一格）之后，建池与核对用的都是 anchor.width()/height()
        //    —— 主 pass 的 target0 已知时按它建，未知时按另一张主目标回落。
        int ensure = mrt.indexOf("POOL.ensure(pool, anchor.width(), anchor.height()");
        int check = mrt.indexOf("GbufferSizeAnchor.checkPoolSize(anchor.width(), anchor.height())");
        assertTrue(ensure > 0 && check > ensure && check - ensure < 900,
                "🔴 核对必须在 POOL.ensure <b>之后</b> —— 之前池还是旧尺寸，比出来的是假漂移");

        String takeover = readOrSkip(TAKEOVER);
        // 🔖 1.2 之后记录点搬进 recordMainPassTargetSize，且调用点必须在**两道门之前**：
        //    ① ownTerrainPass 开关（否则双绘档拿不到这个数）② refusedSize++（尺寸不合也是信息）
        int record = takeover.indexOf("recordMainPassTargetSize(descriptor);");
        int gate = takeover.indexOf("if (!VkDispConfig.MIXIN_OWN_TERRAIN_PASS.get())");
        int refuse = takeover.indexOf("STATS.refusedSize++");
        assertTrue(record > 0 && gate > 0 && refuse > 0, "三个锚点必须都在：" + record + "/" + gate + "/" + refuse);
        assertTrue(record < gate,
                "🔴 1.2：观察必须挂在 ownTerrainPass 开关<b>之前</b> —— 挂在后面，双绘档（取证档与"
                        + "代码默认档都是）就永远看不见主 pass 的 target0 尺寸，池的锚点白换");
        assertTrue(record < refuse,
                "尺寸要在「拒绝接管」之前就记下：尺寸不合本身就是 GAP-049 要看的信息，"
                        + "不能只在接管成功时看得见");
        assertTrue(takeover.contains("private static void recordMainPassTargetSize"),
                "记录本体必须单独成方法（attachmentReplaced 压在 QD-04 的 60 行棘轮以内）");
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
    // ────────────────────────────────────────────────────────────────────────────
    // 1.2（GAP-049 那一格的正解）：建池尺寸的锚点
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 1.2 看到主 pass 的 target0 ⇒ 池按它建，另一张主目标只作回落")
    void choosePrefersMainPassTarget() {
        GbufferSizeAnchor.Anchor a = GbufferSizeAnchor.choose(854, 480, 1280, 720);
        assertEquals(1280, a.width(), "主 pass 的 target0 已知 ⇒ 宽必须跟它");
        assertEquals(720, a.height(), "主 pass 的 target0 已知 ⇒ 高必须跟它");
        assertTrue(a.fromMainPass(), "锚点来源要能被日志与单测区分（回落 vs 主 pass）");
    }

    @Test
    @DisplayName("🔴 1.2 还没看过 target0（首帧之前）⇒ 回落，且不许回落成 0 尺寸")
    void chooseFallsBackBeforeFirstDescriptor() {
        GbufferSizeAnchor.Anchor a = GbufferSizeAnchor.choose(854, 480, 0, 0);
        assertEquals(854, a.width());
        assertEquals(480, a.height());
        assertFalse(a.fromMainPass(), "这一刻只能按另一张主目标建 —— 这正是 GAP-045 那条时序铁律的形状");
    }

    @Test
    @DisplayName("🔴 1.2 target0 只认到一维 ⇒ 保守走回落（不许拿混合尺寸建池）")
    void chooseRejectsHalfKnownTarget0() {
        assertFalse(GbufferSizeAnchor.choose(854, 480, 1280, 0).fromMainPass(),
                "宽有值而高没值 ⇒ 这张数不可信，整对一起弃用");
        assertFalse(GbufferSizeAnchor.choose(854, 480, 0, 720).fromMainPass(), "同上一条，反向");
        assertEquals(854, GbufferSizeAnchor.choose(854, 480, 1280, 0).width(), "回落尺寸原样");
    }

    @Test
    @DisplayName("🔴 接线：ensureColortex 必须按锚点建池与建深度，不许再直接读另一张主目标的尺寸")
    void mrtPassAnchorsOnTheChosenSize() throws Exception {
        String src = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/dev/vkdisp/bridge/MrtTerrainPass.java"));
        int at = src.indexOf("private static void ensureColortex(RenderTarget main)");
        assertTrue(at > 0, "找不到 ensureColortex，本断言的窗口失效");
        int end = src.indexOf("prepareGbufferViews", at);
        assertTrue(end > at, "找不到方法体的结束标记，窗口失效");
        String body = src.substring(at, end);
        assertTrue(body.contains("GbufferSizeAnchor.choose("), "建池尺寸必须走 choose（1.2 的唯一决策点）");
        assertTrue(body.contains("POOL.ensure(pool, anchor.width(), anchor.height()"),
                "POOL.ensure 必须按锚点尺寸建");
        assertTrue(body.contains("new TextureTarget(\"vkdisp gbuffer depth\", anchor.width(), anchor.height()"),
                "gbuffer 深度目标必须与池同尺寸（否则又是一处自己再算一遍）");
        assertTrue(body.contains("colortexDepth.resize(anchor.width(), anchor.height()"),
                "换锚点时深度目标必须一起 resize");
        assertFalse(body.contains("POOL.ensure(pool, main.width, main.height"),
                "GAP-049/1.2：不许退回按 gameRenderer.mainRenderTarget() 的尺寸建池");
        assertFalse(body.contains("checkPoolSize(main.width, main.height)"),
                "核对的也必须是真正建池用的那个尺寸，否则核对的是没用的数");
    }

}
