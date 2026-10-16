package dev.vkdisp.bridge;
/**
 * 【自行补充】GAP-027 天空线第二刀：用<b>包的 {@code gbuffers_skybasic}</b> 画 gbuffer 里的天空
 * （一次全屏 draw），替掉 {@link SkyIntoGbuffer} 那笔原版平面底色。
 *
 * <p>0. 合规核对（第 0 步闸门，通过）：参考对象<b>全部是本仓库自有实现</b> ——
 *    ① 链各级的全屏绘制形状（{@code FrameApi} 里 {@code setPipeline → bindDefaultUniforms →
 *    setUniform → setVertexBuffer → draw(3,1,0,0)} 那一族）；② 逐程序绑定
 *    {@link TerrainPipelineApi#bindPackGbufferUniforms}；③ 派生管线的构造形状
 *    {@code PipelineApi.registerCompositePipeline}（同一个 {@code POST_PROCESSING_SNIPPET}）。
 *    外部候选（Iris / OptiFine / VulkanMod / Sulkan / Beryl）零接触（07-CONSTRAINTS L12）；
 *    许可证：本文件独立编写（MIT），零第三方代码并入。
 *
 * <p>1. 官方/主实现：只用公开 renderpearl API + 本仓库既有出口，<b>零 mixin</b>。
 *
 * <p>2. 备选（都否决，理由是可数的）：
 *    ① <b>重放原版穹顶几何</b> —— {@code SkyRenderer} 的 8 张顶点缓冲全是 private 且无 accessor
 *       （{@code SkyRenderer.java:67-74}），要拿到只能复制它的构建逻辑（L 系列红线，
 *       与 {@code CloudsIntoGbuffer} 当年否决的那条同形）；
 *    ② <b>mixin 进 {@code SkyRenderer} 抓它自建的 RenderPass 来绑 uniform</b> —— 可行但要多一个
 *       注入点（M1 要求逐点登记 + 一键关），而本类这条路根本不需要 pass 句柄：
 *       <b>pass 是我方自建的</b>；
 *    ③ <b>在链里算天空</b>（给包塞 {@code SKY_DEFERRED} 宏）—— 那是<b>替包做选项决定</b>，
 *       且只对声明了这个分支的包有效，属「功能名先出现、语义后补」那一族。
 *
 * <p>3. 我们的差异点：🔑 <b>为什么一次全屏能替掉穹顶</b>（这条是本类存在的全部前提，
 *    且已被离线证明，不是推断）：包的 skybasic <b>片元是屏幕空间</b>的 ——
 *    {@code gbuffers_skybasic.glsl:84-87} 逐字用
 *    {@code gl_FragCoord.xy / vec2(viewWidth, viewHeight)} + {@code gl_FragCoord.z} 过
 *    {@code gbufferProjectionInverse} 反解 {@code viewPos}，方向随后被归一化
 *    ⇒ 穹顶与全屏三角形对同一像素算出同一方向。顶点侧不读任何 attribute
 *    （{@code :148-181} 只有 {@code ftransform()} + 三条 {@code out}）⇒ 它与后处理链<b>同形</b>，
 *    所以顶点走 {@code PostVertexLinker}（包自己的 .vsh + 恒等 MVP）。
 *    证明 = {@code PackTerrainSourceTest#skybasicUsesPostVertexRouteAndNeedsOnlyOneAttachment}。
 *    🔴 <b>但「同形」不等于「那一行自动是恒等」</b>：{@code ftransform()} 展开后是
 *    {@code gbufferProjection * gbufferModelView * vec4(Position, 1.0)}，而本前端给这一族交的是<b>真相机</b>
 *    矩阵 ⇒ 直接把 NDC 三角喂进去 = 半屏巨型三角形（GAP-054 真机实测）。今天这件事由
 *    {@code PostVertexLinker} 的 <b>clip 中性化</b>那一步做掉（只换 clip 那一行，
 *    {@code sunVec}/{@code upVec} 照用真 {@code gbufferModelView}），而本类<b>实测终稿</b>、
 *    不信那个决定 —— 见 {@link #vertexStagePassesPositionThrough()}。
 *
 * <p>4. 许可证核对：本项目 MIT；零第三方代码并入。
 *
 * <p>5. 性能基线：每帧一次全屏三角形（3 顶点、1 次 draw），与原版穹顶扇（10 顶点）同量级；
 *    不测不下结论（T14 / X55）。
 *
 * <p>🔖 <b>静态状态一律走 {@code final} 原子持有者</b>：{@code StaticFieldRatchetTest} 的口径是
 *    「静态<b>非 final</b> 字段只许降」（QD-03）。这与 C1 那轮 {@code PackSamplerSuperset} 快照同一条
 *    纪律 —— 真正去处是 B2/B3 的 {@code PackSession}，换成原子持有者只是换写法，不假装债还掉了。
 *
 * <p>🔴 <b>与原版那一笔互斥</b>：本类接管的那一帧，{@code SkyRenderer.render} <b>必须不跑</b>
 *    （两层天空同框 = GAP-027 双云那一族）。R4 起这件事是<b>结构给的</b>：本类由官方
 *    {@code CustomSkyboxRenderer.renderSky} 调用，回 {@code true} 原版就不再调 {@code render}
 *    （{@code LevelRenderer:385} 的短路），不需要任何「两个读点自己协调」的纪律。
 */
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.vkdisp.VkDisp;
import dev.vkdisp.VkDispConfig;
import dev.vkdisp.VkDispVirtualPack;
import dev.vkdisp.pack.PackTerrainSource;
import dev.vkdisp.pipeline.model.PackTerrainProgram;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/** 包天空的一次全屏绘制（渲染线程独占；跨线程可见性靠下面的 final 原子持有者）。 */
public final class SkyPackGbuffer {

