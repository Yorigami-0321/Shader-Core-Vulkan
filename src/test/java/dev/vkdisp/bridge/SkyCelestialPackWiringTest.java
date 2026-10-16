package dev.vkdisp.bridge;
/**
 * 【自行补充】GAP-027 日月接线的守卫（注入点注册、label 分流、换代、两侧同源、关档退回）
 * 0. 合规核对：参考对象 = 本仓库自有 {@code MrtTerrainPassWiringTest} / {@code MixinWiringTest}
 *    （源码针 + 位置针的形状）。零外部实现。许可证：MIT。
 * 1..5. 同族：见 {@link SkyCelestialPack} 的头部。
 */
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SkyCelestialPackWiringTest {

    private static final Path PACK = Path.of("src/main/java/dev/vkdisp/bridge/SkyCelestialPack.java");
    private static final Path MIXIN = Path.of("src/main/java/dev/vkdisp/mixin/SkyRendererCelestialPipelineMixin.java");
    private static final Path JSON = Path.of("src/main/resources/vkdisp.mixins.json");
    private static final Path TARGETS = Path.of("src/main/java/dev/vkdisp/bridge/MixinTargets.java");

    private static String read(Path path) throws Exception {
        assertNotNull(path);
        return Files.readString(path);
    }

    @Test
    @DisplayName("🔴 注入点必须注册进 mixins.json，且宿主方法与目标调用都用 MixinTargets 常量（不写字面量）")
    void injectionPointIsRegisteredAndTargeted() throws Exception {
        String json = read(JSON);
        assertTrue(json.contains("SkyRendererCelestialPipelineMixin"),
                "混入类没进 client 列表 = 它永远不会被应用，而日志不会有第二句话");
        String mixin = read(MIXIN);
        assertTrue(mixin.contains("MixinTargets.SKY_RENDERER_DRAW_CELESTIAL_BODY"),
                "宿主方法描述符必须是登记过的常量（改签名要当场变红，而不是静默不注）");
        assertTrue(mixin.contains("MixinTargets.RENDER_PASS_SET_PIPELINE"),
                "目标调用同理");
        assertTrue(mixin.contains("require = 1, expect = 1"),
                "一次命中就是硬要求：命中 0 必须启动期失败（T10）");
        String targets = read(TARGETS);
        assertTrue(targets.contains("drawCelestialBody(Ljava/util/function/Supplier;"),
                "描述符逐字取自 26.3 源码（SkyRenderer:252-253），不许凭记忆写");
    }

    @Test
    @DisplayName("🔴 只换 Sun/Moon —— 末地闪烁走同一个私有方法，不区分会拿包的日月程序画它")
    void endFlashIsExcludedByLabel() throws Exception {
        String pack = read(PACK);
        assertTrue(pack.contains("!\"Sun\".equals(label) && !\"Moon\".equals(label)"),
                "enabledFor 必须按 label <b>白名单</b>分流 —— End flash 共用同一个私有方法，"
                        + "不区分就会拿包的日月程序去画末地闪烁");
        // 行为侧：末地闪烁那个 label 必须回 false（与配置无关 ⇒ 关着也 false）
        assertFalse(SkyCelestialPack.enabledFor("End flash"),
                "End flash 不是日月，绝不该被换成 gbuffers_skytextured");
    }

    @Test
    @DisplayName("🔴 每次 draw 必须换一块块字节代（日与月同程序两次 draw，renderStage 在块里）")
    void eachDrawGetsItsOwnGeneration() throws Exception {
        String pack = read(PACK);
        assertTrue(pack.contains("ring.rotate();"),
                "不换代 = 后写的那份被两次 draw 都读到；这是本格的静默错值形状");
        int enter = pack.indexOf("RenderStageSupply.enter(stage)");
        int write = pack.indexOf("TerrainPipelineApi.updateGbufferBuiltins(PROGRAM);", enter);
        assertTrue(enter > 0 && write > enter,
                "renderStage 必须包住「写那一代」的那次调用：块里存的是写那一刻的读数");
        assertTrue(pack.indexOf("ring.rotate();") < enter,
                "顺序必须是 rotate → 写：先写后 rotate 会把刚写的那代让出去，本笔又绑回旧代");
    }

    @Test
    @DisplayName("🔖 关档必须原样交回；且备料失败时不许换管线（换了绑不上 = validateDraw 抛 = 崩帧）")
    void refusesBeforeSwappingPipeline() throws Exception {
        String mixin = read(MIXIN);
        int prepare = mixin.indexOf("SkyCelestialPack.prepare(which)");
        int swap = mixin.indexOf("original.call(pack)");
        assertTrue(prepare > 0 && swap > prepare,
                "必须先备料成功、再换管线；顺序倒了就是「换了着色器却没绑 uniform」那一族崩帧");
        assertTrue(mixin.contains("original.call(requested);"),
                "不接管的路径必须把原版管线原样交回（不吞、不半接）");
        // 🔖 行为侧只能测「label 白名单」这一半：Sun/Moon 那两支会去读 FML 配置，
        //    单测里没有加载中的配置（Cannot get config value before config is loaded）⇒ 不在这里调。
        assertFalse(SkyCelestialPack.enabledFor("End flash"), "末地闪烁必须 false（与开关状态无关）");
        assertFalse(SkyCelestialPack.enabledFor(null), "label 取不到也必须 false，不猜");
    }
}
