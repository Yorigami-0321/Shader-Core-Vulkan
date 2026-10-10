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
    @DisplayName("🔖 活动标记的置位/清位必须在 try/finally —— 漏清会让原版 pass 拿到多附件管线")
    void activeFlagClearedInFinally() {
        String pass = readOrSkip(PASS);
        assertTrue(pass.contains("inMrtPass = true;"), "必须在进入 pass 时置位");
        assertTrue(pass.contains("} finally {"), "必须有 finally");
        // 清位语句在 finally 块内：取 finally 之后的文本里必须出现清位。
        int finallyAt = pass.indexOf("} finally {");
        assertTrue(finallyAt > 0, "找不到 finally 块");
        String after = pass.substring(finallyAt);
        assertTrue(after.contains("inMrtPass = false;"),
                "清位必须在 finally 里 —— 漏清时原版单附件 pass 会拿到多附件管线 ⇒ validation error");
    }

    @Test
    @DisplayName("🔖 M-01 必须在 MRT pass 内返回多附件变体（两套管线不能混用）")
    void m01SelectsMrtVariantInsidePass() {
        String api = readOrSkip(API);
        assertTrue(api.contains("MrtTerrainPass.active()"),
                "M-01 的管线解析必须先判「是否在 MRT pass 内」");
        assertTrue(api.contains("DERIVED_MRT.get(key(layer, multiDraw))"),
                "MRT pass 内必须取多附件变体的表");
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
    @DisplayName("🔖 诊断开关必须默认关闭（常规帧零开销，支柱③ B1 ≤ +2%）")
    void diagnosticIsOffByDefault() {
        String config = readOrSkip(CONFIG);
        assertTrue(config.contains("define(\"mrt.terrain\", false)"),
                "mrt.terrain 必须默认 false —— 开启时地形被画两遍，是诊断路径不是产品功能");
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
    @DisplayName("🔖 依赖 M-05 的捕获：拿不到数据时静默跳过（未启用不是失败）")
    void dependsOnM05Capture() {
        String pass = readOrSkip(PASS);
        assertTrue(pass.contains("TerrainDrawCapture.current()"),
                "必须从 M-05 捕获处取地形 draw 数据");
        assertTrue(pass.contains("if (captured == null)"),
                "捕获为空时应静默跳过 —— 「未启用 M-05」不是失败，M-05 自己的埋点负责可见性");
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
    @DisplayName("`U0001f534 h20 缺陷①：MRT 变体须有 attachment 数守卫，命中即回退原版管线")
    void mrtVariantIsGuardedByAttachmentCount() {
        String src = readBridgeSource("TerrainPipelineApi.java");
        assertTrue(src.contains("hasExpectedAttachmentCount()"),
                "`U0001f535 没有守卫调用：active() 为 true 就无条件把 8 附件管线交给原版");
        assertTrue(src.contains("MRT_GUARD_FALLBACK"),
                "`U0001f50d 守卫命中必须去重告警（热路径上不能刷日志）");
        assertTrue(src.contains("回退原版管线"),
                "`U0001f50d 守卫命中必须自报，否则无法区分「没触发」与「触发了但没生效」");
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