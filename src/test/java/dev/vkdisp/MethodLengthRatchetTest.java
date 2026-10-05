package dev.vkdisp;
/**
 * 【参考调研】QD-04 棘轮：主源码「超长方法」数量只许降不许升
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓库 `docs/QUALITY-DEBT.md` QD-04 与 §3
 *    「闭环回路」条款，以及本轮新立的两条审查纪律（来自 `07-CONSTRAINTS.md` §九）：
 *    · 「统计方法长度必须排除声明行」
 *    · 「报数前先人工验证 1 个样本」
 *    全部为本仓库自有文档事实，无外部代码。
 * 1. 官方/主实现：无（纯源码文本统计）。
 * 2. 备选：
 *    <ul>
 *      <li>① 逐个把 20 个长方法都拆掉 —— <b>否决（本轮不做）</b>：它们分属
 *          转译器核心 / 解析器 / 渲染编排三类，拆渲染编排（`FrameApi#drawFullscreen`
 *          355 行）会动到热路径，必须单独一轮取证，不能和文档轮混在一起。</li>
 *      <li>② 只写进文档不做守卫 —— <b>否决</b>：QD-04 的条目本身就证明了
 *          「写了没人管会漂」—— 它记的是「3 个 `>60` 行方法」，
 *          而 `h35` 实测是 <b>20 个</b>，已错了至少一轮。
 *          与 QD-01 同族：<b>规范/结论必须由守卫兜住</b>。</li>
 *    </ul>
 * 3. 我们的差异点：把「`>60` 行方法数不得增加」做成构建期红灯；
 *    真要拆方法时，改基线是**有意为之**，会出现在 diff 里被人看见。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 单测（扫源码，不进渲染路径）。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 长方法棘轮。
 *
 * <p>🔖 <b>为什么要有它</b>：`QUALITY-DEBT.md` 的 QD-04 长期写着
 * 「主源码仍有 <b>3</b> 个 `>60` 行方法（2026-09-30 时 17 个，已显著改善）」，
 * `h35` 实测是 <b>20 个</b> —— 而且这条「已显著改善」的结论是<b>错的</b>。
 * 与 `h33` 的门控死开关、`h34` 的日志刷屏同属一类：<b>结论写下来了，但没有东西让它保持正确</b>。
 */
class MethodLengthRatchetTest {

    /** 阈值：与 QUALITY-DEBT QD-04 / 07 §九 的口径一致。 */
    private static final int LIMIT = 60;

    /**
     * 当前基线（2026-10-05 实测，见 `evidence/h35-…`）。
     *
     * <p>🔖 <b>为什么是 21 而不是登记里写的 3</b>：`h35` 实测。
     * 登记原文「3 个 `>60` 行方法（2026-09-30 时 17 个，已显著改善）」——
     * 该结论至少错了<b>一整轮</b>，且没有任何东西让它保持正确
     * （QD-04 原定的下一步就是「下一轮审查先定位这 3 个方法」，
     * 也就是说：**没人真的数过**，否则会发现根本不是 3 个）。
     *
     * <p>🔖 <b>降低基线是允许的</b>（那是进步），<b>提高基线需要在 diff 里被看见</b>。
     * 之所以不直接卡 0：转译器核心与解析器里的长方法本就可接受
     * （QD-04 原话：「若是 LegacyBuiltinInjector/OfGlslTranslator 核心转译逻辑，
     * 长方法可接受」），硬卡 0 会逼着人做无意义的拆分。
     */
    private static final int BASELINE = 21;

    private static final Path SRC = Path.of("src/main/java");

    private static final Pattern DECL = Pattern.compile(
            "^\\s{4,}(?:(?:public|private|protected|static|final|synchronized|abstract|default)"
                    + "\\s+)+[\\w.$<>\\[\\]]+\\s+\\w+\\s*\\(");

    private static final Pattern TYPE = Pattern.compile(
            "^(?:public\\s+|final\\s+|abstract\\s+|sealed\\s+|non-sealed\\s+)*"
                    + "(?:class|interface|enum|record)\\s+\\w+");

    /**
     * 一个方法。
     *
     * @param lines **正文行数 = 闭合行 − 声明行**：即「声明行之后到闭合行（含）」的行数。
     *               🔖 口径来自 `07-CONSTRAINTS.md` §九「统计方法长度**必须排除声明行**」——
     *               排除的只有声明行，闭合行仍算正文的一部分。
     */
    private record Method(int lines, String file, int declLine, String signature) {
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /** 剥掉 javadoc / 块注释 / 行注释 —— 统计长度必须只看真代码（07 §九 审查纪律）。 */
    private static List<String> stripNonCode(String text) {
        List<String> out = new ArrayList<>();
        boolean inBlock = false;
        for (String raw : text.split("\n")) {
            String line = raw;
            for (int guard = 0; guard < 400; guard++) {
                if (inBlock) {
                    int end = line.indexOf("*/");
                    if (end < 0) {
                        line = "";
                        break;
                    }
                    inBlock = false;
                    line = line.substring(end + 2);
                    continue;
                }
                int start = line.indexOf("/*");
                if (start < 0) {
                    break;
                }
                int end = line.indexOf("*/", start + 2);
                if (end < 0) {
                    inBlock = true;
                    line = line.substring(0, start);
                    break;
                }
                line = line.substring(0, start) + line.substring(end + 2);
            }
            int cmt = line.indexOf("//");
            if (cmt >= 0) {
                line = line.substring(0, cmt);
            }
            out.add(line);
        }
        return out;
    }

