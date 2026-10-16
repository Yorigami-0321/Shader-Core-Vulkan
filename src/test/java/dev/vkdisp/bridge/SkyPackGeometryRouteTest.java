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
    @DisplayName("🔖 前提必须<b>实测终稿</b>（不是一块可以改真的常量），且判定必须排在契约检查<b>之前</b>")
    void guardIsMeasuredFromArtifactAndRunsFirst() {
        String source = code();
        // 🔴 这块守卫管的是「别把判据写成常量」。上一版这里是两个 `= false` 的 final 布尔，
        //    于是「让它接管」的最小改动 = 把常量改成 true —— 而那**什么都不证明**。
        //    现在判据是对交进包资源的那份顶点终稿跑谓词，改不出来。
        assertFalse(source.contains("VERTEX_STAGE_PASSES_POSITION_THROUGH ="),
                "几何路线的前提不许再是编译期常量：常量只转述「有人决定了什么」，不转述「源里真长什么样」");
        assertTrue(source.contains(
                        "GbufferVertexRoute.passesPositionThrough(PackTerrainSource.SKY_PROGRAM)"),
                "判据必须读终稿量出来的那一位（GbufferVertexRoute 生成期量一次，渲染期只查表）");
        assertTrue(source.contains("boolean passthrough = vertexStagePassesPositionThrough();"),
                "contractProblem() 必须现读那次测量，不许缓存一份「接管时一定成立」的乐观值");
        assertTrue(source.contains(
                        "private static final boolean CAMERA_MATRICES_ARE_IDENTITY = false;"),
                "实测事实：OfUniformManager.putCameraMatrices 交的是真相机矩阵，"
                        + "Family.CHAIN 只翻深度口径 ⇒ 这一条仍是 false（成立的是 clip 那一半）");

        // 🔖 位置针之前先 contains 断言：needle 消失会让 indexOf 回 -1，而 -1 < 任何下标 = 假绿
        assertTrue(source.contains("if (!ndcTriangleRouteValid(passthrough, CAMERA_MATRICES_ARE_IDENTITY))"),
                "几何闸门必须吃**现读的** passthrough（吃常量的话，判据就退回到「有人决定了什么」）");
        int problem = source.indexOf("private static @Nullable String contractProblem()");
        int valid = source.indexOf("if (!ndcTriangleRouteValid(passthrough, CAMERA_MATRICES_ARE_IDENTITY))");
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

    @Test
    @DisplayName("🔴 判据位必须 fail-closed：没量过 / 量出不是 passthrough ⇒ 拒喂全屏三角")
    void routeFlagFailsClosed() {
        dev.vkdisp.pack.GbufferVertexRoute.resetForTests();
        try {
            assertFalse(dev.vkdisp.pack.GbufferVertexRoute
                            .passesPositionThrough(dev.vkdisp.pack.PackTerrainSource.SKY_PROGRAM),
                    "查不到就是 false（没量过 ≠ 量过且成立）");
            assertFalse(dev.vkdisp.pack.GbufferVertexRoute
                    .note(dev.vkdisp.pack.PackTerrainSource.SKY_PROGRAM, null), "没有顶点源 ⇒ false");
            assertFalse(dev.vkdisp.pack.GbufferVertexRoute
                    .passesPositionThrough(dev.vkdisp.pack.PackTerrainSource.SKY_PROGRAM),
                    "note(null) 之后读数必须跟着撤 ⇒ 残留 true 就是拿上一轮的判据画这一轮的几何");
            assertTrue(dev.vkdisp.pack.GbufferVertexRoute.note(
                            dev.vkdisp.pack.PackTerrainSource.SKY_PROGRAM,
                            "void main() {\n    gl_Position = vec4(Position, 1.0);\n}\n"),
                    "真 passthrough 形态必须记成 true（否则天空永远退回原版，本类就成了死码）");
            assertTrue(dev.vkdisp.pack.GbufferVertexRoute
                    .passesPositionThrough(dev.vkdisp.pack.PackTerrainSource.SKY_PROGRAM));
            assertFalse(dev.vkdisp.pack.GbufferVertexRoute.note(
                            dev.vkdisp.pack.PackTerrainSource.SKY_PROGRAM,
                            "void main() {\n    gl_Position = gbufferProjection * vec4(Position, 1.0);\n}\n"),
                    "换包/重生成量出旧句式 ⇒ 记 false 并撤掉那一位");
            assertFalse(dev.vkdisp.pack.GbufferVertexRoute
                    .passesPositionThrough(dev.vkdisp.pack.PackTerrainSource.SKY_PROGRAM));
        } finally {
            dev.vkdisp.pack.GbufferVertexRoute.resetForTests();
        }
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }
}
