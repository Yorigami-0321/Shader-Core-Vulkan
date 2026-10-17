package dev.vkdisp.pack.model;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 【参考调研】{@code blend.<program>} 指令的读法（移植件）
 * 0. 合规核对（第 0 步闸门）：
 *    上游 = Vitrail {@code dev/vitrail/pack/model/BlendMode.java:1-71} @ 4a45f44bbe160ddae94da945a92e791cd96d1ec5
 *    （LGPL-3.0，本机检出 {@code D:/Code/Minecraft/Vitrail-Shaders}，读取日期 2026-10-17）；
 *    本项目同为 LGPL-3.0-or-later ⇒ 同族可搬（{@code docs/07-CONSTRAINTS.md} L13）。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以，逐文件进 {@code NOTICE.md} 移植台账（§〇 P1）。
 *    → 例外条款：无；上游该文件未声明第三方来源（GL 因子名是事实性标识）。
 * 1. 官方/主实现：OptiFine 的 {@code blend.<program>=<srcRgb> <dstRgb> <srcAlpha> <dstAlpha>} 与
 *    {@code off}；Iris 同语义（LGPL-3.0，本项目不读其实现，上游已把它读成这张表）。
 * 3. 我们的差异点：包名与缩进（上游 tab ⇒ 本仓四空格）+ 本注释块；**语义与词数规则逐字保留**，
 *    含「分隔符是单个空格而非空白串」这条（双空格 ⇒ 词数不为 4 ⇒ 整条丢弃）。
 * 4. 许可证核对结论：本项目 LGPL-3.0-or-later；本文件为 Vitrail 的移植件，改动见上。
 * 5. 性能基线：❄️ 冷路径（包加载期读一次），不做优化（18-PARALLEL §7.7）。
 *
 * <p>包写成的样子就存成什么：四个因子**名字**，不是渲染器的任何东西 —— 这个包不碰引擎 API，
 * 所以整套可以脱离游戏对着包语料跑。名字是 GL 的、后端枚举拼法一致，所以对侧是一次查表而不是一张对照表。
 *
 * <p>OptiFine 形态是 {@code blend.<program>=<srcRgb> <dstRgb> <srcAlpha> <dstAlpha>}，
 * 或者一个必须完全不混合的程序写 {@code off}。也接受两个因子，含义即 {@code glBlendFunc}：
 * 同一对同时用于颜色与 alpha。
 *
 * <p>逐缓冲形态 {@code blend.<program>.<buffer>} 读进同一个 record（一条指令一个），由计划层在
 * 「该程序写的每个目标都同意」时折成该程序唯一的那个混合函数。一条管线对它写的每个目标只带
 * 一个混合函数 ⇒ 一个程序对两个目标要两种函数的包，会在计划的注记里被点名，而不是半兑现。
 */
public record BlendMode(boolean off, String srcRgb, String dstRgb, String srcAlpha, String dstAlpha) {

    /** 包什么都没说时程序的混合，即游戏本来会用的那个值。 */
    public static final BlendMode OFF = new BlendMode(true, "", "", "", "");

    /**
     * 读一条指令的值；本引擎表达不了的形态返回 empty。
     *
     * <p>🔴 empty 不等于 {@link #OFF}，区别要命：off 是包在说「别混合」，
     * empty 是引擎在说「我没读懂」，后者由调用方上报，而不是当成前两者里的任一个静默处理。
     */
    public static Optional<BlendMode> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }

        String trimmed = value.trim();
        if (trimmed.equalsIgnoreCase("off")) {
            return Optional.of(OFF);
        }

        // 一个空格，Iris 给混合指令的分隔符，而不是一段空白：两个因子之间打两个空格，
        // 中间就多出一个空词 ⇒ 值不再是四个因子 ⇒ 这条覆盖被丢弃。
        List<String> factors = List.of(trimmed.toUpperCase(Locale.ROOT).split(" ", -1));
        if (factors.size() == 4) {
            return Optional.of(new BlendMode(false, factors.get(0), factors.get(1), factors.get(2),
                    factors.get(3)));
        }

        // glBlendFunc 的两参形态：一对，颜色与 alpha 同用。
        if (factors.size() == 2) {
            return Optional.of(new BlendMode(false, factors.get(0), factors.get(1), factors.get(0),
                    factors.get(1)));
        }

        return Optional.empty();
    }

    /** 按读包那行原文的预期顺序给出四个名字。 */
    @Override
    public String toString() {
        return this.off ? "off" : this.srcRgb + " " + this.dstRgb + " " + this.srcAlpha + " "
                + this.dstAlpha;
    }
}
