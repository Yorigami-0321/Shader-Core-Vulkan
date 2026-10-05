package dev.vkdisp.bridge;
/**
 * 【参考调研】H 线 M-01 派生地形管线构造 + 绑定 / 原版 {@code RenderPipelines} 地形 snippet + 原版 {@code MappableRingBuffer}
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 原版 26.3 {@code RenderPipelines.TERRAIN_SNIPPET} /
 *    {@code MULTIDRAW_TERRAIN_SNIPPET}（以 {@code RenderPipeline.builder(snippet)} 为基底的官方派生写法，
 *    与 {@code SOLID_BLOCK}/{@code CUTOUT_BLOCK} 的 {@code withShaderDefine} / {@code withColorTargetState}
 *    用法同源）；② 原版 {@code net.minecraft.client.renderer.MappableRingBuffer}（uniform 环的官方实现，
 *    用法照 {@code PostPass}：构造 → {@code currentBuffer().map(false,true)} 写 → {@code close} 落盘 →
 *    {@code RenderPass.setUniform(name, buffer)}）。
 *    许可证：Mojang EULA（原版）。**只观察公开 API 的调用形状，不复制实现语句、不搬运着色器文本。**
 *    → 能否并入本项目（MIT）：可以 —— 本文件是独立编写的桥接封装
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：原版 {@code RenderPipeline.builder(snippet).withLocation(...).build()} 派生范式；
 *    {@code BindGroupLayout.builder().withUniform(name, UNIFORM_BUFFER).build()} 加自定义块（GAP-004）。
 * 2. 备选：AccessTransformer / 改原版字段 —— 否决（07-CONSTRAINTS M1：只许动「管线装配层」，
 *    改原版 {@code RenderPipelines} 静态初始化属于侵入原版状态，且 GAP-003 ①②已源码级证伪该路线）。
 * 3. 我们的差异点：① 参数差异全部来自纯数据表 {@code pipeline.model.TerrainDerivedPlan}（可单测）；
 *    ② 每个注入点各有独立入口（{@link #onWireTerrainHit()} / {@link #onBindTerrainParamsHit}）与
 *    独立配置键，满足 M1「逐个开启 + 逐个关闭」；③ 绑定动作只在「两个 override 形参都为 null」
 *    时执行 —— 那正是原版唯一会走 {@code layer.pipeline(...)} 的分支，与 M-01 的替换条件严格对称。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制（07-CONSTRAINTS §〇 P1、L5-L8）。
 * 5. 性能基线：注册为❄️ 冷路径（资源加载期）；绑定为热路径但每帧仅 2–3 次
 *    {@code setUniform}，不引入分配、不做 per-frame map（理由见 {@link #terrainParamsRing()}）。
 */
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import dev.vkdisp.VkDisp;
import dev.vkdisp.VkDispConfig;
import dev.vkdisp.pipeline.model.MrtPlan;
import dev.vkdisp.pipeline.model.PackTerrainProgram;
import dev.vkdisp.pipeline.model.TerrainDerivedPlan;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import org.jspecify.annotations.Nullable;

/**
 * H 线 M-01：把**派生地形管线**接到地形 draw 上（GAP-003 的通道 + GAP-004 的自定义 uniform 块）。
 *
 * <p><b>本类的边界（很关键，读错会以为已经做完 GAP-003）</b>：
 * <ul>
 *   <li>✅ <b>通道已通</b>：原版 {@code ChunkSectionLayer#pipeline(boolean)} 返回值可被替换，
 *       派生管线因此真的被地形 draw 用上（M-01 注入点，见 {@code 04-SPEC.md} §5.0）。</li>
 *   <li>🟡 <b>GAP-004 只做到「块能挂上去且每帧绑上」</b>：本轮片元仍是原版 {@code core/terrain}，
 *       它不读这个块 ⇒ **块被绑定但未被消费**。消费要等 GAP-003 那轮换自研 gbuffer 片元。</li>
 *   <li>⛔ <b>GAP-003 未做</b>：多附件管线要求 render pass 同时有 N 个颜色附件，而原版地形 pass
 *       （{@code LevelRenderer.addMainPass}）只有 1 个（源码级核实，见证据文件）。
 *       ⇒ 多附件必须先拿到 pass 的所有权（第二个注入点，已登记为 M-04，⏸️ 未开始）。</li>
 * </ul>
 */
public final class TerrainPipelineApi {

    /** 自定义 uniform 块名（纯字符串视图；GLSL 侧块名必须与之一致，GAP-004）。 */
    public static final String TERRAIN_PARAMS_UNIFORM = TerrainDerivedPlan.PARAMS_UNIFORM;

    /** 已注册派生管线：key = {@code 层名 + "|" + multiDraw}（顺序稳定，便于日志与计数核对）。 */
    private static final Map<String, RenderPipeline> DERIVED = new LinkedHashMap<>();

    /**
     * GAP-003 方案 A：**多附件**变体（与 {@link #DERIVED} 同键空间）。
     *
     * <p>🔖 <b>为什么要两套</b>：同一批地形 draw 在两种 pass 里跑 ——
     * 原版主 pass（**单**附件，颜色目标是 main）与我方 gbuffer pass（**多**附件）。
     * Vulkan 要求管线颜色附件数与 render pass 附件数**相等** ⇒ 两条 pass 必须用两条不同的管线。
     * 而 M-01 的注入点只知道「谁在调我」，不知道 pass ⇒ 靠 {@link MrtTerrainPass#active()} 区分。
     */
    private static final Map<String, RenderPipeline> DERIVED_MRT = new LinkedHashMap<>();

    /** 多附件变体的「已被取用」标记（每条只打一次日志）。 */
    private static final Map<String, Boolean> WIRED_MRT = new LinkedHashMap<>();

