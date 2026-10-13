package dev.vkdisp.bridge;
/**
 * 【参考调研】R7 / M-04 ⑤-a 的判据与接线守卫 / 只读工程自身文件
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓库 {@code bridge/MainPassTakeover.java}、
 *    {@code mixin/MainPassAttachmentMixin.java}、{@code bridge/MixinTargets.java}、
 *    {@code VkDispConfig.java}，以及 {@code docs/04-SPEC.md} §5.0.7（本项目自有文档，MIT）。
 *    → 可并入本项目（MIT）：本文件不引用任何外部代码，只做纯函数断言 + 文本断言。
 * 1. 官方/主实现：无。
 * 2. 备选：无。
 * 3. 我们的差异点：把「label 改名 / 补槽尺寸不一致」这两条会<b>静默退回今天画面</b>的路径
 *    钉成构建期红灯（X11 不静默降级）。⚠️ 但「注入到底命中没命中」这条<b>单测给不出证据</b> ——
 *    它只在真机日志里（{@code 主 pass 接管=} 那一格），本文件不假装覆盖了它。
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
 * M-04 ⑤-a：主 pass 判定的真值表 + 注入接线。
 */
class MainPassTakeoverTest {

    private static final Path TAKEOVER = Path.of("src/main/java/dev/vkdisp/bridge/MainPassTakeover.java");
    private static final Path MIXIN = Path.of("src/main/java/dev/vkdisp/mixin/MainPassAttachmentMixin.java");
    private static final Path TARGETS = Path.of("src/main/java/dev/vkdisp/bridge/MixinTargets.java");
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
    @DisplayName("判据：单附件 + label ∈ {Main,Solid}（第三道视图身份已因真机实测降级为交叉核对）")
    void predicateIsTwoGatesFromTheCallItself() {
        assertTrue(MainPassTakeover.matchesMainPass(1, "Main"), "原版非 OIT 主 pass（LevelRenderer:458）");
        assertTrue(MainPassTakeover.matchesMainPass(1, "Solid"), "OIT 开时同一条 pass 换了 label");
        assertFalse(MainPassTakeover.matchesMainPass(1, "Item in hand"),
                "GameRenderer:412 也是「单附件 + 主目标的颜色」⇒ 这一档只能靠 label 分开，label 不能省");
        assertFalse(MainPassTakeover.matchesMainPass(1, "Sky"), "天空那条同样只写 main 的颜色");
        assertFalse(MainPassTakeover.matchesMainPass(2, "Main"),
                "我方自己的 MRT pass 就是多附件：这条挡在它和我之间（选内层挂点后它本就不路过，别把双保险删了）");
        assertFalse(MainPassTakeover.matchesMainPass(0, "Main"), "零附件的描述符不是主 pass");
        String labelUnavailable = null;
        assertFalse(MainPassTakeover.matchesMainPass(1, labelUnavailable),
                "label 取不到 = 判据缺一条，宁可不接管也不能猜");
    }

    @Test
    @DisplayName("§5.0.6 ⑤ 第 2 条：补槽尺寸必须与 target0 一致（默认 renderArea 静默取最后一个非 null 附件）")
    void padsMustMatchTargetZeroSize() {
        assertTrue(MainPassTakeover.slotsUsable(3, 1920, 1080, 1920, 1080), "同尺寸 = 可接管");
        assertFalse(MainPassTakeover.slotsUsable(3, 1920, 1080, 1280, 720),
                "尺寸不一致还接管 = 在骗后端（renderArea 会按附件尺寸算，越界行为未定义）⇒ 必须拒");
        assertFalse(MainPassTakeover.slotsUsable(1, 1920, 1080, 1920, 1080),
                "只有 1 槽不叫接管（换完附件与原版逐字节相同，白担一次崩的风险）");
    }

    @Test
    @DisplayName("两个 label 来自 LevelRenderer:458 的三元式：改这张表必须同时改注释里的行号依据")
    void labelTableIsTheVanillaOne() {
        assertTrue(readOrSkip(TAKEOVER).contains("List.of(\"Main\", \"Solid\")"),
                "判据表与原版 :458 那两个串逐字对齐（useImprovedTransparency ? \"Solid\" : \"Main\"）");
    }

