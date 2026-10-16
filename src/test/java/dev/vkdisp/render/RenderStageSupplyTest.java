package dev.vkdisp.render;
/**
 * 【自行补充】GAP-028 ② 的守卫：{@code renderStage} 的逐 draw 供值 + 编号单一真源 + 作用域不漏还原
 *
 * <p>0. 合规核对：参考对象 = 本仓库自有的 {@link RenderStageSupply} 与
 *    {@code glsl/preprocess/RenderStages}（编号口径的唯一真源）。零外部实现（07-CONSTRAINTS L12）；
 *    许可证：本文件独立编写（MIT）。
 * 1. 官方/主实现：OF/Iris 的逐 draw 供值（本类不声称与 Iris 数值一致 —— 那条未核实到源码，X9）。
 * 2. 备选：只在真机上靠画面反推「阶段有没有供上」—— 否决：包的分支写错与没供值在画面上<b>同形</b>
 *    （都是「日月盘不见了」），只能靠读数分开。
 * 3. 我们的差异点：判据落在「宏与供值必须同数、阶段之间必须互不相同、还原必须结构性保证」三件事上。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 纯函数 + 计数，无渲染。
 */
import dev.vkdisp.glsl.preprocess.RenderStages;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderStageSupplyTest {

    @BeforeEach
    @AfterEach
    void isolateStaticHolder() {
        RenderStageSupply.resetForTests();
    }

    @Test
    @DisplayName("🔴 供值必须与宏表<b>同一个数</b>（两处各写一遍迟早漂移，QD-02 口径）")
    void suppliedNumbersComeFromTheMacroTable() {
        for (String stage : RenderStages.STAGES) {
            try (RenderStageSupply.Scope scope = RenderStageSupply.enter(stage)) {
                assertEquals(RenderStages.valueOf(RenderStages.MACRO_PREFIX + stage).intValue(), scope.stage(),
                        "供值必须等于宏展开的那个数：包写的是 `renderStage == MC_RENDER_STAGE_" + stage + "`，"
                                + "两边不等 ⇒ 比较恒假，而它编译得过、不报错");
            }
        }
        assertEquals(RenderStages.valueOf("MC_RENDER_STAGE_NONE").intValue(), RenderStageSupply.NONE,
                "NONE 的 0 也必须取自同一张表");
    }

    @Test
    @DisplayName("🔴 日月/自定义天空三个阶段必须互不相同且都不等于 NONE（否则包的分支互相踩）")
    void stagesBslComparesAreDistinguishable() {
        int none = RenderStageSupply.NONE;
        int sun = RenderStages.valueOf("MC_RENDER_STAGE_SUN");
        int moon = RenderStages.valueOf("MC_RENDER_STAGE_MOON");
        int custom = RenderStages.valueOf("MC_RENDER_STAGE_CUSTOM_SKY");
        assertNotEquals(none, sun, "太阳等于 NONE ⇒ BSL skytextured 的 SUN 分支永远走不到");
        assertNotEquals(none, moon);
        assertNotEquals(none, custom);
        assertNotEquals(sun, moon, "日月同号 ⇒ 同一段代码会拿到错的贴图与色");
        assertNotEquals(sun, custom);
    }

    @Test
    @DisplayName("🔴 作用域必须结构性还原（裸 setter 漏还原会污染同帧后面的地形/水）")
    void scopeRestoresPreviousStageEvenWhenNested() {
        assertEquals(RenderStageSupply.NONE, RenderStageSupply.current(), "初始必须是 NONE");
        try (RenderStageSupply.Scope outer = RenderStageSupply.enter("SKY")) {
            assertEquals(RenderStages.valueOf("MC_RENDER_STAGE_SKY").intValue(), RenderStageSupply.current());
            try (RenderStageSupply.Scope inner = RenderStageSupply.enter("SUN")) {
                assertEquals(RenderStages.valueOf("MC_RENDER_STAGE_SUN").intValue(), RenderStageSupply.current(),
                        "内层要真的生效（日月是两次独立 draw，逐笔各不相同）");
            }
            assertEquals(RenderStages.valueOf("MC_RENDER_STAGE_SKY").intValue(), RenderStageSupply.current(),
                    "内层关掉后必须回到<b>外层</b>那一笔，而不是直接回 NONE");
        }
        assertEquals(RenderStageSupply.NONE, RenderStageSupply.current(), "全部关掉后必须回 NONE");
    }

    @Test
    @DisplayName("🔴 不认识的名字 ⇒ 按 NONE 供 + 留下读数（不沿用上一层，也不猜一个像的）")
    void unknownNameFallsBackToNoneAndIsCounted() {
        try (RenderStageSupply.Scope outer = RenderStageSupply.enter("SKY")) {
            int entersBefore = RenderStageSupply.enters();
            // 故意在 SKY 作用域里读一个错名字：回退必须是 NONE，而不是「继续用 SKY」
            try (RenderStageSupply.Scope bogus = RenderStageSupply.enter("TERRAIN_NOT_A_STAGE")) {
                assertEquals(RenderStageSupply.NONE, bogus.stage(),
                        "错名字沿用上一层 = 把上一笔的阶段带到这一笔，正是本类要消灭的形状");
            }
            assertEquals(RenderStages.valueOf("MC_RENDER_STAGE_SKY").intValue(), RenderStageSupply.current(),
                    "内层（错名字）关掉后必须回到<b>外层</b>那一笔的阶段 —— 回退只作用于本层");
            assertEquals(entersBefore, RenderStageSupply.enters(),
                    "认不出的名字不该被记成一次真进入（否则计数会把「写错名字」洗成「正常」）");
        }
        assertEquals(1, RenderStageSupply.unknownNameReads(), "读错名字必须可数，不然「写了没生效」看不见");
        assertEquals("TERRAIN_NOT_A_STAGE", RenderStageSupply.lastUnknownName());
    }

    @Test
    @DisplayName("🔖 接线守卫：供值出口 + 天空那一笔真的进 SKY + 旧自报不许再说「仍未接」")
    void wiringNeedlesArePresent() throws Exception {
        String manager = Files.readString(Path.of("src/main/java/dev/vkdisp/render/OfUniformManager.java"));
        assertTrue(manager.contains("values.put(\"renderStage\", RenderStageSupply.current())"),
                "供值必须从这块持有者读（再算一遍编号就是第二份口径）");
        assertFalse(manager.contains("逐 draw 供值**仍未接**"),
                "这一半已经接上 ⇒ 自报必须跟着改口径（X11：自报不许比代码更自信，也不许比代码更悲观）");

        String sky = Files.readString(Path.of("src/main/java/dev/vkdisp/bridge/SkyPackGbuffer.java"));
        assertTrue(sky.contains("RenderStageSupply.enter(\"SKY\")"),
                "天空那笔 draw 必须进 SKY 阶段（BSL 靠这个区分天空与日月/星）");
        assertTrue(sky.indexOf("RenderStageSupply.enter(\"SKY\")")
                        < sky.indexOf("updateGbufferBuiltins(PackTerrainSource.SKY_PROGRAM)"),
                "阶段必须在写环<b>之前</b>生效：环里存的就是那一刻的读数，之后设的值这一帧进不去");
        assertTrue(sky.contains("try (dev.vkdisp.render.RenderStageSupply.Scope"),
                "必须用 try-with-resources 结构性还原（裸 setter 是这一族事故的原形状）");
        assertNull(RenderStageSupply.lastUnknownName());
    }
}
