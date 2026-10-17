package dev.vkdisp.mixin;
/**
 * 【参考调研】R7 ⑤-c 第三族（云）：云的取管线那一刻也要补到主 pass 的槽数
 * 0. 合规核对（第 0 步闸门）：参考对象 = 原版 26.3.0.51-beta sources jar
 *    {@code net/minecraft/client/renderer/CloudRenderer}：{@code private void render(RenderPass, RenderPipeline)}
 *    第 <b>225</b> 行（第 229 行是那次 {@code renderPass.setPipeline(RenderSystem.getCompiledPipeline(renderPipeline))}），
 *    以及调用它的两处：{@code render(CloudStatus, RenderPass)} 第 203 行（经典）与
 *    {@code renderOit(…)} 第 223 行（OIT）；MixinExtras 的 {@code @WrapOperation} /
 *    {@code @Local(argsOnly = true)} 公开契约（只观察签名）。
 *    许可证：Mojang EULA（只观察语句与形参表）+ Mixin MIT + NeoForge/MixinExtras LGPL-2.1（只观察契约）
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以；本文件独立编写，零源码搬运
 *    → 例外条款：无；不含 GPL / ARR 代码；参考模组（VulkanMod / Sulkan / Beryl）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：原版把 {@code RenderPass} 与要用的管线一起交给这个私有方法 ⇒ 身份与入参都在手边。
 * 2. 备选：注公开的 {@code render(CloudStatus, RenderPass)} —— <b>否决</b>：它拿不到管线（管线在它内部按
 *    {@code CloudStatus} 选），而且 OIT 那条路径不经过它 ⇒ 收敛点只有私有这一条。
 * 3. 我们的差异点：方法体只有「埋点 + 一次 bridge 判定 + 原样交给 original」（X25）；
 *    pass 身份来自形参 {@code renderPass} 的对象比较（不是静态标志，{@code GAP-040} 那一族）。
 * 4. 许可证核对：本项目 LGPL-3.0-or-later；零第三方代码复制。
 * 5. 性能基线：每帧 1 次（云是整屏一次 draw）⇒ 不构成热点；埋点按 M-01 节流口径。
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
 * R7 ⑤-c 第三族（登记表：{@code docs/04-SPEC.md} §5.0.7 末的取管线点全集表）：
 * 云在主 pass 里取管线那一刻，按 pass 对象身份补到接管后的槽数。
 *
 * <p><b>一个注入点覆盖两条路径</b>：经典云与 OIT 云都收敛到 {@code CloudRenderer#render(RenderPass, RenderPipeline)}。
 * 而 {@code renderOit} 里那句 {@code BLIT_DEPTH_DURING_DEPTH_BOUNDS}（第 215 行）不在收敛点上，
 * 且只在开改进透明时才会走到 ⇒ 归 OIT 那一族处理。
 *
 * <p><b>可关闭</b>：与 M-15 同一键 {@code mixin.passAwarePipeline}；这一支只在主 pass 真被接管时才有语义
 * （{@code mixin.ownTerrainPass=false}（默认）⇒ 逐字原样返回，默认档零变化）。
 */
@Mixin(targets = {MixinTargets.CLOUD_RENDERER}, remap = false)
public abstract class CloudRendererPipelineMixin {

    /**
     * @param requested 原版即将编译的管线（{@code CLOUDS} / {@code FLAT_CLOUDS} 或它们的 OIT 变体）
     * @param original  被包住的那次调用本身 —— 判定完必须照常调用它，不吞
     * @param renderPass 所在方法第 1 个形参：这批云落在哪个 pass —— <b>pass 身份的数据来源</b>
     */
    @WrapOperation(method = {MixinTargets.CLOUD_RENDER_INNER},
            at = @At(value = "INVOKE", target = MixinTargets.RENDER_SYSTEM_GET_COMPILED_PIPELINE),
            require = 1, expect = 1)
    private CompiledRenderPipeline vkdisp$padForTakenOverMainPass(
            RenderPipeline requested, Operation<CompiledRenderPipeline> original,
            @Local(argsOnly = true) RenderPass renderPass) {
        // 首行埋点（M1 编码约束 ③）：证明这条通道真的被走到，节流口径同 M-01。
        PassAwarePipelines.onMainPassSiteHit("CloudRenderer#render");
        return original.call(PassAwarePipelines.selectForMainPass(
                MainPassTakeover.isTakenOverPass(renderPass), requested));
    }
}
