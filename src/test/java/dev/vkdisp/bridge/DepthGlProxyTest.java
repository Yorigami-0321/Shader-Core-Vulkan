package dev.vkdisp.bridge;
/**
 * 【参考调研】GAP-022 ①：GL 口径深度代理的纯判据单测（换算 / 绑哪张 / 格式选择 / 接线守卫）
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓库 {@code docs/13-GAP-REGISTRY.md} GAP-022 的
 *    「由上面推出的正确换算」行（GL 与引擎两套透视式的逐分量代数关系），以及
 *    {@code docs/07-CONSTRAINTS.md} · {@code X11}（禁止静默）· {@code X42}（管线与附件同源）。
 *    原版 API 行号取自 {@code minecraft-patched-26.3.0.51-beta-sources.jar}（见被测类注释）。
 *    全部为本仓库自有规范 + 原版公开 API 事实，无外部代码搬运。
 * 1. 官方/主实现：无（纯函数 + 源码文本守卫）。
 * 2. 备选：跑客户端看画面 —— <b>不作为本类的证明</b>：那一半只能证明「它跑起来了」，
 *    证不了「1−z 这套换算对同一组 (n,f) 恒等」，而那才是这条修法的前提（X9）。
 * 3. 我们的差异点：两套透视式在这里<b>各自独立实现一遍</b>（不复用被测实现的任何算式），
 *    期望值来自 GAP-022 登记的推导；守卫部分沿用 {@code BridgeNullableContractTest}
 *    与 {@code GenerationTimeSwitchInventoryTest} 的源码扫描做法。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 单测（不进渲染路径）。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.vkdisp.bridge.DepthGlProxy.DepthSource;

/**
 * GAP-022 ① 里<b>可离线证明</b>的那一半。
 *
 * <p>🔖 <b>为什么这套必须能离线证</b>：本轮修法的主张是「一次逐像素取反就是<b>全精度正确</b>的
 * 深度约定换算」—— 那是一句代数命题，不需要 GPU，也不需要有人盯屏幕。
 * 反过来，<b>画面到底对不对</b>它一句也证不了（那要等矩阵那一半接上，
 * 见 {@code VkDispConfig#MRT_DEPTH_GL_PROXY} 的注释）。
 */
class DepthGlProxyTest {

    private static final float EPS = 1.0E-6F;

    private static final Path FRAME_API = Path.of("src/main/java/dev/vkdisp/bridge/FrameApi.java");
    private static final Path PIPELINE_API = Path.of("src/main/java/dev/vkdisp/bridge/PipelineApi.java");
    private static final Path CONFIG = Path.of("src/main/java/dev/vkdisp/VkDispConfig.java");
    private static final Path FRAGMENT =
            Path.of("src/main/resources/assets/vkdisp/shaders/depth_gl_flip.fsh");

    private static String readOrSkip(Path path) {
        Assumptions.assumeTrue(Files.exists(path), "工程文件缺失: " + path);
        try {
            return Files.readString(path);
        } catch (java.io.IOException e) {
            throw new AssertionError("读取失败: " + path, e);
        }
    }

    // ── 换算本身 ────────────────────────────────────────────────────────────────

    /** GAP-022 登记的引擎口径反向 Z（窗口 [0,1]：近 = 1.0、远/天空 = 0.0）。 */
    private static float engineDepth(float near, float far, float viewDistance) {
        return (near / (far - near)) * (far / viewDistance - 1.0F);
    }

    /** GAP-022 登记的 OpenGL 口径（窗口 [0,1]：近 = 0.0、远/天空 = 1.0）。 */
    private static float glDepth(float near, float far, float viewDistance) {
        return (far / (far - near)) * (1.0F - near / viewDistance);
    }

    @Test
    @DisplayName("🔴 端点：引擎 0.0(天空) → GL 1.0，引擎 1.0(近) → GL 0.0")
    void endpointsAreTheSkyAndNearPlane() {
        assertEquals(1.0F, DepthGlProxy.toGlDepth(0.0F), EPS, "天空：反向 Z 的 0.0 必须变成 GL 的 1.0");
        assertEquals(0.0F, DepthGlProxy.toGlDepth(1.0F), EPS, "近平面：反向 Z 的 1.0 必须变成 GL 的 0.0");
        assertEquals(0.75F, DepthGlProxy.toGlDepth(0.25F), EPS);
        assertEquals(0.5F, DepthGlProxy.toGlDepth(0.5F), EPS, "自反点只有一个，它必须还落在中间");
    }

