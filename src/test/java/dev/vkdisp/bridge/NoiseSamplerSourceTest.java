package dev.vkdisp.bridge;
/**
 * 【参考调研】GAP-025 守卫：{@code noisetex} 的来源决策必须「包声明优先、回落必点名」
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓库 `docs/13-GAP-REGISTRY.md` GAP-025、
 *    `docs/07-CONSTRAINTS.md` X11（禁止静默降级）· T11（降级必须可见）· X9（不猜），
 *    以及真实包 BSL v10.1.8 的文本事实（`shaders/shaders.properties:141` +
 *    `shaders/tex/noise.png` 512×512 + `shaders/program/final.glsl:41 noiseTextureResolution=512`）。
 *    全部为本仓库自有规范 + 包内公开文本，无外部代码。
 * 1. 官方/主实现：OptiFine `texture.noise` 覆盖内建 `noisetex`（别名那一层是 OF 语法事实）。
 * 2. 备选：
 *    <ul>
 *      <li>① 只测渲染路径 —— <b>做不到</b>：单测 JVM 没有 GPU/设备，
 *          故本档刻意把决策摘成不碰原版类型的纯函数（见 NoiseSamplerSource 的类注释）。</li>
 *      <li>② 不测 —— <b>否决</b>：改动前的 bug 正是「恒返回内置图」这种一行代码级别的沉默，
 *          没有红灯就会漂回去（QD-01/QD-04 同一课）。</li>
 *    </ul>
 * 3. 我们的差异点：额外用**真实 BSL zip** 走一遍「properties 文本 → 绑定解析 → 来源决策」全链，
 *    钉住别名表与真实包不放炮（合成用例挡不住「声明名写成 noise_」这种漂移）。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 单测（读 zip 一次），不进渲染路径。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link NoiseSamplerSource} 的名称解析与回落判定（无 GPU 可测的那一半决策）。 */
class NoiseSamplerSourceTest {

    private static final Path BSL = Path.of("run/shaderpacks/BSL_v10.1.8.zip");

    private static NoiseSamplerSource.Decision decide(Map<String, String> bindings,
            Set<String> loaded) {
        return NoiseSamplerSource.decide(bindings, loaded, true);
    }

    @Test
    @DisplayName("🔖 OF 别名：texture.noise 就是内建 noisetex（旧实现在这里精确匹配原名 ⇒ 永远取不到）")
    void noiseSamplerResolvesThroughOFAlias() {
        assertEquals(java.util.List.of("noisetex", "noise"),
                NoiseSamplerSource.declarationNames(NoiseSamplerSource.NOISE_SAMPLER),
                "原名在前、别名在后：包同时写两种时按更明确的原名走");
        // 别的采样器不受别名表影响（既有 dirt/lighttex 绑定语义一字不变）
        assertEquals(java.util.List.of("dirt"), NoiseSamplerSource.declarationNames("dirt"));
    }

    @Test
    @DisplayName("🟢 包声明的噪声图已加载 ⇒ 用包图，内置图完全不参与")
    void declaredAndLoadedWins() {
        NoiseSamplerSource.Decision d = decide(
                Map.of("noise", "tex/noise.png"), Set.of("noise"));
        assertEquals(NoiseSamplerSource.Source.PACK, d.source());
        assertEquals("noise", d.declaredName());
        assertEquals("tex/noise.png", d.declaredPath());
        assertFalse(d.usesBuiltin(), "包图可用时不该再建一张用不上的内置 64x64");
        assertFalse(d.mustDisclose(), "没有降级就没有点名");
    }

    @Test
    @DisplayName("🔴 声明了却没加载上 ⇒ 回落内置 + 必须点名（X11），点名文案带得上原文路径")
    void declaredButMissingDiscloses() {
        NoiseSamplerSource.Decision d = decide(
                Map.of("noise", "tex/noise.png"), Set.of());
        assertEquals(NoiseSamplerSource.Source.BUILTIN_DECLARED_BUT_MISSING, d.source());
        assertTrue(d.usesBuiltin());
        assertTrue(d.mustDisclose(), "静默回落正是 GAP-025 的复发形态");
        assertTrue(d.disclosure().contains("tex/noise.png"),
                () -> "必须能按原文定位根因: " + d.disclosure());
        assertTrue(d.disclosure().contains("64x64"), () -> "回落内容要说清楚: " + d.disclosure());
    }

