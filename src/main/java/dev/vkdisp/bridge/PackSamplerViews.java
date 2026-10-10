package dev.vkdisp.bridge;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.vkdisp.VkDisp;
import dev.vkdisp.pipeline.model.SamplerDimensionPlan;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【参考调研】C1 · 全屏步（composite / deferred / final）的 sampler 视图路由
 * 0. 合规：参考对象 = 本仓库自有实现事实 —— {@link TerrainPipelineApi#bindPackGbufferUniforms}
 *    已经跑通的「按**声明类型**决定的 {@link SamplerDimensionPlan.ViewKind} 取视图」那张表，
 *    与 {@code FrameApi.chainResolver} 已经取证过的 colortex / depthtex / gaux / shadowtex 名字分派。
 *    本类不引入任何外部项目代码（本项目 MIT；{@code 07} L12 / X19 的第 0 步已过：无新参考对象）。
 * 1. 职责：把「某个 sampler 名这一帧该喂哪张图」从**按名字硬编码**（旧 {@code PipelineApi}
 *    只认 {@code gaux1}，其余 17 名一律 colorView 占位）改成**先看包声明的类型**。
 * 2. 差异点：只覆盖「{@code chainResolver} 没有分支、且类型要求特定维度」的那几类
 *    （3D / 中性材质 / 图集）；其余**照抄链侧那条已取证的路** —— 同一个名字在两条链上
 *    给出不同答案正是本仓反复吃过的那一族（{@code SamplerDimensionPlan} 的 gaux 注释原话）。
 * 3. 非显然约束：① 返回值**永不为 null**（h33 的 2702 行教训：null 一路传到 setUniform 才炸，
 *    且炸出来看不出根因）；② {@code UNSUPPORTED}（原版 26.3 不支持的 3D/数组/cube 形态）
 *    由调用方**不绑**并点名，本类不给「看起来能用」的错维度视图（静默 UB）。
 * 4. 性能：🔥 每帧 3 次全屏步各查一遍表 —— 只做 `Map` 查 + 静态视图取用，无分配、无 IO。
 *    不可绑的点名按「每名一次」节流（GAP-015 同族纪律）。
 */
final class PackSamplerViews {

    /** 已经点过名的「不可绑」名字（每名一次，热路径不刷屏）。 */
    private static final Set<String> UNBINDABLE_REPORTED = ConcurrentHashMap.newKeySet();

    private PackSamplerViews() {
    }

    /**
     * 这一帧 {@code name} 该不该绑。
     *
     * @return {@code false} = 包声明的维度我方给不出类型匹配的视图 ⇒ **不绑**（draw 会响亮抛
     *         {@code Missing uniform}），并在此点名一次
     */
    static boolean bindable(String name) {
        SamplerDimensionPlan.ViewKind kind = kindOf(name);
        if (kind != SamplerDimensionPlan.ViewKind.UNSUPPORTED) {
            return true;
        }
        if (UNBINDABLE_REPORTED.add(name)) {
            VkDisp.LOGGER.error("vkdisp: [C1/GAP-014] 全屏步 sampler '{}' 声明类型 {} 需要"
                    + " 原版 26.3 不支持的视图维度 -> **不绑定**（宁可 draw 抛 Missing uniform，"
                    + "也不喂错维度造成静默 UB）；本条每名只打一次",
                    name, declaredTypeOf(name));
        }
        return false;
    }

    /** 该 sampler 的视图（永不为 null）。 */
    static GpuTextureView resolve(String name, GpuTextureView colorView, GpuTextureView auxView) {
        SamplerDimensionPlan.ViewKind kind = kindOf(name);
        if (kind != null) {
            switch (kind) {
                case VOLUME_3D: {
                    // 🔴 旧实形的真实缺陷：lighttex0/1 是 sampler3D，却被喂了 2D 的 colorView
                    //   = 描述符维度不匹配（Vulkan UB，本机没有 validation layer ⇒ 不报错）。
                    GpuTextureView volume = VolumeStubs.view();
                    return volume != null ? volume : colorView;
                }
                case NEUTRAL_MATERIAL_2D: {
                    GpuTextureView neutral = "specular".equals(name)
                            ? NeutralMaterialMaps.specularView()
                            : NeutralMaterialMaps.normalsView();
                    return neutral != null ? neutral : colorView;
                }
                case ATLAS_2D: {
                    GpuTextureView atlas = MrtTerrainPass.blockAtlas();
                    if (atlas != null) {
                        return atlas;
                    }
                    // 图集还没就绪（资源重载早期帧）⇒ 退回本步的彩色输入，并点名一次。
                    if (UNBINDABLE_REPORTED.add("atlas:" + name)) {
                        VkDisp.LOGGER.warn("vkdisp: [C1] 全屏步 sampler '{}' 要方块图集，"
                                + "但本帧图集为 null -> 暂用本步彩色输入占位（不静默）", name);
                    }
                    return colorView;
                }
                default:
                    break;
            }
        }
        // 其余（含 colortex / depthtex / gaux / shadowtex / shadowcolor / noisetex / 未声明的超集名）
        // 走**链侧那条已取证的路**：PackTextures 真值优先 → colortex 按槽取池视图 → gaux=colortex4
        // → depthtex 走快照/代理 → shadowtex 走桩 → 兜底占位。
        return FrameApi.chainResolver(colorView).view(name);
    }

    /** 包声明的视图类别；未声明（超集里这一张包没用的名字）返回 null。 */
    private static SamplerDimensionPlan.ViewKind kindOf(String name) {
        return dev.vkdisp.pipeline.model.PackSamplerSuperset.current()
                .declared(name)
                .map(dev.vkdisp.pipeline.model.PackSamplerSuperset.Declared::kind)
                .orElse(null);
    }

    private static String declaredTypeOf(String name) {
        return dev.vkdisp.pipeline.model.PackSamplerSuperset.current()
                .declared(name)
                .map(dev.vkdisp.pipeline.model.PackSamplerSuperset.Declared::declaredType)
                .orElse("(未声明)");
    }

    /** 测试用：清掉「每名只点一次」的哨兵，避免用例之间互相吃状态（QD-03/QD-12 的复位约定）。 */
    static void resetReportedForTesting() {
        UNBINDABLE_REPORTED.clear();
    }
}
