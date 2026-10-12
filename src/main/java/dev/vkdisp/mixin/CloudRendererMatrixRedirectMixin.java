package dev.vkdisp.mixin;
/**
 * 【参考调研】R3 / M-10 {@code @Redirect}：改掉原版云取 modelview 的**实参**，不动全局栈
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① Mixin 0.8.7 的 {@code @Mixin(targets=…)} / {@code @Redirect} / {@code @At}
 *    **注解签名**（javap 核实常量池，不读其实现）；
 *    ② 原版 {@code CloudRenderer} 第 225-236 行：private {@code render(RenderPass, RenderPipeline)}
 *    的第 226 行 {@code writeTransform(RenderSystem.getModelViewMatrixCopy())}，
 *    以及 {@code RenderSystem} 第 296 行 {@code public static Matrix4f getModelViewMatrixCopy()}
 *    （sources jar 逐字核实：返回类型 {@code Matrix4f}，实现是 {@code new Matrix4f(modelViewStack)}）。
 *    许可证：Mixin = MIT；原版 = Mojang EULA（只观察语句与签名）。
 *    → 能否并入本项目（MIT）：可以；本文件为独立编写的注入类
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：{@code @Redirect} 替换**调用**（{@code SkyRenderer} 那一族的 M-11 用同一形状）。
 * 2. 备选：① 压/还原 {@code RenderSystem.getModelViewStack()} 全局栈（旧实现）—— **否决**：
 *    漏还原就污染同帧后续原版 pass，而这类污染是**画面级**症状、日志里看不出来；
 *    ② {@code @ModifyVariable} 改 {@code dynamicTransforms} 局部量 —— **否决**：
 *    它改的是已经写好的 UBO 切片，矩阵早在 {@code writeTransform} 里就被消费掉了，改不到源头。
 * 3. 我们的差异点：方法体只有一行转发（X25），矩阵取法与关档代价都写在
 *    {@code CloudsIntoGbuffer#onCloudModelViewRequest}。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：热路径 —— 每次云 draw 一次 {@code new Matrix4f(...)} 拷贝，
 *    与原版 {@code getModelViewMatrixCopy()} 自己那次拷贝同量级（不是新增分配类别）。
 */
import dev.vkdisp.bridge.CloudsIntoGbuffer;
import dev.vkdisp.bridge.MixinTargets;
import net.minecraft.client.renderer.CloudRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * H 线 M-10：原版云不再从我方压过的那条全局栈取位姿（登记表：{@code 04-SPEC} §5.0 M-10 行）。
 *
 * <p><b>为什么这一处 {@code @Redirect} 对原版是恒等变换</b>（源码级核实，2026-10-12）：
 * 原版只在两个地方画云 —— {@code LevelRenderer} 第 738 行（main pass 体内）与第 646 行
 * （OIT 分支），而这两处的全局栈顶都是装配期捕获的<b>相机位姿</b>
 * （{@code CameraRenderState#viewRotationMatrix}）。本注入点交出的正是同一个矩阵
 * ⇒ 原版路径逐字不变；变的一方是我方重放（旧实现要在外面自己压栈再还原）。
 *
 * <p><b>为什么用 {@code @Redirect} 而不是 {@code @ModifyConstant}</b>：要换的是**方法返回值**
 * （一次 {@code new Matrix4f(stack)} 的结果），不是常量。
 *
 * <p><b>可关闭</b>：配置键 {@code mixin.cloudMatrixRedirect}（默认开）。
 * ⚠️ 关档 = 交回原版那句 {@code RenderSystem.getModelViewMatrixCopy()}：原版云不受影响，
 * 但我方重放读到的是裸栈（非相机位姿）⇒ 云网格锚错、透视拉成放射长条（旧症状可复现，
 * bridge 侧会 WARN 一次说明代价）。这是「关掉即回到坏行为」那一族开关，与
 * {@code mrt.terrainAtlasLod0} 同口径。
 *
 * <p><b>为什么 {@code require = 1} / {@code expect = 1}</b>：目标 private 方法的重载选择靠**完整描述符**
 * （{@code render} 有三个重载），改名或签名一变就硬失败，不许静默不生效（T10 / X11）。
 */
@Mixin(targets = {MixinTargets.CLOUD_RENDERER}, remap = false)
public abstract class CloudRendererMatrixRedirectMixin {

    /**
     * 替换第 226 行那次 {@code RenderSystem.getModelViewMatrixCopy()} 调用。
     *
     * @param renderer 原版 {@code CloudRenderer} 实例（被注入方法所属对象；本方法不用它 ——
     *                 矩阵来自渲染状态，与哪个实例在画无关）
     * @return 交予 {@code writeTransform} 的 modelview 矩阵
     */
    @Redirect(method = {MixinTargets.CLOUD_RENDERER_RENDER_INNER},
            at = @At(value = "INVOKE", target = MixinTargets.RENDER_SYSTEM_GET_MODEL_VIEW_MATRIX_COPY),
            require = 1, expect = 1)
    private Matrix4f vkdisp$cloudModelView(CloudRenderer renderer) {
        return CloudsIntoGbuffer.onCloudModelViewRequest();
    }
}
