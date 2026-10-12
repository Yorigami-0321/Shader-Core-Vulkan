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
    @DisplayName("🔴 链的执行不得被 afterLevel 连带关掉（两个轴各自独立）")
    void chainDispatchIsNotGatedOnAfterLevel() {
        String hook = readOrSkip(HOOK);
        int gate = hook.indexOf("if (chainActive) {");
        assertTrue(gate > 0, "链的执行必须只看 chainActive（`if (chainActive) {`）——"
                + "h48 实测：gate 里带 afterLevel() ⇒ 关诊断档会连带把整条链关掉，"
                + "那一臂跑的是旧三步链，却被当成「链生效」下了结论");
        int call = hook.indexOf("FrameApi.drawPostChain(", gate);
        assertTrue(call > gate, "chainActive 分支里必须真的调用 drawPostChain");
        assertFalse(hook.substring(gate, call).contains("afterLevel"),
                "从 `if (chainActive)` 到 drawPostChain 之间不得再出现 afterLevel —— 地形何时画是"
                        + "另一个轴（它只决定挂点，而挂点已抽成 paintGbufferAndTerrain()，"
                        + "结构上就与链的条件分开了）");
        assertTrue(hook.contains("private static void paintGbufferAndTerrain()"),
                "gbuffer 挂点必须是独立方法；把 afterLevel 写回链的条件里就是 h48 的那次错判");
    }

    @Test
    @DisplayName("🔖 R4：天空交回原版 \"sky\" pass（钩子 + 视图 @Redirect），我方不再插 sky pass、也不再在 AfterLevel 补画")
    void skyIsHandedOffToTheVanillaSkyPass() {
        String hook = readOrSkip(HOOK);
        assertFalse(hook.contains("SkyIntoGbuffer.render()"),
                "AfterLevel 档不得再自己画天空：天空现在落在原版 \"sky\" pass 体内，两档共用同一路 ⇒ "
                        + "这里再调一次就是每帧两遍天空 + 多翻一代（GAP-018 的代次账会算错）");
        String helper = hook.substring(hook.indexOf("private static void paintGbufferAndTerrain() {"));
        assertTrue(helper.indexOf("MrtTerrainPass.drawAfterLevel()") > 0,
                "gbuffer 挂点仍必须只负责地形（链的条件与地形的挂点各一个轴，h48 的成因）");
        assertTrue(hook.indexOf("paintGbufferAndTerrain();") < hook.indexOf("FrameApi.drawPostChain("),
                "地形不许排在链**之后**：链跑完 colortex0 就是输出图了，之后画进去的东西没人读");

        String mrt = readOrSkip(MRT);
        int install = mrt.indexOf("SkyIntoGbuffer.installHook();");
        int afterLevelReturn = mrt.indexOf("if (afterLevel()) {");
        assertTrue(install > 0 && afterLevelReturn > install,
                "钩子必须在 afterLevel 的早退**之前**装：它装的是「原版 sky pass 里由谁画天空」，"
                        + "与地形在哪一档画是两个轴（A/B 档同样要天空进 gbuffer）");
        assertFalse(mrt.contains("addPass(\"vkdisp_gbuffer_sky\")"),
                "我方那个 sky pass 应随 R4 删除：留着它 = 每帧两遍天空，而 GAP-039 的四道门控又回到没人判");
        assertFalse(mrt.contains("pass.requires(skyPass);"),
                "requires 的目标现在由 PassOrder 交成**原版**的 \"sky\" 句柄（M-13），不再是自插的 skyPass");
        int terrainInsert = mrt.indexOf("addPass(\"vkdisp_gbuffer_terrain\")");
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