    @Test
    @DisplayName("🔴 不是近似：任意 (n,f,d) 下 z_gl == 1 − z_en（GAP-022 的恒等式）")
    void flipIsExactForEveryDistanceUnderEveryProjection() {
        float[][] nearFar = {{0.05F, 1000.0F}, {0.1F, 500.0F}, {1.0F, 4096.0F}, {0.001F, 64.0F}};
        int checked = 0;
        for (float[] nf : nearFar) {
            float near = nf[0];
            float far = nf[1];
            for (double t = 0.0; t <= 1.0001; t += 0.05) {
                float d = near + (float) t * (far - near);
                float flipped = DepthGlProxy.toGlDepth(engineDepth(near, far, d));
                assertEquals(glDepth(near, far, d), flipped, 1.0E-4F,
                        () -> "n=" + near + " f=" + far + " d=" + d
                                + " ⇒ 一次取反不等于 GL 口径，说明 GAP-022 的恒等式用错了窗口约定");
                checked++;
            }
        }
        // 🔖 网格计数本身也要钉住：4 组 (n,f) × 21 个 d。少一档时本守卫会「全绿但少测一半」，
        //   而那种绿比红更贵（QD-04 的「结论写下来但没东西让它保持正确」同族）。
        assertEquals(84, checked, "扫描网格被改动过 —— 先确认档数，再看结论是否还成立");
    }

    @Test
    @DisplayName("🔖 换算必须是对合（翻两次回原图）：代理是原深度的无损替身，不丢梯度信息")
    void flipIsItsOwnInverse() {
        for (float z = 0.0F; z <= 1.0F; z += 0.01F) {
            assertEquals(z, DepthGlProxy.toGlDepth(DepthGlProxy.toGlDepth(z)), 1.0E-5F,
                    "翻两次回不去 ⇒ 这张代理图不是原深度的双射替身，"
                            + "包侧 AO/边缘检测会在某一段读到编出来的台阶");
        }
    }

    // ── 绑定决策 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 depthtex* 决策表：开关+代理才给代理；缺席给反向 Z；非深度名一律显式占位")
    void bindingDecisionTable() {
        // 开关开 + 代理在 ⇒ 代理（本轮唯一的目标形态）
        assertSame(DepthSource.PROXY, DepthGlProxy.chooseDepthSource("depthtex0", true, true, true));
        assertSame(DepthSource.PROXY, DepthGlProxy.chooseDepthSource("depthtex2", true, true, true));
        // 开关关 ⇒ 保持今天的现状（反向 Z 原图），不是「什么都不绑」
        assertSame(DepthSource.ENGINE_REVERSED_Z,
                DepthGlProxy.chooseDepthSource("depthtex0", false, false, true));
        assertSame(DepthSource.ENGINE_REVERSED_Z,
                DepthGlProxy.chooseDepthSource("depthtex1", false, true, true),
                "代理在场但开关关着 ⇒ 不得越过开关去用它");
        // 开关开 + 代理缺席 ⇒ 降级到反向 Z；这一条调用点必须配一次 WARN（X11，见下一个测试）
        assertSame(DepthSource.ENGINE_REVERSED_Z,
                DepthGlProxy.chooseDepthSource("depthtex0", true, false, true));
        // 连引擎深度都没有 ⇒ 显式占位，不许 null（h33 那一族的口径）
        assertSame(DepthSource.PLACEHOLDER,
                DepthGlProxy.chooseDepthSource("depthtex0", true, false, false));
        assertSame(DepthSource.PLACEHOLDER,
                DepthGlProxy.chooseDepthSource("depthtex0", false, false, false));
        // 别人的名字不许被代理顶走
        assertSame(DepthSource.PLACEHOLDER, DepthGlProxy.chooseDepthSource("shadowtex0", true, true, true));
        assertSame(DepthSource.PLACEHOLDER, DepthGlProxy.chooseDepthSource("colortex0", true, true, true));
    }

    @Test
    @DisplayName("🔖 depthtex* 家族名只看前缀；sampler3D 与 shadowtex 不算")
    void depthFamilyNames() {
        assertTrue(DepthGlProxy.isDepthTextureName("depthtex0"));
        assertTrue(DepthGlProxy.isDepthTextureName("depthtex3"));
        assertFalse(DepthGlProxy.isDepthTextureName("vxDepthTexOpaque"),
                "sampler3D 家族（GAP-014）不许被当成 depthtex* —— 它要的是 3D 视图，"
                        + "给一张 2D 浮点图会把失败挪到另一处更难读的位置");
        assertFalse(DepthGlProxy.isDepthTextureName("shadowtex0"));
        assertFalse(DepthGlProxy.isDepthTextureName("noisetex"));
    }

