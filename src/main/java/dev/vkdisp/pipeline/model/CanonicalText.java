package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】E 线纯计算件 / 规范文本编码小工具
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/18-PARALLEL.md §4 E 线「中间表示能被序列化后打印比对」；
 *    ② 本仓库 docs/08-TESTING.md §6「管线缓存按 program 键，顶点格式变了旧管线会残留」
 *      —— 键必须无歧义，才能保证「同 Program 稳定、不同 Program 不碰撞」。
 *    许可证：本仓库自有文档（MIT 项目）→ 可并入；本文件是通用的「长度前缀文本编码」自造实现，
 *    编码方式（长度前缀消歧）属公开通用技术事实，不受版权保护。
 *    → 能否并入本项目（MIT）：本文件为独立实现，可并入
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码；参考模组零接触（07-CONSTRAINTS L12）。
 * 1. 官方/主实现：长度前缀编码（key=len:value），使字段边界不依赖分隔符是否出现在值里。
 * 2. 备选：直接拼接分隔符 —— 否决（值里出现分隔符时会碰撞：如 program="a=b" 与两字段组合）；
 *    用 String.hashCode 当键 —— 否决（冲突概率与可读性都差，且不属于「可打印比对」）。
 * 3. 我们的差异点：① 字段一行一个 key=len:value，人可读、可比对；
 *    ② 列表字段额外带元素个数前缀，并要求元素 cache-text-safe（无空白 / 无 '|'）；
 *    ③ 本编码只用于相等比较（键）与打印（证据），不做反解析，因此只要求单射性。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 冷路径（构建管线时算一次键），不做任何性能优化（18-PARALLEL §7.7）。
 */
import java.util.List;

/** 规范文本编码工具：长度前缀字段与带计数前缀的列表（保证编码单射，避免分隔符碰撞）。 */
final class CanonicalText {

    private CanonicalText() {
    }

    /**
     * 追加一个字段行：{@code key=len:value\n}。
     *
     * <p>长度前缀使字段边界与值内容无关，因此字段值的组合到文本是单射的
     * （同一个字段元组必得同一文本，不同元组必得不同文本）。
     */
    static void appendField(StringBuilder text, String key, String value) {
        text.append(key).append('=').append(value.length()).append(':').append(value).append('\n');
    }

    /**
     * 列表编码：{@code <count>:<v0>|<v1>|...}。
     *
     * <p>要求元素 cache-text-safe（不含空白与 '|'）—— 由调用方在入口处校验并抛 IllegalArgumentException，
     * 否则单射性不成立。
     */
    static String encodeList(List<String> values) {
        StringBuilder encoded = new StringBuilder();
        encoded.append(values.size()).append(':');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                encoded.append('|');
            }
            encoded.append(values.get(i));
        }
        return encoded.toString();
    }
}
