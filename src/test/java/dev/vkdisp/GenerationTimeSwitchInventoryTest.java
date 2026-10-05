package dev.vkdisp;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * 🔖🔖 **QD-08 结构性守卫**：配置项「在生成期被读」与「必须同步进记忆键/快照」绑成显式清单。
 *
 * <p><b>它守的是什么</b>：本项目有一条反复出现的失败形态（QD-02 / h33 / h43 / 本轮各一例）——
 * <b>配置项存在、能读、某条链静默不生效，日志无异常</b>。
 * 四次的共同根因是：<b>没有任何机制</b>把「你新增了一个在生成期被读的配置项」
 * 与「你必须把它加进记忆键」这两件事绑在一起。
 * `VirtualPackMemoKeyTest` 只守卫了<b>既有三项</b>，是清单型守卫 ——
 * 新增第四项时它不会提醒任何人。
 *
 * <p><b>本类的做法（结构性，不是清单）</b>：从源码里**枚举**所有
 * 「在包源生成窗口内被 {@code .get()} 读取」的配置项，再枚举记忆键里出现的配置项，
 * 然后断言<b>前者 ⊆ 后者</b>。
 * ⇒ 新增一个生成期配置项却忘了进键 ⇒ 本测试当场变红，<b>不需要人去记得更新清单</b>。
 */
class GenerationTimeSwitchInventoryTest {

    private static final Path CONFIG = Path.of("src/main/java/dev/vkdisp/VkDispConfig.java");
    private static final Path VIRTUAL_PACK = Path.of("src/main/java/dev/vkdisp/VkDispVirtualPack.java");
    private static final Path TERRAIN_SOURCE = Path.of("src/main/java/dev/vkdisp/pack/PackTerrainSource.java");

    private static String readOrSkip(Path path) {
        Assumptions.assumeTrue(Files.exists(path), "工程文件缺失: " + path);
        try {
            return Files.readString(path);
        } catch (java.io.IOException e) {
            throw new AssertionError("读取失败: " + path, e);
        }
    }

