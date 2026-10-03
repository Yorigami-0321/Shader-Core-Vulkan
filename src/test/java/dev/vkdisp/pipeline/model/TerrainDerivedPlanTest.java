package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】H 线 M-01 规格表单测 / 纯数据断言（无原版类型、无 GPU 依赖）
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓库 {@code docs/04-SPEC.md} §5.0 与原版 26.3
 *    {@code RenderPipelines} 地形管线的**事实性参数**（Mojang EULA，只提取参数值，不搬运代码）。
 *    → 可并入本项目（MIT）：本文件只断言参数表，不含被参考方代码；不含 GPL/LGPL/ARR。
 * 1. 官方/主实现：同被测类（纯数据表）。
 * 2. 备选：无（本就是单测）。
 * 3. 我们的差异点：期望值**逐条抄自证据文件里的源码级核实表**，而不是抄自实现 ——
 *    这样「实现抄漏了原版状态」会当场变红，而不是跟着实现一起错。
 * 4. 许可证核对：本项目 MIT。
 * 5. 性能基线：❄️ 单测，不适用。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link TerrainDerivedPlan} 的规格表正确性 —— M-01「派生管线有没有把原版状态抄全」的守门测试。 */
class TerrainDerivedPlanTest {

    @Test
    @DisplayName("全量表 = 3 层 × 2 变体 = 6 条，且顺序稳定")
    void allIsSixStableSpecs() {
        List<TerrainDerivedPlan.Spec> specs = TerrainDerivedPlan.all();
        assertEquals(6, specs.size());
        assertEquals(List.of("SOLID", "SOLID", "CUTOUT", "CUTOUT", "TRANSLUCENT", "TRANSLUCENT"),
                specs.stream().map(TerrainDerivedPlan.Spec::layer).toList());
        assertEquals(List.of(false, true, false, true, false, true),
                specs.stream().map(TerrainDerivedPlan.Spec::multiDraw).toList());
        // 顺序稳定是注册日志与断言的前提：同样输入两次必须得到同样次序。
        assertEquals(specs.stream().map(TerrainDerivedPlan.Spec::location).toList(),
                TerrainDerivedPlan.all().stream().map(TerrainDerivedPlan.Spec::location).toList());
    }

    @Test
    @DisplayName("location 唯一生成式：与原版命名不同域、含层名与多重绘制后缀")
    void locationsAreNamespacedAndDistinct() {
        assertEquals("vkdisp:pipeline/terrain_solid", TerrainDerivedPlan.locationOf("solid", false));
        assertEquals("vkdisp:pipeline/terrain_solid_multidraw", TerrainDerivedPlan.locationOf("solid", true));
        assertEquals("vkdisp:pipeline/terrain_cutout", TerrainDerivedPlan.locationOf("cutout", false));
        assertEquals("vkdisp:pipeline/terrain_cutout_multidraw", TerrainDerivedPlan.locationOf("cutout", true));
        assertEquals("vkdisp:pipeline/terrain_translucent", TerrainDerivedPlan.locationOf("translucent", false));
        assertEquals("vkdisp:pipeline/terrain_translucent_multidraw",
                TerrainDerivedPlan.locationOf("translucent", true));
        // 6 条 location 必须两两不同 —— 重复会让原版按 location 建索引时互相覆盖（GAP-003 ① 的教训）。
        assertEquals(6, TerrainDerivedPlan.all().stream()
                .map(TerrainDerivedPlan.Spec::location).distinct().count());
    }

