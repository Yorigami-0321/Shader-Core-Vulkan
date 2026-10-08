package dev.vkdisp.pack.properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ConditionalPreprocessor} 的 {@code MC_VERSION} 数值支路（放在同包内：
 * 本类是 package-private，跨包测试拿不到 —— 这是有意的，不为了测试放宽可见性）。
 */
class ConditionalPreprocessorMcVersionTest {

    /** BSL shaders.properties:167 那一支的最小复现（新=符号名 / 老=数字 ID）。 */
    private static final List<String> BIOME_BRANCH = List.of(
            "#if MC_VERSION >= 11800", "new=biomeSymbolic", "#else", "old=biomeNumeric", "#endif");

    @Test
    @DisplayName("🔴 MC_VERSION 可用时 `>= 11800` 走新支（本轮之前的真实行为是走老支）")
    void modernBranchSelectedWhenVersionKnown() {
        dev.vkdisp.McVersion.overrideForTest(260300);
        try {
            assertEquals(List.of("new=biomeSymbolic"),
                    ConditionalPreprocessor.preprocess(BIOME_BRANCH, Set.of()),
                    "26.3 远高于 1.18 ⇒ 必须取符号名那一支");
        } finally {
            dev.vkdisp.McVersion.overrideForTest(null);
        }
    }

    @Test
    @DisplayName("取不到版本时**保持旧行为**（按未定义=0 判假），不猜一个数")
    void legacyBranchRetainedWhenVersionUnknown() {
        dev.vkdisp.McVersion.overrideForTest(-1);
        try {
            assertEquals(List.of("old=biomeNumeric"),
                    ConditionalPreprocessor.preprocess(BIOME_BRANCH, Set.of()),
                    "拿不到版本就照旧 —— 这条守卫的是「不许偷偷喂一个猜的数」");
        } finally {
            dev.vkdisp.McVersion.overrideForTest(null);
        }
    }

    @Test
    @DisplayName("普通选项宏仍是「定义/未定义」两态，没被数值支路带跑")
    void booleanMacrosUnaffected() {
        dev.vkdisp.McVersion.overrideForTest(260300);
        try {
            List<String> lines = List.of("#if SHADOW", "on", "#endif");
            assertEquals(List.of("on"), ConditionalPreprocessor.preprocess(lines, Set.of("SHADOW")));
            assertTrue(ConditionalPreprocessor.preprocess(lines, Set.of()).isEmpty(),
                    "未定义的选项宏仍必须是假（不是「有数值表就都当真」）");
        } finally {
            dev.vkdisp.McVersion.overrideForTest(null);
        }
    }
}
