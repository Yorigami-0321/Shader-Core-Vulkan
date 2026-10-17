package dev.vkdisp.pack.model;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 【参考调研】目标可以被给成什么颜色格式（移植件，表体源自 Iris）
 * 0. 合规核对（第 0 步闸门）：
 *    上游 = Vitrail {@code dev/vitrail/pack/model/TargetFormat.java:1-225} @ 4a45f44…（LGPL-3.0，
 *    读取日期 2026-10-17）；🔴 **该文件自承转录自 Iris**（Vitrail {@code NOTICE} 第 25-30 行原文：
 *    「transcribes the colour format table of {@code net.irisshaders.iris.gl.texture.InternalTextureFormat}」，
 *    Iris 侧读取日期 2026-08-01）⇒ 台账记 Vitrail + Iris 两条（本项目与两者同为 LGPL-3.0 族）。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以，进 {@code NOTICE.md} 移植台账（§〇 P1）。
 *    → 例外条款：无。
 * 3. 我们的差异点：包名/缩进 + 本注释块；常量、名字表、三通道提升、fixed-function 遗物替换
 *    与 {@link Reason}/{@link Resolution} **逐字保留**。
 *    ⚠️ 注释里的「五个包里二十六条指令落在这条路上」「语料只用到一个遗物」是**上游语料的读数**，
 *    不是我方实测 ⇒ 引用它时按上游口径，别当我方数据（{@code 07} X32）。
 * 4. 许可证核对结论：本项目 LGPL-3.0-or-later；本文件为 Vitrail 移植件，其 Iris 来源已在台账注明。
 * 5. 性能基线：❄️ 冷路径。
 *
 * <p>常量恰好就是「会被交给设备」的那些，它们的名字恰好就是 {@code com.mojang.blaze3d.GpuFormat} 的名字。
 * 这是两重刻意：一是让到真实格式的翻译变成机械动作、对侧没有任何需要临场决定的东西；
 * 二是让本类打印的每一行都能与游戏里引擎自己日志逐行对照。
 *
 * <p>三种分量格式**刻意不在**常量里，尽管游戏给它们起了名字：它们在枚举里存在，但在桌面硬件上
 * 几乎不可能当颜色附件用 ⇒ 这里一次性、在没人绕得过的表里提升成四分量版本。
 *
 * <p>提升有一个包看不见、而本类记录下来的后果：在 GL 里采样三分量纹理恒得 {@code a = 1.0}，
 * 而被提升的那个得到的是 clear 放进去的值。{@link Resolution#alphaAdded()} 就是让 clear 得以纠正的那一位。
 */
public enum TargetFormat {

    R8_UNORM(1, 1, false),
    R8_SNORM(1, 1, false),
    RG8_UNORM(2, 2, false),
    RG8_SNORM(2, 2, false),
    RGBA8_UNORM(4, 4, false),
    RGBA8_SNORM(4, 4, false),
    R16_UNORM(1, 2, false),
    R16_SNORM(1, 2, false),
    RG16_UNORM(2, 4, false),
    RG16_SNORM(2, 4, false),
    RGBA16_UNORM(4, 8, false),
    RGBA16_SNORM(4, 8, false),
    R8_UINT(1, 1, true),
    R8_SINT(1, 1, true),
    RG8_UINT(2, 2, true),
    RG8_SINT(2, 2, true),
    RGBA8_UINT(4, 4, true),
    RGBA8_SINT(4, 4, true),
    R16_UINT(1, 2, true),
    R16_SINT(1, 2, true),
    RG16_UINT(2, 4, true),
    RG16_SINT(2, 4, true),
    RGBA16_UINT(4, 8, true),
    RGBA16_SINT(4, 8, true),
    R32_UINT(1, 4, true),
    R32_SINT(1, 4, true),
    RG32_UINT(2, 8, true),
    RG32_SINT(2, 8, true),
    RGBA32_UINT(4, 16, true),
    RGBA32_SINT(4, 16, true),
    R16_FLOAT(1, 2, false),
    RG16_FLOAT(2, 4, false),
    RGBA16_FLOAT(4, 8, false),
    R32_FLOAT(1, 4, false),
    RG32_FLOAT(2, 8, false),
    RGBA32_FLOAT(4, 16, false),
    RGB10A2_UNORM(4, 4, false),
    RGB10A2_UINT(4, 4, true),
    RG11B10_FLOAT(3, 4, false);

    /** 包什么都不写时得到的那个，以及读不懂的名字回落到的那个。 */
    private static final TargetFormat FALLBACK = RGBA8_UNORM;

    private static final String DEFAULT_NAME = "RGBA";

    private static final Map<String, Entry> DECLARED = table();

    private final int components;
    private final int bytesPerPixel;
    private final boolean integer;

    TargetFormat(int components, int bytesPerPixel, boolean integer) {
        this.components = components;
        this.bytesPerPixel = bytesPerPixel;
        this.integer = integer;
    }

    public enum Reason { EXACT, PROMOTED, REPLACED, UNKNOWN }

    /**
     * 一个声明的格式被解析成了什么，以及是什么造成了差别。
     *
     * @param declared   包写下的那个名字，原样
     * @param alphaAdded 声明的格式没有 alpha 通道而分配的那个有 ⇒ GL 采样器会读到 1.0 的地方，Vulkan 读到 clear 的值
     */
    public record Resolution(String declared, TargetFormat used, Reason reason, boolean alphaAdded) {
    }

    /** 大小写不敏感，与 Iris 一致。从不抛异常，从不返回 null。 */
    public static Resolution resolve(String declared) {
        Entry entry = DECLARED.get(declared.trim().toUpperCase(Locale.ROOT));
        if (entry == null) {
            // 下载的包里一个拼写错误值一行日志，绝不值一次「加载失败」。
            return new Resolution(declared, FALLBACK, Reason.UNKNOWN, false);
        }

        return new Resolution(declared, entry.used(), entry.reason(), entry.alphaAdded());
    }

    /** 没有格式指令的目标拿到什么：RGBA8_UNORM，报成 {@code RGBA}。 */
    public static Resolution defaultFormat() {
        return new Resolution(DEFAULT_NAME, FALLBACK, Reason.EXACT, false);
    }

    /** 逻辑通道数：RGB10A2 是四，RG11B10 是三。 */
    public int components() {
        return this.components;
    }

    public int bytesPerPixel() {
        return this.bytesPerPixel;
    }

    /** 整数目标不能被过滤。 */
    public boolean integer() {
        return this.integer;
    }

    private static Map<String, Entry> table() {
        Map<String, Entry> declared = new LinkedHashMap<>();

        // Iris 的隐式默认，包也可以把它写出来。
        exact(declared, DEFAULT_NAME, RGBA8_UNORM);

        exact(declared, "R8", R8_UNORM);
        exact(declared, "RG8", RG8_UNORM);
        exact(declared, "RGBA8", RGBA8_UNORM);
        exact(declared, "R8_SNORM", R8_SNORM);
        exact(declared, "RG8_SNORM", RG8_SNORM);
        exact(declared, "RGBA8_SNORM", RGBA8_SNORM);
        exact(declared, "R16", R16_UNORM);
        exact(declared, "RG16", RG16_UNORM);
        exact(declared, "RGBA16", RGBA16_UNORM);
        exact(declared, "R16_SNORM", R16_SNORM);
        exact(declared, "RG16_SNORM", RG16_SNORM);
        exact(declared, "RGBA16_SNORM", RGBA16_SNORM);
        exact(declared, "R16F", R16_FLOAT);
        exact(declared, "RG16F", RG16_FLOAT);
        exact(declared, "RGBA16F", RGBA16_FLOAT);
        exact(declared, "R32F", R32_FLOAT);
        exact(declared, "RG32F", RG32_FLOAT);
        exact(declared, "RGBA32F", RGBA32_FLOAT);
        exact(declared, "R8I", R8_SINT);
        exact(declared, "RG8I", RG8_SINT);
        exact(declared, "RGBA8I", RGBA8_SINT);
        exact(declared, "R8UI", R8_UINT);
        exact(declared, "RG8UI", RG8_UINT);
        exact(declared, "RGBA8UI", RGBA8_UINT);
        exact(declared, "R16I", R16_SINT);
        exact(declared, "RG16I", RG16_SINT);
        exact(declared, "RGBA16I", RGBA16_SINT);
        exact(declared, "R16UI", R16_UINT);
        exact(declared, "RG16UI", RG16_UINT);
        exact(declared, "RGBA16UI", RGBA16_UINT);
        exact(declared, "R32I", R32_SINT);
        exact(declared, "RG32I", RG32_SINT);
        exact(declared, "RGBA32I", RGBA32_SINT);
        exact(declared, "R32UI", R32_UINT);
        exact(declared, "RG32UI", RG32_UINT);
        exact(declared, "RGBA32UI", RGBA32_UINT);
        exact(declared, "RGB10_A2", RGB10A2_UNORM);
        exact(declared, "RGB10_A2UI", RGB10A2_UINT);
        // 三通道且无 alpha，仍然是 exact：打包就是一个 32 位字，每台设备都能 attach。
        exact(declared, "R11F_G11F_B10F", RG11B10_FLOAT);

        // 三分量，被加宽。
        promoted(declared, "RGB8", RGBA8_UNORM);
        promoted(declared, "RGB8_SNORM", RGBA8_SNORM);
        promoted(declared, "RGB16", RGBA16_UNORM);
        promoted(declared, "RGB16_SNORM", RGBA16_SNORM);
        promoted(declared, "RGB16F", RGBA16_FLOAT);
        promoted(declared, "RGB32F", RGBA32_FLOAT);
        promoted(declared, "RGB8I", RGBA8_SINT);
        promoted(declared, "RGB8UI", RGBA8_UINT);
        promoted(declared, "RGB16I", RGBA16_SINT);
        promoted(declared, "RGB16UI", RGBA16_UINT);
        promoted(declared, "RGB32I", RGBA32_SINT);
        promoted(declared, "RGB32UI", RGBA32_UINT);

        // fixed-function 时代、完全没有现代对应物的遗物。每一个在这里都是** gaining ** precision，
        // 而这正是驱动这些年一直替它们做的事；语料里它们只被用到一次、且只用其中一个。
        replaced(declared, "RGBA2", RGBA8_UNORM, false);
        replaced(declared, "RGBA4", RGBA8_UNORM, false);
        replaced(declared, "R3_G3_B2", RGBA8_UNORM, true);
        replaced(declared, "RGB5_A1", RGBA8_UNORM, false);
        replaced(declared, "RGB565", RGBA8_UNORM, true);
        // 唯一有损失的那个。每通道九位尾数、共享一个指数 ⇒ 半浮点保留的比 11 与 10 位的打包更多。
        replaced(declared, "RGB9_E5", RGBA16_FLOAT, true);

        return Map.copyOf(declared);
    }

    private static void exact(Map<String, Entry> declared, String name, TargetFormat used) {
        declared.put(name, new Entry(used, Reason.EXACT, false));
    }

    private static void promoted(Map<String, Entry> declared, String name, TargetFormat used) {
        declared.put(name, new Entry(used, Reason.PROMOTED, true));
    }

    private static void replaced(Map<String, Entry> declared, String name, TargetFormat used,
            boolean alphaAdded) {
        declared.put(name, new Entry(used, Reason.REPLACED, alphaAdded));
    }

    private record Entry(TargetFormat used, Reason reason, boolean alphaAdded) {
    }
}
