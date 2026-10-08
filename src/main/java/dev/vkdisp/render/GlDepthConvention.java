package dev.vkdisp.render;
/**
 * 【自行补充】GAP-022 · 引擎反向 Z 与包预期 GL 口径之间的关系 —— <b>本类只装已证的那一半</b>。
 *
 * <p><b>已证 ✅</b>：窗口<b>值</b>的换算就是 {@code z_gl = 1 − z_engine}。依据两条源码事实
 * （26.3.0.51-beta，逐字核实）：
 * <ul>
 *   <li>{@code net/minecraft/client/renderer/Projection.java#getMatrix}：
 *       {@code float near = this.zFar; float far = this.zNear;} 之后才 {@code setPerspective(...)}
 *       ⇒ near/far 是<b>互换</b>的；</li>
 *   <li>{@code VulkanDevice:91-95} 给 {@code DeviceInfo.isZZeroToOne}（第 4 个分量，声明见
 *       {@code DeviceInfo.java:12}）传 {@code true} ⇒ 窗口深度落在 [0,1]。</li>
 * </ul>
 * 合起来 ⇒ 近平面 = 1.0、远平面（天空）= 0.0（与 {@code 07-CONSTRAINTS.md} X34 一致）；
 * 而按 GL 口径（near/far 不交换）算同一组透视系数，逐点满足 {@code z_gl = 1 − z_engine}
 * （{@code GlDepthConventionTest}：zNear=0.05 / zFar=512，5 个距离上差 &lt; 1e−7）。
 *
 * <p>🔴 <b>未证，别顺手拿走</b>：<b>投影矩阵</b>能不能同样翻。我按
 * {@code P_gl = M·P_engine}（{@code M} 第 2 行 = (0,0,−1,1)，即 {@code z ← w − z}）实现过，
 * <b>实测否证</b>（h48p，joml 1.10.9 逐元素比）：
 * <pre>
 *   引擎 setPerspective(fov, asp, zFar, zNear, true)  row2=(0,0, 9.7666e-5, -1.0)        row3=(0,0, +0.050004885, 0)
 *   GL   setPerspective(fov, asp, zNear, zFar, true)  row2=(0,0, -1.0000976, -1.0)       row3=(0,0, -0.050004885, 0)
 *   M·引擎                                           row2=(0,0, -9.7666e-5, -0.9999023)  ← 与 GL 差 1.0
 * </pre>
 * 症结在 {@code m32}：joml 的 {@code zZeroToOne} 分支给的不是 ±1 而是 ≈ ±0.05，
 * 于是 {@code w_clip} 一起变号 ⇒ 只改 z 行、不动 w 行的那条翻转<b>到不了</b> GL 投影。
 *
 * <p>⇒ 落地顺序：<b>先只翻深度</b>（深度代理那一半）；矩阵那一半另起推导，且必须用
 * <b>游戏里真实</b>的 {@code cameraState.projectionMatrix} 与 {@code gbufferProjectionInverse}
 * 做配对检验 —— 自己重构造一份矩阵来验是行不通的（我这轮的往返检查正是这样自毁的）。
 */
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** 纯数值/矩阵，无 GPU、无原版类型 ⇒ 可单测。 */
public final class GlDepthConvention {

    private GlDepthConvention() {
    }

    /**
     * 引擎窗口深度 → 包预期的 GL 窗口深度。✅ 逐点已证（差 &lt; 1e−7）。
     *
     * <p>用途：GAP-022 的深度代理把 {@code depthtex0} 写成这个值，包里的
     * {@code isSky = z == 1.0} / {@code hand = z < 0.56} 那一系分支才会站对边。
     */
    public static float glWindowDepth(float engineWindowDepth) {
        return 1.0F - engineWindowDepth;
    }

    /** 观察距离 d（眼睛看向 −z）在给定投影下的窗口深度 —— <b>只用于测试与判读</b>，不在渲染路径上。 */
    public static float windowDepth(Matrix4f projection, float d) {
        Vector4f clip = projection.transform(0.0F, 0.0F, -d, 1.0F, new Vector4f());
        return clip.z / clip.w;
    }
}
