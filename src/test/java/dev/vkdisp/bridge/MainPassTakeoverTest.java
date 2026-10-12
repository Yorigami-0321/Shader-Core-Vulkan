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
    @DisplayName("判据：单附件 + label ∈ {Main,Solid} + 那个附件就是主目标颜色视图 —— 三条缺一即不接管")
    void predicateIsAnAndOfTheThreeSignals() {
        assertTrue(MainPassTakeover.matchesMainPass(1, "Main", true), "原版非 OIT 主 pass（LevelRenderer:458）");
        assertTrue(MainPassTakeover.matchesMainPass(1, "Solid", true), "OIT 开时同一条 pass 换了 label");
        assertFalse(MainPassTakeover.matchesMainPass(1, "Item in hand", true),
                "GameRenderer:412 也是「单附件 + main 的颜色」，只靠视图身份分不开 ⇒ label 必须参与");
        assertFalse(MainPassTakeover.matchesMainPass(1, "Sky", true), "天空那条同样挂 main 的颜色视图");
        assertFalse(MainPassTakeover.matchesMainPass(2, "Main", true),
                "我方自己的 MRT pass 就是多附件：这条挡在它和我之间（选内层挂点后它本就不路过，别把双保险删了）");
        assertFalse(MainPassTakeover.matchesMainPass(1, "Main", false),
                "视图不是主目标 ⇒ 不是那条 pass（地址不同的同 label pass）");
        String labelUnavailable = null;
        assertFalse(MainPassTakeover.matchesMainPass(1, labelUnavailable, true),
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
}
