package dev.vkdisp.bridge;
/**
 * 【自行补充】GAP-027 穹顶路线的渲染线程半边：让原版天空某一处站点的 draw 用包的 {@code gbuffers_skybasic}
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓库自有 {@link SkyCelestialPack}（同一条「换管线前先备料、
 *    没成就原样交回、绝不半接」的形状 + 每次 draw 一份自己的块代）。原版侧只观察 {@code SkyRenderer} 的
 *    私有方法 {@code renderSkyDisc}/{@code renderStars}/{@code renderSunriseAndSunset} 各自那次
 *    {@code setPipeline}（{@code SkyRenderer.java:167/274/298}），零着色器文本搬运。
 *    外部候选（Iris / OptiFine / VulkanMod / Sulkan / Beryl）零接触（07-CONSTRAINTS L12）。
 *    许可证：独立编写（LGPL-3.0-or-later）。
 * 1. 官方/主实现：官方没有「把包 gbuffer 程序接到原版某笔天空 draw」的钩子；走注 {@code setPipeline} 那一次
 *    （与 {@code SkyRendererCelestialPipelineMixin} 同形，注入点已登记）。
 * 2. 备选：自开 pass 画穹顶 —— 否决（穹顶几何/姿态由原版驱动，自开要重算；见 SkyCelestialPack 类注释第 2 条同族）。
 * 3. 我们的差异点：① <b>原版继续驱动 draw</b>（几何/顶点缓冲/拓扑全照旧），我方只换管线 + 绑块；
 *    ② 逐站 {@code renderStage}：穹顶/星点/晨昏喂同一片元、靠 {@link SkyGeometrySite#stageMacro} 分派（BSL 逐字）；
 *    ③ 闸门<b>默认关</b>：{@code mrt.skyPackTexturedShader=false} ⇒ 一笔都不换（不碰默认档画面）。
 * 4. 许可证核对：本项目 LGPL-3.0-or-later；零第三方代码并入。
 * 5. 性能基线：每站每帧一次「写块 + 一次 draw」（原版本来也发），不新建缓冲。
 */
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.vkdisp.VkDisp;
import dev.vkdisp.VkDispConfig;
import dev.vkdisp.VkDispVirtualPack;
import dev.vkdisp.glsl.translate.BuiltinsBlockLayout;
import dev.vkdisp.pipeline.model.PackTerrainProgram;
import dev.vkdisp.render.RenderStageSupply;
import net.minecraft.client.renderer.MappableRingBuffer;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/** 包 {@code gbuffers_skybasic} 接进原版天空三处站点 draw（渲染线程独占；跨线程可见性靠 final 容器）。 */
public final class SkyGeometryPack {

    /** 每站一份编译缓存（同一天空程序，但各站管线不同形 ⇒ 不能共用一个 CompiledRenderPipeline）。 */
    private static final Map<SkyGeometrySite, @Nullable CompiledRenderPipeline> COMPILED =
            new EnumMap<>(SkyGeometrySite.class);

    /** 与 {@link #COMPILED} 同键的<b>契约身份</b>：换包/热重载 ⇒ 身份变 ⇒ 必须重建，不许复用上一份管线（对齐 {@link SkyCelestialPack} 的 COMPILED_KEY 口径）。 */
    private static final Map<SkyGeometrySite, String> COMPILED_KEY = new EnumMap<>(SkyGeometrySite.class);

    private static final AtomicInteger SITE_HITS = new AtomicInteger();
    private static final AtomicBoolean REPORTED_TAKEOVER = new AtomicBoolean(false);
    private static final AtomicBoolean REPORTED_REFUSAL = new AtomicBoolean(false);

    private SkyGeometryPack() {
    }

    /** 混入注入方法体的<b>首行</b>埋点转发（{@code MixinWiringTest} 那条「首行是 bridge 侧探针」口径）。 */
    public static SkyGeometrySite onSite(SkyGeometrySite site) {
        SITE_HITS.incrementAndGet();
        return site;
    }

    /** 埋点次数（自报读数，不参与判定）。 */
    public static long siteHits() {
        return SITE_HITS.get();
    }

    /**
     * 本站该不该换成包程序。<b>默认关</b>：闸门是 {@code mrt.skyPackTexturedShader}（天空包着线的总闸，
     * 与日月同一个 feature 门；默认 false ⇒ 穹顶一笔都不换 ⇒ 默认档画面不变）。注入点关档
     * {@code mixin.skyCelestialPack=false} 时整体不接。
     */
    public static boolean enabledFor(SkyGeometrySite site) {
        if (!VkDispConfig.MRT_SKY_PACK_TEXTURED_SHADER.get()) {
            return false;
        }
        if (!VkDispConfig.MIXIN_SKY_CELESTIAL_PACK.get()) {
            return false;
        }
        return compiled(site) != null;
    }