    /** 派生管线 location 前缀（每次重建换后缀 ⇒ 满足「改过必须换 location」契约）。 */
    private static final String BASE_LOCATION = "pipeline/gbuffer_sky";

    /** 缓存的派生管线 + 它对应的契约身份（换包 / 重新生成 ⇒ 重建）。 */
    private static final AtomicReference<@Nullable CompiledRenderPipeline> PIPELINE =
            new AtomicReference<>();
    private static final AtomicReference<@Nullable String> PIPELINE_KEY = new AtomicReference<>();

    /** 图集采样器：本类自用的一份（{@code MrtTerrainPass} 那份是 private，且属它的责任域）。 */
    private static final AtomicReference<@Nullable GpuSampler> SAMPLER = new AtomicReference<>();

    private static final AtomicBoolean TOOK_OVER = new AtomicBoolean(false);

    /** 观测面（装在持有者里，不动 QD-03 那条静态字段棘轮）：<b>不参与</b>任何判定。 */
    private static final class Stats {
        private long refusedGeometry;
    }

    private static final Stats STATS = new Stats();

    /**
     * 交进包资源的<b>那份顶点终稿</b>是否把 {@code Position} 原样交出去。
     *
     * <p>✅ 这条现在成立了，但不是把常量改成 true 改出来的：{@code PostVertexLinker} 的 clip 中性化
     * 那一步在配对时把包顶点的 {@code gl_Position = gbufferProjection * gbufferModelView
     * * vec4(Position, 1.0)}（实测 {@code build/dump-head/world0_gbuffers_skybasic-VERTEX.glsl:240}）
     * <b>整行</b>换成 {@code vec4(Position, 1.0)} —— 只动 clip，{@code sunVec}/{@code upVec} 照用真
     * {@code gbufferModelView}（那两个 varying 决定 {@code sunVisibility}/{@code moonVisibility}，
     * 中性化若顺手把整条程序的矩阵都砍了，太阳方向就没了）。
     *
     * <p>🔖 本方法读的是<b>落进包资源的那份文本</b>的量出结果（生成期量一次，见
     * {@code PackTerrainSource#linkPostVertexRoute} 与 {@code GbufferVertexRoute}）⇒ 中性化没发生
     * （包里 clip 写成跨行、或没有 Position 属性）时这里就是 false，几何闸门照旧拒绝，
     * 天空退回原版重放。
     */
    private static boolean vertexStagePassesPositionThrough() {
        return dev.vkdisp.pack.GbufferVertexRoute.passesPositionThrough(PackTerrainSource.SKY_PROGRAM);
    }