    /** 不可识别层名的一次性告警（防拼写错静默通过，07-CONSTRAINTS T11）。 */
    private static final java.util.Set<String> WARNED_UNKNOWN_LAYERS = new java.util.HashSet<>();

    /** M-01 命中计数（注入点是否真被走到 = 静默失败的头号判据，01-DEV-LOOP §5.1）。 */
    private static final AtomicLong WIRE_HITS = new AtomicLong();

    /** M-01b 命中计数。 */
    private static final AtomicLong BIND_HITS = new AtomicLong();

    /** 派生管线是否真被原版 {@code RenderSystem.getCompiledPipeline} 编译过（first-bind 判据）。 */
    private static final Map<String, Boolean> WIRED = new LinkedHashMap<>();

    /** 自定义 uniform 环（GAP-004；懒建，必须在渲染线程 / 设备就绪后）。 */
    private static MappableRingBuffer paramsRing;

    /**
     * GAP-003：包地形片元 {@code VkDispBuiltins} 块的环（懒建；每帧写 —— 块终于有真消费者了）。
     *
     * <p>🔖 与 {@link #paramsRing} 分开的原因：块布局不同（地形片的收编集与 composite 各不相同）
     * ⇒ 字节数不同、成员不同；共用一个环要么装不下，要么写错成员。
     */
    private static MappableRingBuffer terrainBuiltinsRing;

    /** 地形块环的字节下限（与 FrameApi 的 BUILTINS_MIN_BYTES 同口径：std140 对齐后仍够写）。 */
    private static final int TERRAIN_BUILTINS_MIN_BYTES = 256;

    private TerrainPipelineApi() {
    }

    /** 派生管线规格（纯数据视图，供注册循环与日志使用）。 */
    public static java.util.List<TerrainDerivedPlan.Spec> specs() {
        return TerrainDerivedPlan.all();
    }

    /**
     * 注册全部 6 条派生地形管线。
     *
     * <p><b>逐条 try/catch 是刻意的</b>（T11）：一条失败不能把另外 5 条的成败一起吞掉，
     * 否则「6 条里错了 1 条」会表现成「派生管线全灭」。
     */
    public static void registerTerrainDerivedPipelines(RegisterRenderPipelinesEvent event) {
        for (TerrainDerivedPlan.Spec spec : TerrainDerivedPlan.all()) {
            String key = key(spec.layer(), spec.multiDraw());
            try {
                RenderPipeline.Builder builder = spec.multiDraw()
                        ? RenderPipeline.builder(RenderPipelines.MULTIDRAW_TERRAIN_SNIPPET)
                        : RenderPipeline.builder(RenderPipelines.TERRAIN_SNIPPET);
                // 🔴 h20 缺陷②：接线用的派生管线**必须不带**自定义绑定组。
                //   VkDispTerrainParams 只在 MrtTerrainPass.drawTerrain 里绑定；
                //   而 M-01 把这条管线交回给**原版**地形绘制路径 —— 那条路上没人绑这个组。
                //   一条管线带着「只有某个消费者会准备」的状态交给另一个消费者 = 状态泄漏温床。
                //   🔺 自定义 uniform 块由 GAP-003 的 **MRT 变体**管线承载（那是唯一会画的地方）。
                builder.withLocation(Identifier.fromNamespaceAndPath(
                        TerrainDerivedPlan.NAMESPACE, spec.location()
                                .substring((TerrainDerivedPlan.NAMESPACE + ":").length())));
                if (spec.hasAlphaCutout()) {
                    builder.withShaderDefine("ALPHA_CUTOUT", spec.alphaCutout());
                }
                builder.withColorTargetState(spec.translucentBlend()
                        ? new ColorTargetState(BlendFunction.TRANSLUCENT)
                        : ColorTargetState.DEFAULT);
                RenderPipeline pipeline = builder.build();
                event.registerPipeline(pipeline);
                // 纳入 P1.2 的 registered==compiled 口径：派生管线编译失败必须显式暴露（T11）。
                PipelineApi.recordTerrainDerived(pipeline);
                DERIVED.put(key, pipeline);
                WIRED.put(key, Boolean.FALSE);
                VkDisp.LOGGER.info(
                        "vkdisp: [M-01] terrain derived pipeline registered: layer={} multiDraw={} location={}"
                                + " alphaCutout={} translucentBlend={}",
                        spec.layer(), spec.multiDraw(), spec.location(),
                        spec.hasAlphaCutout() ? String.valueOf(spec.alphaCutout()) : "<none>",
                        spec.translucentBlend());
            } catch (Throwable t) {
                VkDisp.LOGGER.error("vkdisp: [M-01] terrain derived pipeline registration failed: layer={} multiDraw={}",
                        spec.layer(), spec.multiDraw(), t);
            }
        }
    }

    /** 已注册派生管线数（供计数断言与日志）。 */
    public static int terrainDerivedPipelineCount() {
        return DERIVED.size();
    }

    /** M-01 开关（{@code mixin.wireTerrain}）。每个注入点一个键 = M1 编码约束 ⑤「一键关闭」。 */
    public static boolean wireTerrainEnabled() {
        return VkDispConfig.MIXIN_WIRE_TERRAIN.get();
    }

    /** M-01b 开关（{@code mixin.bindTerrainParams}）。 */
    public static boolean bindTerrainParamsEnabled() {
        return VkDispConfig.MIXIN_BIND_TERRAIN_PARAMS.get();
    }

