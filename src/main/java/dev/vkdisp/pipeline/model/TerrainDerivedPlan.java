package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】H 线 M-01 派生地形管线规格表 / 原版 {@code RenderPipelines} 的地形管线定义（事实性登记）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 原版 26.3 {@code net.minecraft.client.renderer.RenderPipelines} 中
 *    {@code SOLID_TERRAIN / CUTOUT_TERRAIN / TRANSLUCENT_TERRAIN} 与四个 {@code *_MULTIDRAW}
 *    的**构造参数形状**（javap + 随 MDG 分发的 sources jar 逐行读出，见证据文件
 *    {@code evidence/h01-terrain-pipeline-wire.md} §2 的源码级核实表）。
 *    许可证：Mojang EULA（原版）。**只提取「有哪些 define / 什么混合模式」这类事实性参数**，
 *    不搬运任何 GLSL 源码、不复制任何 Java 实现语句 —— 本文件是参数表，不是原版管线的副本。
 *    → 能否并入本项目（MIT）：可以 —— 事实性参数不受版权保护，且本文件为独立编写的规格表
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码，也不含 Mojang 的着色器文本
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：原版 {@code RenderPipelines.TERRAIN_SNIPPET} 与
 *    {@code MULTIDRAW_TERRAIN_SNIPPET} —— 派生管线一律以这两个 snippet 为基底，
 *    只改 location / ALPHA_CUTOUT / ColorTargetState 三处，保证与原版**逐项等价**。
 * 2. 备选：直接持有原版 {@code RenderPipeline} 引用并只记 location —— 否决（那样无法在
 *    无 GPU 的单测里核对「派生管线是否把原版的状态抄全了」，抄漏一项就是静默的画面差异）。
 * 3. 我们的差异点：把 6 条派生管线的差异**摊平成可单测的纯数据表**
 *    （层 × {普通, 多重绘制}），并给出 location 的**唯一生成式**（禁止散落字面量）。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 冷路径（资源加载期一次性注册），不做性能优化（17-NATIVE.md §3.2）。
 */
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * H 线 M-01 的**派生地形管线规格表**（纯数据，零原版渲染类型依赖 ⇒ 可在无 GPU 单测里自证）。
 *
 * <p><b>为什么要它</b>：M-01 把原版 {@code SOLID/CUTOUT/TRANSLUCENT_TERRAIN}（各含
 * 多重绘制变体）替换成同名的派生管线。派生管线若**少抄一项**原版状态（少一个
 * {@code ALPHA_CUTOUT}、漏了 TRANSLUCENT 的混合模式），画面就会出现难以归因的差异
 * —— 而这种错误不会抛异常。因此把差异摊成表，让「抄全了」成为可断言的事实。
 *
 * <p><b>与原版的对应关系（26.3.0.41-beta sources jar 逐行核实）</b>：
 * <table border="1">
 *   <caption>原版 → 派生</caption>
 *   <tr><th>层</th><th>原版 location</th><th>原版额外参数</th><th>派生 location</th></tr>
 *   <tr><td>SOLID</td><td>{@code pipeline/solid_terrain}</td><td>（无）</td>
 *       <td>{@code vkdisp:pipeline/terrain_solid}</td></tr>
 *   <tr><td>CUTOUT</td><td>{@code pipeline/cutout_terrain}</td>
 *       <td>{@code withShaderDefine("ALPHA_CUTOUT", 0.5F)}</td>
 *       <td>{@code vkdisp:pipeline/terrain_cutout}</td></tr>
 *   <tr><td>TRANSLUCENT</td><td>{@code pipeline/translucent_terrain}</td>
 *       <td>{@code ALPHA_CUTOUT=0.1F} + {@code new ColorTargetState(BlendFunction.TRANSLUCENT)}</td>
 *       <td>{@code vkdisp:pipeline/terrain_translucent}</td></tr>
 * </table>
 * 多重绘制变体（{@code *_MULTIDRAW}）以 {@code MULTIDRAW_TERRAIN_SNIPPET} 为基底，
 * location 后缀 {@code _multidraw}；参数差异与非多重绘制版**完全相同**。
 */
public final class TerrainDerivedPlan {

    /** 派生管线 location 的命名空间（与既有 {@code PipelineApi} 管线同域）。 */
    public static final String NAMESPACE = "vkdisp";

    /** 派生管线 location 的路径前缀（与 {@code FULLSCREEN_LOCATION} 等同形）。 */
    public static final String LOCATION_PREFIX = "pipeline/terrain_";

    /** M-01 派生管线的自定义 uniform 块名（GAP-004）。 */
    public static final String PARAMS_UNIFORM = "VkDispTerrainParams";

