package dev.vkdisp.bridge;

import com.mojang.renderpearl.api.textures.GpuSampler;
import org.jspecify.annotations.Nullable;

/**
 * 【参考调研】R3 / M-07：读出原版 `LevelRenderer#chunkLayerSampler`（private 字段）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① Mixin 0.8.7 的 {@code @Mixin(targets=…)} / {@code @Accessor} **注解签名**
 *    （javap 核实常量池里的类名与描述符，不读其实现）；
 *    ② 原版 {@code LevelRenderer} 第 146 行的字段声明与第 437-449 行的按需重建
 *    （随 MDG 分发的 sources jar，只观察字段类型与重建条件）。
 *    许可证：Mixin = MIT；原版 = Mojang EULA（只观察字段与语义）。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以 —— 本文件是本项目原创契约接口，零第三方代码
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：Mixin 官方范式 —— mixin 类 {@code implements} 本接口，
 *    {@code @Accessor} 在运行期生成实现；调用方把原版对象向上转成本接口。
 * 2. 备选：① 反射 {@code getDeclaredField("chunkLayerSampler") + setAccessible} —— **已否决**
 *    （{@code 07} T10：注入/取值手段必须能证明生效，反射改名即哑、失败只 WARN，正是 {@code GAP-042}）；
 *    ② {@code @Shadow} 字段 —— 否决（本接口要把值**交出去**，影子字段只是类内可见）。
 * 3. 我们的差异点：接口放在 {@code bridge/}（依赖方向 = mixin → bridge），
 *    方法名带 {@code vkdisp$} 前缀避免与原版成员相撞；原版字段是 {@code @Nullable} 的，
 *    本接口照抄该可空性（{@code org.jspecify} 口径，QD-01）。
 * 4. 许可证核对：本项目 LGPL-3.0-or-later；零第三方代码复制。
 * 5. 性能基线：热路径但极轻 —— 一次字段读（无分配、无创建）；不做性能优化（17 §3.2）。
 */
public interface IVkdispChunkLayerSamplerAccess {

    /**
     * 原版这一帧用于方块图集的采样器实例。
     *
     * <p>🔖 它的各向异性来自 {@code optionsRenderState.maxAnisotropyValue}
     * （仅当 {@code textureFiltering == ANISOTROPIC}，否则为 1），并由原版的
     * {@code shouldResetChunkLayerSampler} 门控重建 —— 复用这个实例 = 这两件事一起继承
     * （{@code GAP-041} 要的正是要继承它们）。
     *
     * @return 原版实例；**首帧**原版 pass 体尚未执行时为 {@code null}（原版第 437 行才建它）
     */
    @Nullable
    GpuSampler vkdisp$chunkLayerSampler();
}
