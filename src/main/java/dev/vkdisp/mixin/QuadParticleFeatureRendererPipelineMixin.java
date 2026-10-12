package dev.vkdisp.mixin;
/**
 * 【参考调研】R7 ⑤-c 第二族：四边形粒子的取管线点也要补到主 pass 的槽数
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 原版 26.3.0.51-beta sources jar
 *    {@code net/minecraft/client/renderer/feature/QuadParticleFeatureRenderer}：
 *    {@code private static void drawLayers(StagedVertexBuffer, PreparedGroup, RenderPass, OitStage)}
 *    第 <b>89</b> 行的
 *    {@code renderPass.setPipeline(RenderSystem.getCompiledPipeline(stage != null ? getOitPipeline(stage, entry.getKey()) : entry.getKey().pipeline()))}；
 *    ② MixinExtras 的 {@code @WrapOperation} / {@code @Local(argsOnly = true)} 公开契约（不读实现）。
 *    许可证：Mojang EULA（只观察语句与形参表）+ Mixin MIT + NeoForge/MixinExtras LGPL-2.1（只观察契约）
 *    → 能否并入本项目（MIT）：可以；本文件独立编写，零源码搬运
 *    → 例外条款：无；不含任何 GPL / ARR 代码
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：与 ⑤-c 第一族同一形状（{@code renderPass.setPipeline(getCompiledPipeline(X))}），
 *    差别只在粒子自己决定用哪条管线（{@code SingleQuadParticle.Layer#pipeline()} 或 OIT 变体）。
 * 2. 备选：把它也纳入 {@code PreparedRenderType} 那一处 —— <b>不存在这条路</b>：粒子根本不经过
 *    {@code PreparedRenderType#draw}（本轮数出来的 34 处取管线点里它是独立一处，见 {@code 04-SPEC}
 *    §5.0.7 末的新表）。
 * 3. 我们的差异点：方法体只有「埋点 + 一次 bridge 判定 + 原样把结果交给 original」（X25）。
 *    pass 身份来自<b>形参</b> {@code renderPass} 的对象比较（不是静态标志，{@code GAP-040} 那一族）。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：🔴 每帧每层粒子 1 次（本机量级：每帧 1–2 个 layer）。handler 一次对象引用比较 +
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
 * R7 ⑤-c 第二族（登记表：{@code docs/04-SPEC.md} §5.0.7 表 ⑤ 行 c 与末的新表）：
 * 四边形粒子在主 pass 里取管线那一刻，按 pass 身份补到接管后的槽数。
 *
 * <p><b>它是被真机指出来的</b>：⑤-c 第一族签掉后，同一个 {@code setPipeline} 抛挪到了
 * {@code QuadParticleFeatureRenderer.drawLayers:89} ← {@code executeGroup:79} ←
 * {@code FeatureRenderDispatcher$PreparedGroup.execute:394} ← {@code LevelRenderer.executeSolid:538}
 * —— 逐族放开的顺序就按这个来，不靠猜。
 *
 * <p><b>可关闭</b>：与 M-15 同一键 {@code mixin.passAwarePipeline}；且这一支只在主 pass 真被接管时
 * 才有语义（{@code mixin.ownTerrainPass=false}（默认）⇒ 逐字原样返回，默认档零变化）。
 *
 * <p>🔴 <b>handler 必须是 {@code static}</b>：目标 {@code drawLayers} 是 {@code private static} ——
 * 非静态 handler 包静态方法会在<b>类加载期</b>硬失败
 * （{@code InvalidInjectionException: non-static callback method … targets a static method which is not supported}，
 * 实测栈顶 {@code FeatureRenderDispatcher.<init>:56} ← {@code GameRenderer.<init>:164}），
 * 而那次失败<b>与开关无关</b> ⇒ 默认档也起不来。这是 {@code GAP-047} 那一族的第五条形状。
 */
@Mixin(targets = {MixinTargets.QUAD_PARTICLE_FEATURE_RENDERER}, remap = false)
public abstract class QuadParticleFeatureRendererPipelineMixin {

    /**
     * @param requested 原版即将编译的管线（粒子层自己的管线或它的 OIT 变体）
     * @param original  被包住的那次调用本身 —— 判定完必须<b>照常调用</b>它，不吞
     * @param renderPass 所在方法第 3 个形参：这批粒子落在哪个 pass —— <b>pass 身份的数据来源</b>
     */
    @WrapOperation(method = {MixinTargets.QUAD_PARTICLE_DRAW_LAYERS},
            at = @At(value = "INVOKE", target = MixinTargets.RENDER_SYSTEM_GET_COMPILED_PIPELINE),
            require = 1, expect = 1)
    private static CompiledRenderPipeline vkdisp$padForTakenOverMainPass(
            RenderPipeline requested, Operation<CompiledRenderPipeline> original,
            @Local(argsOnly = true) RenderPass renderPass) {
        // 首行埋点（M1 编码约束 ③）：证明这条通道真的被走到，节流口径同 M-01。
        PassAwarePipelines.onMainPassSiteHit("QuadParticle#drawLayers");
        return original.call(PassAwarePipelines.selectForMainPass(
                MainPassTakeover.isTakenOverPass(renderPass), requested));
    }
}