    /** 去掉注释与字符串字面量（否则 javadoc 里的配置项名会被当成真读取）。 */
    private static String codeView(String source) {
        StringBuilder out = new StringBuilder(source.length());
        for (String line : source.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                continue;
            }
            int blockComment = line.indexOf("/*");
            int lineComment = line.indexOf("//");
            int cut = -1;
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

    /** 全部 `public static final ModConfigSpec.*Value <NAME>` 字段名。 */
    /**
     * 全部 {@code public static final ModConfigSpec.*Value <NAME>} 字段名。
     *
     * <p>🔖🔖 正则必须容许<b>泛型参数</b>：{@code ModConfigSpec.ConfigValue<String>}
     * 才是 profile / selection / packOptionsScreen 三项的真实类型；
     * 首版写成 {@code ModConfigSpec\.[\w.]+} ⇒ 在 {@code <} 处失配 ⇒ 那三项<b>根本没被枚举到</b>
     * ⇒ 守卫会把它们误报成「键里引用了不存在的字段」。
     * 这类「守卫自己先坏了」的失败比没有守卫更糟（假红会让人去改对的代码）。
     */
    private static Set<String> allConfigFields(String configCode) {
        Pattern pattern = Pattern.compile(
                "public\\s+static\\s+final\\s+ModConfigSpec\\.[\\w.]+(?:<[^>]*>)?\\s+(\\w+)\\s*=");
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(configCode);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /** 记忆键方法体里出现的配置字段名。 */
    private static Set<String> memoKeyFields(String packCode) {
        String code = codeView(packCode);
        int start = code.indexOf("private static String currentTerrainMemoKey()");
        assertTrue(start > 0, "找不到 currentTerrainMemoKey（记忆键的单一真源）—— 先修实现");
        // 🔖🔖 取**方法体**（花括号配平），不取固定长度的窗口：
        //   固定窗口会吃到方法后面的东西（本轮就吃到了 javadoc 里的 `PARALLAX`，
        //   于是守卫把一个**注释里的字样**当成「键里引用了不存在的配置字段」而误报）。
        int open = code.indexOf('{', start);
        int depth = 0;
        int end = code.length();
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    end = i;
                    break;
                }
            }
        }
        String body = code.substring(start, end);
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("\\b([A-Z][A-Z0-9_]{3,})\\b").matcher(body);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /**
     * 在「包源生成窗口」内被读取的配置项。
     *
     * <p>🔖 窗口 = {@code generateTerrainSource} 与 {@code generateSources} 两个方法体
     * （包 composite / deferred / final / terrain 四个程序的源都在这里生成）。
     * 窗口外读的（如每帧读的诊断开关）**不**进清单 —— 它们不需要进记忆键，
     * 因为改了不影响已生成的源。把两类混在一起正是这个守卫最容易写错的地方。
     */
    /**
     * 在「包源生成窗口」内被读取的配置项。
     *
     * <p>🔖 窗口 = 三个方法：{@code ensureTerrainProgram}（生成窗口的**入口**，
     * 读 profile/selection 并造记忆键）、{@code generateTerrainSource}、
     * {@code generateSources}（composite/deferred/final 四个程序的源在这里生成）。
     * 窗口外读的（如每帧读的诊断开关）**不**进清单 —— 它们不需要进记忆键，
     * 因为改了不影响已生成的源。把两类混在一起正是这个守卫最容易写错的地方。
     */
    private static Set<String> generationTimeReads(String packCode) {
        String code = codeView(packCode);
        Set<String> reads = new LinkedHashSet<>();
        for (String method : List.of(
                "ensureTerrainProgram", "generateTerrainSource", "generateSources")) {
            int start = code.indexOf(" " + method + "(");
            if (start < 0) {
                continue;
            }
            // 🔖 方法体 = 从签名处的第一个 '{' 起，按花括号深度配平。
            //   旧写法用 "\n    }" 找结尾，会在「方法体里有嵌套 lambda/if 且缩进不同」时
            //   提前截断 ⇒ 枚举结果为空 ⇒ 上条守卫恒绿（假绿比无守卫更糟）。
            int open = code.indexOf('{', start);
            if (open < 0) {
                continue;
            }
            int depth = 0;
            int end = code.length();
            for (int i = open; i < code.length(); i++) {
                char c = code.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        end = i;
                        break;
                    }
                }
            }
            String body = code.substring(start, end);
            Matcher matcher = Pattern.compile("\\b([A-Z][A-Z0-9_]{3,})\\.get\\(\\)").matcher(body);
            while (matcher.find()) {
                reads.add(matcher.group(1));
            }
        }
        return reads;
    }

    @Test
    @DisplayName("🔖🔖 生成期被读的配置项 ⊆ 记忆键（漏一个就是 QD-08 那一族的下一例）")
    void everyGenerationTimeSwitchIsInTheMemoKey() {
        Set<String> declared = allConfigFields(readOrSkip(CONFIG));
        Set<String> reads = generationTimeReads(readOrSkip(VIRTUAL_PACK));
        Set<String> inKey = memoKeyFields(readOrSkip(VIRTUAL_PACK));
        List<String> missing = new ArrayList<>();
        for (String name : reads) {
            if (!declared.contains(name)) {
                continue; // 引用了非配置字段（枚举常量等）⇒ 不属本守卫范围
            }
            if (!inKey.contains(name)) {
                missing.add(name);
            }
        }
        assertEquals(List.of(), missing,
                "这些配置项在**包源生成窗口**内被读取，却没有出现在 currentTerrainMemoKey() 里"
                        + " ⇒ 改它们不会触发地形契约重算，而 composite 侧会按新值重编"
                        + " ⇒ 两条链对同一份配置给出互相矛盾的答案，**且没有任何一行日志会抱怨**"
                        + "（QD-08 本体，已发生四次）。\n  修法：把 " + missing
                        + " 加进 VkDispVirtualPack#currentTerrainMemoKey。");
    }

    @Test
    @DisplayName("🔖 守卫本身不是空转：确实枚举到了生成期读取项（否则上条会恒绿）")
    void inventoryIsNotEmpty() {
        Set<String> reads = generationTimeReads(readOrSkip(VIRTUAL_PACK));
        assertTrue(reads.contains("MRT_PACK_TERRAIN_SHADER")
                        || reads.contains("PACK_PROFILE") || reads.contains("ENABLED"),
                "生成期读取清单为空或与已知项全不相交 ⇒ 守卫的解析器坏了，先修守卫。"
                        + "实际枚举到: " + reads);
    }

    @Test
    @DisplayName("🔖 键里用到的每个字段都必须是真配置项（防手抄错名字）")
    void memoKeyFieldsAreRealConfigFields() {
        Set<String> declared = allConfigFields(readOrSkip(CONFIG));
        Set<String> inKey = memoKeyFields(readOrSkip(VIRTUAL_PACK));
        List<String> unknown = new ArrayList<>();
        for (String name : inKey) {
            // 🔖 记忆键方法体里只有**配置字段**是全大写下划线命名；
            //   `PackOptionOverrideSwitch.spec()` 这类类名首字母大写、其余小写，
            //   天然被正则 `[A-Z][A-Z0-9_]{3,}` 排除（Pack… 含小写字母 ⇒ 不匹配）。
            //   所以这里枚举到的每一个都是「声称是配置字段」的名字。
            if (!declared.contains(name)) {
                unknown.add(name);
            }
        }
        assertEquals(List.of(), unknown,
                "记忆键里出现了 VkDispConfig 上不存在的字段名 " + unknown
                        + " ⇒ 键里那一项是拼错的 ⇒ 它<b>永远</b>匹配不到真实配置，"
                        + "等于该项改了不触发重算（h33 实测过「键名当字段名」那一族）");
    }

    @Test
    @DisplayName("🔖 清单的窗口定义必须写在测试里（后人才能判断自己该不该进清单）")
    void windowDefinitionIsDocumented() {
        String source = readOrSkip(TERRAIN_SOURCE);
        assertTrue(source.contains("applyOptionOverrides") && source.contains("applyCapabilityGate"),
                "包源生成窗口里必须能看到门控与覆盖两个阶段 —— "
                        + "本守卫把「生成期」定义成这两个阶段所在的窗口，"
                        + "若实现变了而守卫没变，守卫会静默漏判");
    }
}