    @Test
    @DisplayName("🔴 翻转 pass 的四个前提缺任意一个都不跑（且必须由自报指名是哪一个）")
    void flipPassRequiresAllFourPreconditions() {
        assertTrue(DepthGlProxy.shouldRenderPass(true, true, true, true));
        assertFalse(DepthGlProxy.shouldRenderPass(false, true, true, true), "开关关");
        assertFalse(DepthGlProxy.shouldRenderPass(true, false, true, true), "代理纹理没建出来");
        assertFalse(DepthGlProxy.shouldRenderPass(true, true, false, true), "gbuffer 深度为 null");
        assertFalse(DepthGlProxy.shouldRenderPass(true, true, true, false), "管线没注册/没编译");
    }

    // ── 格式选择 ────────────────────────────────────────────────────────────────

    /**
     * 🔖 {@code GpuFormat} 里<b>真实存在</b>的浮点格式名清单（逐字抄自
     * {@code minecraft-patched-26.3.0.51-beta-sources.jar} 的
     * {@code com/mojang/renderpearl/api/GpuFormat.java} 枚举体）。
     *
     * <p>为什么测试里要抄一遍清单而不是 {@code GpuFormat.valueOf(...)}：
     * <b>测试源集没有 renderpearl 类路径</b>（{@code build.gradle} 只往
     * {@code testImplementation} 加了 junit + joml；本轮实测 —— 测试里
     * {@code import com.mojang.renderpearl.api.GpuFormat} 报
     * 「package does not exist」，而 {@code render/OfUniformManagerTest} 那批只用 joml 类型）。
     * ⇒ 被测面必须是纯 Java 视图（这里 = {@link DepthGlProxy#proxyFormatName()}），
     *   这份清单就是那条边界的代价，写下来而不是藏起来。
     */
    private static final Set<String> GPU_FORMAT_FLOAT_NAMES = Set.of(
            "R16_FLOAT", "RG16_FLOAT", "RGB16_FLOAT", "RGBA16_FLOAT",
            "R32_FLOAT", "RG32_FLOAT", "RGB32_FLOAT", "RGBA32_FLOAT",
            "RG11B10_FLOAT");

    @Test
    @DisplayName("🔴 格式 = 单通道 32 位浮点，且不能是深度 aspect 的格式（AO/边缘检测吃梯度）")
    void formatIsSingleChannel32BitFloat() {
        String name = DepthGlProxy.proxyFormatName();
        assertEquals("R32_FLOAT", name,
                "代理喂的是深度<b>梯度</b>：1/255 的台阶足以把 AO/边缘检测毁掉 ⇒ 必须 32 位浮点");
        assertTrue(GPU_FORMAT_FLOAT_NAMES.contains(name),
                "格式名必须在 GpuFormat 枚举里真实存在（拼错 = 运行期 valueOf 抛在注册路径上，"
                        + "而注册失败的样子是「整次资源重载失败」—— 比这里红一格难读得多）");
        assertEquals("R", name.substring(0, 1),
                "首字母 = 通道布局：R 才是单通道；写成 RG/RGBA 不会报错，只是白烧 2~4 倍带宽与显存");
        assertTrue(name.endsWith("_FLOAT"), "整型/UNORM 格式给不出 z 的连续梯度");
        assertFalse(name.startsWith("D"),
                "D32_FLOAT 也是「单通道 32 位浮点」，但它有 depth aspect ⇒"
                        + " VulkanConst.textureUsageToVk 不给它 COLOR_ATTACHMENT_BIT，挂成颜色附件是 UB；"
                        + " 本轮要的恰恰是「一张普通浮点图」");
    }

    @Test
    @DisplayName("🔴 usage 必须同时给「当附件写」与「当纹理读」两位（一张图两用）")
    void usageCarriesAttachmentAndSamplingBits() {
        // 位值口径 = com.mojang.renderpearl.api.textures.GpuTexture 的 USAGE_*（1/2/4/8），
        // 测试侧引用不到那个类 ⇒ 这里按位断言，注释把位名写全（改成了别的值会在这里红）。
        int usage = DepthGlProxy.proxyUsage();
        assertEquals(15, usage, "COPY_DST(1)|COPY_SRC(2)|TEXTURE_BINDING(4)|RENDER_ATTACHMENT(8)"
                + " —— 与 ColortexPool.java:29-30 的口径一致（原版 RenderTarget 同值）");
        assertEquals(8, usage & 8, "缺 RENDER_ATTACHMENT(8) ⇒ VulkanConst.textureUsageToVk "
                + "不给 COLOR_ATTACHMENT_BIT ⇒ 本 pass 写不进去（挂附件是未定义行为）");
        assertEquals(4, usage & 4, "缺 TEXTURE_BINDING(4) ⇒ 不给 SAMPLED_BIT ⇒ 链里 depthtex* 采不到它");
    }

