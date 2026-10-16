package dev.vkdisp.mixin;
/**
 * 【自行补充】GAP-027 穹顶那笔 draw 的<b>换管线 + 绑块</b>（M-1x，登记表：04-SPEC §5.0）
 * 0. 合规核对：参考对象 = 本仓库自有 {@code SkyRendererCelestialPipelineMixin}（同一个宿主类 {@code SkyRenderer}、
 *    同一条 {@code @WrapOperation} 注 {@code setPipeline} + 首行埋点 + 先备料再换、失败原样交回、{@code require=1/expect=1}）。
 *    原版侧只观察 {@code SkyRenderer#renderSkyDisc} 那次 {@code setPipeline}（{@code SkyRenderer.java:167}）。
 *    许可证：Mojang EULA（只观察签名）+ Mixin MIT；本文件独立编写，零源码搬运；参考模组零接触。
 * 1. 官方/主实现：官方管线替换通道拿不到 {@code RenderPass} 句柄 ⇒ 注这一次调用。
 * 2. 备选：自开 pass —— 否决（几何/姿态由原版驱动，见 {@code bridge/SkyGeometryPack} 类注释）。
 * 3. 我们的差异点：本体只做三件事 —— 判该不该换（{@link SkyGeometryPack#enabledFor}）、先备料（失败原样交回）、
 *    {@code original.call(派生管线)} 之后交给 bridge 绑块。判定与绑定全在 bridge，本类不含业务（M1 ③）。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：穹顶每帧一次，零分配路径外一律在 bridge 缓存。
 */
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import dev.vkdisp.bridge.MixinTargets;
import dev.vkdisp.bridge.SkyGeometryPack;
import dev.vkdisp.bridge.SkyGeometrySite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 原版穹顶那笔 draw 换用包的 {@code gbuffers_skybasic}（默认关：闸门见 {@code SkyGeometryPack#enabledFor}）。 */
@Mixin(targets = {MixinTargets.SKY_RENDERER}, remap = false)
public abstract class SkyRendererGeometryPipelineMixin {

    /**
     * @param requested 原版即将绑上去的管线（{@code RenderPipelines.SKY}）
     * @param self      被调的那个 {@code RenderPass}（绑块要用它，故注在这次调用上）
     * @param original  被包住的那次调用本身 —— 判定不成立时必须照常调用，不吞
     */
    @WrapOperation(method = {MixinTargets.SKY_RENDERER_SKY_DISC},
            at = @At(value = "INVOKE", target = MixinTargets.RENDER_PASS_SET_PIPELINE),
            require = 1, expect = 1)
    private void vkdisp$drawSkyDiscWithPackShader(CompiledRenderPipeline requested, RenderPass self,
            Operation<Void> original) {
        SkyGeometrySite site = SkyGeometryPack.onSite(SkyGeometrySite.SKY_DISC); // M1 ③：首行埋点转发
        if (!SkyGeometryPack.enabledFor(site)) {
            original.call(requested);
            return;
        }
        CompiledRenderPipeline pack = SkyGeometryPack.compiled(site);
        net.minecraft.client.renderer.MappableRingBuffer ring =
                pack == null ? null : SkyGeometryPack.prepare(site);
        if (ring == null) {
            // 🔴 备料没成就原样交回：换了管线却绑不上块 = validateDraw 抛，跑在帧图 pass 体内 = 客户端死。
            original.call(requested);
            return;
        }
        original.call(pack);
        SkyGeometryPack.apply(self, site);
    }

    /** 星点那笔（原版 {@code SkyRenderer:274} 那次 {@code setPipeline}）换用包的 {@code gbuffers_skybasic}。 */
    @WrapOperation(method = {MixinTargets.SKY_RENDERER_STARS},
            at = @At(value = "INVOKE", target = MixinTargets.RENDER_PASS_SET_PIPELINE),
            require = 1, expect = 1)
    private void vkdisp$drawStarsWithPackShader(CompiledRenderPipeline requested, RenderPass self,
            Operation<Void> original) {
        SkyGeometrySite site = SkyGeometryPack.onSite(SkyGeometrySite.STARS); // M1 ③：首行埋点转发
        if (!SkyGeometryPack.enabledFor(site)) {
            original.call(requested);
            return;
        }
        CompiledRenderPipeline pack = SkyGeometryPack.compiled(site);
        net.minecraft.client.renderer.MappableRingBuffer ring =
                pack == null ? null : SkyGeometryPack.prepare(site);
        if (ring == null) {
            original.call(requested);
            return;
        }
        original.call(pack);
        SkyGeometryPack.apply(self, site);
    }

    /** 晨昏那笔（原版 {@code SkyRenderer:298} 那次 {@code setPipeline}）换用包的 {@code gbuffers_skybasic}。 */
    @WrapOperation(method = {MixinTargets.SKY_RENDERER_SUNRISE_SUNSET},
            at = @At(value = "INVOKE", target = MixinTargets.RENDER_PASS_SET_PIPELINE),
            require = 1, expect = 1)
    private void vkdisp$drawSunriseWithPackShader(CompiledRenderPipeline requested, RenderPass self,
            Operation<Void> original) {
        SkyGeometrySite site = SkyGeometryPack.onSite(SkyGeometrySite.SUNRISE_SUNSET); // M1 ③：首行埋点转发
        if (!SkyGeometryPack.enabledFor(site)) {
            original.call(requested);
            return;
        }
        CompiledRenderPipeline pack = SkyGeometryPack.compiled(site);
        net.minecraft.client.renderer.MappableRingBuffer ring =
                pack == null ? null : SkyGeometryPack.prepare(site);
        if (ring == null) {
            original.call(requested);
            return;
        }
        original.call(pack);
        SkyGeometryPack.apply(self, site);
    }
}
