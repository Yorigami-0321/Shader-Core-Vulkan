package dev.vkdisp.bridge;
/**
 * 【参考调研】R1a / M-15：pass 身份判定 —— 「这次 draw 是不是画进我方那个多附件 pass」由数据回答
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 原版 26.3 {@code net/minecraft/client/renderer/chunk/ChunkSectionsToRender}：
 *    {@code DrawSeparate#render} 第 **176** 行与 {@code DrawIndirect#render} 第 **121** 行的
 *    {@code renderPass.setPipeline(RenderSystem.getCompiledPipeline(...))}，以及这两个 {@code render}
 *    的形参表（第 2 个形参就是 {@code RenderPass}）；
 *    ② MixinExtras（NeoForge 自带）的 {@code @WrapOperation} + {@code @Local(argsOnly = true)} 契约
 *    —— 只观察注解签名，不读实现。
 *    许可证：Mojang EULA（只观察语句与形参表）+ Mixin MIT + NeoForge/MixinExtras LGPL-2.1（只观察契约）。
 *    → 能否并入本项目（MIT）：可以 —— 本文件是独立编写的桥接判定，零源码搬运
 *    → 例外条款：无；不含任何 GPL / ARR 代码
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：原版把 {@code RenderPass} 一路传到取管线那一刻 ⇒ 身份判据本来就在那里，
 *    只是我方以前没用（改用进程级静态布尔）。
 * 2. 备选：① 继续用 {@code MrtTerrainPass.active() + finally} —— **否决**（{@code GAP-040}：
 *    标志位为 true 并<b>不能证明</b>取管线的那一方就在我方 pass 里，而配套守卫
 *    {@code hasExpectedAttachmentCount()} 的判定口径是「标志位为 true 就返回 true」= 空转）；
 *    ② {@code PipelineModifier} —— **已证伪**（缓存键没有包代次、key 只能启动期注册，{@code GAP-045}）。
 * 3. 我们的差异点：判定写成<b>纯函数</b>（{@link #select}）⇒ 不启动客户端就能单测「错配必须被拒」。
 *    本类不含渲染调用，也不碰 {@code RenderSystem}；表的<b>所有权</b>仍在 {@code TerrainPipelineApi}
 *    （注册与查询同源），这里只是读 + 一次身份比较的结果应用。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：🔴 热路径 —— 每帧每层 1 次：一次布尔短路 + 一次 map.get，无分配。
 *    观测面（{@link #onHit()}）按 M-01 的节流口径，不做性能优化（17 §3.2）。
 */
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.vkdisp.VkDisp;
import dev.vkdisp.VkDispConfig;

/**
 * H 线 M-15 的判定侧（登记表：{@code docs/04-SPEC.md} §5.0 的 M-15 行）。
 *
 * <p><b>它解决的问题</b>：地形管线有两套 —— 单附件版（给原版 pass）与多附件版（给我方 gbuffer pass）。
 * 选错就是「原版单附件 pass 拿到 8 附件管线」（Vulkan 未定义）或反过来（{@code setPipeline} 抛）。
 * 旧实现靠「谁记得置位/清位」，本类改成<b>读数据</b>：draw 落到的那个 {@code RenderPass} 对象
 * 是不是我方打开的那一个。
 */
public final class PassAwarePipelines {

    /** 命中计数（只作观测面，<b>不是</b>判据的输入 —— 否则又回到「靠标志位证明标志位」）。 */
    private static long hits;

    private PassAwarePipelines() {
    }

    /**
     * 判定本体。<b>纯函数</b>：同样的入参永远给同样的结果，因此可单测。
     *
     * <p>🔴 <b>故意错配一次必须被拒</b>：{@code ourPass == false} 时逐字<b>原样返回</b>入参 ——
     * 于是原版单附件 pass 永远拿不到多附件管线，且这件事不再依赖「记得在 finally 里清标志位」。
     *
     * <p>⚠️ 身份对上却查不到变体（该层没规划进 gbuffer）时同样原样返回：单附件管线进 8 附件 pass
     * 会被 {@code setPipeline} <b>当场抛</b> ⇒ 是响亮失败，不会被静默画错，因此这里不加第二套告警集合
     * （{@code GAP-040} 那一族就是被这类「再兜一层」堆出来的）。
     *
     * @param layer     原版层名（{@code ChunkSectionLayer#name()}）
     * @param multiDraw 该 draw 走哪条变体（indirect = {@code true}，separate = {@code false}）
     * @param ourPass   {@code renderPass} 是否就是我方 MRT pass 那个对象（由 mixin 侧比较后传入）
     * @param requested 原版即将编译的管线（已被 M-01 换成<b>单附件</b>派生版）
     * @return 要交给 {@code RenderSystem.getCompiledPipeline} 的管线
     */
    public static RenderPipeline select(String layer, boolean multiDraw, boolean ourPass,
            RenderPipeline requested) {
        if (!VkDispConfig.MIXIN_PASS_AWARE_PIPELINE.get() || !ourPass) {
            return requested;
        }
        RenderPipeline mrt = TerrainPipelineApi.mrtVariantOrNull(layer, multiDraw);
        return mrt == null ? requested : mrt;
    }

    /**
     * M1 编码约束 ③ 的埋点：证明「取管线那一刻真的被包住」。
     *
     * <p>🔖 计数<b>不参与</b>任何判定 —— 判据是 {@code renderPass} 的对象身份，
     * 这条只回答「这段注入到底跑没跑」（T10：每处注入点必须能证明被调用）。
     */
    public static void onHit() {
        long count = ++hits;
        if (count == 1L || count % 600L == 0L) {
            VkDisp.LOGGER.info("vkdisp: [M-15] pass-aware getCompiledPipeline hits={}"
                    + "（身份比较在 mixin handler 里做，本行只是观测面）", count);
        }
    }

    /** 累计命中次数（诊断/单测读数）。 */
    public static long hitCount() {
        return hits;
    }
}
