package dev.vkdisp.config;
/**
 * 【参考调研】F 线单测 / profile 预设应用（04-SPEC §3.1 的 profile.NAME 条目语法）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §3.1（ShaderProperties 负责选项、开关、profiles、sliders）；
 *    ② docs/18-PARALLEL.md §4 F 线（"与 Option 模型对接通"）与 §7.3（边界用例）；③ docs/07-CONSTRAINTS.md T11；
 *    ④ F2 冻结契约 dev.vkdisp.pack.ShaderPack#profiles（profile 名 → 原始条目 token 列表，顺序保留）及其
 *    【参考调研】第 1 条转述的 OF 条目语法事实（OPTION:值 / OPTION=值 / OPTION 开 / !OPTION 关 /
 *    profile.其他名 继承 / !program.名 禁程序）。
 *    许可证：本仓库自有文档与自有类型（MIT）→ 可直接消费；OF 侧只取不受版权保护的语法事实（经 F2 转述，不读源码）；
 *    JUnit 5 = EPL-2.0（仅测试期依赖）。第三方源码零接触（07-CONSTRAINTS L12）。
 *    → 能否并入本项目（MIT）：可以（本文件为独立编写的测试）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：JUnit 5 标准断言。
 * 2. 备选：只测正常 profile —— 否决（继承环 / 未定义 profile / 程序开关条目这些降级路径才是 T11 的证据点）。
 * 3. 我们的差异点：明确区分"选项值条目"与"程序开关条目"：后者不是选项值，必须显式 WARN 后跳过，
 *    而不是假装应用成功（静默失败第二定律，18-PARALLEL §7.3）。
 * 4. 许可证核对：本项目 MIT；JUnit EPL-2.0 仅测试期依赖；零第三方代码复制。
 * 5. 性能基线：测试代码不进运行时，无性能影响（18-PARALLEL §7.7）。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** profile 条目语法、继承、环检测与非选项条目的显式跳过。 */
class PackOptionsProfileTest {

    private static Map<String, List<String>> profiles(String name, String... entries) {
        LinkedHashMap<String, List<String>> map = new LinkedHashMap<>();
        map.put(name, List.of(entries));
        return map;
    }

    private static PackOptions newOptions(List<String> sinkLog) {
        return PackOptions.of(List.of(
                OptionFixtures.integer("SHADOW_QUALITY", "0", "0", "1", "2"),
                OptionFixtures.bool("SHADOWS", "false"),
                OptionFixtures.bool("OLD_LIGHTING", "true"),
                OptionFixtures.text("WATER_STYLE", "fast", "fast", "fancy")),
                OptionDiagnosticSink.collectingLines(sinkLog));
    }

    @Test
    void colonAndEqualsEntriesAreApplied() {
        PackOptions options = newOptions(new ArrayList<>());

        List<PackOptions.SetOutcome> outcomes = options.applyProfile("HIGH",
                profiles("HIGH", "SHADOW_QUALITY:2", "WATER_STYLE=fancy"));

        assertEquals(2, outcomes.size());
        assertEquals("2", options.value("SHADOW_QUALITY"));
        assertEquals("fancy", options.value("WATER_STYLE"));
    }

    @Test
    void bareEntryMeansTrueAndNegatedEntryMeansFalse() {
        PackOptions options = newOptions(new ArrayList<>());

        options.applyProfile("CUSTOM", profiles("CUSTOM", "SHADOWS", "!OLD_LIGHTING"));

        assertEquals("true", options.value("SHADOWS"));
        assertEquals("false", options.value("OLD_LIGHTING"));
    }

    @Test
    void negationCombinedWithExplicitValueWarnsAndSetsFalse() {
        PackOptions options = newOptions(new ArrayList<>());

        List<PackOptions.SetOutcome> outcomes = options.applyProfile("ODD", profiles("ODD", "!SHADOWS:true"));

        assertEquals(1, outcomes.size());
        assertEquals("false", outcomes.get(0).appliedValue());
        assertTrue(options.hasDiagnostic("PROFILE_NEGATION_WITH_VALUE"), options.diagnostics().toString());
    }

    @Test
    void inheritanceAppliesParentFirstThenChildOverride() {
        PackOptions options = newOptions(new ArrayList<>());
        LinkedHashMap<String, List<String>> map = new LinkedHashMap<>();
        map.put("BASE", List.of("SHADOW_QUALITY:1"));
        map.put("HIGH", List.of("profile.BASE", "SHADOW_QUALITY:2"));

        List<PackOptions.SetOutcome> outcomes = options.applyProfile("HIGH", map);

        assertEquals(2, outcomes.size());
        assertEquals("1", outcomes.get(0).appliedValue(), "先应用被继承的 profile");
        assertEquals("2", outcomes.get(1).appliedValue(), "子 profile 覆盖父 profile");
        assertEquals("2", options.value("SHADOW_QUALITY"));
    }