    @Test
    @DisplayName("🟡 包根本没声明 texture.noise ⇒ 仍是内置图，但频率风险要写进日志")
    void undeclaredStillDisclosesBuiltin() {
        Map<String, String> bindings = new LinkedHashMap<>();
        bindings.put("dirt", "tex/dirt.png");
        NoiseSamplerSource.Decision d = decide(bindings, Set.of("dirt"));
        assertEquals(NoiseSamplerSource.Source.BUILTIN_NOT_DECLARED, d.source());
        assertTrue(d.usesBuiltin());
        assertTrue(d.mustDisclose(),
                "包的 noiseTextureResolution 未必是 64 ⇒ 「什么都没发生」是假的");
        assertTrue(d.disclosure().contains("texture.noise"), () -> "要说清缺的是哪条声明: "
                + d.disclosure());
    }

    @Test
    @DisplayName("⚪ 没选包：没有后处理链 = 没人采 noisetex ⇒ 不点名、不预热")
    void noPackIsNotAFallback() {
        NoiseSamplerSource.Decision d =
                NoiseSamplerSource.decide(Map.of(), Set.of(), false);
        assertEquals(NoiseSamplerSource.Source.NO_PACK, d.source());
        assertFalse(d.usesBuiltin());
        assertFalse(d.mustDisclose(), "无包时报 WARN 会把启动日志写成假故障（h34 刷屏同族）");
    }

    @Test
    @DisplayName("🔖 精确名声明（texture.noisetex）优先于别名，两种写法都认")
    void exactSamplerNameAlsoWorks() {
        Map<String, String> bindings = Map.of("noisetex", "tex/n.png", "noise", "tex/o.png");
        NoiseSamplerSource.Decision d = decide(bindings, Set.of("noisetex", "noise"));
        assertEquals("noisetex", d.declaredName(), "原名命中优先，别名只兜未声明的情况");
        assertEquals("tex/n.png", d.declaredPath());
    }

    @Test
    @DisplayName("🟢 真实 BSL：shaders.properties 的 texture.noise 经既有解析链必须能定到包图")
    void realBslDeclarationIsReachable() throws Exception {
        Assumptions.assumeTrue(Files.exists(BSL), "本机没有 BSL 库存包: " + BSL);
        try (ZipFile zip = new ZipFile(BSL.toFile())) {
            ZipEntry props = zip.getEntry("shaders/shaders.properties");
            Assumptions.assumeTrue(props != null, "BSL 里没有 shaders/shaders.properties");
            Map<String, String> directives = new LinkedHashMap<>();
            try (InputStream in = zip.getInputStream(props)) {
                for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                        .split("\n")) {
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        directives.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
                    }
                }
            }
            // 走**既有**解析链（不新造一条）：pipeline/model/PackTextureBindings
            Map<String, String> bindings = dev.vkdisp.pipeline.model.PackTextureBindings
                    .fromDirectives(directives).bindings();
            NoiseSamplerSource.Decision d = decide(bindings, Set.of("noise"));
            assertEquals("tex/noise.png", d.declaredPath(),
                    () -> "BSL 的声明行必须原样可达，directives=" + directives.keySet());
            assertEquals(NoiseSamplerSource.Source.PACK, d.source());

