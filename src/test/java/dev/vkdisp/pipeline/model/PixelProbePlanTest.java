package dev.vkdisp.pipeline.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PixelProbePlan} 的决策正确性 —— 「测哪一槽」的守卫。
 *
 * <p>🔖 <b>这些断言守的是假证据</b>：读一张**包片元根本没写**的附件，
 * 得到的必然是清屏值；而清屏值读出来是「全黑」，
 * 于是日志会报「包片元输出黑」——那是<b>结论反了</b>，不是精度差一点。
 * 期望值全部按实测独立写死（BSL v10.1.8 默认档：{@code declaredOutputSlots=[0,3,6,7]}、
 * {@code outputCount=8}），**不跟随实现**。
 */
class PixelProbePlanTest {

    /** BSL 默认档实测：8 个附件，包片元声明写 0/3/6/7。 */
    private static final List<Integer> BSL_DEFAULT = List.of(0, 3, 6, 7);

    @Test
    @DisplayName("🔖🔖 terrainToMain 档必须剔除槽 0 —— 那一档槽 0 就是主目标视图，不是附件")
    void toMainLaneExcludesSlotZero() {
        // 🔴🔖 本类第一版（写死在 TargetReadback 里）挑的是**槽 1**，而槽 1 在 BSL 默认档
        //   根本不是包的输出（声明写的是 0/3/6/7）⇒ 读到的是清屏值
        //   ⇒ 日志报「colortex1 allZero=true」⇒ 会被读成「包片元输出黑」。
        //   那正是 h31 收尾被推翻、`terrainToMain` 误测槽 0 的同一族假证据。
        PixelProbePlan plan = PixelProbePlan.decide(8, true, BSL_DEFAULT, 0);
        assertEquals(List.of(3, 6, 7), plan.colortexSlots(),
                "terrainToMain 档下槽 0 已是主目标视图；对照只能取确实被写的 3/6/7，"
                        + "绝不能取槽 1（那一槽没有包输出，读到的是清屏值）");
        assertFalse(plan.colortexSlots().contains(0), "槽 0 在该档不是附件，绝不能进待测集合");
        assertFalse(plan.colortexSlots().contains(1), "槽 1 没有包输出，绝不能进待测集合");
        assertTrue(plan.comparable(), "默认档有 3 个可对照槽 ⇒ 必须产出两源对照结论");
        assertTrue(plan.declaredSlotsKnown());
        assertTrue(plan.note().contains("不是附件"),
                "说明里必须出现「不是附件」—— 否则读日志的人无从分辨「没写」与「写了但是黑的」");
    }

    @Test
    @DisplayName("🔖 非 terrainToMain 档测全部被写的槽（逐槽给数字，才能分清「哪一路输出是黑的」）")
    void sideBySideLaneProbesEveryWrittenSlot() {
        PixelProbePlan plan = PixelProbePlan.decide(8, false, BSL_DEFAULT, 0);
        assertEquals(List.of(0, 3, 6, 7), plan.colortexSlots(),
                "主目标是原版画面时，包写的每一槽都值得单独取数 —— "
                        + "「只有 albedo 黑」与「四路都黑」是两个完全不同的结论");
        assertTrue(plan.comparable());
        assertFalse(plan.truncated(), "4 槽正好等于上限，不该截断");
    }

    @Test
    @DisplayName("🔖 附件 1/2/4/5 存在但无包输出 ⇒ 一个都不能进待测集合")
    void unwrittenAttachmentsAreNeverProbed() {
        PixelProbePlan toMain = PixelProbePlan.decide(8, true, BSL_DEFAULT, 0);
        PixelProbePlan side = PixelProbePlan.decide(8, false, BSL_DEFAULT, 0);
        for (int slot : List.of(1, 2, 4, 5)) {
            assertFalse(toMain.colortexSlots().contains(slot),
                    "槽 " + slot + " 无包输出（只有清屏值），绝不能当成包的输出来读");
            assertFalse(side.colortexSlots().contains(slot),
                    "槽 " + slot + " 无包输出（只有清屏值），绝不能当成包的输出来读");
        }
    }

    @Test
    @DisplayName("🔖🔖 包只声明写槽 0 时，terrainToMain 档**明确不产出**对照结论")
    void toMainLaneWithOnlySlotZeroDeclaresNoComparison() {
        PixelProbePlan plan = PixelProbePlan.decide(1, true, List.of(0), 0);
        assertTrue(plan.colortexSlots().isEmpty(), "没有第二个可对照的槽 ⇒ 待测集合必须为空");
        assertFalse(plan.comparable(), "取不到对照源时**不得**声称有对照结论");
        assertTrue(plan.note().contains("不产出"),
                "必须明说「本档不产出两源对照结论」——「沉默」与「没有结论」在日志上无法区分");
    }

    @Test
    @DisplayName("🔖🔖 槽位未知（未接包片元）⇒ 不产出对照结论，且不假装知道")
    void unknownSlotsDeclaresNoComparison() {
        // 🔖 空列表的含义是「**不知道**」，不是「全都写了」——
        //   把它读成「0..N-1 全被写」就退化成按附件下标猜槽，
        //   那正是 DrawBuffersSlotAdapter 要消灭的那一类静默绑错槽。
        for (List<Integer> unknown : java.util.Arrays.asList(List.<Integer>of(), null)) {
            PixelProbePlan plan = PixelProbePlan.decide(8, false, unknown, 2);
            assertFalse(plan.comparable(),
                    "槽位未知时不得产出对照结论（读了也不能声称那是包的输出）：" + unknown);
            assertFalse(plan.declaredSlotsKnown());
        }
    }

