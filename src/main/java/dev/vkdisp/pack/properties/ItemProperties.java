package dev.vkdisp.pack.properties;

import java.io.Reader;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【参考调研】item.properties 解析（OF/Iris 物品 ID 映射）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = OptiFine 官方文档 item.properties 语法 + Iris IdMap（LGPL-3.0，只读格式事实）；零代码复制。
 *    → 能否并入本项目（MIT）：可以（仅含格式事实）；不含任何 GPL / LGPL / ARR 代码。
 * 1. 官方/主实现：item.<ID>=<物品列表>，结构与 block.properties 同构（见 IdMapProperties）。一个 ID 只允许一行。支持条件编译。
 * 2. 备选：无。
 * 3. 我们的差异点：与 BlockProperties 共用 IdMapProperties，仅前缀不同（item. vs block.）。
 * 4. 许可证核对结论：本项目 MIT；零第三方代码。
 * 5. 性能基线：❄️ 冷路径，不做优化。
 */
public final class ItemProperties {

    private final Map<Integer, List<IdMapProperties.MappedId>> mappings;

    private ItemProperties(Map<Integer, List<IdMapProperties.MappedId>> mappings) {
        this.mappings = Map.copyOf(mappings);
    }

    public static ItemProperties parse(Reader reader) {
        return parse(IdMapProperties.readLines(reader), null);
    }

    public static ItemProperties parse(Reader reader, Set<String> definedMacros) {
        return parse(IdMapProperties.readLines(reader), definedMacros);
    }

    public static ItemProperties parse(String text) {
        return parse(text, null);
    }

    public static ItemProperties parse(String text, Set<String> definedMacros) {
        return parse(List.of(text.split("\n", -1)), definedMacros);
    }

    private static ItemProperties parse(List<String> lines, Set<String> definedMacros) {
        return new ItemProperties(IdMapProperties.parseIdMap(lines, definedMacros, "item."));
    }

    /** 替代 ID → 被映射物品引用列表（不可变）。 */
    public Map<Integer, List<IdMapProperties.MappedId>> mappings() {
        return mappings;
    }
}
