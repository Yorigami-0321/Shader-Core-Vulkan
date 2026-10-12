package dev.vkdisp.render;
/**
 * 【参考调研】渲染路径接线纪律守卫（h48）
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓库自有 `evidence/h48-flicker-and-readback.md`
 *    （一条 gate 把「链是否启用」与「地形何时画」捆在一起 ⇒ 一臂以为在测链、实际在跑旧三步链）。
 *    无第三方代码，许可证：本项目 MIT。
 * 1. 官方/主实现：无（这是本项目自己的接线纪律）。
 * 2. 备选：无。
 * 3. 差异点：把两件「静默错判」做成构建期红灯 ——
 *    ① 链的执行路径被无关开关（`afterLevel`）连带关掉；
 *    ② 每帧走哪条路在日志里不自报（判读对象不声明自己是谁）。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 单测（扫源码）。
 */
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「跑的是哪条渲染路径」必须有接线级保证。
 *
 * <p>🔖 守的全是**静默错判**：不崩、不报错、画面也像回事，但那一臂测的根本不是你以为的东西。
 */
class RenderRouteWiringTest {

    private static final Path HOOK = Path.of("src/main/java/dev/vkdisp/render/FullscreenPassHook.java");
    private static final Path MRT = Path.of("src/main/java/dev/vkdisp/bridge/MrtTerrainPass.java");
    private static final Path UNIFORMS = Path.of("src/main/java/dev/vkdisp/render/OfUniformManager.java");

    private static String readOrSkip(Path path) {
        assumeTrue(Files.exists(path), "工程文件缺失: " + path);
        try {
            return Files.readString(path);
        } catch (java.io.IOException e) {
            throw new AssertionError("读取失败: " + path, e);
        }
    }

    @Test
    @DisplayName("🔴 链的执行只看 chainActive，且「补画地形」的调用必须整个消失（两个轴各一个轴，A/B 车道不许复活）")
    void chainDispatchIsNotGatedOnAfterLevel() {
        String hook = readOrSkip(HOOK);
        int gate = hook.indexOf("if (chainActive) {");
        assertTrue(gate > 0, "链的执行必须只看 chainActive（`if (chainActive) {`）——"
                + "h48 实测：gate 里带 afterLevel() ⇒ 关诊断档会连带把整条链关掉，"
                + "于是那一臂跑的是旧三步链而画面像「链生效」，我据此下过一次错结论");
        int call = hook.indexOf("FrameApi.drawPostChain(");
        assertTrue(call > gate, "链必须在 gate 里执行");
        String region = hook.substring(gate, call);
        // 🔖 只看**调用**，不看注释：h48 那段的说明文字里仍要写「条件里带 afterLevel()」，
        //   所以这里的针是「补画地形」那两个方法名 —— 它们才是那条 A/B 车道的实体。
        assertFalse(region.contains("MrtTerrainPass.drawAfterLevel") || region.contains("paintGbufferAndTerrain"),
                "从 `if (chainActive)` 到 drawPostChain 之间不得再出现「补画地形」—— 地形现在只在我方"
                        + "帧图 pass 里画一次（M-13 的 requires 边决定它在链之前），R5 已退役 "
                        + "`mrt.terrainAfterLevel` 那条车道");
        assertFalse(hook.contains("MrtTerrainPass.afterLevel"),
                "AfterLevel 档的读取点必须清零：留一个恒真的判档 = 下一条车道又长回来");
        assertFalse(hook.contains("paintGbufferAndTerrain"),
                "gbuffer 挂点这个 helper 应随车道退役整个删除（它存在的唯一理由就是「按档补画」）");
    }

    @Test
    @DisplayName("🔖 R4/R5：天空交回原版 \"sky\" pass（钩子 + 视图 @Redirect），我方既不再插 sky pass、也不在任何钩子里补画")
    void skyIsHandedOffToTheVanillaSkyPass() {
        String hook = readOrSkip(HOOK);
        assertFalse(hook.contains("SkyIntoGbuffer.render()"),
                "钩子里不得再自己画天空：天空现在落在原版 \"sky\" pass 体内 ⇒ 这里再画一次"
                        + "就是每帧两遍天空 + 多翻一代（GAP-018 的代次账会算错）");

        String mrt = readOrSkip(MRT);
        int install = mrt.indexOf("SkyIntoGbuffer.installHook();");
        int terrainInsert = mrt.indexOf("addPass(\"vkdisp_gbuffer_terrain\")");
        assertTrue(install > 0 && install < terrainInsert,
                "钩子必须在装配期、创建我方地形 pass **之前**装：它决定的是「原版 sky pass 里由谁画天空」，"
                        + "而那一刻已经不可能再补一次（帧图执行期才开始画）");
        assertFalse(mrt.contains("addPass(\"vkdisp_gbuffer_sky\")"),
                "我方那个 sky pass 应随 R4 删除：留着它 = 每帧两遍天空，而 GAP-039 的四道门控又回到没人判");
        assertFalse(mrt.contains("pass.requires(skyPass);"),
                "requires 的目标现在由 PassOrder 交成**原版**的 \"sky\" 句柄（M-13），不再是自插的 skyPass");
        assertTrue(terrainInsert > 0 && mrt.indexOf("PassOrder.declareOwnTerrain(pass)") > terrainInsert,
                "h48g 实测：插入序不是依赖，执行序由 resolvePassOrder 按 requires 边解析 ⇒ "
                        + "\"terrain requires 原版 sky\" 这条边必须在创建我方 pass 之后声明");

        int prePainted = mrt.indexOf("private static boolean skyPrePainted(int slot)");
        assertTrue(prePainted > 0, "地形那一槽「天空有没有先写」的判定必须仍然存在");
        String gate = mrt.substring(prePainted, mrt.indexOf("\n    }", prePainted));
        assertTrue(gate.contains("SkyIntoGbuffer.paintedWriteGeneration()"),
                "GAP-039 的收口形状：LOAD 与否要读「本帧天空真写过没有」这个数据，"
                        + "不能读 mrt.skyPass（配置反推 ⇒ 岩浆/遮天/Skybox.NONE 里没人写却照样 LOAD，"
                        + "于是两帧前的旧天空又进画面）");
        assertFalse(gate.contains("MRT_SKY_PASS"),
                "skyPrePainted 里不许再出现配置反推（理由同上一条判据）");
    }

    @Test
    @DisplayName("🔖 每帧走哪条路必须自报（且只在变了时打）")
    void routeIsSelfReported() {
        String hook = readOrSkip(HOOK);
        assertTrue(hook.contains("[route]"), "渲染路径必须在日志里自报（chain=true/false + 走哪条路）");
        assertTrue(hook.contains("if (!route.equals(lastRoute))"),
                "自报必须按「决策变了」去重 —— 否则就是 h33 那 2702 行的形状");
    }

    @Test
    @DisplayName("🔴 gbufferPrevious* / previousCameraPosition 必须有真值来源（不许再恒 0）")
    void previousFrameBuiltinsAreSupplied() {
        String src = readOrSkip(UNIFORMS);
        for (String name : new String[] {"gbufferPreviousModelView", "gbufferPreviousProjection",
                "previousCameraPosition"}) {
            assertTrue(src.contains("values.put(\"" + name + "\""),
                    name + " 必须由 gather() 供上一帧值 —— 恒 0 会让包把「上一帧」当成"
                            + "「相机在原点 + 单位矩阵」，运动向量 = 整屏假位移");
        }
        assertTrue(src.contains("worldSwitch || previousView == null"),
                "换世界时历史必须与当帧对齐（跨世界的旧相机当历史没有意义）");
    }
}
