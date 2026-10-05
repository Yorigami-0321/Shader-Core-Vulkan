package dev.vkdisp.pipeline.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PackOptionEvidence} 的合并与自报 —— QD-08 取证件的守卫。
 *
 * <p>🔖 <b>它防的是静默空转</b>：取证者以为自己改了包选项、实际没改，
 * 于是 A/B 两臂静默变成同一件事，而日志里每一行都正常。
 * 本项目 2026-10-05 就因为落盘 store 里残留着前一轮的 {@code PARALLAX=false}
 * 而把「默认档」带偏了一整轮。
 */
class PackOptionEvidenceTest {

    @Test
    @DisplayName("🔖🔖 两个来源**都要**自报，哪怕一个为空（没打 ≠ 打了但空）")
    void bothSourcesAlwaysAppear() {
        PackOptionEvidence empty = PackOptionEvidence.of(Map.of(), "");
        assertTrue(empty.format().contains("store=" + PackOptionEvidence.NONE),
                "store 为空时必须显式打 <none> —— 否则读日志的人分不清「没有覆盖」与「没打这一项」");
        assertTrue(empty.format().contains("config=" + PackOptionEvidence.NONE),
                "config 为空时同样必须显式打 <none>");
        assertTrue(empty.format().contains("effective=" + PackOptionEvidence.NONE));
        assertFalse(empty.anyActive());
    }

    @Test
    @DisplayName("🔖🔖 store 里的残留覆盖必须出现在取证件里（这正是本项目踩到的那次）")
    void storeResidueIsVisible() {
        // 🔴 2026-10-05 实测事故：前一轮 A/B 留下的 PARALLAX=false 留在落盘 store 里，
        //   本轮以为在测「默认档」，实际带着视差关闭覆盖 ⇒ 结论方向被带偏一整轮。
        Map<String, String> store = new LinkedHashMap<>();
        store.put("BSL_v10.1.8.PARALLAX", "false");
        PackOptionEvidence evidence = PackOptionEvidence.of(store, "");
        assertTrue(evidence.anyActive(), "store 里有覆盖 ⇒ 必须自报「有覆盖生效」");
        assertTrue(evidence.effective().containsKey("BSL_v10.1.8.PARALLAX"));
        assertTrue(evidence.format().contains("PARALLAX=false"),
                "取证件必须打出具体的键值 —— 只说「有覆盖」等于没说");
    }

    @Test
    @DisplayName("🔖 配置串覆盖 store（配置是取证旋钮，取证者最后动的那一层）")
    void configSpecOverridesStore() {
        Map<String, String> store = new LinkedHashMap<>();
        store.put("X.PARALLAX", "false");
        PackOptionEvidence evidence = PackOptionEvidence.of(store, "PARALLAX=true");
        assertEquals("true", evidence.effective().get("PARALLAX"),
                "配置串是取证旋钮，必须压过落盘 store —— 否则改配置却看到旧值生效");
        assertEquals("false", evidence.effective().get("X.PARALLAX"),
                "store 里未被配置串点名的键必须**保留**（不是被整体替换）");
    }

    @Test
    @DisplayName("🔖🔖 语法不对的覆盖片段必须**看得见**（静默丢掉 = 静默空转）")
    void malformedFragmentIsSelfReported() {
        PackOptionEvidence evidence = PackOptionEvidence.of(Map.of(), "PARALLAX");
        assertTrue(evidence.hasMalformedEntry(),
                "「写了但格式不对」若被静默丢掉，取证者会以为覆盖没生效 —— "
                        + "而真相是它写错了格式，这条 WARN 是唯一线索");
        assertTrue(evidence.format().contains("malformed"),
                "自报里要出现 malformed 字样，让读的人一眼看出「这条没生效是因为格式错」");
    }

    @Test
    @DisplayName("🔖 有效表按键排序（同一份配置两次取证必须打出**逐位相同**的字符串）")
    void effectiveTableIsDeterministic() {
        Map<String, String> store = new LinkedHashMap<>();
        store.put("Z.OPT", "1");
        store.put("A.OPT", "2");
        PackOptionEvidence first = PackOptionEvidence.of(store, "M=3");
        PackOptionEvidence second = PackOptionEvidence.of(store, "M=3");
        assertEquals(first.format(), second.format(),
                "顺序若取决于 HashMap 迭代序，同一份配置两次取证会打出不同字符串，"
                        + "而读的人会以为配置变了");
        // 断言排序真的生效（不是靠巧合）
        List<String> keys = List.copyOf(first.effective().keySet());
        assertEquals(keys.stream().sorted().toList(), keys, "有效表必须按键升序");
    }

    @Test
    @DisplayName("🔖 null 键值对被归一掉（不该在证据行里留下 \"null=...\" 这种读不出来的文本）")
    void nullPairsAreNormalized() {
        Map<String, String> store = new LinkedHashMap<>();
        store.put("GOOD", "1");
        store.put(null, "x");
        store.put("BAD", null);
        PackOptionEvidence evidence = PackOptionEvidence.of(store, null);
        assertEquals(1, evidence.effective().size(), "只应保留 GOOD 一项");
        assertFalse(evidence.format().contains("null"));
    }

    @Test
    @DisplayName("🔖 构造器不接受空白说明位之外的东西（说明是构造器自带的，不许外部传空）")
    void recordNormalizesNullSources() {
        PackOptionEvidence evidence = new PackOptionEvidence(null, null, null);
        assertEquals("", evidence.storeSpec());
        assertEquals("", evidence.configSpec());
        assertFalse(evidence.anyActive());
        assertTrue(evidence.format().contains(PackOptionEvidence.NONE));
    }
}