            // 图必须真在 zip 里，且尺寸就是包设计的那个尺度（IHDR 前 8 字节 = width/height）
            ZipEntry png = zip.getEntry("shaders/" + d.declaredPath());
            assertTrue(png != null, "声明的路径在 zip 内不存在: " + d.declaredPath());
            byte[] head = new byte[24];
            try (InputStream in = zip.getInputStream(png)) {
                int read = in.read(head);
                assertEquals(24, read, "PNG 头读不满");
            }
            int width = readIntBE(head, 16);
            int height = readIntBE(head, 20);
            assertEquals(512, width, "BSL noise.png 宽度（shaders/program/final.glsl:41 按 512 设计）");
            assertEquals(512, height);
        }
    }

    /**
     * 🔴🔴 h48y 回归红灯：<b>地形 gbuffer pass 也必须在开 pass 之前把自定义纹理备好</b>。
     *
     * <p>本轮 runClient 实测（真 Vulkan 后端，BSL 默认档）抓到：
     * <pre>
     *   18:34:26.239 [INFO ] gbuffer terrain targets ready     ← ensureTargets 起点
     *   18:34:26.318 [ERROR] builtin noisetex FAILED —— Close the existing render pass...
     *   18:34:26.356 [INFO ] custom texture loaded: noise 512x512   ← 纹理**才**上传完
     * </pre>
     * 顺序本身就是证据：地形 pass 消费 {@code noisetex} 时 {@code PackTextures.loaded} 还是空的，
     * 于是 {@code view()} 回落 {@code builtinNoiseView()}，而它在 render pass 打开期间新建
     * encoder ⇒ 原版抛异常 ⇒ noisetex 缺席（少绑一条，且**每帧**重试同一条失败路径，
     * 因为 {@code builtinNoise} 停在 null）。
     *
     * <p>为什么钉源码形状：判据是「{@code ensureReady()} 在地形 pass 的资源准备阶段被调过」，
     * 真伪只有 GPU 上跑得出来；这里能纯判定的是调用点**存在且在开 pass 之前**。
     * 钉住的后果是：下一个只读链侧 {@code FrameApi:1105} 的人会发现这里也有一处，
     * 而不是以为「链侧已经调过了」。
     */
    @Test
    @DisplayName("🔴🔴 地形 gbuffer pass 必须在开 pass 之前调 PackTextures.ensureReady（否则 noisetex 缺席）")
    void terrainPassPreparesCustomTexturesBeforeOpeningPass() throws Exception {
        Path pass = Path.of("src/main/java/dev/vkdisp/bridge/MrtTerrainPass.java");
        Assumptions.assumeTrue(Files.exists(pass), "工程文件缺失: " + pass);
        String src = Files.readString(pass);
        assertTrue(src.contains("PackTextures.ensureReady();"),
                "🔴 MrtTerrainPass 必须调 PackTextures.ensureReady() —— 自定义纹理的实际上传"
                        + "自己新建 encoder（h10 规则），而地形 pass **早于**链侧 FrameApi 的那一处调用；"
                        + "不在这儿备好，noisetex 会在 render pass 打开期间首建内置兑底并抛"
                        + " \"Close the existing render pass ...\"，导致它每帧缺席（h48y 实测）");

        // 钉住「在同一批资源准备里」：它必须落在 ensureTargets 这条开 pass 之前的路径上。
        int targets = src.indexOf("private static void ensureTargets(");
        assertTrue(targets > 0, "找不到 ensureTargets（若被重命名，请同步更新本守卫的锚点）");
        int cursor = src.indexOf("{", targets);
        int level = 0;
        int end = -1;
        for (int i = cursor; i >= 0 && i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                level++;
            } else if (c == '}') {
                level--;
                if (level == 0) {
                    end = i;
                    break;
                }
            }
        }
        assertTrue(end > targets, "无法解析 ensureTargets 的方法体边界");
        assertTrue(src.substring(targets, end).contains("PackTextures.ensureReady();"),
                "🔴 ensureReady() 必须落在 ensureTargets 里（开 pass 之前的资源准备阶段），"
                        + "而不是在链侧或更晚的调用点上");
    }

    @Test
    @DisplayName("🔴 回归红灯：消费侧必须走别名解析，不许再把内置图写成无条件答案")
    void consumerKeepsPackPriority() throws Exception {
        Path consumer = Path.of("src/main/java/dev/vkdisp/bridge/PackTextures.java");
        Assumptions.assumeTrue(Files.exists(consumer), "工程文件缺失: " + consumer);
        String src = Files.readString(consumer);
        assertTrue(src.contains("NoiseSamplerSource.declarationNames"),
                "view() 必须按「声明名（含 OF 别名）」查包图，否则 texture.noise 又取不到");
        assertFalse(src.contains("if (samplerName.equals(\"noisetex\"))"),
                "旧的无条件内置短路口（原 PackTextures.java:121）回来了 —— 这正是 GAP-025 本体");
    }

    private static int readIntBE(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
    }
}
