package dev.vkdisp.shadow;
/**
 * 【参考调研】P3.1 光空间列表行为测试
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/08-TESTING.md §5（shadow 检查点「光空间列表非空」）、
 *    docs/04-SPEC.md §3.2（shadowModelView/shadowProjection 双矩阵语义）、
 *    docs/18-PARALLEL.md §5 P3.1 完整块（列表结构 / 零向量显式拒绝 / 数值与旧链逐位等价）
 *    与 docs/07-CONSTRAINTS.md T11（降级必须显式）—— 全部为仓库内文档事实，不受版权保护。
 *    外部候选 Iris / OptiFine 源码（GPL）→ 按禁止处理（X21），零代码并入。
 *    → 能否并入本项目（MIT）：可以（用例全自造，断言数值独立重算）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：无外部实现可参考（原版 26.3 无阴影贴图系统，X9），按本项目契约自测。
 * 2. 备选：① 只断言 size>0 —— 否决（还得钉死矩阵数值与旧验收链等价，否则列表化改了画面没人知道）；
 *    ② 只跑 runClient —— 否决（毫秒级冷路径分支覆盖不了零向量/非规数/防御拷贝，X12 边界用例见 18-PARALLEL §9）。
 * 3. 我们的差异点：矩阵期望值在测试里**独立重新计算**（ortho().lookAt() 旧链），不复用被测代码的常量拼装。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，零代码并入（07-CONSTRAINTS X19-X21）。
 * 5. 性能基线：❄️ 冷路径单测（毫秒级），无性能断言（X14）。
 */

import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LightSpaceList}（P3.1 ② 光空间列表）行为测试。
 *
 * <p>断言三件事（18-PARALLEL §5 P3.1 ②）：列表**永不为空**；矩阵与已验收的 P3.1 基础
 * {@code ortho(zZeroToOne)+lookAt} 链**数值等价**；零向量 / 非规数方向**显式拒绝**（T11）。
 */
class LightSpaceListTest {

    /** 与 FrameApi 固定占位方向一致的测试输入（单位化后应原样保留）。 */
    private static final Vector3f PLACEHOLDER_DIR = new Vector3f(0.6F, -1.0F, 0.45F).normalize();

    private static final float EPS = 1.0e-6F;

    // -------------------------------------------------------------- 非空 + 结构

    @Test
    void buildAlwaysReturnsNonEmptySingleCascadeList() {
        List<LightSpaceList.Entry> list = LightSpaceList.build(PLACEHOLDER_DIR);

        assertEquals(1, list.size(), "08-TESTING §5：光空间列表必须非空（当前单级联）");
        LightSpaceList.Entry first = list.get(0);
        assertEquals(0, first.cascade(), "首级联序号从 0 起");
        assertNotNull(first.shadowModelView());
        assertNotNull(first.shadowProjection());
        assertNotNull(first.lightTravelDirection());
        assertEquals(0.1F, first.near(), EPS, "近裁剪面与已验收基础一致");
        assertEquals(8.0F, first.far(), EPS, "远裁剪面与已验收基础一致");
    }

    @Test
    void buildReturnsUnmodifiableList() {
        List<LightSpaceList.Entry> list = LightSpaceList.build(PLACEHOLDER_DIR);

        assertThrows(UnsupportedOperationException.class, () -> list.clear(),
                "列表不许被调用方改写（懒缓存在 bridge 共享）");
    }

    // -------------------------------------------------------------- 数值等价（独立重算）

    @Test
    void viewProjectionMatchesLegacyChainedOrthoLookAt() {
        LightSpaceList.Entry first = LightSpaceList.build(PLACEHOLDER_DIR).get(0);

        // 期望值 = P3.1 基础旧链独立重算（不复用被测类的常量拼装方式）：
        Vector3f eye = new Vector3f(PLACEHOLDER_DIR).negate().mul(4.0F);
        Matrix4f expected = new Matrix4f()
                .ortho(-1.2F, 1.2F, -1.0F, 1.0F, 0.1F, 8.0F, true)
                .lookAt(eye, new Vector3f(0.0F, 0.0F, 0.0F), new Vector3f(0.0F, 1.0F, 0.0F));
        Matrix4f actual = first.viewProjection(new Matrix4f());

        assertTrue(expected.equals(actual, EPS),
                () -> "uLight = P₀ × V₀ 必须与旧链逐位等价，实际=\n" + actual);
        // 分矩阵直接相乘（不经 viewProjection）也必须同结果 —— P/V 分开存不改变合成语义。
        Matrix4f manual = new Matrix4f(first.shadowProjection()).mul(first.shadowModelView());
        assertTrue(manual.equals(actual, EPS), "P × V 手工合成与 viewProjection 一致");
    }

    @Test
    void projectionUsesZZeroToOneAndExactExtents() {
        LightSpaceList.Entry first = LightSpaceList.build(PLACEHOLDER_DIR).get(0);
        Matrix4f expected = new Matrix4f().ortho(-1.2F, 1.2F, -1.0F, 1.0F, 0.1F, 8.0F, true);

        assertTrue(expected.equals(first.shadowProjection(), EPS),
                () -> "投影必须是 zZeroToOne 正交（GL 约定会裁掉近半几何，P3.1 实测），实际=\n"
                        + first.shadowProjection());
    }

    @Test
    void lightTravelDirectionIsStoredNormalizedAndDefensivelyCopied() {
        Vector3f raw = new Vector3f(0.6F, -1.0F, 0.45F); // 非单位输入
        LightSpaceList.Entry first = LightSpaceList.build(raw).get(0);

        raw.zero(); // 调用方事后改写输入不许污染缓存条目
        Vector3f stored = first.lightTravelDirection();
        assertEquals(1.0F, stored.length(), EPS, "方向必须单位化存储");
        assertTrue(stored.equals(PLACEHOLDER_DIR, EPS),
                () -> "归一化方向应与占位方向一致，实际=" + stored);
    }

    // -------------------------------------------------------------- 显式拒绝（T11）

    @Test
    void zeroDirectionThrowsExplicitly() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> LightSpaceList.build(new Vector3f(0.0F, 0.0F, 0.0F)));
        assertTrue(ex.getMessage().contains("零向量"),
                () -> "错误信息必须点名原因（T11 静默失败禁令），实际: " + ex.getMessage());
    }

    @Test
    void nullDirectionThrowsExplicitly() {
        assertThrows(IllegalArgumentException.class, () -> LightSpaceList.build(null));
    }

    @Test
    void nonFiniteDirectionThrowsExplicitly() {
        assertThrows(IllegalArgumentException.class,
                () -> LightSpaceList.build(new Vector3f(Float.NaN, 0.0F, 0.0F)));
        assertThrows(IllegalArgumentException.class,
                () -> LightSpaceList.build(new Vector3f(Float.POSITIVE_INFINITY, 0.0F, 0.0F)));
    }

    @Test
    void nearlyZeroDirectionThrowsExplicitly() {
        // 低于 MIN_DIR_LENGTH_SQUARED 的向量归一化会放大噪声 —— 与零向量同样拒绝。
        assertThrows(IllegalArgumentException.class,
                () -> LightSpaceList.build(new Vector3f(1.0e-8F, 0.0F, 0.0F)));
    }
}