    private static List<Method> methodsIn(Path file) throws IOException {
        List<String> lines = stripNonCode(Files.readString(file));
        List<Method> out = new ArrayList<>();
        int depth = 0;
        int classDepth = -1;
        int methodDepth = -1;
        int declLine = 0;
        String signature = "";

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (classDepth < 0 && depth == 0 && TYPE.matcher(line.trim()).find()) {
                classDepth = 1;
            }
            if (classDepth > 0 && depth == classDepth && DECL.matcher(line).find()) {
                signature = line.trim();
                declLine = i;
                methodDepth = -1;
            }
            if (methodDepth < 0 && depth == classDepth && line.contains("{") && !signature.isEmpty()) {
                methodDepth = classDepth + 1;
            }
            depth += countChar(line, '{') - countChar(line, '}');
            if (classDepth > 0 && depth <= classDepth) {
                if (methodDepth >= 0 && !signature.isEmpty()) {
                    // 行数 = 闭合行 - 声明行（**不含声明行本身**，07 §九 的口径）
                    out.add(new Method(i - declLine, file.toString(), declLine + 1, signature));
                }
                depth = classDepth;
                methodDepth = -1;
                signature = "";
            }
        }
        return out;
    }

    private static List<Method> allMainSourceMethods() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(SRC), "工程目录缺失: " + SRC);
        List<Method> all = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(SRC)) {
            for (Path p : stream.filter(x -> x.toString().endsWith(".java")).sorted().toList()) {
                all.addAll(methodsIn(p));
            }
        }
        return all;
    }

    @Test
    @DisplayName("🔖🔖 主源码 `>60` 行方法数不得超过基线（QD-04 棘轮）")
    void longMethodCountDoesNotGrow() throws IOException {
        List<Method> over = allMainSourceMethods().stream()
                .filter(m -> m.lines() > LIMIT)
                .sorted(Comparator.comparingInt(Method::lines).reversed())
                .toList();

        if (over.size() > BASELINE) {
            StringBuilder detail = new StringBuilder();
            detail.append("当前 ").append(over.size()).append(" 个 >").append(LIMIT)
                    .append(" 行方法，基线是 ").append(BASELINE).append("。超长方法排行：");
            for (int i = 0; i < Math.min(8, over.size()); i++) {
                Method m = over.get(i);
                detail.append('\n').append("  ").append(m.lines()).append("  ")
                        .append(m.file().replace("src/main/java/", "")).append(':').append(m.declLine())
                        .append("  ").append(m.signature());
            }
            detail.append("\n⇒ 拆方法请**单独一轮**做（改动热路径要单独取证）；")
                    .append("若确实接受这个增长，请**有意**调高 BASELINE 并在 commit 里写清理由。");
            org.junit.jupiter.api.Assertions.fail(detail.toString());
        }
        assertTrue(allMainSourceMethods().size() > 100,
                "扫描器必须真的扫到方法（否则一个 0 结果就会把棘轮变成永远通过的空话）");
    }

    @Test
    @DisplayName("🔖 统计口径：行数不含声明行（07 §九 的审查纪律）")
    void lineCountExcludesDeclarationLine() {
        String src = """
                package dev.vkdisp;
                class Sample {
                    public static void tiny() {
                        int a = 1;
                    }
                }
                """;
        List<Method> found;
        try {
            Path tmp = Path.of("build/tmp/ratchet-sample/Sample.java");
            Files.createDirectories(tmp.getParent());
            Files.writeString(tmp, src);
            found = methodsIn(tmp);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        assertEquals(1, found.size(), "应当只识别出 tiny() 一个方法");
        assertEquals(2, found.get(0).lines(),
                "tiny() = 声明行 + 1 行代码 + 闭合行 ⇒ 正文行数 2（**不含声明行**，含闭合行）；"
                        + "若把声明行算进去就会是 3");
    }

    @Test
    @DisplayName("🔖 扫描器必须能数出超长方法（否则棘轮是空话）")
    void scannerDetectsLongMethod() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            body.append("        int v").append(i).append(" = ").append(i).append(";\n");
        }
        String src = "package dev.vkdisp;\nclass Fat {\n"
                + "    public static void huge() {\n" + body + "    }\n}\n";
        List<Method> found;
        try {
            Path tmp = Path.of("build/tmp/ratchet-sample/Fat.java");
            Files.createDirectories(tmp.getParent());
            Files.writeString(tmp, src);
            found = methodsIn(tmp);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        assertEquals(1, found.size());
        assertTrue(found.get(0).lines() > LIMIT,
                "80 行正文的方法必须被判为超长，实测 " + found.get(0).lines());
    }

    @Test
    @DisplayName("🔖 注释里的代码不算方法体（否则大段 javadoc 会虚增行数）")
    void commentsAreNotCounted() {
        String src = """
                package dev.vkdisp;
                class Doc {
                    /**
                     * { { { { { { { { { { { { { { { { { { { { { { { { { { { { { { { { { { { { { {
                     * } } } } } } } } } } } } } } } } } } } } } } } } } } } } } } } } } } }
                     */
                    public static void real() {
                        return;
                    }
                }
                """;
        List<Method> found;
        try {
            Path tmp = Path.of("build/tmp/ratchet-sample/Doc.java");
            Files.createDirectories(tmp.getParent());
            Files.writeString(tmp, src);
            found = methodsIn(tmp);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        assertEquals(1, found.size(), "javadoc 里的花括号不得被当成方法体");
        assertEquals(2, found.get(0).lines(),
                "real() = 声明行 + 1 行 return + 闭合行 ⇒ 2；"
                        + "若 javadoc 里的花括号被当成方法体，这个数会变成 40+");
    }
}
