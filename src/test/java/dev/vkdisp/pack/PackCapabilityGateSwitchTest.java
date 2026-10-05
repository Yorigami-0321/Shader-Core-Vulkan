package dev.vkdisp.pack;
/**
 * 【参考调研】能力门控**开关读取侧**的回归守卫（h33）
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓库 {@code docs/07-CONSTRAINTS.md} X9（不猜）
 *    与 T11（降级必须可见），以及已闭环的 QD-02「死开关」失败形态。
 *    全部为本仓库自有文档事实，无外部代码。
 * 1. 官方/主实现：无（纯文本断言）。
 * 2. 备选：无。
 * 3. 我们的差异点：把「反射字段名与配置键名必须是两个东西」变成构建期红灯。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 单测。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code PackCapabilityGateSwitch} 的回归守卫。
 *
 * <p>🔖 <b>守的是什么</b>：h33 由 MCP 驱动 runClient 抓到 —— 用户把
 * {@code pack.capabilityGate = true} 写进配置、日志照打
 * 「能力门控已由配置关闭（pack.capabilityGate=false）」，
 * 而代码里反射的是 {@code getField("pack.capabilityGate")}，
 * 真实字段名却是 {@code CAPABILITY_GATE} ⇒ 每次 {@link NoSuchFieldException}
 * ⇒ 被 {@code catch (Throwable)} 吞掉 ⇒ <b>开关恒为关且毫无异常</b>。
 *
 * <p>这与已闭环的 QD-02（{@code debugLog} 死开关）是同一族失败形态：
 * <b>不报错、不崩溃、只是永远不生效</b>。
 */
class PackCapabilityGateSwitchTest {

    private static final Path SWITCH =
            Path.of("src/main/java/dev/vkdisp/pack/PackCapabilityGateSwitch.java");
    private static final Path CONFIG =
            Path.of("src/main/java/dev/vkdisp/VkDispConfig.java");

    private static String readOrSkip(Path path) {
        Assumptions.assumeTrue(Files.exists(path), "工程文件缺失: " + path);
        try {
            return Files.readString(path);
        } catch (java.io.IOException e) {
            throw new AssertionError("读取失败: " + path, e);
        }
    }

    /** 只看**非注释行**：本文件的 javadoc 会引用代码字样（如 {@code LoggerFactory}）来说明踩过的坑。 */
    private static boolean codeContains(String text, String literal) {
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                continue;
            }
            if (line.contains(literal)) {
                return true;
            }
        }
        return false;
    }

    @BeforeEach
    void resetOverrideSlot() {
        // 单测覆盖槽是静态的 ⇒ 必须复位，否则会漏进别的测试（QD-03 同族的测试隔离问题）。
        PackCapabilityGateSwitch.override(null);
    }

    @Test
    @DisplayName("🔖🔖 反射用的字段名必须与配置键名分开（h33 的真 bug：二者曾共用一个常量）")
    void fieldNameIsNotTheConfigKey() {
        assertNotEquals(PackCapabilityGateSwitch.CONFIG_KEY, PackCapabilityGateSwitch.FIELD_NAME,
                "配置键名（pack.capabilityGate）不是 Java 字段名（CAPABILITY_GATE）。"
                        + "曾把二者合成一个常量并当字段名用 ⇒ NoSuchFieldException 被吞 ⇒ 开关恒为关");
        assertEquals("pack.capabilityGate", PackCapabilityGateSwitch.CONFIG_KEY,
                "配置键名必须与 VkDispConfig#define 的键逐字一致");
    }

    @Test
    @DisplayName("🔖🔖 反射必须用 FIELD_NAME，且 FIELD_NAME 必须真的存在于 VkDispConfig")
    void reflectionTargetActuallyExists() {
        String source = readOrSkip(SWITCH);
        assertFalse(codeContains(source, "getField(CONFIG_KEY)"),
                "🔴 不得用配置键名做反射 —— 这正是 h33 的 bug 本体");
        assertTrue(codeContains(source, "getField(FIELD_NAME)"),
                "反射必须显式用字段名常量");

        String config = readOrSkip(CONFIG);
        assertTrue(config.contains(" " + PackCapabilityGateSwitch.FIELD_NAME + " ="),
                "VkDispConfig 上必须真的存在字段 " + PackCapabilityGateSwitch.FIELD_NAME
                        + " —— 否则反射永远失败且开关静默失效");
    }

    @Test
    @DisplayName("🔖 字段找不到必须**吵出来**（不得静默当成默认关）")
    void missingFieldIsLoud() {
        String source = readOrSkip(SWITCH);
        assertTrue(source.contains("catch (NoSuchFieldException"),
                "NoSuchFieldException 必须单独捕获 —— 它表示真错误（字段被改名/被删），"
                        + "不是「默认关」");
        assertTrue(source.contains("noteReflectionFailure"),
                "字段找不到必须走一条可见的报错路径（T11）");
        assertFalse(codeContains(source, "LoggerFactory"),
                "🔴 本类**不得**直接用 slf4j：单测 classpath 上没有 slf4j"
                        + "（h33 实测 PackBooleanOptionTest 因此炸成 NoClassDefFoundError），"
                        + "诊断手段不该把无关测试拖挂。改为记录原因、由 PackTerrainSource"
                        + "走既有 TranslateDiagnostic 管道输出");
        assertTrue(source.contains("public static String reflectionFailure()"),
                "失败原因必须可被调用方取到并显示出来");
    }

    @Test
    @DisplayName("🔖 默认必须仍然是关（改变包语义的动作由用户显式开启）")
    void defaultStaysOff() {
        assertFalse(PackCapabilityGateSwitch.DEFAULT_ENABLED,
                "能力门控会关掉包特性 ⇒ 默认必须关，否则用户会「莫名其妙少了特性」");
    }

    @Test
    @DisplayName("🔖 覆盖槽可用（单测必须能在无 FML 环境驱动门控这条分支）")
    void overrideSlotWorks() {
        PackCapabilityGateSwitch.override(() -> true);
        assertTrue(PackCapabilityGateSwitch.enabled(), "覆盖为 true 时必须生效");
        PackCapabilityGateSwitch.override(() -> false);
        assertFalse(PackCapabilityGateSwitch.enabled(), "覆盖为 false 时必须生效");
        PackCapabilityGateSwitch.override(null);
        // 无 FML 环境 ⇒ 回落到默认值，而不是抛异常。
        assertEquals(PackCapabilityGateSwitch.DEFAULT_ENABLED, PackCapabilityGateSwitch.enabled(),
                "没有覆盖、类又不在 classpath 时必须回落默认值");
    }
}
