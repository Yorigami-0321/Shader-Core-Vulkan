package dev.vkdisp;
/**
 * 【参考调研】虚拟包「地形契约记忆键」的完整性守卫（死开关同族的第四个实例）
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓库自有文档
 *    ① {@code docs/07-CONSTRAINTS.md} T11（降级必须可见）+ X9（不猜）；
 *    ② 本仓库已实测的三处同类缺陷：
 *    <ul>
 *      <li>QD-02「{@code debugLog} 死开关」：有定义、有快照、<b>零消费点</b>；</li>
 *      <li>h33「{@code PackCapabilityGateSwitch} 死开关」：反射用配置键名当 Java 字段名
 *          ⇒ 异常被吞 ⇒ 开关恒默认关；</li>
 *      <li>本轮实测「{@code pack.optionOverrides} 不进地形记忆键」：改覆盖串会触发资源重载，
 *          composite 源按新覆盖重编（日志里覆盖表确实变了），而地形契约因键未变
 *          <b>返回旧 memo</b> ⇒ 两条链对同一配置给出不同答案，
 *          且<b>没有一行日志</b>说「地形契约被记忆命中」。</li>
 *    </ul>
 *    许可证：本仓库自有文档 + 自有代码（MIT）；零第三方代码。
 *    → 能否并入本项目（MIT）：可以（测试只做源码文本断言）
 *    → 例外条款：无
 * 1. 官方/主实现：无（本项目自定的接线纪律）。
 * 2. 备选：无。
 * 3. 我们的差异点：把「凡是<b>在生成期被读一次</b>的配置项，都必须进地形契约的记忆键」
 *    做成构建期红灯，并把判据写成**可枚举的清单**而不是「记得加进去」。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 单测（扫源码）。
 */
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 地形契约记忆键的守卫。
 *
 * <p>🔖 本类存在的理由：这一族缺陷（配置项存在、能读、但某条链静默不生效）在本项目
 * 已经出现<b>四次</b>，形态完全一样，且日志看起来永远正常。前三次是一次一个发现的，
 * 本类把它变成有红灯的类别。
 */
class VirtualPackMemoKeyTest {

    private static final Path PACK = Path.of("src/main/java/dev/vkdisp/VkDispVirtualPack.java");

    private static String readOrSkip() {
        Assumptions.assumeTrue(Files.exists(PACK), "工程文件缺失: " + PACK);
        try {
            return Files.readString(PACK);
        } catch (java.io.IOException e) {
            throw new AssertionError("读取失败: " + PACK, e);
        }
    }

    @Test
    @DisplayName("🔖🔖 记忆键必须含包选项覆盖串（否则两条链对同一配置给出不同答案）")
    void memoKeyIncludesOptionOverrides() {
        String pack = readOrSkip();
        int keyAt = pack.indexOf("String key = currentTerrainMemoKey()");
        assertTrue(keyAt > 0,
                "找不到地形契约记忆键的构造（应为 `String key = currentTerrainMemoKey()`）—— 先修这里");
        // 🔖 窗口覆盖整个方法体：实现可能把覆盖串算在局部变量里再拼进键，
        //   只看键那一行会漏判（本测试首版就这么误报了一次 —— 守卫自己出假阴性，
        //   与 h41「诊断说了假话」同族）。
        int methodEnd = pack.indexOf("private static String currentTerrainMemoKey()", keyAt);
        assertTrue(methodEnd > keyAt, "找不到 currentTerrainMemoKey（键的单一真源）—— 先修实现");
        String around = pack.substring(keyAt, methodEnd);
        assertTrue(around.contains("currentTerrainMemoKey()"),
                "ensureTerrainProgram 必须用 currentTerrainMemoKey() 造键 —— "
                        + "「造键用 A、校验用 B」会让取走时的校验永远通过（本轮首版就这么写的）");
        int specAt = pack.indexOf("PackOptionOverrideSwitch.spec()");
        assertTrue(specAt > pack.indexOf("private static String currentTerrainMemoKey()")
                        && specAt < pack.indexOf("}", pack.indexOf(
                                "private static String currentTerrainMemoKey()")),
                "currentTerrainMemoKey 内必须读选项覆盖串（pack.optionOverrides）。"
                        + "地形契约记忆键必须含它。实测症状：改覆盖串 → 资源重载 → "
                        + "composite 源按新覆盖重编（覆盖表变了），"
                        + "而地形契约因键未变返回旧 memo ⇒「覆盖已生效」与「地形画面没变」并存，"
                        + "且没有任何日志说「地形契约被记忆命中」。"
                        + "这与 h33 死开关、QD-02 死开关同族。");
        // 🔖🔖 取走时必须核对键（本轮实测：重载后 memo 还在，按旧配置生成）。
        //   ⚠️ GAP-027 逐程序化之后，「取走」只有**一条**路径：takeSourceMemo(String)
        //   （地形那两个方法已退化为一行委托）⇒ 守卫必须断言这一条路径。
        //   旧版断言 takeTerrainSourceMemo() 的方法体内含核对 —— 逐程序化之后必然失败，
        //   而它要求的是「把实现改回去」。**守卫不是主人**：契约要写成「性质」
        //   （取走必核对键 / 键的算法只有一份），不是「代码长什么样」。
        String takeBody = bracedBody(pack, "private static String takeSourceMemo(String program)");
        assertTrue(takeBody.contains("memoKeyFor()"),
                "取走 memo 时必须核对键 —— ensureTerrainProgram 只在**管线注册期**调一次，"
                        + "而注册事件在资源重载时不再触发 ⇒ 重载后 memo 若还在，"
                        + "它就是按上一轮配置生成的那一份（本轮实测踩到）");
        assertTrue(takeBody.contains("entry.memoKey"),
                "核对键必须真的比较「生成时的键 vs 当前键」，只算出来不用 = 白算");
        // 🔖 逐程序化之后新增的性质：地形也走这条统一路径（否则它被漏检而没人发现）。
        assertTrue(bracedBody(pack, "private static String takeTerrainSourceMemo()")
                        .contains("takeSourceMemo("),
                "地形片元取走必须委托给统一路径 takeSourceMemo(String)");
        assertTrue(bracedBody(pack, "private static String takeTerrainAdapterMemo()")
                        .contains("takeAdapterMemo("),
                "地形适配层取走必须委托给统一路径 takeAdapterMemo(String)");
        // 🔖🔖 键的算法只有一个：每条程序的键 = currentTerrainMemoKey() + "|" + 程序名。
        //   缺程序名后缀 ⇒ 两条程序互相命中对方的 memo（水的源被当地形的用）。
        String keyFor = bracedBody(pack, "String memoKeyFor()");
        assertTrue(keyFor.contains("currentTerrainMemoKey()"),
                "memoKeyFor 必须复用单一真源 currentTerrainMemoKey()（造键与校验同一算法）");
        assertTrue(keyFor.contains("program"),
                "逐程序的键必须带程序名后缀，否则两条程序会互相命中对方的 memo");
    }

