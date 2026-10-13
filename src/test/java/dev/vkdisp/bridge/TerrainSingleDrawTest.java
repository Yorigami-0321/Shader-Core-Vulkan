package dev.vkdisp.bridge;
/**
 * 【自行补充】R7 ⑤-d 第一刀（关掉地形双绘）的形状守卫 / 只读工程自身文件
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓库 {@code bridge/TerrainSingleDraw.java}、
 *    {@code bridge/MrtTerrainPass.java}、{@code VkDispConfig.java}（本项目自有码/文档，MIT）。
 *    → 可并入本项目（MIT）：本文件不引用任何外部代码。
 * 1. 官方/主实现：无。
 * 2. 备选：直接调 {@code TerrainSingleDraw.drawnByMainPass()} 断言真假 —— <b>否决</b>：它第一句就读
 *    {@code VkDispConfig}（FML 的 {@code ModConfigSpec}），而测试运行时没有 FML（{@code GAP-050} 那一族，
 *    第三次撞到之后已经登记）⇒ 一调就 {@code NoClassDefFoundError}。
 *    ⇒ 本文件的职责是<b>形状</b>：两笔 draw 都被同一个判定挡住、判定要两个条件、开关默认关。
 *    「跳过之后画面还有没有地形」是<b>真机判据</b>，不假装单测覆盖了它。
 * 3. 我们的差异点：钉住「不许只看配置键就跳过」这条安全边界 —— 那是能把屏幕画空的操作。
 * 4. 许可证核对：本项目 MIT。
 * 5. 性能基线：❄️ 单测。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⑤-d 第一刀的形状守卫：跳过点、双条件、默认档。
 */
class TerrainSingleDrawTest {

    private static final Path SINGLE_DRAW =
            Path.of("src/main/java/dev/vkdisp/bridge/TerrainSingleDraw.java");
    private static final Path MRT =
            Path.of("src/main/java/dev/vkdisp/bridge/MrtTerrainPass.java");
    private static final Path TAKEOVER =
            Path.of("src/main/java/dev/vkdisp/bridge/MainPassTakeover.java");
    private static final Path CONFIG =
            Path.of("src/main/java/dev/vkdisp/VkDispConfig.java");

    private static String readOrSkip(Path path) {
        Assumptions.assumeTrue(Files.exists(path), "工程文件缺失（不在工程根目录运行？）: " + path);
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new AssertionError("读取失败: " + path, e);
        }
    }

    @Test
    @DisplayName("两组地形 draw 都被同一个判定挡住（OPAQUE + TRANSLUCENT 各一处，不是一处管两处）")
    void bothTerrainGroupsAreGated() {
        String mrt = readOrSkip(MRT);
        int gates = mrt.split("if \\(!TerrainSingleDraw\\.drawnByMainPass\\(\\)\\) \\{", -1).length - 1;
        assertEquals(2, gates,
                "🔴 双绘是<b>两笔</b> renderGroup（OPAQUE 与 TRANSLUCENT/水，GAP-027 那一刀接上的）"
                        + "⇒ 只挡一笔会留下「固体画两遍、水画一遍」这种更难归因的形状");
        assertTrue(mrt.contains("draws.renderGroup(ChunkSectionLayerGroup.OPAQUE, renderPass"),
                "被挡的那两笔本身不许改语义（还是原来那组、原来那个 pass）");
        assertTrue(mrt.contains("DepthSnapshots.take(DepthSnapshots.OPAQUE"),
                "⚠️ 本刀只跳 draw：<b>不</b>跳深度快照 —— 快照归 ⑤-e 处理，一起动就分不开归因");
    }

    @Test
    @DisplayName("跳过要两个条件（开关 ∧ 真接管）；只看键就能把屏幕画空")
    void skipRequiresTakeoverNotJustFlag() {
        String gate = readOrSkip(SINGLE_DRAW);
        String body = gate.substring(gate.indexOf("public static boolean drawnByMainPass()"));
        assertTrue(body.contains("VkDispConfig.MRT_TERRAIN_SINGLE_DRAW.get()"), "第一条件：可关闭键");
        assertTrue(body.contains("MainPassTakeover.takeoverHappened()"),
                "第二条件：主 pass 真被我方接管 —— 没有它，跳过就等于谁都不画地形（空屏）");
        // 🔖 只在<b>方法体</b>内比顺序：类头 javadoc 里也提到 takeoverHappened()，
        //    整文件比位置会被注释先命中（本仓守卫反复踩过的那类假阳性）。
        assertTrue(body.indexOf("MRT_TERRAIN_SINGLE_DRAW.get()") < body.indexOf("takeoverHappened()"),
                "顺序也要成立：先便宜地判开关，再判接管");
        assertTrue(gate.contains("LOGGER.warn") && gate.contains("flagWithoutTakeover"),
                "「键开着但没接管」必须 WARN 一次并计数 —— 那是配错的取证档，不许静默按今天画");
        assertTrue(gate.contains("return false;"), "这一情形下照今天画（安全侧）");

        String takeover = readOrSkip(TAKEOVER);
        assertTrue(takeover.contains("public static boolean takeoverHappened()"),
                "接管与否要能从数据问出来（读的是记下的那条 pass 对象，不是标志位）");
        assertTrue(takeover.contains("STATS.takenOverPass != null"),
                "判据就是「那条被接管的 pass 对象还在不在」");
    }

    @Test
    @DisplayName("默认档：键不存在地为 false ⇒ 逐字等于今天（双绘仍在），且跳过有读数")
    void defaultRouteIsUnchangedAndSkipIsObservable() {
        String config = readOrSkip(CONFIG);
        assertTrue(config.contains(".define(\"mrt.terrainSingleDraw\", false)"),
                "X28/X29：新行为必须可关闭，且<b>默认关</b> —— 默认档必须逐字等于今天");
        String gate = readOrSkip(SINGLE_DRAW);
        assertTrue(gate.contains("STATS.skippedGroups++") && gate.contains("skippedGroupCount()"),
                "跳过要数得出来（T10）：否则「地形不见了」分不清是 ⑤-d 生效还是主 pass 那一笔没走到");
        assertTrue(gate.contains("reportedSkip"), "首次跳过点名一次（节流纪律，同 M-01/h34 那一课）");
        assertTrue(gate.contains("判据"), "自报里要写清下一步看什么（缺地形=主 pass 那一笔没走到/晚一帧）");
    }
}
