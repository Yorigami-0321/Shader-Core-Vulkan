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
 * <p>✅ <b>投影矩阵那一半已推出（h48w，用游戏里真实的那一对矩阵）</b>：
 * {@link #glProjection} / {@link #glProjectionInverse}，做法是左乘
 * {@code D2}（第 2 行 = (0,0,−2,1)，即 {@code row_z ← row_w − 2·row_z}）。
 * 四项数值判据（{@code GlDepthConventionRealMatrixTest}）：与标准 GL {@code [-1,1]} 投影逐元素
 * 差 3.0e−9；窗口深度恰好 {@code 1 − z_engine}；x/y NDC 完全不变；包自己那条
 * {@code depth*2−1 → P⁻¹} 的路往返逐位回到 −1/−16/−128/−1024。
 *
 * <p>⛔ <b>撤回此前写在这里的一段「实测否证」</b>（原文曾断言「只改 z 行到不了 GL 投影，
 * 症结在 joml 的 {@code m32} 是 ±0.05 而不是 ±1」）。那段是<b>两个读数错误叠成的假否证</b>：
 * ① 比较基准的 far 取了 512，而由真实矩阵反解出的<b>真 far = 1024.001</b>（near = 0.05；
 * 那个 {@code 0.05000244} 恰恰就是 near，本来就该在那儿）；② 从日志读矩阵时把
 * 「数学第 N 列」当成了「第 N 行」。上面的表格数字同属那个错基准，已一并作废。
 * 🔖 保留一条仍然成立的旧结论：<b>半翻比不翻更坏</b> ——
 * {@code mrt.depthGlProxy} 目前只翻深度、矩阵一个没动，所以本类的矩阵那两个方法
 * <b>尚未接进渲染路径</b>，接线必须与深度同帧、且要先有画面侧判据。
 * 全部原始读数与推导见 {@code evidence/h48w-gap022-real-matrices.md}。
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

    /**
     * 引擎投影矩阵 → 包预期的 <b>GL {@code [-1,1]} NDC 口径</b>投影矩阵（<b>原地不改</b>，返回新矩阵）。
     *
     * <p>做法是左乘一个只改 z 行的对合式翻转矩阵 {@code D2}（第 2 行 = {@code (0,0,-2,1)}，
     * 即 {@code row_z ← row_w − 2·row_z}）。🔖 为什么是 {@code -2} 而不是 {@code -1}：
     * 包先做 {@code depth*2-1} 再乘 {@code gbufferProjectionInverse}，所以它要的是
     * <b>NDC 口径</b>的矩阵；用 {@code (0,0,-1,1)} 得到的是 {@code [0,1]}（zZeroToOne）那份，
     * 窗口深度虽然也等于 {@code 1 - z_engine}，但反解出来的视空间距离会<b>恰好差一倍</b>
     * （实测 d=1→−0.5002、16→−8.063、128→−68.27）。全部数字见
     * {@code evidence/h48w-gap022-real-matrices.md}。
     *
     * <p>⚠️ 本方法<b>尚未接进渲染路径</b>：GAP-022 的「半翻比不翻更坏」仍然成立，
     * 必须与 {@link #glProjectionInverse} 同帧一起换，且要先有画面侧判据。
     */
    public static Matrix4f glProjection(Matrix4f engineProjection) {
        return flipClipZ(engineProjection, -2.0F, 1.0F);
    }

    /**
     * 与 {@link #glProjection} 配对的逆矩阵。
     *
     * <p>🔖 复合方向与 {@link #glProjection} <b>相反</b>：{@code (D2·P)⁻¹ = P⁻¹·D2⁻¹} ——
     * {@code D2⁻¹} 作用在<b>输入</b>（NDC 向量）上，不是作用在输出上。
     * 写成「改输出列」会得到一个看着像、实际错 13 倍的矩阵（本轮实测）。
     */
    public static Matrix4f glProjectionInverse(Matrix4f engineProjectionInverse) {
        Matrix4f m = engineProjectionInverse;
        Vector4f c2 = m.transform(new Vector4f(0, 0, 1, 0), new Vector4f());
        Vector4f c3 = m.transform(new Vector4f(0, 0, 0, 1), new Vector4f());
        Matrix4f out = new Matrix4f(m);
        // D2⁻¹ 的 z 行 = (0,0,−1/2,+1/2) ⇒ 第 2 列取 −1/2·c2，第 3 列取 1/2·c2 + c3。
        out.setColumn(2, c2.mul(-0.5F, new Vector4f()));
        out.setColumn(3, c2.mul(0.5F, new Vector4f()).add(c3, new Vector4f()));
        return out;
    }

    /**
     * 把「对 clip 向量的仿射变换 {@code z ← a·z + b·w}（x/y/w 不动）」复合到矩阵上。
     *
     * <p>🔖 <b>为什么按基向量构造、而不是手搭一个 D2 去左乘</b>：joml 的
     * {@code get(row, col)} / 构造器参数序与「数学上的第几行」<b>不是</b>直觉的那个对应关系
     * —— 本轮就是先踩了一次（把日志里的 {@code rN} 当成第 N 行，于是 {@code d=1} 算出 20.0
     * 而真实读数是 0.04995361），手搭矩阵再猜左右乘等于把同一个坑再挖一遍。
     * 而「矩阵第 j 个<b>数学列</b>」有一个不依赖任何约定的定义：它就是该矩阵作用在第 j 个
     * 基向量上的<b>结果</b>。于是这里直接读列、改、再写回 —— 与 {@code transform} 和
     * 与上传给 GLSL 的那份序列化天然同口径（地形现在能画对，就是在为这条口径背书）。
     */
    private static Matrix4f flipClipZ(Matrix4f source, float zCoefficient, float wCoefficient) {
        Matrix4f out = new Matrix4f(source);
        for (int column = 0; column < 4; column++) {
            Vector4f basis = new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);
            basis.setComponent(column, 1.0F);
            Vector4f clip = source.transform(basis, new Vector4f());
            float z = wCoefficient * clip.w + zCoefficient * clip.z;
            out.setColumn(column, new Vector4f(clip.x, clip.y, z, clip.w));
        }
        return out;
    }
}
