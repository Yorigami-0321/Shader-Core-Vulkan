package dev.vkdisp;
/**
 * 【参考调研】GAP-056 仪器的守卫 / 只读工程自身源码与反射面
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓库自有代码（LGPL-3.0-or-later）；断言只用 JUnit 公开 API。
 *    → 可并入本项目（LGPL-3.0-or-later）：是，本文件不引用任何外部实现。
 * 1. 官方/主实现：无（纯文本 + 反射面断言）。
 * 2. 备选：无。
 * 3. 我们的差异点：钉住「全量生效档自报」的三件事 ——
 *    ① 枚举**覆盖**每一个 `.define()` 出来的键（漏键 = 仪器说谎）；
 *    ② 格式化把每个键各打一次、非默认才标 ≠；
 *    ③ 它真的挂在配置事件上，且台账里的针与代码里的标签**逐字相同**。
 * 4. 许可证核对：本项目 LGPL-3.0-or-later。
 * 5. 性能基线：❄️ 单测。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * {@link VkDispEffectiveConfig} 的守卫。
 *
 * <p>🔖 这些断言守的是**仪器本身说谎**这一族：代码能编译、日志也能打出一块东西，
 * 但里面缺了正要查的那个键 —— {@code GAP-056} 的整条撤回就是因为「一行日志为它没读过的键作证」。
 */
class VkDispEffectiveConfigTest {

    private static final Path CONFIG = Path.of("src/main/java/dev/vkdisp/VkDispConfig.java");
    private static final Path HOT_RELOAD = Path.of("src/main/java/dev/vkdisp/VkDispConfigHotReload.java");
    private static final Path REGISTRY = Path.of("docs/13-GAP-REGISTRY.md");

    private static String readOrSkip(Path path) {
        Assumptions.assumeTrue(Files.exists(path), "工程文件缺失: " + path);
        try {
            return Files.readString(path);
        } catch (java.io.IOException e) {
            throw new AssertionError("读取失败: " + path, e);
        }
    }

    /** 源码里 {@code .define(…)}/{@code .defineInRange(…)} 一族调用声明出来的全部键。 */
    private static Set<String> declaredKeys(String configSource) {
        Set<String> keys = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\\.define[A-Za-z]*\\(\"([^\"]+)\"").matcher(configSource);
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }

    /** 反射枚举要能在测试 JVM 里跑（night-config / FML 在 dev 测试类路径上）；跑不动就显式跳过。 */
    private static List<String> runtimeKeys() {
        try {
            return VkDispEffectiveConfig.keyNames();
        } catch (Throwable t) {
            Assumptions.assumeTrue(false,
                    "测试 JVM 里加载不了 ModConfigSpec ⇒ 反射枚举这条没法判: " + t);
            return List.of();
        }
    }

    @Test
    @DisplayName("🔑 每个 .define() 出来的键都挂在 public static 字段上（反射枚举的前提）")
    void everyDeclaredKeyIsEnumerable() {
        String config = readOrSkip(CONFIG);
        List<String> orphans = new ArrayList<>();
        List<String> notPublicStatic = new ArrayList<>();
        for (String line : config.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                continue;
            }
            if (!trimmed.startsWith(".define(\"") && !trimmed.startsWith(".defineInRange(\"")
                    && !trimmed.startsWith(".defineList(\"") && !trimmed.startsWith(".defineInList(\"")
                    && !trimmed.startsWith(".defineOfClass(\"")) {
                continue;
            }
            // 收尾行是语句的最后一段 ⇒ 它所属的声明一定在上方最近的「ModConfigSpec.*Value」那一行。
            Matcher m = Pattern.compile("\\.define[A-Za-z]*\\(\"([^\"]+)\"").matcher(trimmed);
            String key = m.find() ? m.group(1) : trimmed;
            String declaration = nearestDeclarationAbove(config, trimmed);
            if (declaration == null) {
                orphans.add(key);
            } else if (!declaration.contains("public static")) {
                notPublicStatic.add(key + " ← " + declaration.trim());
            }
        }
        assertTrue(orphans.isEmpty(),
                "🔴 这些 .define() 没有挂在字段声明上 ⇒ 生效档自报（按 public static ConfigValue 字段枚举）会漏掉它们："
                        + orphans);
        assertTrue(notPublicStatic.isEmpty(),
                "🔴 这些键的字段不是 public static ⇒ getFields() 拿不到，自报会静默少一行：" + notPublicStatic);

