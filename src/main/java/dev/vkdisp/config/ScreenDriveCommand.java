package dev.vkdisp.config;
/**
 * 【参考调研】P4.3 选项屏幕驱动指令（配置热加载边沿 → 屏幕动作的语法与解析）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §3.5（选项值语义在 PackOptions，本类只解析指令
 *    不碰值语义）；② docs/08-TESTING.md §6 执行方式段（P4.2 落地的"外部改值 → 热加载"驱动法 ——
 *    本类是它在选项屏幕上的延伸，同一取证通道）；③ docs/07-CONSTRAINTS.md T11（坏输入必须显式
 *    WARN，不静默吞）与 X9（语法是显式契约，不猜）。全部为仓库内自有文档事实。
 *    语法本身 = 本项目自定义（无外部参考对象）。
 *    许可证：本文件为独立实现的纯 Java 解析类 → 可并入本项目（MIT）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：无外部实现可参考（无输入注入的会话里，"驱动屏幕"的指令语法只能自定义；
 *    参考模组按禁止处理，零接触）。
 * 2. 备选：① 直接执行任意 Java 表达式 —— 否决（等价于开放脚本注入，违背最小能力原则）；
 *    ② 只有 open/done 两个动作、改值另开通道 —— 否决（改值必须与 open/done 同一驱动链，
 *    否则"改"的取证要引入第二种机制）；③ 解析失败也尝试执行 —— 否决（T11 要求显式拒绝）。
 * 3. 我们的差异点：
 *    ① 指令集封闭四件 + 三态解析结果：{@code ""} = None（中性边沿，不算错）、
 *       {@code open} / {@code set:名=值} / {@code page:页号} / {@code done} = 动作、
 *       其余 = Malformed（携带原因，调用方 WARN）——解析器永不抛；
 *    ② {@code set} 的值可含 {@code =}（取<b>第一个</b>{@code =} 之后的全部文本）——
 *       自由文本选项的值不受切分破坏；
 *    ③ 本类不执行任何动作：产出的是数据（sealed 结果），执行在
 *       {@code dev.vkdisp.screen.PackOptionsDrive}（MC 侧）—— 解析可单测，执行不可。
 * 4. 许可证核对结论：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 冷路径（配置热加载边沿才解析一次，字符串级），不做优化（T14）。
 */

/**
 * {@code vkdisp-client.toml} 的 {@code packOptionsScreen} 条目语法（P4.3 驱动）。
 *
 * <p>边沿语义见 {@code VkDispConfigHotReload}：值从上一次快照<b>变化</b>才解析执行一次
 * （重复保存同值不重复执行；执行后快照前移，下一动作写新值即可）。
 */
public sealed interface ScreenDriveCommand {

    /** {@code ""} / null / 空白：中性值，无动作（也不算错误 —— 把驱动值清回空串就是这个语义）。 */
    record None() implements ScreenDriveCommand {}

    /** {@code open}：打开选项屏幕。 */
    record Open() implements ScreenDriveCommand {}

    /** {@code set:选项名=值}：改屏幕当前会话里的一个选项（屏幕未开 = 调用方 WARN 拒绝）。 */
    record Set(String name, String value) implements ScreenDriveCommand {}

    /** {@code page:页号}：翻到指定页（1 基；越界钳到合法页）。 */
    record Page(int page1) implements ScreenDriveCommand {}

    /** {@code done}：提交 + 落盘 + 关屏 + 资源重载（屏幕未开 = 调用方 WARN 拒绝）。 */
    record Done() implements ScreenDriveCommand {}

    /** 非空但无法解析：携带原文与原因，调用方按 T11 打 WARN 后丢弃。 */
    record Malformed(String raw, String reason) implements ScreenDriveCommand {}

    /**
     * 解析驱动值。永不抛。
     *
     * <ul>
     *   <li>{@code null} / 空白 → {@link None}</li>
     *   <li>{@code open} → {@link Open}；{@code done} → {@link Done}</li>
     *   <li>{@code set:名=值} → {@link Set}（第一个 {@code =} 分割，值可再含 {@code =}）</li>
     *   <li>{@code page:N} → {@link Page}（N 为正整数）</li>
     *   <li>其余 → {@link Malformed}（含原因）</li>
     * </ul>
     */
    static ScreenDriveCommand parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new None();
        }
        String text = raw.trim();
        if ("open".equals(text)) {
            return new Open();
        }
        if ("done".equals(text)) {
            return new Done();
        }
        if (text.startsWith("set:")) {
            String body = text.substring("set:".length());
            int eq = body.indexOf('=');
            if (eq < 0) {
                return new Malformed(raw, "set: 缺少 '='（形如 set:选项名=值）");
            }
            String name = body.substring(0, eq).trim();
            String value = body.substring(eq + 1).trim();
            if (name.isEmpty()) {
                return new Malformed(raw, "set: 选项名为空");
            }
            if (value.isEmpty()) {
                return new Malformed(raw, "set: 值为空");
            }
            return new Set(name, value);
        }
        if (text.startsWith("page:")) {
            String body = text.substring("page:".length()).trim();
            try {
                int page = Integer.parseInt(body);
                if (page < 1) {
                    return new Malformed(raw, "page: 页号必须 ≥ 1（1 基）");
                }
                return new Page(page);
            } catch (NumberFormatException e) {
                return new Malformed(raw, "page: 页号不是整数: '" + body + "'");
            }
        }
        return new Malformed(raw, "未知指令（可用: open / set:选项名=值 / page:页号 / done）");
    }
}
