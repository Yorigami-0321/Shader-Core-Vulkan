package dev.vkdisp.mixin;
/**
 * 【参考调研】R3 / M-08 访问器：读原版 `ChunkSectionsToRender$DrawIndirect#drawGroupsPerLayer`（替掉反射）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① Mixin 0.8.7 的 {@code @Mixin(targets=…)} / {@code @Accessor} **注解签名**
 *    （javap 核实常量池，不读其实现）；② 原版 {@code ChunkSectionsToRender} 第 96-150 行
 *    （{@code public static final class DrawIndirect} 与其 private 字段声明、
 *    {@code public record GpuMultiDrawIndexedIndirect(..., int drawCount)} 的公开访问器）。
 *    许可证：Mixin = MIT；原版 = Mojang EULA（只观察字段与 record 访问器）。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以；本文件为独立编写的注入类
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：{@code @Accessor} 交出字段本体（返回类型与字段声明**逐字相同**，不做放宽），
 *    再由一行 concrete 转发把它以更弱的类型交给 bridge 接口。
 * 2. 备选：反射 {@code getDeclaredField("drawGroupsPerLayer")} 与
 *    {@code getDeclaredField("drawCount")} —— **否决**（{@code GAP-042}）。
 *    🔖 后一半本来就是多余的：{@code GpuMultiDrawIndexedIndirect} 是 {@code public record}，
 *    {@code drawCount()} 是它自己生成的公开访问器，直接调就行。
 * 3. 我们的差异点：本类只做「取字段 + 一次 map.get」的转发，累加与自报文案在
 *    {@code MrtTerrainPass#probeDrawCounts}（X25）。
 * 4. 许可证核对：本项目 LGPL-3.0-or-later；零第三方代码复制。
 * 5. 性能基线：❄️ 只在第 300/1200 帧与水那次自报被读；不在每帧热路径。
 */
import dev.vkdisp.bridge.IVkdispTerrainDrawProbe;
import dev.vkdisp.bridge.MixinTargets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * H 线 M-08：地形 draw 条数从「反射 + 失败只 WARN」改成「访问器 + 改名即硬失败」
 * （登记表：{@code docs/04-SPEC.md} §5.0 的 M-08 行）。
 *
 * <p><b>目标为什么写 {@code ChunkSectionsToRender$DrawIndirect} 而不是基类</b>：
 * 那个字段在<b>嵌套变体</b>上（sources jar 第 97 行），基类 {@code ChunkSectionsToRender}
 * 没有它 —— 旧反射之所以能读到，是因为它拿的是 {@code draws.getClass()}（运行期子类）。
 *
 * <p><b>可关闭</b>：配置键 {@code mixin.terrainDrawProbe}（默认开）。关闭 = bridge 不再读，
 * 少一行自报；本注入点无渲染副作用。
 */
@Mixin(targets = {MixinTargets.CHUNK_SECTIONS_TO_RENDER_DRAW_INDIRECT}, remap = false)
public abstract class ChunkSectionsToRenderDrawProbeMixin implements IVkdispTerrainDrawProbe {

    /**
     * 字段本体。返回类型与原版声明<b>逐字相同</b>（{@code EnumMap<…>}，不写成 {@code Map}）：
     * {@code @Accessor} 的类型放宽与否由 applier 决定，本机没有能覆盖那条路的单测 ⇒ 不赌。
     */
    @Accessor(MixinTargets.CHUNK_SECTIONS_TO_RENDER_DRAW_GROUPS)
    public abstract EnumMap<ChunkSectionLayer,
            List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect>> vkdisp$drawGroupsPerLayerRaw();

    @Override
    public Map<ChunkSectionLayer, ? extends List<?>> vkdisp$drawGroupsPerLayer() {
        return vkdisp$drawGroupsPerLayerRaw();
    }
}
