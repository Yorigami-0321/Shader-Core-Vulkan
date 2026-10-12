package dev.vkdisp.bridge;
/**
 * 【参考调研】R7 / M-04 ⑤-b 的通用派生公式守卫 / 纯函数断言 + 只读工程自身文件
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓库 {@code bridge/MainPassPipelineDerivation.java}、
 *    {@code bridge/ColortexPool.java}，以及 {@code docs/04-SPEC.md} §5.0.6 ④ / §5.0.7 表 ④
 *    （本项目自有文档，MIT）。
 *    → 可并入本项目（MIT）：本文件不引用任何外部代码。
 * 1. 官方/主实现：无。
 * 2. 备选：把 renderpearl 加进 {@code testImplementation}，让单测直接构造 {@code ColorTargetState} ——
 *    <b>否决</b>：那个构件不在 Maven Central（本机出网半通，见 memory 的 Windows 车道条），写死
 *    gradle 缓存路径会把 CI 拖回「连红 6 次」那一族（{@code build.gradle:320-330} 当年为 joml
 *    破过一次例，是因为 joml 在 Central 上）。⇒ 能纯的地方纯到 int，碰客户端类型的地方用文本守卫，
 *    并在这里<b>明说哪一半只是文本断言</b>（不假装单测覆盖了设备侧）。
 * 3. 我们的差异点：{@code refusalReason} 是「交回原版」这条退路的<b>全部</b>判定，它只吃两个 int ⇒
 *    真值表能跑；PAD 那一行的格式/写掩码/混合、以及「已声明的槽逐条沿用」是形状约束 ⇒ 文本钉住。
 * 4. 许可证核对：本项目 MIT。
 * 5. 性能基线：❄️ 单测。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M-04 ⑤-b：补槽公式的「交回原版」真值表 + 补槽形状守卫。
 */
class MainPassPipelineDerivationTest {

    private static final Path DERIVATION =
            Path.of("src/main/java/dev/vkdisp/bridge/MainPassPipelineDerivation.java");
    private static final Path POOL = Path.of("src/main/java/dev/vkdisp/bridge/ColortexPool.java");

    private static String readOrSkip(Path path) {
        Assumptions.assumeTrue(Files.exists(path), "工程文件缺失（不在工程根目录运行？）: " + path);
        try {
            return Files.readString(path);
        } catch (java.io.IOException e) {
            throw new AssertionError("读取失败: " + path, e);
        }
    }

    @Test
    @DisplayName("交回原版的条件（纯 int）：补不出来 / 超 8 槽上限 / 零附件；原因串要带数字")
    void refusalCoversTheShapesWeCannotPad() {
        assertNull(MainPassPipelineDerivation.refusalReason(1, 8), "最常见：1 条声明补到 8 槽");
        assertNull(MainPassPipelineDerivation.refusalReason(8, 8), "已声明满 8 条 = 不用补，也不算拒绝");
        assertTrue(MainPassPipelineDerivation.refusalReason(9, 8).contains("9"),
                "管线声明比附件还多 = 补不出来（只能少声明）⇒ 交回原版，原因要点名条数");
        assertNotNull(MainPassPipelineDerivation.refusalReason(2, 1),
                "包输出比主 pass 附件多 ⇒ 这条走 GAP-046 的退路分支");
        assertNotNull(MainPassPipelineDerivation.refusalReason(1, 9),
                "超过 ColorTargetState.MAX_COLOR_TARGETS=8（原版硬上限，不是我们的偏好）");
        assertNotNull(MainPassPipelineDerivation.refusalReason(1, 0), "零附件不是主 pass 的形状");
    }

    @Test
    @DisplayName("补槽形状：RGBA8_UNORM（= 池的格式）+ WRITE_NONE + 不混合；池格式漂了要同步")
    void padShapeMatchesThePoolAndNeverWrites() {
        String derivation = readOrSkip(DERIVATION);
        assertTrue(derivation.contains("GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE"),
                "补槽必须与 ColortexPool 的附件同格式（FrontendRenderPass:119-121 逐条比格式），"
                        + "且 WRITE_NONE —— 包的片元会读 colortex1/2，补槽写颜色就是覆盖它的输出");
        assertTrue(derivation.contains("new ColorTargetState(Optional.empty(),"),
                "Optional.empty() = 不带混合状态；换成任何 BlendFunction 都会改主 pass 的合成结果");
        assertTrue(readOrSkip(POOL).contains("GpuFormat.RGBA8_UNORM"),
                "ColortexPool 的颜色槽格式变了，就必须同步改这里的 PAD");
    }

