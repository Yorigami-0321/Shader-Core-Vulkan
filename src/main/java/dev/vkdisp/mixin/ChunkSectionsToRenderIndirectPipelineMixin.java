package dev.vkdisp.mixin;
/**
 * 【参考调研】R1a / M-15 {@code @WrapOperation} 的第二支：多重绘制（indirect）变体
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 与 {@code ChunkSectionsToRenderSeparatePipelineMixin} 同一条（MixinExtras 的
 *    {@code @WrapOperation}/{@code @Local} 契约 + 原版 {@code ChunkSectionsToRender$DrawIndirect#render}
 *    第 **121** 行的 {@code getCompiledPipeline}）；本文件只换目标类与 {@code multiDraw} 一项。
 *    许可证：Mixin = MIT；MixinExtras/NeoForge = LGPL-2.1（只观察契约）；原版 = Mojang EULA（只观察语句）。
 *    → 能否并入本项目（MIT）：可以；独立编写，零第三方代码复制
 *    → 例外条款：无；不含任何 GPL / ARR 代码
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：同 M-15 另一支（{@code @WrapOperation} + {@code @Local(argsOnly = true)}）。
 * 2. 备选：见 {@code ChunkSectionsToRenderSeparatePipelineMixin} 的第 2 条（modifier 已证伪、
 *    {@code @Redirect} 看不到 renderPass、静态标志就是本条要删的东西）。
 * 3. 我们的差异点：两支合起来才覆盖「地形取管线」的全部两条路（{@code usingMultiDrawIndirectForTerrain}
 *    二选一，见 {@code LevelRenderer} 第 269-275 行）—— <b>少注一支 = 那一支仍走静态标志判定</b>，
 *    这与旧 M-05「两个重载都要注」是同一条纪律。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：🔴 热路径（本机 lavapipe 走的正是这一支）。一次引用比较 + 一次 map.get，无分配。
 */
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.vkdisp.bridge.MixinTargets;
import dev.vkdisp.bridge.MrtTerrainPass;
import dev.vkdisp.bridge.PassAwarePipelines;
import dev.vkdisp.bridge.TerrainPipelineApi;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * H 线 M-15（多重绘制变体，本机实际走的那一支）：{@code ChunkSectionsToRender$DrawIndirect#render}
 * 第 121 行的取管线调用由 <b>pass 对象身份</b>决定变体（登记表：{@code 04-SPEC} §5.0 的 M-15 行，
 * {@code multiDraw = true}）。
 *
 * <p><b>可关闭</b>：与另一支共用配置键 {@code mixin.passAwarePipeline} —— 它们是<b>同一个</b>注入点
 * 的两个变体（M-15），不是一个能单独关掉的策略；分开关会得到「一支升级、一支不升级」的混合画面。
 */
@Mixin(targets = {MixinTargets.CHUNK_SECTIONS_TO_RENDER_DRAW_INDIRECT}, remap = false)
public abstract class ChunkSectionsToRenderIndirectPipelineMixin {

    /** 本变体用哪一条管线：{@code DrawIndirect} 走 {@code layer.pipeline(true)}（第 121 行）。 */
    private static final boolean MULTI_DRAW = true;

    /**
     * @param requested 原版即将编译的管线（已被 M-01 换成单附件派生版）
     * @param original  被包住的那次调用本身
     * @param layer     所在方法第 1 个形参
     * @param renderPass 所在方法第 2 个形参：pass 身份的数据来源
     */
    @WrapOperation(method = {MixinTargets.CHUNK_SECTIONS_TO_RENDER_RENDER_INNER},
            at = @At(value = "INVOKE", target = MixinTargets.RENDER_SYSTEM_GET_COMPILED_PIPELINE),
            require = 1, expect = 1)
    private CompiledRenderPipeline vkdisp$passAwarePipeline(ChunkSectionsToRender self,
            RenderPipeline requested, Operation<CompiledRenderPipeline> original,
            @Local(argsOnly = true, ordinal = 0) ChunkSectionLayer layer,
            @Local(argsOnly = true, ordinal = 0) RenderPass renderPass) {
        PassAwarePipelines.onHit();
        return original.call(PassAwarePipelines.select(
                TerrainPipelineApi.layerNameOf(layer), MULTI_DRAW,
                MrtTerrainPass.isOurPass(renderPass), requested));
    }
}
