package dev.vkdisp.bridge;
/**
 * 【自行补充】GAP-027 穹顶路线决策表的守卫：逐字钉住「站点 → 程序 / 顶点形状 / 渲染阶段」与原版事实一致
 * 0. 合规核对：参考对象 = 本仓库自有 {@link SkyCelestialPackWiringTest}（同一族「冻结表与原版对账」的断言形状）。
 * 1. 官方/主实现：无。
 * 2. 备选：只断言枚举非空 —— 否决：本表砸的是「与 {@code RenderPipelines.java:1037-1083} 逐字一致」，
 *    形状写错（比如把穹顶的 TRIANGLE_FAN 写成 QUADS、把 STARS 的 OVERLAY 写成 DEFAULT）离线看不出，
 *    接上去就是几何解释错 / 观感变，且没有报错 —— 必须拿原版的字面常量当尺子逐条比（X39：真包/真值对账）。
 * 3. 我们的差异点：① 三处<b>同一条</b> {@code gbuffers_skybasic}（BSL 靠 renderStage 分派，见 {@link SkyGeometrySite} 类注释第 3 条）；
 *    ② 顶点形状/拓扑/混合<b>各不相同</b>且必须与原版逐字相同；③ {@code renderStage} 宏名必须能被唯一真源解析（不是 -1）。
 * 4. 许可证：LGPL-3.0-or-later；零第三方代码。
 * 5. 性能基线：❄️ 纯常量比对，离线。
 */
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import dev.vkdisp.pack.PackTerrainSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SkyGeometrySiteTest {

    @Test
    @DisplayName("🔴 三处都喂 gbuffers_skybasic（BSL 用 renderStage 在同一片元上分派，不是三条程序）")
    void allSitesUseTheSameSkybasicProgram() {
        for (SkyGeometrySite site : SkyGeometrySite.values()) {
            assertEquals(PackTerrainSource.SKY_PROGRAM, site.program,
                    site + " 必须喂 gbuffers_skybasic（喂错程序 = 拿日月的图集程序画穹顶）");
        }
    }

    @Test
    @DisplayName("🔴 每站的顶点形状/拓扑/混合必须与原版 RenderPipelines 逐字一致（写错离线看不出）")
    void eachSiteMatchesVanillaPipelineFacts() {
        // RenderPipelines.SKY (:1037-1046)：POSITION + TRIANGLE_FAN + ColorTargetState.DEFAULT
        assertSame(DefaultVertexFormat.POSITION, SkyGeometrySite.SKY_DISC.vertexFormat);
        assertEquals(PrimitiveTopology.TRIANGLE_FAN, SkyGeometrySite.SKY_DISC.topology);
        assertNull(SkyGeometrySite.SKY_DISC.blend, "穹顶用 ColorTargetState.DEFAULT（不是某个混合函数）");
        assertSame(ColorTargetState.DEFAULT, SkyGeometrySite.SKY_DISC.colorTargetState());

        // RenderPipelines.STARS (:1072-1082)：POSITION + QUADS + OVERLAY
        assertSame(DefaultVertexFormat.POSITION, SkyGeometrySite.STARS.vertexFormat);
        assertEquals(PrimitiveTopology.QUADS, SkyGeometrySite.STARS.topology);
        assertEquals(BlendFunction.OVERLAY, SkyGeometrySite.STARS.blend);

        // RenderPipelines.SUNRISE_SUNSET (:1060-1070)：POSITION_COLOR + TRIANGLE_FAN + TRANSLUCENT
        assertSame(DefaultVertexFormat.POSITION_COLOR, SkyGeometrySite.SUNRISE_SUNSET.vertexFormat);
        assertEquals(PrimitiveTopology.TRIANGLE_FAN, SkyGeometrySite.SUNRISE_SUNSET.topology);
        assertEquals(BlendFunction.TRANSLUCENT, SkyGeometrySite.SUNRISE_SUNSET.blend);
    }

    @Test
    @DisplayName("🔴 每站的 renderStage 宏名必须能被唯一真源解析（解不出 = 包按 -1 比较会静默错）")
    void everyStageMacroResolves() {
        for (SkyGeometrySite site : SkyGeometrySite.values()) {
            assertNotEquals(-1, site.renderStageValue(),
                    site.stageMacro + " 不是已知 renderStage 宏（RenderStages 名单里没有它）");
        }
        // SKY/SUNSET/STARS 是三个不同的阶段（BSL 靠这三个数分派 alpha）。
        assertNotEquals(SkyGeometrySite.SKY_DISC.renderStageValue(), SkyGeometrySite.STARS.renderStageValue());
        assertNotEquals(SkyGeometrySite.STARS.renderStageValue(),
                SkyGeometrySite.SUNRISE_SUNSET.renderStageValue());
    }
}
