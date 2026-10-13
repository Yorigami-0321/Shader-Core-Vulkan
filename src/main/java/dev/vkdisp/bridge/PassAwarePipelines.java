package dev.vkdisp.bridge;
/**
 * 【参考调研】R1a / M-15 + R7 ⑤-b/⑤-c：pass 身份判定 —— 「这次 draw 落在哪个 pass」由数据回答
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 原版 26.3 {@code net/minecraft/client/renderer/chunk/ChunkSectionsToRender}：
 *    {@code DrawSeparate#render} 第 **176** 行与 {@code DrawIndirect#render} 第 **121** 行的
 *    {@code renderPass.setPipeline(RenderSystem.getCompiledPipeline(...))}，以及这两个 {@code render}
 *    的形参表（第 2 个形参就是 {@code RenderPass}）；
 *    ② 同 jar {@code net/minecraft/client/renderer/rendertype/PreparedRenderType}：
 *    {@code private void draw(StagedVertexBuffer$ExecuteInfo, RenderPass, RenderPipeline)} 第 **43** 行
 *    那次同样的 {@code getCompiledPipeline}（⑤-c 第一族 —— 全部 RenderType 的公共通路）；
 *    ③ MixinExtras（NeoForge 自带）的 {@code @WrapOperation} + {@code @Local(argsOnly = true)} 契约
 *    —— 只观察注解签名，不读实现。
 *    许可证：Mojang EULA（只观察语句与形参表）+ Mixin MIT + NeoForge/MixinExtras LGPL-2.1（只观察契约）。
 *    → 能否并入本项目（MIT）：可以 —— 本文件是独立编写的桥接判定，零源码搬运
 *    → 例外条款：无；不含任何 GPL / ARR 代码
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：原版把 {@code RenderPass} 一路传到取管线那一刻 ⇒ 身份判据本来就在那里，
 *    只是我方以前没用（改用进程级静态布尔）。
 * 2. 备选：① 继续用 {@code MrtTerrainPass.active() + finally} —— <b>否决</b>（{@code GAP-040}：
 *    标志位为 true 并<b>不能证明</b>取管线的那一方就在我方 pass 里，而配套守卫
 *    {@code hasExpectedAttachmentCount()} 的判定口径是「标志位为 true 就返回 true」= 空转）；
 *    ② {@code PipelineModifier} —— <b>已证伪</b>（缓存键没有包代次、key 只能启动期注册，{@code GAP-045}；
 *    且它看不到「这次要进哪个 pass」⇒ 只能对<b>所有</b> pass 一起补槽，GUI/后处理立刻全崩）。
 * 3. 我们的差异点：判定写成<b>纯函数</b>（{@link #select} / {@link #selectForMainPass}）⇒
 *    不启动客户端就能单测「错配必须被拒」。本类不含渲染调用，也不碰 {@code RenderSystem}；
 *    表的<b>所有权</b>仍在 {@code TerrainPipelineApi}（注册与查询同源），这里只是读 + 身份比较的结果应用。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：🔴 热路径 —— ⑤-c 起 {@code selectForMainPass} 是<b>每次 draw</b> 一次
 *    （{@code PreparedRenderType#draw}）：一次布尔短路 + 一层按对象身份的记忆表（见
 *    {@code MainPassPipelineDerivation} 类头第 5 条），本类自身零分配。
 *    观测面（{@link #onHit} / {@link #onRenderTypeHit}）按 M-01 的节流口径，不做性能优化（17 §3.2）。
 */
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.vkdisp.VkDisp;
import dev.vkdisp.VkDispConfig;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * H 线 M-15 的判定侧 + M-04 ⑤-b/⑤-c 的收口点（登记表：{@code docs/04-SPEC.md} §5.0 的 M-15 行、
 * §5.0.7 表 ④/⑤）。
 *
 * <p><b>它解决的问题</b>：地形管线有两套 —— 单附件版（给原版 pass）与多附件版（给我方 gbuffer pass）；
 * 主 pass 一旦被我方换成 N 附件（M-04 ⑤-a），它里面<b>每一条</b>管线也得有 N 个颜色目标。
 * 选错就是「原版单附件 pass 拿到 8 附件管线」（Vulkan 未定义）或反过来（{@code setPipeline} 抛）。
 * 旧实现靠「谁记得置位/清位」，本类改成<b>读数据</b>：draw 落到的那个 {@code RenderPass} 对象
 * 是不是我方打开的那一个 / 是不是被接管的那一个主 pass。
 */
public final class PassAwarePipelines {

