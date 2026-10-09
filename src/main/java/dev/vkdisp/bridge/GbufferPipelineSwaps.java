package dev.vkdisp.bridge;
/**
 * 【参考调研】GAP-027 的<b>管线替换</b>通道：把原版内部自取的管线换成我方派生版 /
 * 只依据公开 API 与本仓库实测
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 原版 26.3 {@code com/mojang/blaze3d/systems/RenderSystem.java} 的
 *    <b>逐字两行</b>：{@code getCompiledPipelineNullable(RenderPipeline)}（:106）的
 *    <b>首条语句</b>就是 {@code pipeline = PIPELINE_MODIFIERS.apply(pipeline);}（:107），
 *    而 {@code getCompiledPipeline(...)}（:123）只是它包一层 null 检查
 *    ⇒ <b>对 {@code SkyRenderer}/{@code CloudRenderer} 这类「内部自己取管线」的路径有效</b>
 *    （本轮核实：{@code SkyRenderer.java:167,180,256,274,298,314}、
 *    {@code CloudRenderer.java:229} 走的都是 {@code getCompiledPipeline}）；
 *    ② NeoForge 26.3 {@code net/neoforged/neoforge/client/pipeline/} 的**公开 API 与契约**
 *    （LGPL-2.1-only：只观察事件签名、方法契约与抛错条件，不复制其实现）：
 *    {@code RegisterPipelineModifiersEvent.register(ResourceKey, PipelineModifier)}、
 *    {@code PipelineModifier#apply(RenderPipeline, Identifier)}
 *    （javadoc 逐字要求「implementations must be idempotent」、
 *    且改过的管线<b>必须</b>用传进来的 name 作 location）、
 *    {@code PipelineModifierStack#apply} 第 63-66 行：返回值 location 与入参相同 ⇒ <b>抛</b>；
 *    ③ 原版 {@code com/mojang/blaze3d/pipeline/PipelineCache#get}（:30-36）：
 *    <b>缓存未命中就地编译</b>（{@code device.compilePipeline(...).join().finishCompile()}）
 *    ⇒ 派生管线<b>不需要</b>预先 {@code RegisterRenderPipelinesEvent} 注册。
 *    许可证：Mojang EULA + NeoForge LGPL-2.1（均只观察签名/契约）。
 *    → 能否并入本项目（MIT）：可以 —— 本文件是独立编写的桥接封装，零源码搬运。
 *    → 例外条款：无；不含任何 GPL / ARR 代码。
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）。
 * 1. 官方/主实现：NeoForge 的 pipeline modifier 就是官方给的「不改 mixin、在装配层换管线」通道。
 * 2. 备选：① 给每个要换管线的渲染器加一个 mixin —— <b>否决</b>：M1 要求注入点逐个登记且能一键关，
 *    而这条官方通道零注入点；② 自己复制 {@code CloudRenderer} 的绘制逻辑 —— <b>否决</b>（L 系列红线）。
 * 3. 我们的差异点：
 *    <ul>
 *      <li>🔖 <b>幂等</b>是硬要求，不是风格：判定用「入参<b>是不是</b>那条原版管线对象」而不是
 *          「location 里有没有我的后缀」—— 后者在结果再次进 modifier 时会二次加工。</li>
 *      <li>🔖 <b>必须用传进来的 {@code name}</b> 作 location（NeoForge 用它做可追溯），
 *          自己编一个名字会在 {@code PipelineModifierStack:63-66} 那一条被抛出来。</li>
 *      <li>🔖 <b>不认识的管线原样返回</b>（返回入参本身 = 不触发那条抛错检查）。</li>
 *    </ul>
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 *    🔖 本仓已记过一条同族事实（{@code VkDispConfigHotReload} 类注释第 14 行）：
 *    26.3 的 {@code @EventBusSubscriber} <b>没有</b> {@code bus} 属性 —— mod 总线事件
 *    （{@code IModBusEvent}）由 NeoForge 自己路由，注解里不写总线名。
 * 5. 性能基线：❄️ 冷路径 —— 每条「原版管线 → 派生管线」的映射由 NeoForge 侧按
 *    {@code (modifier, pipeline)} 缓存，我方只在<b>第一次</b>被调用时构造；渲染期零额外分配。
 */
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.vkdisp.VkDisp;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.pipeline.PipelineModifier;
import net.neoforged.neoforge.client.pipeline.RegisterPipelineModifiersEvent;

