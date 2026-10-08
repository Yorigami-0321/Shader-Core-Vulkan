package dev.vkdisp.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GAP-022 的口径换算：把已证的那一半钉住，把被否证的那一半钉成红灯守卫。
 *
 * <p>投影矩阵一律按引擎自己的调用形状构造：{@code Projection.getMatrix} =
 * {@code setPerspective(fov, aspect, zFar, zNear, zZeroToOne=true)} —— near/far 互换，
 * 这就是反向 Z 的全部来源。自己另造一份矩阵来「验」是本项目刚踩过的坑
 * （见 {@link #matrixFlipIsNotTheSameAsDepthFlip} 的注释），所以这里比较的就是
 * 引擎那两种调用本身。
 */
class GlDepthConventionTest {

    private static final float NEAR = 0.05F;   // Camera.PROJECTION_Z_NEAR
    private static final float FAR = 512.0F;
    private static final float FOV = (float) Math.toRadians(70.0);
    private static final float ASPECT = 854.0F / 480.0F;

    /** 引擎给包的投影：反向 Z、窗口深度落在 [0,1]。 */
    private static Matrix4f engineProjection() {
        return new Matrix4f().setPerspective(FOV, ASPECT, FAR, NEAR, true);
    }

    /** 同一条透视按 GL 口径（near/far 不交换）构造 = 包预期的深度分布。 */
    private static Matrix4f glProjection() {
        return new Matrix4f().setPerspective(FOV, ASPECT, NEAR, FAR, true);
    }

    /** 只改 z 行（z ← w − z）、w 行不动的那条翻转矩阵。 */
    private static Matrix4f zFlip() {
        return new Matrix4f(
                1.0F, 0.0F, 0.0F, 0.0F,
                0.0F, 1.0F, 0.0F, 0.0F,
                0.0F, 0.0F, -1.0F, 1.0F,
                0.0F, 0.0F, 0.0F, 1.0F);
    }

    private static float maxElementDiff(Matrix4f a, Matrix4f b) {
        float worst = 0.0F;
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                worst = Math.max(worst, Math.abs(a.get(r, c) - b.get(r, c)));
            }
        }
        return worst;
    }

    @Test
    @DisplayName("前提要先成立：引擎口径是近=1.0、远=0.0（反向 Z）")
    void engineConventionIsReversed() {
        Matrix4f p = engineProjection();
        assertEquals(1.0F, GlDepthConvention.windowDepth(p, NEAR), 1e-5F, "近平面应为 1.0");
        assertEquals(0.0F, GlDepthConvention.windowDepth(p, FAR), 1e-5F, "远平面（天空）应为 0.0");
    }

    @Test
    @DisplayName("已证：1 − z_engine 与 GL 口径窗口深度逐点相等")
    void flipEqualsGlDepthPointwise() {
        Matrix4f engine = engineProjection();
        Matrix4f gl = glProjection();
        for (float d : new float[] {NEAR, 1.0F, 16.0F, 128.0F, FAR}) {
            assertEquals(GlDepthConvention.windowDepth(gl, d),
                    GlDepthConvention.glWindowDepth(GlDepthConvention.windowDepth(engine, d)),
                    1e-5F, "d=" + d + " 处 1−z_engine 应等于 z_gl");
        }
    }

    @Test
    @DisplayName("已证：翻完之后包里的天空阈值站对边（包用 z == 1.0 认天空）")
    void skyThresholdLandsOnRightSide() {
        float sky = GlDepthConvention.glWindowDepth(
                GlDepthConvention.windowDepth(engineProjection(), FAR));
        float ground = GlDepthConvention.glWindowDepth(
                GlDepthConvention.windowDepth(engineProjection(), 4.0F));
        assertEquals(1.0F, sky, 1e-4F,
                "deferred1.glsl:337 的 isSky = (z == 1.0) 必须只在天空像素成立");
        assertTrue(ground < 1.0F - 1e-3F,
                "地表深度必须明显小于 1.0，否则包的 z < 1.0 几何分支进不去");
    }

    @Test
    @DisplayName("守卫（红灯）：矩阵不能照 z 行那条式子翻——翻法未推出，别复用")
    void matrixFlipIsNotTheSameAsDepthFlip() {
        // 我曾以为 zFlip() 乘上去就得到 GL 投影。实测否证：joml 的 zZeroToOne 分支把
        // m32 设成约 ±0.05 而不是 ±1 ⇒ w_clip 一起变号，只改 z 行到不了 GL 投影。
        // 这条断言把那条错路钉住：以后谁再用同样的式子翻矩阵，这里就红。
        float diff = maxElementDiff(
                new Matrix4f(zFlip()).mul(engineProjection()), glProjection());
        assertTrue(diff > 0.5F,
                "maxDiff=" + diff + " 若变得很小，说明 m32 行为变了（引擎/joml 升级）"
                        + "⇒ 矩阵那一半可以重新推导，同时本条要改写");
    }

    @Test
    @DisplayName("M 自身仍是对合（只说明式子自洽，不说明它是正确答案）")
    void flipMatrixIsInvolutionButThatProvesNothingAlone() {
        Matrix4f m = zFlip();
        assertEquals(0.0F, maxElementDiff(new Matrix4f(m).mul(m), new Matrix4f()), 1e-6F,
                "M·M 必须是单位阵");
    }
}
