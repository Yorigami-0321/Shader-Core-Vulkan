package dev.vkdisp;
/**
 * 【参考调研】B0 棘轮二：主源码「静态非 final 字段」总数不得增长（19 §3.4 / QD-03 / QD-12）
 * 0. 合规：参考对象 = 本仓库 `docs/19-IMPROVEMENT-PATHS.md` §3.2（病根「静态即全局单例」）与
 *    §3.4 方案 3（`StaticFieldRatchetTest`，基线 175，只许降）；口径直接取自 `19` 附录 A-A1 的
 *    grep 命令，**本测试就是那条命令的可执行化**。零第三方代码。
 * 1. 官方/主实现：无（纯源码文本统计）。
 * 2. 备选：① 用编译期字节码统计（真能数出「字段」而非「像字段的文本」）—— 暂不采用：需要跑
 *          javac/ASM，而 `19` 附录 A 的公开口径是文本级，换成字节码会让「文档数字」与「测试数字」
 *          两套并存；先把 A-A1 钉住，字节码口径留给后续轮。
 *          ② 只写文档 —— 否决：QD-03 登记的「95」与实测 175 差了近一倍，正是「没人管会漂」的实例。
 * 3. 差异点：失败信息按**包分布**排序输出（`bridge` 是重灾区），直接指到该动手的地方；
 *    并带「扫描器自身必须能找到东西」的元测试（`MethodLengthRatchetTest:222` 同族）。
 * 4. 许可证：本项目 MIT；零复制。
 * 5. 性能：❄️ 单测（扫源码）。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 静态可变状态棘轮（QD-03 / QD-12）。
 *
 * <p>🔖 <b>数值出处 = 本测试的 {@link #BASELINE}</b>（`19` §5.1：可被脚本算出的数字不许手抄进文档）。
 *
 * <p>🔖 <b>口径的已知局限（如实登记，不藏）</b>：逐行文本匹配，<b>不剥注释</b> ——
 * 与 `19` 附录 A-A1 的 grep 完全一致，因此注释里写 {@code static Foo bar =} 形态的示例会被算进来
 * （见 {@link #scannerFollowsAppendixACaliber()}）。换成剥注释口径会让数字变小，
 * 那属于**换口径**，必须单独立一条并在文档里注明，不许在同一基线上偷偷改算法。
 */
class StaticFieldRatchetTest {

    /**
     * 基线 = 2026-10-10 实测 175（`19` §1 基线表与附录 A-A1 同一数字）。
     * 只许降；抬高必须在 commit 里写清理由（`19` §3.4：「抬基线必须同 PR 内净下降」）。
     */
    private static final int BASELINE = 175;

    private static final Path SRC = Path.of("src/main/java");

    /** 附录 A-A1 的口径：`static` 开头、非 `final`、单行声明、以 `=` 或 `;` 收口。 */
    private static final Pattern STATIC_NON_FINAL = Pattern.compile(
            "static\\s+(?!final)[\\w<>?\\[\\], .]+\\s+\\w+\\s*(=|;)");

    private static int countIn(String text) {
        int total = 0;
        for (String line : text.split("\n")) {
            Matcher m = STATIC_NON_FINAL.matcher(line);
            int seen = 0;
            while (m.find()) {
                seen++;
            }
            total += seen;
        }
        return total;
    }

    /** 相对 `src/main/java/dev/vkdisp/` 的包名（根包记作 {@code (root)}）。 */
    private static String packageOf(Path file) {
        String rel = SRC.relativize(file).toString().replace('\\', '/');
        int cut = rel.lastIndexOf('/');
        if (cut < 0) {
            return "(root)";
        }
        String pkg = rel.substring(0, cut);
        return pkg.startsWith("dev/vkdisp/") ? pkg.substring("dev/vkdisp/".length()) : pkg;
    }

    private static Map<String, Integer> byPackage() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(SRC), "工程目录缺失: " + SRC);
        Map<String, Integer> sums = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.walk(SRC)) {
            for (Path p : stream.filter(x -> x.toString().endsWith(".java")).sorted().toList()) {
                int n = countIn(Files.readString(p));
                if (n > 0) {
                    sums.merge(packageOf(p), n, Integer::sum);
                }
            }
        }
        return sums;
    }

    private static int total() throws IOException {
        int sum = 0;
        for (int n : byPackage().values()) {
            sum += n;
        }
        return sum;
    }

    @Test
    @DisplayName("🔖 静态非 final 字段总数不得超过基线（QD-03 棘轮）")
    void staticMutableFieldCountDoesNotGrow() throws IOException {
        int actual = total();
        if (actual > BASELINE) {
            List<Map.Entry<String, Integer>> ranked = new ArrayList<>(byPackage().entrySet());
            ranked.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed());
            StringBuilder detail = new StringBuilder();
            detail.append("静态非 final 字段实测 ").append(actual).append("，基线 ").append(BASELINE)
                    .append("。按包分布：");
            for (Map.Entry<String, Integer> e : ranked.subList(0, Math.min(6, ranked.size()))) {
                detail.append("\n  ").append(e.getValue()).append("  ").append(e.getKey());
            }
            detail.append("\n⇒ 新增状态请落到实例上（19 §3.4 方案 2 的三级所有权对象）；"
                    + "确需抬高基线要在 commit 里写清理由，并同轮净下降别处一个。");
            org.junit.jupiter.api.Assertions.fail(detail.toString());
        }
        assertTrue(actual > 0, "扫描结果必须非零，否则棘轮是空话");
    }

    @Test
    @DisplayName("🔖 扫描器自身：数得出静态非 final、不把 static final 或实例字段算进来")
    void scannerFollowsAppendixACaliber() {
        String sample = """
                class A {
                    private static final int CONST = 1;
                    static int mutable = 2;
                    static long counter;
                    int instanceField = 3;
                    static Map<String, List<Integer>> registry = new HashMap<>();
                }
                """;
        assertEquals(3, countIn(sample),
                "static int mutable / static long counter / static Map<...> registry 三条；"
                        + "static final 与实例字段都不算");
        // 局限登记：注释行同样会被数到（口径与附录 A-A1 一致，不改口径）
        assertEquals(1, countIn("class B { /* static int inComment = 1; */ }"),
                "不剥注释是本口径的已知局限，必须与附录 A-A1 的 grep 保持同一数字");
    }

    @Test
    @DisplayName("🔖 扫描器必须覆盖全部主源码文件（漏一个包就等于开了个口子）")
    void scannerCoversWholeTree() throws IOException {
        int files;
        try (Stream<Path> stream = Files.walk(SRC)) {
            files = (int) stream.filter(x -> x.toString().endsWith(".java")).count();
        }
        assertTrue(files >= 150, "主源码文件数应不少于 150，实测 " + files);
        Map<String, Integer> sums = byPackage();
        assertTrue(sums.containsKey("bridge"), "bridge 包是静态可变状态重灾区，必须出现在分布里");
    }
}