    @Test
    @DisplayName("接线：注入类只做「换实参 + 一次 original.call」，且 require/expect 都是 1")
    void mixinForwardsExactlyOnce() {
        String mixin = readOrSkip(MIXIN);
        assertTrue(mixin.contains("MainPassTakeover.onCreateRenderPass(descriptor,"),
                "⑤-a 只换实参、不接管 pass 的生命周期（X25：注入类里不许长逻辑，Operation 交给 bridge）");
        // 🔴 这条是被真机教出来的：Operation.call 的实参表 = 接收者 + 被包住方法的实参，条数少了
        //    就在运行期抛 IncorrectArgumentCountException（且开关关着也抛 —— 转发本身就走这条路）。
        assertTrue(mixin.contains("original.call(encoder, replaced)"),
                "转发必须把接收者一起交回 Operation.call：只交描述符 = 每一次建 pass 都崩，"
                        + "与开关状态无关（实测 TextureAtlas.uploadAnimationFrames 那一路先炸）");
        assertTrue(mixin.contains("require = 1, expect = 1"),
                "这个点没包上的症状 = 与今天逐字节相同 ⇒ 必须是硬失败，不能静默");
        assertFalse(mixin.contains("MrtTerrainPass"),
                "附件的取数全在 bridge 侧；mixin 里出现地形类名 = 逻辑开始往注入类漏");
    }

    @Test
    @DisplayName("目标描述符：6 参重载必须写全签名，嵌套类 RenderArea 用 $（错一个字符就是静默不加载）")
    void targetDescriptorsAreByteExact() {
        // 常量是跨行拼接的字面量 ⇒ 先把「相邻字符串相加」折回一条再逐字比
        String targets = readOrSkip(TARGETS).replaceAll("\"\\s*\\+\\s*\"", "");
        assertTrue(targets.contains("com.mojang.renderpearl.frontend.FrontendCommandEncoder"),
                "M-04 的目标类 = backend 无关的 frontend 那层（不注 VulkanCommandEncoder：X7 不绑后端）");
        assertTrue(targets.contains("Lcom/mojang/renderpearl/api/commands/RenderPass$RenderArea;)"),
                "嵌套类在描述符里必须是 $ 而不是 . —— 写错时 mixin 报「method not found」，别改成点号");
        assertTrue(targets.contains("createRenderPass(Ljava/util/function/Supplier;"),
                "6 参重载必须按全签名瞄准：该类有 4 个同名 createRenderPass，只给方法名会匹配到多条");
        assertTrue(targets.contains("RenderPassDescriptor;)Lcom/mojang/renderpearl/api/commands/RenderPass;"),
                "被包住的那次调用 = 第 95 行 createRenderPass(descriptor)");
    }

    @Test
    @DisplayName("🔴 开关默认必须是关：⑤-a 开着会在 setPipeline 当场抛（⑤-b 之前不许进取证档）")
    void takeoverDefaultsToOff() {
        String config = readOrSkip(CONFIG);
        assertTrue(config.contains("define(\"mixin.ownTerrainPass\", false)"),
                "键名沿用 04-SPEC §5.0 M-04 行登记的 mixin.ownTerrainPass；默认关 —— "
                        + "开着=崩是设计（那是本步唯一的证据），但绝不能是默认档");
        assertTrue(readOrSkip(TAKEOVER).contains("MIXIN_OWN_TERRAIN_PASS"),
                "接管路径必须受这条开关管（可关闭制 X29）");
    }

    @Test
    @DisplayName("附件出自池的待写那一代（GAP-018 口径与地形/云/天空同一条），不是被读那一代")
    void attachmentsComeFromTheWriteGeneration() {
        String takeover = readOrSkip(TAKEOVER);
        assertTrue(takeover.contains("MrtTerrainPass.poolWriteView(slot)"), "主 pass 写的是待写代");
        assertTrue(takeover.contains("MrtTerrainPass.prepareGbufferViews()"),
                "先备好池再取视图（尺寸/槽数在这里定）");
        assertEquals(-1, takeover.indexOf("MrtTerrainPass.poolView("),
                "poolView 给的是**被读那一代**（后处理链用）：拿它当附件 = 同一帧既读又写同一张图");
    }

