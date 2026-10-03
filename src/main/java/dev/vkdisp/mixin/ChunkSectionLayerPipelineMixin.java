package dev.vkdisp.mixin;
/**
 * 【参考调研】H 线 M-01 注入点 / Mixin 官方注解语义 + 原版 {@code ChunkSectionLayer#pipeline}
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① Mixin 0.8.7（NeoForge 26.3 传递依赖 {@code net.fabricmc:sponge-mixin}）的
 *    {@code @Mixin(targets=…)} / {@code @Inject} / {@code @At} / {@code CallbackInfoReturnable}
 *    **注解签名**（javap 核实常量池里的类名与描述符，不读其实现）；
 *    ② 原版 {@code ChunkSectionLayer#pipeline(boolean)} 的源码（随 MDG 分发的
 *    {@code minecraft-patched-26.3.0.41-beta-sources.jar}）。
 *    许可证：Mixin = MIT（FabricMC 官方 LICENSE）；原版 = Mojang EULA（只观察方法签名）。
 *    → 能否并入本项目（MIT）：可以 —— 同族可并入；且本文件不含任何被参考方代码
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：Mixin 官方范式 {@code @Inject(method=…, at=@At("HEAD"), cancellable=true, require=1)}
 *    + {@code CallbackInfoReturnable#setReturnValue} 提前返回。
 * 2. 备选：{@code @Redirect} 包裹原调用 —— 否决（{@code @Redirect} 一旦目标调用点位置变化就整条失效，
 *    而这里需要的是「返回值替换」而非「调用替换」；且原版另有「建网格取顶点格式」两处调用点）。
 *    另一备选：{@code @Mixin(ChunkSectionLayer.class)} 类字面量 —— 否决（M1 编码约束 ① 要求目标类名
 *    收敛到 {@code bridge.MixinTargets} 常量，类字面量等于把硬编码散回每个 mixin 文件）。
 * 3. 我们的差异点：**只转发，不写业务**（07-CONSTRAINTS X25）。方法体只有
 *    ① 埋点 ② 一次查表 ③ 命中才提前返回；全部策略在 {@code bridge.TerrainPipelineApi}。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：热路径但极轻（每帧 2–6 次查表 + 每层 1 次 name()，无分配）；不做性能优化
 *    （17-NATIVE.md §3.2）。
 */
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.vkdisp.bridge.MixinTargets;
import dev.vkdisp.bridge.TerrainPipelineApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * H 线 M-01：把**派生地形管线**接到地形 draw 上（登记表：{@code docs/04-SPEC.md} §5.0 的 M-01 行）。
 *
 * <p><b>注入点</b>：{@code ChunkSectionLayer#pipeline(boolean)} HEAD，{@code cancellable = true}。
 * 选它而不选 {@code renderLayers} 的理由写在 {@code bridge.MixinTargets} 的类注释里：
 * {@code renderLayers} 的两个 override 形参<b>整组共用</b>，而 OPAQUE 组一次含 SOLID + CUTOUT 两层
 * ⇒ 在那里设 override 会让 CUTOUT 丢掉自己的 {@code ALPHA_CUTOUT} 与混合模式。
 *
 * <p><b>可关闭</b>：配置键 {@code mixin.wireTerrain}（默认开）。关掉后本注入点立即回到原版行为。
 *
 * <p><b>为什么 {@code require = 1} / {@code expect = 1}</b>：目标方法一旦在升级中改名或签名变化，
 * 注入会**硬失败**而不是静默不生效 —— 本项目头号坑就是「mixin 没生效但游戏不报错」（T10 / X11）。
 *
 * <p><b>为什么 {@code remap = false}</b>：NeoForge 运行期使用 Mojang 官方映射（无 SRG 名），
 * 且 ModDevGradle **不**注入 Mixin 注解处理器（本工程 {@code annotationProcessor} 配置实测为空），
 * 因此没有 refmap 可用、也不需要 —— 名字按字面量即运行期真名。
 */
@Mixin(targets = {MixinTargets.CHUNK_SECTION_LAYER}, remap = false)
public abstract class ChunkSectionLayerPipelineMixin {

    /**
     * 命中即把原版层对应的派生管线作为返回值。
     *
     * @param multiDraw 原版形参：{@code true} = 多重绘制变体（{@code *_MULTIDRAW}）
     * @param cir       提前返回通道；不 {@code setReturnValue} 就完全等价于原版
     */
    @Inject(method = "pipeline", at = @At("HEAD"), cancellable = true, require = 1, expect = 1)
    private void vkdisp$wireDerivedTerrainPipeline(boolean multiDraw,
            CallbackInfoReturnable<RenderPipeline> cir) {
        // 首行埋点（M1 编码约束 ③）：命中计数 + 首次/每 600 次打一条，不刷屏。
        TerrainPipelineApi.onWireTerrainHit();
        // this 传成 Object：bridge 侧按 Enum 语义取名字，mixin 文件里不出现原版类字面量。
        RenderPipeline derived = TerrainPipelineApi.derivedTerrainPipeline(
                TerrainPipelineApi.layerNameOf(this), multiDraw);
        if (derived != null) {
            cir.setReturnValue(derived);
        }
    }
}