package dev.vkdisp.bridge;
/**
 * 【参考调研】GAP-014（原版无 3D / 数组纹理能力）的接线纪律守卫
 * 0. 合规核对（第 0 步闸门）：参考对象 = 原版 26.3
 *    {@code com.mojang.renderpearl.frontend.FrontendGpuDevice#verifyTextureCreationArgs}
 *    —— 已从 {@code minecraft-patched-26.3.0.41-beta.jar} **逐字节码核对**：
 *    {@code depthOrLayers > 1} 且非 cube 数组 ⇒ 无条件抛
 *    {@code UnsupportedOperationException("Array or 3D textures are not yet supported")}；
 *    cube 数组 {@code depthOrLayers > 6} ⇒ {@code "Array textures are not yet supported"}。
 *    该类位于 {@code frontend}（前后端共用层）⇒ <b>与渲染后端无关</b>。
 *    → 只调用公开 API 做事实核对，零源码搬运（Mojang EULA）。
 * 1. 官方/主实现：无（原版没有 3D 桩这个概念）。
 * 2. 备选：无。
 * 3. 我们的差异点：把「3D 桩建不出来**不许**炸掉整个 pass」变成构建期红灯。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 单测。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 3D 桩（{@code sampler3D} 的类型匹配视图）的失败纪律守卫。
 *
 * <p>🔖 <b>h33 实测的故障链</b>（本类每一条断言都对应其中一环）：
 * <pre>
 *   原版建不出 3D 纹理 ⇒ VolumeStubs.init() 抛 UnsupportedOperationException
 *     ⇒ 抛穿透 ensureTargets，把「创建 atlasSampler」那一步整个跳过
 *     ⇒ 但 colortex 已赋值 ⇒ 下一帧 early-return 认为「都建好了」⇒ 永不补建
 *     ⇒ atlasSampler 永远 null ⇒ 每帧 setUniform(name, view, null)
 *     ⇒ IllegalArgumentException: textureView and sampler must both or neither be null
 *     ⇒ 地形 MRT pass 每帧死一次（实测 2702 条 / 一次运行）
 * </pre>
 * ⇒ 一个 sampler 的能力缺失，被升级成了<b>整个 pass 不可用</b>。
 */
class VolumeStubCapabilityTest {

    private static final Path BRIDGE = Path.of("src/main/java/dev/vkdisp/bridge");
    private static final Path STUBS = BRIDGE.resolve("VolumeStubs.java");
    private static final Path PASS = BRIDGE.resolve("MrtTerrainPass.java");

    private static String readOrSkip(Path path) {
        Assumptions.assumeTrue(Files.exists(path), "工程文件缺失: " + path);
        try {
            return Files.readString(path);
        } catch (java.io.IOException e) {
            throw new AssertionError("读取失败: " + path, e);
        }
    }

