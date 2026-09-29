package dev.vkdisp.pack.properties;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【参考调研】block.properties / item.properties / entity.properties 共用解析（OF/Iris ID 映射）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = OptiFine 官方文档 ID 映射语法（<kind>.<ID>=<对象列表>，支持 namespace:path 与 :prop=value 长格式）
 *    与 Iris IdMap（LGPL-3.0，只读格式事实）；零代码复制。ARR 项目不碰。
 *    → 能否并入本项目（MIT）：可以（仅含格式事实）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：键 = 替代 ID（整数），值 = 被映射对象列表；长格式 namespace:path:type=value 的 :type=value 部分
 *    是属性过滤条件。一个 ID 只允许一行（重复 = T11 显式报错）。支持条件编译（#ifdef 等）。
 * 2. 备选：无。
 * 3. 我们的差异点：ID→图层号映射的「图层号」语义由主线消费，本类只产出 ID→对象引用列表。
 * 4. 许可证核对结论：本项目 MIT；本文件零第三方代码。
 * 5. 性能基线：❄️ 冷路径，不做优化（18-PARALLEL §7.7）。
 */
final class IdMapProperties {

    private IdMapProperties() {
    }

    /** 单条被映射对象引用：id 为 namespace:path（短格式即原样），properties 为长格式的属性过滤条件。 */
    record MappedId(String id, Map<String, String> properties) {
        MappedId {
            properties = Map.copyOf(properties);
        }
    }

    /** 解析 block./item./entity. 前缀的 ID 映射文件。 */
    static Map<Integer, List<MappedId>> parseIdMap(List<String> rawLines, Set<String> macros, String prefix) {
        List<String> lines = ConditionalPreprocessor.preprocess(rawLines, macros);
        LinkedHashMap<Integer, List<MappedId>> result = new LinkedHashMap<>();
        int idx = 0;
        for (String line : lines) {
            idx++;
            int eq = line.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("vkdisp: " + prefix + " 行缺少 '='（行 " + idx + "）：" + line);
            }
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            if (!key.startsWith(prefix)) {
                throw new IllegalArgumentException("vkdisp: 非法 " + prefix + " 键（行 " + idx + "）：" + key);
            }
            String idPart = key.substring(prefix.length()).strip();
            int id;
            try {
                id = Integer.parseInt(idPart);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("vkdisp: " + prefix + " 键必须是整数 ID（行 " + idx + "）：" + key);
            }
            if (result.containsKey(id)) {
                throw new IllegalArgumentException("vkdisp: " + prefix + " ID 重复（T11，行 " + idx + "）：" + id);
            }
            List<MappedId> refs = new ArrayList<>();
            for (String tok : value.split("\\s+")) {
                if (tok.isEmpty()) {
                    continue;
                }
                refs.add(parseToken(tok));
            }
            result.put(id, List.copyOf(refs));
        }
        return result;
    }

    private static MappedId parseToken(String tok) {
        String[] parts = tok.split(":");
        if (parts.length == 1) {
            return new MappedId(tok, Map.of());
        }
        String id = parts[0] + ":" + parts[1];
        LinkedHashMap<String, String> props = new LinkedHashMap<>();
        for (int i = 2; i < parts.length; i++) {
            int kv = parts[i].indexOf('=');
            if (kv < 0) {
                props.put(parts[i], "");
            } else {
                props.put(parts[i].substring(0, kv), parts[i].substring(kv + 1));
            }
        }
        return new MappedId(id, props);
    }

    static List<String> readLines(Reader reader) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(reader)) {
            String l;
            while ((l = br.readLine()) != null) {
                lines.add(l);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("vkdisp: 读取属性文件失败", e);
        }
        return lines;
    }
}
