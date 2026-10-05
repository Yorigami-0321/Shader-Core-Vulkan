package dev.vkdisp.pipeline.model;

import java.util.List;

/**
 * 后处理绑定组的 sampler **超集**（单点真源）。
 *
 * <p>🔖 为什么放 pipeline.model 而不是 bridge：链的**编排期**（pack 层）就要用它 ——
 * 一个 pass 声明了超集之外的 sampler 时，正确动作是「不进链 + WARN」，
 * 而不是运行期被 validateDraw 抛 {@code Unable to find shader defined uniform}
 * （那会炸的是**整帧**，不是那一步）。编排期与绑定期读同一份清单 ⇒ 两侧永不漂移
 * （QD-02 / X42 那一族的机制封堵）。bridge 侧 {@code PipelineApi} 只是消费它。
 *
 * <p>换包扩充按同一口径：先扫包程序的 sampler 名闭包，再补进这份清单（P4.2 切包回归登记）。
 */
public final class PostSamplerSuperset {

    /** 布局条目（顺序 = 绑定顺序；SPIR-V 未引用的条目无害，反向缺失才致命）。 */
    public static final List<String> NAMES = List.of(
            "colortex0", "colortex1", "colortex2", "colortex3", "colortex4",
            "colortex5", "colortex6", "colortex7", "colortex8", "colortex9",
            "depthtex0", "depthtex1", "depthtex2", "noisetex",
            "shadowcolor0", "shadowtex0", "shadowtex1",
            "gaux1", "lighttex0", "lighttex1",
            "vxDepthTexOpaque", "vxDepthTexTrans",
            "dhDepthTex0", "dhDepthTex1");

    /**
     * {@code names} 是否全部落在超集里。
     *
     * <p>🔖 {@code InSampler} 豁免：它是**每条管线都单独登记**的输入采样器
     * （{@code PipelineApi.SAMPLER_UNIFORM}），不属于这份「包自由 sampler」超集。
     */
    public static boolean containsAll(List<String> names) {
        for (String name : names) {
            if (name.equals("InSampler")) {
                continue;
            }
            if (!NAMES.contains(name)) {
                return false;
            }
        }
        return true;
    }

    private PostSamplerSuperset() {}
}
