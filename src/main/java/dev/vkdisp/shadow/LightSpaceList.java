package dev.vkdisp.shadow;
/**
 * 【参考调研】P3.1 光空间列表（自建 ShadowPass 级联条目，08-TESTING §5 验收对象）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §3.2（shadowModelView/shadowProjection 双矩阵语义）/
 *    §3.4（ShadowPass.java 注释「多级联」）与 docs/08-TESTING.md §5（shadow 环节检查点：
 *    「光空间列表非空；阴影贴图内容合理」）—— 仓库内文档事实，不受版权保护；
 *    ② 本项目已验收的光空间矩阵构造（ortho zZeroToOne + lookAt，18-PARALLEL §5 P3.1 基础块）。
 *    外部候选 Iris / OptiFine 源码（GPL）→ 按禁止处理（07-CONSTRAINTS X21），不读不抄。
 *    **X9 前提修正**：对合并 jar 全部 .java 源检索 lightSpace/shadowMatrix/shadowProjection/
 *    shadowModelView/cascade = 0 命中，shadow* 类仅实体投影斑 —— 原版 26.3 不存在可接入的
 *    光空间列表，本类即「自建列表」本体（18-PARALLEL §5 P3.1 完整块 ①）。
 *    → 能否并入本项目（MIT）：可以（独立纯 Java + joml 数学，零第三方代码）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：无外部实现可参考（原版无阴影贴图系统），按 04-SPEC 契约自行实现。
 * 2. 备选：① 等接原版列表 —— 否决（原版没有，前提已证伪）；② 直接在 FrameApi 里内联单矩阵
 *    —— 否决（那就是 P3.1 基础的现状，列表结构正是本轮要补的验收对象与 CSM 数据层）；
 *    ③ 一步到位多级联 CSM 分割 —— 否决（分割方案与逐级像素判据未设计，未验证不接，X9）。
 * 3. 我们的差异点：
 *    ① **列表化**：单矩阵常量升级为 {@code List<Entry>}（级联序号 + V/P 分矩阵 + 深度区间 +
 *       光行进方向）——「列表非空」成为可断言、可打日志的事实（08-TESTING §5）；
 *    ② **V/P 分开存**：为 04-SPEC §3.2 包侧 `shadowModelView`/`shadowProjection` 预备
 *       （本方管线仍合成为单个 uLight，数值与旧链逐位等价）；
 *    ③ **永不返回空列表**：方向向量零/非规数 → 显式 IllegalArgumentException（T11 不静默）。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，零代码并入（07-CONSTRAINTS X19-X21）。
 * 5. 性能基线：❄️ 冷路径构造（列表由 bridge 每会话构建一次并缓存），清晰优先，不做优化
 *    （18-PARALLEL §7.7、07-CONSTRAINTS T14/X14）。
 */

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * P3.1：光空间列表 —— ShadowPass 的级联条目集合（04-SPEC §3.4「ShadowPass // 多级联」的数据层）。
 *
 * <p>验收语义（08-TESTING §5 shadow 行）：渲染时该列表**非空**。当前级联数 = 1（见
 * {@link #CASCADE_FAR_SPLITS}），矩阵与已验收的 P3.1 基础光空间逐位等价 —— 结构先立起来，
 * 多级联分割与太阳/月亮方向来源登记为后续缺口（18-PARALLEL §5 P3.1 完整块 ④，未验证不接）。
 */
public final class LightSpaceList {

    /**
     * 级联远裁剪面（每级一个条目）。当前单级联 far=8 —— 与 P3.1 基础已验收矩阵一致；
     * 追加条目（CSM 分割）时仅扩展此表与正交范围策略，{@link #build} 结构不变。
     */
    public static final float[] CASCADE_FAR_SPLITS = {8.0F};

    /** 级联近裁剪面（第一级；与 P3.1 基础一致）。 */
    public static final float CASCADE_NEAR = 0.1F;

    /** 正交视锥半宽/半高（覆盖验证用局部几何 ±1.2 / ±1；与 P3.1 基础一致）。 */
    public static final float ORTHO_HALF_X = 1.2F;
    public static final float ORTHO_HALF_Y = 1.0F;

    /** 光相机距原点距离（eye = -dir × 此值；与 P3.1 基础一致）。 */
    public static final float EYE_DISTANCE = 4.0F;

    /** 方向向量长度平方低于此值视为零向量（拒绝构造，T11 显式）。 */
    private static final float MIN_DIR_LENGTH_SQUARED = 1.0e-12F;

    /**
     * 一条级联光空间条目。
     *
     * @param cascade             级联序号（0 起）
     * @param shadowModelView     光空间视图矩阵（光源相机看向场景；04-SPEC §3.2 同名语义）
     * @param shadowProjection    光空间投影矩阵（正交，zZeroToOne=true —— Vulkan 深度 [0,1]）
     * @param near                该级近裁剪面
     * @param far                 该级远裁剪面
     * @param lightTravelDirection 光行进方向（单位向量，从光源指向场景）
     */
    public record Entry(
            int cascade,
            Matrix4f shadowModelView,
            Matrix4f shadowProjection,
            float near,
            float far,
            Vector3f lightTravelDirection) {

        /** 归一构造：矩阵/方向非 null；防御性拷贝（条目不可被外部位姿改写）。 */
        public Entry {
            Objects.requireNonNull(shadowModelView, "shadowModelView");
            Objects.requireNonNull(shadowProjection, "shadowProjection");
            Objects.requireNonNull(lightTravelDirection, "lightTravelDirection");
            shadowModelView = new Matrix4f(shadowModelView);
            shadowProjection = new Matrix4f(shadowProjection);
            lightTravelDirection = new Vector3f(lightTravelDirection);
        }

        /** 该级 view-projection（P × V，右乘视图；与旧链 ortho().lookAt() 数值等价）。 */
        public Matrix4f viewProjection(Matrix4f dest) {
            return dest.set(shadowProjection).mul(shadowModelView);
        }
    }

    private LightSpaceList() {}

    /**
     * 按光行进方向构建光空间列表。**永不返回空列表**（见类 javadoc ③）。
     *
     * @param lightTravelDirection 光行进方向（从光源指向场景；不要求已是单位向量）
     * @return 至少一条级联条目的不可变列表
     * @throws IllegalArgumentException 方向为 null / 零向量 / 含 NaN 或 Inf（T11 显式拒绝）
     */
    public static List<Entry> build(Vector3f lightTravelDirection) {
        if (lightTravelDirection == null) {
            throw new IllegalArgumentException("vkdisp: 光行进方向不许为 null");
        }
        Vector3f dir = new Vector3f(lightTravelDirection);
        float lengthSquared = dir.lengthSquared();
        if (!(lengthSquared >= MIN_DIR_LENGTH_SQUARED)
                || Float.isNaN(lengthSquared)
                || Float.isInfinite(lengthSquared)) {
            throw new IllegalArgumentException(
                    "vkdisp: 光行进方向非法（零向量或非规数）: " + dir);
        }
        dir.normalize();

        Vector3f eye = new Vector3f(dir).negate().mul(EYE_DISTANCE);
        Vector3f center = new Vector3f(0.0F, 0.0F, 0.0F);
        Vector3f up = new Vector3f(0.0F, 1.0F, 0.0F);

        List<Entry> entries = new ArrayList<>(CASCADE_FAR_SPLITS.length);
        float near = CASCADE_NEAR;
        for (int cascade = 0; cascade < CASCADE_FAR_SPLITS.length; cascade++) {
            float far = CASCADE_FAR_SPLITS[cascade];
            if (!(far > near)) {
                throw new IllegalStateException(
                        "vkdisp: 级联 " + cascade + " 远裁剪面 (" + far + ") 不大于近裁剪面 (" + near + ")");
            }
            // zZeroToOne=true：Vulkan 深度 [0,1]；GL 约定 [-1,1] 会裁掉近半几何（P3.1 基础实测结论）。
            Matrix4f projection = new Matrix4f()
                    .ortho(-ORTHO_HALF_X, ORTHO_HALF_X, -ORTHO_HALF_Y, ORTHO_HALF_Y, near, far, true);
            Matrix4f view = new Matrix4f().lookAt(eye, center, up);
            entries.add(new Entry(cascade, view, projection, near, far, dir));
            near = far;
        }
        if (entries.isEmpty()) {
            // 结构上不可达（CASCADE_FAR_SPLITS 非空）；保留为防御断言（T11）。
            throw new IllegalStateException("vkdisp: 光空间列表构建为空（级联配置异常）");
        }
        return Collections.unmodifiableList(entries);
    }
}
