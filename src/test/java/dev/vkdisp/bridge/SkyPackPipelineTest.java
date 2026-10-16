package dev.vkdisp.bridge;
/**
 * 【自行补充】GAP-027 穹顶派生管线工厂的守卫（只验<b>离线可证</b>的元数据层，不碰 GPU）
 * 0. 合规核对：参考对象 = 本仓库自有 {@code SkyCelestialPackWiringTest}（同一族「不触发 compiled()、
 *    只验决策/形状」的断言形状）。
 * 1. 官方/主实现：无。
 * 2. 备选：在守卫里调 {@code RenderSystem.getCompiledPipeline} 验真编译 —— <b>否决</b>：那要 GPU，离线跑不了；
 *    编译失败本就该由混入侧既有兜底处理（SkyCelestialPack 同款「没落地就不换」）。本守卫只管「形状层对不对」。
 * 3. 我们的差异点：钉住两条<b>会静默错</b>的形状判据：① 三处都不该留 {@code SAMPLER0}（穹顶不采贴图）；
 *    ② 三处都喂同一条 {@code gbuffers_skybasic}（不是日月那条）。这两条写错都不会抛、只会画面错。
 * 4. 许可证：MIT；零第三方代码。
 * 5. 性能基线：❄️ 纯静态读，离线。
 */
import dev.vkdisp.pack.PackTerrainSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SkyPackPipelineTest {

    @Test
    @DisplayName("🔴 穹顶/星点/晨昏都不留 SAMPLER0（skybasic 片元不采贴图；日月那条才需要）")
    void noSiteNeedsSampler0() {
        for (SkyGeometrySite site : SkyGeometrySite.values()) {
            assertFalse(SkyPackPipeline.requiresSampler0(site),
                    site + " 不该绑 SAMPLER0（绑了 = 多一个必需 uniform，包 skybasic 没这个名字会 validateDraw 抛）");
        }
    }

    @Test
    @DisplayName("🔴 三处管线都建在同一条 gbuffers_skybasic 上（不是日月程序）")
    void allSitesTargetSkybasic() {
        for (SkyGeometrySite site : SkyGeometrySite.values()) {
            assertEquals(PackTerrainSource.SKY_PROGRAM, SkyPackPipeline.program(site),
                    site + " 的程序必须与 SkyGeometrySite 冻结的那条同源");
        }
    }

    @Test
    @DisplayName("🔖 自报名单覆盖三处站点（描述符没建前，至少这条读得到）")
    void describeSitesCoversAllThree() {
        String joined = String.join(",", SkyPackPipeline.describeSites());
        for (SkyGeometrySite site : SkyGeometrySite.values()) {
            org.junit.jupiter.api.Assertions.assertTrue(joined.contains(site.name()),
                    () -> "自报缺 " + site.name() + "：实际 " + joined);
        }
    }
}