    /**
     * 注册 GAP-003 方案 A 的**多附件**地形派生管线（每条 = 单附件版的同层同变体 + N 个颜色附件）。
     *
     * <p>🔖 <b>逐条 try/catch 是刻意的</b>（同 {@link #registerTerrainDerivedPipelines}）：
     * 任一条构造失败时其余 5 条仍应可用；合并计数会把「一条坏」表现成「全灭」。
     */
    public static void registerTerrainDerivedMrtPipelines(RegisterRenderPipelinesEvent event) {
        // 🔖 GAP-003：本次是否换包地形片元 + **冻结附件数**。
        //   冻结发生在**注册这一刻**（而不是每帧现算），因为管线颜色目标数与 render pass
        //   附件数必须恒等；两者若各自现算，就会出现「注册读 3、绘制读 1」⇒ setPipeline 抛
        //   IllegalStateException **崩客户端**（X42）。注册后两侧读同一个冻结值。
        PackTerrainProgram packTerrain = packTerrainForMrt();
        MrtPlan.freezePackOutputCount(packTerrain == null ? 0 : packTerrain.outputCount());
        if (packTerrain != null) {
            VkDisp.LOGGER.info(
                    "vkdisp: [GAP-003] MRT terrain pipelines will use pack fragment: program={}"
                            + " colorTargets={} samplers={} varyings={}",
                    packTerrain.qualifiedName(), MrtPlan.slotCount(),
                    packTerrain.fragmentSamplers().size(), packTerrain.inputs().size());
        }
        for (TerrainDerivedPlan.Spec spec : TerrainDerivedPlan.all()) {
            String mrtKey = key(spec.layer(), spec.multiDraw());
            try {
                RenderPipeline.Builder builder = spec.multiDraw()
                        ? RenderPipeline.builder(RenderPipelines.MULTIDRAW_TERRAIN_SNIPPET)
                        : RenderPipeline.builder(RenderPipelines.TERRAIN_SNIPPET);
                builder.withLocation(Identifier.fromNamespaceAndPath(
                                TerrainDerivedPlan.NAMESPACE,
                                spec.location().substring((TerrainDerivedPlan.NAMESPACE + ":").length()) + "_mrt"))
                        .withBindGroupLayout(BindGroupLayout.builder()
                                .withUniform(TERRAIN_PARAMS_UNIFORM, UniformType.UNIFORM_BUFFER)
                                .build())
                        // 🔖 与 MrtTerrainPass 建的多附件 pass 附件数**必须相等**（Vulkan 要求）。
                        // 两侧都取 MrtPlan.slotCount() —— 单点真源，避免「一处改了一处没改」。
                        .withColorTargetStates(0, MrtPlan.slotCount() - 1, () -> ColorTargetState.DEFAULT);
                if (packTerrain != null) {
                    // 🔖 GAP-003：换成「顶点适配层 + 包自己的片元」。
                    //   顶点侧不能直接用包的 vsh —— 它要 7 个顶点属性（含 Normal / mc_Entity /
                    //   mc_midTexCoord），而原版地形顶点缓冲 DefaultVertexFormat.BLOCK 只有 4 个；
                    //   改网格化属另一层工程。适配层按原版格式取数、逐位置对齐产出包的 9 条 varying。
                    builder.withVertexShader(TERRAIN_PACK_ADAPTER_ID)
                            .withFragmentShader(PACK_TERRAIN_FRAGMENT_ID)
                            // 布局必须**逐条**登记包片元自由声明的 sampler：STRICT_VALIDATION 下
                            // draw() 按布局校验，SPIR-V 反射出的每个名字查不到即抛（实测原文：
                            // Unable to find shader defined uniform）。反向（布局多于 SPIR-V）无害。
                            .withBindGroupLayout(packTerrainBindGroupLayout(packTerrain));
                }
                if (spec.hasAlphaCutout()) {
                    builder.withShaderDefine("ALPHA_CUTOUT", spec.alphaCutout());
                }
                builder.withColorTargetState(spec.translucentBlend()
                        ? new ColorTargetState(BlendFunction.TRANSLUCENT)
                        : ColorTargetState.DEFAULT);
                RenderPipeline pipeline = builder.build();
                event.registerPipeline(pipeline);
                // 纳入 registered==compiled 口径：MRT 变体编译不过必须显式暴露（否则地形会静默退回）。
                PipelineApi.recordTerrainDerived(pipeline);
                DERIVED_MRT.put(mrtKey, pipeline);
                WIRED_MRT.put(mrtKey, Boolean.FALSE);
            } catch (Throwable t) {
                VkDisp.LOGGER.error(
                        "vkdisp: [GAP-003/A] terrain MRT derived pipeline registration failed: layer={} multiDraw={}",
                        spec.layer(), spec.multiDraw(), t);
            }
        }
        VkDisp.LOGGER.info("vkdisp: [GAP-003/A] terrain MRT derived pipelines registered: {}/6 (colorTargets={})",
                DERIVED_MRT.size(), MrtPlan.slotCount());
    }

    /**
     * 顶点适配层着色器 id —— 由虚拟包**按包地形片元的 varying 契约生成**后提供。
     *
     * <p>🔖 为什么不是本模组自己的静态资产：实测 BSL 默认配置要 9 条 varying、开
     * {@code ADVANCED_MATERIALS} 后要 <b>15</b> 条；静态适配层对另一个配置就是「少供」⇒
     * 驱动层在资源加载期抛 {@code ShaderCompileException: missing output at location 14}
     * ⇒ <b>客户端起不来</b>（本轮真实踩到）。生成物与片元源同生共死，杜绝半接线。
     */
    private static final Identifier TERRAIN_PACK_ADAPTER_ID =
            Identifier.fromNamespaceAndPath(dev.vkdisp.VkDispVirtualPack.NAMESPACE,
                    "terrain_pack_adapter");

    /** 包地形片元 id（虚拟资源包提供的 shaders/gbuffers_terrain.fsh）。 */
    private static final Identifier PACK_TERRAIN_FRAGMENT_ID =
            Identifier.fromNamespaceAndPath(dev.vkdisp.VkDispVirtualPack.NAMESPACE, "gbuffers_terrain");

