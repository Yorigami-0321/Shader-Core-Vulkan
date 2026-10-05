package dev.vkdisp;
/**
 * 【参考调研】包选项类配置项的**热加载接线**守卫（死开关那一族的第三个实例）
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓库自有文档
 *    {@code docs/07-CONSTRAINTS.md} T11（降级必须可见）+ X9（不猜），
 *    以及两处**已实测**的同类缺陷：
 *    <ul>
 *      <li>QD-02「{@code debugLog} 死开关」：有定义、有快照、**零消费点**；</li>
 *      <li>h33「{@code PackCapabilityGateSwitch} 死开关」：反射用配置键名当 Java 字段名
 *          ⇒ 每次 {@code NoSuchFieldException} 被 {@code catch (Throwable)} 吞掉
 *          ⇒ 开关恒默认关，日志照打、无异常。</li>
 *    </ul>
 *    许可证：本仓库自有文档 → 可并入（MIT）；零第三方代码。
 *    → 能否并入本项目（MIT）：可以（测试只做源码文本断言）
 *    → 例外条款：无
 * 1. 官方/主实现：无（本项目自定的接线纪律）。
 * 2. 备选：无。
 * 3. 我们的差异点：把「包选项类配置项必须在快照里、且必须触发资源重载」做成构建期红灯。
 *    🔖 <b>为什么这类缺陷值得单独守卫</b>：本项目已经有<b>三个</b>实例
 *    （QD-02 / h33 / 本轮），形态完全一样 ——「配置项存在、能读、但改了不生效」，
 *    而且**日志看起来完全正常**。前两次是逐个发现的，第三次必须变成守卫。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 单测（扫源码）。
 */
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 包选项类配置项的热加载接线守卫。
 *
 * <p>🔖 三个实例：QD-02（{@code debugLog}）、h33（能力门控反射）、
 * 本轮实测（{@code pack.optionOverrides} 改了不重载）。前两个是一次一个发现的，
 * 本类把「这一族」变成有红灯的类别而不是逐个排查的偶发事件。
 */
class PackOptionSwitchReloadTest {

    private static final Path RELOAD = Path.of("src/main/java/dev/vkdisp/VkDispConfigHotReload.java");
    private static final Path CONFIG = Path.of("src/main/java/dev/vkdisp/VkDispConfig.java");

    private static String readOrSkip(Path path) {
        Assumptions.assumeTrue(Files.exists(path), "工程文件缺失: " + path);
        try {
            return Files.readString(path);
        } catch (java.io.IOException e) {
            throw new AssertionError("读取失败: " + path, e);
        }
    }

    @Test
    @DisplayName("🔖🔖 包选项类配置项必须进快照且触发重载（否则改了不生效且日志正常）")
    void packOptionSwitchesAreInSnapshotAndTriggerReload() {
        String reload = readOrSkip(RELOAD);
        // ① 快照必须含这两项（否则边沿判定看不到变化 ⇒ 不重载）。
        assertTrue(reload.contains("VkDispConfig.CAPABILITY_GATE.get()"),
                "能力门控必须进快照 —— 它只在包源生成时被读一次，不重载就等于改了不生效");
        assertTrue(reload.contains("VkDispConfig.OPTION_OVERRIDES.get()"),
                "单变量覆盖必须进快照 —— 同上");
        // ② 边沿判定必须比较这两项。
        assertTrue(reload.contains("previous.capabilityGate() != current.capabilityGate()"),
                "快照里有它但边沿判定不比较它 = 进了快照也没用（比不记还坏：看起来做了）");
        assertTrue(reload.contains("previous.optionOverrides()"),
                "同上：optionOverrides 的变化必须参与边沿判定");
        // ③ 重载日志必须自报这两项（否则取证者无法从日志确认「配置确实被读到了」）。
        assertTrue(reload.contains("optionOverrides='{}'"),
                "热重载日志必须打出 optionOverrides —— 改完配置却看不到它出现在日志里，"
                        + "与「配置项没被读到」在观测上无法区分");
    }

    @Test
    @DisplayName("🔖 三个开关都必须有对应配置项（快照引用的字段必须真实存在）")
    void snapshotReferencesRealConfigFields() {
        String config = readOrSkip(CONFIG);
        for (String field : java.util.List.of(
                "CAPABILITY_GATE", "OPTION_OVERRIDES", "ENABLED", "DEBUG_LOG",
                "PACK_PROFILE", "SHADER_PACK", "PACK_OPTIONS_SCREEN")) {
            assertTrue(config.contains("ModConfigSpec."), "配置类读不到（测试前提失效）");
            assertTrue(config.contains(field),
                    "VkDispConfig 上没有字段 " + field + " —— 快照引用了不存在的字段，"
                            + "编译能过但运行期会抛/静默；这是 h33 那类缺陷的温床");
        }
    }

    @Test
    @DisplayName("🔖 optionOverrides 必须是字符串项（它是 NAME=value 列表，不是布尔）")
    void optionOverridesIsAStringEntry() {
        String config = readOrSkip(CONFIG);
        assertTrue(config.contains("define(\"pack.optionOverrides\", \"\")"),
                "pack.optionOverrides 必须是默认空串的字符串项 —— "
                        + "空串 = 不覆盖（与能力门控默认关同一条纪律：改变包语义由用户显式开启）");
    }
}