    /** {@link #PARAMS_UNIFORM} 的 std140 字节数（2 × vec4 = 32）。 */
    public static final int PARAMS_BYTES = 32;

    /** M-01 派生地形管线可识别的层名（原版 {@code ChunkSectionLayer} 的枚举名）。 */
    public static final List<String> LAYER_NAMES = List.of("SOLID", "CUTOUT", "TRANSLUCENT");

    /** M-01 会接管的 {@code multiDraw} 取值（true = {@code *_MULTIDRAW} 变体）。 */
    public static final List<Boolean> MULTIDRAW_VALUES = List.of(Boolean.FALSE, Boolean.TRUE);

    /**
     * 一条派生管线的规格。
     *
     * @param layer           原版层名（{@code SOLID} / {@code CUTOUT} / {@code TRANSLUCENT}）
     * @param multiDraw       是否为多重绘制变体
     * @param alphaCutout     {@code ALPHA_CUTOUT} define 的阈值；{@code null} = 原版就没有该 define
     * @param translucentBlend 是否用 {@code BlendFunction.TRANSLUCENT} 作颜色目标
     * @param location        派生管线 location（由 {@link #locationOf} 唯一生成）
     */
    public record Spec(String layer, boolean multiDraw, Float alphaCutout, boolean translucentBlend,
            String location) {

        public Spec {
            java.util.Objects.requireNonNull(layer, "layer");
            java.util.Objects.requireNonNull(location, "location");
            if (!LAYER_NAMES.contains(layer)) {
                throw new IllegalArgumentException("unknown terrain layer: " + layer);
            }
        }

        /** 是否有 {@code ALPHA_CUTOUT} define（原版 SOLID 没有，另两层有）。 */
        public boolean hasAlphaCutout() {
            return alphaCutout != null;
        }
    }

    private TerrainDerivedPlan() {
    }

    /**
     * 派生管线 location 的**唯一生成式**（禁止散落字面量，X9 不猜 + 升级排查只看这一处）。
     *
     * @param layer     原版层名，小写（{@code solid} / {@code cutout} / {@code translucent}）
     * @param multiDraw 是否为多重绘制变体
     */
    public static String locationOf(String layer, boolean multiDraw) {
        return NAMESPACE + ":" + LOCATION_PREFIX + layer + (multiDraw ? "_multidraw" : "");
    }

    /** 全量规格表（3 层 × 2 变体 = 6 条），顺序稳定，供注册循环与单测双向核对。 */
    public static List<Spec> all() {
        List<Spec> specs = new ArrayList<>(LAYER_NAMES.size() * MULTIDRAW_VALUES.size());
        for (String layer : LAYER_NAMES) {
            for (boolean multiDraw : MULTIDRAW_VALUES) {
                specs.add(specOf(layer, multiDraw));
            }
        }
        return List.copyOf(specs);
    }

    /**
     * 单条规格。损坏输入（未知层名）显式抛 {@link IllegalArgumentException}，绝不返回默认值
     * —— 默认值会让「层名拼错」变成静默的管线不匹配（07-CONSTRAINTS T11）。
     */
    public static Spec specOf(String layer, boolean multiDraw) {
        String lower = layer.toLowerCase(java.util.Locale.ROOT);
        if (!LAYER_NAMES.contains(layer)) {
            throw new IllegalArgumentException("unknown terrain layer: " + layer);
        }
        Float alphaCutout = switch (layer) {
            // 原版 SOLID_TERRAIN 无 ALPHA_CUTOUT define（RenderPipelines:349-351）。
            case "SOLID" -> null;
            // 原版 CUTOUT_TERRAIN = ALPHA_CUTOUT 0.5F（RenderPipelines:379-385）。
            case "CUTOUT" -> 0.5F;
            // 原版 TRANSLUCENT_TERRAIN = ALPHA_CUTOUT 0.1F + TRANSLUCENT 混合（393-399）。
            case "TRANSLUCENT" -> 0.1F;
            default -> throw new IllegalArgumentException("unknown terrain layer: " + layer);
        };
        return new Spec(layer, multiDraw, alphaCutout, "TRANSLUCENT".equals(layer), locationOf(lower, multiDraw));
    }

    /** 按层名 + 变体查规格；层名不可识别时返回空（调用方必须显式处理，见 {@code TerrainPipelineApi}）。 */
    public static Optional<Spec> find(String layer, boolean multiDraw) {
        try {
            return Optional.of(specOf(layer, multiDraw));
        } catch (IllegalArgumentException unknownLayer) {
            return Optional.empty();
        }
    }
}