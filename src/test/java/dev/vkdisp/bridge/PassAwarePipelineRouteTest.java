package dev.vkdisp.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 第 4 （M-15）/ 闸门（M-04 ⑤-b）那条判据的行为侧：「<b>故意错配一次必须被拒</b>」。
 *
 * <p>🔖 为什么单独一个文件：判定被抽成了 {@link PassAwarePipelines#routeOf}（入参与返回都是基元），
 * 于是这一格能<b>跑</b>而不是靠「源码里有两句 {@code return requested;}」那种形状针近似。
 * 形状针与真值表必须成对存在 —— 只有真值表，将来谁在 {@code select} 里再写一份 {@code if (ourPass)}
 * 就没人管；只有形状针，判据讲的「行为」从没被验证过。
 */
class PassAwarePipelineRouteTest {

    private static final Path AWARE = Path.of("src/main/java/dev/vkdisp/bridge/PassAwarePipelines.java");

    /** 只取非注释行（javadoc 里引用了同样的代码字样，会污染位置搜索）。 */
    private static String code() {
        assumeTrue(Files.exists(AWARE), () -> "源码不在（" + AWARE + "）⇒ 只跑行为侧");
        try {
            return Files.readString(AWARE);
        } catch (java.io.IOException e) {
            assumeTrue(false, "读不到 " + AWARE + "：" + e.getMessage());
            return "";
        }
    }

    @Test
    @DisplayName("🔴 真值表逐格跑：两条身份都不成立 = 原样返回（故意错配那一格）")
    void mismatchIsRefused() {
        // 🔴 判据本体：ourPass=false 且 takenOverMainPass=false ⇒ 没有任何一条身份证明「这次 draw 在我方 pass 里」
        assertEquals(PassAwarePipelines.ROUTE_VERBATIM, PassAwarePipelines.routeOf(true, false, false),
                "开关开着但两条身份都对不上 ⇒ 绝不把多附件管线交给原版单附件 pass");
        // 身份为真才有资格换管线
        assertEquals(PassAwarePipelines.ROUTE_MRT_LAYER_TABLE, PassAwarePipelines.routeOf(true, true, false),
                "我方 MRT pass ⇒ 查层名表（M-01 那张）");
        assertEquals(PassAwarePipelines.ROUTE_PADDED_MAIN_PASS, PassAwarePipelines.routeOf(true, false, true),
                "⑤-b：接管后的主 pass ⇒ 走补槽公式");
        // 两个身份同时为真不可能发生（一个 pass 对象不能同时是我方 MRT pass 与主 pass），
        // 但顺序必须有定义：先查表 —— 查不到就原样返回，不会把 8 附件管线塞进单附件 pass。
        assertEquals(PassAwarePipelines.ROUTE_MRT_LAYER_TABLE, PassAwarePipelines.routeOf(true, true, true),
                "同时为真时表那一支优先（保守：宁可原样让 setPipeline 响亮地抛，不要静默错配）");
    }

    @Test
    @DisplayName("🔴 开关关着 = 逐字退回原版（可关闭制 X29），且压过两条身份")
    void flagOffOverridesBothIdentities() {
        assertEquals(PassAwarePipelines.ROUTE_VERBATIM, PassAwarePipelines.routeOf(false, false, false));
        assertEquals(PassAwarePipelines.ROUTE_VERBATIM, PassAwarePipelines.routeOf(false, true, false),
                "关掉开关后即使身份对上也不许多附件");
        assertEquals(PassAwarePipelines.ROUTE_VERBATIM, PassAwarePipelines.routeOf(false, false, true));
        assertEquals(PassAwarePipelines.ROUTE_VERBATIM, PassAwarePipelines.routeOf(false, true, true));
    }

    @Test
    @DisplayName("三格路由必须互不相同，且 0 号就是「原样返回」那一格")
    void routeCodesAreDistinct() {
        assertEquals(0, PassAwarePipelines.ROUTE_VERBATIM,
                "默认值 = 原样返回：新增整数没赋值时行为退回原版，不是退回某一条身份分支");
        assertTrue(PassAwarePipelines.ROUTE_MRT_LAYER_TABLE != PassAwarePipelines.ROUTE_PADDED_MAIN_PASS);
        assertTrue(PassAwarePipelines.ROUTE_MRT_LAYER_TABLE != PassAwarePipelines.ROUTE_VERBATIM);
        assertTrue(PassAwarePipelines.ROUTE_PADDED_MAIN_PASS != PassAwarePipelines.ROUTE_VERBATIM);
    }

    @Test
    @DisplayName("反漂移针：select 只许「按格子执行」，判定本体不许在 select 里再长一份")
    void selectDelegatesTheWholeDecision() {
        String source = code();
        int selectAt = source.indexOf("public static RenderPipeline select(");
        int routeAt = source.indexOf("public static final int ROUTE_VERBATIM");
        assertTrue(selectAt > 0 && routeAt > selectAt,
                "select 与路由常量都要在（找不到就让 indexOf 返回 -1，位置比较会假绿）");

        String selectBody = source.substring(selectAt, routeAt);
        assertTrue(selectBody.contains("routeOf(VkDispConfig.MIXIN_PASS_AWARE_PIPELINE.get(), ourPass, takenOverMainPass)"),
                "🔴 开关也必须作为<b>入参</b>交给同一个真值表 —— 否则「关掉就退回原版」这一格没被跑过");
        assertEquals(1, occurrences(selectBody, "routeOf("),
                "判定入口有且只有一个（不许一边 routeOf 一边自己 if）");
        assertEquals(0, occurrences(selectBody, "if (ourPass)"),
                "身份短路只许长在 routeOf 里；在 select 里再写一份就是两套判据");
        assertEquals(0, occurrences(selectBody, "if (takenOverMainPass)"));
        assertEquals(0, occurrences(selectBody, "MIXIN_PASS_AWARE_PIPELINE.get() &&"),
                "不许用「开关 && 身份」这种就地拼布尔的写法（真值表会被绕过）");
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }
}
