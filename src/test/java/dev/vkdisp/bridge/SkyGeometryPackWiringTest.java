package dev.vkdisp.bridge;
/**
 * 【自行补充】GAP-027 穹顶/星点/晨昏三站接线的守卫（注入点注册 + @WrapOperation 形参序）
 * 0. 合规核对：参考对象 = 本仓库自有 {@code SkyCelestialPackWiringTest} / {@code MainPassTakeoverTest}
 *    （源码针 + 计数针的形状）。零外部实现。许可证：MIT。
 * 1..5. 同族：见 {@link SkyGeometryPack} 的头部。
 */
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkyGeometryPackWiringTest {

    private static final Path MIXIN =
            Path.of("src/main/java/dev/vkdisp/mixin/SkyRendererGeometryPipelineMixin.java");
    private static final Path JSON = Path.of("src/main/resources/vkdisp.mixins.json");

    private static String read(Path path) throws Exception {
        assertNotNull(path);
        return Files.readString(path);
    }

    /** 数一段文本出现几次（单针会因「整段没了」而白过，计数不会）。 */
    private static int count(String haystack, String needle) {
        return (int) (haystack.split(java.util.regex.Pattern.quote(needle), -1).length - 1L);
    }

    @Test
    @DisplayName("🔴 注入点必须注册进 mixins.json")
    void mixinIsRegistered() throws Exception {
        assertTrue(read(JSON).contains("\"SkyRendererGeometryPipelineMixin\""),
                "没注册 = 这三站一笔都不换，而日志里看不出是没注册还是闸门关着");
    }

    @Test
    @DisplayName("🔴 三站形参序必须「接收者在前」，且 original.call 把接收者一起交回（写反 = 类转换期崩客户端）")
    void allThreeSitesTakeReceiverFirst() throws Exception {
        String mixin = read(MIXIN);
        // setPipeline 是实例方法 ⇒ MixinExtras 期望 (接收者, 实参..., Operation)。
        // 2026-10-16 真机第一次跑就崩在写反的那份上：APPLY 期 invalid signature，与闸门开不开无关。
        assertEquals(3, count(mixin, "RenderPass self, CompiledRenderPipeline requested,"),
                "三站各一条形参表，少一条就是有一站被改回了错的序");
        assertEquals(6, count(mixin, "original.call(self, requested);"),
                "每站两条交回路径（闸门关 / 备料失败），必须都带上接收者");
        assertEquals(3, count(mixin, "original.call(self, pack);"),
                "换管线那条交回必须带上接收者");
        assertEquals(0, count(mixin, "original.call(requested);"),
                "「不带接收者」的旧形状不得复活");
        assertEquals(0, count(mixin, "original.call(pack);"),
                "「不带接收者」的旧形状不得复活");
        assertEquals(3, count(mixin, "SkyGeometryPack.apply(self, site);"),
                "换完管线必须由 bridge 补绑（包自由 sampler 在同一条绑定里）");
    }
}