    /** 本站的派生管线（懒编译 + 缓存）；{@code null} = 本帧不换（交回原版）。 */
    public static @Nullable CompiledRenderPipeline compiled(SkyGeometrySite site) {
        PackTerrainProgram contract = VkDispVirtualPack.packContract(site.program);
        if (contract == null) {
            refuseOnce(site, "包没有可用的 " + site.program + " 契约");
            return null;
        }
        // 🔴 缓存键 = 契约身份（换包/热重载 ⇒ 新对象 ⇒ 键变 ⇒ 重建）。只看「有没有缓存」会拿上一份包
        //   的管线画新包（reload 后静默错，GAP-054「两侧同源」那一族）。与 SkyCelestialPack#compiled 同口径。
        String key = contract.qualifiedName() + "|" + System.identityHashCode(contract);
        CompiledRenderPipeline cached = COMPILED.get(site);
        if (cached != null && key.equals(COMPILED_KEY.get(site))) {
            return cached;
        }
        try {
            RenderPipeline descriptor = SkyPackPipeline.descriptor(site);
            if (descriptor == null) {
                refuseOnce(site, "派生管线描述符建不出（适配层判否 / 契约缺）");
                return null;
            }
            CompiledRenderPipeline compiled = RenderSystem.getCompiledPipeline(descriptor);
            if (compiled != null) {
                COMPILED.put(site, compiled);
                COMPILED_KEY.put(site, key);
            }
            return compiled;
        } catch (Throwable t) {
            refuseOnce(site, "编译派生管线失败: " + t);
            return null;
        }
    }

    /**
     * 换管线前的备料：建环 / 换代 / 写 renderStage / 取块字节缓冲。返回 {@code null} = 这笔交回原版（不半接）。
     * 🔴 必须在 {@code original.call(pack)} <b>之前</b>跑：失败还能原样交回；换完才失败就是 validateDraw 崩帧
     * （GAP-036 ④ 那条教训的形状）。
     */
    public static @Nullable MappableRingBuffer prepare(SkyGeometrySite site) {
        try {
            if (TerrainPipelineApi.existingBuiltinsRing(site.program) == null) {
                TerrainPipelineApi.updateGbufferBuiltins(site.program);
            }
            MappableRingBuffer ring =
                    TerrainPipelineApi.existingBuiltinsRing(site.program);
            if (ring == null) {
                refuseOnce(site, "块字节环取不到");
                return null;
            }
            // 🔴 每站每次 draw 一份自己的代：renderStage 在块里，不换代 = 后写覆盖先读。
            ring.rotate();
            try (RenderStageSupply.Scope scope = RenderStageSupply.enter(site.stageMacro)) {
                TerrainPipelineApi.updateGbufferBuiltins(site.program);
            }
            return ring;
        } catch (Throwable t) {
            refuseOnce(site, "备料抛异常: " + t);
            return null;
        }
    }

    /**
     * 换管线之后补绑：走 {@link TerrainPipelineApi#bindPackGbufferUniforms}（与地形/水<b>同一条</b>维度感知绑定）。
     * 🔴 必须绑<b>包自带的 sampler</b>：BSL 的 {@code gbuffers_skybasic.fsh:42} 声明了 {@code noisetex}，
     *   派生管线的 {@code packBindGroupLayout} 已按契约把它计入绑定组 ⇒ 不绑就是 {@code validateDraw} 抛
     *   「missing uniform noisetex」= 一翻闸就崩帧（本轮读包源码揪出的真实缺陷，之前误以为天空不采样）。
     *   绑定组里的 {@code SAMPLER0}（原版图集槽）确实不留（穹顶不采 celestials 图集），但<b>包的自由 sampler 照绑</b>。
     */
    public static void apply(RenderPass pass, SkyGeometrySite site) {
        TerrainPipelineApi.bindPackGbufferUniforms(pass,
                MrtTerrainPass.atlasSampler(),
                MrtTerrainPass.blockAtlas(),
                MrtTerrainPass.depthView(),
                MrtTerrainPass.poolWriteView(0),
                site.program);
        if (REPORTED_TAKEOVER.compareAndSet(false, true)) {
            VkDisp.LOGGER.info("vkdisp: [GAP-027/sky-geometry] 天空站点 {} 改由包的 {} 画"
                            + "（原版仍驱动几何/拓扑；我方只换管线 + 绑块与包 sampler + renderStage={}）",
                    site, site.program, site.stageMacro);
        }
    }

    private static void refuseOnce(SkyGeometrySite site, String reason) {
        if (REPORTED_REFUSAL.compareAndSet(false, true)) {
            VkDisp.LOGGER.warn("vkdisp: [GAP-027/sky-geometry] {} 闸门开着但<b>不接管</b>：{}"
                    + " ⇒ 该笔天空保持原版（不静默换绑，T11）", site, reason);
        }
    }

    /** 自报：本站块布局是否就绪（不参与判定，供取证读）。 */
    public static boolean layoutReady(SkyGeometrySite site) {
        BuiltinsBlockLayout layout = VkDispVirtualPack.packBuiltinsLayout(site.program);
        return layout != null && !layout.isEmpty();
    }
}
