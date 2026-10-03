package dev.vkdisp.mixin;
/**
 * 【参考调研】H 线 M-05 注入点（只读捕获地形 draw 数据）/ Mixin 官方注解语义 + 原版 {@code LevelRenderer#prepareChunkRenders*}
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① Mixin 0.8.7（NeoForge 26.3 传递依赖）的 {@code @Mixin(targets=…)} / {@code @Inject} /
 *    {@code @At} / {@code CallbackInfoReturnable} 注解签名（javap 核实常量池，不读实现）；
 *    ② 原版 {@code LevelRenderer#prepareChunkRenders} 与 {@code #prepareChunkRendersIndirect} 的签名与调用时机
 *    （随 MDG 分发的 {@code minecraft-patched-26.3.0.41-beta-sources.jar}）。
 *    许可证：Mixin = MIT；原版 = Mojang EULA（只观察方法签名与语句顺序）。
 *    → 能否并入本项目（MIT）：可以 —— 同族可并入；本文件为独立编写的注入类
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：Mixin 官方范式 {@code @Inject(method=…, at=@At("RETURN"))} +
 *    {@code CallbackInfoReturnable#getReturnValue()}。
 * 2. 备选：① 在 {@code LevelRenderer#addMainPass}（M-04）里做 —— 否决（那是**方案 B**，
 *    要改原版 pass 的附件语义；而本注入点是**只读捕获**，不碰任何渲染行为）；
 *    ② 在 {@code FrameGraphSetupEvent} 里拿 —— **已源码级证伪**：
 *    {@code fireFrameGraphSetup} 在 {@code render} 第 249 行，而 {@code prepareChunkRenders*}
 *    在第 271-275 行才调用 ⇒ 事件触发时该对象**尚不存在**（X9 不猜，此处是读源码得出的事实）。
 * 3. 我们的差异点：**只转发、只捕获，不改任何行为**（X25）。方法体只有
 *    ① 埋点 ② 把返回值交给 {@code bridge.TerrainDrawCapture} 保存。
 *    🔖 与 M-01/M-01b 的本质区别：那两个**改变**渲染行为（换管线 / 加 uniform），
 *    本注入点**不改变任何东西** —— 关闭它，地形渲染与原版完全一致。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：热路径但极轻（每帧 1 次引用赋值 + 首次埋点）；不做性能优化。
 */
import dev.vkdisp.bridge.MixinTargets;
import dev.vkdisp.bridge.TerrainDrawCapture;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * H 线 M-05：**只读捕获**地形 draw 数据对象（登记表：{@code docs/04-SPEC.md} §5.0 的 M-05 行）。
 *
 * <p><b>它解决的问题</b>：GAP-003 方案 A 要在我方自己的多附件 pass 里画地形，
 * 就必须拿到 {@code ChunkSectionsToRender}（它持有各层的顶点/索引缓冲与 draw 列表）。
 * 官方 {@code FrameGraphSetupEvent} 给不了（时序早于该对象的创建），所以在这里捕获引用。
 *
 * <p><b>它不做什么</b>：不取消、不修改、不替换任何原版返回值。
 * {@code cancellable} 刻意为 {@code false} —— 本注入点的正确性判据就是
 * 「原版地形照旧渲染」，任何「改动」都是 bug。
 *
 * <p><b>可关闭</b>：配置键 {@code mixin.captureTerrainDraws}（默认开）。
 * 关闭后本注入点不保存引用 ⇒ 我方多附件 pass 拿不到地形数据、保持静默不开
 * （不报错，因为「未启用」不是失败）。
 *
 * <p><b>为什么必须同时注入两个方法</b>：{@code prepareChunkRenders} 与
 * {@code prepareChunkRendersIndirect} 是二选一的关系，由
 * {@code usingMultiDrawIndirectForTerrain}（设备能力 × 关卡设置）决定
 * （原版第 269-275 行）。本机 lavapipe 走的是 <b>indirect</b> 分支 ——
 * 漏注入 indirect 就捕获不到（这正是为什么两个都要，且都有单测守着）。
 */
@Mixin(targets = {MixinTargets.LEVEL_RENDERER}, remap = false)
public abstract class LevelRendererChunkCaptureMixin {

    /**
     * 非多重绘制分支（{@code prepareChunkRenders}）。
     *
     * @param cir 原版返回值通道；只读，**不调 {@code setReturnValue}**
     */
    @Inject(method = "prepareChunkRenders", at = @At("RETURN"), require = 1, expect = 1)
    private void vkdisp$captureTerrainDraws(org.joml.Matrix4fc modelViewMatrix,
            boolean respectTranslucentOrder, CallbackInfoReturnable<ChunkSectionsToRender> cir) {
        TerrainDrawCapture.onCaptured("prepareChunkRenders", cir.getReturnValue());
    }

    /**
     * 多重绘制分支（{@code prepareChunkRendersIndirect}）—— 本机 lavapipe 实际走这条。
     *
     * @param cir 原版返回值通道；只读，**不调 {@code setReturnValue}**
     */
    @Inject(method = "prepareChunkRendersIndirect", at = @At("RETURN"), require = 1, expect = 1)
    private void vkdisp$captureTerrainDrawsIndirect(org.joml.Matrix4fc modelViewMatrix,
            boolean respectTranslucentOrder, CallbackInfoReturnable<ChunkSectionsToRender> cir) {
        TerrainDrawCapture.onCaptured("prepareChunkRendersIndirect", cir.getReturnValue());
    }
}