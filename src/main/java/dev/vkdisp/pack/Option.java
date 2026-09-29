package dev.vkdisp.pack;

import java.util.ArrayList;
import java.util.List;

/**
 * 【参考调研】包自定义选项（shaders.properties 枚举 + GLSL 注释型选项）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/08-TESTING.md §4（解析验收点名：shaders.properties 的选项能被枚举、
 *    sliders/profiles 能被列出、"const int X = 0; // [0 1 2]" 注释型选项能被识别）与 docs/04-SPEC.md §3.1
 *    （Option/OptionType = 包自定义选项；ShaderProperties 负责选项/开关/profiles/sliders 的解析）；
 *    ② OptiFine 官方文档（sp614x/optifine，OptiFineDoc/doc/shaders.txt 与 shaders.properties）中选项来源与语法的事实。
 *    许可证：sp614x/optifine 无 LICENSE（GitHub license API 404）→ ARR，按 07-CONSTRAINTS X20 不并入其文本表达，
 *    仅用不受版权保护的事实性信息（键名/语法形态），零文本复制；Iris（LGPL-3.0，已核 LICENSE）同口径；
 *    参考模组（VulkanMod / Sulkan / Beryl）零接触。
 *    → 能否并入本项目（MIT）：本文件为独立实现的 record，只含格式事实，可并入
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：OF 官方事实——选项有两个来源：(a) shader 源文件里的宏/常量注释（#define NAME 或 // #define NAME、
 *    #define NAME 值 // 允许值 [v1 v2 v3]、const int NAME = 值; // 允许值 [v1 v2 v3]，行尾注释兼作 tooltip）；
 *    (b) shaders.properties 的键值式选项（key=值1|值2|值3）。sliders= 列出做滑条的选项，screen= / screen.NAME= 配置界面分组，
 *    profile.NAME= 是选项预设（归 ShaderPack#profiles，不在本类）。
 * 2. 备选：Iris 的选项模型（格式事实）；只取"名字/默认值/允许值/是否滑条"这组要素，不读其代码。
 * 3. 我们的差异点：① 同名选项出现在多个 shader 文件时 OF 会联动切换、默认值冲突时禁用该选项——
 *    这是解析层策略（打 WARN、禁用），不进本模型字段；② lang 标签（option.NAME / value.NAME）与 tooltip 展示层暂不入契约，
 *    需要时走 18-PARALLEL §3.2 增补；③ screen 字段只存该选项归属的子屏名（"" = 主屏或未配置），
 *    screen= 的完整结构（链接 [NAME]、<profile>、通配 * 、columns）归 A 线 pack/properties/ 的 ShaderProperties。
 * 4. 许可证核对：本项目 MIT；本文件零第三方代码复制，仅含文档事实。
 * 5. 性能基线：❄️ 冷路径（加载/重载时构造一次），不做任何性能优化（18-PARALLEL §7.7）。
 */

/**
 * 包自定义选项（record，不可变）。
 *
 * <p><b>冻结契约</b>：字段已按 {@code docs/18-PARALLEL.md} §3 F2 冻结，供 A/B/C/D/E/F 并行线与关键路径共同消费
 * （F 线 OptionBinding：选项值 → #define 表 / uniform 值；A 线解析产出本对象）。
 * 任何字段/语义变更必须走 {@code 18-PARALLEL.md} §3.2 流程（提出方说明 → env-1 统一改 → 契约版本号 +1 → 通知各环境 rebase），
 * 任何一方不得自行增删改（{@code 07-CONSTRAINTS.md} X12）。冷路径数据，只求清晰、不做性能优化（§7.7）。
 *
 * @param name         选项名（宏/常量/属性键，如 "SSAO"、"oldLighting"）；非空白，构造时裁剪首尾空白
 * @param type         值类型（归类约定见 {@link OptionType}）
 * @param defaultValue 默认值的规范化文本（如 "true"、"0"、"1.5"）；非 null，空串表示解析层未取到默认值（解析层应同时打 WARN，T11）
 * @param values       允许值列表，顺序即原文顺序（GLSL 注释 "[0 1 2]" 或 properties "a|b|c" 归一化而来）；
 *                     允许为空列表（自由文本），元素非空白
 * @param slider       是否列在 shaders.properties 的 {@code sliders=} 中（做滑条展示）
 * @param screen       所属选项界面的子屏名（{@code screen.NAME} 的 NAME）；"" = 主屏或未配置
 */
public record Option(
        String name,
        OptionType type,
        String defaultValue,
        List<String> values,
        boolean slider,
        String screen) {

    /** 冻结契约（18-PARALLEL §3 F2）：字段变更走 §3.2 流程，由 env-1 统一改。完整声明见类级 Javadoc。 */
    public Option {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("vkdisp: option name must not be blank");
        }
        name = name.trim();
        if (type == null) {
            throw new IllegalArgumentException("vkdisp: option '" + name + "' type must not be null");
        }
        if (defaultValue == null) {
            throw new IllegalArgumentException("vkdisp: option '" + name + "' defaultValue must not be null (use \"\" when unknown)");
        }
        defaultValue = defaultValue.trim();
        if (values == null) {
            throw new IllegalArgumentException("vkdisp: option '" + name + "' values must not be null (use an empty list for free text)");
        }
        List<String> copiedValues = new ArrayList<>(values.size());
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("vkdisp: option '" + name + "' values must not contain null/blank entries");
            }
            copiedValues.add(value.trim());
        }
        values = List.copyOf(copiedValues);
        if (screen == null) {
            throw new IllegalArgumentException("vkdisp: option '" + name + "' screen must not be null (use \"\" for the main screen)");
        }
        screen = screen.trim();
    }
}
