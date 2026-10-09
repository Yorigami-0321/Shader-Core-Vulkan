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
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import dev.vkdisp.VkDisp;
import dev.vkdisp.VkDispConfig;
import dev.vkdisp.pipeline.model.GbufferProgramPlan;
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
     * GAP-003 / GAP-027：<b>逐条</b>包 gbuffer 程序各自的 {@code VkDispBuiltins} 块环
     * （懒建；每帧写 —— 块终于有真消费者了）。
     *
     * <p>🔖 与 {@link #paramsRing} 分开的原因：块布局不同（地形片的收编集与 composite 各不相同）
     * ⇒ 字节数不同、成员不同；共用一个环要么装不下，要么写错成员。
     * 🔴 程序<b>之间</b>同理：水的收编集与地形不是一套（X39），所以键 = 程序名，
     * 每条各一条环。用 {@code ConcurrentHashMap} 是因为建环可能发生在资源线程之外的
     * 渲染线程，而注册侧读它 —— 但真正的纪律是「只在渲染线程写内容」（{@code map/close}）。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, MappableRingBuffer>
            BUILTINS_RINGS = new java.util.concurrent.ConcurrentHashMap<>();

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
        // 🔖 GAP-003：本次是否换包地形片元 + **冻结包片元契约**（附件数 + 声明写的槽）。
        //   冻结发生在**注册这一刻**（而不是每帧现算），因为管线颜色目标数与 render pass
        //   附件数必须恒等；两者若各自现算，就会出现「注册读 8、绘制读 1」⇒ setPipeline 抛
        //   IllegalStateException **崩客户端**（X42）。注册后两侧读同一个冻结值。
        // 🔖🔖 两个数**必须同一次调用一起冻结**（MrtPlan.FrozenPackContract）：
        //   附件数与「哪些槽被写」若走两条独立通道，就可能出现「附件按新契约、被写的槽按旧契约」，
        //   两者互相矛盾而日志完全正常。
        // 🔴 GAP-027：现在<b>两条</b>（以后可能更多）程序一起冻进<b>同一次</b> freezePackPrograms 调用，
        //   附件数 = 各条的 max、被写的槽 = 各条的并集 ⇒ 一个 pass 只有一套附件数，
        //   必须容纳写得最多的那一条（BSL 默认档：地形 1 条、水无条件 2 条 ⇒ 附件数 2）。
        GbufferProgramPlan.Entry terrain = new GbufferProgramPlan.Entry(
                dev.vkdisp.pack.PackTerrainSource.TERRAIN_PROGRAM, packTerrainForMrt());
        GbufferProgramPlan.Entry water = new GbufferProgramPlan.Entry(
                dev.vkdisp.pack.PackTerrainSource.WATER_PROGRAM, packWaterForMrt(terrain));
        java.util.List<GbufferProgramPlan.Entry> wiring =
                GbufferProgramPlan.entriesOf(terrain, water);
        MrtPlan.freezePackPrograms(GbufferProgramPlan.packPrograms(wiring));
        // 🔴 GAP-027 强制自报（一行说清「接了哪几条、各几个输出、附件数取到几」）。
        VkDisp.LOGGER.info("vkdisp: [GAP-027] {}",
                GbufferProgramPlan.wiredReport(wiring, MrtPlan.slotCount()));
        reportPerProgram(terrain, water);
        for (TerrainDerivedPlan.Spec spec : TerrainDerivedPlan.all()) {
            String mrtKey = key(spec.layer(), spec.multiDraw());
            try {
                RenderPipeline pipeline = buildMrtPipeline(spec, terrain, water);
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
     * 一条 MRT 派生管线（GAP-003 的地形形状 + GAP-027 的「本层挂哪条程序」）。
     *
     * <p>🔖 从注册循环里拆出来只为守住 QD-04 那条棘轮的口径：编排方法要长就拆方法，
     * <b>不抬基线</b>。
     */
    private static RenderPipeline buildMrtPipeline(TerrainDerivedPlan.Spec spec,
            GbufferProgramPlan.Entry terrain, GbufferProgramPlan.Entry water) {
        RenderPipeline.Builder builder = spec.multiDraw()
                ? RenderPipeline.builder(RenderPipelines.MULTIDRAW_TERRAIN_SNIPPET)
                : RenderPipeline.builder(RenderPipelines.TERRAIN_SNIPPET);
        // 🔴 本层实际用哪条程序（null = 沿用原版 core/terrain）。
        GbufferProgramPlan.Entry layer = GbufferProgramPlan.entryForLayer(spec.layer(), terrain, water);
        String program = layer == null ? null : layer.program();
        builder.withLocation(Identifier.fromNamespaceAndPath(TerrainDerivedPlan.NAMESPACE,
                        spec.location().substring((TerrainDerivedPlan.NAMESPACE + ":").length())
                                + GbufferProgramPlan.mrtSuffix(program, terrain.program())))
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withUniform(TERRAIN_PARAMS_UNIFORM, UniformType.UNIFORM_BUFFER)
                        .build())
                // 🔖 与 MrtTerrainPass 建的多附件 pass 附件数**必须相等**（Vulkan 要求）。
                // 两侧都取 MrtPlan.slotCount() —— 单点真源，避免「一处改了一处没改」。
                .withColorTargetStates(0, MrtPlan.slotCount() - 1, () -> ColorTargetState.DEFAULT);
        if (layer != null) {
            // 🔖 GAP-003：换成「顶点适配层 + 包自己的片元」。
            //   顶点侧不能直接用包的 vsh —— 它要 7 个顶点属性（含 Normal / mc_Entity /
            //   mc_midTexCoord），而原版地形顶点缓冲 DefaultVertexFormat.BLOCK 只有 4 个；
            //   改网格化属另一层工程。适配层按原版格式取数、逐位置对齐产出<b>该条程序</b>的 varying。
            builder.withVertexShader(dev.vkdisp.VkDispVirtualPack.packAdapterShaderId(program))
                    .withFragmentShader(dev.vkdisp.VkDispVirtualPack.packFragmentShaderId(program))
                    // 布局必须**逐条**登记包片元自由声明的 sampler：STRICT_VALIDATION 下
                    // draw() 按布局校验，SPIR-V 反射出的每个名字查不到即抛（实测原文：
                    // Unable to find shader defined uniform）。反向（布局多于 SPIR-V）无害。
                    // 🔴 X39：sampler 清单**逐条程序各一份** —— 水要 8 个（含 gaux1/gaux2/depthtex1），
                    //   照抄地形的 5 个就是「少供」⇒ 每个水的 draw 都抛 Missing uniform。
                    .withBindGroupLayout(packBindGroupLayout(layer.contract()));
        }
        if (spec.hasAlphaCutout()) {
            builder.withShaderDefine("ALPHA_CUTOUT", spec.alphaCutout());
        }
        builder.withColorTargetState(spec.translucentBlend()
                ? new ColorTargetState(BlendFunction.TRANSLUCENT)
                : ColorTargetState.DEFAULT);
        // 🔴 GAP-027：半透明那一层的管线<b>深度测试开、写深度关</b>。
        //   能做到「不动固体/cutout」是因为本前端的深度写入状态挂在**管线**上
        //   （RenderPipeline$Builder#withDepthStencilState），不在 render pass 上 ⇒
        //   只有返回 false 的那一条（TRANSLUCENT 且真挂了包水程序）被改，其余五条逐字不变。
        //   CompareOp 沿用 snippet 的 GREATER_THAN_OR_EQUAL（本引擎反向 Z），只翻 writeDepth。
        if (!GbufferProgramPlan.writesDepth(spec.layer(), terrain, water)) {
            builder.withDepthStencilState(
                    new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false));
            if (DEPTH_WRITE_OFF_LOGGED.compareAndSet(false, true)) {
                VkDisp.LOGGER.info("vkdisp: [GAP-027] {} MRT pipeline depth = test ON / write OFF"
                        + " ({} fragment; SOLID/CUTOUT keep DepthStencilState.DEFAULT = write ON)",
                        spec.layer(), program);
            }
        }
        return builder.build();
    }

    /** 「半透明关写深度」这条自报只打一次（注册期本来只跑一次，哨兵是防别处复用同一判据）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean DEPTH_WRITE_OFF_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * 逐条程序各打一行「用了什么 / 为什么没用」。
     *
     * <p>🔴 GAP-027 的强制项：<b>包里没有水</b>必须说出来（{@code not wired because absent}），
     * 绝不允许「开关开着而什么都没发生」这种静默（X9 / X11 / T11）。
     */
    private static void reportPerProgram(GbufferProgramPlan.Entry terrain,
            GbufferProgramPlan.Entry water) {
        if (terrain.wired()) {
            PackTerrainProgram packTerrain = terrain.contract();
            // 🔖 证据行必须**同时**打出「附件数」与「哪些槽被写」——
            //   只打 colorTargets=8 会让人以为 8 个附件都被写了（实测<b>残留档</b>是 [0,3,6,7]，
            //   附件 1/2/4/5 存在但无片元输出；🔖 真默认档只写槽 0 —— 那 8 来自 store 里
            //   残留的 ADVANCED_MATERIALS=true，见 MrtPlan 的 h45 更正与 evidence/h48 §二十二）。
            VkDisp.LOGGER.info(
                    "vkdisp: [GAP-003] MRT terrain pipelines will use pack fragment: program={}"
                            + " colorTargets={} declaredOutputSlots={} samplers={} varyings={}"
                            + " unwrittenAttachments={}",
                    packTerrain.qualifiedName(), MrtPlan.slotCount(),
                    packTerrain.declaredOutputSlots(),
                    packTerrain.fragmentSamplers().size(), packTerrain.inputs().size(),
                    unwrittenAttachments(packTerrain, MrtPlan.slotCount()));
        }
        if (water.wired()) {
            VkDisp.LOGGER.info("vkdisp: [GAP-027] MRT {} pipelines will use pack fragment: program={}"
                            + " declaredOutputSlots={} samplers={} varyings={}",
                    GbufferProgramPlan.TRANSLUCENT_LAYER, water.contract().qualifiedName(),
                    water.contract().declaredOutputSlots(),
                    water.contract().fragmentSamplers().size(), water.contract().inputs().size());
            return;
        }
        // 没接 ⇒ 说清**为什么**没接（两种原因要分开说，见 packWaterForMrt 的返回口径）。
        VkDisp.LOGGER.warn("vkdisp: [GAP-027] {}", GbufferProgramPlan.notWiredReport(
                water.program(), waterSkip(), terrain.wired() ? terrain.program() : null));
    }

    /** 水没接上的原因：开关关着 vs 包里没有 —— 两者的处置与后果不同，必须分开报。 */
    private static GbufferProgramPlan.Skip waterSkip() {
        return VkDispConfig.MRT_PACK_WATER_SHADER.get()
                ? GbufferProgramPlan.Skip.ABSENT
                : GbufferProgramPlan.Skip.DISABLED;
    }

    /**
     * 存在但<b>没有</b>包片元输出的附件下标（升序）。
     *
     * <p>🔖🔖 <b>为什么专门把它打出来</b>（2026-10-05 实测）：
     * {@code colorTargets=8} 单独看会让人以为 8 个附件都被包片元写了；
     * 而实测<b>残留档</b>（store 带 {@code ADVANCED_MATERIALS=true}）是 {@code declaredOutputSlots=[0,3,6,7]}
     * ⇒ 附件 <b>1/2/4/5</b>
     * 存在但**没有任何片元输出**，读它们只会得到清屏值。
     * （🔖 这<b>不是</b>包默认档：默认档只写槽 0；见 {@code MrtPlan} 的 h45 更正、{@code evidence/h48} §二十二。）
     * 不自报这四项 ⇒ 诊断一旦读了其中一槽，就会把「清屏值」当成「包输出是黑的」报出去。
     *
     * <p>声明槽位为空（不接包片元 / 契约不可得）时返回 {@code [-]}：
     * 「不知道」与「全部被写」必须能分开（同本项目一贯口径）。
     */
    private static String unwrittenAttachments(PackTerrainProgram program, int attachments) {
        java.util.List<Integer> declared = program.declaredOutputSlots();
        if (declared.isEmpty()) {
            return "[-] (declared slots unknown)";
        }
        java.util.List<Integer> missing = new java.util.ArrayList<>();
        for (int slot = 0; slot < attachments; slot++) {
            if (!program.declaresOutputSlot(slot)) {
                missing.add(slot);
            }
        }
        return missing.toString();
    }

    /**
     * 顶点适配层着色器 id —— 由虚拟包**按该条程序的 varying 契约生成**后提供。
     *
     * <p>🔖 为什么不是本模组自己的静态资产：实测 BSL 默认配置地形要 9 条 varying、水要 <b>14</b> 条、
     * 开 {@code ADVANCED_MATERIALS} 后地形要 <b>15</b> 条；静态适配层对另一个签名就是「少供」⇒
     * 驱动层在资源加载期抛 {@code ShaderCompileException: missing output at location 14}
     * ⇒ <b>客户端起不来</b>（本轮真实踩到）。生成物与片元源同生共死，杜绝半接线。
     * 🔴 GAP-027：id 由<b>程序名</b>派生（{@code GbufferProgramPlan}），
     * 地形与水各有一份，水的管线绝不会拿到地形的适配层（X39）。
     */

    /** 「不接包片元」的一次性告警哨兵（默认关 / 无包地形片元，两种原因要分开说）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean PACK_TERRAIN_OFF_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** 「不接包水片元」的一次性告警哨兵（与地形那个**分开**：两种原因要分开说）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean PACK_WATER_OFF_LOGGED =
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

    /**
     * GAP-027：本次 MRT 半透明层是否改用<b>包自己的</b> {@code gbuffers_water} 片元。
     *
     * <p>🔴 {@code null} = 不接线，且<b>两种原因各打一条</b>（开关关着 / 包里没有），
     * 绝不静默（X9 / X11）。地形那条已接与否不影响本判断 —— 两条程序各自独立成立，
     * 这也是 {@code GbufferProgramPlan.programForLayer} 唯一的分支来源。
     *
     * @param terrain 地形那条的声明（只用于「没接上时回落到谁」的自报文案）
     */
    @Nullable
    static PackTerrainProgram packWaterForMrt(GbufferProgramPlan.Entry terrain) {
        if (!VkDispConfig.MRT_PACK_WATER_SHADER.get()) {
            if (PACK_WATER_OFF_LOGGED.compareAndSet(false, true)) {
                VkDisp.LOGGER.info("vkdisp: [GAP-027] {}", GbufferProgramPlan.notWiredReport(
                        dev.vkdisp.pack.PackTerrainSource.WATER_PROGRAM,
                        GbufferProgramPlan.Skip.DISABLED,
                        terrain != null && terrain.wired() ? terrain.program() : null));
            }
            return null;
        }
        PackTerrainProgram program = dev.vkdisp.VkDispVirtualPack.waterProgram();
        if (program == null) {
            // 一次性哨兵在这里**不**置位：本方法每轮注册只调一次，而 reportPerProgram 会打
            // 「not wired because absent」那一行 —— 两处都打会变成同义重复。
            VkDisp.LOGGER.warn("vkdisp: [GAP-027] pack water fragment requested but unavailable"
                    + " (mrt.packWater=true 却没选出 gbuffers_water)");
            return null;
        }
        return program;
    }

    /**
     * 本表冻结后是否真的接上了<b>水</b>（渲染期判据，只读<b>注册期冻结</b>的那一份）。
     *
     * <p>🔖 为什么读 {@link MrtPlan#packProgramNames()} 而不是再读一次配置：
     * 管线是按冻结值注册的，pass 若按<b>当前</b>配置决定画不画半透明，就会出现
     * 「pass 画了半透明、却没有对应的多附件管线」或反之 —— 两侧必须同源（X42）。
     */
    public static boolean waterWiredInFrozenPlan() {
        return MrtPlan.packProgramNames()
                .contains(dev.vkdisp.pack.PackTerrainSource.WATER_PROGRAM);
    }

    /** 某条包片元的绑定组布局：VkDispBuiltins 块 + <b>它自己</b>自由声明的每个 sampler。 */
    private static BindGroupLayout packBindGroupLayout(PackTerrainProgram program) {
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
    /**
     * 🔴 地形 pass **关闭之后**轮换本类的两条环（h48p：GAP-020 排查中掉出的独立缺陷）。
     *
     * <p>机制（不是推测，是与 `FrameApi:979-988` 同一条纪律缺了一半）：
     * `MappableRingBuffer` 是深度 **3** 的环（`MappableRingBuffer.java:14 BUFFER_COUNT=3`），
     * 每帧写 `currentBuffer()` 然后必须 rotate，否则下一帧的 CPU 写入会**覆写 GPU 还在读的槽**
     * —— 原版在 `VulkanCommandEncoder:222-223` 允许 3 个 submit 在飞，所以这个窗口是真实的。
     * 本类此前**从来没有** rotate ⇒ 两条环实际上被钉死在 0 号槽：
     * `paramsRing`（第 489-502 行）与 `terrainBuiltinsRing`（第 596-599 行写、第 665 行绑）。
     * 对照：`FrameApi` 的每条环都在绘制后 rotate（第 980/982/985/988/1227 行）。
     *
     * <p>⚠️ 它与 h48o 的「严格 3 帧周期整帧为空」是否同源**尚未证明**：不 rotate 的环是
     * 周期 1（每帧都覆写同一个槽），本身给不出周期 3；但它是**已成立的未定义行为**，
     * 修掉之后再看那条曲线才有资格说别的东西。
     */
    static void rotateAfterDraw() {
        if (paramsRing != null) {
            paramsRing.rotate();
        }
        // 🔴 GAP-027：轮换**每一条**程序的块环（漏一条 = 那条下一帧的 CPU 写入覆写
        // GPU 还在读的槽 = 同一条已成立的未定义行为，只是换了个消费者）。
        for (MappableRingBuffer ring : BUILTINS_RINGS.values()) {
            ring.rotate();
        }
    }

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
                    mainTargetWidth(), mainTargetHeight(), blockAtlasSizeOrEmpty(), java.util.List.of(),
                    // 🔴 GAP-022：gbuffers_* 这一族<b>永远</b>引擎口径（它的顶点阶段要用这个矩阵写
                    //   gl_Position，喂 D2·P 会让 clip.z 越界）⇒ 传 GBUFFER 让开关对它无效。
                    dev.vkdisp.render.OfUniformManager.Family.GBUFFER);
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
     * GAP-027：<b>逐条程序</b>各打一次绑定摘要的哨兵（{@code {gbuffers_terrain, gbuffers_water}}）。
     *
     * <p>🔖 为什么不能再共用上面那一个布尔：水接上之后若只有地形打过一行，
     * 「水的 8 个 sampler 到底绑没绑」在日志里就是空的 —— 而空与「没接」在观测上同形
     * （本项目最贵的两类混淆之一）。用 Set 而不是新加一个水专用布尔：
     * 第三条程序接进来时不需要再改这里。
     */
    private static final java.util.Set<String> PACK_BIND_LOGGED_PROGRAMS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

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
     * <p>🔖 <b>为什么必须一次性</b>：{@code blockAtlasSizeOrEmpty()} 在<b>每条已接上的</b>
     * gbuffer 程序各写一次块时被调一次（{@link #updateWiredGbufferBuiltins} ⇒ 地形 1 条、
     * 接上水之后 2 条）。不节流就是每帧多条 ERROR ——
     * 与 `h34` 刚修掉的 499 行刷屏是同一类错误，不能重犯。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean ATLAS_SIZE_FAILURE_NOTED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * GAP-003 / GAP-027：每帧把 OF 内建值写进<b>本 pass 这一帧会画的每一条</b>包 gbuffer 程序
     * 自己的 {@code VkDispBuiltins} 环（pass 打开前调用）。
     *
     * <p>🔴🔴 <b>h49 真机抓出来的缺陷（本方法原来只写地形那一条）</b>：水接线那一臂（ON）日志里
     * 每帧一条 {@code pack gbuffer builtins ring is null: program=gbuffers_water}，
     * 一次运行 <b>1032</b> 条 ⇒ 水的块从来没被创建、也从来没被写过。
     * 「环的建与写」都在 {@link #updateGbufferBuiltins} 里，<b>但调用点只有一个程序名</b> ——
     * 与 {@code noisetex}（分类表缺分支）、{@code PackTextures.ensureReady}（准备顺序错了）
     * 同族的<b>第四例「实现了但没接上」</b>。
     * ⇒ 判据同源：这里用 {@link #waterWiredInFrozenPlan()}，与 {@code MrtTerrainPass}
     * 决定「要不要发那次 {@code renderGroup(TRANSLUCENT)}」用的是<b>同一个谓词</b>，
     * 不会出现「画了却没写块」或「写了块却没画」。
     *
     * <p>⚠️ 代价（如实登记）：接上水时每帧多做一次 {@code OfUniformManager.gather}
     * （纯 CPU 取值，不碰 GPU）。水与地形的<b>收编集不同</b>（47 vs 42 个成员）⇒
     * 共用一份字节就是「按地形的成员表写水的块」= 静默喂垃圾，所以必须逐条各写一次。
     */
    public static void updateWiredGbufferBuiltins() {
        updateGbufferBuiltins(dev.vkdisp.pack.PackTerrainSource.TERRAIN_PROGRAM);
        if (waterWiredInFrozenPlan()) {
            updateGbufferBuiltins(dev.vkdisp.pack.PackTerrainSource.WATER_PROGRAM);
        }
    }

    /**
     * GAP-027：每帧把 OF 内建值写进<b>该条程序自己的</b> VkDispBuiltins 环（pass 打开前调用）。
     *
     * <p>🔖 逐条各一次 {@code OfUniformManager.gather}：地形与水<b>收编集不同</b>（块布局不同、
     * 字节数不同），共用一份写出来的字节就是「按地形的成员表写水的块」= 静默喂垃圾。
     * 代价（明写）：每帧多一次 gather（纯 CPU 取值，不碰 GPU），只在真接上水的那一臂发生。
     */
    public static void updateGbufferBuiltins(String program) {
        dev.vkdisp.glsl.translate.BuiltinsBlockLayout layout =
                dev.vkdisp.VkDispVirtualPack.packBuiltinsLayout(program);
        if (layout == null || layout.isEmpty()) {
            return;
        }
        try {
            java.util.Map<String, Object> values = dev.vkdisp.render.OfUniformManager.gather(
                    net.minecraft.client.Minecraft.getInstance(),
                    mainTargetWidth(), mainTargetHeight(), blockAtlasSizeOrEmpty(), java.util.List.of(),
                    // 🔴 GAP-022：gbuffers_* 这一族<b>永远</b>引擎口径（它的顶点阶段要用这个矩阵写
                    //   gl_Position，喂 D2·P 会让 clip.z 越界）⇒ 传 GBUFFER 让开关对它无效。
                    dev.vkdisp.render.OfUniformManager.Family.GBUFFER);
            MappableRingBuffer ring = builtinsRing(program,
                    Math.max(TERRAIN_BUILTINS_MIN_BYTES, layout.byteSize()));
            try (com.mojang.renderpearl.api.buffers.GpuBufferSlice.MappedView view =
                    ring.currentBuffer().map(false, true)) {
                dev.vkdisp.render.OfUniformManager.logUploadOnce(program, layout,
                        dev.vkdisp.render.OfUniformManager.write(layout, values, view.data()), values);
            }
        } catch (Throwable t) {
            if (!BUILTINS_WRITE_FAILED_LOGGED.getAndSet(true)) {
                VkDisp.LOGGER.error("vkdisp: [GAP-003] gbuffer builtins upload FAILED (原文如下)"
                        + " -> 该块将保持零填充: program=" + program, t);
            }
        }
    }

    private static MappableRingBuffer builtinsRing(String program, int bytes) {
        MappableRingBuffer ring = BUILTINS_RINGS.get(program);
        if (ring == null) {
            ring = new MappableRingBuffer(() -> "vkdisp " + program + " builtins",
                    GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM, bytes);
            BUILTINS_RINGS.put(program, ring);
            VkDisp.LOGGER.info("vkdisp: [GAP-027] builtins ring created: program={} bytes={}",
                    program, bytes);
        }
        return ring;
    }

    /** 该条程序的块环（未建返回 {@code null} —— 调用点必须显式报错，不静默跳过）。 */
    private static MappableRingBuffer existingBuiltinsRing(String program) {
        return BUILTINS_RINGS.get(program);
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
        bindPackGbufferUniforms(pass, sampler, atlas, depthView, colorView,
                dev.vkdisp.pack.PackTerrainSource.TERRAIN_PROGRAM);
    }

    /**
     * GAP-027：与 {@link #bindPackTerrainUniforms} 同一条实现，只是<b>按程序名</b>取契约 / 块布局 / 环。
     *
     * <p>🔴 这一参数化不是重构美化：水自己声明 8 个 sampler（地形只有 5 个），
     * 照抄地形的清单 ⇒ 水的每个 draw 都被 {@code validateDraw} 以 Missing uniform 拦下
     * （响亮失败），或反过来把地形的名字绑到水的管线上当垃圾用（静默错）。X39。
     *
     * <p>🔖 调用时机：必须在<b>该层 renderGroup 之前</b>（pass 内改的是当前绑定状态，
     * 后绑覆盖前绑；先 OPAQUE 后 TRANSLUCENT 各绑一次正是这个原因）。
     */
    public static void bindPackGbufferUniforms(RenderPass pass,
            com.mojang.renderpearl.api.textures.GpuSampler sampler,
            com.mojang.renderpearl.api.textures.GpuTextureView atlas,
            com.mojang.renderpearl.api.textures.GpuTextureView depthView,
            com.mojang.renderpearl.api.textures.GpuTextureView colorView,
            String programName) {
        PackTerrainProgram program = dev.vkdisp.VkDispVirtualPack.packContract(programName);
        if (program == null) {
            return;
        }
        dev.vkdisp.glsl.translate.BuiltinsBlockLayout layout =
                dev.vkdisp.VkDispVirtualPack.packBuiltinsLayout(programName);
        if (layout != null && !layout.isEmpty()) {
            MappableRingBuffer ring = existingBuiltinsRing(programName);
            if (ring == null) {
                VkDisp.LOGGER.error("vkdisp: [GAP-003] pack gbuffer builtins ring is null: program={}"
                        + " -> 不绑定 VkDispBuiltins（draw 将因 Missing uniform 抛）", programName);
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
                        // 🔴 GAP-025 接线点：噪声走**选源**（包 texture.noise 优先），不是方块图集。
                        //   此前没有这条分支 ⇒ 地形与水都把图集当噪声读（静默错）。
                        case NOISE_2D -> dev.vkdisp.bridge.PackTextures.view(name);
                        // 🔴 GAP-023 接线点：深度快照**绑桩**，不绑本 pass 的深度附件（h26 的 UB 禁令）。
                        case DEPTH_SNAPSHOT_2D -> gbufferDepthSnapshotStub(name);
                        // 🔴 gauxN = colortex(N+3)，与链侧 FrameApi 同口径。
                        case GAUX_2D -> gauxView(name, atlas);
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
        // 🔴 GAP-027：绑定摘要**逐条程序各打一次** —— 只打一次的话，接了水之后日志里
        //   仍然只有「terrain 绑了 5 个 sampler」那一行，水的 8 个到底是绑了还是没绑，
        //   在观测上分不出来（本项目反复吃过的那种「看起来正常其实没生效」）。
        if (PACK_BIND_LOGGED_PROGRAMS.add(programName)) {
            VkDisp.LOGGER.info("vkdisp: [GAP-027] pack gbuffer uniforms bound: program={}"
                            + " blockMembers={} samplers={}"
                            + " (by dimension: {}; texture_0=图集真值; specular/normals=中性单位元;"
                                    + " shadowtex*=专用桩; depthtex*=1x1 D32@0.0 桩（**不是**本 pass"
                                    + " 附件，快照未实现）; sampler3D*=3D 桩; 其余=图集占位)",
                    programName,
                    layout == null ? 0 : layout.members().size(),
                    program.fragmentSamplers().size(),
                    dimensionPlan.summary());
            for (String warning : dimensionPlan.warnings()) {
                VkDisp.LOGGER.warn("vkdisp: [GAP-003] sampler plan: {}", warning);
            }
        }
        if (!PACK_TERRAIN_BIND_LOGGED.getAndSet(true)) {
            // 🔴🔴 GAP-015（h38 源码级核实）：**本引擎没有「比较采样器」这个能力**。
            //   已从 minecraft-patched-26.3.0.41-beta.jar 逐类核实：
            //   · GpuDevice 只有**一个** createSampler(AddressMode, AddressMode,
            //     FilterMode, FilterMode, int, OptionalDouble) —— **没有 CompareOp 参数**；
            //   · SamplerCache.getClampToEdge(FilterMode, boolean) 那个 boolean
            //     经 LocalVariableTable 核实是 **useMipmaps**，**不是** compare。
            //   ⇒ 拿不到 VkCompareOp 不为 NONE 的采样器。
            //   而包把 shadowtex0/1 声明为 **sampler2DShadow**（要比较采样器）
            //   ⇒ 只能绑**非比较**采样器 ⇒ 描述符类型不匹配 = **Vulkan 未定义行为**。
            //   🔖 为什么**照样绑**、不学 GAP-012/014 那样「不绑 + 报错」：
            //   那两条的对象（sampler3D / cube）在本包的**地形程序里是 0 条**，
            //   不绑不影响渲染；而 shadowtex0/1 **每种配置都在（实测 SHADOW_DEPTH_2D=2）**，
            //   不绑 ⇒ 每个用阴影的包 draw 直接抛 Missing uniform ⇒ 地形整条不渲染。
            //   在支柱①（兼容优先）下「画面里阴影不可信」优于「地形完全不画」。
            //   ⇒ 但**绝不沉默**：这条一次性说明让取证者不会把阴影结果当成可信数据。
            VkDisp.LOGGER.warn(
                    "vkdisp: [GAP-015] shadowtex* 是 sampler2DShadow（比较采样器），"
                            + "而本引擎**建不出比较采样器**（GpuDevice 无 CompareOp 重载，"
                            + "SamplerCache 的 boolean 是 useMipmaps 不是 compare）"
                            + " ⇒ 当前绑的是**非比较**采样器 = 描述符类型不匹配 = Vulkan UB。"
                            + " 后果限定为：**阴影项的结果不可信**（不是崩溃、不是全黑）；"
                            + "地形本身仍会画。不绑则会让每个用阴影的包整条地形不渲染，"
                            + "按兼容优先故保留绑定。正确修法需要原版提供比较采样器。");
        }
    }

    /** GAP-023 深度快照自报去重（每个名字一次）。 */
    private static final java.util.Set<String> DEPTH_SNAPSHOT_NOTED =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    /** gaux 回退自报去重。 */
    private static final java.util.Set<String> GAUX_NOTED =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    /**
     * GAP-023：把 {@code depthtexN} 绑到一张**桩**，而<b>不是</b>本 pass 的深度附件。
     *
     * <p>🔴🔴 <b>为什么必须是桩</b>：传进来的 {@code depthView} 就是<b>本 pass 自己的深度附件</b>
     * （调用点 {@code MrtTerrainPass:501-502} 逐字传 {@code colortexDepth.getDepthTextureView()}，
     * 且绑定发生在 <b>pass 内、renderGroup 之前</b>）。把一张 image 同时作读写附件与采样器
     * = Vulkan <b>未定义行为</b> —— 这条是本仓库 <b>h26 用实测换来的结论</b>，原话就写在
     * 上面那个 switch 的 {@code SHADOW_DEPTH_2D} 分支（「不得绑本 pass 的深度/颜色附件」），
     * 而 {@code mrt.shadowStubs=false} 那一支被专门标成「仅供 A/B 取证」。
     * 症状也不是假想的：{@code evidence/h25/h26} 把「整帧地形间歇性消失」收敛到
     * <b>只有包片元</b>（原版 core/terrain 不声明这些 sampler ⇒ 不触发），
     * 与 GAP-020 重开的那个「整帧为空」是<b>同一族机制</b>。
     * 本机没有 {@code VK_LAYER_KHRONOS_validation} ⇒ 这条用法错<b>不会报错</b>，
     * 所以「臂没崩」不能当作它没事（X35）。
     *
     * <p>🔶 <b>桩值语义（可解释的缺省，不是编一个好看的数）</b>：1×1 {@code D32} 清到
     * {@code 0.0} = 本引擎反向 Z 的<b>远平面</b> = 「这一层此刻还没有写过任何东西」——
     * 那正是 {@code depthtex1}（半透明后的快照）在水自己正在画的这一刻<b>应有的真值</b>。
     * 与 {@code ShadowStubs} 共用同一张图：同为「永不作附件」的 1×1 D32@0.0，
     * 不新建 GPU 资源、不加新开关。
     *
     * <p>🔴 <b>代价必须点名</b>：三个名字<b>同源且都不含场景深度</b> ⇒ 包里依赖
     * 「两个深度层之比」的判据（{@code composite.glsl:333 z1 > z0}、水的遮挡识别）
     * <b>在这一条实现真快照（GAP-023 的两次 blit）之前全部不可信</b>。
     * 相对 4568258 之前（落 {@code PLACEHOLDER_2D} = 方块图集）的净变化：
     * 不再拿图集当深度读，且<b>消除了 UB</b>。
     */
    private static com.mojang.renderpearl.api.textures.GpuTextureView gbufferDepthSnapshotStub(
            String name) {
        if (DEPTH_SNAPSHOT_NOTED.add(name)) {
            VkDisp.LOGGER.warn("vkdisp: [GAP-023] {} 绑的是 1x1 D32@0.0 **桩**"
                    + "（语义 = 远平面 = 这一层还没写过）—— 不绑本 pass 的深度附件："
                    + "读写附件 + 采样器同图 = Vulkan UB，是本仓库 h26 的实测结论，"
                    + "且本机没有 validation layer（错了不报）。"
                    + "depthtex0/1/2 目前同源、都不含场景深度 ⇒ 依赖 z1 > z0 的判据在"
                    + "GAP-023 的快照 blit 实现之前不可信", name);
        }
        return ShadowStubs.depthView();
    }

    /**
     * OF {@code gauxN} = {@code colortex(N+3)}（与链侧 {@code FrameApi} 的 {@code gaux} 分支同口径）。
     *
     * <p>🔖 解析不出槽号 / 池里没有 ⇒ 回退占位，<b>但要点名</b>：
     * 「名字里带数字却解析失败」意味着包用了本引擎没实现的家族，必须看得见（X9 / X11）。
     */
    private static com.mojang.renderpearl.api.textures.GpuTextureView gauxView(
            String name, com.mojang.renderpearl.api.textures.GpuTextureView fallback) {
        int slot;
        try {
            slot = Integer.parseInt(name.substring("gaux".length())) + 3;
        } catch (NumberFormatException e) {
            if (GAUX_NOTED.add(name)) {
                VkDisp.LOGGER.warn("vkdisp: [GAP-027] gaux 名字不带可用槽号：'{}' ⇒ 回退占位", name);
            }
            return fallback;
        }
        com.mojang.renderpearl.api.textures.GpuTextureView view = MrtTerrainPass.poolView(slot);
        if (view == null && GAUX_NOTED.add(name)) {
            VkDisp.LOGGER.warn("vkdisp: [GAP-027] {} ⇒ colortex{} 视图不存在（池未建 / 槽超上限）"
                    + " ⇒ 回退占位（不静默换名）", name, slot);
        }
        return view != null ? view : fallback;
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