    /**
     * 这一族的 {@code gbufferModelView}/{@code gbufferProjection} 是<b>恒等</b>？
     *
     * <p>🔴 也不是：{@code OfUniformManager.putCameraMatrices} 第 <b>377-378</b> 行交的是<b>真·相机</b>
     * 矩阵，{@code Family.CHAIN} 只翻深度口径（{@code glDepthConvention} ⇒ {@code convention.forward}），
     * 不翻成恒等。⇒ 天空被分到 CHAIN 族（{@code GbufferVertexSources.builtinsFamily}）只解决了
     * 「片元按 GL 口径写分支」，<b>顶点那一族仍被真投影打过一遍</b>。
     */
    private static final boolean CAMERA_MATRICES_ARE_IDENTITY = false;

    /**
     * 「喂一张 NDC 全屏大三角」这条几何路线什么时候成立（{@code GAP-054} 的判据本体，<b>纯函数</b>）。
     *
     * <p>两条途径任一即可：① 顶点终稿把 {@code Position} 原样交出去（我方 passthrough 顶点），
     * 或 ② 这次 gather 给这一族供的是<b>恒等</b> view/projection。两条都不成立时，那张
     * {@code (-1,-1),(3,-1),(-1,3)} 的大三角会被当成<b>视空间 z=1 的世界几何</b>过一遍透视
     * ⇒ 屏幕上留下一个切过半个画面的巨型三角形（真机实测：默认档、镜头朝地平线，
     * 左半一堵被拉到巨大的草/叶纹理墙，边界是一条竖直边 + 一条斜边）。
     *
     * <p>🔖 为什么写成整数决策之外的<b>布尔或</b>而不是一张表：这里只有「能不能喂全屏三角」
     * 一个问题，两个入参就是它的全部自由度（口径同 {@code MainPassTakeover.depthDecision}）。
     */
    static boolean ndcTriangleRouteValid(boolean vertexStagePassesPositionThrough,
            boolean cameraMatricesAreIdentity) {
        return vertexStagePassesPositionThrough || cameraMatricesAreIdentity;
    }

    /** 诊断读数：因几何路线不成立而交回原版的次数（不参与判定）。 */
    public static long refusedGeometryCount() {
        return STATS.refusedGeometry;
    }

    /** 只报一次的闸门（接管与退回各一次；刷屏会把别的行挤掉，h34/h33 那一族）。 */
    private static final AtomicBoolean REPORTED = new AtomicBoolean(false);

    private static final AtomicLong DRAWS = new AtomicLong();

    /** 天空全屏顶点缓冲（建一次；换设备/重建的情况由调用方在异常路径退回原版档）。 */
    private static final AtomicReference<@Nullable GpuBuffer>
            SKY_VERTICES = new AtomicReference<>();

    private SkyPackGbuffer() {
    }

    /**
     * 本帧是否<b>具备</b>由包天空接管的条件（开关 + 契约）。
     *
     * <p>🔴 三个条件缺一不可：{@code mrt.skyPackShader} 开着、{@code mrt.skyPass} 也开着
     * （本类接管的是<b>那一笔</b>，天空进不进 gbuffer 的总闸仍是它）、且该程序真的接线
     * （契约在、输出恰好单槽 0）。不满足时<b>退回原版重放</b>并点名原因 —— 不静默换绑（T11）。
     */
    public static boolean enabled() {
        if (!VkDispConfig.MRT_SKY_PACK_SHADER.get() || !VkDispConfig.MRT_SKY_PASS.get()) {
            return false;
        }
        return contractProblem() == null;
    }

