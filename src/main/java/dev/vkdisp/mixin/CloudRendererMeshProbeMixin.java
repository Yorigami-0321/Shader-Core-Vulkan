package dev.vkdisp.mixin;
/**
 * 【参考调研】R3 / M-09 访问器：读原版 `CloudRenderer#quadCount`（替掉反射）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① Mixin 0.8.7 的 {@code @Mixin(targets=…)} / {@code @Accessor} **注解签名**
 *    （javap 核实常量池，不读其实现）；② 原版 {@code CloudRenderer} 第 58 行
 *    {@code private int quadCount}、第 198 行早退谓词、第 235 行 {@code drawIndexed(6 * quadCount)}
 *    （sources jar 逐字核实）。
 *    许可证：Mixin = MIT；原版 = Mojang EULA（只观察字段与谓词）。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以；本文件为独立编写的注入类
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：Mixin 官方 {@code @Accessor} 范式（abstract getter，applier 生成实现）。
 * 2. 备选：反射 {@code getDeclaredField("quadCount")} —— **否决**（{@code GAP-042}，
 *    旧实现即此，失败只 WARN 且改名即哑）。
 * 3. 我们的差异点：**只有抽象访问器**；为什么不再交出 {@code texture}/{@code utb} 两个引用字段，
 *    理由逐条写在 {@code bridge.IVkdispCloudMeshProbe} 的类注释第 3 条（X25）。
 * 4. 许可证核对：本项目 LGPL-3.0-or-later；零第三方代码复制。
 * 5. 性能基线：❄️ 只在诊断档自报时读一次 int。
 */
import dev.vkdisp.bridge.IVkdispCloudMeshProbe;
import dev.vkdisp.bridge.MixinTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * H 线 M-09：云侧「这一帧到底有没有发出 draw」的判据改成访问器（登记表：{@code 04-SPEC} §5.0 M-09 行）。
 *
 * <p><b>它替掉什么</b>：{@code CloudsIntoGbuffer#meshState} 里三次
 * {@code getDeclaredField + setAccessible}，以及那条
 * 「反射失败 ⇒ 本臂不能区分『没发 draw』与『发了没落地』」的 WARN 分支（物理删除）。
 *
 * <p><b>可关闭</b>：配置键 {@code mixin.cloudProbe}（默认开）。关闭 = bridge 侧不再读这个访问器、
 * 少一行自报；本注入点本身没有渲染副作用。
 */
@Mixin(targets = {MixinTargets.CLOUD_RENDERER}, remap = false)
public abstract class CloudRendererMeshProbeMixin implements IVkdispCloudMeshProbe {

    /** 原版 private 字段 {@code quadCount} 的读取器。 */
    @Accessor(MixinTargets.CLOUD_RENDERER_QUAD_COUNT)
    @Override
    public abstract int vkdisp$quadCount();
}
