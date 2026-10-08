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
 * <p>🔴 <b>时序：天空在地形之前</b>（h48e 改的，理由全是实测）：
 * 原本「地形后补天空」指望 {@code colortexDepth} 里的地形深度把天空裁成「只有空的
 * 那片」。两臂交叉否掉了这个指望 —— 挂 gbuffer 深度与挂**私有空白深度**的
 * {@code c0@afterSky} 读数<b>逐位相同</b>（都是 81.3139），而空白深度下天空必然铺满全屏
 * ⇒ 挂 gbuffer 深度那一臂<b>也</b>铺满了全屏 ⇒ 天空把刚画好的地形整片盖掉。
 * 原版自己就是「天空先画、地形后盖」（{@code LevelRenderer} 的序列），OF/Iris 的
 * gbuffer 顺序同样是 skybasic → terrain ⇒ 现在照这个来：
 * <b>先</b>清 colortex0 的待写代并让天空铺满，<b>再</b>让地形 pass 以 LOAD 语义盖上去。
 *
 * <p>🔖 <b>为什么写「待写那一代」</b>：GAP-018 的双代轮转里，地形 pass 写 {@code 1 - cur}
 * 然后翻代。天空必须写<b>同一代</b>，否则翻代后地形一盖，天空就被丢进上一代作废。
 *
 * <p>🔴 挂点有两个，<b>两档都必须排在地形之前</b>，而且顺序靠<b>声明</b>不靠插入序：
 * <ul>
 *   <li>帧图档（取证一直用的那一档，{@code terrainAfterLevel=false}）=
 *       {@code MrtTerrainPass#onFrameGraphSetup} 插 {@code vkdisp_gbuffer_sky}，
 *       并让地形 pass {@code requires(skyPass)}；</li>
 *   <li>AfterLevel 档 = {@code FullscreenPassHook#paintGbufferAndTerrain} 先本类、后地形重放。</li>
 * </ul>
 * 🔖 为什么必须显式 {@code requires}：h48g 实测「先插 sky 再插 terrain」<b>不保证执行序</b> ——
 * 帧图按资源依赖解析，插入序不是依赖 ⇒ sky 跑到 terrain <b>之后</b>，天空又被地形盖回去
 * （{@code c0@afterSky} 只剩 0.0611，而正确顺序下是 72.85~84.50）。
 * ✅ {@code FramePass#requires(FramePass)} 是公开接口方法（本轮源码级核实，见 {@code 06-MIGRATION} V5）。
 */
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.vkdisp.VkDisp;
import dev.vkdisp.VkDispConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.jspecify.annotations.Nullable;
import org.joml.Vector4f;
import java.util.Optional;
import java.util.OptionalDouble;

/** 天空 → colortex0 的重放器（渲染线程独占，无锁）。 */
public final class SkyIntoGbuffer {

    private static @Nullable GbufferTarget target;
    private static @Nullable SkyRenderer renderer;
    private static int targetWidth;
    private static int targetHeight;
    /** 真正跑过多少次天空重放（观测面自报的节流基准）。 */
    private static long renders;
    private static boolean readyLogged;
    private static java.util.Set<String> skipLogged = new java.util.HashSet<>();

    /**
     * 天空**自己**的深度。
     *
     * <p>🔖 不用 gbuffer 深度：地形在天空**之后**画，天空不需要被地形遮挡（它要铺满，
     * 再由地形盖掉），而共用一张深度只会把「天空片元的深度值」写进地形要用的那张图里
     * （h48e 实测：那条通道对包的 {@code depthtex0} 分支是干扰源，见 evidence §15.3/15.4）。
     */
    private static @Nullable TextureTarget skyDepth;

    /** 自己持有的天空状态（不去改原版那一份共享状态）。 */
    private static final SkyRenderState skyState = new SkyRenderState();

    private SkyIntoGbuffer() {
    }

    /** 链跑之前、地形之前调用一次（开关关 / 前提不满足 ⇒ 跳过，但每种原因自报一次）。 */
    public static void render() {
        if (!VkDispConfig.MRT_SKY_PASS.get()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            skipOnce("not-in-world");
            return;
        }
        GpuTextureView color = MrtTerrainPass.poolWriteView(0);
        if (color == null) {
            skipOnce("gbuffer-view-null");
            return;
        }
        int width = color.getWidth(0);
        int height = color.getHeight(0);
        GpuTextureView depth = ensureSkyDepth(width, height);
        if (depth == null) {
            skipOnce("sky-depth-null");
            return;
        }
        GpuBufferSlice fog = RenderSystem.getShaderFog();
        if (fog == null) {
            skipOnce("fog-slice-null");
            return;
        }
        clearWriteGeneration(color, depth);
        if (renderer == null || targetWidth != width || targetHeight != height) {
            rebuild(mc, color, depth, width, height);
        }
        // 🔴🔖 **每帧**重指视图：双代轮转下「待写那一代」是交替的（GAP-018），
        //   只在 rebuild 时设一次 = 天空永远画进第一次看到的那一代 ⇒ 地形写另一代并翻代，
        //   链读到「只有地形」。h48i 实测就是这个形状（`c0@afterSky=0.0000`、
        //   `c0@afterTerrain=82.7168`）。`SkyRenderer` 每帧现取 `getColorTextureView()`
        //   （源码第 134 行）⇒ 改视图即可，不必重建渲染器。
        target.repoint(color, depth);
        LevelRenderState levelState = mc.gameRenderer.gameRenderState().levelRenderState;
        // 🔖 状态必须**自己抽**：原版是在帧图装配期对它自己的 `skyRenderer` 调
        //   `extractRenderState(...)` 填 `skyRenderState`，而我们在 AfterLevel 才跑 ——
        //   实测直接借用共享状态会拿到 `skyColor == null` ⇒ SkyRenderer.renderSkyDisc
        //   在 `new Vector4f(v)` 处 NPE（h48 S1-sky 臂逐字）。用自己的 state 对象，
        //   不去改原版那一份（改了会污染原版天空 pass）。
        renderer.extractRenderState(mc.level, levelState.worldPartialTicks,
                mc.gameRenderer.mainCamera(), skyState);
        renderer.render(fog, skyState);
        reportObservationFace(mc, levelState.worldPartialTicks);
        TargetReadback.probeAfterSky();
    }

    /**
     * 观测面自报（每 300 帧一行）。
     *
     * <p>🔖 为什么必须有：h48k 量到 {@code c0@afterSky = 0.53~0.91}（近黑），而「夜空本来就黑」
     * 与「天空没画进这一代」这两种原因在**同一个数字**上长得一样 —— 那轮的 F2 又没落地，
     * 画面侧也没判据。判读对象必须自己声明此刻的世界时刻与它要写进 colortex 的颜色，
     * 否则下一轮还是只能猜（07-CONSTRAINTS X9 / 08-TESTING 的观测面纪律）。
     */
    private static void reportObservationFace(Minecraft mc, float partialTicks) {
        renders++;
        if (renders % 300L != 0L) {
            return;
        }
        long clock = mc.level.getDefaultClockTime();
        var color = skyState.skyColor;
        VkDisp.LOGGER.info("vkdisp: [GAP-003/sky] 观测面自报: clockTime={}（当地时 {}）,"
                        + " skyColor=({}, {}, {}) 这是天空片元要写进 colortex0 的值,"
                        + " rain={} render#={}",
                clock, Math.floorMod(clock, 24000L),
                color == null ? "null" : String.format("%.3f", color.x()),
                color == null ? "null" : String.format("%.3f", color.y()),
                color == null ? "null" : String.format("%.3f", color.z()),
                String.format("%.3f", mc.level.getRainLevel(partialTicks)), renders);
    }

    /**
     * 天空**之前**的那一步：把待写代清成天空的底色。
     *
     * <p>为什么必须自己清：天空 pass 的颜色是 **LOAD** 语义（原版 {@code SkyRenderer} 写死），
     * 而地形 pass 在这一代改为 LOAD 后**不再清**槽 0 ⇒ 没人清的话槽 0 会留着
     * **两帧前**的内容（天空只覆盖它真画到的地方）。alpha 清成 1.0 的理由与
     * {@code MrtTerrainPass} 里 h47c 那条相同（premultiplied 黑会让 bloom 权重塌缩）。
     *
     * <p>🔴 为什么用**一个只清不画的 render pass**而不是 {@code CommandEncoder#clearColorTexture}：
     * h48l 实测那次 `clearColorTexture` **完全没落进纹理**（`c0@afterSky` 连 alpha 都是 0，
     * 而天空自己也是空的），且本机**没有 validation layer**（07-CONSTRAINTS X35）⇒
     * 布局用错不会报错、只会静默不生效。{@code clearColorTexture} 走的是手动 barrier 路径，
     * 而我方池纹理是被 render pass 当附件用过的（布局不是它假设的那个）。
     * 对照：本项目所有**确实生效**的清屏（地形 pass、ShadowStubs）都是走
     * `createRenderPass(..., Optional.of(clearColor), ...)` 的**附件清屏**语义 ⇒ 这里照同一条路。
     */
    private static void clearWriteGeneration(GpuTextureView color, GpuTextureView depth) {
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "vkdisp sky gbuffer clear",
                color,
                java.util.Optional.of(new Vector4f(0.0F, 0.0F, 0.0F, 1.0F)),
                depth,
                java.util.OptionalDouble.of(0.0))) {
            // 什么都不画：这个 pass 的存在只为让引擎按它自己的布局规则把附件清一遍。
        }
    }

    private static @Nullable GpuTextureView ensureSkyDepth(int width, int height) {
        if (skyDepth == null) {
            skyDepth = new TextureTarget("vkdisp sky depth", width, height, null, GpuFormat.D32_FLOAT);
        } else if (skyDepth.width != width || skyDepth.height != height) {
            skyDepth.resize(width, height);
        }
        return skyDepth.getDepthTextureView();
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
            VkDisp.LOGGER.info("vkdisp: [GAP-003/sky] 天空重放器就绪：目标 = colortex0 **待写代** {}x{}"
                    + "（LOAD 语义、先于地形、不翻代）；深度 = 天空私有", width, height);
        } else {
            VkDisp.LOGGER.info("vkdisp: [GAP-003/sky] 天空重放器重建（换尺寸/换世界）：{}x{}", width, height);
        }
    }

    /** 每种跳过原因只报一次（热路径日志 I/O 纪律；但「为什么没画」必须可见，X11）。 */
    private static void skipOnce(String reason) {
        if (skipLogged.add(reason)) {
            VkDisp.LOGGER.info("vkdisp: [GAP-003/sky] 本帧跳过天空重放（原因={}）", reason);
        }
    }
}
