package dev.vkdisp.mixin;
/**
 * 【参考调研】R7 ⑤-c 第一族：主 pass 被接管后，<b>全部</b> RenderType 的取管线点也要补到 N 槽
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 原版 26.3.0.51-beta sources jar
 *    {@code net/minecraft/client/renderer/rendertype/PreparedRenderType}：
 *    {@code private void draw(StagedVertexBuffer$ExecuteInfo, RenderPass, RenderPipeline)} 第 <b>43</b> 行
 *    的 {@code renderPass.setPipeline(RenderSystem.getCompiledPipeline(renderPipeline))}，
 *    以及 {@code RenderSystem} 第 106-127 行那对 {@code getCompiledPipelineNullable}/{@code getCompiledPipeline}
 *    （返回 {@code CompiledRenderPipeline} ⇒ 补槽必须发生在<b>进缓存之前</b>，也就是这一行）；
 *    ② MixinExtras 的 {@code @WrapOperation} / {@code @Local(argsOnly = true)} 公开契约（不读实现）。
 *    许可证：Mojang EULA（只观察语句与形参表）+ Mixin MIT + NeoForge/MixinExtras LGPL-2.1（只观察契约）
 *    → 能否并入本项目（MIT）：可以；本文件独立编写，零源码搬运
 *    → 例外条款：无；不含任何 GPL / ARR 代码
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：原版在这里「取编译好的管线 → 交给当前 pass」，中间没有任何可按 pass 分派的钩子；
 *    我方只是把那次取管线的<b>入参</b>换成补槽后的同一条管线。
 * 2. 备选：① 包一层 {@code RenderPass#setPipeline} —— <b>否决</b>：它收的是
 *    {@code CompiledRenderPipeline}（{@code FrontendRenderPass:107}），补槽要改的是<b>声明</b>，
 *    那一刻已经编译完了；② {@code PipelineModifier} —— <b>否决</b>（{@code GAP-045}：key 只能启动期注册，
 *    而槽数 N 是运行期的数；而且 modifier 看不到「这次要进哪个 pass」，全局补槽会连带 GUI / 后处理一起坏）；
 *    ③ 逐族（实体 / 粒子 / 云 / 天气…）各注一处 —— <b>否决</b>：本轮数出来 34 处取管线点里
 *    这<b>一处</b>就是全部 RenderType 的公共通路，逐族注是十几处注入点换一处。
 * 3. 我们的差异点：方法体只有「埋点 + 一次 bridge 判定 + 原样把结果交给 original」（X25）。
 *    pass 身份来自<b>形参</b> {@code renderPass} 的对象比较（不是静态标志，{@code GAP-040} 那一族）。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：🔴 最热的取管线点（每帧每次 draw 一次）。handler 只做一次对象引用比较 +
 *    一次身份表查（{@code MainPassPipelineDerivation.remember}），零分配；埋点按 M-01 节流口径。
 */
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.vkdisp.bridge.MainPassTakeover;
import dev.vkdisp.bridge.MixinTargets;
import dev.vkdisp.bridge.PassAwarePipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * R7 ⑤-c 第一族（登记表：{@code docs/04-SPEC.md} §5.0.7 表 ⑤ 行 c）：
 * {@code PreparedRenderType#draw} 第 43 行那次 {@code getCompiledPipeline} 由 <b>pass 对象身份</b>
 * 决定是否补到主 pass 的槽数。
 *
 * <p><b>覆盖范围</b>：这一处是全部 {@code RenderType} 的公共通路（实体、粒子、方块层、水/玻璃、
 * 经典透明那一大族）⇒ ⑤-c「逐族放开」的第一族一处顶十几族。真机也是先在这里响的：
 * 崩栈 {@code PreparedRenderType.draw:43} ← {@code drawFromBuffer:29} ← {@code executeClassicTransparency:721}。
 *
 * <p><b>为什么不复用 M-15 那个 {@code select(layer, …)}</b>：渲染类型的名字里有 {@code "solid"} /
 * {@code "translucent"} 这种<b>与 {@code ChunkSectionLayer} 层名同字</b>的串，把名字拿去查地形多附件表
 * 就会在有非地形 draw 落进我方 gbuffer pass 的那天，把地形管线塞给不相干的一族。
 * 这里走 {@link PassAwarePipelines#selectForMainPass} —— 只按形状补声明数，不查任何表。
 *
 * <p><b>可关闭</b>：与 M-15 同一个键 {@code mixin.passAwarePipeline}（默认开）；而这一支<b>只有</b>
 * 在主 pass 真被我方接管时才有语义（{@code mixin.ownTerrainPass=false}（默认）⇒
 * {@link MainPassTakeover#isTakenOverPass} 永远 false ⇒ 逐字原样返回，默认档零变化）。
 *
 * <p><b>为什么 {@code require = 1} / {@code expect = 1}</b>：目标 private 方法靠<b>完整描述符</b>选定
 * （{@code draw} 与 {@code drawFromBuffer} 同族重载），改名或签名一变就注入期硬失败，
 * 不许静默不生效（T10 / X11）。
 */
@Mixin(targets = {MixinTargets.PREPARED_RENDER_TYPE}, remap = false)
public abstract class PreparedRenderTypePipelineMixin {

    /**
     * @param requested 原版即将编译的管线（这一条来自 {@code PreparedRenderType} 的 record 分量）
     * @param original  被包住的那次调用本身 —— 判定完必须<b>照常调用</b>它，不吞
     * @param renderPass 所在方法第 2 个形参：这次 draw 落在哪个 pass —— <b>pass 身份的数据来源</b>
     */
    @WrapOperation(method = {MixinTargets.PREPARED_RENDER_TYPE_DRAW},
            at = @At(value = "INVOKE", target = MixinTargets.RENDER_SYSTEM_GET_COMPILED_PIPELINE),
            require = 1, expect = 1)
    private CompiledRenderPipeline vkdisp$padForTakenOverMainPass(
            RenderPipeline requested, Operation<CompiledRenderPipeline> original,
            @Local(argsOnly = true) RenderPass renderPass) {
        // 首行埋点（M1 编码约束 ③）：证明这条通道真的被走到，节流口径同 M-01。
        PassAwarePipelines.onRenderTypeHit();
        return original.call(PassAwarePipelines.selectForMainPass(
                MainPassTakeover.isTakenOverPass(renderPass), requested));
    }
}
