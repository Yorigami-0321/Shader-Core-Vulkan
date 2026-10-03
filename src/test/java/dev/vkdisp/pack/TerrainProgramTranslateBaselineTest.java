package dev.vkdisp.pack;
import dev.vkdisp.glsl.TranslateResult;
import dev.vkdisp.glsl.translate.OfGlslTranslator;
import dev.vkdisp.glsl.translate.ShaderStage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【参考调研】用**真实包**（BSL v10.1.8 的 {@code gbuffers_terrain}）量测翻译链的边界 /
 * 只读本仓库源码与本地库存包，不复制任何第三方着色器文本进仓库。
 *
 * <p>🔖 <b>本测试回答的问题</b>：GAP-003 要让地形走包的自研 {@code gbuffers_terrain}，
 * 翻译链在**文本层**到底走到哪一步了？—— 结论是「文本层已经通了」，这与 GAP-003 登记里
 * 「多附件原语与地形接入都没做」的旧判断不同，必须用真实输入钉住。
 */
class TerrainProgramTranslateBaselineTest {

    private static final Path PACK = Path.of("run/shaderpacks/BSL_v10.1.8.zip");
    private static final String PROGRAM = "shaders/program/gbuffers_terrain.glsl";

    @Test
    @DisplayName("🔖 基线：BSL gbuffers_terrain 的 FSH 段现在能零 ERROR 翻译，且合成 5 个输出声明")
    void terrainFragmentTranslatesWithoutErrors() throws Exception {
        String fsh = readStage("FSH");
        assertTrue(fsh.split("\n").length > 300,
                "FSH 段提取异常：只有 " + fsh.split("\n").length + " 行，说明 #ifdef/#endif 配对逻辑退化了");

        TranslateResult result = OfGlslTranslator.translate(ShaderStage.FRAGMENT, fsh);

        List<String> errors = new ArrayList<>();
        for (var d : result.diagnostics()) {
            if (d.severity().isError()) {
                errors.add("line " + d.line() + ": " + d.message());
            }
        }
        assertTrue(errors.isEmpty(),
                "🔖 BSL 真实地形片元应当零 ERROR 地通过翻译链；实际有：\n  " + String.join("\n  ", errors));

        String out = result.text();
        assertFalse(out.contains("gl_FragData"),
                "输出里不该残留 gl_FragData（GLSL 120 内建在 core profile 不存在）");

        // BSL 的 gbuffers_* 系列最多写 5 个槽（gl_FragData[0..4]）⇒ 合成 5 个 location 声明。
        for (int loc = 0; loc <= 4; loc++) {
            assertTrue(out.contains("layout(location = " + loc + ") out vec4"),
                    "缺少 location " + loc + " 的输出声明 —— BSL 的 DRAWBUFFERS:08367 分支需要 5 个槽");
        }
    }

    @Test
    @DisplayName("🔖 诊断里必须保留「包内未声明 location N 的片元输出」这条可见信息")
    void synthesizedOutputsAreReported() throws Exception {
        TranslateResult result = OfGlslTranslator.translate(ShaderStage.FRAGMENT, readStage("FSH"));
        long synth = result.diagnostics().stream()
                .filter(d -> d.message().contains("未声明 location") && d.message().contains("片元输出"))
                .count();
        assertEquals(5, synth,
                "应恰好报告 5 条合成输出声明；数量变了说明 FragmentOutputAdapter 的行为变了，"
                        + "必须先弄清再改（GAP-003 依赖「最多 5 槽」这个事实）");
    }

    @Test
    @DisplayName("🔖 记录真实基线数字：游离 uniform 条数（供 GAP-004 那个块对照）")
    void looseUniformCountBaseline() throws Exception {
        TranslateResult result = OfGlslTranslator.translate(ShaderStage.FRAGMENT, readStage("FSH"));
        var msg = result.diagnostics().stream()
                .filter(d -> d.message().contains("游离非透明 uniform"))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "没有「游离非透明 uniform 收编」诊断 —— BSL 的地形片元大量使用 OF 内建，"
                                + "若这条消失说明 UniformInjector 退化了"));
        // 只锁「存在且为正数」这个性质，具体数字随包版本变，写进证据文档而不是硬编码在此。
        String text = msg.message();
        assertTrue(text.matches(".*\\d+ 条游离非透明 uniform.*"),
                "诊断措辞变了，无法从消息里读出条数：" + text);
    }

    // ------------------------------------------------------------------ 辅助

    private static String readStage(String macro) throws Exception {
        Assumptions.assumeTrue(Files.exists(PACK), "BSL 库存包不在本地（run/shaderpacks/BSL_v10.1.8.zip）");
        try (ZipFile zf = new ZipFile(PACK.toFile())) {
            String src = new String(zf.getInputStream(zf.getEntry(PROGRAM)).readAllBytes(),
                    StandardCharsets.UTF_8);
            return extractStage(src, macro);
        }
    }

    /**
     * 取 {@code #ifdef <macro>} … 匹配 {@code #endif} 之间的段。
     *
     * <p>🔖 <b>为什么必须按嵌套计数</b>（本测试第一版踩过）：BSL 这份文件是 **CRLF**，
     * 而且**嵌套的 {@code #endif} 也顶格**（第 29 行就是）。任何「找下一个 {@code #endif}」
     * 的写法都会在第一层嵌套处截断 —— 实测只取到 20 行（真实应为 438 行），
     * 而 20 行里恰好没有一句 {@code gl_FragData}，于是探针会**误报「翻译全过、5 个输出齐备」**。
     * 这正是 X38 的又一次实例：判据必须真的作用在被测内容上。
     */
    private static String extractStage(String src, String macro) {
        String[] lines = src.split("\n", -1);
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals("#ifdef " + macro)) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            throw new AssertionError("包内找不到 #ifdef " + macro);
        }
        int depth = 0;
        for (int i = start; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.startsWith("#if")) {
                depth++;
            } else if (t.startsWith("#endif")) {
                depth--;
                if (depth == 0) {
                    return String.join("\n", Arrays.copyOfRange(lines, start, i + 1));
                }
            }
        }
        throw new AssertionError("#ifdef " + macro + " 没有配对的 #endif");
    }
}