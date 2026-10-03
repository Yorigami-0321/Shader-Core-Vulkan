package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】GAP-003 多附件规格表单测 / 纯数据断言（无原版类型、无 GPU 依赖）
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓库 `docs/13-GAP-REGISTRY.md` GAP-003 条目
 *    （albedo / normal+lightmap / material 三槽）与原版 26.3 `ColorTargetState.MAX_COLOR_TARGETS = 8`
 *    这条**事实性上限**（Mojang EULA，只取数值，不搬运代码）。
 *    → 可并入本项目（MIT）：本文件只断言参数，不含被参考方代码。
 * 1. 官方/主实现：同被测类（纯数据表）。
 * 2. 备选：无（本就是单测）。
 * 3. 我们的差异点：期望值**独立于实现写死** —— 这样「实现改错了槽数/指纹」会当场变红，
 *    而不是跟着实现一起错。指纹的可量化性由 `fingerprintRIsDistinct` 单独锁住。
 * 4. 许可证核对：本项目 MIT。
 * 5. 性能基线：❄️ 单测，不适用。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link MrtPlan} 的规格表正确性 —— GAP-003「三槽各自拿到可区分内容」的前置守卫。 */
class MrtPlanTest {

    @Test
    @DisplayName("槽位数 = 3，且槽位下标与 OF location 逐条对应")
    void slotCountMatchesOfGbufferRoles() {
        assertEquals(3, MrtPlan.SLOT_COUNT);
        List<MrtPlan.SlotSpec> slots = MrtPlan.slots();
        assertEquals(3, slots.size());
        for (int i = 0; i < slots.size(); i++) {
            assertEquals(i, slots.get(i).slot(), "槽位下标必须等于附件下标");
        }
        assertEquals("colortex0/albedo", slots.get(0).role());
        assertEquals("colortex1/normal+lightmap", slots.get(1).role());
        assertEquals("colortex2/material", slots.get(2).role());
    }

    @Test
    @DisplayName("🔖 槽位指纹逐槽不同且落在 [0,1) —— 这是「分槽生效」可量化判读的前提")
    void fingerprintRIsDistinct() {
        Set<Float> seen = new HashSet<>();
        for (int slot = 0; slot < MrtPlan.SLOT_COUNT; slot++) {
            float fingerprint = MrtPlan.fingerprintR(slot);
            assertTrue(fingerprint >= 0.0F && fingerprint < 1.0F, "指纹必须在 [0,1)：" + fingerprint);
            assertTrue(seen.add(fingerprint), "指纹重复，槽位就区分不出来了：" + fingerprint);
        }
        assertEquals(0.0F, MrtPlan.fingerprintR(0));
        assertNotEquals(MrtPlan.fingerprintR(0), MrtPlan.fingerprintR(1));
        assertNotEquals(MrtPlan.fingerprintR(1), MrtPlan.fingerprintR(2));
    }

    @Test
    @DisplayName("槽位格式统一 RGBA8_UNORM（本轮只验「分槽」，不验格式兼容）")
    void formatsAreUniform() {
        for (MrtPlan.SlotSpec spec : MrtPlan.slots()) {
            assertEquals("RGBA8_UNORM", spec.format());
        }
    }

    @Test
    @DisplayName("设备能力收敛：低于计划数时降档，但至少保留 1")
    void clampSlotsRespectsDeviceLimit() {
        assertEquals(3, MrtPlan.clampSlots(8));
        assertEquals(3, MrtPlan.clampSlots(4));
        assertEquals(3, MrtPlan.clampSlots(3));
        assertEquals(2, MrtPlan.clampSlots(2));
        assertEquals(1, MrtPlan.clampSlots(1));
        // 🔖 荒谬值不能变成 0 附件 —— 那会让 RenderPassDescriptor 没有任何颜色附件。
        assertEquals(1, MrtPlan.clampSlots(0));
        assertEquals(1, MrtPlan.clampSlots(-5));
    }

    @Test
    @DisplayName("视图槽位越界显式抛错，绝不静默夹取（X9 不猜）")
    void viewSlotOutOfRangeThrows() {
        assertEquals(0, MrtPlan.requireViewSlot(0, 3));
        assertEquals(2, MrtPlan.requireViewSlot(2, 3));
        assertThrows(IllegalArgumentException.class, () -> MrtPlan.requireViewSlot(3, 3));
        assertThrows(IllegalArgumentException.class, () -> MrtPlan.requireViewSlot(-1, 3));
        // 设备只给 2 槽时，槽 2 也必须报错（而不是悄悄看槽 0 或槽 1）。
        assertEquals(1, MrtPlan.requireViewSlot(1, 2));
        assertThrows(IllegalArgumentException.class, () -> MrtPlan.requireViewSlot(2, 2));
    }

    @Test
    @DisplayName("槽位下标越界的 SlotSpec 构造显式抛错")
    void slotSpecRejectsOutOfRangeSlot() {
        assertThrows(IllegalArgumentException.class,
                () -> new MrtPlan.SlotSpec(-1, "x", "RGBA8_UNORM", 0.0F));
        assertThrows(IllegalArgumentException.class,
                () -> new MrtPlan.SlotSpec(MrtPlan.HARD_MAX_SLOTS, "x", "RGBA8_UNORM", 0.0F));
        // 上界本身合法（原版 MAX_COLOR_TARGETS = 8 ⇒ 下标 0..7）。
        assertEquals(7, new MrtPlan.SlotSpec(7, "x", "RGBA8_UNORM", 1.0F).slot());
    }

    @Test
    @DisplayName("计划槽数不超过原版硬上限")
    void planRespectsHardMax() {
        assertTrue(MrtPlan.SLOT_COUNT <= MrtPlan.HARD_MAX_SLOTS,
                "计划槽数不得超过原版 ColorTargetState.MAX_COLOR_TARGETS");
        assertEquals(8, MrtPlan.HARD_MAX_SLOTS, "原版 ColorTargetState.MAX_COLOR_TARGETS = 8");
    }
}