package dev.vkdisp.mixin;
/**
 * 【参考调研】R2 / M-06 注入点（只读捕获地形 draw 与雾切片）/ Mixin 官方注解语义 + 原版 {@code LevelRenderer#addMainPass}
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① Mixin 0.8.7（NeoForge 26.3 传递依赖 {@code net.fabricmc:sponge-mixin}）的
 *    {@code @Mixin(targets=…)} / {@code @Inject} / {@code @At} / {@code CallbackInfo}
 *    **注解签名**（javap 核实常量池里的类名与描述符，不读其实现）；
 *    ② 原版 {@code LevelRenderer#addMainPass} 的签名与调用时机
 *    （随 MDG 分发的 {@code minecraft-patched-*-sources.jar}：方法体第 396-402 行，
 *    唯一调用点在第 **277** 行且<b>无条件</b>执行）。
 *    许可证：Mixin = MIT（FabricMC 官方 LICENSE）；原版 = Mojang EULA（只观察方法签名与语句顺序）。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以 —— 同族可并入；且本文件不含任何被参考方代码
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：Mixin 官方范式 {@code @Inject(method=…, at=@At("HEAD"))} +
 *    形参与原版<b>逐个对齐</b>（private 方法一样可注，只要名字与描述符对得上）。
 * 2. 备选：① 旧 M-05 —— 注 {@code prepareChunkRenders*} 两个 {@code @At("RETURN")}
 *    （{@code CallbackInfoReturnable#getReturnValue}）。**已被本点替代并删除**：两个注入点只换来
 *    一个引用，而本点一次就拿齐 {@code terrainFog} 与 {@code ChunkSectionsToRender}
 *    （后者正是「两支在第 277 行汇成一点」的红利）；
 *    ② {@code FrameGraphSetupEvent} —— **已源码级证伪**：事件在第 249 行触发，
 *    {@code chunkSectionsToRender} 在第 271-275 行才创建 ⇒ 事件实参里没有它（X9 不猜，读源码得出）。
 * 3. 我们的差异点：**只转发、只捕获，不改任何行为**（X25）。方法体只有一句转发。
 *    🔖 与被它替代的 M-05 相同：关闭它，地形渲染与原版<b>逐字一致</b>。
 * 4. 许可证核对：本项目 LGPL-3.0-or-later；零第三方代码复制。
 * 5. 性能基线：热路径但极轻（每帧 1 次 HEAD 命中 + 两次引用赋值，无分配）；不做性能优化。
 */
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.vkdisp.bridge.MixinTargets;
import dev.vkdisp.bridge.TerrainDrawCapture;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * H 线 M-06：<b>只读捕获</b>地形 draw 数据与地形雾切片
 * （登记表：{@code docs/04-SPEC.md} §5.0 的 M-06 行；它替代 §5.0 的 M-05 那两个 RETURN 注入点）。
 *
 * <p><b>它解决的问题</b>：GAP-003 方案 A 要在我方自己的多附件 pass 里画地形，就必须拿到
 * {@code ChunkSectionsToRender}；而 {@code GAP-038} 要在我方 pass 体里补雾，就必须拿到
 * {@code terrainFog}。官方 {@code FrameGraphSetupEvent} 两个都给不了（时序早于创建点、
 * 实参表里也没这两项），原版把两者同时交出来的<b>唯一必经点</b>就是这里。
 *
 * <p><b>它不做什么</b>：不取消、不修改、不替换任何原版行为。{@code cancellable} 刻意不写
 * （本注入点的正确性判据就是「原版地形照旧渲染」，任何「改动」都是 bug）。
 *
 * <p><b>为什么 {@code require = 1} / {@code expect = 1}</b>：目标方法一旦在升级中改名或换签名，
 * 注入会<b>硬失败</b>而不是静默不生效 —— 本项目头号坑就是「mixin 没生效但游戏不报错」（T10 / X11）。
 *
 * <p><b>形参为什么逐个照抄</b>：形参列表就是选择器的一部分 —— 少写/写错一个，注入点在升版时
 * 匹配不上就<b>抛</b>（这正是我们要的行为：把「哑掉的判据」变成「响亮的失败」）。
 *
 * <p><b>可关闭</b>：配置键 {@code mixin.captureMainPassArgs}（默认开）。
 * 关闭后本注入点不保存引用 ⇒ 我方多附件 pass 拿不到地形数据、保持静默不开（不报错，
 * 因为「未启用」不是失败）。
 */
@Mixin(targets = {MixinTargets.LEVEL_RENDERER}, remap = false)
public abstract class LevelRendererMainPassCaptureMixin {

    /**
     * 原版 {@code addMainPass} 的五个形参逐个对齐（private，第 396-402 行）——
     * 其中第 3 个是雾切片、第 4 个是地形 draw 数据，正是本注入点要捕获的两项。
     */
    @Inject(method = MixinTargets.LEVEL_RENDERER_ADD_MAIN_PASS, at = @At("HEAD"), require = 1, expect = 1)
    private void vkdisp$captureMainPassArgs(FrameGraphBuilder frame,
            FeatureRenderDispatcher.PreparedFrame featureFrame, GpuBufferSlice terrainFog,
            ChunkSectionsToRender chunkSectionsToRender, boolean consistentDepthRequired,
            CallbackInfo ci) {
        // 首行埋点 + 转发（M1 编码约束 ③）：计数、首次自报与开关判定都在 bridge 侧。
        //   第三个实参是 **GAP-038 的雾读数**用的：`terrainFog` 只是一块 UBO 切片（看不出内容），
        //   而它的<b>来源</b>是 `cameraState.fogData`（原版 `FogRenderer#updateBuffer` 每帧从它写进切片，
        //   源码级核实：`GameRenderer:671` 先 `updateBuffer(cameraState.fogData)` 再 `getBuffer(WORLD)`）
        //   ⇒ 读 FogData 与读切片是同一份数据，但不必去猜 std140 的字节布局。
        TerrainDrawCapture.onCaptured(chunkSectionsToRender, terrainFog, vkdisp$levelRenderState());
    }

    /**
     * 取 {@code LevelRenderer#levelRenderState}（第 129 行那个<b>包私有</b> final 字段）交给 bridge，
     * 由 bridge 再走一步到 {@code cameraRenderState}（{@code fogType} 与 {@code fogData} 都是 public 字段）。
     *
     * <p>🔴 为什么是 {@code @Accessor} 而不是反射：反射读私有字段是 {@code GAP-042} 那一族
     * （升版改名即静默失去判据，失败只 WARN）。这里 {@code require = 1} ⇒ 字段改名会在<b>启动期硬失败</b>。
     * 口径同 M-08/M-09（{@code IVkdispChunkLayerSamplerAccess} 那一族）。
     * <p>🔖 为什么「走一步到 cameraRenderState」放在 bridge 而不是这里：X25 要求 mixin 只转发
     * （方法体第一条语句就是那次 {@code onXxx(} 调用）⇒ 取字段、判空、格式化全在 bridge 侧。
     */
    @Accessor("levelRenderState")
    abstract net.minecraft.client.renderer.state.level.LevelRenderState vkdisp$levelRenderState();
}