    @Test
    void unknownProfileIsErrorWithEmptyOutcomes() {
        PackOptions options = newOptions(new ArrayList<>());

        List<PackOptions.SetOutcome> outcomes = options.applyProfile("NOPE", profiles("HIGH", "SHADOWS"));

        assertTrue(outcomes.isEmpty());
        OptionDiagnostic error = options.diagnostics().get(0);
        assertEquals("UNKNOWN_PROFILE", error.code());
        assertTrue(error.isError());
    }

    @Test
    void inheritanceCycleIsDetectedAndStopped() {
        PackOptions options = newOptions(new ArrayList<>());
        LinkedHashMap<String, List<String>> map = new LinkedHashMap<>();
        map.put("A", List.of("profile.B", "SHADOWS"));
        map.put("B", List.of("profile.A"));

        options.applyProfile("A", map);

        assertTrue(options.hasDiagnostic("PROFILE_CYCLE"), options.diagnostics().toString());
        assertEquals("true", options.value("SHADOWS"), "环之前的条目仍然生效");
    }

    @Test
    void selfInheritanceIsDetected() {
        PackOptions options = newOptions(new ArrayList<>());

        options.applyProfile("A", profiles("A", "profile.A"));

        assertTrue(options.hasDiagnostic("PROFILE_CYCLE"), options.diagnostics().toString());
    }

    @Test
    void programToggleEntriesAreWarnedAndIgnored() {
        PackOptions options = newOptions(new ArrayList<>());

        List<PackOptions.SetOutcome> outcomes = options.applyProfile("HIGH",
                profiles("HIGH", "program.gbuffers_terrain=0", "!program.composite1", "SHADOWS"));

        assertEquals(1, outcomes.size(), "只有选项值条目产生赋值结果");
        assertEquals("SHADOWS", outcomes.get(0).name());
        assertEquals(2, options.diagnostics().stream()
                .filter(d -> d.code().equals("PROFILE_PROGRAM_TOGGLE_IGNORED")).count());
    }

    @Test
    void emptyAndDegenerateEntriesAreWarnedAndSkipped() {
        PackOptions options = newOptions(new ArrayList<>());

        List<PackOptions.SetOutcome> outcomes = options.applyProfile("EMPTYISH",
                profiles("EMPTYISH", "", "   ", "!", ":"));

        assertTrue(outcomes.isEmpty());
        assertEquals(4, options.diagnostics().stream()
                .filter(d -> d.code().equals("EMPTY_PROFILE_ENTRY")).count(),
                options.diagnostics().toString());
    }

    @Test
    void unknownOptionEntryIsWarnedAndRejected() {
        PackOptions options = newOptions(new ArrayList<>());

        List<PackOptions.SetOutcome> outcomes = options.applyProfile("HIGH", profiles("HIGH", "NOPE:1"));

        assertEquals(1, outcomes.size());
        assertEquals(PackOptions.SetStatus.REJECTED, outcomes.get(0).status());
        assertTrue(options.hasDiagnostic("UNKNOWN_OPTION"), options.diagnostics().toString());
    }

    @Test
    void emptyProfileIsWarned() {
        PackOptions options = newOptions(new ArrayList<>());

        options.applyProfile("EMPTY", profiles("EMPTY"));

        assertTrue(options.hasDiagnostic("EMPTY_PROFILE"), options.diagnostics().toString());
    }

    @Test
    void nullEntryListIsTreatedAsUndefinedProfile() {
        PackOptions options = newOptions(new ArrayList<>());
        Map<String, List<String>> map = new HashMap<>();
        map.put("BROKEN", null);

        options.applyProfile("BROKEN", map);

        assertTrue(options.hasDiagnostic("UNKNOWN_PROFILE"), options.diagnostics().toString());
    }

    @Test
    void nullEntryInsideProfileListIsWarnedAndSkipped() {
        PackOptions options = newOptions(new ArrayList<>());
        List<String> entries = new ArrayList<>();
        entries.add("SHADOWS");
        entries.add(null);
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("HIGH", entries);

        List<PackOptions.SetOutcome> outcomes = options.applyProfile("HIGH", map);

        assertEquals(1, outcomes.size());
        assertTrue(options.hasDiagnostic("NULL_PROFILE_ENTRY"), options.diagnostics().toString());
    }

    @Test
    void applyFromShaderPackConsumesF2ProfilesMap() {
        var pack = OptionFixtures.pack(
                profiles("HIGH", "SHADOW_QUALITY:2", "SHADOWS"),
                OptionFixtures.integer("SHADOW_QUALITY", "0", "0", "1", "2"),
                OptionFixtures.bool("SHADOWS", "false"));
        PackOptions options = PackOptions.of(pack);

        List<PackOptions.SetOutcome> outcomes = options.applyProfile(pack, "HIGH");

        assertEquals(2, outcomes.size());
        assertEquals("2", options.value("SHADOW_QUALITY"));
        assertEquals("true", options.value("SHADOWS"));
    }

    @Test
    void profileNameIsTrimmedBeforeLookup() {
        PackOptions options = newOptions(new ArrayList<>());

        List<PackOptions.SetOutcome> outcomes = options.applyProfile("  HIGH  ", profiles("HIGH", "SHADOWS"));

        assertEquals(1, outcomes.size());
        assertEquals("true", options.value("SHADOWS"));
    }
}
