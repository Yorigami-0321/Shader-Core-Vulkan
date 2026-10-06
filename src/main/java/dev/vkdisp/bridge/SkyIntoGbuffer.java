package dev.vkdisp.bridge;
/**
 * 【自行补充】GAP-003 非地形 gbuffer 程序线 · 第一步：**把天空画进 colortex0**（h48）。
 *
 * <p><b>为什么走这条路</b>（源码级核实 26.3.0.51-beta，全部公开 API，零 mixin / 零 M-04）：
 * <ul>
 *   <li>{@code SkyRenderer} 的构造器与 {@code render(GpuBufferSlice, SkyRenderState)} 都是 public；</li>
 *   <li>它**自己建** render pass，附件取 {@code renderTarget.getColorTextureView()}，
 *       且颜色是 **LOAD** 语义（{@code Optional.empty()}）⇒ 给它一个指向我方 colortex 的
 *       {@link GbufferTarget}，天空就落在 gbuffer 里 —— 正是 OF 里 {@code gbuffers_skybasic}
 *       的落点（BSL 的 {@code composite1} 只是把 colortex0 透传，所以天空**必须**先在 gbuffer 里）；</li>
 *   <li>{@code Minecraft#getTextureManager()} / {@code #getAtlasManager()} 都是 public。</li>
 * </ul>
 *
 * <p>🔖 <b>为什么天空写「被读那一代」而不是待写那一代</b>（GAP-018 的边界）：
 * 双代轮转只服务于「同一个 pass 既读又写同一张图」的情形。天空 pass 不采 colortex0，
 * 它是**叠加**在刚画好的地形上（LOAD 语义），所以必须打在**当前被读的那一代**上，
 * 并且**不翻代** —— 打错代 = 天空叠在两帧前的陈旧内容上。
 *
 * <p>⚠️ 本轮仍未实测的三点（打开 {@code mrt.skyPass} 后按判据逐条量，见 GAP-003 登记行）：
 * ① 深度附件的第 5 参 {@code OptionalDouble.empty()} 是「不清」还是「清成 1.0」
 *    （若清深度 ⇒ 地形深度被抹 ⇒ 链把全屏当天空）；② {@code RenderSystem.getShaderFog()}
 *    在本调用点是否是原版那一份雾切片；③ 换世界 / 换尺寸时实例重建。
 */
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.vkdisp.VkDisp;
import dev.vkdisp.VkDispConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.jspecify.annotations.Nullable;

/** 天空 → colortex0 的重放器（渲染线程独占，无锁）。 */
public final class SkyIntoGbuffer {

    private static @Nullable RenderTarget target;
    private static @Nullable SkyRenderer renderer;
    private static int targetWidth;
    private static int targetHeight;
    private static boolean readyLogged;
    private static java.util.Set<String> skipLogged = new java.util.HashSet<>();

    /** 自己持有的天空状态（不去改原版那一份共享状态）。 */
    private static final SkyRenderState skyState = new SkyRenderState();

    private SkyIntoGbuffer() {
    }

    /** 链跑之前调用一次（开关关 / 前提不满足 ⇒ 静默跳过，但每种原因自报一次）。 */
    public static void render() {
        if (!VkDispConfig.MRT_SKY_PASS.get()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            skipOnce("not-in-world");
            return;
        }
        GpuTextureView color = MrtTerrainPass.poolView(0);
        GpuTextureView depth = MrtTerrainPass.depthView();
        if (color == null || depth == null) {
            skipOnce("gbuffer-view-null");
            return;
        }
        GpuBufferSlice fog = RenderSystem.getShaderFog();
        if (fog == null) {
            skipOnce("fog-slice-null");
            return;
        }
        int width = color.getWidth(0);
        int height = color.getHeight(0);
        if (renderer == null || targetWidth != width || targetHeight != height) {
            rebuild(mc, color, depth, width, height);
        }
        LevelRenderState levelState = mc.gameRenderer.gameRenderState().levelRenderState;
        // 🔖 状态必须**自己抽**：原版是在帧图装配期对它自己的 `skyRenderer` 调
        //   `extractRenderState(...)` 填 `skyRenderState`，而我们在 AfterLevel 才跑 ——
        //   实测直接借用共享状态会拿到 `skyColor == null` ⇒ SkyRenderer.renderSkyDisc
        //   在 `new Vector4f(v)` 处 NPE（h48 S1-sky 臂逐字）。用自己的 state 对象，
        //   不去改原版那一份（改了会污染原版天空 pass）。
        renderer.extractRenderState(mc.level, levelState.worldPartialTicks,
                mc.gameRenderer.mainCamera(), skyState);
        renderer.render(fog, skyState);
        TargetReadback.probeAfterSky();
    }

    private static void rebuild(Minecraft mc, GpuTextureView color, GpuTextureView depth,
            int width, int height) {
        if (renderer != null) {
            renderer.close();
        }
        target = new GbufferTarget("vkdisp gbuffer colortex0 (sky writer)", color, depth);
        renderer = new SkyRenderer(mc.getTextureManager(), mc.getAtlasManager(), target);
        targetWidth = width;
        targetHeight = height;
        if (!readyLogged) {
            readyLogged = true;
            VkDisp.LOGGER.info("vkdisp: [GAP-003/sky] 天空重放器就绪：目标 = colortex0 {}x{}（LOAD 语义，"
                    + "不翻代）；开关 mrt.skyPass", width, height);
        } else {
            VkDisp.LOGGER.info("vkdisp: [GAP-003/sky] 天空重放器重建（换尺寸或换世界）：{}x{}", width, height);
        }
    }

    /** 每种跳过原因只报一次（热路径日志 I/O 纪律；但「为什么没画」必须可见，X11）。 */
    private static void skipOnce(String reason) {
        if (skipLogged.add(reason)) {
            VkDisp.LOGGER.info("vkdisp: [GAP-003/sky] 本帧跳过天空重放（原因={}）", reason);
        }
    }
}