    @Test
    @DisplayName("GAP-052：改进透明（OIT）开着 ⇒ 形状对得上也不接管，且这条拒绝不混进「判据不匹配」的红灯")
    void improvedTransparencyBlocksTakeover() {
        assertFalse(MainPassTakeover.takeoverAllowed(1, "Main", true),
                "🔴 OIT 那一族全程以 gameRenderer.mainRenderTarget() 为画布/深度源（最后 \"OIT Composite\""
                        + " 写它的颜色），而接管后的主 pass 写进池子 ⇒ 池子成孤儿、谁都不抛（静默错）⇒ 这一档不接管");
        // 🔑 开 OIT 时那条 pass 的 label 不是 "Main" 而是 "Solid"（LevelRenderer:458 的三元式）⇒
        //    这一行同时钉住两件事：形状判据认得 "Solid"（label 表覆盖三元式两边），而 OIT 闸门确实拦得住它
        //    （否则这条守卫在实际运行里永远不会触发 = 死码）。
        assertTrue(MainPassTakeover.matchesMainPass(1, "Solid"), "label 表要覆盖 :458 三元式的两边");
        assertFalse(MainPassTakeover.takeoverAllowed(1, "Solid", true),
                "这一档真机上会以 label='Solid' 走到这里 ⇒ 必须走「OIT 拒绝」而不是「判据不匹配」那条红灯");
        assertTrue(MainPassTakeover.takeoverAllowed(1, "Main", false), "关着 ⇒ 与今天签过的那几臂一致");
        assertFalse(MainPassTakeover.takeoverAllowed(2, "Main", false), "多附件的那条本来就不是主 pass（⑤-a 判据）");
        assertFalse(MainPassTakeover.takeoverAllowed(1, "Water mask", false), "label 不在表内（OIT 自己那条 pass）");

        String takeover = readOrSkip(TAKEOVER);
        assertTrue(takeover.contains("STATS.refusedOit++") && takeover.contains("oitRefusalCount()"),
                "这一族必须计数 + 读数拿得到（口径同 GAP-046 的退路分支：退路不许静默）");
        assertTrue(takeover.contains("reportedOitFork"), "首次点名一条就够（节流纪律）");
        // 🔖 只在 takeoverAllowedFor <b>方法体</b>内比顺序：warnIfNeverMatched() 在 attachmentReplaced 里
        //    还有别的调用点（拿不到 target0 那一支），整文件比位置会被它先命中。
        String gate = takeover.substring(takeover.indexOf("private static boolean takeoverAllowedFor("));
        // 🔴 先钉「两个针都在」，再比顺序：needle 消失时 indexOf 给 -1，而 -1 < 任何下标 ⇒ 顺序断言会**假绿**
        assertTrue(gate.contains("refuseOitFork(label);") && gate.contains("warnIfNeverMatched();"),
                "两条拒绝都得还在方法体里（改调用形状时要同步改这两根针）");
        assertTrue(gate.indexOf("refuseOitFork(label);") < gate.indexOf("warnIfNeverMatched();"),
                "两条红灯不许混：「形状对但被 OIT 挡」≠「判据不匹配（label 改名）」——"
                        + "后者才该怀疑注入失效");
    }

    @Test
    @DisplayName("⑤-e 第一格：深度附件换不换由三态纯函数给；要求换但视图没建出来 ⇒ 退回不换且必须点名（X11 不许静默降级）")
    void depthAttachmentSwapDecision() {
        assertEquals(MainPassTakeover.DEPTH_KEEP, MainPassTakeover.depthDecision(false, true),
                "键关着 ⇒ 逐字今天的形状（只换颜色）——默认档必须零变化");
        assertEquals(MainPassTakeover.DEPTH_KEEP, MainPassTakeover.depthDecision(false, false),
                "关着时连视图都不该去取");
        assertEquals(MainPassTakeover.DEPTH_SWAP_TO_GBUFFER, MainPassTakeover.depthDecision(true, true));
        assertEquals(MainPassTakeover.DEPTH_SWAP_REFUSED_NO_VIEW, MainPassTakeover.depthDecision(true, false),
                "要换但池的深度那一刻还不存在 ⇒ 走「拒绝」那一格，不许悄悄当没发生");

        String takeover = readOrSkip(TAKEOVER);
        assertTrue(takeover.contains("MRT_TAKEOVER_DEPTH_TO_GBUFFER"),
                "这一挡必须有独立开关（X29），而且要能在同一趟里 A/B（每帧现读）");
        assertTrue(takeover.contains("noteDepthViewUnavailable();") && takeover.contains("深度退回="),
                "退路要计数 + 进自报：否则「深度没换」这件事在日志里是隐形的");
    }
}
