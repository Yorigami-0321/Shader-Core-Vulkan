package dev.vkdisp.pack.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * 【参考调研】一条 {@code image.NAME}（自定义图像）声明（移植件，文法源自 Iris）
 * 0. 合规核对（第 0 步闸门）：
 *    上游 = Vitrail {@code dev/vitrail/pack/model/ImageInformation.java:1-171} @ 4a45f44…（LGPL-3.0，
 *    读取日期 2026-10-17）；🔴 该文件由 Vitrail {@code NOTICE} 点名按 Iris
 *    {@code net.irisshaders.iris.shaderpack.properties.ShaderProperties} 的 {@code image.} 文法读，
 *    且「第十七条被拒」的上限给到 Iris 侧 {@code :519} ⇒ 台账记 Vitrail + Iris 两条。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以，进 {@code NOTICE.md} 移植台账（§〇 P1）。
 * 3. 我们的差异点：包名/缩进 + 本注释块；六词起、相对式恰八词、7/8/9 词分别对应 1D/2D/3D、
 *    「尺寸不是数字就回到 defines 里查同名设置」这四条逐字保留。
 *    ⚠️ {@code TEXTURE_RECTANGLE} 在本读法里不会被产出（上游词数分支没有它）⇒ 保持原样，别顺手补。
 * 4. 许可证核对结论：本项目 LGPL-3.0-or-later；本文件为 Vitrail 移植件，其 Iris 来源已在台账注明。
 * 5. 性能基线：❄️ 冷路径。
 *
 * <p>一条 {@code image.NAME}，按 Iris 读到的样子，且在任何东西被分配之前。这里不碰设备。
 *
 * @param sampler 包写 {@code none} 时为空 ⇒ 这张图只被存、从不被采样
 */
public record ImageInformation(String name, Optional<String> sampler, PackTexture.Shape shape,
        PixelFormat pixelFormat, TargetFormat.Resolution internalFormat, PixelType pixelType,
        int width, int height, int depth, boolean clear, boolean relative, float relativeWidth,
        float relativeHeight) {

    /** Iris 拒绝第十七条。Complementary Ultra 离它还远。 */
    public static final int LIMIT = 16;

    /**
     * {@code dev.vkdisp.pack.properties.ShaderProperties} 对 {@code image.} 的回答，
     * 含那些「当时是活的却读不出来」的行。
     */
    public record Reading(List<ImageInformation> images, List<String> dropped) {

        public Reading {
            images = List.copyOf(images);
            dropped = List.copyOf(dropped);
        }

        public static Reading empty() {
            return new Reading(List.of(), List.of());
        }
    }

    /** 日志用的一条：名字、采样器、形状与尺寸。 */
    public String describe() {
        String size = this.relative
                ? this.relativeWidth + "x" + this.relativeHeight + " of the screen"
                : switch (this.shape) {
                    case TEXTURE_1D -> Integer.toString(this.width);
                    case TEXTURE_2D -> this.width + "x" + this.height;
                    case TEXTURE_3D -> this.width + "x" + this.height + "x" + this.depth;
                    case TEXTURE_RECTANGLE -> this.width + "x" + this.height;
                };

        return this.name
                + this.sampler.map(sampler -> " as " + sampler).orElse("")
                + " " + this.shape.name() + " " + this.internalFormat.declared() + " " + size
                + (this.clear ? ", cleared each frame" : "");
    }

    /**
     * 一条 {@code image.NAME} 的值，按 Iris 的词数；或这条行为什么留不下。
     *
     * <p>返回 null 表示这张图已被收下。尺寸不是数字时去 {@code defines} 里查同名条目 ——
     * 那是「Iris 会先对文件跑一遍预处理器，才让这个解析器看到 token」的替代做法。
     */
    public static String parse(String name, String value, Map<String, String> defines,
            List<ImageInformation> images) {
        String[] parts = value.split(" ", -1);
        if (parts.length < 6) {
            return "expected at least six words";
        }

        Optional<String> sampler = parts[0].isEmpty() || parts[0].equalsIgnoreCase("none")
                ? Optional.empty()
                : Optional.of(parts[0]);
        Optional<PixelFormat> format = PixelFormat.parse(parts[1]);
        TargetFormat.Resolution internal = TargetFormat.resolve(parts[2]);
        Optional<PixelType> pixelType = PixelType.parse(parts[3]);
        if (format.isEmpty() || pixelType.isEmpty()
                || internal.reason() == TargetFormat.Reason.UNKNOWN) {
            return "format " + parts[1] + " internal " + parts[2] + " pixel type " + parts[3];
        }

        boolean clear = Boolean.parseBoolean(parts[4]);
        boolean relative = Boolean.parseBoolean(parts[5]);
        PackTexture.Shape shape;
        int width;
        int height;
        int depth;
        float relativeWidth = 0;
        float relativeHeight = 0;
        if (relative) {
            if (parts.length != 8) {
                return "a relative image takes two size words";
            }

            try {
                relativeWidth = Float.parseFloat(parts[6]);
                relativeHeight = Float.parseFloat(parts[7]);
            } catch (NumberFormatException e) {
                return "relative size is not a number";
            }

            shape = PackTexture.Shape.TEXTURE_2D;
            width = 0;
            height = 0;
            depth = 0;
        } else if (parts.length == 7) {
            OptionalInt size = dimension(parts[6], defines);
            if (size.isEmpty()) {
                return "size is not a number";
            }

            shape = PackTexture.Shape.TEXTURE_1D;
            width = size.getAsInt();
            height = 0;
            depth = 0;
        } else if (parts.length == 8) {
            OptionalInt sizeX = dimension(parts[6], defines);
            OptionalInt sizeY = dimension(parts[7], defines);
            if (sizeX.isEmpty() || sizeY.isEmpty()) {
                return "size is not a number";
            }

            shape = PackTexture.Shape.TEXTURE_2D;
            width = sizeX.getAsInt();
            height = sizeY.getAsInt();
            depth = 0;
        } else if (parts.length == 9) {
            OptionalInt sizeX = dimension(parts[6], defines);
            OptionalInt sizeY = dimension(parts[7], defines);
            OptionalInt sizeZ = dimension(parts[8], defines);
            if (sizeX.isEmpty() || sizeY.isEmpty() || sizeZ.isEmpty()) {
                return "size is not a number";
            }

            shape = PackTexture.Shape.TEXTURE_3D;
            width = sizeX.getAsInt();
            height = sizeY.getAsInt();
            depth = sizeZ.getAsInt();
        } else {
            return "unknown image type";
        }

        images.add(new ImageInformation(name, sampler, shape, format.get(), internal,
                pixelType.get(), width, height, depth, clear, relative, relativeWidth,
                relativeHeight));

        return null;
    }

    /** 一个尺寸 token；不是数字时，用同名条目在包的设置里再查一次。 */
    private static OptionalInt dimension(String token, Map<String, String> defines) {
        try {
            return OptionalInt.of(Integer.parseInt(token));
        } catch (NumberFormatException e) {
            String value = defines.get(token);
            if (value == null) {
                return OptionalInt.empty();
            }

            try {
                return OptionalInt.of(Integer.parseInt(value.trim()));
            } catch (NumberFormatException ignored) {
                return OptionalInt.empty();
            }
        }
    }
}
