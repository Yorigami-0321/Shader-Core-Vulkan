package dev.vkdisp.bridge;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 🔴 GAP-030 的接线守卫（读源码正文的口径同 {@code DepthGlProxyTest} / {@code ShadowSamplerAliasingTest}）。
 *
 * <p>🔖 为什么这一族必须用「源码文本」钉而不是只跑单测：本条缺陷的全部危险性发生在
 * <b>资源重载期的管线装配</b>与<b>每帧的 draw 绑定</b>上，两者都没有可离线运行的等价物 ——
 * 「管线声明了顶点绑定而 draw 没绑」在 STRICT_VALIDATION 下抛的是每帧一次，
 * 「适配层被静默用着」在日志里看起来完全正常。改一侧不改另一侧 = 单测在测空气。
 */
class PostVertexWiringTest {

    private static final Path PIPELINE_API = Path.of("src/main/java/dev/vkdisp/bridge/PipelineApi.java");
    private static final Path FRAME_API = Path.of("src/main/java/dev/vkdisp/bridge/FrameApi.java");
    private static final Path VIRTUAL_PACK = Path.of("src/main/java/dev/vkdisp/VkDispVirtualPack.java");
    private static final Path CHAIN = Path.of("src/main/java/dev/vkdisp/pack/PackPostChain.java");
    private static final Path CONFIG = Path.of("src/main/java/dev/vkdisp/VkDispConfig.java");
    private static final Path SWITCH =
            Path.of("src/main/java/dev/vkdisp/pack/PackPostVertexSwitch.java");

    private static String readOrSkip(Path path) {
        Assumptions.assumeTrue(Files.exists(path), "工程文件缺失: " + path);
        try {
            return Files.readString(path);
        } catch (java.io.IOException e) {
            throw new AssertionError("读取失败: " + path, e);
        }
    }

    @Test
    @DisplayName("🔴 post 管线必须声明顶点绑定，且元素名单取自链接器（不许两处各列一张表）")
    void postPipelinesDeclareTheFrozenVertexFormat() {
        String api = readOrSkip(PIPELINE_API);
        assertTrue(api.contains(".withVertexBinding(0, POST_VERTEX_FORMAT)"),
                "链槽管线没声明顶点绑定 ⇒ 包的 post VSH 按名读 Position/UV0 时，"
                        + "引擎直接抛 'does not have a matching vertex buffer element'（PipelineBuilder:139）");
        assertTrue(api.contains("PostVertexLinker.servableAttributes()"),
                () -> "格式元素名必须是链接器那份冻结名单；实际读的是: " + api.lines()
                        .filter(l -> l.contains("addAttribute") || l.contains("servableAttributes"))
                        .toList());
        assertTrue(api.contains("GpuFormat.RGBA32_FLOAT"),
                "每个元素都要够 4 个浮点分量（包声明 vec4 Position 时引擎 :167 要求分量数不少于它）");
    }

    @Test
    @DisplayName("🔴 链每一级 draw 前必须绑全屏顶点缓冲（声明了 format 却不绑 = 每帧抛）")
    void everyPostPassBindsTheFullscreenBuffer() {
        String frame = readOrSkip(FRAME_API);
        String runPostPass = frame.substring(frame.indexOf("private static void runPostPass("));
        assertTrue(runPostPass.contains("pass.setVertexBuffer(0, postVertexBuffer().slice())"),
                () -> "FrontendRenderPass:537-547：管线声明了顶点格式就必须绑缓冲；runPostPass 里没绑");
        assertTrue(runPostPass.indexOf("setVertexBuffer(0, postVertexBuffer()")
                        < runPostPass.indexOf("pass.draw(3, 1, 0, 0)"),
                "绑定必须在 draw 之前");
        assertTrue(frame.contains("GpuBuffer.USAGE_VERTEX"),
                () -> "缓冲必须带 USAGE_VERTEX，否则 setVertexBuffer 直接抛（:229-231）");
    }

    @Test
    @DisplayName("🔴 全屏三角形的三顶点必须逐字复刻适配层已验证的屏幕 uv 取向（p416）")
    void fullscreenVerticesKeepTheVerifiedUvOrientation() {
        String frame = readOrSkip(FRAME_API);
        // 适配层由 gl_VertexIndex 生成：v0(-1,-1)/uv(0,0)、v1(+3,-1)/uv(2,0)、v2(-1,+3)/uv(0,2)
        assertTrue(frame.contains("{-1.0F, -1.0F, 0.0F, 0.0F}"), () -> "v0 变了：\n" + frame);
        assertTrue(frame.contains("{3.0F, -1.0F, 2.0F, 0.0F}"), "v1 变了 = 屏幕 uv 取向漂移（p416 那一族）");
        assertTrue(frame.contains("{-1.0F, 3.0F, 0.0F, 2.0F}"), "v2 变了 = 同上");
    }

