package dev.vkdisp.bridge;
/**
 * 【参考调研】包地形片元「阴影类 sampler」的**专用桩纹理** / 只依据公开 API 与本仓库实测。
 *
 * <p>🔴🔖 <b>它解决的是什么问题</b>：包片元（BSL {@code gbuffers_terrain}）声明了
 * {@code shadowtex0} / {@code shadowtex1} / {@code shadowcolor0} 三个 sampler。
 * 原实现把它们绑到**本 pass 自己的深度附件与 colortex0** ——
 * 而那两张图同时是该 render pass 的**读写附件**。
 * 在 Vulkan 里把同一张 image 既作读写附件又作采样器是<b>未定义行为</b>：
 * 驱动可以丢 draw、可以给垃圾、也可以无事发生，<b>且不会有 validation error</b>
 * （本机没装 validation layer，{@code AGENT_CONTEXT.md} §9.4.15）。
 *
 * <p>🔶 <b>实测症状与之吻合</b>：{@code evidence/h25} 与 {@code h26} 用 2×2 对照把闪烁的触发
 * 条件收敛到<b>只有「包自己的地形片元」</b>；原版 {@code core/terrain} 不声明这些 sampler ⇒
 * 不触发，与观测一致。
 *
 * <p>🔖 <b>代价（如实登记，不粉饰）</b>：桩纹理里<b>没有真阴影贴图</b> ⇒ 阴影项**不承诺**
 * （原实现的注释也写「阴影结果不承诺」）。桩值刻意选成「深度 = 0.0」——
 * 那是本引擎反向 Z 下的<b>远平面</b>（见 {@code h04} §5 的三条字节码级证据）
 * ⇒ 阴影项取「无遮挡」，是<b>可解释的缺省</b>，比喂一张含本 pass 自身深度的图更接近正确。
 *
 * <p>合规：只调用公开 API（{@code GpuDevice#createTexture} / {@code createTextureView} /
 * {@code clearColorAndDepthTextures}），零源码搬运。
 */
final class ShadowStubs {

    /** 桩深度视图（1×1 {@code D32_FLOAT}，清到 0.0 = 远平面）。 */
    private static com.mojang.renderpearl.api.textures.GpuTextureView depthView;

    /** 桩颜色视图（1×1 {@code RGBA8_UNORM}，清到 (0,0,0,0)）。 */
    private static com.mojang.renderpearl.api.textures.GpuTextureView colorView;

    private ShadowStubs() {
    }

    /**
     * 🔴 **必须在任何 render pass 打开之前调用**（渲染线程）。
     *
     * <p>本方法会新建 command encoder 并做一次 clear。若在
     * {@code try (RenderPass …)} 内部调用，RenderPearl 会抛
     * {@code IllegalStateException: Close the existing render pass before creating a new one!}
     * —— 本轮第一版就是踩了这个（绑定点 {@code bindPackTerrainUniforms} 在 pass 内部）。
     * ⇒ 调用点安排在 {@code MrtTerrainPass#ensureTargets}（建 pass 描述符之前）。
     *
     * <p>**永不作为 render pass 附件**，这是它消除别名的前提。
     */
    static void init() {
        ensure();
    }

    private static synchronized void ensure() {
        if (depthView != null && colorView != null) {
            return;
        }
        var device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
        // 🔖 COPY_DST 是 `clearColorAndDepthTextures` 的**硬性要求**
        //   （实测原文：`Color texture must have USAGE_COPY_DST`，本轮第一版漏了）。
        //   TEXTURE_BINDING 因为它要当 sampler 源；RENDER_ATTACHMENT 因为 clear 走附件路径。
        int usage = com.mojang.renderpearl.api.textures.GpuTexture.USAGE_COPY_DST
                | com.mojang.renderpearl.api.textures.GpuTexture.USAGE_TEXTURE_BINDING
                | com.mojang.renderpearl.api.textures.GpuTexture.USAGE_RENDER_ATTACHMENT;
        var depthTex = device.createTexture(() -> "vkdisp shadow stub depth",
                usage, com.mojang.renderpearl.api.GpuFormat.D32_FLOAT, 1, 1, 1, 1);
        var colorTex = device.createTexture(() -> "vkdisp shadow stub color",
                usage, com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
        depthView = device.createTextureView(depthTex);
        colorView = device.createTextureView(colorTex);
        // 🔖 桩深度 = 1.0（2026-10-10 真机重测臂）。旧决策 0.0 出自 h50h/h50i 两臂，但那是在
        //     lavapipe + 深度代理恒零的时代测的「黑帧率」判据 —— 节奏类指标（X55），且当时
        //     体积云/阴影分支根本吃不到深度，两臂差异测的是别的东西。真机上深度族修好后，
        //     体积云把 0.0 的桩读成「全遮挡」⇒ 整屏黑色云带纱幔（2026-10-10 用户截图复现）。
        //     GL 口径 1.0 = 远平面 = 无遮挡：比较采样器（shadow2D z<=texel）与非比较回退
        //     （直接取 r）两种读法下 1.0 都给出「全受光」⇒ 两向同果，判据干净。
        //   谁要改回 0.0，先拿出真机上比「黑云带消失」更强的判据；lavapipe 那两臂不作数。
        //   🔖 颜色桩仍全 0：它只是占位，本 pass 不做阴影，BSL 不用它算可见性。
        com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder()
                .clearColorAndDepthTextures(colorTex,
                        new org.joml.Vector4f(0.0F, 0.0F, 0.0F, 0.0F), depthTex, 1.0F);
        dev.vkdisp.VkDisp.LOGGER.info(
                "vkdisp: [GAP-003] shadow stubs ready (1x1 D32@1.0 + RGBA8@0) —— "
                        + "shadowtex0/1 与 shadowcolor0 **不再绑本 pass 的读写附件**"
                        + "（读写附件 + 采样器同图 = Vulkan UB，且不报 validation error）");
    }

    /**
     * 🔬 A/B 用：**故意**恢复「绑本 pass 读写附件」的旧行为（Vulkan 未定义行为）。
     *
     * <p>仅在 {@code mrt.shadowStubs = false} 时走，用于同二进制单变量对照取证。
     * 默认路径永远不调用它。
     */
    static com.mojang.renderpearl.api.textures.GpuTextureView ownDepthAttachment(
            com.mojang.renderpearl.api.textures.GpuTextureView depthView) {
        return depthView;
    }

    /** {@link #ownDepthAttachment} 的颜色侧对应物（colortex0）。 */
    static com.mojang.renderpearl.api.textures.GpuTextureView ownColorAttachment(
            com.mojang.renderpearl.api.textures.GpuTextureView colorView) {
        return colorView;
    }

    static com.mojang.renderpearl.api.textures.GpuTextureView depthView() {
        // ⚠️ 这里**不再**懒建：懒建会在 render pass 内新建 encoder 而抛异常。
        //   正常路径由 MrtTerrainPass#ensureTargets → ShadowStubs#init() 提前建好。
        if (depthView == null) {
            throw new IllegalStateException(
                    "vkdisp: shadow stubs 未初始化 —— 必须在 render pass 之前调 ShadowStubs.init()");
        }
        return depthView;
    }

    static com.mojang.renderpearl.api.textures.GpuTextureView colorView() {
        if (colorView == null) {
            throw new IllegalStateException(
                    "vkdisp: shadow stubs 未初始化 —— 必须在 render pass 之前调 ShadowStubs.init()");
        }
        return colorView;
    }
}