package dev.vkdisp.pipeline.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PixelProbeVerdict} 的判读正确性 —— 「同一组数字在不同档位下含义相反」的守卫。
 *
 * <p>🔖 <b>这些断言守的是误诊</b>：把「已经定位到 GAP-008」读成「有个接线 bug 去查」，
 * 会让下一轮取证跑在一个不存在的方向上，而日志看起来完全正常。
 */
class PixelProbeVerdictTest {

    @Test
    @DisplayName("🔖🔖🔖 toMain 档「主黑 + 槽正常」= GAP-008 本体，**不是**落点问题")
    void toMainLaneBlackMainWithContentSlotIsGap008() {
        // 🔴🔖 本类的成因：实测 `main#2 allZero=true` 与 `colortex3#2 meanRGB=(0,255,0)` 同时成立。
        //   terrainToMain=true 时主目标就是我方 pass 的**附件 0**（包的 albedo），
        //   所以 draw **落到了**主目标 —— 那里只是 albedo ≡ 0。
        //   旧标签表在这一格会给出 NOT_ON_MAIN（"地形 draw 没落到主目标"）= 误诊。
        PixelProbeVerdict verdict = PixelProbeVerdict.of(true, true, false, "colortex3");
        assertEquals(PixelProbeVerdict.ALBEDO_BLACK_OTHERS_OK, verdict.id(),
                "toMain 档主黑 + 槽有内容 = albedo ≡ 0 而其余输出正常 = GAP-008 的定义形态");
        assertEquals(PixelProbeVerdict.Severity.RED, verdict.severity(),
                "RED：它会推翻正在进行的取证方向（别再去查接线）");
        assertTrue(verdict.meaning().contains("落到了"),
                "解释必须明确说 draw **落到了**主目标 —— 否则读的人还是会去查落点");
        assertNotEquals(PixelProbeVerdict.NOT_ON_MAIN, verdict.id(),
                "绝不能复用 toMain=false 档的标签（那一档主目标是原版画面，语义相反）");
    }

    @Test
    @DisplayName("🔖🔖 toMain=false 档「主黑 + 槽正常」= 真的没落到主目标（与上一条相反）")
    void sideBySideLaneBlackMainWithContentSlotIsLandingProblem() {
        // 🔖 同一组布尔量，档位不同 ⇒ 结论相反。这正是判读必须带档位的理由。
        PixelProbeVerdict verdict = PixelProbeVerdict.of(false, true, false, "colortex0");
        assertEquals(PixelProbeVerdict.NOT_ON_MAIN, verdict.id(),
                "toMain=false 时主目标是原版画面（我方 pass 不写它）⇒ 主黑 = draw 没落到主目标");
        assertEquals(PixelProbeVerdict.Severity.RED, verdict.severity());
        assertTrue(verdict.meaning().contains("原版画面"),
                "解释必须点明主目标此刻是原版画面 —— 看着「正常」不构成任何证据（h31 的教训）");
    }

    @Test
    @DisplayName("🔖 两种档位下四个布尔组合都各有结论，且两档的标签集不重叠")
    void bothLanesCoverAllFourCombinations() {
        String[] labelsToMain = new String[4];
        String[] labelsSide = new String[4];
        int i = 0;
        for (boolean mainZero : new boolean[] {true, false}) {
            for (boolean slotZero : new boolean[] {true, false}) {
                labelsToMain[i] = PixelProbeVerdict.of(true, mainZero, slotZero, "colortex3").id();
                labelsSide[i] = PixelProbeVerdict.of(false, mainZero, slotZero, "colortex3").id();
                i++;
            }
        }
        // 🔖 四格必须**互不相同**：漏掉一格 ⇒ 某一类现象被读成另一类（本项目头号坑）。
        assertEquals(4, java.util.Set.of(labelsToMain).size(),
                "toMain 档四格结论必须互不相同：" + java.util.Arrays.toString(labelsToMain));
        assertEquals(4, java.util.Set.of(labelsSide).size(),
                "toMain=false 档四格结论必须互不相同：" + java.util.Arrays.toString(labelsSide));
        // 🔖 两档只在「都有内容」这一格上同名 —— 其余三格语义相反，绝不能共用标签。
        assertEquals(PixelProbeVerdict.BOTH_HAVE_CONTENT, labelsToMain[3]);
        assertEquals(PixelProbeVerdict.BOTH_HAVE_CONTENT, labelsSide[3]);
        for (int k = 0; k < 3; k++) {
            assertNotEquals(labelsToMain[k], labelsSide[k],
                    "toMain 两档在第 " + k + " 格上共用了标签 ⇒ 语义会互相误读");
        }
    }

    @Test
    @DisplayName("🔖🔖 toMain 档全黑时明说「本读数分不开两种成因」")
    void toMainLaneAllBlackAdmitsAmbiguity() {
        PixelProbeVerdict verdict = PixelProbeVerdict.of(true, true, true, "colortex3");
        assertEquals(PixelProbeVerdict.ALL_BLACK, verdict.id());
        assertTrue(verdict.meaning().contains("分不开"),
                "「一个片元都没出」与「所有输出都是黑的」是两件事，"
                        + "读不出来时必须承认读不出来，而不是挑一个说得出口的");
    }

    @Test
    @DisplayName("🔖 toMain 档 albedo 正常而某槽黑 = 反过来的那一格")
    void toMainLaneSlotBlackWithContentAlbedo() {
        PixelProbeVerdict verdict = PixelProbeVerdict.of(true, false, true, "colortex6");
        assertEquals(PixelProbeVerdict.SLOT_BLACK_ALBEDO_OK, verdict.id(),
                "albedo 有内容而该槽黑 ⇒ 与 SLOT_BLACK 是不同的一格（主目标身份相反）");
    }

    @Test
    @DisplayName("🔖 槽标签为 null/空白时用中性措辞，不抛也不产出「null」这种读不出来的标签")
    void blankSlotLabelGetsNeutralWording() {
        for (String label : new String[] {null, "", "   "}) {
            PixelProbeVerdict verdict = PixelProbeVerdict.of(false, false, true, label);
            assertTrue(verdict.meaning().contains("该槽"),
                    "标签缺失时措辞必须是中性的「该槽」，不能留下 null/空白：" + label);
            assertTrue(!verdict.meaning().contains("null"), "解释里不能出现字面 null");
        }
    }

    @Test
    @DisplayName("🔖 结论名与解释都不许为空（空白 = 让读的人自己组合）")
    void blankIdOrMeaningRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new PixelProbeVerdict("  ", PixelProbeVerdict.Severity.RED, "x"));
        assertThrows(IllegalArgumentException.class,
                () -> new PixelProbeVerdict("X", PixelProbeVerdict.Severity.RED, " "));
    }
}