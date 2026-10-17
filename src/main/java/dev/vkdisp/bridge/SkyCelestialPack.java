package dev.vkdisp.bridge;
/**
 * 【自行补充】GAP-027 天空线第三刀：让原版<b>日月那两笔 draw</b> 用包的 {@code gbuffers_skytextured}
 * 0. 合规核对（第 0 步闸门）：参考对象 = ① 本仓库自有实现 —— {@link SkyPackGbuffer}（派生管线构造 +
 *    「编译没落地就本帧不接管」的兜底形状）与 {@code GbufferPipelineSwaps}（官方管线替换通道）；
 *    ② 原版 26.3 {@code SkyRenderer} 的<b>公开/可观察行为事实</b>（只取调用序与形参，不复制文本）：
 *    {@code drawCelestialBody}（{@code SkyRenderer.java:252-263}）逐字
 *    {@code setPipeline(getCompiledPipeline(CELESTIAL)) → bindDefaultUniforms →
 *    setUniform("DynamicTransforms") → setUniform("Sampler0", celestials…) → setVertexBuffer →
 *    drawIndexed(6,1,0,baseVertex,0)}，而 {@code CELESTIAL} 那条管线是
 *    {@code GLOBALS + PROJECTION + DYNAMIC_TRANSFORMS + SAMPLER0 + POSITION_TEX + QUADS + OVERLAY}
 *    （{@code RenderPipelines.java:1084-1094}）。
 *    外部候选（Iris / OptiFine / VulkanMod / Sulkan / Beryl）零接触（07-CONSTRAINTS L12）。
 *    许可证：独立编写（LGPL-3.0-or-later），零第三方代码、零 Mojang 着色器文本。
 *
 * <p>1. 官方/主实现：NeoForge 没有「把包的 gbuffer 程序接到原版某笔 draw 上」的公开钩子；
 *    官方给的是<b>管线替换</b>那条通道（{@code RenderSystem.getCompiledPipelineNullable} 首条语句
 *    就是 {@code PIPELINE_MODIFIERS.apply}）—— 但本格的绑 uniform 需要 {@code RenderPass} 句柄，
 *    而 modifier 拿不到 ⇒ 走「注 {@code setPipeline} 那一次调用」这一条（与
 *    {@code WeatherEffectRendererPipelineMixin} 同一形状，注入点已登记）。
 *
 * <p>2. 备选（三条都否决，理由可数）：
 *    ① <b>自开 pass 画日月</b> —— 必须自己交天体姿态矩阵，而原版把它放在 {@code DynamicTransforms}
 *       里、由 {@code applyCelestialBodyTransform} 沿 pose 链现算（{@code SkyRenderer:219-250}），
 *       外面取不到 ⇒ 只能复制那段数学；
 *    ② <b>PipelineModifier 换管线</b> —— 拿不到 {@code RenderPass} ⇒ 无法补绑我方
 *       {@code VkDispBuiltins} 与包 sampler，而缺 uniform 是 {@code validateDraw} 抛（崩帧那一族）；
 *    ③ <b>用 {@code SkyPackGbuffer} 那张全屏三角顺带画日月</b> —— 否决：日月的 uv 决定采图集哪一块，
 *       是<b>几何数据</b>，屏幕空间那套（{@code skybasic}）成立的前提在这里不适用。
 *
 * <p>3. 我们的差异点：
 *       ① <b>原版继续驱动 draw</b> ⇒ 几何、姿态、索引、月相的 {@code baseVertex} 全部照旧，
 *          我方只换着色器与补绑 ⇒ 日月位置不可能被我方算错（这是本格最大的风险源）；
 *       ② 🔴 <b>先备好块字节与视图，再决定换不换</b>：{@link #prepare} 失败就原样交回原版管线，
 *          绝不「换了管线却绑不上」（那会在 {@code validateDraw} 抛，而这跑在帧图 pass 体内 = 客户端死，
 *          {@code GAP-036} ④ 那条教训）；
 *       ③ 🔴 <b>每次 draw 一份自己的块代</b>：日与月是<b>同一程序两次 draw</b>，而 {@code renderStage}
 *          在块里 —— 已核实 {@code VulkanGpuBuffer.map} 是 {@code vmaMapMemory} 直写主机指针
 *          （不发 encoder），所以 pass 内可写；但两次写必须落在<b>不同代</b>（{@code rotate}），
 *          否则 CPU 跑到 GPU 前面 ⇒ 太阳读到月亮的 {@code renderStage}；
 *       ④ <b>只认 Sun/Moon 两个 label</b>：{@code renderEndFlash} 也走同一个私有方法
 *          （{@code SkyRenderer:232-241}），不区分就会拿包的日月程序去画末地闪烁。
 *
 * <p>4. 许可证核对：本项目 LGPL-3.0-or-later；零第三方代码并入。
 * <p>5. 性能基线：开启时每帧多两次「写块 + 一次 4 顶点 draw」（原版本来也发这两次 draw），
 *    不新增缓冲与分配；不测不下结论（T14 / X55）。
 */
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.vkdisp.VkDisp;
import dev.vkdisp.VkDispConfig;
import dev.vkdisp.VkDispVirtualPack;
import dev.vkdisp.glsl.translate.BuiltinsBlockLayout;
import dev.vkdisp.pack.PackTerrainSource;
import dev.vkdisp.pipeline.model.PackTerrainProgram;
import dev.vkdisp.render.RenderStageSupply;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.data.AtlasIds;
import org.jspecify.annotations.Nullable;

