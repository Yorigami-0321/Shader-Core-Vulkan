package dev.vkdisp.bridge;

/**
 * 【参考调研】R3 / M-09：读出原版 `CloudRenderer#quadCount`（private 字段），替掉反射
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① Mixin 0.8.7 的 {@code @Accessor} **注解签名**（javap 核实，不读实现）；
 *    ② 原版 {@code CloudRenderer} 第 58 行的 {@code private int quadCount}、第 198 行的
 *    静默早退谓词 {@code texture != null && quadCount != 0}、第 235 行的
 *    {@code drawIndexed(6 * this.quadCount, …)}（sources jar 逐字核实）。
 *    许可证：Mixin = MIT；原版 = Mojang EULA（只观察字段与谓词）。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以 —— 本项目原创契约接口
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：Mixin 官方 {@code @Accessor} 范式（运行期生成 getter，改名即硬失败）。
 * 2. 备选：反射 {@code getDeclaredField("quadCount")} —— **已否决**（{@code GAP-042}：
 *    改名即哑、失败只 WARN，不满足 {@code 07} T10「必须能证明被调用」）。
 * 3. 我们的差异点：**只交出 {@code quadCount} 这一项**。
 *    🔖 原反射还读 {@code texture} 与 {@code utb} 两个引用字段，本轮不再读，理由不是省事而是判据等价：
 *    ① {@code quadCount != 0} 蕴含 {@code texture != null}（网格只能在纹理就位后算出），
 *    而原版早退条件正是这两者的合取 ⇒ 「没发 draw」的判定已经覆盖；
 *    ② {@code utb} 为 null 时原版第 234 行会**抛 NPE**（响亮失败），不属于「静默早退」那一族，
 *    用它做判据会把「响亮失败」误登记成「静默失效」。
 *    两个引用字段的类型都是 {@code CloudRenderer} 的 private 嵌套类，在 Java 源码里不可命名 ⇒
 *    要交出它们只能退化成 {@code Object}（访问器返回类型放宽），本轮不引入这个不确定性。
 * 4. 许可证核对：本项目 LGPL-3.0-or-later；零第三方代码复制。
 * 5. 性能基线：一次 int 字段读；无分配。
 */
public interface IVkdispCloudMeshProbe {

    /**
     * 本帧云网格的 quad 条数（0 = 原版 {@code CloudRenderer#render} 会静默早退、一条 draw 都不发）。
     *
     * <p>🔖 必须在 {@code prepare(...)} **之后**读：它是 prepare 算出来的，之前读到的是上一帧
     * （首帧必然 0，读早了会把「正常首帧」报成「网格坏了」）。
     */
    int vkdisp$quadCount();
}
