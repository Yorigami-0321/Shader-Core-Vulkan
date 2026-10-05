package dev.vkdisp.glsl.translate;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 🔖🔖 **每个转译段的诊断都必须被并进最终结果**（X45 的结构性守卫）。
 *
 * <p><b>它守的是什么</b>（本轮真实踩到）：新增 7.8 采样因子探针时，我把
 * {@code SampleFactorProbeAdapter.apply(...)} 接进了<b>文本</b>链，
 * 却忘了把它的 {@code diagnostics} 加进返回列表
 * ⇒ 改写<b>照样生效</b>，而「命中 N 处 / 一处都没命中」那条自报<b>永远不出现</b>。
 * 实测时看到「画面没变」，<b>无法区分</b>「开关没生效」与「结论不成立」——
 * 而实际上探针是生效的。这正是诊断段存在的理由被架空。
 *
 * <p>🔖 与 {@code PixelProbeWiringTest} 那几条同源，但守卫的是<b>转译段</b>这一族：
 * 它们的判据都是「改了文本却没自报 ⇒ 静默空转」。
 */
class TranslateStageDiagnosticTest {

    private static final Path TRANSLATOR =
            Path.of("src/main/java/dev/vkdisp/glsl/translate/OfGlslTranslator.java");

    private static String readOrSkip() {
        Assumptions.assumeTrue(Files.exists(TRANSLATOR), "工程文件缺失: " + TRANSLATOR);
        try {
            return Files.readString(TRANSLATOR);
        } catch (java.io.IOException e) {
            throw new AssertionError("读取失败: " + TRANSLATOR, e);
        }
    }

    /** 去掉注释（否则 javadoc 里提到的段名会被当成真调用）。 */
    private static String codeView(String source) {
        StringBuilder out = new StringBuilder(source.length());
        for (String line : source.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                continue;
            }
            int cut = -1;
            int blockComment = line.indexOf("/*");
            int lineComment = line.indexOf("//");
            if (blockComment >= 0) {
                cut = blockComment;
            }
            if (lineComment >= 0 && (cut < 0 || lineComment < cut)) {
                cut = lineComment;
            }
            out.append(cut >= 0 ? line.substring(0, cut) : line).append('\n');
        }
        return out.toString();
    }

    /** 本文件里「被调用」的诊断段适配器（形如 `XxxProbeAdapter.Result v = XxxProbeAdapter.apply(...)`）。 */
    private static Set<String> appliedStages(String code) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile(
                "(\\w+ProbeAdapter)\\.Result\\s+(\\w+)\\s*=\\s*\\1\\.apply\\s*\\(").matcher(code);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    @Test
    @DisplayName("🔖🔖 每个诊断段的 diagnostics 都必须并进最终列表（本轮自踩：文本接了、诊断没接）")
    void everyAppliedProbeStageSurfacesItsDiagnostics() {
        String code = codeView(readOrSkip());
        Set<String> stages = appliedStages(code);
        assertTrue(!stages.isEmpty(),
                "解析不到任何诊断段调用 ⇒ 守卫的解析器坏了，先修守卫。");

        // 对每个段，找到它的结果变量名，再要求存在 `变量名.diagnostics()` 出现在 diagnostics.add 里。
        List<String> missing = new ArrayList<>();
        Matcher matcher = Pattern.compile(
                "(\\w+ProbeAdapter)\\.Result\\s+(\\w+)\\s*=\\s*\\1\\.apply\\s*\\(").matcher(code);
        while (matcher.find()) {
            String variable = matcher.group(2);
            if (!code.contains(variable + ".diagnostics()")) {
                missing.add(matcher.group(1) + "（变量 " + variable + "）");
            }
        }
        assertTrue(missing.isEmpty(),
                "这些诊断段的 diagnostics() 从未被读取 ⇒ 它们的自报（命中数 / 没命中）"
                        + "永远不会进日志 ⇒ 实测看到「画面没变」时**无法区分**"
                        + "「开关没生效」与「结论不成立」（X45）。本轮已因此误判过一次：" + missing);
    }

    @Test
    @DisplayName("🔖🔖 诊断段的**改写结果**必须真的流进后续管线（接了诊断没接文本 = 反向同款缺陷）")
    void everyAppliedProbeStageFeedsTheTextChain() {
        String code = codeView(readOrSkip());
        Matcher matcher = Pattern.compile(
                "(\\w+ProbeAdapter)\\.Result\\s+(\\w+)\\s*=\\s*\\1\\.apply\\s*\\(([^;]*)\\)\\s*;").matcher(code);
        List<String> unused = new ArrayList<>();
        while (matcher.find()) {
            String variable = matcher.group(2);
            // 该变量的 .text() 必须出现在后续某个调用实参里（否则改写被丢弃）。
            if (!code.contains(variable + ".text()")) {
                unused.add(matcher.group(1));
            }
        }
        assertTrue(unused.isEmpty(),
                "这些诊断段的 .text() 从未被使用 ⇒ 它的改写被算出来却丢弃，"
                        + "开关看起来开了而画面毫无变化：" + unused);
    }
}