/** 我方注册的管线替换器（GAP-027：非地形几何要换状态/换着色器时的官方通道）。 */
@EventBusSubscriber(modid = VkDisp.MOD_ID, value = Dist.CLIENT)
public final class GbufferPipelineSwaps {

    /**
     * 「云的派生管线」替换器：只把原版 {@code RenderPipelines.CLOUDS}（FANCY 云，<b>带背面裁剪</b>）
     * 换成同 snippet 但 {@code withCull(false)} 的版本。
     *
     * <p>🔴 <b>为什么需要它</b>（h49n/h49o 实测，{@code evidence/h49l-clouds-into-gbuffer.md} §七/§八）：
     * 云几何搬进我方 gbuffer pass 后 {@code drawIndexed} 确实发出（反射 {@code quadCount=9746}），
     * 但对 colortex0 <b>零写入</b>；把画质切到 {@code fast}（原版改走
     * {@code FLAT_CLOUDS}，它逐字 {@code withCull(false)}）后同一探针读到
     * {@code meanRGB=(255, 0.2588, 255)} ⇒ <b>云像素落地了</b>。
     * 差异只有「裁剪」一项 ⇒ 这一格要的就是关掉它。
     *
     * <p>⚠️ <b>这不是把云画对</b>：它只让云<b>不被剔光</b>。h49o 的覆盖仍只有 ~0.1% 画面量级，
     * 位姿/相机偏移那一半未查（登记表 GAP-027 的 h49l 行写清了）。
     * 同一个派生管线还按 {@code mrt.cloudsNoDepthWrite} 关掉<b>深度写入</b>
     * （h49z/h50a：云把自己的深度写进 AO 读的那张 {@code depthtex} ⇒ 链 67% 黑帧，
     * 且与 {@code depthGlProxy} 无关）⇒ 一条派生管线修两项状态，各自有独立开关。
     */
    public static final ResourceKey<PipelineModifier> CLOUDS_NO_CULL =
            ResourceKey.create(PipelineModifier.MODIFIERS_KEY,
                    Identifier.fromNamespaceAndPath(VkDisp.MOD_ID, "clouds_no_cull"));

    /** 替换命中计数（自报用；证明「modifier 真的被调过」而不是只注册了）。 */
    private static final AtomicBoolean APPLIED_ONCE = new AtomicBoolean(false);

    private GbufferPipelineSwaps() {
    }

    /** 注册入口（官方事件，mod 总线）。 */
    @SubscribeEvent
    public static void onRegisterModifiers(RegisterPipelineModifiersEvent event) {
        event.register(CLOUDS_NO_CULL, GbufferPipelineSwaps::cloudsNoCull);
        VkDisp.LOGGER.info("vkdisp: [GAP-027] pipeline modifier 注册: key={} 目标=RenderPipelines.CLOUDS"
                + " -> withCull(false)（幂等：非该对象原样返回）", CLOUDS_NO_CULL.identifier());
    }

    /**
     * 替换本体。<b>幂等</b>：只有入参<b>就是</b> {@code RenderPipelines.CLOUDS} 时才加工，
     * 加工结果再进来时判定不成立 ⇒ 原样返回。
     */
    static RenderPipeline cloudsNoCull(RenderPipeline pipeline, Identifier name) {
        if (pipeline != RenderPipelines.CLOUDS) {
            return pipeline;
        }
        if (APPLIED_ONCE.compareAndSet(false, true)) {
            VkDisp.LOGGER.info("vkdisp: [GAP-027] 云管线替换<b>生效</b>: location={} "
                    + "（原版 CLOUDS 带背面裁剪 ⇒ 我方 pass 里云被剔光；派生版 withCull(false)）",
                    name);
        }
        var builder = RenderPipeline.builder(RenderPipelines.CLOUDS_SNIPPET)
                .withLocation(name)
                .withCull(false);
        if (dev.vkdisp.VkDispConfig.MRT_CLOUDS_NO_DEPTH_WRITE.get()) {
            // 🔴 只关**写入**，比较函数照抄 DEFAULT 的 GREATER_THAN_OR_EQUAL（本引擎反向 Z）
            //   ⇒ 山仍然挡云；改掉的只有「云的深度被写进链里 AO 读的那张 depthtex」。
            builder = builder.withDepthStencilState(new com.mojang.renderpearl.api.pipeline
                    .DepthStencilState(com.mojang.renderpearl.api.pipeline.CompareOp
                    .GREATER_THAN_OR_EQUAL, false));
        }
        return builder.build();
    }
}
