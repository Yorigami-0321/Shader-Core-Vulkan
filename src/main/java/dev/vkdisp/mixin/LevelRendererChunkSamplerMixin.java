package dev.vkdisp.mixin;
/**
 * 【参考调研】R3 / M-07 访问器：读原版 `LevelRenderer#chunkLayerSampler` / Mixin `@Accessor` 注解语义
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① Mixin 0.8.7（NeoForge 26.3 传递依赖 {@code net.fabricmc:sponge-mixin}）的
 *    {@code @Mixin(targets=…)} / {@code @Accessor} **注解签名**（javap 核实常量池，不读其实现）；
 *    ② 原版 {@code LevelRenderer} 第 146 行字段声明与第 437-449 行重建条件
 *    （随 MDG 分发的 {@code minecraft-patched-*-sources.jar}，只观察字段类型与语义）。
 *    许可证：Mixin = MIT（FabricMC 官方 LICENSE）；原版 = Mojang EULA（只观察字段）。
 *    → 能否并入本项目（MIT）：可以 —— 同族可并入；本文件不含任何被参考方代码
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：Mixin 官方 {@code @Accessor} 范式 —— 抽象方法由运行期 applier 生成实现，
 *    目标类被加上 {@code implements} 本文件声明的接口。
 * 2. 备选：① 反射读该 private 字段 —— **否决**（{@code GAP-041}/{@code GAP-042}：改名即哑、
 *    失败只 WARN，不满足 {@code 07} T10）；② {@code @Inject} 进原版 pass 体把采样器抓出来 ——
 *    **否决**（多一个注入点、还要存一个静态字段；访问器一次读就够）。
 * 3. 我们的差异点：**只有抽象访问器，零方法体**（X25「mixin 只转发，不写业务」的极限形态）。
 *    「用不用这个实例」的决策在 {@code MrtTerrainPass#atlasSampler()}，由
 *    {@code mixin.reuseChunkSampler} 把关（X29 可关闭制）。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：热路径 —— 每帧一次字段读，无分配、无 {@code createSampler}。
 */
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.vkdisp.bridge.IVkdispChunkLayerSamplerAccess;
import dev.vkdisp.bridge.MixinTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * H 线 M-07：把原版图集采样器交给我方（登记表：{@code docs/04-SPEC.md} §5.0 的 M-07 行）。
 *
 * <p><b>为什么访问器的返回类型逐字照抄字段声明</b>：{@code @Accessor} 的 getter 若把返回类型
 * 放宽（例如交成 {@code Object}），是否插入 {@code CHECKCAST} 取决于 applier，
 * 而这条路本机没有单测能覆盖 ⇒ 这里刻意写 {@code GpuSampler} = 字段声明的类型，不引入放宽。
 * 原版字段是 {@code @Nullable} 的（首帧原版 pass 体尚未执行时为 null），
 * 可空性表达在接口 {@link IVkdispChunkLayerSamplerAccess} 的 {@code @Nullable} 上。
 *
 * <p><b>为什么这里没有 {@code require/expect}</b>：那两个属性不属于 {@link Accessor}。
 * 本注入点「升版即硬失败」的性质由 applier 保证 —— 目标字段不存在时生成访问器直接抛，
 * mixin 应用失败会响亮报出（同 {@code injectors.defaultRequire=1} 的口径）。
 *
 * <p><b>可关闭</b>：配置键 {@code mixin.reuseChunkSampler}（默认开）。关档后
 * {@code MrtTerrainPass} 不再读这个实例，改走公开的 {@code RenderSystem.getSamplerCache()}
 * —— 那一档的各向异性恒为 1，正是 {@code GAP-041} 登记的坏档，留着只为 A/B。
 */
@Mixin(targets = {MixinTargets.LEVEL_RENDERER}, remap = false)
public abstract class LevelRendererChunkSamplerMixin implements IVkdispChunkLayerSamplerAccess {

    /** 原版 private 字段 {@code chunkLayerSampler} 的读取器（名字前缀避免与原版成员相撞）。 */
    @Accessor(MixinTargets.LEVEL_RENDERER_CHUNK_LAYER_SAMPLER)
    @Override
    public abstract GpuSampler vkdisp$chunkLayerSampler();
}
