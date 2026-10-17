package dev.vkdisp.pack.model;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;

/**
 * 【参考调研】程序被画在什么 alpha 测试之下（移植件）
 * 0. 合规核对（第 0 步闸门）：
 *    上游 = Vitrail {@code dev/vitrail/pack/model/AlphaTest.java:1-138} @ 4a45f44…（LGPL-3.0，
 *    读取日期 2026-10-17）；阈值对（{@code ALPHA_CUTOUT_THRESHOLD_*}）与「取被问的名字而不是服务的文件」
 *    两处口径由上游点名 Iris / 原版 {@code RenderPipelines} ⇒ 台账记 Vitrail 一条，引用的是原版事实。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以，进 {@code NOTICE.md} 移植台账（§〇 P1）。
 * 3. 我们的差异点：包名/缩进/文件头；四个默认常量、八个比较名、{@code GL_} 前缀、第三词忽略、
 *    「单空格分隔」、取反写成 {@code if (!(a > r)) discard}、以及用 BigDecimal 而不是
 *    {@code Float.toString}（那会把 0.0001 印成 {@code 1.0E-4}）逐字保留。
 *    🔴 接线状态：本类的 parse 已接进 {@code pack/properties/ShaderProperties#alphaTests()}；
 *    把 {@link #discard(String)} 写进片元末尾那一步**还没接**（注入点在
 *    {@code pack/PackTerrainSource} 与 {@code pack/PackCompositeSource} 造源码处），
 *    所以 alpha test 这条特性目前仍**未生效**，不许写成已生效。
 * 4. 许可证核对结论：本项目 LGPL-3.0-or-later；本文件为 Vitrail 移植件，改动见上。
 * 5. 性能基线：❄️ 冷路径（源码生成期）。
 *
 * <p>包指望 {@code glAlphaFunc}，而那已经在 core profile 里消失了十五年。Sodium 在自己的片元阶段
 * 用 {@code ALPHA_CUTOUT} define 下的一条 {@code discard} 模拟它；包的程序**替换**那个阶段 ⇒
 * 一个画在 cutout pass 上而没有这条测试的包，会把树叶和草画成实心立方体，那比不画还糟。
 *
 * <p>这条测试是 **pass 的属性而不是程序的**：同一个 {@code gbuffers_terrain} 在 solid pass 上完全不测、
 * 在 cutout pass 上半。包用 {@code shaders.properties} 里的 {@code alphaTest.<程序>} 覆盖它，
 * 键是**真正服务该 pass 的那个文件名**而不是被问到的名字 —— Iris 也是这么查的。
 *
 * <p>比较写成取反形式 {@code if (!(a > r)) discard}，因为那是 Iris 发出来的形态，而包是照着 Iris 写的。
 * 它也在 NaN alpha 上与直写形式不同：这里丢弃，而 {@code if (a <= r)} 会保留。
 *
 * @param function 包要求拿什么来比较；{@link Function#ALWAYS} 就是完全不测
 * @param reference 拿 alpha 与什么比
 */
public record AlphaTest(Function function, float reference) {

    /** 不测。solid pass 要的东西，也是 {@code alphaTest.x=off} 的意思。 */
    public static final AlphaTest OFF = new AlphaTest(Function.ALWAYS, 0.0F);

    /** cutout 的默认值。Iris 这里用一半，Sodium 自己的 {@code ALPHA_CUTOUT} 也是。 */
    public static final AlphaTest CUTOUT = new AlphaTest(Function.GREATER, 0.5F);

    /**
     * 除地形以外各处的 cutout 默认值：十分之一而不是一半。
     *
     * <p>游戏自己在同一个分法下存着同一对数：{@code RenderPipelines} 把
     * {@code ALPHA_CUTOUT_THRESHOLD_DEFAULT} 声明成十分之一、
     * {@code ALPHA_CUTOUT_THRESHOLD_CUTOUT_TERRAIN} 一半，而它建的每个 cutout 实体管线都取前者。
     * Iris 给它的实体程序也是这同一个十分之一。
     */
    public static final AlphaTest ONE_TENTH = new AlphaTest(Function.GREATER, 0.1F);

    /**
     * translucent 的默认值：万分之一，那是 Iris 的数；Sodium 给自己的着色器用百分之一，
     * 而包是照着 Iris 写的。
     */
    public static final AlphaTest NON_ZERO = new AlphaTest(Function.GREATER, 0.0001F);

    /** {@code glAlphaFunc} 取过的八个名字，以及每一个在 GLSL 里是什么。 */
    public enum Function {

        NEVER(null),
        LESS("<"),
        EQUAL("=="),
        LEQUAL("<="),
        GREATER(">"),
        NOTEQUAL("!="),
        GEQUAL(">="),
        ALWAYS(null);

        private final String operator;

        Function(String operator) {
            this.operator = operator;
        }
    }

    /**
     * 读一条 {@code alphaTest.<程序>} 的值；它没说出任何可兑现的东西时返回 empty。
     *
     * <p>{@code off} 与 {@code false} 都在语料包里出现，都表示不测。其余是「一个函数 + 一个参考值」，
     * 两者都不是的那一行是**被拒而不是被猜**：错的阈值就是一张有洞的、或者本该有洞却没有洞的画面。
     */
    public static Optional<AlphaTest> parse(String raw) {
        String value = raw.trim();
        if (value.equalsIgnoreCase("off") || value.equalsIgnoreCase("false")) {
            return Optional.of(OFF);
        }

        // 第三个 token 被忽略而不是被拒，这是 Iris 对它的处理：行尾一句注释
        // 否则会害得这个包丢掉它的阈值。
        //
        // 一个空格而不是一段空白，因为那就是 Iris 给这条指令的分隔符：用 tab 或双空格写的值
        // 在那儿会解析出空词，在这里读进去就等于接受一行参考实现会拒绝的行。
        String[] parts = value.split(" ", -1);
        if (parts.length < 2) {
            return Optional.empty();
        }

        // GL_ALWAYS 是 shaders.properties 文档里的拼法，其余名字都裸写。两种都收，Iris 也两种都收。
        String name = parts[0].startsWith("GL_") ? parts[0].substring(3) : parts[0];
        try {
            return Optional.of(new AlphaTest(Function.valueOf(name.toUpperCase(Locale.ROOT)),
                    Float.parseFloat(parts[1])));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 这条到底丢不丢东西。{@code ALWAYS} 为 false（那就是不测）。 */
    public boolean tests() {
        return this.function != Function.ALWAYS;
    }

    /**
     * 放在片元 {@code main} 末尾、对这里点名的输出的 alpha 做比较的那条语句；没有测试要写时为空。
     */
    public String discard(String alpha) {
        if (this.function == Function.ALWAYS) {
            return "";
        }

        if (this.function == Function.NEVER) {
            return "discard;";
        }

        return "if (!(" + alpha + " " + this.function.operator + " " + literal() + ")) { discard; }";
    }

    /**
     * 参考值按 GLSL 写。完整写出来而不用 {@link Float#toString} —— 后者对 translucent 阈值给的是
     * {@code 1.0E-4}：GLSL 合法，但在 shader dump 里与包自己写的 {@code 0.0001} 并排时读不出来。
     */
    private String literal() {
        String plain = new BigDecimal(Float.toString(this.reference)).toPlainString();

        return plain.indexOf('.') < 0 ? plain + ".0" : plain;
    }
}
