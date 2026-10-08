package dev.vkdisp.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GAP-022 矩阵那一半 —— <b>钉在「游戏里真实的那一对矩阵」上</b>（h48w 取证）。
 *
 * <p>🔖 为什么单独立一个类、而不是往 {@code GlDepthConventionTest} 里加一条：
 * 那个类的所有期望值都是从<b>我自己重构造</b>的透视矩阵算出来的，而上一轮正是这样把
 * 「比较基准取错 far（用了 512，真值 1024.001）」当成了「矩阵翻不动」，
 * 还顺手钉了一条<b>红灯守卫</b>去拦一条其实正确的式子。
 * ⇒ 本类的每一条都先<b>用真实读数校验重建</b>，再判翻法：
 *   真实矩阵 = {@code evidence/h48w-gap022-real-matrices.md} 里
 *   {@code [GAP-022/matrix] frame=300 inWorld=true} 那一行（iso 车道、真·包默认档）。
 */
class GlDepthConventionRealMatrixTest {

    /** 由真实矩阵的 m00/m11 反出来的视口比与竖直 FOV（m11 = 1/tan(fov/2)，m00 = m11/aspect）。 */
    private static final float Y_SCALE = 0.9826973F;
    private static final float X_SCALE = 0.55233574F;
    private static final float ASPECT = Y_SCALE / X_SCALE;
    private static final float FOV_Y = (float) (2.0 * Math.atan(1.0 / Y_SCALE));
    /** 由真实矩阵反解出的真视锥（🔴 不是 512）。 */
    private static final float NEAR = 0.05F;
    private static final float FAR = 1024.001F;

    /** 日志里逐字打出来的三个窗口深度读数 —— 用来证明「我重建的矩阵就是游戏那一个」。 */
    private static final float REAL_DEPTH_AT_1 = 0.04995361F;
    private static final float REAL_DEPTH_AT_16 = 0.0030763221F;
    private static final float REAL_DEPTH_AT_128 = 3.4181355E-4F;

    /** 引擎矩阵：原版 {@code Projection.getMatrix} 就是「near/far 互换后」调 setPerspective。 */
    private static Matrix4f engineProjection() {
        return new Matrix4f().perspective(FOV_Y, ASPECT, FAR, NEAR, true);
    }

    @Test
    @DisplayName("先证明重建=真实：三个已知距离上的窗口深度逐位对上日志读数")
    void reconstructionMatchesTheGamesRealReadings() {
        Matrix4f p = engineProjection();
        assertEquals(REAL_DEPTH_AT_1, GlDepthConvention.windowDepth(p, 1.0F), 1e-6F,
                "d=1 的读数对不上 ⇒ 重建的不是游戏那一份，后面的判据全部无效");
        assertEquals(REAL_DEPTH_AT_16, GlDepthConvention.windowDepth(p, 16.0F), 1e-6F, "d=16");
        assertEquals(REAL_DEPTH_AT_128, GlDepthConvention.windowDepth(p, 128.0F), 1e-7F, "d=128");
        // 反向 Z 这个前提也要在真实矩阵上成立，而不是只在我方的假设里
        assertTrue(GlDepthConvention.windowDepth(p, NEAR) > 0.999F,
                "引擎近端面应当≈1.0，实际 " + GlDepthConvention.windowDepth(p, NEAR));
        assertTrue(GlDepthConvention.windowDepth(p, FAR) < 1e-3F,
                "引擎远平面应当≈0.0，实际 " + GlDepthConvention.windowDepth(p, FAR));
    }

    @Test
    @DisplayName("🔖 D2（row_z ← row_w − 2·row_z）翻出来的矩阵就是标准 GL [-1,1] 投影")
    void glProjectionEqualsStandardGlForwardZMatrix() {
        Matrix4f flipped = GlDepthConvention.glProjection(engineProjection());
        Matrix4f expected = new Matrix4f().perspective(FOV_Y, ASPECT, NEAR, FAR, false);
        assertEquals(0.0F, maxElementDiff(flipped, expected), 1e-5F,
                "翻完必须等于「同一视锥的标准 GL 投影」，否则包里的矩阵数学整体错位");
    }