    /** 「不接包片元」的一次性告警哨兵（默认关 / 无包地形片元，两种原因要分开说）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean PACK_TERRAIN_OFF_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** 本次 MRT 地形管线是否改用包自己的片元；不接时返回 {@code null}（沿用原版 core/terrain）。 */
    @Nullable
    static PackTerrainProgram packTerrainForMrt() {
        // 🔖 注册期早于 openResources 约 4.5 秒（runClient 实测），且切包重载时本事件不再触发
        //   ⇒ 必须在**这里**先把契约算出来，否则这条路径永远拿不到包片元、且不报错。
        dev.vkdisp.VkDispVirtualPack.ensureTerrainProgram();
        if (!dev.vkdisp.VkDispConfig.MRT_PACK_TERRAIN_SHADER.get()) {
            if (PACK_TERRAIN_OFF_LOGGED.compareAndSet(false, true)) {
                VkDisp.LOGGER.info("vkdisp: [GAP-003] pack terrain fragment disabled by config"
                        + " (mrt.packTerrainShader=false) -> MRT terrain pipeline keeps vanilla core/terrain");
            }
            return null;
        }
        PackTerrainProgram program = dev.vkdisp.VkDispVirtualPack.terrainProgram();
        if (program == null) {
            if (PACK_TERRAIN_OFF_LOGGED.compareAndSet(false, true)) {
                VkDisp.LOGGER.warn("vkdisp: [GAP-003] pack terrain fragment requested but unavailable"
                        + " (no pack gbuffers_terrain selected) -> MRT terrain pipeline keeps vanilla core/terrain");
            }
            return null;
        }
        PACK_TERRAIN_OFF_LOGGED.set(false);
        return program;
    }

    /** 包地形片元的绑定组布局：VkDispBuiltins 块 + 它自由声明的每个 sampler。 */
    private static BindGroupLayout packTerrainBindGroupLayout(PackTerrainProgram program) {
        BindGroupLayout.Builder builder = BindGroupLayout.builder();
        for (String name : program.bindGroupUniformNames()) {
            builder = name.equals(PackTerrainProgram.BUILTINS_BLOCK)
                    ? builder.withUniform(name, UniformType.UNIFORM_BUFFER)
                    : builder.withUniform(name, UniformType.COMBINED_IMAGE_SAMPLER);
        }
        return builder.build();
    }

    /**
     * M-01 注入点入口：把「原版层名 + multiDraw」解析成派生管线（**只查表，不写渲染逻辑**）。
     *
     * @return 派生管线；开关关闭 / 未注册 / 层名不可识别 → {@code null}（调用方必须继续走原版返回值）
     */
    public static RenderPipeline derivedTerrainPipeline(String layer, boolean multiDraw) {
        // 🔖 GAP-003 方案 A：我方 MRT 地形 pass 内必须换**多附件变体** ——
        // 原版 DrawSeparate#render 只调 layer.pipeline(false)、不透传 pass 引用，
        // 所以「当前在哪个 pass」只能由我方置位标记告知（见 MrtTerrainPass.active）。
        // ⚠️ 标记漏清 ⇒ 原版单附件 pass 拿到多附件管线 ⇒ 立刻 validation error
        //（所以置位/清位都放在 finally 里）。
        if (MrtTerrainPass.active()) {
            // 🔴 h20 缺陷①守卫：只有当**当前 pass 的 color attachment 数**确实是 8，
            //   才允许把 MRT 变体交出去。
            //   🔖 理由：MrtTerrainPass.active() 只是进程级静态布尔，它为 true 并**不能证明**
            //   「此刻取管线的原版调用点正处于那个 8 附件 pass 里」。
            //   前提一旦不成立，原版单附件 pass 就会拿到 8 附件管线 ⇒ Vulkan 未定义 ⇒ 画面时好时坏。
            if (MrtTerrainPass.hasExpectedAttachmentCount()) {
            RenderPipeline mrt = DERIVED_MRT.get(key(layer, multiDraw));
            if (mrt != null) {
                WIRED.put(key(layer, multiDraw), Boolean.TRUE);
                if (Boolean.FALSE.equals(WIRED_MRT.put(key(layer, multiDraw), Boolean.TRUE))) {
                    VkDisp.LOGGER.info("vkdisp: [GAP-003/A] wired (mrt variant): layer={} multiDraw={} -> {}",
                            layer, multiDraw, mrt.getLocation());
                }
                return mrt;
            }
            } else {
                // 🔶 守卫命中：**不给**原版 MRT 管线。
                if (MRT_GUARD_FALLBACK.add(layer)) {
                    VkDisp.LOGGER.warn(
                            "vkdisp: [GAP-011] active() 为 true 但当前 pass 的 color attachment 数不是 {}"
                                    + "⇒ **回退原版管线**（绝不把 8 附件管线交给原版单附件 pass）。layer={} multiDraw={}",
                            MrtTerrainPass.expectedAttachmentCount(), layer, multiDraw);
                }
            }
        }
        if (!wireTerrainEnabled()) {
            if (WIRE_OFF_LOGGED.compareAndSet(false, true)) {
                VkDisp.LOGGER.info("vkdisp: [M-01] disabled by config (mixin.wireTerrain=false)"
                        + " -> vanilla terrain pipeline in use (derived still registered={} and compiled)",
                        DERIVED.size());
            }
            return null;
        }
        RenderPipeline pipeline = DERIVED.get(key(layer, multiDraw));
        if (pipeline == null) {
            if (WARNED_UNKNOWN_LAYERS.add(layer)) {
                VkDisp.LOGGER.warn(
                        "vkdisp: [M-01] unknown terrain layer '{}' multiDraw={} -> fall back to vanilla pipeline"
                                + " (registered keys={})",
                        layer, multiDraw, DERIVED.keySet());
            }
            return null;
        }
        WIRE_OFF_LOGGED.set(false);
        // 🔖 通道真的通了的**直接**证据：打的是我方 location（不是「我方对象非空」）。
        // 原版随后走 RenderSystem.getCompiledPipeline(返回值) ⇒ 这条 location 就是地形 draw 实际用的管线。
        if (Boolean.FALSE.equals(WIRED.put(key(layer, multiDraw), Boolean.TRUE))) {
            VkDisp.LOGGER.info("vkdisp: [M-01] wired: layer={} multiDraw={} -> {}",
                    layer, multiDraw, pipeline.getLocation());
        }
        return pipeline;
    }

