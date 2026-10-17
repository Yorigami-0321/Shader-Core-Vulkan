package dev.vkdisp.bridge;

import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import dev.vkdisp.pack.model.BlendMode;
import java.util.Locale;
import java.util.Optional;

/**
 * 【参考调研】包写的混合因子名 → 官方 {@code BlendFunction}
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 移植件 {@code pack/model/BlendMode}（Vitrail {@code 4a45f44}，LGPL-3.0，已进
 *    {@code NOTICE.md} 台账）—— 它把「四个因子名」原样存下来，转换只许发生在本类；
 *    ② 官方 API 事实（不受版权保护）：{@code renderpearl/api/pipeline/BlendFunction.java:26-33}
 *    有 {@code BlendFunction(srcColor, dstColor, srcAlpha, dstAlpha)} 四因子构造，
 *    {@code BlendFactor} 的名字集合就是包写的那些（{@code SRC_ALPHA}/{@code ONE}/{@code ZERO}/
 *    {@code ONE_MINUS_*}/{@code DST_*}/{@code SRC_COLOR}/{@code CONSTANT_*}），
 *    {@code BlendOp} 是 {@code ADD}/{@code SUBTRACT}/{@code REVERSE_SUBTRACT}/{@code MIN}/{@code MAX}。
 *    逐字取自本机 {@code mergeWithSources_*_output.jar}，读取日期 2026-10-17。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以（本文件独立实现，只采纳公开 API 事实与名字）
 *    → 例外条款：无；不含任何 GPL / AGPL / ARR 代码
 * 1. 官方/主实现：Iris/OptiFine 的 {@code blend.<程序>} 语义即四个 GL 因子；Vitrail 也是
 *    「存名字、在对侧一次查表」（其 {@code BlendMode} 文件头明写这个设计）。
 * 3. 我们的差异点：① 认不出来的因子名**整条拒收并点名**（返回 {@link Status#UNRECOGNIZED_FACTOR}），
 *    不回落成 TRANSLUCENT —— 猜错的混合是画面上一块半透明糊，比不混合更难查；
 *    ② 只做名字→枚举，不建管线、不碰附件（混合真正落地要等「每程序一套管线」的骨架，见
 *    {@code 19} §7-9 的三条架构级阻碍表）；③ 位置在这不是巧合：{@code 07} T5 规定业务包不许
 *    import 原版渲染类型，只有 bridge 层能碰 {@code renderpearl}。
 * 4. 许可证核对结论：本项目 LGPL-3.0-or-later；本文件独立编写，消费的是移植件的公开 API。
 * 5. 性能基线：❄️ 冷路径（装配期一次），只求清晰（18-PARALLEL §7.7）。
 */
public final class PackBlendState {

    /** 一条 {@code blend.<程序>} 的三种结局。 */
    public enum Status {
        /** 包写了 {@code off}：不混合（官方形态 = 颜色目标不带 BlendFunction）。 */
        OFF,
        /** 四个名字都认得，已落成官方 BlendFunction。 */
        MAPPED,
        /** 至少一个因子名认不出 ⇒ 整条不应用，且必须点名。 */
        UNRECOGNIZED_FACTOR,
        /** 值本身不是「四个（或两个）因子」形态：{@code BlendMode.parse} 就没通过。 */
        MALFORMED
    }

    /**
     * @param status 结局
     * @param blend  仅 {@link Status#MAPPED} 时非 null
     * @param detail 拒收时点名是哪个名字／原文是什么
     */
    public record Outcome(Status status, BlendFunction blend, String detail) {

        /** 该结论能不能拿去设颜色目标状态。 */
        public boolean applies() {
            return this.status == Status.OFF || this.status == Status.MAPPED;
        }
    }

    private PackBlendState() {
    }

    /**
     * 读一条已解析成 {@link BlendMode} 的声明。
     *
     * @param mode 包的声明；{@code null} 表示这条根本没解析成形 ⇒ {@link Status#MALFORMED}
     */
    public static Outcome of(BlendMode mode) {
        return of(mode, "");
    }

    /** 同上，{@code raw} 只用于拒收时的点名（日志要能指回包写的那一行）。 */
    public static Outcome of(BlendMode mode, String raw) {
        if (mode == null) {
            return new Outcome(Status.MALFORMED, null, raw);
        }
        if (mode.off()) {
            return new Outcome(Status.OFF, null, "off");
        }
        Optional<BlendFactor> srcColor = factor(mode.srcRgb());
        Optional<BlendFactor> dstColor = factor(mode.dstRgb());
        Optional<BlendFactor> srcAlpha = factor(mode.srcAlpha());
        Optional<BlendFactor> dstAlpha = factor(mode.dstAlpha());
        if (srcColor.isEmpty() || dstColor.isEmpty() || srcAlpha.isEmpty() || dstAlpha.isEmpty()) {
            return new Outcome(Status.UNRECOGNIZED_FACTOR, null, firstUnknown(mode));
        }
        return new Outcome(Status.MAPPED,
                new BlendFunction(srcColor.get(), dstColor.get(), srcAlpha.get(), dstAlpha.get()), null);
    }

    /**
     * 名字→枚举：大写 + 去前导 {@code GL_}（有的包按 GL 常量全名写）。
     * 认不出返回空，由调用方决定怎么点名 —— 这里绝不猜。
     */
    public static Optional<BlendFactor> factor(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String token = name.trim().toUpperCase(Locale.ROOT);
        if (token.startsWith("GL_")) {
            token = token.substring(3);
        }
        if (token.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(BlendFactor.valueOf(token));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String firstUnknown(BlendMode mode) {
        for (String token : new String[]{mode.srcRgb(), mode.dstRgb(), mode.srcAlpha(), mode.dstAlpha()}) {
            if (factor(token).isEmpty()) {
                return token;
            }
        }
        return "";
    }
}
