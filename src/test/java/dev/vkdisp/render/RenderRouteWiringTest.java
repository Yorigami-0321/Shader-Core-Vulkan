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
    @DisplayName("🔖 天空必须排在地形之前（h48e：地形深度裁不住原版天空）")
    void skyIsDispatchedBeforeTerrain() {
        String hook = readOrSkip(HOOK);
        // 只看 helper **体内**的次序：onAfterLevel 末尾还有一条「旧三步链档的地形兜底」，
        // 全文 indexOf 会先撞上它，于是这条守卫会在与顺序无关的改动上误红（本轮实踩过）。
        int helper = hook.indexOf("private static void paintGbufferAndTerrain() {");
        assertTrue(helper > 0, "gbuffer 挂点必须是独立方法（链的条件里不许掺 afterLevel）");
        String body = hook.substring(helper);
        int sky = body.indexOf("SkyIntoGbuffer.render()");
        int terrain = body.indexOf("MrtTerrainPass.drawAfterLevel()");
        assertTrue(sky > 0 && terrain > 0, "AfterLevel 档里天空重放与地形重放都要出现");
        assertTrue(sky < terrain,
                "AfterLevel 档的顺序必须是「天空先铺满 colortex0 → 地形 LOAD 盖上去」。h48e 实测："
                        + "反过来的话原版天空盘把刚画好的地形整片盖掉（gbuffer 深度与空白私有深度"
                        + "两臂的 c0@afterSky 逐位相同 = 全屏覆盖），链读到的就是纯天空色");
        int chainCall = hook.indexOf("FrameApi.drawPostChain(");
        int callSite = hook.indexOf("paintGbufferAndTerrain();");
        assertTrue(callSite > 0 && chainCall > callSite,
                "天空不许排在链**之后**：链跑完 colortex0 就是输出图了，之后画进去的东西没人读"
                        + "（判据 = 挂点的**调用点**在 drawPostChain 之前）");
        assertTrue(body.contains("if (!MrtTerrainPass.afterLevel())"),
                "AfterLevel 档的天空挂点必须由 helper 自己判档：帧图档的天空已经在帧图里排好了，"
                        + "钩子里再画一次就是每帧两遍天空 + 多翻一代（GAP-018 的代次账会算错）");

        String mrt = readOrSkip(MRT);
        int skyInsert = mrt.indexOf("addPass(\"vkdisp_gbuffer_sky\")");
        int terrainInsert = mrt.indexOf("addPass(\"vkdisp_gbuffer_terrain\")");
        assertTrue(skyInsert > 0 && terrainInsert > 0, "帧图档两条 pass 都要在");
        assertTrue(mrt.indexOf("pass.requires(skyPass)") > terrainInsert,
                "帧图档的顺序**必须靠 requires 声明**，不能靠插入序。h48g 实测：执行序由 "
                        + "FrameGraphBuilder#resolvePassOrder 按资源依赖解析，插入序不是依赖 "
                        + "⇒ 只按顺序 addPass 时天空跑到地形之后，又被盖回去（c0@afterSky=0.0611）");
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
