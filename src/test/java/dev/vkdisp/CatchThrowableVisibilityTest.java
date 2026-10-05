package dev.vkdisp;
/**
 * 【参考调研】QD-05 守卫：裸 `catch (Throwable)` 不许把异常整个丢掉
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓库 `docs/07-CONSTRAINTS.md`
 *    · `X11`「禁止用 try/catch 吞掉异常让它看起来能跑」
 *    · `T11`「降级必须可见」
 *    · `X9`「把待确认项用猜的值填」
 *    以及 `docs/QUALITY-DEBT.md` QD-05 的登记原文（`h33` 立）。
 *    全部为本仓库自有文档事实，无外部代码。
 * 1. 官方/主实现：无（纯源码文本扫描）。
 * 2. 备选：
 *    <ul>
 *      <li>① 禁止一切 `catch (Throwable)` —— <b>否决</b>：本仓有大量**正当**的防御性用法
 *          （如 `PackPrecompileScheduler` 明确写着「预编译失败不阻断切换，
 *          真正的加载会在同步路径里再试一次并给出诊断」）。一刀切会逼人删掉必要的防御。</li>
 *      <li>② 只在 review 里手工检查 —— <b>否决</b>：`h33` 的门控死开关就是手工检查漏掉的，
 *          而 QD-05 登记的原文就是「下轮审查按此 grep 全仓」——
 *          <b>也就是说「下轮审查」从来没真正约束住任何东西</b>。</li>
 *    </ul>
 * 3. 我们的差异点：把「`catch (Throwable x)` 必须用到 x，或在块内写明为何可以丢弃」
 *    做成构建期红灯。注意判据<b>不是「catch 了什么类型」</b>——
 *    窄类型 catch（`NumberFormatException` / `IOException`）大量是
 *    「降级到默认值并把原因记进诊断列表」的正当做法（`h35` 全仓审计已核实）。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 单测（扫源码，不进渲染路径）。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「真错误伪装成默认值」这一族的守卫。
 *
 * <p>🔖 <b>本项目已在这条防线上栽了三次</b>：
 * <ol>
 *   <li>QD-02 `debugLog` 死开关（定义齐全、零消费点）；</li>
 *   <li>`h33` `PackCapabilityGateSwitch`：配置键名当字段名去反射 ⇒
 *       `NoSuchFieldException` 被 `catch (Throwable)` 吞掉 ⇒ 开关恒默认关；</li>
 *   <li>`h35` `TerrainPipelineApi#blockAtlasSizeOrEmpty`：
 *       `catch (Throwable t) { return new int[]{0,0}; }` ⇒ 异常整个丢失，
 *       而该值每帧喂进 OF uniform。</li>
 * </ol>
 */
class CatchThrowableVisibilityTest {

    private static final Path SRC = Path.of("src/main/java");

    private static final Pattern CATCH = Pattern.compile(
            "\\}\\s*catch\\s*\\(\\s*(Throwable|Error)\\s+(\\w+)\\s*\\)\\s*\\{");

    /** 块内出现即认为「有交代」：注释，或实际用了捕获变量。 */
    private static final List<String> JUSTIFICATIONS =
            List.of("//", "*", "t.", "e.", "LOGGER.", "debugLog", "printStackTrace");

    private record Site(String file, int line, String var, String body) {
    }

    private static List<String> stripCommentsAndBlanks(String text) {
        List<String> out = new ArrayList<>();
        boolean inBlock = false;
        for (String raw : text.split("\n")) {
            out.add(raw);
            if (inBlock) {
                if (raw.contains("*/")) {
                    inBlock = false;
                }
                continue;
            }
            int i = raw.indexOf("/*");
            if (i >= 0 && raw.indexOf("*/", i + 2) < 0) {
                inBlock = true;
            }
        }
        return out;
    }

