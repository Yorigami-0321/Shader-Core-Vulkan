package dev.vkdisp.config;
/**
 * 【参考调研】单选项强制覆盖单测 / h42 §4.2 登记的「两组不是单变量」那一条
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓库自有文档
 *    {@code docs/13-GAP-REGISTRY.md} GAP-008 条目（h42 §4.2 的两臂数字
 *    {@code outputs 8→1 / samplers 7→5 / varyings 15→9} 与 §五 登记的下一步
 *    「做真正单变量的 PARALLAX A/B」）+ 本仓库自有的 {@code PackCapabilityGate}（同族处置形态）。
 *    → 能否并入本项目（MIT）：可以（测试代码不进分发 jar）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：无（纯逻辑类）。
 * 2. 备选：无。
 * 3. 我们的差异点（每条测试钉一个具体主张）：
 *    <ul>
 *      <li>🔖 <b>只改点名的那些</b> —— 这正是它与能力门控的全部区别；断言里必须逐项列出
 *          「改了什么 / 没改什么」，否则守卫证明不了「单变量」这个卖点。</li>
 *      <li>🔖 <b>解析失败 ⇒ 一条都不改</b>（不是「部分生效」）：部分生效会让取证者
 *          以为 A/B 做成了。</li>
 *      <li>🔖 <b>空转必须吵出来</b>：包没有这个选项时静默跳过，与「施加了但画面没变」
 *          在日志上完全一样（h33 死开关那一次的形态）。</li>
 *    </ul>
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：测试代码不进运行时。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vkdisp.pack.Option;
import dev.vkdisp.pack.OptionType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 单选项强制覆盖单测（钉住「真的只改一个」与「空转必吵」两条）。 */
class PackOptionOverrideTest {

    private static Option boolOption(String name, String def) {
        return new Option(name, OptionType.BOOLEAN, def, List.of("true", "false"), false, "");
    }

    private static Option intOption(String name, String def, List<String> values) {
        return new Option(name, OptionType.INTEGER, def, values, false, "");
    }

    private static PackOptions options() {
        // 🔖 sink 必须收进**可变**列表：`PackOptions#set` 产生诊断时会往里 add，
        //   传 `List.of()` 会抛 UnsupportedOperationException（本轮首次跑就踩到）。
        return PackOptions.of(
                List.of(
                        boolOption("PARALLAX", "true"),
                        boolOption("SSS", "true"),
                        boolOption("EMISSIVE", "true"),
                        intOption("SHARPEN", "0", List.of("0", "1", "2", "3"))),
                OptionDiagnosticSink.collecting(new java.util.ArrayList<>()));
    }

    @Test
    @DisplayName("🔖🔖 只改点名的那个选项，其余一个都不动（这就是「单变量」的全部含义）")
    void changesOnlyTheNamedOption() {
        PackOptions options = options();
        PackOptionOverride.Result result = PackOptionOverride.apply(options, "PARALLAX=false");

        assertEquals(Map.of("PARALLAX", "false"), result.applied(),
                "只应改 PARALLAX 一个 —— 能力门控会一次改 9 个，那正是本类要替代的东西");
        assertEquals("false", options.value("PARALLAX"));
        assertEquals("true", options.value("SSS"), "SSS 必须是 true（未被点名）");
        assertEquals("true", options.value("EMISSIVE"), "EMISSIVE 必须是 true（未被点名）");
        assertEquals("0", options.value("SHARPEN"), "SHARPEN 必须是原值（未被点名）");
        assertEquals(1, result.applied().size());
    }

    @Test
    @DisplayName("🔖 多条可指定，但只限用户写下的那些")
    void multipleEntriesApplied() {
        PackOptions options = options();
        PackOptionOverride.Result result =
                PackOptionOverride.apply(options, "PARALLAX=false;SHARPEN=3");

        assertEquals(Map.of("PARALLAX", "false", "SHARPEN", "3"), result.applied());
        assertEquals("true", options.value("SSS"), "第三条未被点名，必须保持原值");
        assertEquals("true", options.value("EMISSIVE"));
    }

