package dev.vkdisp.bridge;
/**
 * 【自行补充】把「我方 gbuffer 池的一张颜色视图 + 深度视图」包成 {@code RenderTarget} 的薄壳。
 *
 * <p>为什么可以这么做（源码级核实，26.3.0.51-beta，零猜测）：
 * ① {@code RenderTarget} 的公开构造器
 *    {@code RenderTarget(@Nullable String, @Nullable GpuFormat, @Nullable GpuFormat)}
 *    **不创建任何纹理**（建纹理的是子类 {@code TextureTarget}）；
 * ② {@code colorTextureView} / {@code depthTextureView} 是 **protected** 字段，
 *    子类可直接赋值 ⇒ 不需要反射、不需要 mixin。
 *
 * <p>用途：原版 {@code SkyRenderer} 的构造器与 {@code render(...)} 都是 public，
 * 且它**自己建 render pass** 并从 {@code renderTarget.getColorTextureView()} 取附件 ——
 * 给它这个壳，天空就直接画进我方 colortex（OF 语义里 {@code gbuffers_skybasic} 的落点），
 * 而不需要 M-04（拿原版主 pass 的所有权）。
 *
 * <p>🔴 两个**必须**封死的方法：{@code resize} 与 {@code destroyBuffers} ——
 * 它们若被原版调用，会去重建/关闭**不属于本对象**的纹理（我方池纹理由
 * {@link ColortexPool} 自己按尺寸管理）。静默让出所有权 = 池被别人关掉 ⇒ 之后每帧崩
 * （h25/h33 那族资源问题的形状）。
 */
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.jspecify.annotations.Nullable;

/** 只当「视图持有者」用的 RenderTarget：不建纹理、不 resize、不销毁。渲染线程独占。 */
final class GbufferTarget extends RenderTarget {

    GbufferTarget(@Nullable String label, GpuTextureView color, @Nullable GpuTextureView depth) {
        super(label, null, null);
        repoint(color, depth);
    }

    /**
     * 🔴 每帧把壳指向**本帧该写的那一代**视图。
     *
     * <p>为什么必须有这个方法（h48i 实测的定位）：GAP-018 的双代轮转下
     * {@code ColortexPool} 的「待写那一代」**每帧交替**，而 {@code SkyRenderer} 每帧都从
     * {@code renderTarget.getColorTextureView()} 现取附件（源码 {@code SkyRenderer:134}）。
     * 只在重建实例时设一次视图 ⇒ 天空会一直画进**第一次看到的那一代**，
     * 地形却写另一代并翻代 ⇒ 链读到「只有地形」，天空每一帧都被丢在没人读的那一代里。
     * 实测形状：{@code c0@afterSky = 0.0000 allZero=true} 而 {@code c0@afterTerrain = 82.72}。
     *
     * <p>🔖 为什么改视图就够、不必重建 {@code SkyRenderer}：它每帧现取视图，不缓存附件。
     */
    void repoint(GpuTextureView color, @Nullable GpuTextureView depth) {
        this.colorTextureView = color;
        this.depthTextureView = depth;
        this.width = color.getWidth(0);
        this.height = color.getHeight(0);
    }

    @Override
    public void resize(int width, int height) {
        // 故意 no-op：尺寸由 ColortexPool 的 ensure() 管（换尺寸时整池重建 + 换视图）。
    }

    @Override
    public void destroyBuffers() {
        // 故意 no-op：本对象不持有这些 GPU 资源的生命周期。
    }
}