        String reporter = readOrSkip(Path.of("src/main/java/dev/vkdisp/VkDispEffectiveConfig.java"));
        assertTrue(reporter.contains("VkDispConfig.class.getFields()")
                        && reporter.contains("Modifier.isStatic")
                        && reporter.contains("ModConfigSpec.ConfigValue.class.isAssignableFrom"),
                "🔴 自报的枚举口径变了（不再是「本类的 public static ConfigValue 字段」）⇒ 上面的前提就不再保证全覆盖");
    }

    /** 在源码里找 {@code statement} 首次出现处往上最近的一条「ModConfigSpec.*Value 字段声明」起始行。 */
    private static String nearestDeclarationAbove(String source, String statement) {
        String[] lines = source.split("\n");
        int at = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals(statement.trim())) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            return null;
        }
        for (int i = at; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("public static final ModConfigSpec") || line.startsWith("static ModConfigSpec")
                    || line.startsWith("private static final ModConfigSpec")
                    || line.startsWith("protected static final ModConfigSpec")) {
                return line;
            }
        }
        return null;
    }

    @Test
    @DisplayName("🔑 生效档自报的枚举必须逐个覆盖 .define() 出来的键（漏一个 = 仪器说谎）")
    void enumerationCoversEveryDeclaredKey() {
        Set<String> declared = declaredKeys(readOrSkip(CONFIG));
        assertFalse(declared.isEmpty(), "VkDispConfig 里一个 .define( 都没解析到 ⇒ 解析口径已失效");

        List<String> runtime = runtimeKeys();
        Set<String> reported = new LinkedHashSet<>(runtime);
        assertEquals(runtime.size(), reported.size(),
                "🔴 枚举里有重名键（同一个点分路径出现两次）：" + runtime);

        List<String> missing = new ArrayList<>();
        for (String key : declared) {
            if (!reported.contains(key)) {
                missing.add(key);
            }
        }
        List<String> extra = new ArrayList<>(reported);
        extra.removeAll(declared);
        assertTrue(missing.isEmpty(),
                "🔴 这些键在 VkDispConfig 里 .define() 了，但生效档自报**不会打**它们 ⇒ 取证侧读不到： " + missing);
        assertTrue(extra.isEmpty(), "🔴 自报里有 .define() 之外的键（路径拼错？）：" + extra);
    }

    @Test
    @DisplayName("📐 格式化：每个键各一行段内条目，非默认才标 ≠，摘要里的计数要真")
    void formatPrintsEveryKeyOnceAndMarksOnlyNonDefault() {
        List<VkDispEffectiveConfig.Reading> readings = List.of(
                new VkDispEffectiveConfig.Reading("enabled", "true", "true", false),
                new VkDispEffectiveConfig.Reading("mrt.terrain", "true", "false", true),
                new VkDispEffectiveConfig.Reading("mrt.packTerrainShader", "true", "false", true),
                new VkDispEffectiveConfig.Reading("shaderPack", "\"none\"", "\"\"", true));

        List<String> lines = VkDispEffectiveConfig.format("单测档", readings);
        String joined = String.join("\n", lines);

        assertTrue(lines.get(0).startsWith("vkdisp: " + VkDispEffectiveConfig.LABEL + " 档=单测档 4 键 / 非默认 3："),
                "🔴 摘要行口径变了（取证侧的针就是这个标签 + `非默认 N`）：" + lines.get(0));
        assertTrue(lines.get(0).contains("mrt.terrain, mrt.packTerrainShader, shaderPack"),
                "摘要没列出非默认键：" + lines.get(0));

        for (String key : List.of("enabled", "mrt.terrain", "mrt.packTerrainShader", "shaderPack")) {
            String leaf = key.contains(".") ? key.substring(key.indexOf('.') + 1) : key;
            assertEquals(1, occurrences(joined, leaf + "="),
                    "🔴 键 " + key + " 在自报块里出现的次数不是 1（漏打或重打）:\n" + joined);
        }
        assertTrue(joined.contains("terrain=true≠"), "默认值不同的键必须标 ≠:\n" + joined);
        assertFalse(joined.contains("enabled=true≠"), "等于代码默认的键不许标 ≠:\n" + joined);
    }

    @Test
    @DisplayName("🪝 自报真的挂在配置事件上：冷启动每臂一次 + 热重载一次")
    void reportIsHookedToBothConfigEvents() {
        String hotReload = readOrSkip(HOT_RELOAD);
        assertTrue(hotReload.contains("VkDispEffectiveConfig.report(\"启动首载\")"),
                "🔴 没在 ModConfigEvent.Loading 上调全量自报 ⇒ 「每臂回读实际生效档」还是没仪器");
        assertTrue(hotReload.contains("VkDispEffectiveConfig.report(\"热重载\")"),
                "🔴 没在 ModConfigEvent.Reloading 上调全量自报 ⇒ 热重载换臂读不到档");
        int loading = hotReload.indexOf("static void onConfigLoading");
        int firstReport = hotReload.indexOf("VkDispEffectiveConfig.report(\"启动首载\")");
        assertTrue(loading >= 0 && firstReport > loading,
                "🔴 启动首载那次自报不在 onConfigLoading 里（或位置早于它）");
    }

    @Test
    @DisplayName("📌 台账里的针与代码里的标签逐字相同（P1/P5 那一族：文档引用的针匹配不到实际输出）")
    void ledgerNeedleMatchesTheCodeLabel() {
        String registry = readOrSkip(REGISTRY);
        String needle = "`" + VkDispEffectiveConfig.LABEL + "`";
        assertTrue(registry.contains(needle),
                "🔴 docs/13 的 GAP-056 纪律行没有引用代码里的标签 " + needle + " ⇒ 台账的针又变成假绿针");
    }

    private static int occurrences(String text, String literal) {
        int count = 0;
        for (int i = text.indexOf(literal); i >= 0; i = text.indexOf(literal, i + literal.length())) {
            count++;
        }
        return count;
    }
}
