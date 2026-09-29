package dev.vkdisp.bridge;
/**
 * 【参考调研】F1 契约冻结 / 原版 renderpearl 纹理与渲染目标 API
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 原版 26.3 客户端 jar 内 com.mojang.renderpearl.api.textures.*
 *    与 com.mojang.blaze3d.pipeline.RenderTarget（RenderTarget 未迁入 renderpearl，仍在 blaze3d）。
 *    只观察 javap 签名与官方调用点（PostPass/RenderTarget），零源码文本搬运；参考模组零接触。
 *    → 能否并入本项目（MIT）：本文件为独立的薄封装签名，可并入
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：原版 GpuTexture/GpuTextureView（getColorTextureView 等）与
 *    PostPass 对输入纹理 view+sampler 的官方用法。
 * 2. 备选：无（GAP 判定：不需要登记）。
 * 3. 我们的差异点：只冻结纯 Java 视图（尺寸/格式/是否可写），colortex 池（RenderTargetPool）
 *    的真实分配留关键路径；A–F 并行线不触碰本实现。
 * 4. 许可证核对：本项目 MIT；只调用公开 API 签名，无代码复制。
 * 5. 性能基线：每帧一次视图读取，冷路径（P0 阶段），无优化需求。
 */

/**
 * F1 冻结契约：纹理/渲染目标的纯 Java 签名（{@code docs/18-PARALLEL.md} §3 F1）。
 *
 * <p><b>签名 + Javadoc 冻结，无实现</b>。不暴露任何 {@code com.mojang.renderpearl.*}
 * 或 {@code com.mojang.blaze3d.*} 类型（07 T5）；原版类型收敛在实现内部。
 * 改动必须走 {@link ContractVersion} §3.2 流程。
 */
public interface TextureApi {
    /**
     * 纹理视图的纯 Java 描述（colortex/depthtex 中间表示与 E 线序列化打印用）。
     *
     * @param width  mip 0 宽度（像素）
     * @param height mip 0 高度（像素）
     * @param format 逻辑格式名（如 {@code RGBA8_UNORM}、{@code DEPTH32_FLOAT}），
     *               与 {@code 04-SPEC.md} §3.4 渲染目标要求对应
     * @param sampleCount 采样数（1 = 非多重采样）
     */
    record TextureView(int width, int height, String format, int sampleCount) {}

    /**
     * 取当前主渲染目标的颜色视图描述（纯数据，不含原版类型）。
     *
     * @return 主目标颜色视图；设备未就绪时抛 {@link IllegalStateException}（不许静默返回 null）
     */
    TextureView mainColorView();

    /**
     * 校验请求的格式/尺寸组合是否被当前设备支持。
     *
     * @param format 逻辑格式名（见 {@link TextureView#format()}）
     * @param width  宽度（>0）
     * @param height 高度（>0）
     * @return 支持返回 true；不支持返回 false（调用方必须显式降级并打 WARN，T11）
     */
    boolean isFormatSupported(String format, int width, int height);

    /** 本契约版本（与 {@link ContractVersion#VERSION} 一致）。 */
    int contractVersion();
}
