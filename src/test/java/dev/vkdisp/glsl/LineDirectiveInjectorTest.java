package dev.vkdisp.glsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A3 守卫：`#line` 发射 + 驱动错误归因。
 *
 * <p>验收判据（`19` §2.6-A3）：一条故意写坏的转译产物 → 归因结果里必须出现**包内**文件与行号。
 */
class LineDirectiveInjectorTest {

    private static final String TRANSLATED = """
            #version 430
            uniform sampler2D colortex0;
            int broken line here;
            void main() { gl_FragColor = vec4(0); }
            """;

    private static SourceLineMap lineMap(String file, int... originLines) {
        SourceLineMap.Builder builder = SourceLineMap.builder(file);
        for (int line : originLines) {
            if (line == 0) {
                builder.addSynthetic();
            } else {
                builder.add(file, line);
            }
        }
        return builder.build();
    }

    @Test
    @DisplayName("🔖 #line 注入：首条 #line 必须在 #version 之后（GLSL 硬性要求）")
    void firstLineDirectiveAfterVersion() {
        SourceLineMap map = lineMap("composite.fsh", 1, 2, 3, 4);
        String injected = LineDirectiveInjector.inject(TRANSLATED, map);
        String[] lines = injected.split("\n");
        int versionIdx = -1;
        int firstLineIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("#version")) {
                versionIdx = i;
            }
            if (lines[i].startsWith("#line") && firstLineIdx < 0) {
                firstLineIdx = i;
            }
        }
        assertTrue(versionIdx >= 0, "输出必须保留 #version");
        assertTrue(firstLineIdx > versionIdx,
                "首条 #line 必须在 #version 之后，实测 version@" + versionIdx + " #line@" + firstLineIdx);
    }

    @Test
    @DisplayName("🔖 #line 注入：起源连续时不重复发射（只在断裂处发）")
    void noRedundantDirectivesForSequentialLines() {
        SourceLineMap map = lineMap("composite.fsh", 1, 2, 3, 4);
        String injected = LineDirectiveInjector.inject(TRANSLATED, map);
        long count = injected.lines().filter(l -> l.startsWith("#line")).count();
        assertEquals(1, count, "连续起源只需首条 #line（后续行自然递增），实测 " + count + " 条");
    }

    @Test
    @DisplayName("🔖 #line 注入：起源断裂（#include 展开 / 行删除）时发射新 #line")
    void emitsDirectiveAtDiscontinuity() {
        // 模拟：第 1-2 行来自 composite.fsh:10-11，第 3 行来自 common.glsl:5，第 4 行回到 composite.fsh:13
        SourceLineMap.Builder builder = SourceLineMap.builder("composite.fsh");
        builder.add("composite.fsh", 10);
        builder.add("composite.fsh", 11);
        builder.add("common.glsl", 5);
        builder.add("composite.fsh", 13);
        SourceLineMap map = builder.build();

        String injected = LineDirectiveInjector.inject(TRANSLATED, map);
        long count = injected.lines().filter(l -> l.startsWith("#line")).count();
        assertTrue(count >= 3, "至少 3 处断裂（首条 + 文件切换 + 行号跳跃），实测 " + count);
        assertTrue(injected.contains("#line 5 \"common.glsl\""),
                "文件切换处必须发射含文件名的 #line，实测：\n" + injected);
    }

    @Test
    @DisplayName("🔖 #line 注入：合成行（origin=0）不发射 #line")
    void syntheticLinesGetNoDirective() {
        SourceLineMap map = lineMap("composite.fsh", 1, 0, 3, 4);
        String injected = LineDirectiveInjector.inject(TRANSLATED, map);
        assertFalse(injected.contains("#line 0"), "合成行没有包内起源，不许发射 #line 0");
    }

    @Test
    @DisplayName("🔖 #line 注入：lineMap 为 unmapped 时原样返回（不注入）")
    void unmappedLineMapReturnsOriginal() {
        String result = LineDirectiveInjector.inject(TRANSLATED, SourceLineMap.unmapped());
        assertEquals(TRANSLATED, result, "unmapped 时不应改变文本");
    }

    @Test
    @DisplayName("🔴 A3 验收：故意写坏的转译产物 → 归因必须指到包内文件与行号")
    void brokenOutputAttributesToPackSourceLine() {
        // 模拟：转译产物第 3 行是坏的，它来自包内 composite.fsh 第 7 行
        SourceLineMap map = lineMap("composite.fsh", 5, 6, 7, 8);
        String fakeError = "vkdisp:pack/BSL/composite.fsh:7:5: error: syntax error";
        LineDirectiveInjector.Attribution attribution =
                LineDirectiveInjector.attributeError(fakeError, map);

        assertEquals(LineDirectiveInjector.Cause.PACK_SOURCE, attribution.cause(),
                "错误行 7 在 lineMap 里有起源 ⇒ 归因为包源（不是合成行）");
        assertFalse(attribution.locations().isEmpty(), "必须解析出至少一个位置");
        LineDirectiveInjector.MappedLocation loc = attribution.locations().get(0);
        assertEquals("composite.fsh", loc.originFile());
        assertEquals(7, loc.originLine());
        assertTrue(loc.readable().contains("composite.fsh:7"),
                "可读形式必须含包内文件:行，实测：" + loc.readable());
    }

    @Test
    @DisplayName("🔴 A3 验收：错误落在合成行 → 归因为 TRANSLATION_BUG（本项目 ERROR）")
    void syntheticLineErrorIsTranslationBug() {
        // 第 2 行是合成行（我们注入的 uniform 块）
        SourceLineMap map = lineMap("composite.fsh", 1, 0, 3, 4);
        String fakeError = "vkdisp:pack/BSL/composite.fsh:2:1: error: unknown type";
        LineDirectiveInjector.Attribution attribution =
                LineDirectiveInjector.attributeError(fakeError, map);

        assertEquals(LineDirectiveInjector.Cause.TRANSLATION_BUG, attribution.cause(),
                "合成行（origin=0）的错误 = 本项目转译 bug");
        assertTrue(attribution.summary().contains("TRANSLATION_BUG"),
                "摘要必须写明归因，实测：" + attribution.summary());
    }

    @Test
    @DisplayName("🔖 无映射时归因为 UNKNOWN（不猜）")
    void noMapMeansUnknown() {
        LineDirectiveInjector.Attribution attribution =
                LineDirectiveInjector.attributeError("error at line 5", SourceLineMap.unmapped());
        assertEquals(LineDirectiveInjector.Cause.UNKNOWN, attribution.cause());
    }

    @Test
    @DisplayName("🔖 注入后的文本仍含原始代码行（#line 不吞代码）")
    void injectedTextPreservesAllCodeLines() {
        SourceLineMap map = lineMap("composite.fsh", 1, 2, 3, 4);
        String injected = LineDirectiveInjector.inject(TRANSLATED, map);
        for (String originalLine : TRANSLATED.split("\n")) {
            if (!originalLine.isBlank()) {
                assertTrue(injected.contains(originalLine),
                        "原始代码行必须完整保留在注入后文本里，缺失：" + originalLine);
            }
        }
    }
}
