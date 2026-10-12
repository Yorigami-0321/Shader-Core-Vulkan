package dev.vkdisp.mixin;
/**
 * 【参考调研】R1a / M-15 {@code @WrapOperation}：地形取管线那一刻，用 pass 的**对象身份**决定变体
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① MixinExtras（NeoForge 自带，{@code io.github.llamalad7:mixinextras-neoforge:0.5.5}）
 *    的 {@code @WrapOperation} / {@code Operation} / {@code @Local} **注解与接口签名**
 *    （只用其公开契约，不读实现；LGPL-2.1 同族条款见 {@code 07} L 系列）；
 *    ② Mixin 0.8.7 的 {@code @Mixin(targets=…)} / {@code @At} 注解签名（javap 核实常量池）；
 *    ③ 原版 {@code ChunkSectionsToRender}：{@code DrawSeparate#render} 第 **176** 行与
 *    {@code DrawIndirect#render} 第 **121** 行的
 *    {@code renderPass.setPipeline(RenderSystem.getCompiledPipeline(...))}，以及
 *    {@code RenderSystem} 第 123 行 {@code public static CompiledRenderPipeline getCompiledPipeline(RenderPipeline)}
 *    （sources jar 逐字核实）。
 *    许可证：Mixin = MIT；MixinExtras/NeoForge = LGPL-2.1（只观察契约）；原版 = Mojang EULA（只观察语句）。
 *    → 能否并入本项目（MIT）：可以；本文件为独立编写的注入类，零第三方代码复制
 *    → 例外条款：无；不含任何 GPL / ARR 代码
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：{@code @WrapOperation} + {@code @Local(argsOnly = true)} ——
 *    既拿到**被包住的调用**的入参与返回值通道，又拿到<b>所在方法的形参</b>（这里要的是 {@code renderPass}）。
 * 2. 备选：① {@code PipelineModifier} —— **已证伪**（结果缓存按 {@code (modifier, 入参管线)} 建、
 *    没有失效路径，而 key 只能启动期注册 ⇒ 包代次进不了缓存，{@code GAP-045}）；
 *    ② {@code @Redirect} —— **否决**（handler 只能看到被调用者的实例与形参，看不到 {@code renderPass}，
 *    于是「在哪个 pass 里」这件事又只能靠静态标志 —— 那正是本条要删掉的东西）；
 *    ③ 留在 M-01 里判 {@code active()} —— **否决**（{@code GAP-040}：静态布尔 + 附件数空转守卫）。
 * 3. 我们的差异点：方法体只有「埋点 + 一次 bridge 判定 + 原样把结果交给 original」（X25）。
 *    判定本体 {@code TerrainPipelineApi.passAwarePipeline} 是<b>纯函数</b> ⇒ 不启动客户端就能单测
 *    「错配必须被拒」。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：🔴 热路径 —— 每次地形 draw 每组 1 次（本机每帧 2–6 次取管线 × 层数）。
 *    handler 只做一次对象引用比较 + 一次 map.get，无分配；埋点按 M-01 的节流口径（首次 + 每 600 次）。
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
 * H 线 M-15（非多重绘制变体）：{@code ChunkSectionsToRender$DrawSeparate#render} 第 176 行那次
 * {@code getCompiledPipeline} 由 <b>pass 对象身份</b>决定是否升级成多附件变体
 * （登记表：{@code docs/04-SPEC.md} §5.0 的 M-15 行；{@code multiDraw = false} 那一支）。
 *
 * <p><b>可关闭</b>：配置键 {@code mixin.passAwarePipeline}（默认开）。关档 = 判定直接原样返回入参
 * ⇒ 我方 8 附件 pass 会拿到单附件管线并被 {@code setPipeline} 当场抛（响亮失败，A/B 位）。
 *
 * <p><b>为什么 {@code require = 1} / {@code expect = 1}</b>：目标方法名与形参表就是判据本身
 * （{@code renderPass} 是第 2 个形参）。原版改签名时这里必须<b>注入期硬失败</b>，
 * 而不是回到「靠静态布尔猜」的老路（T10 / X11）。
 */
@Mixin(targets = {MixinTargets.CHUNK_SECTIONS_TO_RENDER_DRAW_SEPARATE}, remap = false)
public abstract class ChunkSectionsToRenderSeparatePipelineMixin {

    /** 本变体用哪一条管线：{@code DrawSeparate} 走 {@code layer.pipeline(false)}（第 176 行）。 */
    private static final boolean MULTI_DRAW = false;

    /**
     * @param requested 原版即将编译的管线（已被 M-01 换成单附件派生版）
     * @param original  被包住的那次调用本身 —— 判定完必须<b>照常调用</b>它，不吞
     * @param layer     所在方法第 1 个形参：本次要画的层（判定按层名查表）
     * @param renderPass 所在方法第 2 个形参：这次 draw 落在哪个 pass —— <b>pass 身份的数据来源</b>
     */
    @WrapOperation(method = {MixinTargets.CHUNK_SECTIONS_TO_RENDER_RENDER_INNER},
            at = @At(value = "INVOKE", target = MixinTargets.RENDER_SYSTEM_GET_COMPILED_PIPELINE),
            require = 1, expect = 1)
    private CompiledRenderPipeline vkdisp$passAwarePipeline(ChunkSectionsToRender self,
            RenderPipeline requested, Operation<CompiledRenderPipeline> original,
            @Local(argsOnly = true, ordinal = 0) ChunkSectionLayer layer,
            @Local(argsOnly = true, ordinal = 0) RenderPass renderPass) {
        // 首行埋点（M1 编码约束 ③）：证明这条通道真的被走到，节流口径同 M-01。
        PassAwarePipelines.onHit();
        return original.call(PassAwarePipelines.select(
                TerrainPipelineApi.layerNameOf(layer), MULTI_DRAW,
                MrtTerrainPass.isOurPass(renderPass), requested));
    }
}
