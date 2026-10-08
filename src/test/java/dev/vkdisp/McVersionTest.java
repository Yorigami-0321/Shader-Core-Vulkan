package dev.vkdisp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link McVersion} 的编码口径与两处接线（GLSL 侧 {@code DefineProcessor} /
 * 属性侧 {@code ConditionalPreprocessor}）。
 *
 * <p>🔖 期望值全部<b>从包自己的门限反推</b>后独立写死，不跟随实现 ——
 * 这样「编码算错一位」会当场变红，而不是跟着实现一起错。
 */
class McVersionTest {

    /** 包内实际出现过的门限 → 人类版本号（逐条取自 BSL v10.1.8 的 #if 行）。 */
    private static final String[][] THRESHOLDS = {
            {"1.7.10", "10710"}, {"1.8.0", "10800"}, {"1.9.0", "10900"}, {"1.13.0", "11300"},
            {"1.15.0", "11500"}, {"1.16.5", "11605"}, {"1.18.0", "11800"}, {"1.21.6", "12106"},
            {"1.21.9", "12109"}, {"1.21.11", "12111"}, {"26.3", "260300"},
    };

    @Test
    @DisplayName("编码 = major*10000 + minor*100 + patch（口径来自包自己的门限）")
    void encodingMatchesPackThresholds() {
        for (String[] row : THRESHOLDS) {
            OptionalInt encoded = McVersion.encode(row[0]);
            assertTrue(encoded.isPresent(), "编码不出来：" + row[0]);
            assertEquals(Integer.parseInt(row[1]), encoded.getAsInt(), row[0] + " 应编成 " + row[1]);
        }
    }

    @Test
    @DisplayName("拿不到就**不定义**，绝不喂一个猜的数（X9）")
    void unparseableVersionsYieldEmptyNotZero() {
        for (String bad : java.util.Arrays.asList("1.20.2-rc2", "snapshot", "", "1", null)) {
            assertTrue(McVersion.encode(bad).isEmpty(), "不该编码出来：" + bad);
        }
    }

    @Test
    @DisplayName("🔴 GLSL 侧接线：DefineProcessor 也要按 MC_VERSION 选分支，且宏不许漏进正文")
    void glslPreprocessorTakesModernBranch() {
        String source = "#if MC_VERSION >= 11800\nfloat modern = 1.0;\n#else\nfloat legacy = 1.0;\n#endif\n";
        McVersion.overrideForTest(260300);
        try {
            String out = dev.vkdisp.glsl.preprocess.DefineProcessor.process(
                    source, dev.vkdisp.glsl.SourceLineMap.builder(null).build()).text();
            assertTrue(out.contains("modern"), "GLSL 侧也必须走新支，实际输出：" + out);
            assertFalse(out.contains("legacy"), "老支必须被整段删掉，实际：" + out);
            assertFalse(out.contains("MC_VERSION"), "宏名不该残留在正文里：" + out);
        } finally {
            McVersion.overrideForTest(null);
        }
    }

    @Test
    @DisplayName("numericOf 只认 MC_VERSION；别的标识符不许被当成数值宏")
    void numericLookupIsScopedToTheOneMacro() {
        McVersion.overrideForTest(260300);
        try {
            assertEquals(260300, McVersion.numericOf("MC_VERSION").getAsInt());
            assertTrue(McVersion.numericOf("SHADOW").isEmpty(), "别的名不许被当成数值宏");
            assertTrue(McVersion.numericOf("").isEmpty());
        } finally {
            McVersion.overrideForTest(null);
        }
    }
}
