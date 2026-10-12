package dev.vkdisp.bridge;
/**
 * 【参考调研】R4（第 5 步）：让天空落进 gbuffer 的<b>两条官方通路</b> —— 官方天空盒钩子 + 原版视图 @Redirect
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 原版 26.3.0.51-beta sources jar：{@code LevelRenderer} 第 369-394 行
 *    （{@code addSkyPass}：外层门控在第 371 行 {@code fogType != POWDER_SNOW && != LAVA &&
 *    !doesMobEffectBlockSky}，内层第 381 行 {@code state.skybox != NONE}；{@code "sky"} pass 第 382 行
 *    创建；其 {@code executes} 体第 <b>385</b> 行先问 {@code customSkyboxRenderer.renderSky(...)}，
 *    只有回 {@code false} 才调 {@code skyRenderer.render}），以及 {@code SkyRenderer} 第 132-161 行
 *    （第 <b>134/135</b> 行两次 {@code renderTarget.getXxxTextureView()}；第 137-139 行<b>自己</b>建
 *    "Sky" pass，两个附件都是 {@code Optional.empty()/OptionalDouble.empty()} = LOAD 语义）；
 *    ② NeoForge 26.3.0.51-beta：{@code net.neoforged.neoforge.client.CustomSkyboxRenderer}
 *    （单方法接口，{@code default false}；文档原文「return true prevents vanilla sky rendering」）
 *    与 {@code RenderLevelStageEvent.AfterSky}（原版第 388-390 行、天空刚画完且那个 pass 已关的那一刻
 *    post）—— <b>只实现 / 订阅其 API，不并入其代码</b>，与本项目对 NeoForge 事件的一路用法同口径；
 *    ③ 本仓库自有：{@link MrtTerrainPass}（colortex 池与代次口径、gbuffer 深度）、
 *    {@link SkyPackGbuffer}（包的 {@code gbuffers_skybasic} 一次全屏）、{@link PassOrder}
 *    （「terrain requires 原版 sky」那条边）。
 *    → 能否并入本项目（MIT）：可以；本文件独立编写
 *    → 例外条款：无 GPL / ARR 代码并入；LGPL-2.1 的 NeoForge 接口按 API 使用，不复制其实现
 * 1. 官方/主实现：①② 本身就是官方通路 —— 钩子回 {@code true} = 原版让位，{@code @Redirect} 换实参 =
 *    原版自己那一笔画到我方图上。两者都落在原版 {@code "sky"} pass 体内 ⇒ 四道门控<b>由原版判</b>
 *    （GAP-039 一行都不用抄）。
 * 2. 备选（都否决，理由可数）：① <b>旧做法</b> = 自建 {@code SkyRenderer} + {@code GbufferTarget} 薄壳
 *    + 我方插一个 {@code vkdisp_gbuffer_sky} pass —— <b>否决</b>：它整个绕开原版门控（岩浆 / 粉雪 /
 *    遮天效果 / {@code Skybox.NONE} 维度里照样画 = GAP-039），还得自己持有一份 {@code SkyRenderState}
 *    并调 {@code extractRenderState}（借原版那份会 {@code skyColor == null} 而 NPE，h48 S1-sky 臂逐字），
 *    并自建一张私有天空深度；② <b>M-12 注 {@code addSkyPass}</b> 自己判那四道 {@code if} —— <b>否决</b>：
 *    官方钩子已经给出同一效果，注入只是多一个登记点、多一分签名漂移面（V3 排查表）。
 * 3. 我们的差异点：天空不再需要「我方 pass」这个前提 ⇒ {@code GbufferTarget} 整类删除、原版
 *    {@code SkyRenderer} 实例<b>复用</b>而不是复制，代次口径与地形 / 云同一条（都写
 *    {@code poolWriteView(0)}，都在 {@code advanceWritten} 之前）。
 * 4. 许可证核对：本项目 MIT；零第三方代码并入。
 * 5. 性能基线：每帧一次天空（原版那一笔或包的一次全屏 draw，3~10 顶点），与换代前同量级；
 *    不测不下结论（T14 / X55）。
 */
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.vkdisp.VkDisp;
import dev.vkdisp.VkDispConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.CustomSkyboxRenderer;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * 天空 → gbuffer 的交接面（渲染线程独占，无锁）。
 *
 * <p><b>本帧天空到底画没画</b>由 {@link #paintedWriteGeneration()} 交出，而<b>不是</b>由配置推：
 * 原版在岩浆 / 粉雪 / 遮天 / {@code Skybox.NONE} 里根本不建 {@code "sky"} pass（第 371/381 行），
 * 于是钩子不会被调、{@code renderSky} 与两条 @Redirect 都不会跑 ⇒ 那个标记保持 {@code false}
 * ⇒ 地形那一槽按 <b>CLEAR</b> 打开。这就是 GAP-039 的收口形状：「这一刻没天空」这件事由
 * <b>数据</b>说出，不由我方复读那四道 {@code if}。
 *
 * <p>🔴 <b>为什么槽 0 必须 LOAD</b>（h48e 实测）：天空先铺满、地形后盖 ⇒ 地形清槽 0 就等于抹掉天空。
 * 反过来，天空这一路自己也不清（原版两个附件都是 LOAD），所以<b>清屏</b>这件事留在
 * {@link #clearWriteGeneration}：不留它，槽 0 里就是<b>两帧前</b>的内容（GAP-018 双代轮转）。
 *
 * <p>🔖 <b>深度只清一次、只翻一次</b>（{@code GAP-037} 的地雷）：本类给天空的颜色/深度视图都按 LOAD
 * 挂那张 gbuffer 深度，<b>不</b>清它、也<b>不</b>写它（原版五条天空管线的 {@code depthStencilState}
 * 都是 {@code null}，包的 skybasic 那条是 {@code ALWAYS_PASS} + 不写）⇒ 一帧里深度的清屏仍然只有
 * 地形 pass 那一次，{@code DepthGlProxy} 的 {@code 1 − z} 仍然只作用一层。
 */
@EventBusSubscriber(modid = VkDisp.MOD_ID, value = Dist.CLIENT)
public final class SkyIntoGbuffer implements CustomSkyboxRenderer {

    /** 装进 {@code LevelRenderState#customSkyboxRenderer} 的单例（该字段每帧被清 ⇒ 装配期重挂）。 */
    private static final SkyIntoGbuffer HOOK = new SkyIntoGbuffer();

    /** 每种跳过原因只报一次（热路径日志 I/O 纪律；但「为什么没画」必须可见，X11）。 */
    private static final java.util.Set<String> SKIP_LOGGED = new java.util.HashSet<>();

    /** 本帧天空是否已经写过「待写那一代」的 colortex0 ⇒ 地形那一槽走 LOAD 还是 CLEAR。 */
    private static boolean paintedThisFrame;
    /** {@link #renderSky} 真被原版调到的次数。🔴 它是「钩子到底装上没有」的判据（见 {@link #installHook}）。 */
    private static long hookCalls;
    /** 天空真跑过多少帧（观测面自报的节流基准）。 */
    private static long renders;
    /** 本帧天空是否由<b>包的 skybasic</b> 画的（{@code false} = 原版那一笔，仅自报用）。 */
    private static boolean lastFrameByPack;
    private static boolean readyLogged;

    private SkyIntoGbuffer() {
    }

    /**
     * 装配期（{@code LevelRenderer:249} 那个官方事件里）把钩子挂进<b>本帧</b>的 {@code LevelRenderState}。
     *
     * <p>🔴 必须每帧做：{@code LevelRenderState#reset()} 第 54 行每帧把该字段清回 {@code null}。
     * <p>⚠️ 只在它<b>为空</b>时安装：已有别的模组声明了自定义天空盒就不抢（它优先），我方退回
     * 「M-11 换视图」那一路 ⇒ 原版天空仍进 gbuffer，只是包的天空让位。
     * <p>⚠️ <b>本帧是否真被调到</b>是可观测的（{@link #hookCalls}）：若装配期之后 NeoForge 又覆写该字段，
     * {@code hookCalls} 会停在 0 而画面仍是「原版天空进 gbuffer」⇒ 那条失败是<b>响亮</b>的，
     * 修法是把安装点搬到 {@code addSkyPass}（M-12），不是猜。
     */
    public static void installHook() {
        paintedThisFrame = false;
        if (!VkDispConfig.MRT_SKY_PASS.get()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        LevelRenderState state = mc.gameRenderer.gameRenderState().levelRenderState;
        if (state == null) {
            skipOnce("level-render-state-null");
            return;
        }
        if (state.customSkyboxRenderer == null) {
            state.customSkyboxRenderer = HOOK;
            if (!readyLogged) {
                readyLogged = true;
                VkDisp.LOGGER.info("vkdisp: [R4/sky] 官方天空盒钩子已装（LevelRenderState#customSkyboxRenderer）"
                        + "⇒ 包天空在原版 \"sky\" pass 体内接管，回 true 即与原版互斥；"
                        + "四道门控（岩浆/粉雪/遮天/Skybox.NONE）由原版判（GAP-039）");
            }
        } else if (state.customSkyboxRenderer != HOOK) {
            skipOnce("custom-skybox-taken-by-other-mod");
        }
    }

    /**
     * 官方钩子本体（原版在 {@code LevelRenderer:385} 调它，那一刻 {@code "sky"} pass 已打开但
     * <b>内部没有 render pass</b> —— 原版自己的在 {@code SkyRenderer:139} 才开 ⇒ 这里可以自开我方 pass）。
     *
     * @return {@code true} = 包天空已画进 colortex0，原版那一笔<b>不跑</b>；{@code false} = 交回原版，
     *         它的两个视图由 M-11 换成同一张 colortex0 + 同一张 gbuffer 深度
     */
    @Override
    public boolean renderSky(LevelRenderState levelRenderState, SkyRenderState skyRenderState,
            Matrix4fc viewRotationMatrix, GpuBufferSlice skyFog) {
        hookCalls++;
        reportObservationFace(levelRenderState, skyRenderState);
        // 🔖 与原版第 133 行同一条：天空那一刻的雾就是 skyFog。我方包天空走 bindDefaultUniforms，
        //   不吃这一步也不报错，但留着它 ⇒ 两档（包 / 原版）在雾上口径一致（GAP-038 的同族）。
        RenderSystem.setShaderFog(skyFog);
        MrtTerrainPass.prepareGbufferViews();
        GpuTextureView color = MrtTerrainPass.poolWriteView(0);
        GpuTextureView depth = MrtTerrainPass.depthView();
        if (color == null) {
            skipOnce("gbuffer-view-null");
            return false;
        }
        if (depth == null) {
            // 🔴 包天空那条 pass 不能挂 null 深度（实测：客户端 exit 1，见 SkyPackGbuffer#render 注释）
            skipOnce("gbuffer-depth-null");
            return false;
        }
        if (!SkyPackGbuffer.enabled()) {
            // 包天空不接管（关档 / 契约不满足）⇒ 交回原版那一笔，它的两个视图由 M-11 换成同一张图。
            return false;
        }
        lastFrameByPack = SkyPackGbuffer.render(color, depth);
        if (!lastFrameByPack) {
            return false;
        }
        paintedThisFrame = true;
        return true;
    }

    /**
     * M-11 第一处（原版 {@code SkyRenderer:134}）：把「取颜色视图」换成我方 colortex0 的<b>待写那一代</b>。
     *
     * <p>🔴 顺带在这里清屏：原版那一笔的两个附件都是 LOAD，而我方槽 0 里留着的是<b>两帧前</b>的内容
     * （GAP-018 双代轮转）⇒ 必须在它开 pass <b>之前</b>清一次。这里是那一刻之前唯一的自己的代码
     * （第 133 行是原版设雾、第 137 行就开 pass 了）。
     */
    public static @Nullable GpuTextureView onSkyColorViewRequest(RenderTarget vanillaTarget) {
        if (!viewsIntoGbuffer()) {
            return vanillaTarget.getColorTextureView();
        }
        MrtTerrainPass.prepareGbufferViews();
        GpuTextureView color = MrtTerrainPass.poolWriteView(0);
        if (color == null) {
            skipOnce("gbuffer-view-null");
            return vanillaTarget.getColorTextureView();
        }
        if (!paintedThisFrame) {
            clearWriteGeneration(color, MrtTerrainPass.depthView());
            paintedThisFrame = true;
        }
        return color;
    }

    /**
     * M-11 第二处（原版 {@code SkyRenderer:135}）：把「取深度视图」换成我方 gbuffer 深度。
     *
     * <p>🔖 这张图天空<b>既不测也不写</b>（原版天空管线 {@code depthStencilState == null}）⇒ 挂它只是
     * 让那个 pass 的附件描述与后来的地形/云一致，不产生任何深度语义（{@code GAP-037}：清与翻都只有一次）。
     */
    public static @Nullable GpuTextureView onSkyDepthViewRequest(RenderTarget vanillaTarget) {
        if (!viewsIntoGbuffer()) {
            return vanillaTarget.getDepthTextureView();
        }
        GpuTextureView depth = MrtTerrainPass.depthView();
        return depth == null ? vanillaTarget.getDepthTextureView() : depth;
    }

    /** M-11 是否启用（{@code mixin.skyViews} 且天空总闸 {@code mrt.skyPass}）。 */
    public static boolean viewsIntoGbuffer() {
        return VkDispConfig.MIXIN_SKY_VIEWS.get() && VkDispConfig.MRT_SKY_PASS.get();
    }

    /**
     * 本帧天空是否已写过待写那一代（{@code MrtTerrainPass} 据此让地形那一槽走 LOAD）。
     *
     * <p>🔴 它<b>不是</b>配置的反向读取：岩浆 / 遮天 / {@code Skybox.NONE} 里钩子与 @Redirect 都不跑，
     * 这个标记就保持 false ⇒ 地形照旧清槽 0，于是「不画天空」真的落到图上（GAP-039 的判据本体）。
     */
    public static boolean paintedWriteGeneration() {
        return paintedThisFrame;
    }

    /** 观测面自报每 300 帧一次的节流基准（供 {@code MrtTerrainPass} 的换代自报对齐；只读）。 */
    public static long skyHookCalls() {
        return hookCalls;
    }

    /**
     * 天空<b>之后</b>的那一刻取一次读数：官方 {@code RenderLevelStageEvent.AfterSky}
     * （原版第 388-390 行，在 {@code "sky"} pass 体内、{@code SkyRenderer} 自己那个 pass 已关之后）。
     *
     * <p>🔖 为什么挂事件而不是留在本类方法里：现在有两路天空（包的 / 原版的），取点时刻<b>同一个</b>，
     * 挂在两路各自末尾就是两份实现；官方事件是它们共同的下一句。
     */
    @SubscribeEvent
    static void onAfterSky(RenderLevelStageEvent.AfterSky event) {
        TargetReadback.probeAfterSky();
    }

    /**
     * 观测面自报（每 300 帧一行）。
     *
     * <p>🔖 为什么必须有：h48k 量到 {@code c0@afterSky = 0.53~0.91}（近黑），而「夜空本来就黑」与
     * 「天空没画进这一代」这两种原因在<b>同一个数字</b>上长得一样 ⇒ 判读对象必须自己声明此刻的世界
     * 时刻、它要写进 colortex 的颜色，以及<b>钩子到底被调了几次</b>（{@code hookCalls} 停在 0 =
     * 装配期那次安装没生效，见 {@link #installHook} 的 ⚠️ 条）（07-CONSTRAINTS X9 / 08-TESTING 观测面纪律）。
     */
    private static void reportObservationFace(LevelRenderState levelRenderState, SkyRenderState skyState) {
        renders++;
        if (renders % 300L != 0L) {
            return;
        }
        long clock = levelRenderState.gameTime;
        var color = skyState.skyColor;
        float rain = Minecraft.getInstance().level == null ? 0.0F
                : Minecraft.getInstance().level.getRainLevel(levelRenderState.worldPartialTicks);
        VkDisp.LOGGER.info("vkdisp: [R4/sky] 观测面自报: gameTime={}（当地时 {}）, skyColor=({}, {}, {})"
                        + " 这是天空片元要写进 colortex0 的值, rain={} 天空帧#={} 钩子被调#={} 接管方={}",
                clock, Math.floorMod(clock, 24000L),
                color == null ? "null" : String.format("%.3f", color.x()),
                color == null ? "null" : String.format("%.3f", color.y()),
                color == null ? "null" : String.format("%.3f", color.z()),
                String.format("%.3f", rain), renders, hookCalls,
                paintedThisFrame ? (lastFrameByPack ? "包的 skybasic" : "原版那一笔(M-11)") : "还没写");
    }

    /**
     * 天空<b>之前</b>的那一步：把待写代清成天空的底色（alpha 必须 1.0，理由同 h47c：
     * GL 的 {@code (0,0,0,0)} 在那条链上不是「黑」而是「premultiplied 黑」，bloom 权重会塌缩）。
     *
     * <p>🔴 为什么用**一个只清不画的 render pass** 而不是 {@code CommandEncoder#clearColorTexture}：
     * h48l 实测那次 {@code clearColorTexture} <b>完全没落进纹理</b>（{@code c0@afterSky} 连 alpha 都是 0），
     * 且本机<b>没有 validation layer</b>（07-CONSTRAINTS X35）⇒ 布局用错不报错、只静默不生效；
     * {@code clearColorTexture} 走手动 barrier 路径，而我方池纹理是被 render pass 当附件用过的。
     * 对照：本项目所有<b>确实生效</b>的清屏都走 {@code createRenderPass(..., Optional.of(clear), ...)}
     * 的<b>附件清屏</b>语义 ⇒ 照同一条路。
     *
     * <p>🔖 深度按 <b>LOAD</b> 挂上：本方法只负责颜色。清它就是把「谁清 gbuffer 深度」变成两处
     * （地形那一 pass 才是它的属主，且 {@code GAP-037} 的「只清一次、只翻一次」要靠这个单点）。
     */
    private static void clearWriteGeneration(GpuTextureView color, @Nullable GpuTextureView depth) {
        RenderPassDescriptor.Builder descriptor = RenderPassDescriptor
                .builder(() -> "vkdisp sky gbuffer clear")
                .withColorAttachment(color, Optional.of(new Vector4f(0.0F, 0.0F, 0.0F, 1.0F)));
        if (depth != null) {
            descriptor.withDepthAttachment(depth, OptionalDouble.empty());
        }
        try (var pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(descriptor.build())) {
            // 什么都不画：这个 pass 的存在只为让引擎按它自己的布局规则把附件清一遍。
        }
    }

    private static void skipOnce(String reason) {
        if (SKIP_LOGGED.add(reason)) {
            VkDisp.LOGGER.info("vkdisp: [R4/sky] 本帧天空不走我方（原因={}）", reason);
        }
    }
}
