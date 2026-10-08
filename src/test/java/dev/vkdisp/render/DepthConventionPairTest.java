package dev.vkdisp.render;
/**
 * 【自行补充】GAP-022「成对口径」的离线守卫（深度那一半 + 矩阵那一半必须一起切）
 * 0. 合规核对（第 0 步闸门，通过）：参考对象 = 本仓库自有
 *    {@link GlDepthConvention}（已证的 D2 换算）/ {@link DepthConventionPair}（本轮接线本体）/
 *    {@link OfUniformManager#putCameraMatrices}（真实调用路径）/
 *    {@code evidence/h48w-gap022-real-matrices.md} §四 那张表。
 *    期望值全部由**独立算式**算出（joml 自己的 perspective/invert，不复用被测实现的中间量）。
 *    许可证：本文件为独立编写的测试代码（MIT），零第三方代码。
 * 1. 官方/主实现：无。
 * 2. 备选：只跑 runClient 看画面 —— <b>不作为本类的证明</b>：画面能证明「生效了」，
 *    证不了「上一帧那一本没被翻两次」这种只在第二帧之后才出现的错（本项目被半翻口径烧的形态
 *    恰恰是「第一帧看着对」）。画面判据仍在主线（GAP-022 登记表）。
 * 3. 我们的差异点：测的是 {@code OfUniformManager.putCameraMatrices} 这条**产品调用路径**
 *    （纯 joml，配置值由参数传入），不是测试自己抄的一份镜像实现。
 * 4. 许可证核对结论：本项目 MIT。
 * 5. 性能基线：❄️ 冷路径单测。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.vkdisp.glsl.translate.BuiltinsBlockLayout;
import dev.vkdisp.glsl.translate.UniformInjector;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 开关开着时：深度与**每一个**交给包的投影矩阵必须同口径；开关关掉时：一个字节都不许变。
 *
 * <p>🔖 这里钉的全是本项目最贵的那一类失败：<b>不报错、画面像是对的、但两半口径不一致</b>。
 * 半翻的具体形态是 {@code depthtex0 = 1 − z}（GL 口径）配 <b>引擎</b>的
 * {@code gbufferProjectionInverse} —— 包读到的深度与它反解用的矩阵来自两套视锥。
 */
class DepthConventionPairTest {

    private static final float NEAR = 0.05F;
    private static final float FAR = 1024.001F;
    private static final Vector3f VIEW = new Vector3f(0.3F, -0.2F, 0.0F);

    private static final Path UNIFORMS = Path.of("src/main/java/dev/vkdisp/render/OfUniformManager.java");
    private static final Path PAIR = Path.of("src/main/java/dev/vkdisp/render/DepthConventionPair.java");
    private static final Path PROXY = Path.of("src/main/java/dev/vkdisp/bridge/DepthGlProxy.java");
    private static final Path CONFIG = Path.of("src/main/java/dev/vkdisp/VkDispConfig.java");

    @BeforeEach
    void clearHistory() {
        // 历史槽与自报状态都是进程级的：不清就会让用例顺序决定结果（本轮要证的正是「逐帧怎样」）。
        DepthConventionPair.resetForTest();
    }

    /** 引擎投影：原版 {@code Projection#getMatrix} 就是「near/far 互换后」调 setPerspective。 */
    private static Matrix4f engineProjection(float far, float fovDegrees) {
        return new Matrix4f().perspective((float) Math.toRadians(fovDegrees), 1.7777778F,
                far, NEAR, true);
    }

    /** 独立算出来的 GL {@code [-1,1]} 投影（标准档：near/far <b>不</b>互换）。 */
    private static Matrix4f standardGlProjection(float far, float fovDegrees) {
        return new Matrix4f().perspective((float) Math.toRadians(fovDegrees), 1.7777778F,
                NEAR, far, false);
    }