    private static String bodyOf(String source, String signature) {
        int start = source.indexOf(signature);
        org.junit.jupiter.api.Assumptions.assumeTrue(start > 0, "找不到方法: " + signature);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open, i + 1);
                }
            }
        }
        throw new AssertionError("方法体括号不配对: " + signature);
    }

    @Test
    @DisplayName("🔖🔖 createTexture 必须被 try 包住（3D 纹理原版不支持，会抛）")
    void textureCreationIsGuarded() {
        String stubs = readOrSkip(STUBS);
        String ensure = bodyOf(stubs, "private static synchronized void ensure()");
        assertTrue(ensure.contains("try {"),
                "createTexture(depthOrLayers>1) 在原版上**无条件抛** UnsupportedOperationException，"
                        + "必须捕获 —— h33 实测它抛穿整个 pass 构造");
        assertTrue(ensure.contains("catch (UnsupportedOperationException"),
                "必须单独捕获 UnsupportedOperationException（= 能力缺失这一确定事实）");
        assertTrue(ensure.contains("noteUnsupported"),
                "捕获后必须走一条可见的报错路径（T11：降级必须可见）");
    }

    @Test
    @DisplayName("🔖🔖 view() 建不出来时必须返回 null，不得抛")
    void viewReturnsNullInsteadOfThrowing() {
        String stubs = readOrSkip(STUBS);
        String view = bodyOf(stubs, "static GpuTextureView view()");
        assertFalse(view.contains("throw"),
                "🔴 view() 不得抛：在绑定点抛会把「一个 sampler 的能力缺失」"
                        + "升级成「整个 pass 建不起来」，这正是 h33 的现场。"
                        + "绑定点已有「view==null ⇒ ERROR + 跳过该条绑定」的既定分支");
        assertTrue(view.contains("return null"),
                "必须返回 null，让调用点走既定的响亮失败路径");
    }

    @Test
    @DisplayName("🔖🔖 能力缺失只报一次，且不再每帧重试")
    void unsupportedIsNotedOnce() {
        String stubs = readOrSkip(STUBS);
        String ensure = bodyOf(stubs, "private static synchronized void ensure()");
        assertTrue(stubs.contains("private static boolean unsupportedNoted"),
                "必须有一个「已确认不支持」的静态标志");
        assertTrue(ensure.contains("if (unsupportedNoted)"),
                "确认不支持后必须**不再重试** —— 每帧重试就是每帧抛");
        String note = bodyOf(stubs, "private static void noteUnsupported(");
        assertTrue(note.contains("unsupportedNoted = true"),
                "探测失败那一次就要把标志置上（不再每帧重试）");
    }

    @Test
    @DisplayName("🔖 能力缺失必须**按需求**报错，不能一探测到就吵")
    void unsupportedIsReportedOnDemandOnly() {
        String stubs = readOrSkip(STUBS);
        String view = bodyOf(stubs, "static GpuTextureView view()");
        assertTrue(view.contains("reportUnsupportedOnce"),
                "🔴 h33 实测：init() 无条件调 ⇒ 哪怕包的地形片元根本没声明 sampler3D"
                        + "（BSL 的 3 个 3D 采样器在别的阶段用），也会打出这条 ERROR ——"
                        + "那是在报一个当前配置下并不存在的故障");
        String note = bodyOf(stubs, "private static void noteUnsupported(");
        assertFalse(note.contains("LOGGER"),
                "「记录」阶段不打日志：记录与报错必须分开（记录≠报错）");
        assertTrue(stubs.contains("unsupportedCause"),
                "必须保留异常原文进日志（X9 不猜：原文必须能查到）");
        String report = bodyOf(stubs, "private static synchronized void reportUnsupportedOnce()");
        assertTrue(report.contains("unsupportedReported"),
                "报错本身也要只打一次");
    }

    @Test
    @DisplayName("🔖🔖 atlasSampler 的创建不得被 colortex 的状态门控（级联的根）")
    void atlasSamplerIsNotGatedOnColortex() {
        String pass = readOrSkip(PASS);
        String ensureTargets = bodyOf(pass, "private static void ensureTargets(RenderTarget main)");
        assertTrue(ensureTargets.contains("ensureColortex("),
                "colortex 必须拆成独立的 ensure（尺寸/resize 与资源创建分开）");
        assertTrue(ensureTargets.contains("ensureAtlasSampler()"),
                "🔴 ensureTargets 必须**无条件**调用 ensureAtlasSampler —— "
                        + "h33 的级联正是因为它被放在了 colortex==null 分支里");
        assertTrue(ensureTargets.contains("ensureShadowStubs()"),
                "阴影桩同理：必须每帧兜底，不能只在首次创建路径里");
        assertTrue(ensureTargets.contains("VolumeStubs.init()"),
                "3D 桩同理：必须在建 pass 之前调用（那时才能建 encoder）");
    }

    @Test
    @DisplayName("🔖🔖 采样器创建必须幂等（每帧兜底，不是每帧新建）")
    void atlasSamplerCreationIsIdempotent() {
        String pass = readOrSkip(PASS);
        String ensure = bodyOf(pass, "private static void ensureAtlasSampler()");
        assertTrue(ensure.contains("if (atlasSampler == null)"),
                "🔴 每帧兜底的前提是**幂等**：已是 sampler 就直接返回，"
                        + "否则每帧新建 = 每帧泄漏一个 GPU 对象");
    }

    @Test
    @DisplayName("🔖🔖 采样器不可用时必须在绘制前停下并说明原因")
    void missingSamplerStopsBeforeDraw() {
        String pass = readOrSkip(PASS);
        String draw = bodyOf(pass, "private static void drawTerrain()");
        assertTrue(draw.contains("atlasSamplerReady()"),
                "🔴 绘制前必须判采样器可用 —— 否则症状是看不出根因的"
                        + " 'textureView and sampler must both or neither be null'（h33 实测 2702 条）");
        String ready = bodyOf(pass, "private static boolean atlasSamplerReady()");
        assertTrue(ready.contains("ERROR") || ready.contains("LOGGER.error"),
                "不可用时必须**报错并说明原因**（X11：禁止静默降级成什么都不做）");
    }

    @Test
    @DisplayName("🔖 重复的渲染期错误必须节流（h33 实测一次运行刷 2702 行）")
    void repeatedErrorsAreThrottled() {
        String pass = readOrSkip(PASS);
        assertTrue(pass.contains("shadowStubsWarned"),
                "每帧兜底之后，A/B 警告必须自己节流");
        assertTrue(pass.contains("atlasSamplerMissingNoted"),
                "「采样器缺失」同样只报一次");
        String api = readOrSkip(BRIDGE.resolve("TerrainPipelineApi.java"));
        assertTrue(api.contains("PACK_TERRAIN_NULL_VIEW_WARNED"),
                "「sampler 无类型匹配视图」每帧每 sampler 都有分支 ⇒ 必须节流"
                        + "（M-01 埋点过密把热路径变 I/O 瓶颈的同一课）");
        assertTrue(api.contains("NULL_VIEW_LOG_EVERY"),
                "节流必须是「首条 + 每 N 帧」，不能是彻底静音");
    }

    @Test
    @DisplayName("🔖 能力缺失的报错必须写明是原版限制、且不能喂 2D 图集冒充")
    void unsupportedMessageIsHonest() {
        String stubs = readOrSkip(STUBS);
        String note = bodyOf(stubs, "private static synchronized void reportUnsupportedOnce()");
        assertTrue(note.contains("GAP-014"),
                "必须登记为缺口（07 T12：先登记再自行补充）");
        assertTrue(note.contains("不支持 3D"),
                "必须明说这是原版能力缺失，不能让取证者误判成渲染故障");
        assertFalse(note.contains("LOGGER.warn"),
                "能力缺失是 ERROR 不是 WARN —— 它意味着依赖该能力的包特性**不成立**");
        assertTrue(note.contains("unsupportedCause"),
                "异常原文必须作为参数进日志（X9：不猜 ⇒ 原文要能查到）");
    }

    @Test
    @DisplayName("🔖 诊断清屏色默认仍应是生产语义（h32 轮三的成果不得被回退）")
    void slotClearStaysNeutralByDefault() {
        String config = readOrSkip(Path.of("src/main/java/dev/vkdisp/VkDispConfig.java"));
        assertTrue(config.contains("define(\"mrt.slotDiagnosticClear\", false)"),
                "高对比逐槽诊断色默认必须关 —— 它会造成假色天空（h27b 实测）");
        assertTrue(config.contains("define(\"pack.capabilityGate\", false)"),
                "能力门控默认必须关 —— 它会改变包语义（支柱①：改变语义由用户显式开启）");
    }
}
