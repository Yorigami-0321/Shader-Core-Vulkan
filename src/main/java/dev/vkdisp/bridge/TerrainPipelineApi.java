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
import dev.vkdisp.pipeline.model.TerrainDerivedPlan;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

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
                builder.withLocation(Identifier.fromNamespaceAndPath(
                                TerrainDerivedPlan.NAMESPACE, spec.location()
                                        .substring((TerrainDerivedPlan.NAMESPACE + ":").length())))
                        // GAP-004：自定义 uniform 块挂在派生管线自己的绑定组上（原版 Globals 只有 9 字段，放不下）。
                        .withBindGroupLayout(BindGroupLayout.builder()
                                .withUniform(TERRAIN_PARAMS_UNIFORM, UniformType.UNIFORM_BUFFER)
                                .build());
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
     * M-01 注入点入口：把「原版层名 + multiDraw」解析成派生管线（**只查表，不写渲染逻辑**）。
     *
     * @return 派生管线；开关关闭 / 未注册 / 层名不可识别 → {@code null}（调用方必须继续走原版返回值）
     */
    public static RenderPipeline derivedTerrainPipeline(String layer, boolean multiDraw) {
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
}