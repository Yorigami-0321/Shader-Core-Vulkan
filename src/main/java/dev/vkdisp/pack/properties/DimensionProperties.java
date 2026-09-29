package dev.vkdisp.pack.properties;

import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【参考调研】dimension.properties 解析（Iris 维度分支，OF worldN 文件夹语义的事实补充）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = Iris 官方文档 dimension.properties 语法（dimension.<文件夹名>=<维度ID> [...]，支持 * 通配）
 *    与 OptiFine worldN 文件夹机制（事实性信息）；零代码复制。ARR 项目不碰。
 *    → 能否并入本项目（MIT）：可以（仅含格式事实）；不含任何 GPL / LGPL / ARR 代码。
 * 1. 官方/主实现：dimension.<文件夹名>=维度ID 列表；未列出的维度回退到 shaders/ 根；* 表示「其余未定义维度」。
 *    OF 的 worldN 文件夹机制是目录级事实，由 B 线扫包时按结构识别，本类只解析 dimension.* 键值。
 * 2. 备选：无。
 * 3. 我们的差异点：worldN 文件夹是否存在属包结构事实（B 线职责），本类只消费 dimension.properties 的显式映射。
 * 4. 许可证核对结论：本项目 MIT；零第三方代码。
 * 5. 性能基线：❄️ 冷路径，不做优化。
 */
public final class DimensionProperties {

    private final Map<String, DimensionEntry> entries;

    private DimensionProperties(Map<String, DimensionEntry> entries) {
        this.entries = Map.copyOf(entries);
    }

    public static DimensionProperties parse(Reader reader) {
        return parse(IdMapProperties.readLines(reader), null);
    }

    public static DimensionProperties parse(Reader reader, Set<String> definedMacros) {
        return parse(IdMapProperties.readLines(reader), definedMacros);
    }

    public static DimensionProperties parse(String text) {
        return parse(text, null);
    }

    public static DimensionProperties parse(String text, Set<String> definedMacros) {
        return parse(List.of(text.split("\n", -1)), definedMacros);
    }

    private static DimensionProperties parse(List<String> rawLines, Set<String> definedMacros) {
        List<String> lines = ConditionalPreprocessor.preprocess(rawLines, definedMacros);
        LinkedHashMap<String, DimensionEntry> result = new LinkedHashMap<>();
        int idx = 0;
        for (String line : lines) {
            idx++;
            int eq = line.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("vkdisp: dimension.properties 行缺少 '='（行 " + idx + "）：" + line);
            }
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            if (!key.startsWith("dimension.")) {
                throw new IllegalArgumentException("vkdisp: 非法 dimension 键（行 " + idx + "）：" + key);
            }
            String folder = key.substring("dimension.".length()).strip();
            if (folder.isEmpty()) {
                throw new IllegalArgumentException("vkdisp: dimension.<文件夹名> 不能为空（行 " + idx + "）");
            }
            List<String> ids = new ArrayList<>();
            boolean wildcard = false;
            for (String tok : value.split("\\s+")) {
                if (tok.isEmpty()) {
                    continue;
                }
                if (tok.equals("*")) {
                    wildcard = true;
                } else {
                    ids.add(tok);
                }
            }
            if (result.containsKey(folder)) {
                throw new IllegalArgumentException("vkdisp: dimension 文件夹重复（T11，行 " + idx + "）：" + folder);
            }
            result.put(folder, new DimensionEntry(folder, List.copyOf(ids), wildcard));
        }
        return new DimensionProperties(result);
    }

    /** 单个维度映射条目：文件夹名 + 显式维度 ID 列表 + 是否含 * 通配。 */
    public record DimensionEntry(String folder, List<String> dimensionIds, boolean wildcard) {
    }

    /** 文件夹名 → 维度映射条目（不可变）。 */
    public Map<String, DimensionEntry> entries() {
        return entries;
    }
}