    /** 观测面计数（装在持有者里，不动 QD-03 那条静态字段棘轮）。 */
    private static final class Counters {
        private long terrain;
        private long renderType;

        /**
         * ⑤-c 各族注入点各自的命中数（键的个数 = 注入点数，天然有界）。
         * 分站点计数的理由：一次真机跑要能同时看清<b>几族都活着</b>，否则只能靠崩点挪位置一族一试。
         */
        private final Map<String, Long> sites = new LinkedHashMap<>();
    }

    /** 计数<b>不参与</b>任何判定 —— 判据是 {@code renderPass} 的对象身份，否则又回到「靠标志位证明标志位」。 */
    private static final Counters COUNT = new Counters();

    private PassAwarePipelines() {
    }

    /**
     * 地形那两条的判定本体（M-15）。<b>纯函数</b>：同样的入参永远给同样的结果，因此可单测。
     *
     * <p>🔴 <b>故意错配一次必须被拒</b>：{@code ourPass == false} 时不会拿到多附件变体 ——
     * 于是原版单附件 pass 永远拿不到 8 附件管线，且这件事不再依赖「记得在 finally 里清标志位」。
     *
     * <p>⚠️ 身份对上却查不到变体（该层没规划进 gbuffer）时同样原样返回：单附件管线进 8 附件 pass
     * 会被 {@code setPipeline} <b>当场抛</b> ⇒ 是响亮失败，不会被静默画错，因此这里不加第二套告警集合
     * （{@code GAP-040} 那一族就是被这类「再兜一层」堆出来的）。
     *
     * @param layer     原版层名（{@code ChunkSectionLayer#name()}）
     * @param multiDraw 该 draw 走哪条变体（indirect = {@code true}，separate = {@code false}）
     * @param ourPass   {@code renderPass} 是否就是我方 MRT pass 那个对象（由 mixin 侧比较后传入）
     * @param takenOverMainPass {@code renderPass} 是否是<b>接管后的主 pass</b>
     *                          （{@link MainPassTakeover#isTakenOverPass}；没开 M-04 时永远 false）
     * @param requested 原版即将编译的管线（已被 M-01 换成<b>单附件</b>派生版）
     * @return 要交给 {@code RenderSystem.getCompiledPipeline} 的管线
     */
    public static RenderPipeline select(String layer, boolean multiDraw, boolean ourPass,
            boolean takenOverMainPass, RenderPipeline requested) {
        switch (routeOf(VkDispConfig.MIXIN_PASS_AWARE_PIPELINE.get(), ourPass, takenOverMainPass)) {
            case ROUTE_MRT_LAYER_TABLE: {
                RenderPipeline mrt = TerrainPipelineApi.mrtVariantOrNull(layer, multiDraw);
                return mrt == null ? requested : mrt;
            }
            case ROUTE_PADDED_MAIN_PASS:
                // ⑤-b：主 pass 现在是 N 附件 ⇒ 地形这两条也要有 N 个颜色目标，否则 setPipeline 抛。
                //        补不动的形状（声明比附件多等）由公式自己交回原版并计数（GAP-046）。
                return MainPassPipelineDerivation.derive(requested, MainPassTakeover.takenOverSlots());
            default:
                return requested;
        }
    }

    /** 原样返回入参（开关关着，或<b>两条身份都不成立</b> = 故意错配那一格）。 */
    public static final int ROUTE_VERBATIM = 0;
    /** 走 M-01 那张层名表（多附件地形管线）。 */
    public static final int ROUTE_MRT_LAYER_TABLE = 1;
    /** 走 ⑤-b 的补槽公式（被接管的主 pass）。 */
    public static final int ROUTE_PADDED_MAIN_PASS = 2;

