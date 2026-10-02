package dev.vkdisp.screen;

import java.nio.file.Path;
import java.util.List;

import dev.vkdisp.pack.ShaderPackScanner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PackPickerCandidates} 契约：候选顺序 / 保留值 / 当前值缺失不静默 / 值归一。
 *
 * <p>覆盖三态语义（自动 / passthrough / 包名精确匹配）与「当前配置值不在库存」的显式处理
 * —— 后者是 X9（不猜）的落地：绝不把一个不存在的包静默显示成别的包。
 */
class PackPickerCandidatesTest {

    /** 造一个扫描结果（不碰文件系统，纯数据）。 */
    private static ShaderPackScanner.ScanResult scan(String... names) {
        List<ShaderPackScanner.DiscoveredPack> packs = new java.util.ArrayList<>();
        for (String name : names) {
            packs.add(new ShaderPackScanner.DiscoveredPack(
                    name, ShaderPackScanner.Kind.DIRECTORY,
                    Path.of("dummy", name), "shaders"));
        }
        return new ShaderPackScanner.ScanResult(packs, List.of());
    }

    @Test
    void firstTwoChoicesAreAutoAndNone() {
        var result = PackPickerCandidates.build(scan("BSL", "Complementary"), "", true);
        assertEquals(PackPickerCandidates.AUTO, result.choices().get(0).value());
        assertEquals(PackPickerCandidates.NONE, result.choices().get(1).value());
        assertEquals(2 + 2, result.choices().size(), "两个保留值 + 两个包");
    }

    // ---------------------------------------------------------------- label 显示（2026-10-02 实测缺陷回归锁）

    @Test
    void labelOfAutoIsNeverBlank() {
        //缺陷原文：「none 这个一开始是不显示任何字的」——根因是 AUTO 的 value 是空串，
        // 当文本渲染就是空白。label 必须是可读文案。
        var result = PackPickerCandidates.build(scan("BSL"), PackPickerCandidates.AUTO, true);
        String label = PackPickerCandidates.labelOf(result, PackPickerCandidates.AUTO);
        assertNotNull(label);
        assertFalse(label.isBlank(), "「自动」态的显示文本不许是空白");
        assertEquals(PackPickerCandidates.defaultAutoLabel(), label);
    }

    @Test
    void labelOfNoneIsNeverBlank() {
        var result = PackPickerCandidates.build(scan("BSL"), PackPickerCandidates.NONE, true);
        String label = PackPickerCandidates.labelOf(result, PackPickerCandidates.NONE);
        assertNotNull(label);
        assertFalse(label.isBlank(), "「passthrough」态的显示文本不许是空白");
        assertEquals(PackPickerCandidates.defaultNoneLabel(), label);
    }

    @Test
    void labelOfEveryChoiceIsNonBlank() {
        // 逐条锁：候选列表里任何一项被渲染成空白都是缺陷（不只前两条保留值）
        var result = PackPickerCandidates.build(scan("BSL", "Complementary"), "BSL", true);
        for (var choice : result.choices()) {
            String label = PackPickerCandidates.labelOf(result, choice.value());
            assertNotNull(label, "候选 '" + choice.value() + "' 的 label 为 null");
            assertFalse(label.isBlank(), "候选 '" + choice.value() + "' 的 label 是空白");
        }
    }

    @Test
    void labelOfUnknownValueFallsBackToValueItself() {
        // X9：值不在候选里时显示原值（让用户看见自己配的是什么），不是空白也不是猜测的包名
        var result = PackPickerCandidates.build(scan("BSL"), "GhostPack", true);
        assertFalse(result.currentInList());
        assertEquals("GhostPack", PackPickerCandidates.labelOf(result, "GhostPack"));
    }

    @Test
    void labelOfNullResultAndNullValueDegradeGracefully() {
        assertEquals(PackPickerCandidates.defaultAutoLabel(),
                PackPickerCandidates.labelOf(null, null));
        assertEquals(PackPickerCandidates.defaultAutoLabel(),
                PackPickerCandidates.labelOf(null, PackPickerCandidates.AUTO));
        assertEquals("BSL", PackPickerCandidates.labelOf(null, "BSL"));
    }

