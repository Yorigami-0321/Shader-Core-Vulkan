package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】两源读数的**判读**（同一组数字在不同档位下含义相反 —— 本类的存在理由）
 * 0. 合规核对（第 0 步闸门）：
 *    参考对象 = ① 本仓库自有的 {@code dev.vkdisp.bridge.TargetReadback}（被本类取代的判读段，
 *    MIT 自有代码）；② BSL v10.1.8 {@code shaders/program/gbuffers_terrain.glsl} 424–442 行的
 *    DRAWBUFFERS 活分支（本仓库自有实测；槽位语义取自 OptiFine 公开的注释约定）。
 *    全部为仓库内自有代码与自有实测事实，不受版权保护，不搬运任何第三方或 Mojang 源码。
 *    → 能否并入本项目（MIT）：可以（本文件为独立编写的纯 Java 判读类）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码，也不含任何 Mojang 着色器文本
 * 1. 官方/主实现：无（Vulkan / 原版里都不存在「两个读数怎么判读」这个概念）。
 * 2. 备选：
 *    <ul>
 *      <li>① 沿用一个 verdict 名表、不看档位 —— <b>否决（本类的成因，2026-10-05 实测暴露）</b>：
 *          四分判定表在 {@code terrainToMain=false} 下是对的（主目标 = 原版画面），
 *          在 {@code terrainToMain=true} 下<b>整张表反过来</b> ——
 *          那一档「主目标」就是我方 pass 的<b>附件 0</b>（包的 albedo）。
 *          实测原文：{@code main#2 allZero=true} 与 {@code colortex3#2 meanRGB=(0,255,0)} 同时成立
 *          ⇒ 标签 {@code NOT_ON_MAIN}（"地形 draw 没落到主目标"）是<b>误诊</b>：
 *          draw 恰恰<b>落到了</b>主目标，只是那里的 albedo 是黑的。
 *          按那个标签去查接线，会把下一轮取证引向一个不存在的 bug。</li>
 *      <li>② 只在 toMain 档改文案、其余不动 —— 否决：那等于把「档位影响判读」这件事散落成
 *          两处 if；下次再加一个档位就会漏。判读必须<b>按档位整体参数化</b>。</li>
 *      <li>③ 不判读、只把四个布尔量打进日志 —— 否决：那把「数字」推给读日志的人去组合，
 *          而本项目反复吃亏的正是「组合这一步」被做错（h31 收尾被推翻那一族）。</li>
 *    </ul>
 * 3. 我们的差异点：判读是<b>纯函数</b>，输入 = （档位、主目标是否全黑、该槽是否全黑、槽标签），
 *    输出 = （结论名 + 一句可直接进日志的解释 + 严重级）。⇒ 可单测，且**档位错配会当场变红**。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 冷路径（每探针轮、每槽一次；默认 300 帧一轮）。
 */

/**
 * 两个像素读数的判读结论（纯逻辑，零 GPU 依赖 ⇒ 可单测）。
 *
 * <p><b>为什么判读必须知道档位</b>：这四个布尔组合在不同档位下<b>含义相反</b>。
 * 关键在于「主目标是谁」：
 * <pre>
 *   terrainToMain=false ⇒ 主目标是**原版画面**（我方 pass 压根不写它）
 *       主黑 + colortex 有内容 ⇒ draw 没落到主目标（落点/接线问题）
 *   terrainToMain=true  ⇒ 主目标就是我方 pass 的**附件 0**，即包的 albedo
 *       主黑 + colortex 有内容 ⇒ draw **落到了**主目标，只是 albedo ≡ 0
 *                              （= GAP-008 本体，与上面那句<b>正好相反</b>）
 * </pre>
 * ⇒ 不带档位地复用同一张标签表，会把「已经定位到 GAP-008」读成「有个接线 bug」。
 */
public record PixelProbeVerdict(String id, Severity severity, String meaning) {

    /** 严重级：决定日志用 WARN 还是 INFO（不是「有多严重」，而是「读的人有多容易读错」）。 */
    public enum Severity {
        /** 需要立刻停下当前假设：结论会推翻正在进行的取证方向。 */
        RED,
        /** 状态变化，不改变方向。 */
        YELLOW,
        /** 一切正常。 */
        GREEN
    }

    /** toMain=false 档：主黑 + 槽有内容（draw 没落到主目标）。 */
    public static final String NOT_ON_MAIN = "NOT_ON_MAIN";
    /** toMain=false 档：主有内容 + 槽黑（我方 pass 那一路输出是黑的）。 */
    public static final String SLOT_BLACK = "SLOT_BLACK";
    /** toMain=false 档：两者都黑。 */
    public static final String BOTH_BLACK = "BOTH_BLACK";
    /** toMain=false 档：两者都有内容。 */
    public static final String BOTH_HAVE_CONTENT = "BOTH_HAVE_CONTENT";
    /** 🔖🔖 toMain 档专属：albedo 黑、其余被写槽正常 ⇒ **GAP-008 本体已定位**。 */
    public static final String ALBEDO_BLACK_OTHERS_OK = "ALBEDO_BLACK_OTHERS_OK";
    /** toMain 档：albedo 与该槽都黑 ⇒ 没有任何一路有内容（可能整条 draw 没出片元）。 */
    public static final String ALL_BLACK = "ALL_BLACK";
    /** toMain 档：albedo 有内容、该槽黑 ⇒ 这一路输出是黑的（反过来的那一格）。 */
    public static final String SLOT_BLACK_ALBEDO_OK = "SLOT_BLACK_ALBEDO_OK";

    /** 归一：解释不许为空白（空白 = 读日志的人无从判断该信什么）。 */
    public PixelProbeVerdict {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("vkdisp: 判读结论名不许为空");
        }
        if (meaning == null || meaning.isBlank()) {
            throw new IllegalArgumentException("vkdisp: 判读解释不许为空（否则等于让读的人自己组合）");
        }
        severity = severity == null ? Severity.YELLOW : severity;
    }

    /**
     * 判读。
     *
     * @param toMain   {@code mrt.terrainToMain}（决定「主目标是谁」）
     * @param mainZero 主目标逐像素全黑
     * @param slotZero 该 colortex 槽逐像素全黑
     * @param slot     槽标签（如 {@code colortex3}；进解释文本）
     */
    public static PixelProbeVerdict of(boolean toMain, boolean mainZero, boolean slotZero, String slot) {
        String label = slot == null || slot.isBlank() ? "该槽" : slot;
        if (toMain) {
            // 🔖🔖 档位敏感：主目标 = 附件 0 = 包的 albedo。
            if (mainZero && !slotZero) {
                return new PixelProbeVerdict(ALBEDO_BLACK_OTHERS_OK, Severity.RED,
                        "mrt.terrainToMain=true 档下**主目标就是我方 pass 的附件 0（包的 albedo）**："
                                + "主目标逐像素全黑而 " + label + " 有内容 ⇒ 地形 draw **确实落到了**主目标，"
                                + "只是 albedo ≡ 0，而同一片元的其余输出（" + label + "）形态正常。"
                                + "⇒ 这是 GAP-008 的**定义形态**（输出黑），不是落点/接线问题。"
                                + "⚠️ 本档下若按 NOT_ON_MAIN 读，会把已定位的 GAP-008 误当成接线 bug 去查");
            }
            if (mainZero && slotZero) {
                return new PixelProbeVerdict(ALL_BLACK, Severity.RED,
                        "mrt.terrainToMain=true 档下主目标与 " + label + " 都逐像素全黑"
                                + " ⇒ 没有任何一路输出有内容：可能是整条地形 draw 一个片元都没出"
                                + "（几何/深度/管线），也可能所有输出都是黑的。"
                                + "⚠️ 这两件事本读数**分不开** —— 需要 fullscreenProbe 那个二分");
            }
            if (!mainZero && slotZero) {
                return new PixelProbeVerdict(SLOT_BLACK_ALBEDO_OK, Severity.YELLOW,
                        "mrt.terrainToMain=true 档下 albedo（主目标）有内容而 " + label
                                + " 逐像素全黑 ⇒ 反过来的那一格：albedo 正常、这一路输出是黑的");
            }
            return new PixelProbeVerdict(BOTH_HAVE_CONTENT, Severity.GREEN,
                    "mrt.terrainToMain=true 档下 albedo 与 " + label + " 都有内容 ⇒ 该路输出链路正常");
        }
        if (mainZero && !slotZero) {
            return new PixelProbeVerdict(NOT_ON_MAIN, Severity.RED,
                    "主目标（**原版画面**，我方 pass 不写它）逐像素全黑而 " + label + " 有内容"
                            + " ⇒ 地形 draw **没有落到主目标**（落点/接线问题，不是着色问题）。"
                            + "这条把「输出黑」与「没落到主目标」分开了（h42 §4.3 登记的未分辨因素）");
        }
        if (!mainZero && slotZero) {
            return new PixelProbeVerdict(SLOT_BLACK, Severity.YELLOW,
                    "主目标（**原版画面**，看着「正常」不构成任何证据 —— h31 的收尾就是这么被推翻的）"
                            + "有内容，而 " + label + " 逐像素全黑 ⇒ 我方 pass 写进 " + label
                            + " 的那一路包片元输出是黑的");
        }
        if (mainZero && slotZero) {
            return new PixelProbeVerdict(BOTH_BLACK, Severity.YELLOW,
                    "主目标与 " + label + " 都逐像素全黑"
                            + " ⇒ 该路输出黑**且**主目标也是黑的（两种成因本读数分不开）");
        }
        return new PixelProbeVerdict(BOTH_HAVE_CONTENT, Severity.GREEN,
                "主目标与 " + label + " 都有内容 ⇒ 该路输出链路正常；若画面仍不对，问题在两者之后（合成/上屏）");
    }
}