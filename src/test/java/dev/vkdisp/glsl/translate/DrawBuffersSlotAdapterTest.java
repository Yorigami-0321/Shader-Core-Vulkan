package dev.vkdisp.glsl.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DRAWBUFFERS 槽位映射的单测（纯文本，无 GPU、无库存包）。
 *
 * <p>🔖 <b>为什么这组断言必须存在</b>：映射错了<b>画面照样出得来</b> ——
 * 材质会写进 colortex1、法线写进 colortex2，没有任何一行日志会抱怨（h06 预言的静默绑错槽）。
 * 而「能力口径」与「生产口径」在同一个源里长得不一样（前者多条分支并存），一旦混淆，
 * 累积合并出来的表会自相矛盾（实测得 [0,3,6,7,7]）。每一条坑都留一个钉子。
 */
class DrawBuffersSlotAdapterTest {

    private static final String NL = String.valueOf((char) 10);

    private static final Pattern DECL = Pattern.compile(
            "layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*out\\s+vec4\\s+(\\w+)");

    /** 生产口径夹具：预处理已求值（无 #if 族指令），带若干累积标记与对应数量的合成声明。 */
    private static String productionSource(int slotCount, String... markers) {
        StringBuilder sb = new StringBuilder("#version 410").append(NL);
        for (String marker : markers) {
            sb.append("    /* DRAWBUFFERS:").append(marker).append(" */").append(NL);
        }
        for (int i = 0; i < slotCount; i++) {
            sb.append("layout(location = ").append(i).append(") out vec4 vkdispFragOut")
                    .append(i).append(";").append(NL);
        }
        return sb.append("void main() { }").append(NL).toString();
    }

    private static List<String> locations(String text) {
        Matcher m = DECL.matcher(text);
        List<String> out = new ArrayList<>();
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    @Test
    @DisplayName("🔖 恒等映射（DRAWBUFFERS:0）不改写、**也不出诊断**（幂等断言靠这条）")
    void identityMappingIsSilent() {
        DrawBuffersSlotAdapter.Result r =
                DrawBuffersSlotAdapter.apply(ShaderStage.FRAGMENT, productionSource(1, "0"));
        assertTrue(r.honored());
        assertFalse(r.remapped());
        assertTrue(r.diagnostics().isEmpty(),
                "恒等映射出 INFO 属噪音，且会破坏「第二遍翻译零新增诊断」的幂等契约");
    }

    @Test
    @DisplayName("🔖 累积语义：0 + 0367 ⇒ gl_FragData[0..3] → colortex 0/3/6/7（h06 实测口径）")
    void cumulativeMarkersMapToColortexIndices() {
        DrawBuffersSlotAdapter.Result r = DrawBuffersSlotAdapter.apply(
                ShaderStage.FRAGMENT, productionSource(4, "0", "0367"));
        assertTrue(r.honored(), "不该拒绝：" + r.failure());
        assertTrue(r.remapped());
        assertEquals(List.of("0", "3", "6", "7"), locations(r.text()),
                "🔖 材质必须落 colortex3、法线落 colortex6、第四槽落 colortex7 ——"
                        + "按下标绑定会**静默写错槽**（画面有内容但通道全错）");
        assertEquals(List.of(0, 3, 6, 7), r.slots());
    }

    @Test
    @DisplayName("🔖 MCBL_SS + 高级材质同开 ⇒ 需要 9 个附件 ⇒ **显式拒绝**（如实报能力边界）")
    void threeStageCumulativeExceedsDeviceLimit() {
        // DRAWBUFFERS:08367 ⇒ 最大下标 8 ⇒ 需要 9 个颜色附件，而 Vulkan maxColorAttachments = 8。
        // 这不是 bug，是如实报告能力边界：宁可「不接线 + 显式拒绝」，
        // 也不夹到 8 槽后静默把 colortex8 写坏（那正是本段要消灭的那类静默错误）。
        DrawBuffersSlotAdapter.Result r = DrawBuffersSlotAdapter.apply(
                ShaderStage.FRAGMENT, productionSource(5, "0", "08", "08367"));
        assertFalse(r.honored(), "需要 9 个附件，必须拒绝而不是夹取");
        assertTrue(r.failure().contains("9 个附件"), r.failure());
        assertFalse(r.remapped());
    }

    @Test
    @DisplayName("🔖 超过 maxColorAttachments(8) ⇒ **显式拒绝**且不改写（绝不静默夹取）")
    void overflowRefusesLoudly() {
        // 08976 ⇒ 五个互异槽位，最大下标 9 ⇒ 需要 10 个附件 > 8。
        DrawBuffersSlotAdapter.Result r = DrawBuffersSlotAdapter.apply(
                ShaderStage.FRAGMENT, productionSource(5, "08976"));
        assertFalse(r.honored(), "最大下标 9 需要 10 个附件，超出能力上限，必须拒绝");
        assertTrue(r.failure().contains("10 个附件"),
                "失败原文要带上实际需要的附件数：" + r.failure());
        assertFalse(r.remapped());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.severity().isError()),
                "拒绝必须同时出一条 ERROR（T11：不许只靠返回值）");
    }

    @Test
    @DisplayName("🔖 单条标记内部槽位重复 ⇒ 拒绝（同一个 colortex 被两个输出抢占）")
    void duplicateSlotsRefuse() {
        DrawBuffersSlotAdapter.Result r = DrawBuffersSlotAdapter.apply(
                ShaderStage.FRAGMENT, productionSource(5, "08999"));
        assertFalse(r.honored());
        assertTrue(r.failure().contains("重复"), r.failure());
    }

    @Test
    @DisplayName("🔖 源里仍有条件指令 ⇒ 按**能力口径**不改写（多条分支同时在场，合并必矛盾）")
    void liveConditionalsMeanCapabilityCaliber() {
        String raw = String.join(NL,
                "#version 410",
                "/* DRAWBUFFERS:0 */",
                "layout(location = 0) out vec4 a0;",
                "#ifdef MCBL_SS",
                "/* DRAWBUFFERS:08 */",
                "layout(location = 1) out vec4 a1;",
                "#else",
                "/* DRAWBUFFERS:0367 */",
                "layout(location = 1) out vec4 a1;",
                "#endif",
                "");
        DrawBuffersSlotAdapter.Result r = DrawBuffersSlotAdapter.apply(ShaderStage.FRAGMENT, raw);
        assertTrue(r.honored(), "能力口径不是缺陷，只是不能按生产口径改写");
        assertFalse(r.remapped());
        assertEquals(locations(raw), locations(r.text()),
                "能力口径必须**逐字节不改写**（保留「下标 = location」的原始形态）");
    }

    @Test
    @DisplayName("🔖 没有标记的包 = 恒等、零诊断（不是缺陷）")
    void noMarkerIsIdentity() {
        String plain = "#version 410" + NL + "layout(location = 0) out vec4 c;" + NL
                + "void main() { }" + NL;
        DrawBuffersSlotAdapter.Result r = DrawBuffersSlotAdapter.apply(ShaderStage.FRAGMENT, plain);
        assertTrue(r.honored());
        assertFalse(r.remapped());
        assertTrue(r.diagnostics().isEmpty());
    }

    @Test
    @DisplayName("🔖 顶点阶段不参与（标记只在片元里有意义）")
    void vertexStageIsUntouched() {
        DrawBuffersSlotAdapter.Result r = DrawBuffersSlotAdapter.apply(
                ShaderStage.VERTEX, productionSource(4, "0367"));
        assertFalse(r.remapped());
        assertTrue(r.diagnostics().isEmpty());
    }
}
