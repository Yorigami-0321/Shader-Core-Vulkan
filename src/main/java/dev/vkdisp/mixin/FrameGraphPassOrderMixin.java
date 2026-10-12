package dev.vkdisp.mixin;
/**
 * 【参考调研】R5 / M-13 注入点：包住原版创建帧图 pass 的那两次 {@code addPass}，把 pass 句柄交出去
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① Mixin 0.8.7 的 {@code @Mixin(targets=…)} / {@code @At} 注解签名 +
 *    MixinExtras（NeoForge 自带 0.5.5）的 {@code @WrapOperation} / {@code Operation} **契约**
 *    （只观察注解与接口签名，不读实现）；
 *    ② 原版 {@code LevelRenderer#addSkyPass}（第 369 行，内部第 382 行
 *    {@code FramePass pass = frame.addPass("sky");}）与 {@code #addMainPass}（第 396 行，内部
 *    第 403 行 {@code FramePass pass = frame.addPass("main");}）；
 *    ③ 原版 {@code FrameGraphBuilder#addPass(String)} 的公开签名与 {@code FramePass#requires(FramePass)}。
 *    许可证：Mojang EULA（只观察语句）+ Mixin MIT + NeoForge/MixinExtras LGPL-2.1（只观察契约）。
 *    → 能否并入本项目（MIT）：可以；本文件为独立编写的注入类，零第三方代码复制
 *    → 例外条款：无；不含任何 GPL / ARR 代码
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：{@code @WrapOperation} 包住「返回值」，先 {@code original.call(...)} 拿到
 *    <b>原版那个真对象</b>再登记 —— 不自己 new、不替换实现。
 * 2. 备选：① 自己按 {@code addPass} 的<b>插入序</b>推断执行序 —— **已被真机否证**（h48g：天空排到
 *    地形之后，{@code c0@afterSky} 只剩 0.0611）；② {@code @Inject HEAD} 到 {@code addSkyPass} ——
 *    **否决**（HEAD 时刻 pass 还不存在，拿不到句柄；只有包住 {@code addPass} 的返回值才拿得到）。
 * 3. 我们的差异点：方法体只有「original.call + 一行转发」（X25）。边的加法与双向登记都在
 *    {@code bridge.PassOrder}。⚠️ 两个 {@code @WrapOperation} 各 {@code require=1/expect=1}：
 *    少包一个 = 那条边永远连不上，而症状只是「画面顺序时好时坏」，必须是硬失败。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 装配期每帧 2 次（{@code addSkyPass} / {@code addMainPass} 各一），渲染期零开销。
 */
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.vkdisp.bridge.MixinTargets;
import dev.vkdisp.bridge.PassOrder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * H 线 M-13：把「我方 gbuffer pass 必须排在原版天空之后」从<b>实测反推</b>变成<b>显式声明</b>
 * （登记表：{@code docs/04-SPEC.md} §5.0 的 M-13 行）。
 *
 * <p><b>为什么句柄只能这么拿</b>：{@code FrameGraphSetupEvent}（第 249 行）在我方插自己 pass 的时刻，
 * 原版 {@code "sky"}（382）与 {@code "main"}（403）都还不存在；而 {@code requires} 需要的是
 * <b>对象</b>。包住 {@code addPass} 的返回值 = 在它们诞生的那一刻把句柄接住。
 *
 * <p><b>可关闭</b>：配置键 {@code mixin.pinPassOrder}（默认开）。关档 = 不登记句柄 ⇒
 * 那条 requires 边不再被声明（顺序退回「按插入序猜」= h48g 那个坏形状），
 * 原版自身的 pass 创建<b>不受任何影响</b>（转发后原样返回）。
 */
@Mixin(targets = {MixinTargets.LEVEL_RENDERER}, remap = false)
public abstract class FrameGraphPassOrderMixin {

    /**
     * 包住 {@code LevelRenderer#addSkyPass} 内的 {@code frame.addPass("sky")}（第 382 行）。
     *
     * @param builder  被调用方法的接收者 = 原版那个 {@code FrameGraphBuilder}（本方法不用它）
     * @param name     原版传入的 pass 名（{@code "sky"}）
     * @param original 被包住的那次调用 —— 必须先 {@code call} 才能拿到原版创建的真对象
     */
    @WrapOperation(method = {MixinTargets.LEVEL_RENDERER_ADD_SKY_PASS},
            at = @At(value = "INVOKE", target = MixinTargets.FRAME_GRAPH_BUILDER_ADD_PASS),
            require = 1, expect = 1)
    private FramePass vkdisp$pinSkyPassOrder(FrameGraphBuilder builder, String name,
            Operation<FramePass> original) {
        return PassOrder.onPassDeclared(name, original.call(builder, name));
    }

    /** 包住 {@code LevelRenderer#addMainPass} 内的 {@code frame.addPass("main")}（第 403 行）。 */
    @WrapOperation(method = {MixinTargets.LEVEL_RENDERER_ADD_MAIN_PASS},
            at = @At(value = "INVOKE", target = MixinTargets.FRAME_GRAPH_BUILDER_ADD_PASS),
            require = 1, expect = 1)
    private FramePass vkdisp$pinMainPassOrder(FrameGraphBuilder builder, String name,
            Operation<FramePass> original) {
        return PassOrder.onPassDeclared(name, original.call(builder, name));
    }
}
