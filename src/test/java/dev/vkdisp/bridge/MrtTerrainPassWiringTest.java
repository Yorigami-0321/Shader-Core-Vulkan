package dev.vkdisp.bridge;
/**
 * 【参考调研】GAP-003 方案 A 接线纪律的单测 / 只读工程自身源码
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓库 `src/main/java/dev/vkdisp/bridge/` 与
 *    `pipeline/model/TerrainDerivedPlan`（MIT，自有代码）+ `docs/04-SPEC.md` §5.0。
 *    → 可并入本项目（MIT）：本文件只做源码文本断言，不引用任何外部代码。
 * 1. 官方/主实现：无（纯文本断言）。
 * 2. 备选：无。
 * 3. 我们的差异点：把「方案 A 的接线纪律」变成构建期红灯 ——
 *    其中两条是**静默失效**（不出错、只是没生效）：① 帧图 pass 被剔除；
 *    ② M-01 的活动标记漏清 ⇒ 原版 pass 拿到多附件管线。
 * 4. 许可证核对：本项目 MIT。
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
 * 方案 A（地形进多附件 pass）的接线守卫。
 *
 * <p>🔖 这些断言守的都是**静默失效**：代码能编译、单测能过、游戏不报错，
 * 但功能没生效 —— 本项目头号坑（T10 / X11）。
 */
class MrtTerrainPassWiringTest {

    private static final Path BRIDGE = Path.of("src/main/java/dev/vkdisp/bridge");
    private static final Path PASS = BRIDGE.resolve("MrtTerrainPass.java");
    private static final Path API = BRIDGE.resolve("TerrainPipelineApi.java");
    private static final Path CONFIG = Path.of("src/main/java/dev/vkdisp/VkDispConfig.java");

    private static String readOrSkip(Path path) {
        Assumptions.assumeTrue(Files.exists(path), "工程文件缺失: " + path);
        try {
            return Files.readString(path);
        } catch (java.io.IOException e) {
            throw new AssertionError("读取失败: " + path, e);
        }
    }