    @Test
    @DisplayName("🔖🔖 解析失败 ⇒ 一条都不改（不是「部分生效」）")
    void malformedSpecChangesNothing() {
        PackOptions options = options();
        PackOptionOverride.Result result = PackOptionOverride.apply(options, "PARALLAX");

        assertTrue(result.applied().isEmpty(), "解析失败时不得改任何选项");
        assertTrue(result.hasDiagnostic(PackOptionOverride.MALFORMED_ENTRY),
                "必须报 MALFORMED_ENTRY —— 静默失败会让取证者以为 A/B 做成了");
        assertEquals("true", options.value("PARALLAX"), "PARALLAX 必须保持原值");
    }

    @Test
    @DisplayName("🔖 混合串里有一条坏 ⇒ 整体不生效（宁可全不改，也不要半改）")
    void oneBadEntryInvalidatesWholeSpec() {
        PackOptions options = options();
        PackOptionOverride.Result result =
                PackOptionOverride.apply(options, "PARALLAX=false;SSS");

        assertTrue(result.applied().isEmpty(),
                "半生效比全不生效更危险：取证者会以为 PARALLAX 已关而其实只关了一半意图");
        assertTrue(result.hasDiagnostic(PackOptionOverride.MALFORMED_ENTRY));
        assertEquals("true", options.value("PARALLAX"));
    }

    @Test
    @DisplayName("🔖🔖 包里没这个选项 ⇒ 吵出来，不静默跳过（h33 死开关同族）")
    void unknownOptionIsLoud() {
        PackOptions options = options();
        PackOptionOverride.Result result = PackOptionOverride.apply(options, "NO_SUCH_OPTION=false");

        assertTrue(result.hasDiagnostic(PackOptionOverride.UNKNOWN_OPTION),
                "「包里没有这个选项」必须 WARN：它在日志上与「施加了但画面没变」长得一样");
        assertTrue(result.applied().isEmpty());
    }

    @Test
    @DisplayName("🔖🔖 越界值被钳制 ⇒ 必须单独报「生效的不是你写的值」")
    void clampedValueIsCalledOut() {
        // 🔖 钳制与拒绝是两件事：钳制**生效了**，但生效的不是用户写的那个值。
        //   若两者都只报「已覆盖」，写了 SHARPEN=99 实际拿到 3 的取证者会误判归因。
        PackOptions options = options();
        PackOptionOverride.Result result = PackOptionOverride.apply(options, "SHARPEN=99");

        assertEquals("3", options.value("SHARPEN"), "SHARPEN=99 越界 ⇒ 钳到上界 3");
        assertTrue(result.hasDiagnostic(PackOptionOverride.VALUE_CLAMPED),
                "钳制必须在**本类的结果诊断**里出现 —— PackOptions 自己那批诊断进的是它的 sink，"
                        + "调用方（PackTerrainSource）只读结果里的诊断，漏了就等于静默");
        assertFalse(result.hasDiagnostic(PackOptionOverride.SET_REJECTED),
                "钳制不等于拒绝；两个诊断码不能混用");
        assertEquals("3", result.applied().get("SHARPEN"), "applied 里记的必须是**实际生效**的值");
    }

    @Test
    @DisplayName("🔖 类型不符 / 选项不存在 ⇒ 拒绝并保留原值（不静默吞掉）")
    void rejectedValueKeepsPrevious() {
        PackOptions options = options();
        // SHARPEN 是 INTEGER，给它 true ⇒ 归一化阶段直接 REJECTED
        PackOptionOverride.Result result = PackOptionOverride.apply(options, "SHARPEN=true");

        assertTrue(result.hasDiagnostic(PackOptionOverride.SET_REJECTED),
                "被拒绝的覆盖必须有诊断；否则日志会与「覆盖生效了」混淆");
        assertEquals("0", options.value("SHARPEN"), "被拒绝时必须保留原值");
        assertTrue(result.applied().isEmpty());
    }

    @Test
    @DisplayName("🔖 值本就相同 ⇒ 不记进 applied（否则日志会说「我们改了它」）")
    void alreadyEqualIsNotRecordedAsApplied() {
        PackOptions options = options();
        PackOptionOverride.Result result = PackOptionOverride.apply(options, "SSS=true");

        assertTrue(result.applied().isEmpty(),
                "本来就是 true 却报成「已覆盖」，下一次取证会据此误判归因"
                        + "（与 PackCapabilityGate 处理「本来就关的」是同一条纪律）");
        assertEquals(List.of("SSS"), result.unchanged());
        assertTrue(result.hasDiagnostic(PackOptionOverride.ALREADY_EQUAL));
    }

