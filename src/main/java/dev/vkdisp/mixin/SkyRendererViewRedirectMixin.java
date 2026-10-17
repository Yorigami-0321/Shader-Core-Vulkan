package dev.vkdisp.mixin;
/**
 * 【参考调研】R4 / M-11 {@code @Redirect}：让<b>原版</b>那笔天空画进我方 gbuffer（换视图实参）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 原版 26.3.0.51-beta sources jar：{@code SkyRenderer} 第 132-161 行
 *    （第 134 行 {@code renderTarget.getColorTextureView()}、第 135 行 {@code getDepthTextureView()}，
 *    第 137-139 行用这两个视图自建 "Sky" pass 且都是 LOAD），以及 {@code RenderTarget} 第 129/137 行
 *    （两个 getter 都是 {@code public @Nullable GpuTextureView}）；
 *    ② Mixin 0.8.7 的 {@code @Mixin} / {@code @Redirect} / {@code @At} **注解签名**（不读其实现）；
 *    ③ 本仓库自有的 {@code CloudRendererMatrixRedirectMixin}（M-10：同一形状，换实参不换全局状态）。
 *    许可证：Mixin = MIT；原版 = Mojang EULA（只观察语句与签名）。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以；本文件为独立编写的注入类
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：NeoForge 给包天空的官方钩子（{@code LevelRenderer:385}）负责「包接管」那一档，
 *    本类负责「原版那一笔」那一档 —— 两档都在原版 {@code "sky"} pass 体内，门控都由原版判。
 * 2. 备选：① <b>旧做法</b>：自己 {@code new SkyRenderer(..., new GbufferTarget(...))} 并每帧
 *    {@code repoint} —— <b>否决</b>：多一份 {@code SkyRenderer}（含 8 张顶点缓冲）与一份
 *    {@code SkyRenderState} 要自己 {@code extractRenderState}，而 {@code GbufferTarget} 那个
 *    {@code RenderTarget} 薄壳根本不拥有纹理却要封死 {@code resize}/{@code destroyBuffers}；
 *    ② 注 {@code createRenderPass} 换整个描述符 —— <b>否决</b>：要复制原版的 pass 描述，复制量大且
 *    把「LOAD 语义」这一条从原版手里抢过来（改原版行为而不是喂它参数）。
 * 3. 我们的差异点：只换<b>两个实参</b>，原版的 pass、几何、管线、状态机全部照旧 ⇒ 天空的画质与
 *    原版逐字相同（星点那一族判据不因换代而回退），而 {@code GbufferTarget} 整类从仓库里消失。
 * 4. 许可证核对：本项目 LGPL-3.0-or-later；零第三方代码复制。
 * 5. 性能基线：每帧两次方法替换（原本就是两次 getter 调用），零新增分配 —— 我方那两个视图是
 *    {@code ColortexPool} 里已有的对象，不是每帧新建。
 */
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.vkdisp.bridge.MixinTargets;
import dev.vkdisp.bridge.SkyIntoGbuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * H 线 M-11：原版天空取附件视图的那两次调用交给我方 gbuffer（登记表：{@code 04-SPEC} §5.0 M-11 行）。
 *
 * <p><b>为什么这两处 {@code @Redirect} 对原版是恒等变换的形状</b>（源码级核实，2026-10-12）：
 * {@code SkyRenderer} 里 {@code renderTarget} 字段<b>只</b>在第 134/135 行被读（第 66 行声明，
 * 全文再无其它用处）⇒ 换掉这两个返回值就等于换掉了它画到哪儿，其它一切（穹顶几何、五管线、
 * 深度语义）逐字不变。而 {@code render} 全仓库只有一个调用点（{@code LevelRenderer:386}，
 * 在原版四道门控之后的 {@code "sky"} pass 体内）⇒ 不存在「别处也调它」的第二种语义。
 *
 * <p><b>可关闭</b>：配置键 {@code mixin.skyViews}（默认开）。
 * ⚠️ 关档 = 原版天空回到<b>主目标</b>，而我方 colortex0 的槽 0 由地形按 CLEAR 打开 ⇒ 画面回到
 * 「天空不进 gbuffer」那一档（链读 colortex0 就是黑底）。这是「关掉即回到坏行为」那一族开关。
 *
 * <p><b>为什么 {@code require = 1} / {@code expect = 1}</b>：两条各自只命中一处，改名或换签名要
 * 当场硬失败，不许静默不生效（T10 / X11）。
 */
@Mixin(targets = {MixinTargets.SKY_RENDERER}, remap = false)
public abstract class SkyRendererViewRedirectMixin {

    /**
     * 替换第 134 行那次 {@code renderTarget.getColorTextureView()}。
     *
     * @param vanillaTarget 原版那个 {@code RenderTarget}（{@code gameRenderer.mainRenderTarget()}；
     *                      关档时向它回退，取它自己的颜色视图）
     * @return 交给原版 "Sky" pass 的颜色附件视图
     */
    @Redirect(method = {MixinTargets.SKY_RENDERER_RENDER},
            at = @At(value = "INVOKE", target = MixinTargets.RENDER_TARGET_GET_COLOR_TEXTURE_VIEW),
            require = 1, expect = 1)
    private GpuTextureView vkdisp$skyColorView(RenderTarget vanillaTarget) {
        return SkyIntoGbuffer.onSkyColorViewRequest(vanillaTarget);
    }

    /**
     * 替换第 135 行那次 {@code renderTarget.getDepthTextureView()}。
     *
     * @param vanillaTarget 原版那个 {@code RenderTarget}（同上）
     * @return 交给原版 "Sky" pass 的深度附件视图
     */
    @Redirect(method = {MixinTargets.SKY_RENDERER_RENDER},
            at = @At(value = "INVOKE", target = MixinTargets.RENDER_TARGET_GET_DEPTH_TEXTURE_VIEW),
            require = 1, expect = 1)
    private GpuTextureView vkdisp$skyDepthView(RenderTarget vanillaTarget) {
        return SkyIntoGbuffer.onSkyDepthViewRequest(vanillaTarget);
    }
}
