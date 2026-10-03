package dev.vkdisp.bridge;
/**
 * 【参考调研】H 线 mixin 接线纪律的单测 / 只读工程自身资源文件
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓库 {@code src/main/resources/vkdisp.mixins.json}、
 *    {@code src/main/templates/META-INF/neoforge.mods.toml}、{@code docs/04-SPEC.md} §5.0、
 *    {@code docs/07-CONSTRAINTS.md} M1/T1/X26（本项目自有文档，MIT）。
 *    → 可并入本项目（MIT）：本文件不引用任何外部代码，只做文本断言。
 * 1. 官方/主实现：无（纯文本断言）。
 * 2. 备选：无。
 * 3. 我们的差异点：把「mixin 静默不加载」这一类**不报错的失效**变成构建期红灯 ——
 *    X26（建了 mixins.json 却没取消 toml 注释）与 T1（compatibilityLevel 必须是 JAVA_25）
 *    都是历史上真实踩过的坑，而它们**不会**让 `./gradlew build` 失败。
 * 4. 许可证核对：本项目 MIT。
 * 5. 性能基线：❄️ 单测。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * H 线 mixin 接线的**纪律守卫**：注入点必须同时出现在
 * 「① mixin 源码 ② {@code vkdisp.mixins.json} ③ {@code neoforge.mods.toml} 的 [[mixins]]」
 * 三处，且配置键存在。任一处缺 ⇒ 注入点静默不生效（T10 / X11 / X26）。
 */
class MixinWiringTest {

    private static final Path MIXIN_DIR = Path.of("src/main/java/dev/vkdisp/mixin");
    private static final Path MIXIN_JSON = Path.of("src/main/resources/vkdisp.mixins.json");
    private static final Path MODS_TOML = Path.of("src/main/templates/META-INF/neoforge.mods.toml");
    private static final Path CONFIG = Path.of("src/main/java/dev/vkdisp/VkDispConfig.java");

    private static String readOrSkip(Path path) {
        Assumptions.assumeTrue(Files.exists(path), "工程文件缺失（不在工程根目录运行？）: " + path);
        try {
            return Files.readString(path);
        } catch (java.io.IOException e) {
            throw new AssertionError("读取失败: " + path, e);
        }
    }

    @Test
    @DisplayName("T1：compatibilityLevel 必须是 JAVA_25（JAVA_21 在 Java 25 下静默跳过全部 mixin）")
    void compatibilityLevelIsJava25() {
        assertTrue(readOrSkip(MIXIN_JSON).contains("\"compatibilityLevel\": \"JAVA_25\""),
                "vkdisp.mixins.json 的 compatibilityLevel 必须是 JAVA_25");
    }

    @Test
    @DisplayName("X26：[[mixins]] 必须已取消注释并指向 ${mod_id}.mixins.json")
    void modsTomlDeclaresMixinConfig() {
        String toml = readOrSkip(MODS_TOML);
        assertFalse(toml.contains("#[[mixins]]"),
                "[[mixins]] 仍被注释 —— mixin 会被静默跳过（X26）");
        assertTrue(toml.contains("[[mixins]]"), "[[mixins]] 段缺失");
        assertTrue(toml.contains("config=\"${mod_id}.mixins.json\""),
                "[[mixins]] 的 config 必须指向 ${mod_id}.mixins.json");
    }

