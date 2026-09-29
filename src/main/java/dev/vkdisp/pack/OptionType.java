package dev.vkdisp.pack;

/**
 * 【参考调研】选项值类型（OF/Iris 选项取值形态）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/08-TESTING.md §4（解析验收：shaders.properties 选项枚举、
 *    "const int X = 0; // [0 1 2]" 型注释选项必须能识别）与 docs/04-SPEC.md §3.1（Option/OptionType = 包自定义选项）；
 *    ② OptiFine 官方文档（sp614x/optifine，OptiFineDoc/doc/shaders.txt 与 shaders.properties）中选项取值形态的事实描述。
 *    许可证：sp614x/optifine 无 LICENSE（GitHub license API 404）→ ARR，按 07-CONSTRAINTS X20 不并入其文本表达，
 *    仅用不受版权保护的事实（true|false / 数值列表 / 文本值这些取值形态），零文本复制；Iris（LGPL-3.0，已核 LICENSE）同口径；
 *    参考模组（VulkanMod / Sulkan / Beryl）零接触。
 *    → 能否并入本项目（MIT）：本文件为独立实现的枚举，只含格式事实，可并入
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：OF 官方事实——布尔选项值域为 true/false（shaders.properties 写法 clouds=fast|fancy|off 属"多值文本"，
 *    oldLighting=true|false 属布尔）；GLSL 注释选项 const int X = 0; // [0 1 2] 为整数列表、
 *    const float X = 1.0; // [...] 为浮点列表；允许值列表用方括号空格分隔，properties 侧用竖线分隔，解析后统一进 Option#values。
 * 2. 备选：Iris 的选项类型划分（格式事实）；不读代码，只按同一取值形态归类。
 * 3. 我们的差异点：① 不引入"离散枚举 vs 连续区间"两个类型——二者都落在 INTEGER/FLOAT 上，
 *    "是否做成滑条"由 Option#slider（shaders.properties 的 sliders= 键）单独表达，GUI 侧自行决定渲染形态；
 *    ② 自由文本（值域为空列表）用 STRING 承载；③ 不在本枚举里编码默认值/标签，那些是 Option 的字段或 lang 层的事。
 * 4. 许可证核对：本项目 MIT；本文件零第三方代码复制，仅含文档事实。
 * 5. 性能基线：❄️ 冷路径（解析期一次性归类），不做任何性能优化（18-PARALLEL §7.7）。
 */

/**
 * 包自定义选项的值类型（枚举）。
 *
 * <p><b>冻结契约</b>：字段已按 {@code docs/18-PARALLEL.md} §3 F2 冻结，供 A/B/C/D/E/F 并行线与关键路径共同消费。
 * 任何字段/语义变更必须走 {@code 18-PARALLEL.md} §3.2 流程（提出方说明 → env-1 统一改 → 契约版本号 +1 → 通知各环境 rebase），
 * 任何一方不得自行增删改（{@code 07-CONSTRAINTS.md} X12）。冷路径数据，只求清晰、不做性能优化（§7.7）。
 *
 * <p><b>归类约定</b>（由解析层执行，结果写入 {@link Option#type()}）：
 * properties 形如 {@code key=true|false} 或 GLSL 形如 {@code const bool X = ...} → BOOLEAN（values 约定为 ["true","false"]）；
 * 允许值全部为整数 → INTEGER；全部为浮点 → FLOAT；其余 → STRING。
 */
public enum OptionType {

    /** 布尔开关（true / false）。约定 values = ["true", "false"]，defaultValue 为其中之一。 */
    BOOLEAN,

    /** 整数取值（允许值列表全为整数，如 "0 1 2"；可为离散枚举，也可被 sliders= 标成滑条）。 */
    INTEGER,

    /** 浮点取值（允许值列表全为浮点，如 "0.5 1.0 1.5"）。 */
    FLOAT,

    /** 文本取值（值域非数值非布尔，如 fast|fancy|off；values 为空列表时表示自由文本）。 */
    STRING
}