    @Test
    @DisplayName("🔖 槽位未知时仍可把 viewSlot 当**原始观测面**回读（只是不产出对照结论）")
    void unknownSlotsStillReadsViewSlotAsRawObservation() {
        PixelProbePlan plan = PixelProbePlan.decide(8, false, List.of(), 2);
        assertEquals(List.of(2), plan.colortexSlots(),
                "viewSlot 在范围内时可作为原始观测面回读（数字仍是事实）");
        assertFalse(plan.comparable(), "但必须明确不产出对照结论");
        assertTrue(plan.note().contains("原始观测面"), "说明要讲清「为什么测它却不给结论」");
    }

    @Test
    @DisplayName("🔖 terrainToMain + 槽位未知 ⇒ 一个 colortex 都不读")
    void toMainLaneWithUnknownSlotsReadsNothing() {
        PixelProbePlan plan = PixelProbePlan.decide(8, true, List.of(), 2);
        assertTrue(plan.colortexSlots().isEmpty(),
                "该档下连槽 0 都是主目标视图；在不知道包写哪些槽时读任何 colortex 都可能是清屏值");
        assertFalse(plan.comparable());
        assertTrue(plan.note().contains("不产出"), "必须明说不产出对照结论");
    }

    @Test
    @DisplayName("🔖 无附件时不得产出任何数字（这与「数字是 0」是两件事）")
    void noAttachmentsProducesNoSlots() {
        PixelProbePlan plan = PixelProbePlan.decide(0, false, BSL_DEFAULT, 0);
        assertTrue(plan.colortexSlots().isEmpty());
        assertFalse(plan.comparable());
        assertEquals(0, plan.comparable() ? 1 : 0);
        assertTrue(plan.note().contains("0"), "说明要写清附件数，让「没数字」与「数字是 0」能分开");
    }

    @Test
    @DisplayName("🔖 声明槽越界 ⇒ 明确不产出对照结论（契约与 pass 已不一致，属 X42 响亮失败）")
    void outOfRangeDeclaredSlotIsNotSilentlyClamped() {
        // 🔖 静默夹取会让「契约要 9 个附件、pass 只有 8 个」这件事看起来还在正常工作
        PixelProbePlan plan = PixelProbePlan.decide(8, false, List.of(0, 9), 0);
        assertTrue(plan.colortexSlots().isEmpty(), "越界的声明不能进待测集合");
        assertFalse(plan.comparable());
        assertTrue(plan.note().contains("X42"), "必须点名这是 X42（响亮失败），不是「没得测」");
    }

    @Test
    @DisplayName("🔖 超上限时截断并**点名**被丢掉的槽（显存预算不许悄悄吞掉观测面）")
    void truncationIsSelfReported() {
        // OF 语义允许最多 8 个输出槽（BSL + MCBL_SS 实测会要 9 个，已被显式拒绝）
        PixelProbePlan plan = PixelProbePlan.decide(8, false, List.of(0, 1, 2, 3, 4, 5, 6, 7), 0);
        assertEquals(PixelProbePlan.MAX_COLORTEX_PROBES, plan.colortexSlots().size(),
                "待测槽数受 MAX_COLORTEX_PROBES 封顶（显存预算）");
        assertTrue(plan.truncated(), "发生截断必须自报");
        assertTrue(plan.note().contains("未测"), "note 必须点名哪些槽没测");
        assertFalse(plan.note().contains("未测 " + plan.colortexSlots()),
                "被保留的槽不该出现在「未测」清单里");
    }

    @Test
    @DisplayName("🔖 viewSlot 不是被写的槽时要提醒（那张图同样不是包的输出）")
    void unwrittenViewSlotIsFlagged() {
        // 🔖 mrt.viewSlot 只影响诊断视图 blit 显示哪一张；把它配成没被写的槽，
        //   显示出来的那张图是清屏色 —— 会被当成「包输出是黑的」。
        PixelProbePlan plan = PixelProbePlan.decide(8, false, BSL_DEFAULT, 1);
        assertTrue(plan.note().contains("mrt.viewSlot=1"),
                "必须点名配置值，否则读日志的人只会看到「viewSlot=1」却不知道它没有包输出");
    }

    @Test
    @DisplayName("🔖 说明不许为空（空白说明 = 读日志的人无从判断有没有结论）")
    void noteMustNotBeBlank() {
        assertThrows(IllegalArgumentException.class,
                () -> new PixelProbePlan(List.of(), true, true, false, "   "));
        assertThrows(IllegalArgumentException.class,
                () -> new PixelProbePlan(List.of(), true, true, false, null));
    }

    @Test
    @DisplayName("🔖 待测槽必须去重升序（同一个槽出现两次 = 两个缓冲抢一张图）")
    void slotsAreDedupedAndSorted() {
        PixelProbePlan plan = PixelProbePlan.decide(8, false, List.of(7, 3, 3, 0), 0);
        assertEquals(List.of(0, 3, 7), plan.colortexSlots());
    }
}