    // ── 接线守卫（「实现了但没接上」= 本项目最贵的失败形态，QD-02 / h33 各一例） ────

    @Test
    @DisplayName("🔖 管线格式与纹理格式必须同源一个真源（FrontendRenderPass.java:119-121 逐附件对账）")
    void pipelineFormatComesFromTheSingleSource() {
        String pipeline = readOrSkip(PIPELINE_API);
        int start = pipeline.indexOf("public static void registerDepthProxyPipeline");
        assertTrue(start > 0, "找不到深度代理管线的注册体 —— 本轮的修法根本没接线");
        int end = pipeline.indexOf("\n    }\n", start);
        assertTrue(end > start, "注册体结尾定位失败 —— 签名/缩进变了，先修守卫再谈结论");
        String body = pipeline.substring(start, end);
        assertTrue(body.contains("DepthGlProxy.proxyFormat()"),
                "注册体必须从唯一真源取格式；抄第二遍字面量 = 改一处漏一处，"
                        + "而那两处一旦不一致是 setPipeline 当场抛（X42 同族）");
        assertFalse(body.contains("GpuFormat.R32"), "同上：注册体里不许出现格式字面量");
        assertTrue(body.contains("ColorTargetState.WRITE_RED"),
                "单通道图的写掩码就该只有 R —— 让「这是一张单通道图」出现在源码里而不只在注释里");
    }

    @Test
    @DisplayName("🔖 链的 depthtex* 分支必须走代理决策；翻转 pass 必须在 drawPostChain 里被调到")
    void chainResolverGoesThroughTheProxyDecision() {
        String frame = readOrSkip(FRAME_API);
        int start = frame.indexOf("if (name.startsWith(\"depthtex\"))");
        assertTrue(start > 0, "FrameApi 里没有 depthtex* 分支了 —— 解析器被改写，先确认新形态");
        String branch = frame.substring(start, frame.indexOf("}", frame.indexOf("return", start)));
        assertTrue(branch.contains("DepthGlProxy.chainDepthView"),
                "这一支必须经过代理决策；直接绑 MrtTerrainPass.depthView() 就是本轮要修的那一行");
        assertTrue(frame.contains("refreshDepthGlProxy(main, label)"),
                "翻转 pass 必须在 drawPostChain 里被调到 —— 链外没有「地形之后、第一级之前」这个位置；"
                        + "不接线的结果是开关恒无效（QD-02 那一族）。"
                        + "2026-10-10 真机根修：它用**自己的 encoder**（同 encoder「刚当过附件就采样」"
                        + "在 NVIDIA 读 0），位置要求不变。");
    }

    @Test
    @DisplayName("🔖 开关存在、默认关、名字就是 mrt.depthGlProxy")
    void switchIsDeclaredOffAndNamedAsRegistered() {
        String config = readOrSkip(CONFIG);
        assertTrue(config.contains("define(\"mrt.depthGlProxy\", false)"),
                "默认必须是 false：矩阵那一半（gbufferProjection / gbufferProjectionInverse）"
                        + "接上之前，只翻深度 = 半真半假，不能默认生效");
        assertTrue(config.contains("MRT_DEPTH_GL_PROXY"),
                "开关字段名与消费点必须对得上（h33：把键名当字段名去反射 ⇒ 开关恒默认值）");
        String proxy = readOrSkip(Path.of("src/main/java/dev/vkdisp/bridge/DepthGlProxy.java"));
        assertTrue(proxy.contains("VkDispConfig.MRT_DEPTH_GL_PROXY.get()"),
                "消费点读的必须是同一个字段，不是另一个同名开关");
    }

    @Test
    @DisplayName("🔖 片元里那行翻转必须与 Java 镜像同式（改一侧不改另一侧 = 单测在测空气）")
    void fragmentMatchesTheJavaMirror() {
        String fsh = readOrSkip(FRAGMENT);
        assertTrue(fsh.contains("1.0 - engineDepth"),
                "GLSL 侧的换算式必须是 1 − z_engine；Java 镜像 DepthGlProxy#toGlDepth 就是它");
        assertTrue(fsh.contains("fragColor = vec4(1.0 - engineDepth"),
                "值必须落在 .r（R32F 只有 R 分量，写 .g/.b 是空转）");
        assertTrue(fsh.contains("texture(InSampler, vUv)"),
                "必须是原始 vUv：翻 v 会让代理与原深度视图错行，链里同一个 texCoord 采 color 与 depth"
                        + " 就不再是同一像素（原地顶替 depthtex0 的前提没了）");
    }
}
