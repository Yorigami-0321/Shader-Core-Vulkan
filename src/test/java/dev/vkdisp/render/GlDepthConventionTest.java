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
 * （矩阵那一半的判据在 `GlDepthConventionRealMatrixTest`，它先校验重建再判翻法），所以这里比较的就是
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
    @DisplayName("🔖 矩阵那一半**不在本类判**：本类的透视是自行重构造的，真判据在 RealMatrixTest")
    void matrixHalfIsJudgedAgainstRealNumbersNotThisFixture() {
        // ⛔ 此前这里有一条红灯守卫 `matrixFlipIsNotTheSameAsDepthFlip`，
        //   它用本类的 `setPerspective(FOV, ASPECT, NEAR, FAR, true)` 当比较基准，
        //   于是把「基准是自己造的、且 far 取 512（游戏真值 = 1024.001）」当成了
        //   「矩阵翻不动」—— 那条守卫拦的其实是一条**正确**的式子。已删。
        // ⇒ 矩阵那一半的全部判据在 {@code GlDepthConventionRealMatrixTest}：
        //   它先用游戏真实日志里的三个窗口深度读数**校验重建**，再判翻法。
        //   本类只保留「深度值」那一半（①②③ 三条），那部分与矩阵无关。
        Matrix4f m = zFlip();
        assertEquals(0.0F, maxElementDiff(new Matrix4f(m).mul(m), new Matrix4f()), 1e-6F,
                "M（z ← w − z）是对合 —— 这条自洽性本身**不**说明它是正确答案，见上面那段");
        Matrix4f d2 = new Matrix4f(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, -2, 1, 0, 0, 0, 1);
        assertTrue(maxElementDiff(new Matrix4f(d2).mul(d2), new Matrix4f()) > 0.5F,
                "真正要用的 D2（z ← w − 2z）不是对合；它的逆是常数矩阵 (0,0,−1/2,+1/2) 那一行");
    }
}