    @Test
    void packsAreSortedByName() {
        var result = PackPickerCandidates.build(scan("Sildur", "BSL", "Complementary"), "", true);
        List<String> values = result.choices().subList(2, 5).stream()
                .map(PackPickerCandidates.Choice::value).toList();
        assertEquals(List.of("BSL", "Complementary", "Sildur"), values,
                "包名按字典序（扫描顺序不定，UI 需要稳定顺序）");
    }

    @Test
    void currentValueInListIsDetected() {
        assertTrue(PackPickerCandidates.build(scan("BSL"), "BSL", true).currentInList());
        assertTrue(PackPickerCandidates.build(scan("BSL"), "", true).currentInList(),
                "空串 = 自动，恒在列表内");
        assertTrue(PackPickerCandidates.build(scan("BSL"), "none", true).currentInList(),
                "none = passthrough 保留值，恒在列表内");
    }

    @Test
    void currentValueNotInInventoryIsFlaggedNotSilentlySubstituted() {
        var result = PackPickerCandidates.build(scan("BSL"), "VanillaPlus", true);
        assertFalse(result.currentInList(), "库存里没有该包 → 必须显式标记");
        assertEquals("VanillaPlus", result.currentValue(), "原值原样保留，不改成别的包");
        assertEquals(PackPickerCandidates.missingLabel(), PackPickerCandidates.initialSelection(result),
                "下拉初始值应是占位项，而不是悄悄选第一个包");
    }

    @Test
    void initialSelectionReturnsRealValueWhenPresent() {
        var result = PackPickerCandidates.build(scan("BSL", "Sildur"), "Sildur", true);
        assertEquals("Sildur", PackPickerCandidates.initialSelection(result));
    }

    @Test
    void resolveMapsBlankAndMissingLabelToAuto() {
        assertEquals(PackPickerCandidates.AUTO, PackPickerCandidates.resolve(null));
        assertEquals(PackPickerCandidates.AUTO, PackPickerCandidates.resolve(""));
        assertEquals(PackPickerCandidates.AUTO,
                PackPickerCandidates.resolve(PackPickerCandidates.missingLabel()),
                "占位项 = 不改配置 = 归一回自动");
        assertEquals("BSL", PackPickerCandidates.resolve("BSL"), "真实包名原样透传");
        assertEquals("none", PackPickerCandidates.resolve("none"));
    }

    @Test
    void duplicatePackNamesAreDeduped() {
        // 两个条目同名 → 下拉值会撞车，只留第一个
        var result = PackPickerCandidates.build(scan("BSL", "BSL"), "", true);
        assertEquals(3, result.choices().size(), "自动 + none + 唯一BSL");
        assertEquals(1, result.packCount());
    }

    @Test
    void nullScanResultDegradesToEmptyWithoutThrowing() {
        var result = PackPickerCandidates.build(null, "", false);
        assertNotNull(result);
        assertEquals(2, result.choices().size(), "只有两个保留值");
        assertEquals(0, result.packCount());
        assertFalse(result.inventoryExists());
    }

    @Test
    void statusLineReportsInventoryAndProblemState() {
        assertTrue(PackPickerCandidates.statusLine(
                        PackPickerCandidates.build(null, "", false)).contains("不存在"));
        assertTrue(PackPickerCandidates.statusLine(
                        PackPickerCandidates.build(scan(), "", true)).contains("没有合法包"));
        var withProblem = new ShaderPackScanner.ScanResult(
                new java.util.ArrayList<>(
                        List.of(new ShaderPackScanner.DiscoveredPack("BSL",
                                ShaderPackScanner.Kind.DIRECTORY, Path.of("dummy", "BSL"), "shaders"))),
                List.of(new ShaderPackScanner.PackProblem(Path.of("x"),
                        ShaderPackScanner.ProblemKind.BROKEN_ZIP, "zip 损坏")));
        var line = PackPickerCandidates.statusLine(
                PackPickerCandidates.build(withProblem, "BSL", true));
        assertTrue(line.contains("包数=1"));
        assertTrue(line.contains("问题=1"), "问题条目数必须显示，不是静默");
    }
}
