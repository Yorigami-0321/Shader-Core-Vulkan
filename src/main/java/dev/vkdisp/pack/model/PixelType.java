package dev.vkdisp.pack.model;

import java.util.Optional;

/**
 * 【参考调研】raw 贴图一个值有多宽（移植件）
 * 0. 合规核对（第 0 步闸门）：
 *    上游 = Vitrail {@code dev/vitrail/pack/model/PixelType.java:1-68} @ 4a45f44…（LGPL-3.0，
 *    读取日期 2026-10-17）；名字是 GL 的公开标识，Vitrail {@code NOTICE} 未记它源自 Iris ⇒ 台账只记 Vitrail。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以，进 {@code NOTICE.md} 移植台账（§〇 P1）。
 * 3. 我们的差异点：包名/缩进 + 本注释块；packed 标志与 {@link #bytesPerTexel} 的分支逐字保留。
 * 4. 许可证核对结论：本项目 LGPL-3.0-or-later；本文件为 Vitrail 移植件，改动见上。
 * 5. 性能基线：❄️ 冷路径。
 *
 * <p>这里住着两类名字，把它们分清是这个类存在的全部理由：
 * 普通类型给的是**一个通道**的宽度，所以一个 texel 的值 = 宽度 × 通道数；
 * packed 类型给的是**整个 texel**（连同所有通道）的宽度，再乘通道数就会向文件要它持有的三倍字节，
 * 并把一个完全合法的包拒掉。语料里没有任何包写 packed 类型 —— 这正是「区别要写下来，
 * 而不是日后才发现」的理由。
 */
public enum PixelType {

    BYTE(1, false),
    SHORT(2, false),
    INT(4, false),
    HALF_FLOAT(2, false),
    FLOAT(4, false),
    UNSIGNED_BYTE(1, false),
    UNSIGNED_SHORT(2, false),
    UNSIGNED_INT(4, false),
    UNSIGNED_BYTE_3_3_2(1, true),
    UNSIGNED_BYTE_2_3_3_REV(1, true),
    UNSIGNED_SHORT_5_6_5(2, true),
    UNSIGNED_SHORT_5_6_5_REV(2, true),
    UNSIGNED_SHORT_4_4_4_4(2, true),
    UNSIGNED_SHORT_4_4_4_4_REV(2, true),
    UNSIGNED_SHORT_5_5_5_1(2, true),
    UNSIGNED_SHORT_1_5_5_5_REV(2, true),
    UNSIGNED_INT_8_8_8_8(4, true),
    UNSIGNED_INT_8_8_8_8_REV(4, true),
    UNSIGNED_INT_10_10_10_2(4, true),
    UNSIGNED_INT_2_10_10_10_REV(4, true),
    UNSIGNED_INT_10F_11F_11F_REV(4, true),
    UNSIGNED_INT_5_9_9_9_REV(4, true);

    private final int bytes;
    private final boolean packed;

    PixelType(int bytes, boolean packed) {
        this.bytes = bytes;
        this.packed = packed;
    }

    /** 大小写不敏感：语料里有三个包把这些名字写成小写。 */
    public static Optional<PixelType> parse(String name) {
        for (PixelType type : values()) {
            if (type.name().equalsIgnoreCase(name.trim())) {
                return Optional.of(type);
            }
        }

        return Optional.empty();
    }

    /** 一个 texel 的成本 —— packed 类型自己就答了这个数。 */
    public long bytesPerTexel(PixelFormat format) {
        return this.packed ? this.bytes : (long) this.bytes * format.components();
    }

    /** 一个通道占多少字节；packed 类型那是所有通道共享的整个字。 */
    public int channelBytes() {
        return this.bytes;
    }
}
