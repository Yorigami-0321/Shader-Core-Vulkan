package dev.vkdisp.pack.model;

import java.util.Optional;

/**
 * 【参考调研】raw 贴图一个字节属于几个通道（移植件）
 * 0. 合规核对（第 0 步闸门）：
 *    上游 = Vitrail {@code dev/vitrail/pack/model/PixelFormat.java:1-48} @ 4a45f44…（LGPL-3.0，
 *    读取日期 2026-10-17）；Vitrail {@code NOTICE} 未记它源自 Iris（名字是 GL 的公开标识）⇒ 台账只记 Vitrail。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以，进 {@code NOTICE.md} 移植台账（§〇 P1）。
 * 3. 我们的差异点：包名/缩进 + 本注释块，取值与大小写规则逐字保留。
 * 4. 许可证核对结论：本项目 LGPL-3.0-or-later；本文件为 Vitrail 移植件，改动见上。
 * 5. 性能基线：❄️ 冷路径。
 *
 * <p>按包写的名字给出通道数：通道数是把「声明的尺寸」变成「文件必须有多少字节」的那一步，
 * 也就是唯一能分辨截断 blob 与完整 blob 的检查。这里不决定递给设备什么，那是设备旁的事。
 */
public enum PixelFormat {

    RED(1),
    RG(2),
    RGB(3),
    BGR(3),
    RGBA(4),
    BGRA(4),
    RED_INTEGER(1),
    RG_INTEGER(2),
    RGB_INTEGER(3),
    BGR_INTEGER(3),
    RGBA_INTEGER(4),
    BGRA_INTEGER(4);

    private final int components;

    PixelFormat(int components) {
        this.components = components;
    }

    /** 大小写不敏感：语料里有三个包把这些名字写成小写。 */
    public static Optional<PixelFormat> parse(String name) {
        for (PixelFormat format : values()) {
            if (format.name().equalsIgnoreCase(name.trim())) {
                return Optional.of(format);
            }
        }

        return Optional.empty();
    }

    public int components() {
        return this.components;
    }
}
