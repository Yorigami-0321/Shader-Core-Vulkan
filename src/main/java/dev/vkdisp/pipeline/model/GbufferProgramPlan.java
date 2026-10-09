package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】GAP-027「多条 gbuffer 程序接进同一个 MRT pass」的**决策表** / 本仓库自有的
 * {@link MrtPlan}（多附件冻结契约）+ {@link PackTerrainProgram}（单条程序的渲染契约）+
 * {@link TerrainDerivedPlan}（层 × 变体的派生管线规格）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本项目自有的三个纯数据类（同为 MIT，零外部代码）与
 *    {@code docs/04-SPEC.md} §5.0（M-01 注入点）/ {@code docs/13-GAP-REGISTRY.md} GAP-027 条目。
 *    原版侧只用到一条**事实性 API 形状**（{@code RenderPipeline.Snippet} 的
 *    {@code withDepthStencilState} 覆盖是逐管线的，不是逐 pass 的 —— javap 核实
 *    {@code RenderPipeline$Builder} 与 {@code DepthStencilState} 的公开签名，Mojang EULA，
 *    **不搬运任何实现语句**)。
 *    → 能否并入本项目（MIT）：可以（本文件是独立编写的纯数据决策表）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码，也不含 Mojang 的着色器文本
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：无（原版没有「一个 gbuffer pass 里同时挂多条包程序」这个概念 ——
 *    OF/Iris 靠 {@code gbuffers_water} 单独一道 draw，而它的**渲染契约**（几个附件、
 *    哪些槽被写）在 Vulkan 上必须由我们显式声明）。
 * 2. 备选：
 *    ① 在 {@code bridge.TerrainPipelineApi} 里直接 if-else 判断水/地形 —— <b>否决</b>：
 *      bridge 侧要加载 {@code RenderPipeline} / {@code MappableRingBuffer} 等原版类型，
 *      单测跑不了（本项目已实测：FML 类在单测里 {@code NoClassDefFoundError}）；
 *      「哪一层用哪条程序」「自报行该长什么样」恰恰是最该被单测钉住的两件事。
 *    ② 把程序名写死进 {@link TerrainDerivedPlan} 的 {@code Spec} —— <b>否决</b>：
 *      Spec 是「与原版逐项等价」的参数表（它的单测逐条抄自原版 RenderPipelines 源码核实表），
 *      往里加包语义会把两套口径混成一份，两边都失去对照价值。
 *    ③ 每条程序各写一份「路径 + id + 层归属 + 自报」—— <b>否决</b>，这就是本项目反复吃的
 *      「两份状态互相矛盾而日志全正常」那一族（X42）；本类把这些量收成<b>程序名的纯函数</b>。
 * 3. 我们的差异点：
 *    ① <b>路径/id 全部由程序名派生</b>（{@link #fragmentPath} / {@link #adapterPath}），
 *      新增第三条程序（{@code gbuffers_skybasic} …）时<b>不必</b>再动虚拟包的十个硬编码点；
 *    ② <b>层 → 程序</b>是一张可单测的表（{@link #programForLayer}），不是散在注册循环里的 if；
 *    ③ <b>深度写入</b>按层决定（{@link #writesDepth} / {@link #DEPTH_WRITE_OFF_LAYERS}）：
 *      今天是<b>所有层都写深度</b>（与原版 {@code TRANSLUCENT_TERRAIN} 一致；唯一该关的是
 *      {@code WEATHER}，它还没进本 pass）—— 🔴 此前把 TRANSLUCENT 关了，是错的，见那个字段的注释；
 *    ④ 自报行由本类生成（{@link #wiredReport} / {@link #notWiredReport}）：
 *      「接了哪几条、各几个输出、最后几个附件」与「为什么没接」都必须<b>一行说清</b>
 *      （GAP-026 同一口径：不报 = 不知道）。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 冷路径（管线注册一次 / 资源重载一次）；渲染期只读已冻结的结果。
 */
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * GAP-027：「哪几条包 gbuffer 程序被接进 MRT pass、各自负责哪一层、由此得到什么」的决策表。
 *
 * <p><b>零原版类型依赖</b>（不 import {@code RenderPipeline} / {@code Identifier} /
 * {@code VkDispConfig}）⇒ 可以在无 GPU、无 FML 的单测里逐条断言（本项目的既定纪律：
 * 决策逻辑放能测的地方，执行留在 bridge）。
 *
 * <p><b>本类不读配置</b>：{@code mrt.packWater} 的开与关由调用方折算成
 * 「{@link Entry#contract()} 是不是 {@code null}」再传进来。理由：配置项一旦在生成期被读，
 * 就必须进记忆键（QD-08 那一族）；把「读配置」留给调用方，本类就只剩纯映射，
 * 而键的守卫仍在 {@code VkDispVirtualPack#currentTerrainMemoKey} 那一侧生效。
 */
public final class GbufferProgramPlan {

    /** 半透明地形所在的层名（原版 {@code ChunkSectionLayer.TRANSLUCENT} 的枚举名）。 */
    public static final String TRANSLUCENT_LAYER = "TRANSLUCENT";

    /** MRT 变体 location 的既有后缀（GAP-003 起就是这个形状，不改）。 */
    public static final String MRT_SUFFIX = "_mrt";

    /** 挂<b>非地形</b>程序时追加到 location 的名字段（GAP-027：换程序就换 location，别拿同一个名字挂两套内容）。 */
    public static final String MRT_PROGRAM_SEPARATOR = "_";

    /** OF 的 gbuffer 程序名前缀（路径派生靠它切出简称）。 */
    public static final String GBUFFERS_PREFIX = "gbuffers_";

    /** 一条程序的接线声明。 */
    public record Entry(String program, PackTerrainProgram contract) {

        public Entry {
            Objects.requireNonNull(program, "program");
        }

        /** 是否真的接上了（拿到契约才算接上；{@code null} = 不接，这是<b>正常状态</b>不是失败）。 */
        public boolean wired() {
            return contract != null && contract.outputCount() > 0;
        }

        /** 该程序对 MRT pass 的贡献（不接 ⇒ 输出数 0，{@link MrtPlan#freezePackPrograms} 会跳过它）。 */
        public MrtPlan.PackProgram toPackProgram() {
            return new MrtPlan.PackProgram(program,
                    wired() ? contract.outputCount() : 0,
                    wired() ? contract.declaredOutputSlots() : List.of());
        }
    }

    /** 「没接」的原因（自报必须把原因说清，绝不静默：X9 / X11 / T11）。 */
    public enum Skip {
        /** 配置关着（{@code mrt.packWater=false}）—— 默认档就是这个。 */
        DISABLED("disabled (mrt.packWater=false)"),
        /** 包里<b>没有</b>这条程序（换包后完全正常，不是失败）。 */
        ABSENT("absent (the selected pack has no such program)"),
        /** 有程序但契约解析不出来 / 槽位语义未被兑现 ⇒ 拒绝接线。 */
        UNPARSEABLE("contract could not be parsed (slot semantics refused)"),
        /** 生成链抛了异常（原文已由调用方打过 ERROR）。 */
        GENERATION_FAILED("source generation threw");

        private final String text;

        Skip(String text) {
            this.text = text;
        }

        /** 自报里的那一段（英文，与本项目其它日志同口径，便于 grep）。 */
        public String text() {
            return text;
        }
    }

    private GbufferProgramPlan() {
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // ① 资源路径 / 着色器 id：全部是程序名的纯函数
    // ────────────────────────────────────────────────────────────────────────────────

    /**
     * 程序简称：去掉 {@code gbuffers_} 前缀（{@code gbuffers_water → water}）。
     *
     * <p>🔖 不带前缀时按原名返回（不抛）：本方法同时喂给<b>路径</b>与<b>着色器 id</b> 两侧
     * ⇒ 两条虚拟包资源与管线引用永远指向同一个名字，不会出现「包提供了但管线找不到」。
     */
    public static String shortName(String program) {
        return program.startsWith(GBUFFERS_PREFIX) ? program.substring(GBUFFERS_PREFIX.length()) : program;
    }

    /** 片元源在虚拟包内的路径（相对 {@code assets/<ns>/}）。 */
    public static String fragmentPath(String program) {
        return "shaders/" + program + ".fsh";
    }

    /** 顶点适配层源在虚拟包内的路径（逐程序<b>各自一份</b>，绝不复用别条程序的适配层）。 */
    public static String adapterPath(String program) {
        return "shaders/" + shortName(program) + "_pack_adapter.vsh";
    }

    /** 管线的片元着色器 id 路径（FileToIdConverter 会解析到 {@link #fragmentPath}）。 */
    public static String fragmentShaderPath(String program) {
        return program;
    }

    /** 管线的顶点着色器 id 路径（FileToIdConverter 会解析到 {@link #adapterPath}）。 */
    public static String adapterShaderPath(String program) {
        return shortName(program) + "_pack_adapter";
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // ② 层 → 程序 / 深度写入 / location
    // ────────────────────────────────────────────────────────────────────────────────

    /**
     * 该层挂哪条程序；返回 {@code null} = <b>沿用原版 {@code core/terrain}</b>（不是失败）。
     *
     * <p>🔖 规则只有两条，且都是「包语义」而非「层语义」的猜测：
     * <ol>
     *   <li>{@code TRANSLUCENT} 且水接上了 ⇒ 水（GAP-027 的本体）；</li>
     *   <li>其余情形 ⇒ 地形（接上了就用它，没接上就 {@code null} = 原版）。</li>
     * </ol>
     * ⇒ 水关着时，三层的返回值与 GAP-027 之前<b>逐字相同</b>。
     */
    public static String programForLayer(String layer, Entry terrain, Entry water) {
        if (water != null && water.wired() && TRANSLUCENT_LAYER.equals(layer)) {
            return water.program();
        }
        return terrain != null && terrain.wired() ? terrain.program() : null;
    }

    /** 该层实际用到的声明（{@code null} = 该层不接任何包程序）。 */
    public static Entry entryForLayer(String layer, Entry terrain, Entry water) {
        String program = programForLayer(layer, terrain, water);
        if (program == null) {
            return null;
        }
        return program.equals(water != null ? water.program() : null) ? water : terrain;
    }

    /**
     * 该层的 MRT 管线<b>是否写深度</b>。
     *
     * <p>🔴 只有「TRANSLUCENT 且真挂了包水程序」才关写深度 ⇒ 深度<b>测试仍开</b>。
     * 其余各层保持 {@code DepthStencilState.DEFAULT}（= 测试开 + 写开），
     * 与既有臂逐字相同。
     *
     * <p>🔖 为什么这件事能逐层做而不用改 pass：本前端的深度写入状态挂在<b>管线</b>上
     * （{@code RenderPipeline$Builder#withDepthStencilState}，javap 核实），
     * 不在 {@code RenderPassDescriptor} 上 ⇒ 半透明那一层的管线单独关写深度，
     * 固体/cutout 两条管线一个字节都不变。
     */
    public static boolean writesDepth(String layer, Entry terrain, Entry water) {
        return !DEPTH_WRITE_OFF_LAYERS.contains(layer);
    }

    /**
     * 本 pass 里<b>不</b>写深度的层。<b>今天是空集</b>，而且这不是「还没想到」，是核实过原版的结论：
     *
     * <p>🔴 原版事实（{@code net/minecraft/client/renderer/RenderPipelines.java}，本轮逐行核实）：
     * <ul>
     *   <li>{@code TRANSLUCENT_TERRAIN}（第 393-399 行）与 {@code TRANSLUCENT_TERRAIN_MULTIDRAW}
     *       （第 400-406 行）<b>根本没有</b> {@code withDepthStencilState} 那一格
     *       ⇒ 走 builder 默认 = {@code DepthStencilState.DEFAULT} = <b>写深度开</b>；</li>
     *   <li>{@code TRANSLUCENT_BLOCK}（第 434-441 行）更是<b>显式</b>写
     *       {@code .withDepthStencilState(DepthStencilState.DEFAULT)}；</li>
     *   <li>整张表里唯一关写深度的 gbuffer 阶段层是 {@code WEATHER}
     *       （第 1028-1032 行）= {@code (GREATER_THAN_OR_EQUAL, writeDepth=false)}。</li>
     * </ul>
     *
     * <p>🔴 <b>本条是一次改错</b>：GAP-027 接水时把 {@code TRANSLUCENT} 也关掉了写深度，
     * 两处都错 —— ① 破坏与原版逐项等价（支柱①）； 让 OF 的 {@code depthtex1}
     * （= 半透明之后的深度）<b>在结构上永远等于</b> {@code depthtex0}，于是 GAP-023
     * 那三个时刻的快照永远拍不出差别（真机 h50p 实测：八个朝向、pitch 0，
     * {@code depthviz0} 与 {@code depthviz1} 每一帧都是同一个数 16.136）。
     * 天气层还没接进本 pass ⇒ 等它接进来时把 {@code "WEATHER"} 加进这个集合，
     * <b>而不是</b>再给半透明加特例。
     */
    public static final java.util.Set<String> DEPTH_WRITE_OFF_LAYERS = java.util.Set.of();

    /** 该层是不是「被包水程序接管的半透明层」。 */
    public static boolean isWaterLayer(String layer, Entry terrain, Entry water) {
        return water != null && water.wired() && TRANSLUCENT_LAYER.equals(layer);
    }

    /**
     * MRT 变体 location 的后缀。
     *
     * <p>🔖 地形保持既有 {@code _mrt}（历史臂与日志对账靠它）；换成别的程序时把简称插进去
     * （{@code _water_mrt}）⇒ 同一层不会因为「换了片元却沿用同一个 location」出现
     * 两条同名不同内容的管线。
     */
    public static String mrtSuffix(String program, String terrainProgram) {
        if (program == null || program.equals(terrainProgram)) {
            return MRT_SUFFIX;
        }
        return MRT_PROGRAM_SEPARATOR + shortName(program) + MRT_SUFFIX;
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // ③ 冻结用的列表 + 自报行
    // ────────────────────────────────────────────────────────────────────────────────

    /** 参与冻结的声明（跳过不接的；顺序稳定 = 传入顺序）。 */
    public static List<MrtPlan.PackProgram> packPrograms(List<Entry> entries) {
        List<MrtPlan.PackProgram> programs = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            if (entry != null) {
                programs.add(entry.toPackProgram());
            }
        }
        return List.copyOf(programs);
    }

    /** 接上的条数（自报与「一条都没接」判据用）。 */
    public static long wiredCount(List<Entry> entries) {
        return entries.stream().filter(Objects::nonNull).filter(Entry::wired).count();
    }

    /**
     * 注册期的<b>唯一一行</b>自报（GAP-027 强制项）。
     *
     * <p>形态（调用方加 {@code vkdisp: [GAP-027] } 前缀）：
     * <pre>
     * gbuffer programs wired: [gbuffers_terrain(1 out), gbuffers_water(2 out)] -&gt; attachments=2
     * </pre>
     * 🔖 三个量必须同时出现：<b>接了哪几条</b> / <b>各几个输出</b> / <b>附件数取到几</b>。
     * 少任一条都会让取证者把「接了但没生效」读成「没接」，或把 {@code attachments=2}
     * 读成「两个附件都被写了」（后者由 {@link #attachmentsNote} 补上）。
     *
     * @param attachments 实际附件数（= {@link MrtPlan#slotCount()}，注册期冻结后的值）
     */
    public static String wiredReport(List<Entry> entries, int attachments) {
        StringBuilder text = new StringBuilder("gbuffer programs wired: [");
        boolean first = true;
        for (Entry entry : entries) {
            if (entry == null || !entry.wired()) {
                continue;
            }
            if (!first) {
                text.append(", ");
            }
            first = false;
            text.append(entry.program()).append('(').append(entry.contract().outputCount())
                    .append(" out)");
        }
        text.append("] -> attachments=").append(attachments);
        return text.toString();
    }

    /**
     * 附件数「没被写满」那半句自报（<b>不</b>并进 {@link #wiredReport} 的理由：
     * 它是「写了几个」的细节，而 wiredReport 的口径是「接了几条程序」——
     * 混在一行里会让人把 {@code unwritten=[1]} 读成「第 1 条程序没接」）。
     */
    public static String attachmentsNote(List<Integer> declaredSlots, int attachments) {
        List<Integer> unwritten = new ArrayList<>();
        for (int slot = 0; slot < attachments; slot++) {
            final int current = slot;
            if (declaredSlots.stream().noneMatch(value -> value != null && value == current)) {
                unwritten.add(slot);
            }
        }
        return "attachments written by pack programs=" + declaredSlots
                + " unwritten=" + (unwritten.isEmpty() ? "[]" : unwritten.toString());
    }

    /**
     * 「这条程序没接」的一行自报（强制：<b>永不静默</b>）。
     *
     * <p>形态：
     * <pre>
     * gbuffers_water not wired because absent (the selected pack has no such program)
     *   -&gt; TRANSLUCENT keeps gbuffers_terrain's pipeline; terrain wiring unaffected
     * </pre>
     * 🔖 必须连带说明<b>后果</b>与<b>影响面</b>：只说「没接」会被读成「接了但不生效」
     * （本项目最贵的两类混淆之一）。
     */
    public static String notWiredReport(String program, Skip skip, String fallbackProgram) {
        String consequence = program.equals(fallbackProgram)
                ? "-> no gbuffer program wired; MRT pass keeps vanilla core/terrain"
                : "-> " + TRANSLUCENT_LAYER + " keeps "
                        + (fallbackProgram == null ? "vanilla core/terrain" : fallbackProgram)
                        + "'s pipeline; terrain wiring unaffected";
        return program + " not wired because " + skip.text() + " " + consequence;
    }

    /** 便捷构造：地形 + 水两条声明（顺序 = 冻结顺序 = 自报顺序）。 */
    public static List<Entry> entriesOf(Entry terrain, Entry water) {
        List<Entry> entries = new ArrayList<>(2);
        if (terrain != null) {
            entries.add(terrain);
        }
        if (water != null) {
            entries.add(water);
        }
        return List.copyOf(entries);
    }
}