    /** 🔴 h20 守卫去重集合：同一 layer 只告警一次（热路径上不能刷日志）。 */
    private static final java.util.Set<String> MRT_GUARD_FALLBACK =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 派生管线是否已被原版取用（= 通道真的通了，而不是只注册成功）。 */
    public static int wiredDerivedPipelineCount() {
        int count = 0;
        for (Boolean wired : WIRED.values()) {
            if (Boolean.TRUE.equals(wired)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 🔖 <b>节流间隔为什么是 25 万次而不是 600（首版踩过的坑，勿回调）</b>：
     * {@code pipeline(boolean)} 在**建网格**时也被调用（{@code SectionRenderDispatcher} 第 76 行、
     * {@code LevelRenderer} 第 796 行取顶点格式），实测进世界首段约 <b>1300 次/秒</b>。
     * 按 600 节流 = 每 0.45 秒往渲染线程写一行日志 ⇒ <b>日志 I/O 本身成了热路径开销</b>（支柱③ B1 ≤ +2%）。
     * 改成「首次 + 每 25 万次」后，首跑日志里 M-01 埋点行从 <b>221 行</b>降到 1 行，
     * 而「是否还活着」的信息一点没丢。
     */
    private static final long HIT_LOG_EVERY = 250_000L;

    /** 「M-01 已因配置关闭」的一次性日志哨兵。 */
    private static final java.util.concurrent.atomic.AtomicBoolean WIRE_OFF_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** M-01 注入方法体首行（埋点）：命中计数 + 节流日志。 */
    public static void onWireTerrainHit() {
        long hits = WIRE_HITS.incrementAndGet();
        if (hits == 1L) {
            VkDisp.LOGGER.info(
                    "vkdisp: [M-01] hit (ChunkSectionLayer#pipeline) — derived terrain pipeline wiring active"
                            + " (enabled={}, derivedRegistered={})", wireTerrainEnabled(), DERIVED.size());
        } else if (hits % HIT_LOG_EVERY == 0L) {
            VkDisp.LOGGER.info("vkdisp: [M-01] hit x{} (wired={}/{})",
                    hits, wiredDerivedPipelineCount(), DERIVED.size());
        }
    }

    /**
     * M-01b 注入方法体首行（埋点）：命中计数 + 节流日志。
     *
     * <p>与 {@link #bindTerrainParams} 分成两步，是为了让注入方法体**第一行**就是埋点
     * （M1 编码约束 ③），而「要不要绑 / 绑什么」的策略留在 bridge。
     */
    public static void onBindTerrainParamsEntry() {
        long hits = BIND_HITS.incrementAndGet();
        if (hits == 1L) {
            VkDisp.LOGGER.info(
                    "vkdisp: [M-01b] hit (ChunkSectionsToRender#renderLayers) — custom uniform block entry reached"
                            + " (enabled={}, block={}, bytes={})",
                    bindTerrainParamsEnabled(), TERRAIN_PARAMS_UNIFORM, TerrainDerivedPlan.PARAMS_BYTES);
        } else if (hits % HIT_LOG_EVERY == 0L) {
            VkDisp.LOGGER.info("vkdisp: [M-01b] hit x{}", hits);
        }
    }

    /**
     * M-01b：把自定义 uniform 块绑到当前 RenderPass（GAP-004）。
     *
     * <p>调用方（注入点）已保证「两个 override 形参都为 null」，即原版此刻用的是
     * {@code layer.pipeline(...)} —— 正是 M-01 可能替换成派生管线的那条分支。
     */
    public static void bindTerrainParams(RenderPass pass) {
        pass.setUniform(TERRAIN_PARAMS_UNIFORM, terrainParamsRing().currentBuffer());
    }

    /** 取原版层枚举的名字（mixin 不写原版类型 ⇒ 由 bridge 承担 {@code Enum} 语义）。 */
    public static String layerNameOf(Object layer) {
        return layer instanceof Enum<?> enumValue ? enumValue.name() : String.valueOf(layer);
    }

    /** 自定义 uniform 环（M-01b / GAP-004）。
     *
     * <p><b>为什么只写一次、不每帧写</b>：本轮没有任何着色器读这个块（片元仍是原版
     * {@code core/terrain}），per-frame {@code map+close} 纯属白付热路径代价。
     * 等 GAP-003 那轮换上自研片元、块真的有消费者时，再改成每帧上传 ——
     * 代价必须挂在消费者身上，不能挂在「也许以后会用」上（支柱③ B1 ≤ +2%）。
     */
    private static MappableRingBuffer terrainParamsRing() {
        MappableRingBuffer ring = paramsRing;
        if (ring == null) {
            ring = new MappableRingBuffer(
                    () -> "vkdisp terrain params",
                    GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM,
                    TerrainDerivedPlan.PARAMS_BYTES);
            // vec4 A = (秒数, 绑定计数 mod 65536, 0, 0)；vec4 B 预留（后续 OF 内建矩阵/向量）。
            try (com.mojang.renderpearl.api.buffers.GpuBufferSlice.MappedView view =
                    ring.currentBuffer().map(false, true)) {
                Std140Builder.intoBuffer(view.data())
                        .putVec4(0.0F, 0.0F, 0.0F, 0.0F)
                        .putVec4(0.0F, 0.0F, 0.0F, 0.0F);
            }
            paramsRing = ring;
            VkDisp.LOGGER.info("vkdisp: [M-01b] terrain params ring created: bytes={} (written once; no consumer yet)",
                    TerrainDerivedPlan.PARAMS_BYTES);
        }
        return ring;
    }

    private static String key(String layer, boolean multiDraw) {
        return layer + "|" + multiDraw;
    }
    /**
     * GAP-003：每帧把**眼空间太阳方向**写进 VkDispTerrainParams（顶点适配层要它算 sunVec）。
     *
     * <p><b>为什么从「只写一次」改成每帧写</b>：该块此前没有任何消费者，写一次是诚实的
     * 「零值基线」；顶点适配层出现后它有了真消费者，而太阳方向逐帧变化 ⇒ 必须每帧更新。
     *
     * <p><b>必须在 render pass 打开之前调用</b>：MappableRingBuffer 的 map/close 会把本帧数据
     * 落进环，而 pass 打开期间动 encoder 是已实测到的错误用法（FrameApi 同款纪律）。
     */
    public static void updateTerrainParams() {
        try {
            java.util.Map<String, Object> values = dev.vkdisp.render.OfUniformManager.gather(
                    net.minecraft.client.Minecraft.getInstance(),
                    mainTargetWidth(), mainTargetHeight(), blockAtlasSizeOrEmpty(), java.util.List.of());
            Object sun = values.get("sunPosition");
            float sx = 0.0F;
            float sy = 1.0F;
            float sz = 0.0F;
            if (sun instanceof org.joml.Vector3f vector) {
                sx = vector.x();
                sy = vector.y();
                sz = vector.z();
            }
            try (com.mojang.renderpearl.api.buffers.GpuBufferSlice.MappedView view =
                    terrainParamsRing().currentBuffer().map(false, true)) {
                Std140Builder.intoBuffer(view.data()).putVec4(sx, sy, sz, 0.0F);
            }
        } catch (Throwable t) {
            if (!PARAMS_WRITE_FAILED_LOGGED.getAndSet(true)) {
                VkDisp.LOGGER.error("vkdisp: [GAP-003] terrain params upload FAILED (原文如下)"
                        + " -> sunVec 将按零向量处理", t);
            }
        }
    }

    /** 一次性错误哨兵（上传失败只打一次，避免每帧刷屏）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean PARAMS_WRITE_FAILED_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** 一次性错误哨兵（块上传失败只打一次）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean BUILTINS_WRITE_FAILED_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** 一次性埋点哨兵（绑定摘要只打一次）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean PACK_TERRAIN_BIND_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * 「某个 sampler 没有类型匹配的视图」这条 ERROR 的节流哨兵（h33 加）。
     *
     * <p>🔖 <b>为什么必须节流</b>：本方法每帧调、每个 sampler 各有一条分支。
     * 不节流时一次三分钟的取证就是数千行完全相同的 ERROR，
     * 既淹没真正的首行根因，又把热路径变成 I/O 瓶颈（M-01 埋点过密的同一课）。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean PACK_TERRAIN_NULL_VIEW_WARNED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** 节流周期（帧）：首条之后每这么多帧再报一次，可见性不丢。 */
    private static final long NULL_VIEW_LOG_EVERY = 600L;

    /** 帧计数（配合 {@link #NULL_VIEW_LOG_EVERY} 节流）。 */
    private static long nullViewFrames;

    /**
     * 「取方块图集尺寸失败」只报一次（h35 / QD-05）。
     *
     * <p>🔖 <b>为什么必须一次性</b>：{@code blockAtlasSizeOrEmpty()} <b>每帧</b>被调两次
     * （{@code updateTerrainBuiltins} 的两个调用点）。不节流就是每帧两条 ERROR ——
     * 与 `h34` 刚修掉的 499 行刷屏是同一类错误，不能重犯。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean ATLAS_SIZE_FAILURE_NOTED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** GAP-003：每帧把 OF 内建值写进地形片元的 VkDispBuiltins 环（pass 打开前调用）。 */
    public static void updateTerrainBuiltins() {
        dev.vkdisp.glsl.translate.BuiltinsBlockLayout layout =
                dev.vkdisp.VkDispVirtualPack.terrainBuiltinsLayout();
        if (layout == null || layout.isEmpty()) {
            return;
        }
        try {
            java.util.Map<String, Object> values = dev.vkdisp.render.OfUniformManager.gather(
                    net.minecraft.client.Minecraft.getInstance(),
                    mainTargetWidth(), mainTargetHeight(), blockAtlasSizeOrEmpty(), java.util.List.of());
            MappableRingBuffer ring = terrainBuiltinsRing(
                    Math.max(TERRAIN_BUILTINS_MIN_BYTES, layout.byteSize()));
            try (com.mojang.renderpearl.api.buffers.GpuBufferSlice.MappedView view =
                    ring.currentBuffer().map(false, true)) {
                dev.vkdisp.render.OfUniformManager.logUploadOnce("terrain", layout,
                        dev.vkdisp.render.OfUniformManager.write(layout, values, view.data()), values);
            }
        } catch (Throwable t) {
            if (!BUILTINS_WRITE_FAILED_LOGGED.getAndSet(true)) {
                VkDisp.LOGGER.error("vkdisp: [GAP-003] terrain builtins upload FAILED (原文如下)"
                        + " -> 该块将保持零填充", t);
            }
        }
    }

    private static MappableRingBuffer terrainBuiltinsRing(int bytes) {
        MappableRingBuffer ring = terrainBuiltinsRing;
        if (ring == null) {
            ring = new MappableRingBuffer(() -> "vkdisp terrain builtins",
                    GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM, bytes);
            terrainBuiltinsRing = ring;
            VkDisp.LOGGER.info("vkdisp: [GAP-003] terrain builtins ring created: bytes={}", bytes);
        }
        return ring;
    }

    /**
     * GAP-003：把包地形片元要绑的 uniform 逐条绑到当前 render pass。
     *
     * <p><b>为什么必须逐条绑</b>：STRICT_VALIDATION 下 validateDraw 按**布局**校验，
     * 每个条目都要先 setUniform，缺一条即抛 Missing uniform 名（实测原文）。
     *
     * <p><b>视图怎么选</b>（占位一律显式，不用「猜一个像的」）：
     * texture_0 = 方块图集（真值）；noisetex = 方块图集（占位）；
     * shadowtex0/1 = **专用 1×1 D32 桩**（类型匹配 sampler2DShadow）；
     * shadowcolor0 = **专用 1×1 RGBA8 桩**。
     *
     * <p>🔴🔴 <b>为什么不能绑「本 pass 的深度 / colortex 0」</b>（原实现那样绑过，本轮修正）：
     * 那两张图**同时是本 pass 的读写 render pass 附件**
     * （深度附件清屏 0.0 且地形写深度；colortex0 被清屏并写入）。
     * 在 Vulkan 里把同一张 image **既作为读写附件、又作为采样器**属于
     * <b>未定义行为</b> —— 驱动可以丢弃 draw、可以给出垃圾、也可以什么都不做，
     * <b>而且不会报 validation error</b>（本机无 validation layer，§9.4.15）。
     * 🔶 本项目实测到的症状正是「整帧地形间歇性消失」（`evidence/h25`/`h26` 把触发条件收敛到
     * **只有包片元**；而原版 {@code core/terrain} 不声明这些 sampler ⇒ 不触发，与观测一致）。
     * ⇒ 桩纹理**永远不被当附件**，从根上消除别名（aliasing）。
     *
     * <p>🔖 <b>代价要说清</b>：桩纹理里没有真阴影贴图 ⇒ 阴影项仍**不承诺**
     * （与原实现的语义承诺一致：原注释也写「阴影结果不承诺」）。桩值选成
     * 「深度 = 0.0 = 本引擎的<b>远平面</b>」⇒ 阴影项取「无遮挡」，
     * 是<b>可解释的缺省</b>，比喂一张含本 pass 自身深度的图更接近正确。
     */
    public static void bindPackTerrainUniforms(RenderPass pass,
            com.mojang.renderpearl.api.textures.GpuSampler sampler,
            com.mojang.renderpearl.api.textures.GpuTextureView atlas,
            com.mojang.renderpearl.api.textures.GpuTextureView depthView,
            com.mojang.renderpearl.api.textures.GpuTextureView colorView) {
        PackTerrainProgram program = dev.vkdisp.VkDispVirtualPack.terrainProgram();
        if (program == null) {
            return;
        }
        dev.vkdisp.glsl.translate.BuiltinsBlockLayout layout =
                dev.vkdisp.VkDispVirtualPack.terrainBuiltinsLayout();
        if (layout != null && !layout.isEmpty()) {
            MappableRingBuffer ring = terrainBuiltinsRing;
            if (ring == null) {
                VkDisp.LOGGER.error("vkdisp: [GAP-003] pack terrain builtins ring is null"
                        + " -> 不绑定 VkDispBuiltins（draw 将因 Missing uniform 抛）");
            } else {
                pass.setUniform(PackTerrainProgram.BUILTINS_BLOCK, ring.currentBuffer());
            }
        }
        // 🔴🔴 维度决策（本轮修复的核心）：**视图类别由片元声明的 sampler 类型决定**，
        //   不是「一律喂方块图集」。原实现对未识别的名字一律 `default -> atlas`，
        //   而 BSL 声明了 4 个 `sampler3D`（lighttex / lighttex0 / lighttex1 / voxeltex）
        //   ⇒ 它们拿到了 2D 图集视图 = **描述符类型不匹配 = Vulkan 未定义行为**，
        //   且本机没有 validation layer ⇒ 不报任何错（与 h27 的别名 UB 同一类：
        //   静默、无告警、只能靠推理发现）。实测依据 = BSL v10.1.8 全包 sampler 声明统计。
        dev.vkdisp.pipeline.model.SamplerDimensionPlan.Plan dimensionPlan =
                dev.vkdisp.pipeline.model.SamplerDimensionPlan.fromFragmentSource(
                        program.fragmentSource());
        boolean useStubs = dev.vkdisp.VkDispConfig.MRT_SHADOW_STUBS.get();
        for (dev.vkdisp.pipeline.model.SamplerDimensionPlan.Binding binding : dimensionPlan.bindings()) {
            String name = binding.name();
            if (!binding.bindable()) {
                // 🔶 不可绑 ⇒ **不绑**。宁可让 draw 抛 Missing uniform（响亮失败、可定位），
                //   也不拿 2D 视图冒充 3D/cube（静默 UB）。这是与旧 `default -> atlas` 的
                //   根本区别：旧实现在这里总能绑出一个「看起来能用」的视图。
                VkDisp.LOGGER.error("vkdisp: [GAP-003] sampler '{}' 类型 '{}' 无类型匹配的视图（{}）"
                        + " -> **不绑定**（宁可 Missing uniform 抛，也不喂错维度造成静默 UB）",
                        name, binding.declaredType(), binding.reason());
                continue;
            }
            com.mojang.renderpearl.api.textures.GpuTextureView view =
                    switch (binding.kind()) {
                        case ATLAS_2D -> atlas;
                        // 🔴 h26 修正：**不得**绑本 pass 的深度/颜色附件（读写附件 + 采样器 = Vulkan UB）。
                        //   默认改绑专用桩纹理（永不作附件），彻底消除别名。
                        // 🔬 mrt.shadowStubs=false 时**故意**恢复旧绑定，仅供同二进制单变量对照取证。
                        case SHADOW_DEPTH_2D -> useStubs
                                ? ShadowStubs.depthView()
                                : ShadowStubs.ownDepthAttachment(depthView);
                        case SHADOW_COLOR_2D -> useStubs
                                ? ShadowStubs.colorView()
                                : ShadowStubs.ownColorAttachment(colorView);
                        // 🔴 h10 实测修正：高级材质路径的全黑画面来自这里 ——
                        //   把 specular/normals 绑成方块图集，而它们是逐方块**材质贴图集**，
                        //   图集的 .z（ao）与 .r/.g（smoothness/f0）不是材质语义 ⇒ albedo 被乘得全零。
                        //   ⇒ 绑**乘法单位元**（NeutralMaterialMaps）：
                        //      specular=(0,0,0,1) ⇒ metalness=0, smoothness=0 ⇒ *1
                        //      normals =(128,128,255,255) ⇒ ao=1.0 ⇒ *1
                        //   这是可解释的缺省（"没有材质覆盖、没有 AO、法线朝上"），不是编一个假输入。
                        case NEUTRAL_MATERIAL_2D -> name.equals("specular")
                                ? NeutralMaterialMaps.specularView()
                                : NeutralMaterialMaps.normalsView();
                        // 🔴 本轮新增：sampler3D ⇒ 类型匹配的 3D 桩（全 0 = 无体积光照/体素数据）。
                        case VOLUME_3D -> VolumeStubs.view();
                        case PLACEHOLDER_2D -> atlas;
                        // decide() 已把 UNSUPPORTED 过滤掉；这里只是让编译器知道穷尽了。
                        case UNSUPPORTED -> null;
                    };
            if (view == null) {
                // 🔴 h33：**每帧**一条 ERROR 会把日志冲垮（本轮实测同类问题一次运行 2702 行）。
                //   节流成「首次 + 每 600 帧」—— 可见性不丢，I/O 压力可忽略。
                if (!PACK_TERRAIN_NULL_VIEW_WARNED.getAndSet(true)
                        || (nullViewFrames++ % NULL_VIEW_LOG_EVERY) == 0L) {
                    VkDisp.LOGGER.error("vkdisp: [GAP-003] pack terrain sampler view is null: {}"
                            + " -> 跳过该条绑定（draw 将因 Missing uniform 抛）。"
                            + "常见原因见上方最近一条 ERROR：原版 26.3 不支持 3D/数组纹理"
                            + "（GAP-014）⇒ sampler3D 无法绑定类型匹配的视图",
                            name);
                }
                continue;
            }
            pass.setUniform(name, view, sampler);
        }
        if (!PACK_TERRAIN_BIND_LOGGED.getAndSet(true)) {
            VkDisp.LOGGER.info("vkdisp: [GAP-003] pack terrain uniforms bound: blockMembers={} samplers={}"
                            + " (by dimension: {}; texture_0=图集真值; specular/normals=中性单位元;"
                                    + " shadowtex*=专用桩; sampler3D*=3D 桩; 其余=图集占位)",
                    layout == null ? 0 : layout.members().size(),
                    program.fragmentSamplers().size(),
                    dimensionPlan.summary());
            for (String warning : dimensionPlan.warnings()) {
                VkDisp.LOGGER.warn("vkdisp: [GAP-003] sampler plan: {}", warning);
            }
        }
    }

    private static int mainTargetWidth() {
        com.mojang.blaze3d.pipeline.RenderTarget target =
                net.minecraft.client.Minecraft.getInstance().gameRenderer.mainRenderTarget();
        return target == null ? 0 : target.width;
    }

    private static int mainTargetHeight() {
        com.mojang.blaze3d.pipeline.RenderTarget target =
                net.minecraft.client.Minecraft.getInstance().gameRenderer.mainRenderTarget();
        return target == null ? 0 : target.height;
    }

    /**
     * 方块图集尺寸（{@code OfUniformManager.gather} 需要）；取不到时给 {@code {0,0}}。
     *
     * <p>🔖 <b>零值语义由 gather 侧承担，但「为什么取不到」不能悄悄消失</b>（h35 / QD-05）。
     * 旧实现是 {@code catch (Throwable t) { return new int[]{0,0}; }} —— 异常对象整个被丢掉，
     * 于是「纹理真的没加载好」与「这里就是没有图集」<b>在日志里完全一样</b>。
     * 而这个值<b>每帧</b>喂进 uniform（{@code updateTerrainBuiltins} 的两个调用点），
     * 尺寸错了会让整条 OF uniform 静默走偏。
     *
     * <p>🔖 这正是 {@code h33}「能力门控死开关」那一族：<b>真错误伪装成默认值</b>。
     * 本处按 `T11`（降级必须可见）改为**一次性**报错并带上异常原文（`X9` 不猜）。
     */
    private static int[] blockAtlasSizeOrEmpty() {
        try {
            net.minecraft.client.renderer.texture.AbstractTexture texture =
                    net.minecraft.client.Minecraft.getInstance().getTextureManager().getTexture(
                            net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
            if (texture == null) {
                return new int[] {0, 0};
            }
            com.mojang.renderpearl.api.textures.GpuTexture gpu = texture.getTexture();
            if (gpu == null) {
                return new int[] {0, 0};
            }
            return new int[] {gpu.getWidth(0), gpu.getHeight(0)};
        } catch (Throwable t) {
            if (ATLAS_SIZE_FAILURE_NOTED.compareAndSet(false, true)) {
                VkDisp.LOGGER.error(
                        "vkdisp: 取方块图集尺寸失败 —— 本次按 {0,0} 继续，"
                                + "这会让本帧的 OF uniform 全部走偏（尺寸错了不会报错，只会画面不对）。"
                                + "原文：{}",
                        t.toString());
            }
            return new int[] {0, 0};
        }
    }
}