    @Test
    @DisplayName("ALPHA_CUTOUT 三档逐条对齐原版：SOLID 无 / CUTOUT 0.5 / TRANSLUCENT 0.1")
    void alphaCutoutMatchesVanillaSource() {
        // 原版 RenderPipelines:349-351 SOLID_TERRAIN —— 无 ALPHA_CUTOUT define。
        assertNull(TerrainDerivedPlan.specOf("SOLID", false).alphaCutout());
        assertFalse(TerrainDerivedPlan.specOf("SOLID", false).hasAlphaCutout());
        // 原版 RenderPipelines:379-385 CUTOUT_TERRAIN —— ALPHA_CUTOUT 0.5F。
        assertEquals(0.5F, TerrainDerivedPlan.specOf("CUTOUT", false).alphaCutout());
        assertTrue(TerrainDerivedPlan.specOf("CUTOUT", true).hasAlphaCutout());
        // 原版 RenderPipelines:393-399 TRANSLUCENT_TERRAIN —— ALPHA_CUTOUT 0.1F。
        assertEquals(0.1F, TerrainDerivedPlan.specOf("TRANSLUCENT", false).alphaCutout());
    }

    @Test
    @DisplayName("TRANSLUCENT 是唯一带 BlendFunction.TRANSLUCENT 的层（其余走 ColorTargetState.DEFAULT）")
    void onlyTranslucentBlends() {
        assertFalse(TerrainDerivedPlan.specOf("SOLID", false).translucentBlend());
        assertFalse(TerrainDerivedPlan.specOf("CUTOUT", false).translucentBlend());
        assertTrue(TerrainDerivedPlan.specOf("TRANSLUCENT", false).translucentBlend());
        assertTrue(TerrainDerivedPlan.specOf("TRANSLUCENT", true).translucentBlend());
    }

    @Test
    @DisplayName("multiDraw 变体与非变体除 location 外参数完全相同")
    void multidrawDiffersOnlyByLocation() {
        for (String layer : TerrainDerivedPlan.LAYER_NAMES) {
            TerrainDerivedPlan.Spec plain = TerrainDerivedPlan.specOf(layer, false);
            TerrainDerivedPlan.Spec multi = TerrainDerivedPlan.specOf(layer, true);
            assertEquals(plain.alphaCutout(), multi.alphaCutout(), layer);
            assertEquals(plain.translucentBlend(), multi.translucentBlend(), layer);
            assertFalse(plain.location().equals(multi.location()), layer);
        }
    }

    @Test
    @DisplayName("未知层名显式抛错/返回空，绝不静默返回默认规格（T11）")
    void unknownLayerIsExplicit() {
        assertThrows(IllegalArgumentException.class, () -> TerrainDerivedPlan.specOf("WIRE", false));
        assertTrue(TerrainDerivedPlan.find("WIRE", false).isEmpty());
        assertTrue(TerrainDerivedPlan.find("SOLID", false).isPresent());
    }

    @Test
    @DisplayName("层名只认原版枚举名（大写）：小写输入显式拒绝，不做宽松归一化")
    void layerNameIsStrictlyVanillaEnumName() {
        // 正例：原版枚举名 → location 用小写形式（读起来与原版 pipeline/solid_terrain 同构）。
        assertEquals(TerrainDerivedPlan.specOf("CUTOUT", false).location(),
                TerrainDerivedPlan.specOf("CUTOUT", false).location());
        assertTrue(TerrainDerivedPlan.specOf("CUTOUT", false).location().endsWith("terrain_cutout"));
        // 反例：小写不被接受 —— 宽松归一化会把「层名拼错」变成静默的管线不匹配（T11）。
        assertThrows(IllegalArgumentException.class, () -> TerrainDerivedPlan.specOf("cutout", false));
        assertTrue(TerrainDerivedPlan.find("cutout", false).isEmpty());
    }

    @Test
    @DisplayName("自定义 uniform 块名与字节数是唯一常量（GAP-004 的绑定契约）")
    void paramsContract() {
        assertEquals("VkDispTerrainParams", TerrainDerivedPlan.PARAMS_UNIFORM);
        // 2 × vec4 = 32 字节（std140）。改了名字却没改 GLSL 块名 = 布局与声明不匹配（X9 不猜）。
        assertEquals(32, TerrainDerivedPlan.PARAMS_BYTES);
    }
}