    /** 找出所有裸 {@code catch (Throwable|Error)} 的块体原文（<b>保留注释</b>，因为注释就是交代）。 */
    private static List<Site> sitesIn(Path file) throws IOException {
        List<String> lines = stripCommentsAndBlanks(Files.readString(file));
        List<Site> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            java.util.regex.Matcher m = CATCH.matcher(lines.get(i));
            if (!m.find()) {
                continue;
            }
            int depth = 1;
            int j = i;
            while (depth > 0 && j < lines.size() - 1) {
                j++;
                depth += count(lines.get(j), '{') - count(lines.get(j), '}');
            }
            out.add(new Site(file.toString(), i + 1, m.group(2),
                    String.join("\n", lines.subList(i, Math.min(j + 1, lines.size())))));
        }
        return out;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    private static List<Site> allSites() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(SRC), "工程目录缺失: " + SRC);
        List<Site> all = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(SRC)) {
            for (Path p : stream.filter(x -> x.toString().endsWith(".java")).sorted().toList()) {
                all.addAll(sitesIn(p));
            }
        }
        return all;
    }

    @Test
    @DisplayName("🔖🔖 裸 catch (Throwable) 必须用到捕获变量，或在块内写明为什么可以丢弃（QD-05）")
    void bareThrowableCatchMustExplainItself() throws IOException {
        List<Site> bad = new ArrayList<>();
        for (Site s : allSites()) {
            boolean explained = JUSTIFICATIONS.stream().anyMatch(s.body()::contains);
            boolean usesVar = s.body().contains(s.var() + ".")
                    || s.body().contains("(" + s.var())
                    || s.body().contains(", " + s.var())
                    || s.body().contains(" " + s.var() + ")");
            if (!explained && !usesVar) {
                bad.add(s);
            }
        }
        if (!bad.isEmpty()) {
            StringBuilder d = new StringBuilder("以下裸 catch (Throwable) 把异常整个丢掉了，"
                    + "既没用捕获变量也没写明理由（07 X11/T11）：");
            for (Site s : bad) {
                d.append('\n').append("  ").append(s.file().replace("src/main/java/", ""))
                        .append(':').append(s.line());
            }
            d.append("\n⇒ 要么把异常原文记进日志/诊断（首选），要么在块内用注释写清"
                    + "「为什么这里可以安全丢弃」并说明错误会在别处重新暴露。");
            fail(d.toString());
        }
    }

    @Test
    @DisplayName("🔖 守卫本身有效：能抓到无交代的丢弃（否则它只是一句空话）")
    void guardDetectsSilentSwallow() throws IOException {
        Path tmp = Path.of("build/tmp/catch-guard/Silent.java");
        Files.createDirectories(tmp.getParent());
        Files.writeString(tmp, """
                package dev.vkdisp;
                class Silent {
                    void bad() {
                        try {
                            int x = 1;
                        } catch (Throwable t) {
                            return;
                        }
                    }
                }
                """);
        List<Site> found = sitesIn(tmp);
        assertEquals(1, found.size(), "应当识别出这一个 catch");
        Site s = found.get(0);
        boolean explained = JUSTIFICATIONS.stream().anyMatch(s.body()::contains);
        assertTrue(!explained, "无注释无使用的样本必须被判为无交代");
    }

    @Test
    @DisplayName("🔖 守卫不得误伤：有注释交代的正当用法必须放过")
    void guardAcceptsJustifiedUse() throws IOException {
        Path tmp = Path.of("build/tmp/catch-guard/Justified.java");
        Files.createDirectories(tmp.getParent());
        Files.writeString(tmp, """
                package dev.vkdisp;
                class Justified {
                    void ok() {
                        try {
                            int x = 1;
                        } catch (Throwable t) {
                            // 预编译失败不阻断切换：真正的加载会在同步路径里再试一次并给出诊断。
                            boolean b = false;
                        }
                    }
                }
                """);
        List<Site> found = sitesIn(tmp);
        assertEquals(1, found.size());
        Site s = found.get(0);
        boolean explained = JUSTIFICATIONS.stream().anyMatch(s.body()::contains);
        assertTrue(explained, "有注释交代的用法必须被放过 —— 一刀切会逼人删掉必要的防御");
    }

    @Test
    @DisplayName("🔖 窄类型 catch 不在守卫范围（它们大量是正当的降级 + 诊断）")
    void narrowCatchIsOutOfScope() throws IOException {
        Path tmp = Path.of("build/tmp/catch-guard/Narrow.java");
        Files.createDirectories(tmp.getParent());
        Files.writeString(tmp, """
                package dev.vkdisp;
                class Narrow {
                    void parse(String s) {
                        try {
                            int v = Integer.parseInt(s);
                        } catch (NumberFormatException e) {
                            return;
                        }
                    }
                }
                """);
        assertTrue(sitesIn(tmp).isEmpty(),
                "NumberFormatException 降级到默认值是正当做法，不该被这条守卫拦下"
                        + "（h35 全仓审计：81 个 catch 里 41 个已记录异常原文，其余多数是窄类型降级）");
    }
}
