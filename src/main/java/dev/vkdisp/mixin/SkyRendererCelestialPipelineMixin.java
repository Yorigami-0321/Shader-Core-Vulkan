package dev.vkdisp.mixin;
/**
 * 【自行补充】GAP-027 日月那笔 draw 的<b>换管线 + 补绑</b>（M-1x，登记表：04-SPEC §5.0）
 * 0. 合规核对：参考对象 = 本仓库自有 {@code WeatherEffectRendererPipelineMixin}
 *    （{@code @WrapOperation} + {@code @Local(argsOnly = true)} 取形参 + 首行埋点 + `require=1/expect=1`）
 *    与 {@code SkyRendererViewRedirectMixin}（同一个宿主类，M-11）。原版侧只观察
 *    {@code SkyRenderer#drawCelestialBody} 的形参表与那一次 {@code setPipeline}
 *    （{@code SkyRenderer.java:252-263}）。许可证：Mojang EULA（只观察签名）+ Mixin MIT；
 *    本文件独立编写，零源码搬运；参考模组（VulkanMod/Sulkan/Beryl）零接触（07-CONSTRAINTS L12）。
 * 1. 官方/主实现：官方管线替换通道（{@code PIPELINE_MODIFIERS}）拿不到 {@code RenderPass} 句柄，
 *    而本格必须在换管线之后补绑我方 uniform 块 ⇒ 只能注这一次调用。
 * 2. 备选：modifier 通道 / 自开 pass —— 都否决，逐字理由见 {@code bridge/SkyCelestialPack} 类注释第 2 条。
 * 3. 我们的差异点：本体只做三件事 —— 判「该不该换」（含<b>按 label 排除末地闪烁</b>）、
 *    先备料（失败就原样交回，绝不半接）、{@code original.call(self, 派生管线)} 之后交给 bridge 补绑。
 *    判定与绑定全在 bridge，本类不含业务（M1 编码约束 ③）。
 * 4. 许可证核对：本项目 LGPL-3.0-or-later；零第三方代码复制。
 * 5. 性能基线：每帧两次（日、月各一次），零分配路径外一律在 bridge 里缓存。
 */
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import dev.vkdisp.bridge.MixinTargets;
import dev.vkdisp.bridge.SkyCelestialPack;
import java.util.function.Supplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 原版日月那笔 draw 换用包的 {@code gbuffers_skytextured}（可关：{@code mixin.skyCelestialPack}）。 */
@Mixin(targets = {MixinTargets.SKY_RENDERER}, remap = false)
public abstract class SkyRendererCelestialPipelineMixin {

    /**
     * @param self      被调的那个 {@code RenderPass}（补绑要用它；实例方法的<b>接收者必须在最前</b>，
     *                  写反 ⇒ MixinExtras APPLY 期判 invalid signature ⇒ 类转换失败 = 客户端崩）
     * @param requested 原版即将绑上去的管线（{@code RenderPipelines.CELESTIAL}）
     * @param original  被包住的那次调用本身 —— 判定不成立时必须照常调用，不吞
     * @param label     宿主方法第 1 个形参：{@code "Sun"} / {@code "Moon"} / {@code "End flash"}
     */
    @WrapOperation(method = {MixinTargets.SKY_RENDERER_DRAW_CELESTIAL_BODY},
            at = @At(value = "INVOKE", target = MixinTargets.RENDER_PASS_SET_PIPELINE),
            require = 1, expect = 1)
    private void vkdisp$drawCelestialsWithPackShader(RenderPass self, CompiledRenderPipeline requested,
            Operation<Void> original, @Local(argsOnly = true) Supplier<String> label) {
        String which = SkyCelestialPack.onCelestialSite(label.get()); // M1 ③：首行埋点转发
        if (!SkyCelestialPack.enabledFor(which)) {
            original.call(self, requested);
            return;
        }
        CompiledRenderPipeline pack = SkyCelestialPack.compiled();
        SkyCelestialPack.Pending pending = pack == null ? null : SkyCelestialPack.prepare(which);
        if (pending == null) {
            // 🔴 备料没成就原样交回：换了管线却绑不上 uniform 是 validateDraw 抛，
            //    而这跑在帧图 pass 体内、无人接 ⇒ 客户端直接死（GAP-036 ④ 那条教训的形状）。
            original.call(self, requested);
            return;
        }
        original.call(self, pack);
        SkyCelestialPack.apply(self, pending);
    }
}