    /**
     * 这次取管线该走哪条身份（<b>纯函数</b>，且是 {@link #select} <b>唯一</b>的判定入口）。
     *
     * <p>🔴 为什么要把它从 {@code select} 里抽出来：判据「故意错配一次必须被拒」讲的是<b>行为</b>，
     * 而 {@code select} 的入参与返回都是 renderpearl 的 {@code RenderPipeline} —— 测试运行时没有那些类
     * （{@code GAP-050}），于是那条判据此前只能用「源码里有两句 {@code return requested;}」这种<b>形状针</b>
     * 来近似。抽成整数决策之后，真值表可以逐格跑；而 {@code select} 只剩「按格子执行」，
     * 两者由 {@code MrtTerrainPassWiringTest} 那根「select 必须调 routeOf」的针钉在一起，<b>不许各长一份</b>。
     *
     * @param flagOn            {@code mixin.passAwarePipeline}（关掉 = 可关闭制 X29，逐字退回原版）
     * @param ourPass           {@code renderPass} 就是我方 MRT pass 那个对象（对象身份，mixin 侧比好传进来）
     * @param takenOverMainPass {@code renderPass} 是接管后的主 pass（{@link MainPassTakeover#isTakenOverPass}）
     */
    public static int routeOf(boolean flagOn, boolean ourPass, boolean takenOverMainPass) {
        if (!flagOn) {
            return ROUTE_VERBATIM;
        }
        if (ourPass) {
            // 同一帧里一个 pass 对象不可能既是我方 MRT pass 又是接管后的主 pass；真出现时先走表那一支
            // （它查不到就原样返回，不会把 8 附件管线塞进单附件 pass）。
            return ROUTE_MRT_LAYER_TABLE;
        }
        if (takenOverMainPass) {
            return ROUTE_PADDED_MAIN_PASS;
        }
        return ROUTE_VERBATIM;
    }

    /**
     * ⑤-c 第一族（{@code PreparedRenderType#draw} —— 全部 RenderType 的公共取管线点）的判定。
     *
     * <p>🔖 <b>为什么不复用 {@link #select}</b>：那条的入参是<b>层名</b>，要拿它查地形多附件表；
     * 而渲染类型的名字里就有 {@code "solid"} / {@code "translucent"} 这种<b>与层名同字</b>的串
     * （{@code ChunkSectionLayer} 也叫 {@code solid}）⇒ 一旦哪天有非地形的 draw 落进我方 gbuffer pass，
     * 传名字过去就会把<b>地形</b>管线塞给它（顶点格式对不上）。所以这里<b>只</b>走主 pass 那一支：
     * 补槽是「按形状改声明数」，不需要知道这是哪一族。
     *
     * @param takenOverMainPass 这次 draw 是不是落在被接管的主 pass 里（对象身份，由 mixin 侧比较后传入）
     * @param requested         原版即将编译的管线
     * @return 要交给 {@code RenderSystem.getCompiledPipeline} 的管线
     */
    public static RenderPipeline selectForMainPass(boolean takenOverMainPass, RenderPipeline requested) {
        if (!VkDispConfig.MIXIN_PASS_AWARE_PIPELINE.get() || !takenOverMainPass) {
            return requested;
        }
        return MainPassPipelineDerivation.derive(requested, MainPassTakeover.takenOverSlots());
    }

    /**
     * M1 编码约束 ③ 的埋点：证明「地形取管线那一刻真的被包住」。
     *
     * <p>🔖 计数<b>不参与</b>任何判定（T10：每处注入点必须能证明被调用）。
     */
    public static void onHit() {
        long count = ++COUNT.terrain;
        if (count == 1L || count % 600L == 0L) {
            VkDisp.LOGGER.info("vkdisp: [M-15] pass-aware getCompiledPipeline（地形两条）hits={}"
                    + "（身份比较在 mixin handler 里做，本行只是观测面）", count);
        }
    }

    /**
     * ⑤-c 各族注入点的埋点：一处一条，证明「这处取管线真的被包住」（T10：每处注入点必须能证明被调用）。
     *
     * <p>🔖 计数<b>不参与</b>任何判定；分站点是因为「崩点挪位置」一次只能证一族，
     * 而这一行能在<b>同一趟</b>真机里同时给出几族各自活没活。
     */
    public static void onMainPassSiteHit(String site) {
        long total = ++COUNT.renderType;
        long forSite = COUNT.sites.merge(site, 1L, Long::sum);
        if (forSite == 1L) {
            VkDisp.LOGGER.info("vkdisp: [M-04/⑤-c] 取管线注入点首次命中：{}", site);
        } else if (total % 600L == 0L) {
            VkDisp.LOGGER.info("vkdisp: [M-04/⑤-c] 取管线注入点累计={}，分站点={}", total, COUNT.sites);
        }
    }

    /** 累计命中次数（诊断/单测读数）：地形两条那处。 */
    public static long hitCount() {
        return COUNT.terrain;
    }

    /** 累计命中次数（诊断/单测读数）：⑤-c 那些处（全部站点合计数）。 */
    public static long renderTypeHitCount() {
        return COUNT.renderType;
    }

    /** 某一处注入点的命中数（单测/诊断读数；没跑过就是 0）。 */
    public static long siteHitCount(String site) {
        return COUNT.sites.getOrDefault(site, 0L);
    }
}