/** 包的 {@code gbuffers_skytextured} 接进原版日月 draw（渲染线程独占；跨线程可见性靠 final 原子持有者）。 */
public final class SkyCelestialPack {

    private static final String PROGRAM = PackTerrainSource.SKYTEXTURED_PROGRAM;

    /** 派生管线 location 前缀（每次重建换后缀 ⇒ 满足「改过必须换 location」契约）。 */
    private static final String BASE_LOCATION = "pipeline/gbuffer_sky_celestial";

    private static final AtomicReference<@Nullable CompiledRenderPipeline> COMPILED =
            new AtomicReference<>();
    private static final AtomicReference<@Nullable String> COMPILED_KEY = new AtomicReference<>();

    /** 观测面（不参与任何判定）。 */
    private static final AtomicInteger PREPARED = new AtomicInteger();
    private static final AtomicInteger DRAWN = new AtomicInteger();
    private static final AtomicBoolean REPORTED_REFUSAL = new AtomicBoolean(false);
    private static final AtomicBoolean REPORTED_TAKEOVER = new AtomicBoolean(false);
    private static final AtomicInteger SITE_HITS = new AtomicInteger();
    private static final AtomicBoolean SITE_LOG_ONCE = new AtomicBoolean(false);

    private SkyCelestialPack() {
    }

    /**
     * M1 ③ 埋点：证明这笔真的走到了（而不是「注册了但没人调」那一族），并把 label 原样交回。
     *
     * &lt;p&gt;混入的注入方法体<b>首行</b>必须转发到这里 —— 判据是 {@code MixinWiringTest} 那条
     * 「首行是 bridge 侧埋点转发」，与 M-01/M-05/M-10 同一口径（不写死类名）。
     */
    public static String onCelestialSite(String label) {
        SITE_HITS.incrementAndGet();
        if (SITE_LOG_ONCE.compareAndSet(false, true)) {
            VkDisp.LOGGER.info("vkdisp: [GAP-027/sky-textured] 注入点已走到（首个 label={}）"
                    + " —— 这一行只证明「这笔 draw 经过了我方判定」，接管与否看下一行", label);
        }
        return label;
    }

    /** 埋点次数（自报读数，不参与判定）。 */
    public static long siteHits() {
        return SITE_HITS.get();
    }

    /**
     * 这个 label 对应的那笔 draw 是否该换成包程序。
     *
     * @param label 原版 {@code drawCelestialBody} 第 1 个形参（实测取值：{@code "Sun"} / {@code "Moon"}
     *              / {@code "End flash"}）
     */
    public static boolean enabledFor(@Nullable String label) {
        if (!"Sun".equals(label) && !"Moon".equals(label)) {
            return false;
        }
        return available() == null;
    }

    /** 契约/开关侧的就绪判定；{@code null} = 可接管，否则返回<b>不接管的原因</b>（自报用）。 */
    static @Nullable String available() {
        if (!VkDispConfig.MRT_SKY_PACK_TEXTURED_SHADER.get()) {
            return "mrt.skyPackTexturedShader=false";
        }
        if (!VkDispConfig.MIXIN_SKY_CELESTIAL_PACK.get()) {
            return "mixin.skyCelestialPack=false（注入点关档）";
        }
        PackTerrainProgram program = VkDispVirtualPack.packContract(PROGRAM);
        if (program == null) {
            return "包没有可用的 " + PROGRAM + " 契约（转译/编译失败，或包里就没有这条程序）";
        }
        if (program.outputCount() != 1 || !List.of(0).equals(program.declaredOutputSlots())) {
            return PROGRAM + " 的输出不是单槽 0（outputs=" + program.outputCount()
                    + " slots=" + program.declaredOutputSlots() + "）⇒ 附件数与 pass 不匹配，"
                    + "setPipeline 会当场抛（原版那条管线只有 1 个颜色目标）";
        }
        return null;
    }