    @Test
    @DisplayName("派生只补「没声明的」：已声明的槽逐条沿用、null 洞填 PAD、其余状态靠 toBuilder 保住")
    void declaredTargetsAreCopiedVerbatim() {
        String derivation = readOrSkip(DERIVATION);
        assertTrue(derivation.contains("existing == null ? pad : existing"),
                "target0（及任何已声明的槽）必须逐字沿用 —— 重造就会把原版的混合/写掩码洗掉");
        assertTrue(derivation.contains("pipeline.toBuilder()"),
                "用原版 toBuilder() 派生（uniform / 顶点 / 片元等其余状态全保留），不 new Builder");
        assertTrue(derivation.contains("withColorTargetState(index, targets[index])"),
                "逐槽按 index 交回去（activeColorTargetStateCount 靠 index 抬，RenderPipeline:273-276）");
        assertEquals(-1, derivation.indexOf("RenderPipelines."),
                "这里不许出现「按名字白名单派生」—— §5.0.6 ④ 的开放集合正是六处阶段事件交出去的未知管线");
    }

    @Test
    @DisplayName("退路不许静默：交回原版要计数 + 首次点名（GAP-046 收窄后的写法）")
    void refusalIsCountedAndNamed() {
        String derivation = readOrSkip(DERIVATION);
        assertTrue(derivation.contains("STATS.refused++"), "交回原版要计数");
        assertTrue(derivation.contains("首条交回原版的管线"), "第一次拒绝必须点名是哪条管线");
        assertTrue(derivation.contains("return pipeline;"), "拒绝 = 原样交回入参，不改任何东西");
    }

    @Test
    @DisplayName("⑤-c：derive 是每次 draw 一次 ⇒ 必须有按对象身份的记忆层，且槽数一变就作废")
    void deriveIsMemoisedByIdentity() {
        String derivation = readOrSkip(DERIVATION);
        assertTrue(derivation.contains("RenderPipeline memoised = remember(pipeline, slotCount);"),
                "先查记忆表 —— 不查就是每次 draw 都 toBuilder().build() 建新对象");
        assertTrue(derivation.contains("new IdentityHashMap<>()"),
                "按对象身份记（主 pass 里取的都是 RenderPipelines 静态常量）；用 equals/hashCode 会把"
                        + "大 record 的哈希开销叠在原版缓存之上");
        assertTrue(derivation.contains("if (STATS.memoSlots != slotCount) {"),
                "槽数变了（换包档 / 换池尺寸）必须整表作废，否则会把 8 槽的结果给 5 槽的 pass");
        assertTrue(derivation.contains("STATS.padded.size() >= 256"),
                "没有「包代次」这一维就只能靠上限清空 —— GAP-018/045 那一族的形状");
        assertTrue(derivation.contains("STATS.padded.put(pipeline, padded);"), "派生结果要回写记忆表");
    }

    @Test
    @DisplayName("⑤-c 第一族的注入点存在、只转发；且 selectForMainPass 不许查地形的层名表")
    void renderTypeSiteDoesNotReuseTheLayerNameTable() {
        String mixin = readOrSkip(Path.of("src/main/java/dev/vkdisp/mixin/PreparedRenderTypePipelineMixin.java"));
        assertTrue(mixin.contains("MixinTargets.PREPARED_RENDER_TYPE_DRAW"),
                "目标必须写完整描述符（draw 有同族重载；改名要注入期硬失败，T10 / X11）");
        assertTrue(mixin.contains("PassAwarePipelines.onMainPassSiteHit("),
                "每处注入点都要能证明被走到（M1 编码约束 ③）");
        assertTrue(mixin.contains("MainPassTakeover.isTakenOverPass(renderPass)"),
                "pass 身份来自形参 renderPass 的对象比较，不是静态标志（GAP-040 那一族）");

        String aware = readOrSkip(Path.of("src/main/java/dev/vkdisp/bridge/PassAwarePipelines.java"));
        int site = aware.indexOf("public static RenderPipeline selectForMainPass(");
        assertTrue(site > 0, "⑤-c 那一支要走独立入口（而不是把渲染类型名当层名传进 select）");
        String body = aware.substring(site, aware.indexOf("\n    }", site));
        assertEquals(-1, body.indexOf("mrtVariantOrNull"),
                "🔴 名字会撞车：RenderType 有 \"solid\"/\"translucent\"，ChunkSectionLayer 也叫 solid —— "
                        + "拿渲染类型名去查地形多附件表，就会在有非地形 draw 落进我方 gbuffer pass 的那天，"
                        + "把地形管线塞给不相干的一族");
        assertTrue(body.contains("!takenOverMainPass"),
                "没接管主 pass ⇒ 逐字原样返回（默认档 mixin.ownTerrainPass=false 时这一处零影响）");
    }
}
