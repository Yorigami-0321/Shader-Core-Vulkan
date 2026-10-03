package dev.vkdisp.mixin;
/**
 * 【参考调研】H 线 M-01b 注入点 / Mixin {@code @Inject} 官方范式 + 原版 {@code ChunkSectionsToRender#renderLayers}
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① Mixin 0.8.7 的 {@code @Mixin(targets=…)} / {@code @Inject} / {@code @At} / {@code CallbackInfo}
 *    注解签名（javap 核实常量池，不读实现）；② 原版 {@code ChunkSectionsToRender#renderLayers} 的源码
 *    （随 MDG 分发的 {@code minecraft-patched-26.3.0.41-beta-sources.jar}，第 73–94 行）。
 *    许可证：Mixin = MIT；原版 = Mojang EULA（只观察方法签名与语句顺序）。
 *    → 能否并入本项目（MIT）：可以；本文件为独立编写的注入类
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：Mixin 官方范式 —— private 方法用完整形参列表 + {@code CallbackInfo} 收尾。
 * 2. 备选：{@code @ModifyArgs} / {@code @ModifyVariable} 改形参 —— 否决（M-01 已在
 *    {@code ChunkSectionLayer#pipeline} 上按层解决管线替换，本注入点要做的只是**往 RenderPass
 *    上绑一个 uniform**，与形参无关）。
 * 3. 我们的差异点：**只转发，不写业务**（X25）。本类只做三件事：埋点、判断「两个 override 是否都为
 *    null」（与 M-01 的替换条件严格对称）、转发给 {@code bridge.TerrainPipelineApi}。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：热路径，每帧 2–3 次 {@code setUniform}（同一对象、无分配）；不做性能优化。
 */
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.vkdisp.bridge.MixinTargets;
import dev.vkdisp.bridge.TerrainPipelineApi;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * H 线 M-01b：把 M-01 派生管线新增的**自定义 uniform 块**（GAP-004）绑到地形 draw 的 RenderPass 上
 * （登记表：{@code docs/04-SPEC.md} §5.0 的 M-01b 行）。
 *
 * <p><b>为什么必须有这个注入点</b>：M-01 只替换「用哪条管线」，而派生管线比原版多一个
 * bind group 条目（{@code VkDispTerrainParams}）。驱动层 STRICT_VALIDATION 下
 * {@code validateDraw} 按<b>布局</b>逐条校验 —— 每个条目都必须先 {@code setUniform}，否则抛
 * {@code Missing uniform 名}。原版 {@code renderLayers} 自己只绑 {@code TerrainUniform} /
 * {@code Sampler0} / {@code Sampler2}，所以这一个条目没人绑 ⇒ 必须在 draw 之前由我们补上。
 *
 * <p><b>为什么必须判「两个 override 都为 null」才绑</b>：override 非 null 时原版用的是
 * {@code WIREFRAME}（线框模式）或 {@code OIT_TERRAIN}（有序透明）——那两条管线<b>没有</b>我们的块，
 * 此时多绑一个名字是往不认它的布局里塞值。此条件与 M-01 的替换条件**严格对称**：
 * 我们的块只在我们的管线可能被用到时才绑。
 */
@Mixin(targets = {MixinTargets.CHUNK_SECTIONS_TO_RENDER}, remap = false)
public abstract class ChunkSectionsToRenderMixin {

    /**
     * 形参与原版 {@code renderLayers} 逐个对齐（7 参，末两个是 override）——
     * <b>升级时原版一改签名，这里就会注入失败（require=1）而不是静默不生效</b>。
     */
    @Inject(method = "renderLayers", at = @At("HEAD"), require = 1, expect = 1)
    private void vkdisp$bindTerrainParams(ChunkSectionLayer[] layers, GpuSampler sampler,
            RenderPass renderPass, GpuTextureView atlas, GpuTextureView lightmap,
            RenderPipeline renderPipelineOverride, RenderPipeline renderPipelineOverrideMultidraw,
            CallbackInfo ci) {
        // 首行埋点（M1 编码约束 ③）：由 bridge 统一做首次 / 每 600 次的节流日志与计数。
        TerrainPipelineApi.onBindTerrainParamsEntry();
        if (renderPipelineOverride != null || renderPipelineOverrideMultidraw != null) {
            // 原版 override 生效 ⇒ 用的是 WIREFRAME / OIT 管线，没有我们的块，不绑。
            return;
        }
        if (TerrainPipelineApi.bindTerrainParamsEnabled()) {
            TerrainPipelineApi.bindTerrainParams(renderPass);
        }
    }
}