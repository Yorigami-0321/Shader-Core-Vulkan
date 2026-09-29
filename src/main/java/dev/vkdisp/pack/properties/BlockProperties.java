package dev.vkdisp.pack.properties;

import java.io.Reader;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【参考调研】block.properties 解析（OF/Iris 方块 ID 映射）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = OptiFine 官方文档 block.properties 语法 + Iris IdMap（LGPL-3.0，只读格式事实）；零代码复制。
 *    → 能否并入本项目（MIT）：可以（仅含格式事实）；不含任何 GPL / LGPL / ARR 代码。
 * 1. 官方/主实现：block.<ID>=<方块列表>；短格式 red_flower、长格式 minecraft:red_flower、属性格式
 *    minecraft:red_flower:type=white_tulip。一个 ID 只允许一行。支持条件编译。
 * 2. 备选：无。
 * 3. 我们的差异点：ID→图层号由主线消费，本类只产出 ID→方块引用列表（见 IdMapProperties）。
 * 4. 许可证核对结论：本项目 MIT；零第三方代码。
 * 5. 性能基线：❄️ 冷路径，不做优化。
 */
public final class BlockProperties {

    private final Map<Integer, List<IdMapProperties.MappedId>> mappings;

    private BlockProperties(Map<Integer, List<IdMapProperties.MappedId>> mappings) {
        this.mappings = Map.copyOf(mappings);
    }

    public static BlockProperties parse(Reader reader) {
        return parse(IdMapProperties.readLines(reader), null);
    }

    public static BlockProperties parse(Reader reader, Set<String> definedMacros) {
        return parse(IdMapProperties.readLines(reader), definedMacros);
    }

    public static BlockProperties parse(String text) {
        return parse(text, null);
    }

    public static BlockProperties parse(String text, Set<String> definedMacros) {
        return parse(List.of(text.split("\n", -1)), definedMacros);
    }

    private static BlockProperties parse(List<String> lines, Set<String> definedMacros) {
        return new BlockProperties(IdMapProperties.parseIdMap(lines, definedMacros, "block."));
    }

    /** 替代 ID → 被映射方块引用列表（不可变）。 */
    public Map<Integer, List<IdMapProperties.MappedId>> mappings() {
        return mappings;
    }
}
