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
    @DisplayName("取片元 memo 时不得无条件清掉适配层 memo")
    void fragmentTakeMustNotDropAdapterMemo() {
        // 🔖 GAP-027 之后「取走」只发生在**逐程序的统一路径** takeSourceMemo(String) 里，
        //   地形那两个方法已退化为一行委托 ⇒ 守卫必须跟着单一真源走。
        //   旧版查的是 takeTerrainSourceMemo() 的方法体 —— 逐程序化之后那是个空壳，
        //   而真正会清 adapterMemo 的两处都在 takeSourceMemo 里 ⇒ 守卫看着在、实际空转。
        assertFieldClearIsGuarded(rawMethodBody("private static String takeSourceMemo(String program)"),
                "entry.adapterMemo");
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
    @DisplayName("自己清的必须留着，否则下次 ensureTerrainProgram 会拿到过期源")
    void fragmentTakeStillClearsItsOwnMemo() {
        String body = methodBody("private static String takeSourceMemo(String program)");
        assertTrue(body.contains("entry.sourceMemo = null;"), "取走即清空是既定契约");
        assertTrue(body.contains("entry.memoKey = null;"), "记忆键也必须清，否则换包后不会重算");
        // 🔖 GAP-027：地形那条**必须经由**这条路径。若哪天有人给它复制一份取走逻辑，
        //   两份状态（逐程序的 entry 与地形专有字段）迟早互相漂移，而症状是
        //   「换了包、地形没换」这种不报错、不留日志的静默失效。
        assertTrue(methodBody("private static String takeTerrainSourceMemo()").contains("takeSourceMemo("),
                "地形片元取走必须委托给逐程序统一路径 takeSourceMemo(String)");
    }

    @Test
    @DisplayName("适配层 memo 必须由自己的 take 方法清理")
    void adapterTakeClearsItsOwnMemo() {
        String body = methodBody("private static String takeAdapterMemo(String program)");
        assertTrue(body.contains("entry.adapterMemo = null;"), "适配层必须自己清自己");
        assertTrue(methodBody("private static String takeTerrainAdapterMemo()").contains("takeAdapterMemo("),
                "地形适配层取走必须委托给逐程序统一路径 takeAdapterMemo(String)");
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

    /**
     * 方法体**原文**（保留缩进与注释）。
     *
     * <p>需要它是因为「对某字段的清空是否被 {@code if} 包住」这类断言看的是<b>位置</b>，
     * 而规整（{@code trim}）恰好把位置信息丢掉。
     */
    private static String rawMethodBody(String signature) {
        String src = read();
        int i = src.indexOf(signature);
        assertTrue(i >= 0, "方法没找到: " + signature);
        // 必须按**行**精确匹配 `    }`（4 空格 + 闭合花括号），不能用 indexOf：
        //   8 空格缩进的 `        }` 在**子串**意义上也包含 `    }`
        //   => 方法体会在任何嵌套 if/块处被**截断**（加 A/B 的 if 块时真的踩到了：
        //   断言看不到块之后的清空语句 => 报了一个根本不成立的失败）。
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
        return src.substring(i, end);
    }

    /** 去掉注释行与缩进后的方法体（做「含某片段」这类断言时用）。 */
    private static String methodBody(String signature) {
        StringBuilder sb = new StringBuilder();
        for (String line : rawMethodBody(signature).split("\n")) {
            String t = line.trim();
            if (!t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*")) {
                sb.append(t).append(10);
            }
        }
        return sb.toString();
    }

    /**
     * 「对某字段的清空必须被 if 约束」—— 裸赋值 = GAP-010 回归。
     *
     * <p>🔴 合法的清空点<b>有两个</b>（GAP-027 逐程序化之后）：
     * <ol>
     *   <li>记忆键不符（配置变了 ⇒ 整条程序的 memo 一起作废；若只作废片元、留着适配层，
     *      就得到「片元是新的、顶点是旧的」的半接线 —— 那正是要防的东西）；</li>
     *   <li>A/B 取证开关 {@code mrt.gap010Regression}。</li>
     * </ol>
     * 两者都在 {@code if} 块内 ⇒ 判据是「<b>缩进必须深于方法体顶层</b>」，而不是
     * 「必须恰好被某个具体条件包住」—— 后者会把第 ① 个（正确且必需）的分支判成违规。
     */
    private static void assertFieldClearIsGuarded(String body, String field) {
        boolean seen = false;
        for (String line : body.split("\n")) {
            String t = line.trim();
            if (t.startsWith("//") || !t.contains(field + " = null;")) {
                continue;
            }
            seen = true;
            int indent = line.indexOf(field);
            assertTrue(indent >= 12,
                    "对 " + field + " 的清空不得出现在方法顶层（必须被 if 约束，否则 = GAP-010 回归）：" + t);
        }
        assertTrue(seen, "守卫失去对象：方法体里找不到 `" + field + " = null;`（契约是否已被搬走？）");
    }

    private static String read() {
        try {
            return Files.readString(Path.of(SRC));
        } catch (java.io.IOException e) {
            throw new AssertionError("读不到源码: " + SRC, e);
        }
    }
}

