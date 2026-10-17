package dev.vkdisp.pack.target;

import java.util.Set;

/**
 * 【参考调研】哪些 sampler 声明能建出管线（移植件）
 * 0. 合规核对（第 0 步闸门）：
 *    上游 = Vitrail {@code dev/vitrail/pack/target/SamplerTypes.java:1-65} @ 4a45f44…（LGPL-3.0，
 *    读取日期 2026-10-17）。Vitrail {@code NOTICE} 未记它源自 Iris（它讲的是**游戏自己**的
 *    {@code GlslCompiler.addToBindGroup} 的维度检查）⇒ 台账只记 Vitrail 一条。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以，进 {@code NOTICE.md} 移植台账（§〇 P1）。
 * 1. 官方/主实现：规则属于游戏 —— {@code GlslCompiler.addToBindGroup} 遍历 SPIR-V 反射给的每个
 *    sampler，维度不是 {@code SpvDim2D}/{@code SpvDimCube} 就抛
 *    {@code Sampled texture (X) must have type of SpvDim2D or SpvDimCube} ⇒ 程序里任何一处
 *    {@code sampler3D} 就让那个程序的**整条管线**建不起来。
 * 3. 我们的差异点：包名/缩进/文件头；{@link #BINDABLE} 十个成员与 {@link #shapeOf} 的
 *    {@code i}/{@code u} 前缀剥离逐字保留。🔴 上游为放行 volume 另有一个 {@code GlslCompilerMixin}
 *    把那次遍历改成「{@code SpvDim3D} 当 {@code SpvDim2D} 读」—— 我方**本文件不含**那个绕行，
 *    是否要同样的注入是另一件事（它属 mixin 登记制，且要真机判据）。
 * 4. 许可证核对结论：本项目 LGPL-3.0-or-later；本文件为 Vitrail 移植件，改动见上。
 * 5. 性能基线：❄️ 冷路径（装配期按声明判一次）。
 *
 * <p>只按类型判，不看名字也不看用法。名字毫无证据：Mellow 在共享 include 里写
 * {@code uniform sampler3D colortex6} ⇒ 按名字判会把它当颜色目标、递给它一张真 2D 视图。
 */
public final class SamplerTypes {

    /** 去掉 integer/unsigned 前缀之后，sampler 类型怎么拼。 */
    private static final String SAMPLER = "sampler";

    /**
     * 维度是 {@code SpvDim2D} 或 {@code SpvDimCube} 的全部 —— 那个检查只看这一件事：
     * shadow / array / multisample 拼法带的还是这两个维度，所以过得去。
     * {@code 2DRect} 无论读起来如何都不是，它是 {@code SpvDimRect}。
     */
    private static final Set<String> BINDABLE = Set.of(
            "2D", "2DShadow", "2DArray", "2DArrayShadow", "2DMS", "2DMSArray",
            "Cube", "CubeShadow", "CubeArray", "CubeArrayShadow");

    private SamplerTypes() {
    }

    /** 这个类型的声明是否会让携带它的程序建不起管线。 */
    public static boolean refused(String type) {
        String shape = shapeOf(type);

        return shape != null && !BINDABLE.contains(shape);
    }

    /**
     * {@code sampler} 之后那段，{@code usampler3D} 答 {@code 3D}；类型名根本不指 sampler 时返回 null。
     * storage image 是另一种资源，不在这里回答。
     */
    public static String shapeOf(String type) {
        String name = type;
        if (name.startsWith("i") || name.startsWith("u")) {
            name = name.substring(1);
        }

        return name.startsWith(SAMPLER) ? name.substring(SAMPLER.length()) : null;
    }
}
