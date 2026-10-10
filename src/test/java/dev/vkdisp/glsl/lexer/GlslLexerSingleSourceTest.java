package dev.vkdisp.glsl.lexer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 【参考调研】A1 守卫：转译/解析层不许再长出自己的「行首锚定正则声明」，也不许留第二套注释状态机
 * 0. 合规：参考对象 = 本仓库自有文档与代码事实 —— {@code 07-CONSTRAINTS.md} 的 **X43**
 *    （「一行可有多条声明」⇒ 行首锚定正则会**静默少认**）、{@code 19-IMPROVEMENT-PATHS.md} §2.2 病根 (a)
 *    （「没有单一词法源 ⇒ 每加一个阶段就自带一套判断逻辑」）与 §2.6-A1 的判据原文
 *    （「新守卫 {@code GlslLexerSingleSourceTest}：扫 {@code glsl/**} 里新出现的行首锚定正则声明并禁止增长」）。
 *    零第三方代码（本项目 MIT）。
 * 1. 做法：与 {@code MethodLengthRatchetTest} / {@code ClassLineRatchetTest} 同族 —— 基线 = 当前实测，
 *    只许降；并带「扫描器自身必须能找到东西」的元测试（那条先例的失败模式：基线永远满足不了的判据
 *    等于没有判据）。
 * 2. 性能：❄️ 单测（扫源码）。
 */
class GlslLexerSingleSourceTest {

    /** 被管的源码范围：转译/预处理/词法三层 + 那份「第二套扫描器」所在的提取器。 */
    private static final List<Path> SCANNED = List.of(
            Path.of("src/main/java/dev/vkdisp/glsl"),
            Path.of("src/main/java/dev/vkdisp/pack/GlslDeclarationExtractor.java"));

    /**
     * 「行首锚定的声明正则」= 字符串字面量以 {@code ^} 开头，且里面点了声明关键字。
     * 这类写法是 X43 的复发面：一行两条声明时它会**静默少认**第二条。
     */
    private static final Pattern ANCHORED_DECLARATION = Pattern.compile(
            "\"\\^[^\"]*(uniform|varying|attribute|layout|const\\s|int\\s|float\\s)");

    /**
     * 基线 = 2026-10-10 实测 **2**（{@code DrawBuffersSlotAdapter} 的 location 锚定式等两条）。
     * 只许降；A4（声明解析升级语法级）应当把它降到 0。
     */
    private static final int BASELINE = 2;

    private record Hit(String file, int line, String text) {
    }

    private static List<Path> javaFiles() throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path root : SCANNED) {
            if (Files.isRegularFile(root)) {
                out.add(root);
                continue;
            }
            Assumptions.assumeTrue(Files.isDirectory(root), "路径不存在: " + root);
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(p -> p.toString().endsWith(".java")).sorted().forEach(out::add);
            }
        }
        return out;
    }

    private static List<Hit> anchoredDeclarations() throws IOException {
        List<Hit> hits = new ArrayList<>();
        for (Path file : javaFiles()) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = ANCHORED_DECLARATION.matcher(lines.get(i));
                if (m.find()) {
                    hits.add(new Hit(file.toString(), i + 1, lines.get(i).strip()));
                }
            }
        }
        return hits;
    }

    @Test
    @DisplayName("🔖 行首锚定的声明正则不得增长（X43 口径；A4 的目标是清零）")
    void anchoredDeclarationRegexesDoNotGrow() throws IOException {
        List<Hit> hits = anchoredDeclarations();
        if (hits.size() > BASELINE) {
            StringBuilder detail = new StringBuilder();
            detail.append("实测 ").append(hits.size()).append(" 处行首锚定声明正则，基线 ")
                    .append(BASELINE).append("：");
            for (Hit hit : hits) {
                detail.append('\n').append("  ").append(hit.file()).append(':').append(hit.line())
                        .append("  ").append(hit.text());
            }
            detail.append("\n⇒ 新增阶段请用 ").append(GlslTokens.class.getSimpleName())
                    .append(" 的 token 流 / codeViews（`19` §2.5 的 L1），别再造一套行首锚定正则"
                            + "（X43：一行两条声明时它会静默少认）");
            org.junit.jupiter.api.Assertions.fail(detail.toString());
        }
        assertTrue(hits.size() <= BASELINE);
    }

    @Test
    @DisplayName("🔖 扫描器自身必须能数出锚定正则（否则这条守卫是永远通过的空话）")
    void scannerActuallyFindsAnchoredRegexes() {
        String sample = "    private static final Pattern P = Pattern.compile(\"^\\\\s*uniform\\\\s+\\\\w+\");";
        assertTrue(ANCHORED_DECLARATION.matcher(sample).find(),
                "扫描器必须认得这种写法，否则上面的棘轮等于不存在");
        assertFalse(ANCHORED_DECLARATION.matcher("    int x = 1; // 没有锚定").find(),
                "普通行不该被数进来");
    }

    @Test
    @DisplayName("🔴 第二套注释状态机必须已经并进单一词法源（A1 的实质交付）")
    void secondCommentScannerIsGone() throws IOException {
        String extractor = Files.readString(
                Path.of("src/main/java/dev/vkdisp/pack/GlslDeclarationExtractor.java"));
        assertFalse(extractor.contains("indexOf(\"*/\")"),
                "GlslDeclarationExtractor 里不该再有自写的块注释扫描（它现在应委托 GlslTokens.codeViews）");
        assertTrue(extractor.contains("GlslTokens.codeViews"),
                "提取器必须走单一词法源，实测缺少对 GlslTokens.codeViews 的调用");

        String defineProcessor = Files.readString(
                Path.of("src/main/java/dev/vkdisp/glsl/preprocess/DefineProcessor.java"));
        assertTrue(defineProcessor.contains("GlslTokens.endsWithLineContinuation"),
                "续行判定的唯一实现应在 GlslTokens；DefineProcessor 只留封装");
        assertFalse(defineProcessor.contains("trimmedDirective.endsWith(\"\\\\\")"),
                "DefineProcessor 不该再自己 endsWith(\"\\\\\")（那是第二份口径）");
    }

    @Test
    @DisplayName("🔖 词法源在 glsl/lexer 一处，且被管范围内不许出现第二个 tokenize/stripComments 实现")
    void lexerIsTheOnlyPlaceOwningCommentState() throws IOException {
        int owners = 0;
        for (Path file : javaFiles()) {
            String text = Files.readString(file);
            boolean ownsStateMachine = text.contains("indexOf(\"*/\")") || text.contains("boolean inBlock");
            String normalized = file.toString().replace('\\', '/');
            boolean isLexerOrItsFoundation = normalized.contains("glsl/lexer/")
                    || normalized.endsWith("CommentState.java")
                    || normalized.endsWith("GlslTextScan.java");
            if (ownsStateMachine && !isLexerOrItsFoundation) {
                owners++;
            }
        }
        assertEquals(0, owners,
                "除词法源（glsl/lexer + 它复用的 CommentState/GlslTextScan）之外，"
                        + "不该再有自带注释状态机的文件（实测 " + owners + " 处）");
    }

    @Test
    @DisplayName("🔴 translate/ 阶段的高层操作必须走 GlslTokens 门面，不许直调 GlslTextScan")
    void translateStagesRouteThroughGlslTokens() throws IOException {
        List<String> violations = new ArrayList<>();
        List<String> bannedCalls = List.of(
                "GlslTextScan.codeViews(", "GlslTextScan.preprocessorSkipLines(",
                "GlslTextScan.headerEnd(");
        for (Path file : javaFiles()) {
            String normalized = file.toString().replace('\\', '/');
            boolean isFoundation = normalized.endsWith("GlslTextScan.java")
                    || normalized.contains("glsl/lexer/");
            if (isFoundation) {
                continue;
            }
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                for (String banned : bannedCalls) {
                    if (lines.get(i).contains(banned)) {
                        violations.add(file.getFileName() + ":" + (i + 1) + " → " + banned);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "translate/ 阶段必须走 GlslTokens 门面（A1 的单一入口纪律），"
                        + "不许直调 GlslTextScan 的高层操作。违例：\n  "
                        + String.join("\n  ", violations));
    }
}