    /** 供 {@link SkyPackGbuffer} 的接管闸门用：日月这半有没有真的可画。 */
    public static boolean wired() {
        return available() == null && compiled() != null;
    }

    /** 换管线之前一次「备好块字节 + 视图」；返回 {@code null} = 这笔交回原版（不半接）。 */
    public static @Nullable Pending prepare(@Nullable String label) {
        String stage = "Moon".equals(label) ? "MOON" : "SUN";
        PackTerrainProgram program = VkDispVirtualPack.packContract(PROGRAM);
        if (program == null) {
            return null;
        }
        BuiltinsBlockLayout layout = VkDispVirtualPack.packBuiltinsLayout(PROGRAM);
        if (layout == null || layout.isEmpty()) {
            refuseOnce("块的 std140 布局为空（终稿里没有 " + PackTerrainProgram.BUILTINS_BLOCK
                    + "）⇒ 本笔交回原版");
            return null;
        }
        // 🔴 换管线之前先把块与本笔的 renderStage 写好、视图取到：这里任何一步失败都还能原样交回原版。
        try {
            if (TerrainPipelineApi.existingBuiltinsRing(PROGRAM) == null) {
                TerrainPipelineApi.updateGbufferBuiltins(PROGRAM); // 只为了建环（这一代不留给本笔）
            }
            net.minecraft.client.renderer.MappableRingBuffer ring =
                    TerrainPipelineApi.existingBuiltinsRing(PROGRAM);
            if (ring == null) {
                refuseOnce("块字节环取不到 ⇒ 本笔交回原版");
                return null;
            }
            // 🔴 每次 draw 一份自己的代：日与月同程序两次 draw，而 renderStage 在块里 ——
            //    不换代就是「后写的那份被两次 draw 都读到」（CPU 直写跑在 GPU 前面）。
            ring.rotate();
            try (RenderStageSupply.Scope scope = RenderStageSupply.enter(stage)) {
                TerrainPipelineApi.updateGbufferBuiltins(PROGRAM);
            }
            AtlasPair atlas = atlas();
            if (atlas == null) {
                refuseOnce("celestials 图集取不到 ⇒ 包的 texture sampler 没有来源，本笔交回原版");
                return null;
            }
            PREPARED.incrementAndGet();
            return new Pending(ring, atlas.view());
        } catch (Throwable t) {
            refuseOnce("备料抛异常 ⇒ 本笔交回原版：" + t);
            return null;
        }
    }

    /** 备好的东西（块字节 + 图集视图），在 {@code setPipeline} 之后交给 {@link #apply}。 */
    public record Pending(net.minecraft.client.renderer.MappableRingBuffer builtins,
            GpuTextureView celestials) {
    }

    /** 管线已换成包程序之后补绑：我方块 + 包自己声明的每个 sampler。 */
    public static void apply(RenderPass pass, Pending pending) {
        pass.setUniform(PackTerrainProgram.BUILTINS_BLOCK, pending.builtins().currentBuffer());
        AtlasPair atlas = atlas();
        GpuSampler sampler = atlas == null ? null : atlas.sampler();
        if (sampler == null) {
            // 🔴 走到这里已经换了管线 ⇒ 绑不上就是响亮失败。这是刻意的：静默交回原版会留下
            //   「日志说接管、画的是原版」那种最难查的形态（本仓 h33 那族）。
            throw new IllegalStateException("vkdisp: [GAP-027] 日月采样器未建 ⇒ 无法绑 "
                    + PROGRAM + " 的 sampler（宁可抛，也不静默喂错维度）");
        }
        for (String name : samplerNames()) {
            pass.setUniform(name, pending.celestials(), sampler);
        }
        DRAWN.incrementAndGet();
        if (REPORTED_TAKEOVER.compareAndSet(false, true)) {
            VkDisp.LOGGER.info("vkdisp: [GAP-027/sky-textured] 日月改由包的 " + PROGRAM + " 画"
                    + "（原版仍驱动几何/姿态/baseVertex；我方只换管线 + 补绑 "
                    + PackTerrainProgram.BUILTINS_BLOCK + " 与 " + samplerNames() + "）");
        }
    }

    /** 包里那条程序<b>自己声明</b>的 sampler 名（逐条绑，缺一个就是 validateDraw 抛）。 */
    private static List<String> samplerNames() {
        PackTerrainProgram program = VkDispVirtualPack.packContract(PROGRAM);
        return program == null ? List.of() : program.fragmentSamplers();
    }