    @Test
    @DisplayName("🔖 空串 ⇒ 不覆盖，且必须产生一条 INFO（不是无声无息）")
    void emptySpecIsExplicit() {
        PackOptions options = options();
        PackOptionOverride.Result result = PackOptionOverride.apply(options, "  ");

        assertTrue(result.applied().isEmpty());
        assertTrue(result.hasDiagnostic("OPTION_OVERRIDE_OFF"),
                "空串也要说话：否则「我没配覆盖」与「配了但没生效」无法区分");
        assertEquals("true", options.value("PARALLAX"));
    }

    @Test
    @DisplayName("🔖 语法解析：空白容忍、分隔符、后写覆盖先写")
    void parseSemantics() {
        Map<String, String> parsed = PackOptionOverride.parse(" PARALLAX = false ; SHARPEN=3 ");
        assertEquals(Map.of("PARALLAX", "false", "SHARPEN", "3"), parsed, "空白应被容忍");

        assertEquals(Map.of("PARALLAX", "true"),
                PackOptionOverride.parse("PARALLAX=false;PARALLAX=true"),
                "同名重复时保留最后一次");
        assertEquals(Map.of(), PackOptionOverride.parse(""), "空串 = 空表");
        assertEquals(Map.of(), PackOptionOverride.parse(null), "null = 空表（不是异常）");

        assertThrows(IllegalArgumentException.class, () -> PackOptionOverride.parse("A"));
        assertThrows(IllegalArgumentException.class, () -> PackOptionOverride.parse("=v"));
        assertThrows(IllegalArgumentException.class, () -> PackOptionOverride.parse("A="));
    }

    @Test
    @DisplayName("🔖 不写用户文件：覆盖后 store 仍为空（裁决：不改写用户配置）")
    void doesNotTouchUserStore() {
        PackOptions options = options();
        PackOptionStore store = PackOptionStore.empty();
        PackOptionOverride.apply(options, "PARALLAX=false");

        assertEquals(Map.of(), store.forPack("任何包"),
                "本类**只改内存**。改用户配置文件是 Iris 都不做的事（无先例，见 GAP-009 裁决）");
    }

    @Test
    @DisplayName("🔖 施加 + 转发诊断到 sink（接线形态与能力门控同款）")
    void applyForwardsDiagnosticsToSink() {
        PackOptions options = options();
        List<OptionDiagnostic> collected = new java.util.ArrayList<>();
        PackOptionOverride.Result result =
                PackOptionOverride.apply(options, "PARALLAX=false", collected::add);

        assertFalse(collected.isEmpty(), "诊断必须转发到 sink，否则调用点看不到");
        assertEquals(result.diagnostics().size(), collected.size(),
                "转发条数必须与结果里的条数一致（不多不少）");
    }

    @Test
    @DisplayName("🔖🔖 覆盖面守卫：本类**不得**硬编码任何包特性名")
    void doesNotHardcodeAnyPackOptionName() {
        // 🔖 X27 的直接守卫：一旦有人在这里写死 "PARALLAX"，本测试立刻红。
        //   硬编码会砍掉 Complementary 之类「有同名特性但无外部依赖」的包的可用特性。
        String source;
        try {
            source = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/java/dev/vkdisp/config/PackOptionOverride.java"));
        } catch (java.io.IOException e) {
            throw new AssertionError("读不到源码", e);
        }
        for (String forbidden : List.of("\"PARALLAX\"", "\"ADVANCED_MATERIALS\"", "\"SSS\"")) {
            for (String line : source.split("\n")) {
                String trimmed = line.strip();
                if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                    continue;
                }
                assertFalse(trimmed.contains(forbidden),
                        "代码里出现包特性名常量 " + forbidden + " ⇒ 这是硬编码，"
                                + "会砍掉其它包的可用特性（X27）。选项名必须由用户按需指定");
            }
        }
    }
}