package dev.vkdisp.bridge;
/**
 * 【参考调研】GAP-055 的接线纪律守卫：自定义纹理的上传**只许发生在开 pass 之前**
 * 0. 合规核对（第 0 步闸门）：参考对象 = ① 原版 26.3
 *    {@code com.mojang.renderpearl.frontend.FrontendCommandEncoder#writeToTexture} 的前置条件
 *    （render pass 开着时新建 encoder 抛 {@code Close the existing render pass…}，Mojang EULA，
 *    只读行为事实、零代码复制）；② 本仓库 {@code ShadowStubs} 已经付过学费的同一条纪律
 *    （「阴影桩刻意不懒建 ⇒ 帧首建桩点」）与 {@code PixelProbeWiringTest} 的源码文本断言法。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以（本测试只读源码文本，不搬运任何代码）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：无（本项目自定的接线纪律）。
 * 2. 备选：① 运行时判「有 pass 开着就不写」并在 pass 内懒建 —— <b>否决</b>：那是把每帧异常
 *          换成每帧日志，噪声图那一帧仍然是缺席的；② 只在 {@code drawPostChain} 里预热 ——
 *          <b>否决</b>：默认档（{@code mrt.terrain=false} 且链不跑 ⇒ 旧三步链）到不了那里，
 *          这正是 GAP-055 的实测形状。
 * 3. 我们的差异点：把「帧首预热点被删掉」「{@code view()} 又被改回懒建」这两条**静默失效**
 *    做成构建期红灯 —— 两者都不崩启动，只在真机上刷出每帧一条异常（实测一趟 106 998 条）。
 * 4. 许可证核对：本项目 LGPL-3.0-or-later；零第三方代码复制。
 * 5. 性能基线：❄️ 单测（扫源码），不进渲染路径。
 */
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GAP-055：内置/自定义纹理的创建点与帧首预热点。
 *
 * <p>🔖 守的是同一种形状：<b>不崩、日志看着正常，但每帧在 pass 体内抛一条异常</b>。
 */
class PackTextureWarmupWiringTest {

    private static final Path HOOK = Path.of("src/main/java/dev/vkdisp/render/FullscreenPassHook.java");
    private static final Path TEX = Path.of("src/main/java/dev/vkdisp/bridge/PackTextures.java");

    private static String readOrSkip(Path path) {
        Assumptions.assumeTrue(Files.exists(path), "工程文件缺失: " + path);
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new AssertionError("读取失败: " + path, e);
        }
    }

    /** 只统计非注释行里的出现（javadoc 会引用代码字样，GAP-055 的教训正是「注释不算证据」）。 */
    private static int countCode(String text, String literal) {
        int count = 0;
        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                continue;
            }
            count += line.split(java.util.regex.Pattern.quote(literal), -1).length - 1;
        }
        return count;
    }

    @Test
    @DisplayName("🔴 帧首（开 pass 之前）必须预热自定义纹理，且早于本帧任何一条 pass 绘制")
    void warmUpHappensBeforeAnyPassOpens() {
        String hook = readOrSkip(HOOK);
        int warm = hook.indexOf("PackTextures.ensureReady();");
        assertTrue(warm > 0,
                "GAP-055：FullscreenPassHook 帧首必须有 PackTextures.ensureReady() —— "
                        + "默认档（mrt.terrain=false 且链不跑）不经过 drawPostChain / MrtTerrainPass / "
                        + "SkyPackGbuffer 那三个调用点，缺了这一行就没人预热");
        int chain = hook.indexOf("FrameApi.drawPostChain(");
        int fullscreen = hook.indexOf("FrameApi.drawFullscreen(");
        assertTrue(chain > warm, "预热必须早于 drawPostChain，否则链解析采样器时仍在 pass 体内建纹理");
        assertTrue(fullscreen > warm, "预热必须早于 drawFullscreen（旧三步链那一档靠它）");
        assertTrue(countCode(hook, "PackTextures.ensureReady();") == 1,
                "帧首预热点只许一处 —— 多处会把「哪一帧建的」重新变成猜");
    }

    @Test
    @DisplayName("🔴 view() 只读不建：唯一的创建点在 ensureReady 体内（懒建 = pass 内抛）")
    void viewNeverCreatesTheBuiltinNoise() {
        String tex = readOrSkip(TEX);
        assertTrue(countCode(tex, "builtinNoiseView();") == 1,
                "GAP-055：builtinNoiseView() 的调用点必须恰好一处；实测 "
                        + countCode(tex, "builtinNoiseView();") + " 处");
        int ensureReady = tex.indexOf("public static void ensureReady()");
        int loadAll = tex.indexOf("private static Map<String, TextureTarget> loadAll()");
        int call = tex.indexOf("builtinNoiseView();");
        assertTrue(ensureReady > 0 && loadAll > ensureReady,
                "找不到 ensureReady / loadAll 的边界，断言口径失效");
        assertTrue(call > ensureReady && call < loadAll,
                "唯一的创建调用必须在 ensureReady() 体内（帧首、开 pass 之前），实测 call=" + call
                        + " ensureReady=" + ensureReady + " loadAll=" + loadAll);
        int view = tex.indexOf("public static @Nullable GpuTextureView view(");
        assertTrue(view > 0, "view() 签名变了，本断言的锚点需要跟着改");
        assertTrue(call < view, "view()（pass 体内被调）里不许出现创建调用");
    }
}