    /** 派生管线（懒建 + 按契约身份重建）；{@code null} = 本笔不接管。 */
    public static @Nullable CompiledRenderPipeline compiled() {
        PackTerrainProgram program = VkDispVirtualPack.packContract(PROGRAM);
        if (program == null) {
            return null;
        }
        String key = program.qualifiedName() + "|" + System.identityHashCode(program);
        CompiledRenderPipeline cached = COMPILED.get();
        if (cached != null && key.equals(COMPILED_KEY.get())) {
            return cached;
        }
        try {
            RenderPipeline built = RenderPipeline.builder(net.minecraft.client.renderer.RenderPipelines.GLOBALS_SNIPPET)
                    .withLocation(net.minecraft.resources.Identifier.fromNamespaceAndPath(
                            VkDisp.MOD_ID, BASE_LOCATION + "_" + Integer.toHexString(key.hashCode())))
                    .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.PROJECTION)
                    .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.DYNAMIC_TRANSFORMS)
                    // 🔴 必须留 SAMPLER0：原版在这笔 draw 里无条件 setUniform("Sampler0", …)，
                    //    我方管线没这个名字就是「Unable to find shader defined uniform」= 崩帧。
                    .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.SAMPLER0)
                    .withBindGroupLayout(TerrainPipelineApi.packBindGroupLayout(program))
                    .withVertexShader(VkDispVirtualPack.packAdapterShaderId(PROGRAM))
                    .withFragmentShader(VkDispVirtualPack.packFragmentShaderId(PROGRAM))
                    // 与原版逐字一致：POSITION_TEX + QUADS + OVERLAY（否则日月的混合观感会变）。
                    .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
                    .withPrimitiveTopology(com.mojang.renderpearl.api.pipeline.PrimitiveTopology.QUADS)
                    .withColorTargetState(new com.mojang.renderpearl.api.pipeline.ColorTargetState(
                            com.mojang.renderpearl.api.pipeline.BlendFunction.OVERLAY))
                    .build();
            CompiledRenderPipeline compiled = RenderSystem.getCompiledPipeline(built);
            if (compiled == null) {
                return null;
            }
            COMPILED.set(compiled);
            COMPILED_KEY.set(key);
            VkDisp.LOGGER.info("vkdisp: [GAP-027/sky-textured] 派生日月管线已建: program={} vertex={} fragment={}",
                    program.qualifiedName(), VkDispVirtualPack.packAdapterShaderId(PROGRAM),
                    VkDispVirtualPack.packFragmentShaderId(PROGRAM));
            return compiled;
        } catch (Throwable t) {
            refuseOnce("派生日月管线建立失败 ⇒ 日月保持原版: " + t);
            return null;
        }
    }

    /** celestials 图集（视图 + 采样器）；取不到就本笔交回原版（只 WARN 一次）。 */
    private static @Nullable AtlasPair atlas() {
        AtlasPair cached = ATLAS.get();
        if (cached != null) {
            return cached;
        }
        try {
            TextureAtlas atlas = Minecraft.getInstance().getAtlasManager()
                    .getAtlasOrThrow(AtlasIds.CELESTIALS);
            GpuTextureView view = atlas.getTextureView();
            if (view == null) {
                return null;
            }
            AtlasPair pair = new AtlasPair(view, atlas.getSampler());
            ATLAS.set(pair);
            return pair;
        } catch (Throwable t) {
            // 取证手段不该把功能拖挂：这里只 WARN，且**不接管**（prepare 回 null ⇒ 原版画）。
            refuseOnce("取 celestials 图集失败 ⇒ 包的 texture sampler 没有来源: " + t);
            return null;
        }
    }

    /** 图集那对（原版 {@code SkyRenderer:259} 用的就是同一对的两个 getter）。 */
    private record AtlasPair(GpuTextureView view, GpuSampler sampler) {
    }

    private static final AtomicReference<@Nullable AtlasPair> ATLAS = new AtomicReference<>();

    private static void refuseOnce(String reason) {
        if (REPORTED_REFUSAL.compareAndSet(false, true)) {
            VkDisp.LOGGER.warn("vkdisp: [GAP-027/sky-textured] mrt.skyPackTexturedShader 开着但<b>不接管</b>："
                    + reason + " ⇒ 该笔日月保持原版着色（不静默换绑，T11）");
        }
    }

    /** 自报读数（不参与判定）。 */
    public static long preparedCount() {
        return PREPARED.get();
    }

    public static long drawnCount() {
        return DRAWN.get();
    }
}
