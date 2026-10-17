package dev.vkdisp.pack.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 【参考调研】一条 {@code bufferObject.N}（SSBO）声明（移植件，文法源自 Iris）
 * 0. 合规核对（第 0 步闸门）：
 *    上游 = Vitrail {@code dev/vitrail/pack/model/BufferObject.java:1-130} @ 4a45f44…（LGPL-3.0，
 *    读取日期 2026-10-17）；🔴 该文件由 Vitrail {@code NOTICE} 点名「read the bufferObject directives
 *    with the grammars of {@code net.irisshaders.iris.shaderpack.properties.ShaderProperties}」
 *    并给出上限的行号证据（Iris 侧 {@code :407} 与 {@code :431}）⇒ 台账记 Vitrail + Iris 两条。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以，进 {@code NOTICE.md} 移植台账（§〇 P1）。
 * 1. 官方/主实现：OptiFine 的 {@code bufferObject.N}（大小/块名/四词相对式三种写法）；上限 12 是 Iris 定的。
 * 3. 我们的差异点：包名/缩进 + 本注释块；词数分支、{@code size < 1} 即「这条 buffer 关掉」的读法、
 *    以及「被上限拒绝的行是**丢弃并点名**而不是 log-and-return」这三条逐字保留。
 * 4. 许可证核对结论：本项目 LGPL-3.0-or-later；本文件为 Vitrail 移植件，其 Iris 来源已在台账注明。
 * 5. 性能基线：❄️ 冷路径。
 *
 * <p>一条 {@code bufferObject.N}，按 Iris 读到的样子，且在任何东西被分配之前。这里不碰设备。
 */
public record BufferObject(int index, long size, boolean relative, float scaleX, float scaleY,
        Optional<String> name) {

    /** Iris 拒绝十二以上的索引；那些槽位是保留的。 */
    public static final int LIMIT = 12;

    /**
     * {@code dev.vkdisp.pack.properties.ShaderProperties} 对 {@code bufferObject.} 的回答，
     * 含那些「当时是活的却读不出来」的行。
     */
    public record Reading(List<BufferObject> buffers, List<String> dropped) {

        public Reading {
            buffers = List.copyOf(buffers);
            dropped = List.copyOf(dropped);
        }

        public static Reading empty() {
            return new Reading(List.of(), List.of());
        }

        public boolean hasIndex(int index) {
            for (BufferObject buffer : this.buffers) {
                if (buffer.index() == index) {
                    return true;
                }
            }

            return false;
        }

        public boolean hasName(String name) {
            for (BufferObject buffer : this.buffers) {
                if (buffer.name().filter(name::equals).isPresent()) {
                    return true;
                }
            }

            return false;
        }
    }

    /** 日志用的一条：说清索引、可选名字与大小。 */
    public String describe() {
        String size = this.relative
                ? this.size + " bytes per pixel at " + this.scaleX + "x" + this.scaleY
                        + " of the screen"
                : this.size + " bytes";

        return this.index
                + this.name.map(name -> " as " + name).orElse("")
                + " " + size;
    }

    /**
     * 一条 {@code bufferObject.N} 的值，按 Iris 的词数；或这条行为什么留不下。
     *
     * <p>返回 null 表示这条 buffer 已被收下。两词及以下是「绝对大小 + 可选名字」；四词以上是相对式。
     */
    public static String parse(String indexText, String value,
            Map<Integer, BufferObject> buffers) {
        int index;
        try {
            index = Integer.parseInt(indexText);
        } catch (NumberFormatException e) {
            return "index is not a number";
        }

        if (index > LIMIT) {
            return "only indices 0 to " + LIMIT + " are allowed";
        }

        String[] parts = value.split(" ", -1);
        if (parts.length == 0 || parts[0].isEmpty()) {
            return "expected a size";
        }

        long size;
        try {
            size = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            return "size is not a number";
        }

        if (size < 1L) {
            return "size below one disables the buffer";
        }

        if (parts.length <= 2) {
            Optional<String> name = parts.length > 1 && !parts[1].isEmpty()
                    ? Optional.of(parts[1])
                    : Optional.empty();
            buffers.put(index, new BufferObject(index, size, false, 0.0F, 0.0F, name));
            return null;
        }

        if (parts.length < 4) {
            return "a relative buffer takes four words";
        }

        boolean relative = Boolean.parseBoolean(parts[1]);
        float scaleX;
        float scaleY;
        try {
            scaleX = Float.parseFloat(parts[2]);
            scaleY = Float.parseFloat(parts[3]);
        } catch (NumberFormatException e) {
            return "relative scale is not a number";
        }

        buffers.put(index, new BufferObject(index, size, relative, scaleX, scaleY, Optional.empty()));
        return null;
    }
}
