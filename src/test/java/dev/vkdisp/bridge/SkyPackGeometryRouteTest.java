package dev.vkdisp.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code GAP-054} 的守卫：包天空那条「NDC 全屏大三角」几何路线<b>什么时候不许走</b>。
 *
 * <p>🔖 这块画面是本项目最难查的一类症状：<b>不报错的错</b>。几何被真相机矩阵打过一遍之后
 * 仍然合法提交（本机没有 validation layer），于是屏幕上一半是天空、日志全绿。
 * ⇒ 判据只能落在「喂这张三角的前提成不成立」上，而且必须能<b>跑</b>。
 */
class SkyPackGeometryRouteTest {

    private static final Path SKY = Path.of("src/main/java/dev/vkdisp/bridge/SkyPackGbuffer.java");

    private static String code() {
        assumeTrue(Files.exists(SKY), () -> "源码不在（" + SKY + "）⇒ 只跑行为侧");
        try {
            return Files.readString(SKY);
        } catch (java.io.IOException e) {
            assumeTrue(false, "读不到 " + SKY + "：" + e.getMessage());
            return "";
        }
    }

    @Test
    @DisplayName("🔴 前提的真值表：两条都不成立 ⇒ 全屏三角这条路线必须被拒（GAP-054 那一格）")
    void ndcTriangleNeedsEitherPassthroughOrIdentityMvp() {
        assertFalse(SkyPackGbuffer.ndcTriangleRouteValid(false, false),
                "顶点终稿会乘真相机矩阵、矩阵又不是恒等 ⇒ 喂 NDC 坐标就是拿世界几何过一遍透视");
        assertTrue(SkyPackGbuffer.ndcTriangleRouteValid(true, false),
                "顶点是 passthrough ⇒ NDC 坐标原样出去，路线成立");
        assertTrue(SkyPackGbuffer.ndcTriangleRouteValid(false, true),
                "顶点会乘矩阵、但这一族供的是恒等 ⇒ 乘了等于没乘，路线成立");
        assertTrue(SkyPackGbuffer.ndcTriangleRouteValid(true, true), "两条都成立当然可以走");
    }

    @Test
    @DisplayName("🔖 今天这两个事实必须是 false（守卫不许是空转），且判定必须排在契约检查<b>之前</b>")
    void guardIsNotVacuousAndRunsFirst() {
        String source = code();
        // 🔖 位置针之前先 contains 断言：needle 消失会让 indexOf 回 -1，而 -1 < 任何下标 = 假绿
        assertTrue(source.contains(
                        "private static final boolean VERTEX_STAGE_PASSES_POSITION_THROUGH = false;"),
                "实测事实：包的 skybasic 顶点终稿逐字乘 gbufferProjection * gbufferModelView"
                        + "（build/dump-head1/world0_gbuffers_skybasic-VERTEX.glsl:240）⇒ 改成 true 要连证据一起改");
        assertTrue(source.contains(
                        "private static final boolean CAMERA_MATRICES_ARE_IDENTITY = false;"),
                "实测事实：OfUniformManager.putCameraMatrices:377-378 交的是真相机矩阵，"
                        + "Family.CHAIN 只翻深度口径 ⇒ 不是恒等");

        int problem = source.indexOf("private static @Nullable String contractProblem()");
        int valid = source.indexOf("if (!ndcTriangleRouteValid(VERTEX_STAGE_PASSES_POSITION_THROUGH, "
                + "CAMERA_MATRICES_ARE_IDENTITY))");
        int contract = source.indexOf("PackTerrainProgram program = VkDispVirtualPack.packContract",
                problem);
        assertTrue(problem > 0 && valid > problem, "判定必须真的长在 contractProblem() 里（否则是死代码）");
        assertTrue(contract > valid,
                "🔴 必须排在契约检查之前：契约那两条失败是「响亮地抛」，而几何错配是「静默画错」"
                        + "⇒ 先拒静默那一类，才不会出现「契约通过 ⇒ 巨型三角形照画」");
    }

    @Test
    @DisplayName("🔖 拒绝必须有名有计数（T10/T11：不静默换绑，且每处判据都要能自证走到）")
    void refusalIsReportedAndCounted() {
        String source = code();
        assertTrue(source.contains("STATS.refusedGeometry++"),
                "不计数就分不清「这条判据没走到」与「走到了但画面还是错的」");
        assertTrue(source.contains("public static long refusedGeometryCount()"),
                "计数必须有出口（真机取证要读得到）");
        assertTrue(source.contains("GAP-028 ③"),
                "拒绝理由必须点名<b>真正的修法</b>（按 draw 供 renderStage + 改喂原版天空几何），"
                        + "否则下一个人在这里再兜一层「再试一次全屏三角」");
    }

    @Test
    @DisplayName("❌ 那张三角本身不许被改回「世界坐标」口径（它是 NDC，z=1 是天空的远平面）")
    void fullscreenTriangleStaysNdc() {
        String source = code();
        assertTrue(source.contains("{-1.0F, -1.0F, 0.0F, 0.0F}")
                        && source.contains("{3.0F, -1.0F, 2.0F, 0.0F}")
                        && source.contains("{-1.0F, 3.0F, 0.0F, 2.0F}"),
                "NDC 大三角的三个顶点（与 FrameApi.POST_FULLSCREEN_VERTICES 同一张）");
        assertTrue(source.contains("putFloat(vertex[0]).putFloat(vertex[1]).putFloat(1.0F).putFloat(1.0F)"),
                "Position 的 z 写死 1.0（远平面）+ w=1 ⇒ 恒等下 NDC z=1；改这里要连 GAP-037 的深度判据一起看");
        assertEquals(1, occurrences(source, "pass.draw(3, 1, 0, 0);"),
                "全屏三角只有<b>一处</b> draw（多一处就是有人又兜了一层）");
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }
}
