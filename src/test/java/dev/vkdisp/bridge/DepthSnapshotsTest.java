package dev.vkdisp.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GAP-023：{@code depthtex0/1/2} 是<b>同一张深度在三个时刻的快照</b>。
 *
 * <p>只测<b>不需要 GPU 的那一半</b>：名字→时刻的映射（本缺陷的语义核心）、资源位的取值、
 * 以及「没取过快照就绝不给视图」这条回退契约。blit 本身在真机臂上判（见 {@code evidence/}）。
 *
 * <p>🔴 为什么测试里要抄一份字面量而不是直接用枚举/常量类：测试源集<b>没有</b> renderpearl
 * 类路径（同一条边界记在 {@code DepthGlProxyTest} 第 169 行起）。⇒ 被测面必须是纯 Java 视图，
 * 这份清单就是那条边界的<b>代价</b>，写下来而不是藏起来。
 */
@DisplayName("GAP-023 depthtex 三时刻快照的名字映射与回退契约")
class DepthSnapshotsTest {

    /** 逐字抄自 {@code com/mojang/renderpearl/api/GpuFormat.java} 的深度族格式名。 */
    private static final Set<String> GPU_FORMAT_DEPTH_NAMES = Set.of(
            "D16_UNORM", "D24_UNORM", "D32_FLOAT", "D24_UNORM_S8_UINT", "S8_UINT",
            "D32_FLOAT_S8_UINT");

    @Test
    @DisplayName("depthtex0/1/2 分别映射到不透明后 / 半透明后 / 顶层后")
    void threeNamesMapToThreeMoments() {
        assertEquals(DepthSnapshots.OPAQUE, DepthSnapshots.slotOf("depthtex0"));
        assertEquals(DepthSnapshots.TRANSLUCENT, DepthSnapshots.slotOf("depthtex1"));
        assertEquals(DepthSnapshots.TOP_LAYER, DepthSnapshots.slotOf("depthtex2"));
        assertEquals(3, DepthSnapshots.COUNT, "三个时刻 = 三张快照");
    }

    @Test
    @DisplayName("不是 depthtex 形态的名字一律 -1（不许把别的采样名错接成深度层）")
    void nonDepthNamesAreRejected() {
        assertEquals(-1, DepthSnapshots.slotOf("shadowtex0"));
        assertEquals(-1, DepthSnapshots.slotOf("depthtex"));
        assertEquals(-1, DepthSnapshots.slotOf("depthtex3"));
        assertEquals(-1, DepthSnapshots.slotOf("depthtexX"));
        assertEquals(-1, DepthSnapshots.slotOf(null));
    }

    @Test
    @DisplayName("快照位 = 可采样 + 可拷入拷出，且不含 RENDER_ATTACHMENT")
    void usageIsSampleAndCopyOnly() {
        int usage = DepthSnapshots.snapshotUsage();
        assertEquals(7, usage, "COPY_DST(1)|COPY_SRC(2)|TEXTURE_BINDING(4)");
        assertEquals(0, usage & DepthSnapshots.RENDER_ATTACHMENT,
                "从不画进快照 —— 带附件位是白付带宽，也会让人误以为它参与渲染");
        assertTrue((usage & DepthSnapshots.TEXTURE_BINDING) != 0, "要当 sampler 源");
        assertTrue((usage & DepthSnapshots.COPY_DST) != 0, "blit 的目标");
        assertTrue((usage & DepthSnapshots.COPY_SRC) != 0, "GAP-022 的深度代理还要从它再拷一次");
    }

    @Test
    @DisplayName("格式必须是深度族里真实存在的名字（拼错 = 运行期 valueOf 抛在建资源路径上）")
    void formatNameExistsInGpuFormat() {
        String name = DepthSnapshots.snapshotFormatName();
        assertEquals("D32_FLOAT", name,
                "必须与活深度同格式：格式不同 copyTextureToTexture 的 aspect 就不匹配");
        assertTrue(GPU_FORMAT_DEPTH_NAMES.contains(name),
                "名字要在 GpuFormat 的深度族里真实存在（这里拼错的现场是「整次地形 pass 建不出来」，"
                        + "比这一格红难得多）");
    }

    @Test
    @DisplayName("没取过快照时任何一格都不算就位 —— 调用方才有「回退」可言")
    void noSlotIsClaimedBeforeItIsTaken() {
        // 🔴 这里刻意不调 view()：它的返回类型是 renderpearl 的 GpuTextureView，测试源集没有它，
        //   javac 连调用都拒（本轮实测 error: cannot access GpuTextureView）。
        //   所以「本帧有没有」被单独开成纯函数 has(int) —— 这条边界本身就是被测对象之一。
        DepthSnapshots.beginFrame();
        for (int slot = 0; slot < DepthSnapshots.COUNT; slot++) {
            assertFalse(DepthSnapshots.has(slot),
                    "slot " + slot + "：ensure/take 都没跑过就该说「没有」，不许假装接好了");
        }
        assertFalse(DepthSnapshots.hasTranslucentSnapshot(),
                "水没接进 gbuffer 之前不许声称 1 号存在 —— 说了就是撒谎，z1 > z0 仍恒假");
        assertTrue(DepthSnapshots.describeFrame().contains("taken=[]"),
                "自报行要说「本帧取了几格」，否则缺席会被读成已就位");
    }

    @Test
    @DisplayName("has() 对越界下标恒 false（不抛 —— 名字来自包，越界是常态不是异常）")
    void hasIsFalseForOutOfRangeSlots() {
        DepthSnapshots.beginFrame();
        assertFalse(DepthSnapshots.has(-1), "负下标");
        assertFalse(DepthSnapshots.has(DepthSnapshots.COUNT), "刚好越界");
        assertFalse(DepthSnapshots.has(99), "离谱越界");
        // 🔴 这里刻意不测 take(GpuTexture)：它的形参类型在测试源集里不存在，
        //   javac 连传 null 都拒（本轮实测 error: cannot access GpuTexture）。
        //   take 的前置条件由真机臂判：blit 没跑成时 [GAP-023] 自报行会说「快照=无 ⇒ 回退活深度」。
    }
}