    private static BuiltinsBlockLayout layout(String... memberLines) {
        StringBuilder source = new StringBuilder();
        source.append(UniformInjector.BLOCK_HEADER).append('\n');
        source.append(UniformInjector.BLOCK_OPEN).append('\n');
        for (String line : memberLines) {
            source.append(line).append('\n');
        }
        source.append(UniformInjector.BLOCK_CLOSE).append('\n');
        BuiltinsBlockLayout parsed = BuiltinsBlockLayout.parse(source.toString());
        assertTrue(parsed.failure() == null, parsed.failure());
        return parsed;
    }

    private static float[] bytes(BuiltinsBlockLayout layout, Map<String, Object> values) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(layout.byteSize()).order(ByteOrder.nativeOrder());
        OfUniformManager.WriteStats stats = OfUniformManager.write(layout, values, buffer);
        assertEquals(layout.members().size(), stats.written(), stats.toString());
        float[] out = new float[layout.byteSize() / 4];
        for (int i = 0; i < out.length; i++) {
            out[i] = buffer.getFloat(i * 4);
        }
        return out;
    }

    private static float[] elements(Matrix4f m) {
        float[] out = new float[16];
        m.get(out);
        return out;
    }

    private static void assertSameMatrix(Matrix4f expected, Matrix4f actual, String message) {
        assertArrayEqualsSame(expected, actual, 1.0E-6F, message);
    }

    private static void assertSameMatrix(Matrix4f expected, Matrix4f actual, float eps,
            String message) {
        assertArrayEqualsSame(expected, actual, eps, message);
    }

    private static void assertArrayEqualsSame(Matrix4f expected, Matrix4f actual, float eps,
            String message) {
        float[] a = elements(expected);
        float[] b = elements(actual);
        for (int i = 0; i < 16; i++) {
            assertEquals(a[i], b[i], eps, message + " —— 元素 " + i + " 不一致");
        }
    }

    private static Map<String, Object> gatherCameraMatrices(Matrix4f projection,
            boolean worldSwitch, boolean glConvention) {
        Map<String, Object> values = new HashMap<>();
        OfUniformManager.putCameraMatrices(values, new Matrix4f().identity(), projection,
                new Vector3f(1.0F, 2.0F, 3.0F), worldSwitch, glConvention);
        return values;
    }

    private static Matrix4f matrix(Map<String, Object> values, String key) {
        Object value = values.get(key);
        assertTrue(value instanceof Matrix4f, key + " 必须是 mat4（供值形态变了）");
        return (Matrix4f) value;
    }

    // ── (a) 开关开着 ⇒ 交出去的是 GL 口径那一本，且**不是**引擎原样 ──────────────────

    @Test
    @DisplayName("🔴 开关开：gbufferProjection = glProjection(引擎矩阵)，且绝不等于引擎那一份")
    void enabledHandsOutTheGlProjection() {
        Matrix4f engine = engineProjection(FAR, 70.0F);
        Map<String, Object> values = gatherCameraMatrices(engine, true, true);

        Matrix4f handed = matrix(values, "gbufferProjection");
        assertSameMatrix(GlDepthConvention.glProjection(engine), handed,
                "开关开着时交出去的必须是 D2·P 那一本");
        assertSameMatrix(standardGlProjection(FAR, 70.0F), handed, 1.0E-4F,
                "h48w §三 判据 A：D2·P 就是同一视锥的标准 GL [-1,1] 投影（独立算式核对）");
        assertFalse(same(handed, engine),
                "开关开着还交引擎原样 = 接线根本没生效（QD-02 那一族的死开关）");
    }

    @Test
    @DisplayName("🔴 开关开：投影逆走的是 P⁻¹·D2inv —— 与正矩阵构成**真逆**（h48w 判据 C）")
    void enabledInverseIsTheTrueInverseOfTheForward() {
        Matrix4f engine = engineProjection(FAR, 70.0F);
        Map<String, Object> values = gatherCameraMatrices(engine, true, true);

        Matrix4f forward = matrix(values, "gbufferProjection");
        Matrix4f backward = matrix(values, "gbufferProjectionInverse");
        assertSameMatrix(new Matrix4f(), forward.mul(backward, new Matrix4f()), 1.0E-3F,
                "(D2·P)⁻¹ 必须等于交出去的那一份逆：不成对就是「深度一套、反解另一套」");
        assertSameMatrix(GlDepthConvention.glProjectionInverse(engine.invert(new Matrix4f())),
                backward, "逆那一半必须由 P⁻¹ 复合 D2inv，不是重新求逆、也不是改输出列");
    }

    @Test
    @DisplayName("🔴 开关开：矩阵那一半与深度那一半自洽（窗口深度 = 1 − z_engine）")
    void enabledMatrixAgreesWithTheFlippedDepth() {
        Matrix4f engine = engineProjection(FAR, 70.0F);
        Matrix4f handed = matrix(gatherCameraMatrices(engine, true, true), "gbufferProjection");
        for (float d : new float[] {NEAR, 1.0F, 16.0F, 128.0F, FAR}) {
            // DepthGlProxy 写进 depthtex* 的是 1 − z_engine；矩阵那份的 ndc 必须与之同一条式子。
            assertEquals(GlDepthConvention.glWindowDepth(GlDepthConvention.windowDepth(engine, d)),
                    (GlDepthConvention.windowDepth(handed, d) + 1.0F) / 2.0F, 1.0E-5F,
                    "d=" + d + "：深度翻了而矩阵没跟上（或反之）就在这一行露出来");
            assertEquals(2.0F * GlDepthConvention.windowDepth(engine, d) - 1.0F,
                    -GlDepthConvention.windowDepth(handed, d), 1.0E-5F,
                    "d=" + d + "：翻完的 ndc 与引擎 ndc 必须反号同幅");
        }
    }

    // ── (b) 开关关掉 ⇒ 与今天逐字节相同 ────────────────────────────────────────────

    @Test
    @DisplayName("🔴 开关关：四个投影/相机值写出来的字节与「改动前那样直接 put」逐位相同")
    void disabledIsByteIdenticalToBefore() {
        Matrix4f previousEngine = engineProjection(900.0F, 65.0F);
        Matrix4f currentEngine = engineProjection(FAR, 70.0F);

        // 今天（改动前）的写法：原样 put，逆用带行列式守卫的 invert。
        BuiltinsBlockLayout layout = layout(
                "mat4 gbufferProjection;", "mat4 gbufferProjectionInverse;",
                "mat4 gbufferPreviousProjection;", "mat4 gbufferModelView;",
                "mat4 gbufferModelViewInverse;", "vec3 cameraPosition;",
                "mat4 gbufferPreviousModelView;", "vec3 previousCameraPosition;");
        Map<String, Object> before = new HashMap<>();
        before.put("gbufferProjection", currentEngine);
        before.put("gbufferProjectionInverse", currentEngine.invert(new Matrix4f()));
        before.put("gbufferPreviousProjection", previousEngine);
        // 上一帧的视图/相机历史：先跑一次把历史推上去，两侧同序 ⇒ 只比投影那一半是否变味。
        gatherCameraMatrices(previousEngine, true, false);
        Map<String, Object> after = gatherCameraMatrices(currentEngine, false, false);
        before.put("gbufferModelView", after.get("gbufferModelView"));
        before.put("gbufferModelViewInverse", after.get("gbufferModelViewInverse"));
        before.put("cameraPosition", after.get("cameraPosition"));
        before.put("gbufferPreviousModelView", after.get("gbufferPreviousModelView"));
        before.put("previousCameraPosition", after.get("previousCameraPosition"));

        float[] oldBytes = bytes(layout, before);
        float[] newBytes = bytes(layout, after);
        assertEquals(oldBytes.length, newBytes.length);
        for (int i = 0; i < oldBytes.length; i++) {
            assertEquals(Float.floatToIntBits(oldBytes[i]), Float.floatToIntBits(newBytes[i]),
                    "关掉开关时第 " + i + " 个 float 都不许变（默认档必须与改动前逐位一致）");
        }
    }

    // ── (c) 双翻守卫 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 连续三帧：上一帧那一本永远只被翻一次（存的是引擎口径）")
    void previousMatrixIsNeverDoubleFlipped() {
        Matrix4f frame1 = engineProjection(900.0F, 65.0F);
        Matrix4f frame2 = engineProjection(FAR, 70.0F);
        Matrix4f frame3 = engineProjection(512.0F, 80.0F);

        Map<String, Object> v1 = gatherCameraMatrices(frame1, true, true);
        // 首帧（换世界/无历史）：上一帧 = 当帧 ⇒ 同样只翻一次。
        assertSameMatrix(GlDepthConvention.glProjection(frame1),
                matrix(v1, "gbufferPreviousProjection"), "首帧的 previous 应与当帧同口径同一次翻");

        Map<String, Object> v2 = gatherCameraMatrices(frame2, false, true);
        Matrix4f previousInV2 = matrix(v2, "gbufferPreviousProjection");
        assertSameMatrix(GlDepthConvention.glProjection(frame1), previousInV2,
                "第二帧的 previous 必须是 glProjection(存下来的引擎矩阵)");
        assertFalse(same(previousInV2, GlDepthConvention.glProjection(
                GlDepthConvention.glProjection(frame1))),
                "D2 不是对合（z'' = 4z − w）⇒ 一旦历史槽存了翻完的那一份，这里就会露馅");

        Map<String, Object> v3 = gatherCameraMatrices(frame3, false, true);
        assertSameMatrix(GlDepthConvention.glProjection(frame2),
                matrix(v3, "gbufferPreviousProjection"),
                "第三帧仍然只翻一次：历史槽存的必须是**引擎口径**（取+存在同一个方法里）");
    }

    @Test
    @DisplayName("🔖 关着时历史槽给的是引擎原样；换世界时上一帧与当帧对齐")
    void disabledKeepsEngineConventionAndWorldSwitchAligns() {
        Matrix4f frame1 = engineProjection(900.0F, 65.0F);
        Map<String, Object> v1 = gatherCameraMatrices(frame1, true, false);
        assertSameMatrix(frame1, matrix(v1, "gbufferPreviousProjection"),
                "关着时 previous 必须是引擎原样（首帧对齐成当帧）");

        Matrix4f frame2 = engineProjection(FAR, 70.0F);
        Map<String, Object> v2 = gatherCameraMatrices(frame2, false, false);
        assertSameMatrix(frame1, matrix(v2, "gbufferPreviousProjection"), "关着时不许偷偷翻");

        Map<String, Object> v3 = gatherCameraMatrices(frame2, true, false);
        assertSameMatrix(frame2, matrix(v3, "gbufferPreviousProjection"),
                "worldSwitch=true 时历史必须与当帧对齐（跨世界的旧相机当历史没有意义）");
    }

    @Test
    @DisplayName("🔖 同一帧内 gather 被调多次也只有一个答案：四个出口共用一份快照")
    void multipleGathersInOneFrameShareOneDecision() {
        Matrix4f engine = engineProjection(FAR, 70.0F);
        Map<String, Object> on = gatherCameraMatrices(engine, true, true);
        assertTrue(same(matrix(on, "gbufferProjection"),
                GlDepthConvention.glProjection(engine)), "开：正矩阵那一本");
        assertTrue(same(matrix(on, "gbufferPreviousProjection"),
                GlDepthConvention.glProjection(engine)), "开：上一帧那一本也必须同口径");
        Map<String, Object> off = gatherCameraMatrices(engine, true, false);
        assertTrue(same(matrix(off, "gbufferProjection"), engine), "关：原样");
        assertTrue(same(matrix(off, "gbufferPreviousProjection"), engine), "关：也原样");
    }

    @Test
    @DisplayName("🔖 双翻必须**看得出来**（D2 非对合：z'' = 4z − w）—— 上面那条守卫不是空话")
    void doubleFlipIsDistinguishableFromSingleFlip() {
        Matrix4f engine = engineProjection(FAR, 70.0F);
        Matrix4f once = GlDepthConvention.glProjection(engine);
        Matrix4f twice = GlDepthConvention.glProjection(once);
        assertFalse(same(once, twice),
                "若翻两次与翻一次逐位相同，那「有没有双翻」这条判据在数值上就不可检，"
                        + "previousMatrixIsNeverDoubleFlipped 会变成永远通过的空话");
        assertFalse(same(once, engine), "翻一次必须看得见（否则整个开关都是空转）");
        // 引擎口径下的 z 行系数 a/b 摊出来就是 4z − w 那一步：钉住代数形状，改 D2 时这里会红。
        float[] onceZ = {once.get(2, 0), once.get(2, 1), once.get(2, 2), once.get(2, 3)};
        float[] twiceZ = {twice.get(2, 0), twice.get(2, 1), twice.get(2, 2), twice.get(2, 3)};
        assertNotEquals(onceZ[2], twiceZ[2], 1.0E-4F, "z 行系数必须被改第二次");
    }

    // ── (d) 自报 ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 自报行在**两种状态**下都存在；缺行不许与「关」同义")
    void selfReportExistsInBothStates() {
        List<String> on = new ArrayList<>();
        DepthConventionPair.reportThrottled(true, 1, on::add);
        assertEquals(1, on.size(), "开着的状态必须自报一条");

        List<String> off = new ArrayList<>();
        DepthConventionPair.reportThrottled(false, 2, off::add);
        assertEquals(1, off.size(), "关着的状态也必须自报一条（不许靠「没有行」表达关）");

        String gl = on.get(0);
        assertTrue(gl.startsWith("vkdisp: [GAP-022] depth convention = GL"), gl);
        assertTrue(gl.contains("depthtex=1-z") && gl.contains("gbufferProjection=D2·P"),
                "自报必须把「深度那一半」和「矩阵那一半」都写进同一行：只写一半就是本轮要修的形态");
        assertTrue(gl.contains("previous"), "上一帧那一本也必须被点名（双翻就藏在这里）");
        assertTrue(gl.contains("frame=1"), gl);

        String engine = off.get(0);
        assertTrue(engine.startsWith("vkdisp: [GAP-022] depth convention = ENGINE"), engine);
        assertTrue(engine.contains("reverse Z") && engine.contains("原样"), engine);
        assertTrue(engine.contains("frame=2"), engine);
        assertNotEquals(gl, engine);
    }

    @Test
    @DisplayName("🔖 自报节流：同状态每 300 帧一次；状态一变立刻补一条（热路径不许每帧一条）")
    void selfReportIsThrottledButReactsToChange() {
        List<String> lines = new ArrayList<>();
        DepthConventionPair.reportThrottled(false, 10, lines::add);
        assertEquals(1, lines.size(), "首条必打");
        DepthConventionPair.reportThrottled(false, 10 + DepthConventionPair.REPORT_EVERY_FRAMES - 1,
                lines::add);
        assertEquals(1, lines.size(), "没到周期不许重复打（gather 每帧最多 4 次）");
        DepthConventionPair.reportThrottled(false, 10 + DepthConventionPair.REPORT_EVERY_FRAMES,
                lines::add);
        assertEquals(2, lines.size(), "到周期必须再报一次，否则开着/关着只有第一帧有证据");
        DepthConventionPair.reportThrottled(true, 11 + DepthConventionPair.REPORT_EVERY_FRAMES,
                lines::add);
        assertEquals(3, lines.size(), "口径一变必须立刻自报（换档不能被节流藏住）");
    }

    // ── 接线守卫：实现了但没接上 = 本项目最贵的失败（QD-02 / h33） ──────────────────

    @Test
    @DisplayName("🔴 矩阵那一半必须读**同一个**开关，且 gather 每帧只读一次")
    void matrixHalfReadsTheSameSwitchOnce() {
        String uniforms = readOrSkip(UNIFORMS);
        String proxy = readOrSkip(PROXY);
        assertTrue(uniforms.contains("VkDispConfig.MRT_DEPTH_GL_PROXY.get()"),
                "矩阵那一半必须由 mrt.depthGlProxy 决定 —— 另立一个开关就是「两半各自漂移」");
        assertTrue(proxy.contains("VkDispConfig.MRT_DEPTH_GL_PROXY.get()"),
                "深度那一半读的必须是同一个字段（成对的前提是同一个真源）");
        assertEquals(1, countCode(uniforms, "VkDispConfig.MRT_DEPTH_GL_PROXY.get()"),
                "一帧内只许读一次配置：读两次 = 中途热改会出现「深度翻了矩阵没翻」");
        assertTrue(uniforms.contains("DepthConventionPair.forFrame("),
                "开关值必须交给一个**共用快照**，而不是散在四个 put 里各判一次");
        assertTrue(uniforms.contains("convention.forward(projection)")
                        && uniforms.contains("convention.backward(inverted(projection))"),
                "gbufferProjection / gbufferProjectionInverse 必须走出口");
        assertTrue(uniforms.contains("convention.forward(convention.takePreviousEngine("),
                "gbufferPreviousProjection 必须走**同一个** forward 出口（两个三元式各抄一遍=本轮要修的）");
        assertFalse(uniforms.contains("values.put(\"gbufferProjection\", projection)"),
                "不许再有把引擎原样交出去的那一行");
        assertFalse(uniforms.contains("convention.backward(projection)"),
                "backward 吃的是**引擎口径的逆**（h48w 判据 C 的复合方向）；直接喂投影矩阵就是正矩阵");
        assertFalse(uniforms.contains("private static org.joml.Matrix4f previousProjection"),
                "上一帧只能有一份表示（引擎口径），搬进 DepthConventionPair 后这里不许再留历史槽");
    }

    @Test
    @DisplayName("🔴 自报必须在 debugLog 门**之外**：关档也要留痕")
    void selfReportIsNotGatedOnDebugLog() {
        String uniforms = readOrSkip(UNIFORMS);
        int report = uniforms.indexOf("DepthConventionPair.reportThrottled(");
        int debugGate = uniforms.indexOf("if (dev.vkdisp.VkDispConfig.DEBUG_LOG.get()");
        assertTrue(report > 0, "接线了就必须自报（判据对象不声明自己是谁 = h48 §二十二 那一族）");
        assertTrue(debugGate > 0, "debugLog 那道门还在（守卫的相对位置前提）");
        assertTrue(report < debugGate,
                "自报不许排在 DEBUG_LOG 门里 —— 那样 debugLog=false 时「没有这行」会被读成「开关是关的」");
        assertTrue(uniforms.contains("dev.vkdisp.VkDisp.LOGGER::info"),
                "生产侧的 sink 必须是 LOGGER（本类不加载 VkDisp，所以这条只能按接线文本证）");
        String pair = readOrSkip(PAIR);
        assertTrue(pair.contains("sink.accept(reportLine("),
                "自报文本由 reportLine 出本体 ⇒ 单测能钉它，日志侧只换 sink");
    }

    @Test
    @DisplayName("🔖 默认档不许被顺手改：mrt.depthGlProxy 仍然是 false")
    void switchStillDefaultsToOff() {
        String config = readOrSkip(CONFIG);
        assertTrue(config.contains("define(\"mrt.depthGlProxy\", false)"),
                "本轮接线不附带改默认档（画面判据还没跑，GAP-022 登记表那条要求先于默认生效）");
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private static boolean same(Matrix4f a, Matrix4f b) {
        float[] x = elements(a);
        float[] y = elements(b);
        for (int i = 0; i < 16; i++) {
            if (Float.floatToIntBits(x[i]) != Float.floatToIntBits(y[i])) {
                return false;
            }
        }
        return true;
    }

    private static String readOrSkip(Path path) {
        assumeTrue(Files.exists(path), "工程文件缺失: " + path);
        try {
            return Files.readString(path);
        } catch (java.io.IOException e) {
            throw new AssertionError("读取失败: " + path, e);
        }
    }

    /** 只统计非注释行（javadoc 会引用代码字样，全文计数会把守卫变成永真的空话）。 */
    private static int countCode(String text, String literal) {
        int count = 0;
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                continue;
            }
            count += line.split(java.util.regex.Pattern.quote(literal), -1).length - 1;
        }
        return count;
    }
}
