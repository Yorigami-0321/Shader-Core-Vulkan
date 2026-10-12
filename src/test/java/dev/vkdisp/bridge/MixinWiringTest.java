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
        assertTrue(config.contains("mixin.captureMainPassArgs"), "M-06 缺少可关闭键 mixin.captureMainPassArgs");
        assertTrue(config.contains("mixin.reuseChunkSampler"), "M-07 缺少可关闭键 mixin.reuseChunkSampler");
        assertTrue(config.contains("mixin.terrainDrawProbe"), "M-08 缺少可关闭键 mixin.terrainDrawProbe");
        assertTrue(config.contains("mixin.cloudProbe"), "M-09 缺少可关闭键 mixin.cloudProbe");
        assertTrue(config.contains("mixin.cloudMatrixRedirect"), "M-10 缺少可关闭键 mixin.cloudMatrixRedirect");
        assertTrue(config.contains("mixin.passAwarePipeline"), "M-15 缺少可关闭键 mixin.passAwarePipeline");
        assertTrue(config.contains("mixin.pinPassOrder"), "M-13 缺少可关闭键 mixin.pinPassOrder");
        assertTrue(config.contains("define(\"mixin.wireTerrain\", true)"), "M-01 默认应为开");
        assertTrue(config.contains("define(\"mixin.bindTerrainParams\", true)"), "M-01b 默认应为开");
        assertTrue(config.contains("define(\"mixin.captureMainPassArgs\", true)"), "M-06 默认应为开");
        assertTrue(config.contains("define(\"mixin.reuseChunkSampler\", true)"), "M-07 默认应为开");
        assertTrue(config.contains("define(\"mixin.terrainDrawProbe\", true)"), "M-08 默认应为开");
        assertTrue(config.contains("define(\"mixin.cloudProbe\", true)"), "M-09 默认应为开");
        assertTrue(config.contains("define(\"mixin.cloudMatrixRedirect\", true)"), "M-10 默认应为开");
        assertTrue(config.contains("define(\"mixin.passAwarePipeline\", true)"), "M-15 默认应为开");
    }

    @Test
    @DisplayName("M-06：两点收一点 —— M-05 那两个 prepareChunkRenders* 的 RETURN 注入必须已删除")
    void m06ReplacesBothM05Points() throws java.io.IOException {
        assertFalse(Files.exists(MIXIN_DIR.resolve("LevelRendererChunkCaptureMixin.java")),
                "M-05 的注入类必须随 M-06 落地一起删除 —— 留着就是两个捕获点并存，"
                        + "「谁写本帧捕获」重新变成运行时竞争（旧类存在的唯一理由就是拿返回值）");
        String config = readOrSkip(CONFIG);
        assertFalse(config.contains("mixin.captureTerrainDraws"),
                "旧键 mixin.captureTerrainDraws 应已被 mixin.captureMainPassArgs 取代（一扇门不许两把钥匙）");
        String mixin = readOrSkip(MIXIN_DIR.resolve("LevelRendererMainPassCaptureMixin.java"));
        assertTrue(mixin.contains("MixinTargets.LEVEL_RENDERER_ADD_MAIN_PASS"),
                "M-06 的注入方法名必须来自 MixinTargets 常量（M1 编码约束 ①）");
        assertTrue(mixin.contains("@At(\"HEAD\")"),
                "只读捕获要在 HEAD：五个形参在方法体第一句就可读，不必等返回值");
    }

    @Test
    @DisplayName("M-06：全类恰好一个非取消的 @Inject，且 terrainFog 与地形 draw 两个形参都照抄")
    void m06IsSingleReadOnlyHeadInjection() {
        String mixin = readOrSkip(MIXIN_DIR.resolve("LevelRendererMainPassCaptureMixin.java"));
        assertEquals(1, countCodeOccurrences(mixin, "@Inject("),
                "两点收一点 ⇒ 全类恰好一个注入（旧 M-05 是两个重载各一个）");
        // 只读的三条硬证据：在 HEAD、不 cancellable、不 setReturnValue。
        // ⚠️ 只查代码行：类 javadoc 里解释了「cancellable 为何刻意不写」，全文匹配会被自己的说明判红。
        assertEquals(0, countCodeOccurrences(mixin, "cancellable"),
                "M-06 是只读捕获，代码里出现 cancellable 就意味着能改掉原版行为");
        assertEquals(0, countCodeOccurrences(mixin, "setReturnValue("),
                "M-06 不调 setReturnValue —— 那是改原版行为，不是只读捕获");
        assertTrue(mixin.contains("GpuBufferSlice terrainFog"),
                "雾切片必须被捕获（GAP-038 补雾的唯一来源，旧 M-05 拿不到它）");
        assertTrue(mixin.contains("ChunkSectionsToRender chunkSectionsToRender"),
                "地形 draw 数据必须被捕获，且类型化（bridge 侧因此不再存 Object）");
    }

    /**
     * 统计**非注释行**里某字面量的出现次数。
     *
     * <p>为什么必须跳过注释：类 javadoc 的【参考调研】块会引用注解与 API 的字面量
     * （例如 {@code @Inject(method = "prepareChunkRenders")}），
     * 直接 split 全文会把文档里的例子算成代码 —— 本测试自己就踩过这个坑。
     */
    private static int countCodeOccurrences(String text, String literal) {
        int count = 0;
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                continue;
            }
            count += (line.split(java.util.regex.Pattern.quote(literal), -1).length - 1);
        }
        return count;
    }

    @Test
    @DisplayName("M1 编码约束 ③：注入方法体首行是埋点转发；纯访问器类改查「接口 + 零反射」")
    void injectMethodsStartWithProbe() throws java.io.IOException {
        Assumptions.assumeTrue(Files.isDirectory(MIXIN_DIR), "mixin 目录不存在");
        try (var files = Files.list(MIXIN_DIR)) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith("Mixin.java")).toList()) {
                List<String> lines = Files.readAllLines(p);
                String text = String.join("\n", lines);
                // 🔖 R3 起有两种注入点形状，判据必须分开，不能逼访问器类去补一个没用的 @Inject：
                //   ① @Inject / @Redirect —— 有方法体 ⇒ 首行必须是 bridge 埋点转发；
                //   ② @Accessor（M-07/M-08/M-09）—— 抽象方法**没有体**，埋点无处放 ⇒
                //      证据换成「它把字段交进 bridge 声明的 IVkdisp* 接口，且文件里没有反射」。
                if (countCodeOccurrences(text, "@Inject(") == 0
                        && countCodeOccurrences(text, "@Redirect(") == 0
                        && countCodeOccurrences(text, "@WrapOperation(") == 0) {
                    assertTrue(countCodeOccurrences(text, "@Accessor(") > 0,
                            p.getFileName() + "：没有 @Inject/@Redirect 就必须是 @Accessor 访问器注入点");
                    assertTrue(countCodeOccurrences(text, "implements IVkdisp") > 0,
                            p.getFileName() + "：访问器类必须 implements bridge 的 IVkdisp* 接口"
                                    + "（否则 bridge 侧取不到值，只能退回反射）");
                    assertEquals(0, countCodeOccurrences(text, "getDeclaredField"),
                            p.getFileName() + "：访问器已经替掉反射，文件里不许再出现 getDeclaredField");
                    continue;
                }
                int injectAt = -1;
                for (int i = 0; i < lines.size(); i++) {
                    String trimmed = lines.get(i).trim();
                    // ⚠️ 必须跳过注释行：类 javadoc 的【参考调研】里会引用 "@Inject(...)" 字样。
                    if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                        continue;
                    }
                    if (trimmed.contains("@Inject(") || trimmed.contains("@Redirect(")
                            || trimmed.contains("@WrapOperation(")) {
                        injectAt = i;
                        break;
                    }
                }
                assertTrue(injectAt >= 0, p.getFileName() + " 里没有 @Inject/@Redirect/@WrapOperation");
                // 先定位方法体的开括号（签名可能跨多行），再取体内第一条语句。
                int bodyAt = -1;
                for (int i = injectAt; i < lines.size() && i < injectAt + 20; i++) {
                    if (lines.get(i).trim().endsWith("{")) {
                        bodyAt = i + 1;
                        break;
                    }
                }
                assertTrue(bodyAt > 0, p.getFileName() + " 的注入注解之后找不到方法体开括号");
                String firstStatement = null;
                for (int i = bodyAt; i < lines.size() && i < bodyAt + 12; i++) {
                    String line = lines.get(i).trim();
                    if (line.isEmpty() || line.startsWith("*") || line.startsWith("//")) {
                        continue;
                    }
                    firstStatement = line;
                    break;
                }
                // 🔖 不写死类名：M-01/M-01b 转发到 TerrainPipelineApi.on*，M-05 转发到
                //    TerrainDrawCapture.onCaptured，M-10 转发到 CloudsIntoGbuffer.onCloudModelViewRequest
                //    —— 判据是「首行是 bridge 侧埋点转发」。
                assertTrue(firstStatement != null
                                && firstStatement.matches(".*\\w+\\.on\\w*\\(.*"),
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
        assertEquals("net.minecraft.client.renderer.LevelRenderer", MixinTargets.LEVEL_RENDERER);
        assertEquals("addMainPass", MixinTargets.LEVEL_RENDERER_ADD_MAIN_PASS);
        // 🔴 M-05 的两个常量随注入点一起删除（M-06 用同一个 addMainPass 点拿两样东西）：
        //    这里刻意断言「不再有 prepareChunkRenders 常量」，免得下一轮有人把它加回来。
        assertEquals(0, countCodeOccurrences(readOrSkip(Path.of(
                        "src/main/java/dev/vkdisp/bridge/MixinTargets.java")),
                "LEVEL_RENDERER_PREPARE_CHUNK_RENDERS"),
                "M-05 已退役：MixinTargets 里不该再留 prepareChunkRenders* 的目标常量");
        // R3（M-07…M-10）新增的目标 —— 逐字对着 26.3.0.41-beta sources jar 的声明核。
        assertEquals("net.minecraft.client.renderer.CloudRenderer", MixinTargets.CLOUD_RENDERER);
        assertEquals("quadCount", MixinTargets.CLOUD_RENDERER_QUAD_COUNT);
        assertEquals("chunkLayerSampler", MixinTargets.LEVEL_RENDERER_CHUNK_LAYER_SAMPLER);
        assertEquals("net.minecraft.client.renderer.chunk.ChunkSectionsToRender$DrawIndirect",
                MixinTargets.CHUNK_SECTIONS_TO_RENDER_DRAW_INDIRECT);
        assertEquals("drawGroupsPerLayer", MixinTargets.CHUNK_SECTIONS_TO_RENDER_DRAW_GROUPS);
        // M-10 的两条硬事实：private 重载必须带完整描述符（CloudRenderer 上有三个叫 render 的方法），
        // 而被替换的那次调用返回 Matrix4f（不是 Matrix4fc）—— 替换者的返回类型跟着它。
        assertEquals("render(Lcom/mojang/renderpearl/api/commands/RenderPass;"
                        + "Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V",
                MixinTargets.CLOUD_RENDERER_RENDER_INNER);
        assertEquals("com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrixCopy()Lorg/joml/Matrix4f;",
                MixinTargets.RENDER_SYSTEM_GET_MODEL_VIEW_MATRIX_COPY);
        // R1a（M-15）：两支取管线调用所在**各自的嵌套变体**，方法必须写完整描述符，
        // 而被包住的调用要带返回类型（@At(target=…) 的写法就是「类.方法(描述符)返回」）。
        assertEquals("net.minecraft.client.renderer.chunk.ChunkSectionsToRender$DrawSeparate",
                MixinTargets.CHUNK_SECTIONS_TO_RENDER_DRAW_SEPARATE);
        assertTrue(MixinTargets.CHUNK_SECTIONS_TO_RENDER_RENDER_INNER.startsWith("render(")
                        && MixinTargets.CHUNK_SECTIONS_TO_RENDER_RENDER_INNER.endsWith(")V"),
                "M-15 的注入方法必须是完整描述符（handler 靠它拿 renderPass 形参）");
        assertEquals("com.mojang.blaze3d.systems.RenderSystem.getCompiledPipeline("
                        + "Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)"
                        + "Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;",
                MixinTargets.RENDER_SYSTEM_GET_COMPILED_PIPELINE);
        assertEquals(1, MixinTargets.MIXIN_CONFIG_COUNT,
                "MIXIN_CONFIG_COUNT 必须与 neoforge.mods.toml 里启用的 [[mixins]] 数一致");
    }
}