    @Test
    @DisplayName("每个 mixin 源文件都在 vkdisp.mixins.json 的 client 清单里（漏登记 = 静默不加载）")
    void everyMixinClassIsListed() throws java.io.IOException {
        Assumptions.assumeTrue(Files.isDirectory(MIXIN_DIR), "mixin 目录不存在");
        List<String> declared;
        try (var files = Files.list(MIXIN_DIR)) {
            declared = files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith("Mixin.java"))
                    .map(n -> n.substring(0, n.length() - ".java".length()))
                    .sorted()
                    .toList();
        }
        assertFalse(declared.isEmpty(), "mixin 目录里一个 *Mixin.java 都没有");
        String json = readOrSkip(MIXIN_JSON);
        for (String name : declared) {
            assertTrue(json.contains("\"" + name + "\""),
                    "mixin 类未登记进 vkdisp.mixins.json: " + name);
        }
    }

    @Test
    @DisplayName("M1 编码约束 ①：注入点的目标类名只引用 MixinTargets 常量，无散落字面量")
    void targetsComeFromMixinTargetsConstants() throws java.io.IOException {
        Assumptions.assumeTrue(Files.isDirectory(MIXIN_DIR), "mixin 目录不存在");
        Pattern literal = Pattern.compile("targets\\s*=\\s*\\{\\s*\"(net\\.minecraft[^\"]*)\"");
        try (var files = Files.list(MIXIN_DIR)) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith("Mixin.java")).toList()) {
                String text;
                try {
                    text = Files.readString(p);
                } catch (java.io.IOException e) {
                    throw new AssertionError("读取失败: " + p, e);
                }
                Matcher m = literal.matcher(text);
                assertFalse(m.find(),
                        p.getFileName() + " 的 @Mixin(targets=…) 里出现了字面量类名，必须引用 MixinTargets 常量");
            }
        }
    }

    @Test
    @DisplayName("M1 编码约束 ⑤：每个注入点都能一键关闭（配置项存在且默认开）")
    void everyInjectionPointHasItsOwnToggle() {
        String config = readOrSkip(CONFIG);
        assertTrue(config.contains("mixin.wireTerrain"), "M-01 缺少可关闭键 mixin.wireTerrain");
        assertTrue(config.contains("mixin.bindTerrainParams"), "M-01b 缺少可关闭键 mixin.bindTerrainParams");
        assertTrue(config.contains("define(\"mixin.wireTerrain\", true)"), "M-01 默认应为开");
        assertTrue(config.contains("define(\"mixin.bindTerrainParams\", true)"), "M-01b 默认应为开");
    }

    @Test
    @DisplayName("M1 编码约束 ③：注入方法体首行是埋点转发（防静默失败）")
    void injectMethodsStartWithProbe() throws java.io.IOException {
        Assumptions.assumeTrue(Files.isDirectory(MIXIN_DIR), "mixin 目录不存在");
        try (var files = Files.list(MIXIN_DIR)) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith("Mixin.java")).toList()) {
                List<String> lines = Files.readAllLines(p);
                int injectAt = -1;
                for (int i = 0; i < lines.size(); i++) {
                    String raw = lines.get(i);
                    String trimmed = raw.trim();
                    // ⚠️ 必须跳过注释行：类 javadoc 的【参考调研】里会引用 "@Inject(...)" 字样。
                    if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                        continue;
                    }
                    if (trimmed.contains("@Inject(")) {
                        injectAt = i;
                        break;
                    }
                }
                assertTrue(injectAt >= 0, p.getFileName() + " 里没有 @Inject");
                // 先定位方法体的开括号（签名可能跨多行），再取体内第一条语句。
                int bodyAt = -1;
                for (int i = injectAt; i < lines.size() && i < injectAt + 20; i++) {
                    if (lines.get(i).trim().endsWith("{")) {
                        bodyAt = i + 1;
                        break;
                    }
                }
                assertTrue(bodyAt > 0, p.getFileName() + " 的 @Inject 之后找不到方法体开括号");
                String firstStatement = null;
                for (int i = bodyAt; i < lines.size() && i < bodyAt + 12; i++) {
                    String line = lines.get(i).trim();
                    if (line.isEmpty() || line.startsWith("*") || line.startsWith("//")) {
                        continue;
                    }
                    firstStatement = line;
                    break;
                }
                assertTrue(firstStatement != null && firstStatement.contains("TerrainPipelineApi.on"),
                        p.getFileName() + " 的注入方法体首行必须是埋点转发（实测 "
                                + firstStatement + "）");
            }
        }
    }

    @Test
    @DisplayName("目标类名常量与 26.3 实际包名一致（X9：不猜，逐字核对）")
    void mixinTargetConstantsMatchVanilla() {
        assertEquals("net.minecraft.client.renderer.chunk.ChunkSectionLayer",
                MixinTargets.CHUNK_SECTION_LAYER);
        assertEquals("pipeline", MixinTargets.CHUNK_SECTION_LAYER_PIPELINE);
        assertEquals("net.minecraft.client.renderer.chunk.ChunkSectionsToRender",
                MixinTargets.CHUNK_SECTIONS_TO_RENDER);
        assertEquals("renderLayers", MixinTargets.CHUNK_SECTIONS_RENDER_LAYERS);
        assertEquals(1, MixinTargets.MIXIN_CONFIG_COUNT,
                "MIXIN_CONFIG_COUNT 必须与 neoforge.mods.toml 里启用的 [[mixins]] 数一致");
    }
}