    /** 契约可用性判定；返回 {@code null} = 可用，否则返回<b>不可用的原因</b>（自报用）。 */
    private static @Nullable String contractProblem() {
        // 🔴 GAP-054：几何路线先要对，才轮到契约。这一条排在最前，因为它拒的是「画出来一半屏幕」
        //    这种<b>不报错的错</b>（无 validation layer ⇒ 错几何照样提交），而契约那两条会响亮地抛。
        boolean passthrough = vertexStagePassesPositionThrough();
        if (!ndcTriangleRouteValid(passthrough, CAMERA_MATRICES_ARE_IDENTITY)) {
            STATS.refusedGeometry++;
            return "包天空的<b>顶点终稿</b>里 clip 写入没有把 Position 原样交出（实测 passthrough=" + passthrough
                    + "），而本前端这一族供的是<b>真·相机</b>矩阵（Camera_MATRICES_ARE_IDENTITY="
                    + CAMERA_MATRICES_ARE_IDENTITY + "）⇒ 我方喂的 NDC 全屏大三角会被当成视空间几何"
                    + "过一遍透视，在屏幕上留下一个切过半个画面的巨型三角形（GAP-054 真机实测）。"
                    + " 中性化没发生的两种形状：① 包里那条 clip 赋值写成<b>跨行</b>形态"
                    + "（本前端只认整行单语句，改了会把源改坏）；② 包顶点没有 Position/vaPosition 属性"
                    + "（那就没有「屏幕坐标从哪来」的答案，不猜）。"
                    + " 另一条修法在 GAP-028 ③：按 draw 供 renderStage 并把包天空改喂<b>原版天空几何</b>。";
        }
        PackTerrainProgram program = VkDispVirtualPack.packContract(PackTerrainSource.SKY_PROGRAM);
        if (program == null) {
            return "包没有可用的 gbuffers_skybasic 契约（转译/编译失败，或包里就没有这条程序）";
        }
        if (program.outputCount() != 1 || !List.of(0).equals(program.declaredOutputSlots())) {
            return "天空片元的输出不是单槽 0（outputs=" + program.outputCount()
                    + " slots=" + program.declaredOutputSlots()
                    + "）⇒ 管线附件数与 pass 不匹配，setPipeline 会当场抛";
        }
        return null;
    }