    @Test
    @DisplayName("🔴 包顶点源必须逐槽过驱动编译才落地；回落必须整槽且打 ERROR")
    void packVertexSourceIsDriverValidatedBeforeInstalling() {
        String pack = readOrSkip(VIRTUAL_PACK);
        int start = pack.indexOf("static String[] postVerticesFrom(");
        assertTrue(start >= 0, "postVerticesFrom 必须还在（链槽顶点源的唯一出口）");
        int end = pack.indexOf("\n        /**", start);
        final String method = pack.substring(start, end < 0 ? pack.length() : end);
        assertTrue(method.contains("packVertexCompiles("),
                () -> "包顶点源必须逐槽过一次驱动编译 —— 静态对齐挡不住「包自己用了未声明的名字」那一族，"
                        + "把没编过的源交给 required 管线的代价是整次资源重载失败。实际: " + method);
        assertTrue(method.contains("adapterVertexSource(pass)"),
                "编译失败必须整槽回落适配层（片元/顶点同生共死，不许半套）");
        assertTrue(pack.contains("ShaderCompileApi.compileStage"),
                "回落判据走的必须是原版那条 shaderc 通道（bridge/ShaderCompileApi），不是自研猜测");
        int zeros = pack.indexOf("generated.worldVectorZeros()");
        assertTrue(zeros >= 0,
                "适配层的零值必须真打进日志 —— GAP-030 之前它只写在类注释里、从没接线（X11）");
        assertTrue(pack.substring(zeros, Math.min(zeros + 320, pack.length())).contains("LOGGER.error"),
                () -> "世界向量零值占位走的是 ERROR 而不是 WARN；实际片段: "
                        + pack.substring(zeros, Math.min(zeros + 320, pack.length())));
    }

    @Test
    @DisplayName("🔖 链的顶点槽不许留「有包顶点源却用适配层」的半套状态")
    void chainCarriesTheVertexSourceAndReportsWhichOneRan() {
        String chain = readOrSkip(CHAIN);
        assertTrue(chain.contains("hasPackVertexSource()"),
                () -> "Pass 必须能被读出没接到包顶点程序；实际字段: " + chain.lines()
                        .filter(l -> l.contains("vertexSource")).toList());
        assertTrue(chain.contains("hasPackVertexSource() ? \"pack\" : \"adapter\""),
                "每个槽的顶点来源必须逐槽进自报行（否则「包顶点没接上」读起来像「包没有顶点程序」）");
        assertTrue(chain.contains("[GAP-030] post 顶点程序来源"),
                "必须有一行可数的 pack=/adapter= 汇总（X46 的可数日志行判据）");
    }

    @Test
    @DisplayName("🔖 开关字段名与反射常量必须对得上（h33：把配置键名当字段名 ⇒ 开关恒默认值）")
    void switchFieldNamesMatchTheReflectionConstants() {
        String config = readOrSkip(CONFIG);
        String sw = readOrSkip(SWITCH);
        assertTrue(config.contains("PACK_POST_VERTEX_PROGRAM"),
                "VkDispConfig 里必须真有那个字段（键名 ≠ 字段名时反射只会静默吃掉）");
        assertTrue(config.contains(".define(\"pack.postVertexProgram\", true)"),
                "默认必须是开：关着跑的是世界向量零值档 = 已定案的镜像虚影根因");
        assertTrue(sw.contains("FIELD_NAME = \"PACK_POST_VERTEX_PROGRAM\""),
                () -> "读取侧反射的字段名必须与配置字段逐字一致；实际: " + sw.lines()
                        .filter(l -> l.contains("FIELD_NAME") || l.contains("CONFIG_KEY")).toList());
        assertTrue(sw.contains("CONFIG_KEY = \"pack.postVertexProgram\""),
                "配置键名与字段名必须是两个分开的常量");
    }

    @Test
    @DisplayName("🔴 适配层不得再被当作链顶点的主路（它是兜底，注释与代码都得这么说）")
    void adapterIsDeclaredFallbackNotMainPath() {
        String adapter = readOrSkip(Path.of(
                "src/main/java/dev/vkdisp/glsl/translate/PackPostVertexAdapter.java"));
        assertTrue(adapter.contains("GAP-030"), "适配层的身份必须在类注释里说清（兜底，不是主路）");
        assertFalse(adapter.contains("每条生成一行 WARN 诊断登记「该 varying 本轮不成立」"),
                () -> "这句是当年没接线的自述（zeroSupplied 只有单测读过）—— 留着就是文档说假话（X9）");
    }
}