    /** 只统计非注释行的字面量出现次数（javadoc 的【参考调研】会引用代码字样）。 */
    private static int countCode(String text, String literal) {
        int count = 0;
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                continue;
            }
            count += line.split(java.util.regex.Pattern.quote(literal), -1).length - 1;
        }
        return count;
    }

    @Test
    @DisplayName("🔖 帧图 pass 必须 disableCulling —— 否则被剔除且不报错（静默失效头号来源）")
    void passDisablesCulling() {
        String pass = readOrSkip(PASS);
        assertEquals(1, countCode(pass, "pass.disableCulling()"),
                "我方插的帧图 pass 必须显式 disableCulling："
                        + "原版 FrameGraphBuilder#identifyPassesToKeep 会剔除「产出未被消费」的 pass，"
                        + "被剔除时**不报错**，功能静默失效");
    }

    @Test
    @DisplayName("🔖 M-15：pass 身份是**对象**不是标志位 —— inMrtPass 与 finally 纪律整体消失")
    void passIdentityIsObjectNotFlag() {
        String pass = readOrSkip(PASS);
        // 🔖 旧守卫（activeFlagClearedInFinally）要求「置位 + finally 清位」成对存在。
        //   R1a 之后这整条纪律不该存在：判据换成「这次 draw 的 renderPass 是不是我方那个 pass 对象」，
        //   而 RenderPearl 每次 createRenderPass 都给**新**对象 ⇒ 上一帧的引用不会与本帧原版 pass 相撞，
        //   于是「漏清 = 原版 pass 拿到 8 附件管线」这个风险类别（GAP-040）从代码里消失。
        assertEquals(0, countCode(pass, "inMrtPass"),
                "静态布尔必须删干净 —— 留着就是靠「记得清」保证正确性");
        assertEquals(0, countCode(pass, "} finally {"),
                "两处 pass 体都不再需要 finally 清理（对象身份不需要清）");
        assertEquals(2, countCode(pass, "ownPass = renderPass;"),
                "地形 pass A 与水 pass B 都要记下自己的 pass 对象 —— 少记一条，那一趟 draw 就拿不到多附件变体");
        assertTrue(pass.contains("public static boolean isOurPass("),
                "身份判定必须有唯一出口（mixin 侧只做一次比较，不写业务，X25）");
    }

    @Test
    @DisplayName("🔖 M-01 退化成「只按层名查表」；多附件升级只在 PassAwarePipelines.select 里")
    void m01OnlyLooksUpLayerTable() {
        String api = readOrSkip(API);
        int derivedAt = api.indexOf("public static RenderPipeline derivedTerrainPipeline");
        int awareAt = api.indexOf("static @Nullable RenderPipeline mrtVariantOrNull", derivedAt + 10);
        assertTrue(derivedAt > 0 && awareAt > derivedAt,
                "两个都要在：derivedTerrainPipeline（M-01 查表）与 mrtVariantOrNull（M-15 用的表读）");
        String derived = api.substring(derivedAt, awareAt);
        assertEquals(0, countCode(derived, "MrtTerrainPass.active()"),
                "M-01 不再判「在不在我方 pass 里」—— 它只按层名交出单附件派生版");
        assertEquals(0, countCode(derived, "hasExpectedAttachmentCount()"),
                "空转守卫已退役（GAP-040）");
        // 🔖 判定本体落在独立类（ClassLineRatchet 的口径：巨类不再长新逻辑，QD-10 / 19 §3.1）。
        String aware = readBridgeSource("PassAwarePipelines.java");
        assertTrue(aware.contains("TerrainPipelineApi.mrtVariantOrNull(layer, multiDraw)"),
                "多附件变体的查表只在这一处（两套管线不混用的收口点）");
        assertTrue(aware.contains("!ourPass"),
                "🔴 判据的短路形状：pass 身份不符 ⇒ 逐字原样返回入参（故意错配一次必须被拒）");
    }

    @Test
    @DisplayName("🔖🔖 管线附件数与 pass 附件数必须取同一处（无 validation layer ⇒ 不匹配静默失效）")
    void attachmentCountHasSingleSource() {
        // 🔖 本机**没有** Vulkan validation layer ⇒ 「管线颜色附件数 ≠ pass 附件数」
        // 是静默未定义行为：draw 照提交、一条片元都不出、日志全绿、屏幕只有清屏色。
        // 这正是本轮卡了最久的症状 ⇒ 两侧必须同源，且不能再引用写死的 SLOT_COUNT。
        String api = readOrSkip(API);
        assertTrue(api.contains("withColorTargetStates(0, MrtPlan.slotCount() - 1"),
                "MRT 管线附件数必须取 MrtPlan.slotCount()（单点真源）");
        assertEquals(0, countCode(api, "withColorTargetStates(0, MrtPlan.SLOT_COUNT - 1"),
                "不得用写死的 SLOT_COUNT —— 两侧会与可调的 slotCount() 脱钩");
        String pass = readOrSkip(PASS);
        assertTrue(pass.contains("MrtPlan.slotCount()"),
                "pass 附件数必须取同一个 MrtPlan.slotCount()");
        assertTrue(readOrSkip(Path.of("src/main/java/dev/vkdisp/pipeline/model/MrtPlan.java"))
                        .contains("public static int slotCount()"),
                "单点真源必须是可调方法（否则没法把两侧同时设成 1 做对照实验）");
    }

    @Test
    @DisplayName("🔖 两条 gbuffer 路线开关必须**同默认值**（半开档 = 全黑，2026-10-12 实测）")
    void terrainKeysShareOneDefault() {
        // 本测试的前身是「mrt.terrain 必须默认 false —— 开启时地形被画两遍、屏幕画面不变」。
        // 🔴 那句前提被真机否证了：单开 mrt.terrain 时地形**只进 colortex**，而把 colortex 带回
        //   主目标的是后处理链（其生效条件同时含 mrt.packTerrainShader）⇒ 半开档实测
        //   `main` allZero=true 而同帧 `c0` nonBlack=100%（探针口径 NOT_ON_MAIN）、零异常 = 全黑。
        //   所以真正的不变式不是「默认必须关」，而是「**两条不得一开一关**」+「半开必须点名」。
        String config = readOrSkip(CONFIG);
        String terrain = codeDefaultOf(config, "mrt.terrain");
        String packFragment = codeDefaultOf(config, "mrt.packTerrainShader");
        assertEquals(terrain, packFragment,
                "🔴 mrt.terrain 与 mrt.packTerrainShader 必须同开同关 —— 一开一关 = 半开档 = 屏幕全黑"
                        + "（链不跑而地形已离开主目标）。实测读数见 CHANGE_LOG 2026-10-12 与 GAP-036");

        // 半开档仍要能被单独构造（A/B 取证要它），但**不许静默**：hook 里必须有点名那一行。
        String hook = readOrSkip(Path.of("src/main/java/dev/vkdisp/render/FullscreenPassHook.java"));
        assertTrue(hook.contains("半开档"),
                "🔴 半开档（terrain 开而链不跑）必须在日志里点名一次 —— 本仓纪律 X11：允许难看，禁止静默");
    }

    /** 取 {@code .define("键", 默认值)} 里那个字面量（源码级，不启动游戏）。 */
    private static String codeDefaultOf(String config, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("define\\(\"" + java.util.regex.Pattern.quote(key) + "\",\\s*(true|false)\\)")
                .matcher(config);
        assertTrue(m.find(), "VkDispConfig 里找不到 " + key + " 的 define(…, 布尔) 形状");
        return m.group(1);
    }

    @Test
    @DisplayName("🔖 本轮不产出画面改进：pass 只画 OPAQUE 组、只写自己的 colortex")
    void scopeIsExplicitlyNarrow() {
        String pass = readOrSkip(PASS);
        // 只画 OPAQUE：半透明地形与特性/云仍在原版 pass ⇒ 牵连面最小。
        assertTrue(pass.contains("ChunkSectionLayerGroup.OPAQUE"),
                "本轮只画 OPAQUE 组（固体 + cutout）；半透明地形未覆盖");
        // 不碰主目标：attachment 全部来自我方 colortex。
        assertTrue(countCode(pass, "mainRenderTarget().getColorTextureView()") == 0,
                "本 pass 不得写主目标（写主目标会与原版 pass 争同一附件）");
    }

    @Test
    @DisplayName("🔖🔖 回读必须用**不翻转**管线 —— 翻转版会把画面上下颠倒（h05 实测）")
    void readbackMustUseNoFlipPipeline() {
        // 🔖🔖 本轮真实 bug：回读采样的是**引擎自己渲染出的** colortex（与主目标同取向），
        //   却用了 `fullscreen_flipv`。那次翻转补偿的是「包 composite 的 OF 原始 vUv 语义」，
        //   不是引擎取向 ⇒ 画面被上下颠倒。
        // 🔖 为什么 h02/h04 都没抓到：那两轮回读的是常量指纹与平滑渐变，
        //   **对采样坐标错误完全不敏感** ⇒ 判据内容必须能区分被测属性。
        String probe = readOrSkip(Path.of("src/main/java/dev/vkdisp/bridge/MrtProbe.java"));
        assertEquals(0, countCode(probe, "PipelineApi.mrtViewPipeline()"),
                "回读不得再用翻转版 mrtViewPipeline（它是为包 composite 的 OF vUv 语义准备的）");
        assertTrue(probe.contains("PipelineApi.mrtViewNoFlipPipeline()"),
                "回读必须用不翻转版（采样引擎渲染目标时不需要 V 翻转）");
        String api = readOrSkip(Path.of("src/main/java/dev/vkdisp/bridge/PipelineApi.java"));
        assertTrue(api.contains("MRT_VIEW_NOFLIP_LOCATION"), "必须注册一条不翻转版回读管线");
        assertTrue(api.contains("withVertexShader(Identifier.fromNamespaceAndPath(VkDisp.MOD_ID, \"fullscreen\"))"),
                "不翻转版的顶点着色器必须是 fullscreen（原始 vUv），不是 fullscreen_flipv");
        assertTrue(countCode(readOrSkip(Path.of(
                "src/main/java/dev/vkdisp/render/FullscreenPipelineRegistrar.java")),
                "registerMrtViewPipeline(event)") >= 1,
                "翻转版管线必须保留：合成链的 OF vUv 语义补偿仍依赖它（不能为修一个 bug 删另一个能力）");
    }

    @Test
    @DisplayName("🔴 mip 金字塔的 blit 不得带 V 翻转 —— 带了就是奇数级上下镜像（GAP-031 真机定案）")
    void mipPyramidBlitMustNotFlipV() {
        // 事故形态：`blit.fsh` 曾经是「中间目标 → 主目标」那一步的**翻转版**，而它全仓唯一的
        //   消费者是 `FrameApi.generateMipPyramids`（mip L-1 → mip L = 同一张图的两个层级）
        //   ⇒ 每生成一级镜像一次 ⇒ 奇数级上下颠倒。
        // 🔖 与上面 readbackMustUseNoFlipPipeline 是同一族：那次是回读，这次是金字塔。
        //   判据不是「看起来怪」而是可数的分带读数（GAP-031）：m1 的 SKY_BAND 0.9351 >
        //   TERRAIN_BAND 0.6166，而 mip0/m2/m4 都是 SKY < TERRAIN —— 只有奇数级反，
        //   正是「每级翻一次」的签名。
        String blit = readOrSkip(Path.of(
                "src/main/resources/assets/vkdisp/shaders/blit.fsh"));
        assertEquals(0, countCode(blit, "1.0 - vUv"),
                "金字塔 blit 不得 V 翻转：同类目标之间的拷贝必须保持取向");
        assertTrue(countCode(blit, "texture(InSampler, vUv)") >= 1,
                "blit 必须是原样拷贝（直接以 vUv 采样），不是某种坐标补偿");

        // 消费者锚点：这条守卫断的是「谁在用这条管线」。消费者换了（尤其换成跨目标取向的一步），
        //   取向口径就要重判 —— 不许让守卫跟着代码悄悄失效。
        assertTrue(readOrSkip(BRIDGE.resolve("FrameApi.java")).contains("PipelineApi.blitPipeline()"),
                "generateMipPyramids 仍是 blit 管线的消费者；它变了就必须重判本守卫的取向前提");

        // 翻转能力本身必须还在：那是「中间目标 → 主目标」的补偿（P-1f 标定），
        //   不能为了修金字塔把它一起删掉 —— 那会把另一格改回上下颠倒。
        String flip = readOrSkip(Path.of(
                "src/main/resources/assets/vkdisp/shaders/fullscreen_flipv.vsh"));
        assertTrue(flip.contains("1.0 - uv.y"),
                "fullscreen_flipv 仍负责中间目标→主目标的 V 翻转（GAP-031 修的是金字塔，不是这条约定）");
    }

    @Test
    @DisplayName("🔖 两条模式（帧图内 / AfterLevel）共用同一条回读路径，不再分叉")
    void bothModesShareOneReadbackPath() {
        // h05 已证实帧图内插 pass 同样是通的 ⇒ terrainAfterLevel 只是 A/B 诊断开关。
        String frame = readOrSkip(Path.of("src/main/java/dev/vkdisp/bridge/FrameApi.java"));
        assertTrue(frame.contains("drawExternalView("),
                "回读必须走 drawExternalView（两条模式共用），不得按模式分叉出两套回读");
    }

    @Test
    @DisplayName("🔖 自建深度必须清到 0.0（反向 Z）—— 清成 1.0 会把地形全深度测试掉且零报错")
    void ownDepthIsCleared() {
        String pass = readOrSkip(PASS);
        // 🔖🔖 本引擎是反向 Z：原版 clear pass 清的是 **0.0**（LevelRenderer:255）。
        //   清成 1.0（近平面）⇒ 地形全部被深度测试拒绝 ⇒「pass 跑通、零报错、屏幕只有清屏色」。
        //   本轮为这个 0.0/1.0 之差白跑了 6 趟客户端，必须有构建期红灯。
        assertTrue(pass.contains("withDepthAttachment(colortexDepth.getDepthTextureView(), OptionalDouble.of(0.0))"),
                "自建深度必须清到 0.0（反向 Z 的远平面），与原版 clear pass 一致");
        assertEquals(0, countCode(pass, "OptionalDouble.of(1.0)"),
                "不得把深度清成 1.0 —— 那是反向 Z 的**近**平面，会把地形全部深度测试掉");
        // 🔴 h50n 把这条从「一处都不许」改成「只许出现在**续接段**里，且恰好一处」：
        //   GAP-023 第二格要求把半透明（水）单独开成 pass B，好在不透明段与半透明段之间发 blit
        //   （blit 是 encoder 命令，pass 打开期间不能发 —— h10 规则）。
        //   pass B 的深度**必须** LOAD（empty()）：清成 0.0 就把不透明段的深度抹掉了，
        //   而反向 Z 下 0.0 = 远平面 ⇒ 水会画到地形**后面**去。
        //   ⇒ 原话「不得对自建深度用 empty()」在只有一段的时候是对的，拆段之后会误伤正确的写法。
        //   真正要禁的是**第一段**用 empty()（那等于本帧从上一帧的深度开始画）。
        int segmentB = pass.indexOf("private static void drawWaterSegment");
        assertTrue(segmentB > 0, "拆段之后的 pass B（drawWaterSegment）必须存在");
        assertEquals(0, countCode(pass.substring(0, segmentB),
                        "withDepthAttachment(colortexDepth.getDepthTextureView(), OptionalDouble.empty())"),
                "第一段（本帧开头那个 pass）不得对自建深度用 empty() —— 它必须清到 0.0");
        assertEquals(1, countCode(pass.substring(segmentB),
                        "withDepthAttachment(colortexDepth.getDepthTextureView(), OptionalDouble.empty())"),
                "续接段（pass B）的深度必须恰好 LOAD 一次；多于一次 = 有人又开了一段却忘了清屏语义");
    }

    @Test
    @DisplayName("🔖 回读用的槽数必须取地形 pass 的，不是探针的（本轮真踩过）")
    void readbackSlotCountComesFromTerrainPass() {
        // 🔖 实测踩坑：地形模式下探针那套 colortex **根本没建**（actualSlots=0），
        // 误取探针槽数 ⇒ 每帧抛 "mrt view slot 0 out of range 0..-1"。
        // 这类错误不崩游戏、只刷日志，很容易被当成噪声忽略 ⇒ 必须有构建期红灯。
        String frame = readOrSkip(Path.of("src/main/java/dev/vkdisp/bridge/FrameApi.java"));
        assertTrue(frame.contains("MrtPlan.requireViewSlot(MrtProbe.viewSlot(), MrtTerrainPass.actualSlots())"),
                "地形模式的回读槽数上限必须取 MrtTerrainPass.actualSlots()");
        assertTrue(frame.contains("!MrtTerrainPass.toMain()"),
                "诊断「画到主目标」模式下不得再做 colortex 回读 —— 那会把刚画进主目标的内容"
                        + "用 colortex 覆盖掉，把唯一证据擦掉");
        assertEquals(0, countCode(frame, "MrtProbe.viewSlot(), MrtProbe.actualSlots()"),
                "不得在需要地形槽数的位置取探针槽数（探针本轮未建 = 0 ⇒ 每帧抛异常）");
        assertTrue(frame.contains("MrtTerrainPass.actualSlots() > 0"),
                "地形目标尚未建成的帧应静默跳过（不是失败），建成后再校验槽位越界");
    }

    @Test
    @DisplayName("🔖 依赖 M-06 的捕获：拿不到数据时静默跳过，且类型化到不再需要 instanceof")
    void dependsOnM06Capture() {
        String pass = readOrSkip(PASS);
        assertTrue(pass.contains("TerrainDrawCapture.current()"),
                "必须从 M-06 捕获处取地形 draw 数据");
        assertTrue(pass.contains("if (draws == null)"),
                "捕获为空时应静默跳过 —— 「未启用 M-06」不是失败，M-06 自己的埋点负责可见性");
        // 🔴 R2 的「两点收一点」在这里的可验收证据：捕获存的类型是 ChunkSectionsToRender（由注入点
        //   形参保证），于是旧 M-05 那套 volatile Object + 模式匹配 instanceof + 「捕获到别的东西」
        //   的 ERROR 分支必须整体消失 —— 留着就是用一个运行时猜测替编译期类型再加一层。
        // ⚠️ 判据只认「捕获那一次的类型猜测」这个形状，不认所有 instanceof：
        //    probeDrawCounts 里对 public record 元素类型的 instanceof 是正当的（M-08 用它读 drawCount）。
        assertEquals(0, countCode(pass, "instanceof ChunkSectionsToRender draws"),
                "M-06 之后不该再有「instanceof ChunkSectionsToRender draws」：形参类型已经保证");
        assertEquals(0, countCode(pass, "Object captured"),
                "捕获不应再以 Object 形态存在（旧 M-05 的 volatile Object 已换成类型化字段）");
        assertTrue(pass.contains("ChunkSectionsToRender draws = TerrainDrawCapture.current()"),
                "取用点应直接按类型接（TerrainDrawCapture.current() 已返回 ChunkSectionsToRender）");
    }

    @Test
    @DisplayName("🔖🔖 捕获必须在 pass 体（执行期）读，不能在帧图装配期读")
    void captureIsReadAtExecuteTimeNotAssemblyTime() {
        // 🔖🔖 本轮真踩过、且症状极具欺骗性的坑：
        //   帧图装配 = LevelRenderer#render 第 249 行（官方事件），M-05 捕获 = 第 271-275 行，
        //   **装配早于捕获** ⇒ 装配期读到的是上一帧的 ChunkSectionsToRender，
        //   其 DynamicGpuBuffer 切片已被本帧上传环形复用覆盖 ⇒ 几何退化 ⇒ 零片元。
        //   表面症状：pass 正常跑、0 validation error、无异常日志，colortex 里只有清屏色。
        String pass = readOrSkip(PASS);
        int setupAt = pass.indexOf("static void onFrameGraphSetup(");
        int bodyAt = pass.indexOf("private static void drawTerrain(");
        assertTrue(setupAt > 0 && bodyAt > setupAt, "找不到装配方法或 pass 体");
        String setup = pass.substring(setupAt, bodyAt);
        assertEquals(0, countCode(setup, "TerrainDrawCapture.current()"),
                "装配期不得读捕获（那时拿到的是上一帧数据）；必须在 pass 体内读");
        String body = pass.substring(bodyAt);
        assertTrue(body.contains("TerrainDrawCapture.current()"),
                "pass 体（执行期）必须读捕获 —— 那才是本帧的数据");
    }

    @Test
    @DisplayName("`U0001f534 h20 缺陷②：接线用的派生管线**不得**携带只在我方 pass 里绑定的自定义绑定组")
    void wiredPipelineCarriesNoOrphanBindGroup() {
        String src = readBridgeSource("TerrainPipelineApi.java");
        int i = src.indexOf("public static void registerTerrainDerivedPipelines");
        assertTrue(i >= 0, "注册方法没找到");
        int end = src.indexOf("public static", i + 10);
        String body = src.substring(i, end > i ? end : src.length());
        assertFalse(body.contains("TERRAIN_PARAMS_UNIFORM"),
                "`U0001f534 接线管线带了 VkDispTerrainParams 绑定组：原版路径无人绑定它");
    }

    @Test
    @DisplayName("🔴 GAP-040 退役：MRT 变体的守卫是 pass 身份，不是「附件数对不对」的空转比较")
    void mrtVariantGuardedByIdentityNotAttachmentCount() {
        String api = readBridgeSource("TerrainPipelineApi.java");
        String aware = readBridgeSource("PassAwarePipelines.java");
        assertEquals(0, countCode(api, "MRT_GUARD_FALLBACK"),
                "旧守卫的去重告警集合必须删 —— 它守的是一个不该存在的状态（标志位为 true 却不在我方 pass 里）");
        assertFalse(api.contains("hasExpectedAttachmentCount()"),
                "旧守卫的判定本体是「inMrtPass 为 true 就直接返回 true」= 拿标志位证明标志位");
        assertTrue(aware.contains("boolean ourPass"),
                "新判据以 pass 身份为**入参**（由 @WrapOperation 的 renderPass 形参算出）");
        // 埋点必须在，但它**不是**判据的输入（否则又回到「靠标志位证明标志位」那一族）。
        assertTrue(aware.contains("onHit()"),
                "M-15 的观测面：证明那次取管线真的被包住（热路径节流同 M-01）");
        assertFalse(aware.contains("inMrtPass"),
                "判定侧不得再引用静态标志 —— 那是本条要删掉的东西");
    }

    @Test
    @DisplayName("`U0001f534 h34：`ensureTargets` 每帧都跑 ⇒ 里面不得有无条件 INFO 日志")
    void ensureTargetsHasNoUnconditionalInfoLog() {
        // `U0001f534 h34 实测：`h33` 拆 ensure 时把「targets ready」这条 INFO 从
        // 「首次创建分支」挪到了 `ensureTargets` 末尾 ⇒ 变成每帧一条
        // ⇒ 一次运行刷了 **499 行**。与 `h25` 的 M-01 埋点 600→250000、
        // 以及本文件上面「守卫命中必须去重告警（热路径上不能刷日志）」是同一条纪律。
        String pass = readOrSkip(PASS);
        int start = pass.indexOf("private static void ensureTargets(");
        int end = pass.indexOf("private static void ensureColortex(");
        assertTrue(start > 0 && end > start, "定位不到 ensureTargets / ensureColortex");
        String ensureTargets = pass.substring(start, end);

        // `ensureTargets` 里出现的 INFO 必须是**带条件**的（inside an if / one-shot flag）。
        int idx = ensureTargets.indexOf("LOGGER.info(");
        assertTrue(idx < 0,
                "`U0001f534 ensureTargets` 每帧都会执行，里面不得有无条件 LOGGER.info"
                        + "（h34 实测刷了 499 行）。请把它移到真正建好资源的那一刻，"
                        + "或加一次性/节流哨兵");
        // 反向确认：这条日志确实还在，且挂在创建路径上（不能为了不刷屏把可诊断性删掉）。
        assertTrue(pass.contains("gbuffer terrain targets ready"),
                "`U0001f534 「targets ready」这条可诊断性不得为了不刷屏而删掉");
    }

    private static String readBridgeSource(String file) {
        try {
            return Files.readString(java.nio.file.Path.of(
                    "src/main/java/dev/vkdisp/bridge", file));
        } catch (java.io.IOException e) {
            throw new AssertionError("读不到 bridge 源码: " + file, e);
        }
    }
}