    /**
     * 画一次包天空。
     *
     * @param color colortex0 的<b>待写那一代</b>视图（代次口径由 {@code SkyIntoGbuffer} 维护，
     *              本类不翻代 —— 翻了地形就会盖到另一代上）
     * @param depth <b>gbuffer 那张</b>深度视图（R4 起天空与地形共用一张，不再有私有空白深度）。
     *              天空对它<b>既不测也不写</b>：原版五条天空管线的 {@code depthStencilState} 都是
     *              {@code null}（{@code RenderPipeline.java:456} 核实），本类那条是
     *              {@code ALWAYS_PASS} + 不写 ⇒ 挂上它只为了附件描述与后续 pass 一致，
     *              深度的清屏与 {@code 1 − z} 换算仍各只有一次（{@code GAP-037} 的口径）。
     *              🔴 <b>不能传 null</b>：实测那样会让客户端以 exit 1 直接死掉
     *              （日志最后一行就是本类的 builtins 上传 ⇒ 崩在这条 pass 上），
     *              本前端没有 validation layer 所以没有第二句话可看。
     * @return 真的画了返回 true；否则 false，<b>调用方交回原版那一笔</b>（其视图由 M-11 换到同一张图）
     */
    public static boolean render(GpuTextureView color, GpuTextureView depth) {
        String why = contractProblem();
        if (why != null) {
            reportOnce("mrt.skyPackShader=true 但**不接管**，本帧回到原版 SkyRenderer 重放: " + why);
            return false;
        }
        CompiledRenderPipeline compiled = pipeline();
        if (compiled == null) {
            return false;
        }
        if (TOOK_OVER.compareAndSet(false, true)) {
            VkDisp.LOGGER.info("vkdisp: [GAP-027/sky] 天空改由<b>包的 gbuffers_skybasic</b> 画"
                    + "（一次全屏 draw，屏幕空间片元）；原版 SkyRenderer 那一笔本帧<b>不跑</b>。"
                    + " 几何路线前提实测: clipPassthrough={} draws={}",
                    vertexStagePassesPositionThrough(), DRAWS.get());
        }
        // 🔖 逐程序 builtins 环必须在**开 pass 之前**写：写环要 map 缓冲（要新 encoder），
        //   而 render pass 打开期间建新 encoder 会被 RenderPearl 拒（SkyIntoGbuffer 同一条 h10 规则）。
        // 🔴 自定义纹理（GAP-009/025）必须在**开 pass 之前**就绪：本 pass 跑在地形之前，
        //   而 {@code MrtTerrainPass} 里那条 ensureReady 在它自己 pass 的**内部**才调
        //   ⇒ 首帧 NOISE_2D 分支会回落到 builtinNoiseView()，而它在 render pass 打开期间
        //   新建 encoder ⇒ 原版抛 "Close the existing render pass..."（h48y 同一条实测）。
        //   本方法幂等（指纹相同即早退）。
        PackTextures.ensureReady();
        VolumeStubs.init();
        TerrainPipelineApi.updateGbufferBuiltins(PackTerrainSource.SKY_PROGRAM);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "vkdisp gbuffer pack sky",
                color, Optional.of(new Vector4f(0.0F, 0.0F, 0.0F, 1.0F)),
                depth, OptionalDouble.empty())) {
            pass.setPipeline(compiled);
            RenderSystem.bindDefaultUniforms(pass);
            TerrainPipelineApi.bindPackGbufferUniforms(pass, ensureSampler(),
                    MrtTerrainPass.blockAtlas(), depth, color, PackTerrainSource.SKY_PROGRAM);
            pass.setVertexBuffer(0, skyVertexBuffer().slice());
            // 🔬 GAP-037：这一笔的深度口径来自**顶点常量**（Position.z 写死 1.0），
            //   与代理那趟全屏 1−z 是两个独立的所有者 —— 两个都记上，读数才会说「翻了两次」。
            dev.vkdisp.bridge.DepthFlipLedger.note("SkyPackGbuffer 顶点常量 Position.z=1.0");
            pass.draw(3, 1, 0, 0);
        } catch (Throwable t) {
            // 🔴 退回不是「静默换绑」：这一帧画不出来就交给原版重放，但必须点名 + 只点一次。
            reportOnce("包天空这一路执行失败 ⇒ 回到原版重放（异常见下）: " + t, t);
            return false;
        }
        DRAWS.incrementAndGet();
        return true;
    }

    /** 派生管线（懒建 + 按契约身份重建）。返回 {@code null} = 本帧不接管。 */
    private static @Nullable CompiledRenderPipeline pipeline() {
        PackTerrainProgram program = VkDispVirtualPack.packContract(PackTerrainSource.SKY_PROGRAM);
        if (program == null) {
            return null;
        }
        String key = program.qualifiedName() + "|" + System.identityHashCode(program);
        CompiledRenderPipeline cached = PIPELINE.get();
        if (cached != null && key.equals(PIPELINE_KEY.get())) {
            return cached;
        }
        try {
            RenderPipeline built = RenderPipeline
                    .builder(net.minecraft.client.renderer.RenderPipelines.POST_PROCESSING_SNIPPET)
                    .withLocation(net.minecraft.resources.Identifier.fromNamespaceAndPath(
                            VkDisp.MOD_ID, BASE_LOCATION + "_" + Integer.toHexString(key.hashCode())))
                    .withVertexShader(VkDispVirtualPack.packAdapterShaderId(PackTerrainSource.SKY_PROGRAM))
                    .withFragmentShader(VkDispVirtualPack.packFragmentShaderId(PackTerrainSource.SKY_PROGRAM))
                    // 🔴 顶点绑定必须声明：包自己的 post VSH 要按名读到 Position（引擎按
                    //   element.name() 配对，与链各级同一条事实；见 PipelineApi.POST_VERTEX_FORMAT）。
                    //   实测漏声明的形状 = ShaderCompileException: attrib (Position) does not have a
                    //   matching vertex buffer element ⇒ compilePipeline 回 null，而 getCompiledPipeline
                    //   抛的是「Failed to find or load pipeline」（措辞会误导归因）。
                    .withVertexBinding(0, PipelineApi.POST_VERTEX_FORMAT)
                    .withBindGroupLayout(TerrainPipelineApi.packBindGroupLayout(program))
                    // 🔴 深度必须**显式**关掉（实测：常数色探针写进 colortex0 仍是 (0,0,0) ⇒
                    //   坏在通路不在数学）。原版五条天空管线的 depthStencilState 都是 null，
                    //   而我方这条 pass 挂了一张**从未清过**的私有深度（内容未定义）⇒
                    //   按默认比较函数走就是「全部片元被深度拒」⇒ 零写入。
                    //   比较函数用 ALWAYS_PASS、写深度 false：与原版语义等价，且把意图写进源码。
                    .withDepthStencilState(new com.mojang.renderpearl.api.pipeline.DepthStencilState(
                            com.mojang.renderpearl.api.pipeline.CompareOp.ALWAYS_PASS, false))
                    .withColorTargetState(ColorTargetState.DEFAULT)
                    .build();
            // 派生管线**不需要**预先 RegisterRenderPipelinesEvent 注册：PipelineCache 未命中就地编译
            //   （派生地形管线同一条事实）。但编译是异步的 ⇒ 没落地就本帧返回 null，
            //   调用方退回原版重放，**不画半张天空**。
            CompiledRenderPipeline compiled = RenderSystem.getCompiledPipeline(built);
            if (compiled == null) {
                return null;
            }
            PIPELINE.set(compiled);
            PIPELINE_KEY.set(key);
            VkDisp.LOGGER.info("vkdisp: [GAP-027/sky] 派生天空管线已建: program={} fragment={} vertex={}",
                    program.qualifiedName(),
                    VkDispVirtualPack.packFragmentShaderId(PackTerrainSource.SKY_PROGRAM),
                    VkDispVirtualPack.packAdapterShaderId(PackTerrainSource.SKY_PROGRAM));
            return compiled;
        } catch (Throwable t) {
            reportOnce("派生天空管线建立失败 ⇒ 回到原版重放（异常见下）", t);
            return null;
        }
    }

    /**
     * 天空自己的全屏三角形缓冲。🔴 <b>不能复用 {@code FrameApi.postVertexBuffer()}</b>：
     * 那份缓冲把 {@code Position} 写成 {@code (x, y, 0.0, 1.0)}（{@code FrameApi:661}），
     * 而恒等 MVP 下 {@code gl_Position = vec4(Position,1)} ⇒ 设备深度 {@code gl_FragCoord.z = 0}。
     * 链各级不读 {@code gl_FragCoord.z} ⇒ 对它们无害；但包的 skybasic <b>读</b>它
     * （{@code gbuffers_skybasic.glsl:84-87} 把它当屏幕深度过 {@code gbufferProjectionInverse}）
     * ⇒ {@code z=0} 在 GL 口径下是<b>近平面</b>，反解出的 {@code viewPos} 长度只有 ~near，
     * 于是 {@code DrawStars} 的噪声坐标 {@code viewPos.xz/256 + viewPos.y/128} 对<b>所有</b>像素
     * 塌成同一个纹素 ⇒ 星点整片消失（真机夜里档实测：无星 + 天顶一圈不连续）。
     * 这里把 z 写成 1.0 = GL 口径的远平面，与包对「天空像素」的预期一致。
     */
    private static GpuBuffer skyVertexBuffer() {
        GpuBuffer cached = SKY_VERTICES.get();
        if (cached != null) {
            return cached;
        }
        List<String> names = PipelineApi.postVertexAttributeNames();
        int stride = PipelineApi.postVertexStride();
        java.nio.ByteBuffer data = java.nio.ByteBuffer
                .allocateDirect(FULLSCREEN_VERTICES.length * stride)
                .order(java.nio.ByteOrder.nativeOrder());
        for (float[] vertex : FULLSCREEN_VERTICES) {
            for (String name : names) {
                putAttribute(data, name, vertex);
            }
        }
        data.flip();
        GpuBuffer buffer = RenderSystem.getDevice()
                .createBuffer(() -> "vkdisp sky fullscreen",
                        GpuBuffer.USAGE_VERTEX, data);
        SKY_VERTICES.set(buffer);
        VkDisp.LOGGER.info("vkdisp: [GAP-027/sky] 天空全屏缓冲已建: stride={} attributes={} z=1.0(远平面)",
                stride, names.size());
        return buffer;
    }

    /** 与 {@code FrameApi.POST_FULLSCREEN_VERTICES} 同一张三角形（NDC 大三角），只是 z 由本类写。 */
    private static final float[][] FULLSCREEN_VERTICES = {
            {-1.0F, -1.0F, 0.0F, 0.0F},
            {3.0F, -1.0F, 2.0F, 0.0F},
            {-1.0F, 3.0F, 0.0F, 2.0F},
    };

    /** 逐属性写：每个元素固定 4 个 float（与 {@code PipelineApi.POST_VERTEX_FORMAT} 同口径）。 */
    private static void putAttribute(java.nio.ByteBuffer data, String name, float[] vertex) {
        switch (name) {
            // 🔴 Position 的 z = 1.0（本类存在的全部理由），w = 1.0 ⇒ 恒等 MVP 下 NDC z = 1
            case "Position" -> data.putFloat(vertex[0]).putFloat(vertex[1]).putFloat(1.0F).putFloat(1.0F);
            case "UV0" -> data.putFloat(vertex[2]).putFloat(vertex[3]).putFloat(0.0F).putFloat(1.0F);
            default -> data.putFloat(0.0F).putFloat(0.0F).putFloat(0.0F).putFloat(1.0F);
        }
    }

    private static GpuSampler ensureSampler() {
        GpuSampler cached = SAMPLER.get();
        if (cached != null) {
            return cached;
        }
        // 🔖 maxLod 与地形那条同源（GAP-016：本引擎里隐式导数会选到坏 mip ⇒ 采样恒 0）。
        boolean lod0 = VkDispConfig.MRT_TERRAIN_ATLAS_LOD0.get();
        GpuSampler created = RenderSystem.getDevice().createSampler(
                AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR, FilterMode.LINEAR, 1,
                lod0 ? OptionalDouble.of(0.0) : OptionalDouble.empty());
        SAMPLER.set(created);
        return created;
    }

    private static void reportOnce(String message) {
        reportOnce(message, null);
    }

    private static void reportOnce(String message, @Nullable Throwable cause) {
        if (REPORTED.compareAndSet(false, true)) {
            if (cause == null) {
                VkDisp.LOGGER.warn("vkdisp: [GAP-027/sky] {}", message);
            } else {
                VkDisp.LOGGER.warn("vkdisp: [GAP-027/sky] {}", message, cause);
            }
        }
    }

    /** 自报用：本类接管以来累计画了几帧。 */
    public static long drawCount() {
        return DRAWS.get();
    }
}
