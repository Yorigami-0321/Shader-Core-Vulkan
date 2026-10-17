package dev.vkdisp.bridge;

import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

/**
 * 【参考调研】R3 / M-08：读出地形 draw 数据里**每层实际排队的 draw 组**，替掉反射
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① Mixin 0.8.7 的 {@code @Accessor} **注解签名**（javap 核实，不读实现）；
 *    ② 原版 {@code ChunkSectionsToRender$DrawIndirect} 第 97 行的
 *    {@code private final EnumMap<ChunkSectionLayer, List<GpuMultiDrawIndexedIndirect>> drawGroupsPerLayer}
 *    与公开 record {@code ChunkSectionsToRender$GpuMultiDrawIndexedIndirect#drawCount()}
 *    （sources jar 第 96-188 行逐字核实）。
 *    许可证：Mixin = MIT；原版 = Mojang EULA（只观察字段与 record 访问器）。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以 —— 本项目原创契约接口
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：{@code @Accessor} 交出**字段本体**（返回类型与字段声明逐字相同，不做放宽），
 *    本接口只把它以更弱的类型交出去（{@code ? extends List<?>}），加法在 mixin 侧的一行转发里。
 * 2. 备选：反射 {@code getDeclaredField("drawGroupsPerLayer") / ("drawCount")} —— **已否决**
 *    （{@code GAP-042}）。🔖 而且 {@code drawCount} 那一半**本来就是公开的**：
 *    {@code GpuMultiDrawIndexedIndirect} 是 {@code public record}，
 *    {@code drawCount()} 是它自己生成的公开访问器 ⇒ 旧反射里那次 {@code setAccessible} 是多余的。
 * 3. 我们的差异点：判据形状不变（层 → groups/draws 两个数），但取值路径改成
 *    「访问器 + 公开 record 访问器」，改名即**注入期硬失败**而不是运行期 WARN。
 * 4. 许可证核对：本项目 LGPL-3.0-or-later；零第三方代码复制。
 * 5. 性能基线：❄️ 只在诊断档、且只在第 300/1200 帧与水印那次被读（不在每帧热路径）。
 */
public interface IVkdispTerrainDrawProbe {

    /**
     * 层 → 该层排队好的 draw 组列表。
     *
     * <p>⚠️ 只有 **indirect 变体**（{@code DrawIndirect}）实现本接口：本机 lavapipe 走的就是这条
     * 分支（{@code usingMultiDrawIndirectForTerrain}）。{@code DrawSeparate} 的对应字段是
     * {@code drawsPerLayer}（第 153 行，元素类型 {@code RenderPass.Draw}），本轮未登记、未注入
     * —— 要覆盖它先按 X28 登记，别顺手加注入点。
     */
    Map<ChunkSectionLayer, ? extends List<?>> vkdisp$drawGroupsPerLayer();
}
