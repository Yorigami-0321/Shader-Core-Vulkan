package dev.vkdisp.mixin;
/**
 * 【参考调研】R7 ⑤-c 第五族（世界边界）：边界力场的取管线那一刻也要补到主 pass 的槽数
 * 0. 合规核对（第 0 步闸门）：参考对象 = 原版 26.3.0.51-beta sources jar
 *    {@code net/minecraft/client/renderer/WorldBorderRenderer}：
 *    {@code public void render(WorldBorderRenderState, RenderPass, Vec3, double)} 第 <b>151</b> 行
 *    （第 158 行是那次 {@code renderPass.setPipeline(RenderSystem.getCompiledPipeline(renderPipeline))}）；
 *    MixinExtras 的 {@code @WrapOperation} / {@code @Local(argsOnly = true)} 公开契约（只观察签名）。
 *    许可证：Mojang EULA（只观察语句与形参表）+ Mixin MIT + NeoForge/MixinExtras LGPL-2.1（只观察契约）
 *    → 能否并入本项目（MIT）：可以；本文件独立编写，零源码搬运
 *    → 例外条款：无；不含 GPL / ARR 代码；参考模组（VulkanMod / Sulkan / Beryl）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：边界自己就是一条 {@code RenderType} 之外的专用管线（{@code RenderPipelines.WORLD_BORDER}），
 *    取管线那一刻同样带着 {@code renderPass}。
 * 2. 备选：连 {@code renderOit}（第 170 行）一起注 —— <b>本步不做</b>：边界<b>没有</b>像云/天气那样的收敛点，
 *    那一句只在开改进透明时才会走到 ⇒ 归 OIT 那一族（少注一处就少一处未取证的注入点）。
 * 3. 我们的差异点：方法体只有「埋点 + 一次 bridge 判定 + 原样交给 original」（X25）；
 *    pass 身份来自形参 {@code renderPass} 的对象比较（不是静态标志）。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：每帧 1 次（且只有镜头进得去边界力场时才有 {@code state.alpha > 0}）⇒ 不构成热点。
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
 * R7 ⑤-c 第五族（登记表：{@code docs/04-SPEC.md} §5.0.7 末的取管线点全集表）：
 * 世界边界力场在主 pass 里取管线那一刻，按 pass 对象身份补到接管后的槽数。
 *
 * <p><b>可关闭</b>：与 M-15 同一键 {@code mixin.passAwarePipeline}；这一支只在主 pass 真被接管时才有语义
 * （{@code mixin.ownTerrainPass=false}（默认）⇒ 逐字原样返回，默认档零变化）。
 */
@Mixin(targets = {MixinTargets.WORLD_BORDER_RENDERER}, remap = false)
public abstract class WorldBorderRendererPipelineMixin {

    /**
     * @param requested 原版即将编译的管线（{@code WORLD_BORDER}）
     * @param original  被包住的那次调用本身 —— 判定完必须照常调用它，不吞
     * @param renderPass 所在方法第 2 个形参：这笔边界落在哪个 pass —— <b>pass 身份的数据来源</b>
     */
    @WrapOperation(method = {MixinTargets.WORLD_BORDER_RENDER},
            at = @At(value = "INVOKE", target = MixinTargets.RENDER_SYSTEM_GET_COMPILED_PIPELINE),
            require = 1, expect = 1)
    private CompiledRenderPipeline vkdisp$padForTakenOverMainPass(
            RenderPipeline requested, Operation<CompiledRenderPipeline> original,
            @Local(argsOnly = true) RenderPass renderPass) {
        // 首行埋点（M1 编码约束 ③）：证明这条通道真的被走到，节流口径同 M-01。
        PassAwarePipelines.onMainPassSiteHit("WorldBorderRenderer#render");
        return original.call(PassAwarePipelines.selectForMainPass(
                MainPassTakeover.isTakenOverPass(renderPass), requested));
    }
}
