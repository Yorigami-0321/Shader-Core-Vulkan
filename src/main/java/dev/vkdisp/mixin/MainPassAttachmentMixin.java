package dev.vkdisp.mixin;
/**
 * 【参考调研】R7 / M-04 第 ⑤-a 步的注入点：包住 frontend 建描述符后那一次「交后端」的调用
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 原版 26.3.0.51-beta sources jar：{@code com/mojang/renderpearl/frontend/
 *    FrontendCommandEncoder.java} 第 <b>80-96</b> 行那个 6 参重载（第 89 行自己
 *    {@code RenderPassDescriptor.builder(label).withColorAttachment(...)}、第 94 行
 *    {@code descriptor.withRenderArea(renderArea)}、第 <b>95</b> 行
 *    {@code return this.createRenderPass(descriptor.build())}），以及第 67-78 行两条便利重载
 *    各自 {@code return this.createRenderPass(…)} 并到它（实测原文）；
 *    ② {@code LevelRenderer} 第 <b>455-463</b> 行（原版主 pass 就是走 5 参重载进来的）；
 *    ③ MixinExtras（NeoForge 自带 0.5.5）的 {@code @WrapOperation} / {@code Operation} <b>契约</b>
 *    （只观察注解与接口签名，不读实现）。
 *    许可证：Mojang EULA（只观察语句）+ Mixin MIT + NeoForge/MixinExtras LGPL-2.1（只观察契约）。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以；本文件为独立编写的注入类，零第三方代码复制
 *    → 例外条款：无；不含任何 GPL / ARR 代码（参考模组零接触，07-CONSTRAINTS L12）
 * 1. 官方/主实现：把 {@code Operation} 当成「这一次调用」的一等公民交给我方 bridge，由它决定
 *    传哪条描述符 —— 我们<b>不</b>接管 render pass 的生命周期、不自己 new {@code RenderPass}。
 * 2. 备选：① 注 {@code LevelRenderer#addMainPass} 里那次调用 —— <b>做不到</b>（那行在
 *    {@code pass.executes} 的 lambda 内 ⇒ 只能瞄合成方法 {@code lambda$addMainPass$N}）；
 *    ② 注 {@code VulkanCommandEncoder} —— <b>否决</b>（绑后端，X7 +「不要动：后端分叉」）；
 *    ③ 注 {@code FrontendRenderPass} 的 {@code setPipeline} —— <b>否决</b>（那是「校验」而不是「选 pass」，
 *    在这里改等于绕过附件校验做 MRT，GAP-046 的账就再也数不清了）。
 * 3. 我们的差异点：方法体只有「一次转发 + 一次 original.call」（X25），判定与重建都在
 *    {@code bridge.MainPassTakeover}。⚠️ {@code require=1/expect=1}：这个点没包上的症状是
 *    「主 pass 一直是单附件、包读不到地形之外的那几槽」= 与今天逐字节相同 ⇒ 必须硬失败。
 * 4. 许可证核对：本项目 LGPL-3.0-or-later；零第三方代码复制。
 * 5. 性能基线：每次建单颜色附件的 pass 多一次转发（判定是三次比较）；开关关着时只多读一次布尔。
 */
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import dev.vkdisp.bridge.MainPassTakeover;
import dev.vkdisp.bridge.MixinTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * H 线 M-04 ⑤-a：把原版主 GPU pass 的颜色附件换成我方 colortex 阵列
 * （登记表：{@code docs/04-SPEC.md} §5.0 的 M-04 行 + 执行序 §5.0.7）。
 *
 * <p><b>可关闭</b>：配置键 {@code mixin.ownTerrainPass}，🔴 默认<b>关</b> —— 本步（⑤-a）故意只换附件、
 * 不派生管线，开着会在 {@code FrontendRenderPass#setPipeline} 当场抛「附件数不匹配」；
 * 那句抛就是「注入确实命中主 pass」的证据（§5.0.7 ⑤-a 的原文）。⑤-b 接上通用派生公式之前，
 * 这条开关不许进任何取证档。关档 = 描述符原样交回 = 与今天逐字节相同。
 */
@Mixin(targets = {MixinTargets.FRONTEND_COMMAND_ENCODER}, remap = false)
public abstract class MainPassAttachmentMixin {

    /**
     * 包住 6 参重载内部第 95 行那一次 {@code createRenderPass(descriptor)}。
     *
     * <p>⚠️ {@code Operation.call(…)} 的实参表 = <b>接收者 + 被包住方法的全部实参</b>（MixinExtras 0.5.5
     * 在运行期按条数硬校验：{@code IncorrectArgumentCountException: Expected 2 but got 1}，实测踩过）。
     * 所以这里必须把 {@code encoder} 一起交回去 —— bridge 只见得到描述符，绑接收者这件事留在注入类里做。
     *
     * @param encoder    被调用方法的接收者（= {@code FrontendCommandEncoder} 自己）
     * @param descriptor 原版刚造好的那条描述符（单颜色附件 + 可选深度 + renderArea）
     * @param original   被包住的那次调用
     */
    @WrapOperation(method = {MixinTargets.FRONTEND_ENCODER_CREATE_PASS_SIX},
            at = @At(value = "INVOKE", target = MixinTargets.FRONTEND_ENCODER_CREATE_PASS_DESCRIPTOR),
            require = 1, expect = 1)
    private RenderPass vkdisp$takeOverMainPassAttachments(FrontendCommandEncoder encoder,
            RenderPassDescriptor descriptor, Operation<RenderPass> original) {
        return MainPassTakeover.onCreateRenderPass(descriptor,
                replaced -> original.call(encoder, replaced));
    }
}
