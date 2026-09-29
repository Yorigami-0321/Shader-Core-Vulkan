package dev.vkdisp.config;
/**
 * 【参考调研】F 线单测 / 选项 fixture（F2 冻结契约的最小构造器）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库自有冻结契约 dev.vkdisp.pack.Option / OptionType / ShaderPack（MIT 项目自有）
 *    与 docs/18-PARALLEL.md §7.6（fixture 与第三方素材限制：只允许自造最小样本）。
 *    许可证：本项目自有类型（MIT）→ 可直接构造；JUnit 5 = EPL-2.0，仅 testImplementation 依赖，不进分发 jar。
 *    第三方 shaderpack / 参考模组零接触（07-CONSTRAINTS L12 / X20）。
 *    → 能否并入本项目（MIT）：可以（本文件为独立编写的测试脚手架，数据全部自造）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：F2 冻结契约的公开构造器（Option 的 6 字段、ShaderPack 的 8 字段）。
 * 2. 备选：每个测试类各写一份构造代码 —— 否决（重复，且"字段顺序写错"这类低级错误会散落各处）。
 * 3. 我们的差异点：fixture 只造内存对象，不读任何文件、不起游戏、不碰 GPU（18-PARALLEL §2 并行判据）。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：测试代码不进运行时，无性能影响。
 */
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.vkdisp.pack.Option;
import dev.vkdisp.pack.OptionType;
import dev.vkdisp.pack.ShaderPack;

/** F 线单测用的选项 / 包构造器（自造最小样本，不含任何第三方素材）。 */
final class OptionFixtures {

    private OptionFixtures() {
    }

    static Option option(String name, OptionType type, String defaultValue, List<String> values) {
        return new Option(name, type, defaultValue, values, false, "");
    }

    static Option slider(String name, String defaultValue, String... values) {
        return new Option(name, OptionType.INTEGER, defaultValue, List.of(values), true, "");
    }

    static Option integer(String name, String defaultValue, String... values) {
        return option(name, OptionType.INTEGER, defaultValue, List.of(values));
    }

    static Option floating(String name, String defaultValue, String... values) {
        return option(name, OptionType.FLOAT, defaultValue, List.of(values));
    }

    /** 布尔选项：不给允许值时按 F2 约定用 ["true","false"]。 */
    static Option bool(String name, String defaultValue, String... values) {
        return option(name, OptionType.BOOLEAN, defaultValue,
                values.length == 0 ? List.of("true", "false") : List.of(values));
    }

    static Option text(String name, String defaultValue, String... values) {
        return option(name, OptionType.STRING, defaultValue, List.of(values));
    }

    /** 无选项、无 profile 的最小包（F2 契约允许空 programs / 空 options / 空 dimensionFolders）。 */
    static ShaderPack pack(Option... options) {
        return new ShaderPack("fixture", "/tmp/fixture", false, List.of(), List.of(options),
                Map.of(), Map.of(), Set.of());
    }

    /** 带 profile 预设的最小包（条目语法见 F2 ShaderPack 的【参考调研】第 1 条）。 */
    static ShaderPack pack(Map<String, List<String>> profiles, Option... options) {
        return new ShaderPack("fixture", "/tmp/fixture", false, List.of(), List.of(options),
                profiles, Map.of(), Set.of());
    }
}