    @Test
    @DisplayName("翻完之后：GPU 写进 depthtex 的窗口深度 = 1 − z_engine，且 x/y NDC 一个都不许动")
    void depthFlipsAndGeometryIsUntouched() {
        Matrix4f engine = engineProjection();
        Matrix4f flipped = GlDepthConvention.glProjection(engine);
        for (float d : new float[] {NEAR, 0.5F, 1.0F, 16.0F, 128.0F, 512.0F, FAR}) {
            // 🔖 翻完那份是 **[-1,1] NDC 口径** ⇒ 它的 clip.z/clip.w 是 ndc，不是窗口深度；
            //    两者关系正是包自己写死的那一步：window = (ndc + 1) / 2。
            float ndc = GlDepthConvention.windowDepth(flipped, d);
            assertEquals(GlDepthConvention.glWindowDepth(GlDepthConvention.windowDepth(engine, d)),
                    (ndc + 1.0F) / 2.0F, 1e-5F,
                    "d=" + d + "：深度那一半与矩阵那一半必须自洽（差 2 倍就是 [0,1] 那份的症状）");
            assertEquals(2.0F * GlDepthConvention.windowDepth(engine, d) - 1.0F, -ndc, 1e-5F,
                    "d=" + d + "：翻完的 ndc 必须与引擎 ndc 反号同幅（近→−1、远→+1）");
            Vector4f a = engine.transform(0.3F, -0.2F, -d, 1.0F, new Vector4f());
            Vector4f b = flipped.transform(0.3F, -0.2F, -d, 1.0F, new Vector4f());
            assertEquals(a.x / a.w, b.x / b.w, 1e-5F, "d=" + d + " 的 x NDC 不许变");
            assertEquals(a.y / a.w, b.y / b.w, 1e-5F, "d=" + d + " 的 y NDC 不许变");
        }
        assertEquals(-1.0F, GlDepthConvention.windowDepth(flipped, NEAR), 1e-4F, "近端面 ndc 应为 −1");
        assertEquals(1.0F, GlDepthConvention.windowDepth(flipped, FAR), 1e-4F, "远平面 ndc 应为 +1");
    }

    @Test
    @DisplayName("🔴 包口径往返必须回到真距离 —— 这条就是 (0,0,-1,1) 那份 [0,1] 矩阵露馅的地方")
    void packRoundTripRecoversViewDepth() {
        Matrix4f engine = engineProjection();
        Matrix4f flipped = GlDepthConvention.glProjection(engine);
        Matrix4f inverse = GlDepthConvention.glProjectionInverse(engine.invert(new Matrix4f()));
        assertEquals(0.0F, maxElementDiff(new Matrix4f(flipped).mul(inverse), new Matrix4f()), 1e-4F,
                "(D2·P)⁻¹ 必须等于 P⁻¹·D2⁻¹ ⇒ 配对成立，不需要重新求逆");

        for (float d : new float[] {1.0F, 16.0F, 128.0F}) {
            // 包做的事：读窗口深度 → *2-1 当 NDC → 乘 gbufferProjectionInverse → 透视除
            float window = GlDepthConvention.glWindowDepth(GlDepthConvention.windowDepth(engine, d));
            Vector4f ndc = flipped.transform(0.0F, 0.0F, -d, 1.0F, new Vector4f());
            Vector4f back = inverse.transform(
                    ndc.x / ndc.w, ndc.y / ndc.w, 2.0F * window - 1.0F, 1.0F, new Vector4f());
            assertEquals(-d, back.z / back.w, 1e-2F * d,
                    "d=" + d + " 反解不回来 = 矩阵口径与包的 depth*2-1 不匹配（[0,1] 那份会差整一倍）");
        }
    }

    private static float maxElementDiff(Matrix4f a, Matrix4f b) {
        float worst = 0.0F;
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                worst = Math.max(worst, Math.abs(a.get(row, col) - b.get(row, col)));
            }
        }
        return worst;
    }
}