    @Test
    @DisplayName("🔖 清单可枚举：生成期被读一次的配置项都在键里")
    void memoKeyCoversEveryGenerationTimeSwitch() {
        String pack = readOrSkip();
        int keyAt = pack.indexOf("String key = currentTerrainMemoKey()");
        assertTrue(keyAt > 0, "ensureTerrainProgram 必须用 currentTerrainMemoKey() 造键（单一真源）");
        // 这两项就是「在生成期被读、且改变生成结果」的既有配置项。
        // 新增同类项时必须同步进这个清单，否则守卫不会提醒（这是清单型守卫的已知代价，
        // 故这里把清单显式写出来，让人一眼看出「要加就加在这里」）。
        for (String read : List.of("PACK_PROFILE", "SHADER_PACK")) {
            assertTrue(pack.contains(read),
                    "生成期配置项 " + read + " 在本类里消失了 —— 守卫清单与实现已脱节，先修守卫");
        }
        int keyBodyStart = pack.indexOf("private static String currentTerrainMemoKey()");
        assertTrue(keyBodyStart > 0);
        // 🔖🔖 取**方法体**（花括号配平）而不是固定长度窗口：h45 在键里补两项后，
        //   方法体变长，固定 500 字的窗口**截不到末尾**的覆盖串 ⇒ 本守卫误报。
        //   「守卫因实现变长而误报」是把人引向改对代码的死路，必须消灭。
        String keyBody = bracedBody(pack, "private static String currentTerrainMemoKey()");
        assertTrue(keyBody.contains("PACK_PROFILE") && keyBody.contains("SHADER_PACK")
                        && keyBody.contains("PackOptionOverrideSwitch"),
                "currentTerrainMemoKey 必须同时含 profile / selection / 覆盖串 —— "
                        + "键里少任何一项，那一项对应的配置改了都不会触发重算");
        // 🔖 QD-08 收口：生成窗口内被读的另两项（当生成闸门用）也必须在键里。
        assertTrue(keyBody.contains("ENABLED") && keyBody.contains("MRT_PACK_TERRAIN_SHADER"),
                "ENABLED 与 MRT_PACK_TERRAIN_SHADER 在生成窗口内被读取（生成前的闸门），"
                        + "必须在记忆键里 —— 否则改动它们不触发地形契约重算，"
                        + "而 openResources 侧按新值走，两条链互相矛盾而日志全正常（QD-08 本体）");
        // 🔖 GAP-027 新增项：水那条的生成闸门（mrt.packWater）同样在本窗口内被读
        //   ⇒ 它必须进键（QD-08 第五例）。守卫要跟着新开关一起长，否则
        //   「加了新开关但忘了进键」下次照样能穿过去。
        assertTrue(keyBody.contains("MRT_PACK_WATER_SHADER"),
                "mrt.packWater 是 GAP-027 的生成闸门，必须在记忆键里");
    }

    @Test
    @DisplayName("🔖 记忆命中必须是**静默无日志**这件事本身要被记下来（否则同类缺陷还会再来）")
    void memoHitIsDocumentedAsSilentFailure() {
        // 本测试不检查行为，只要求「记忆键不完整 = 静默」这个性质在源码里有明确记录。
        // 没有记录时，后来人加配置项就不会意识到要同步进键。
        String pack = readOrSkip();
        assertTrue(pack.contains("地形契约的记忆键") || pack.contains("记忆键"),
                "记忆键的说明必须留在源码里（它是「要同步加项」的唯一提示）");
    }

    /**
     * 取「签名之后第一个花括号块」的原文（花括号配平）。
     *
     * <p>🔖 为什么不用固定长度窗口：h45 在键里补两项后方法体变长，固定 500 字的窗口
     * <b>截不到末尾</b>的覆盖串 ⇒ 守卫误报。而「守卫因实现变长而误报」会把人引向
     * 改对代码的死路，必须消灭（本类里已经吃过一次同族的亏）。
     */
    private static String bracedBody(String src, String signature) {
        int at = src.indexOf(signature);
        assertTrue(at > 0, "方法没找到: " + signature);
        String code = src.substring(at);
        int open = code.indexOf('{');
        assertTrue(open > 0, "方法没有花括号体: " + signature);
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return code.substring(0, i + 1);
                }
            }
        }
        throw new AssertionError("花括号不配平: " + signature);
    }
}