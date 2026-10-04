package dev.vkdisp.pack;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PackTerrainMemoTakeTest {

    private static final String SRC = "src/main/java/dev/vkdisp/VkDispVirtualPack.java";

    @Test
    @DisplayName("`U0001f534U0001f536 取片元 memo 时不得清掉适配层 memo")
    void fragmentTakeMustNotDropAdapterMemo() {
        String body = methodBody("private static String takeTerrainSourceMemo()");
        String[] srcLines = body.split("\n");
        // 🔰 允许「A/B 取证开关」这一个例外，但必须是**唯一**的写法：
        //   对 terrainAdapterMemo 的赋值只能出现在 `if (…MRT_GAP010_REGRESSION.get())` 里，
        //   且该开关默认必须为 false（否则等于把已修的缺陷设成出厂行为）。
        for (int k = 0; k < srcLines.length; k++) {
            if (!srcLines[k].contains("terrainAdapterMemo")) {
                continue;
            }
            String window = String.join("\n", java.util.Arrays.copyOfRange(srcLines,
                    Math.max(0, k - 6), Math.min(srcLines.length, k + 1)));
            assertTrue(window.contains("MRT_GAP010_REGRESSION.get()"),
                    "GAP-010 根因：适配层 memo 被片元的取用顺带清空。"
                            + "唯一允许的例外是 A/B 取证开关 mrt.gap010Regression，"
                            + "且必须在 if 条件里（裸赋值一律拒绝）");
        }
        String cfg;
        try {
            cfg = Files.readString(Path.of("src/main/java/dev/vkdisp/VkDispConfig.java"));
        } catch (java.io.IOException e) {
            throw new AssertionError("读 VkDispConfig 失败", e);
        }
        assertTrue(cfg.contains(".define(\"mrt.gap010Regression\", false)"),
                "A/B 开关必须**默认 false** —— 否则等于把已修的缺陷设成出厂行为");
    }

    @Test
    @DisplayName("`U0001f535 自己清的必须留着，否则下次 ensureTerrainProgram 会拿到过期源")
    void fragmentTakeStillClearsItsOwnMemo() {
        String body = methodBody("private static String takeTerrainSourceMemo()");
        assertTrue(body.contains("terrainSourceMemo = null;"),
                "`U0001f535 取走即清空是既定契约");
        assertTrue(body.contains("terrainMemoKey = null;"),
                "`U0001f535 记忆键也必须清，否则换包后不会重算");
    }

    @Test
    @DisplayName("`U0001f536 适配层 memo 必须由自己的 take 方法清理")
    void adapterTakeClearsItsOwnMemo() {
        String body = methodBody("private static String takeTerrainAdapterMemo()");
        assertTrue(body.contains("terrainAdapterMemo = null;"),
                "`U0001f536 适配层必须自己清自己");
    }

    @Test
    @DisplayName("`U0001f536 生成链必须同生共死：取不到片元时重生成并重取适配层")
    void terrainAndAdapterAreAllOrNothing() {
        String src = read();
        int i = src.indexOf("String terrainSource = takeTerrainSourceMemo();");
        assertTrue(i >= 0, "生成链里的取用点没找到");
        int end = src.indexOf("return new GeneratedSources(", i);
        assertTrue(end > i, "找不到生成链的返回点");
        String body = src.substring(i, end);
        assertTrue(body.contains("} else {"),
                "`U0001f535 缺 else 分支：取不到片元就没有重新生成的路径");
        assertTrue(body.contains("terrainAdapter = takeTerrainAdapterMemo();"),
                "`U0001f536 重生成后必须重取适配层，否则半接线");
    }

    private static String methodBody(String signature) {
        String src = read();
        int i = src.indexOf(signature);
        assertTrue(i >= 0, "方法没找到: " + signature);
        // 必须按**行**精确匹配 `    }`（4 空格 + 闭合花括号），不能用 indexOf：
        //   8 空格缩进的 `        }` 在**子串**意义上也包含 `    }`
        //   => 方法体会在任何嵌套 if/块处被**截断**（本轮加 A/B 的 if 块时真的踩到了：
        //   断言看不到块之后的 `terrainMemoKey = null;` => 报了一个根本不成立的失败）。
        //   嵌套块的闭合花括号缩进更深，所以「第一行恰好等于 `    }`」就是方法末尾。
        int end = -1;
        int cursor = i;
        while (true) {
            int nl = src.indexOf('\n', cursor + 1);
            if (nl < 0) {
                break;
            }
            if ("    }".equals(src.substring(cursor + 1, nl))) {
                end = cursor;
                break;
            }
            cursor = nl;
        }
        assertTrue(end > i, "方法体结束没找到: " + signature);
        StringBuilder sb = new StringBuilder();
        for (String line : src.substring(i, end).split("\n")) {
            String t = line.trim();
            if (!t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*")) {
                sb.append(t).append(10);
            }
        }
        return sb.toString();
    }

    private static String read() {
        try {
            return Files.readString(Path.of(SRC));
        } catch (java.io.IOException e) {
            throw new AssertionError("读不到源码: " + SRC, e);
        }
    }
}

