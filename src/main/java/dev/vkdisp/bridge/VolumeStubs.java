package dev.vkdisp.bridge;
/**
 * 【参考调研】包地形片元的 {@code sampler3D}（OF 体积光照 / 体素贴图）**类型匹配的 3D 桩**
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 原版 26.3 {@code com.mojang.renderpearl.api.device.GpuDevice#createTexture}
 *    （随 MDG 分发的 sources jar 逐行读出签名 —— 关键是它有
 *    {@code (label, usage, format, width, height, depthOrLayers, mipLevels)} 这个重载，
 *    {@code depthOrLayers > 1} 即 3D 纹理）与 {@code CommandEncoder#writeToTexture}；
 *    ② 本仓库自有 {@link ShadowStubs} / {@code NeutralMaterialMaps}（同款「类型正确的中性桩」
 *    既有实现，MIT 自有代码，本类沿用其纪律与注释密度）。
 *    许可证：Mojang EULA —— 只调用公开 API，零源码搬运。
 *    → 能否并入本项目（MIT）：可以 —— 独立编写的桥接封装
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：无（原版没有「包片元的 sampler3D 缺省值」这个概念）。
 * 2. 备选：
 *    <ul>
 *      <li>① 继续喂 2D 方块图集 —— <b>否决，这就是本轮要修的 bug</b>。
 *          {@code sampler3D} 在 Vulkan 里要求描述符类型是 3D 图像视图，
 *          喂 2D 视图是<b>未定义行为</b>：驱动可以丢 draw / 给垃圾 / 无事发生，
 *          <b>且本机没有 validation layer，不会报任何错</b>
 *          （与 {@code h27} 的别名 UB 同一类问题：静默、无告警、只能靠推理发现）。
 *          实测依据：BSL v10.1.8 全包扫出 4 个 {@code sampler3D}
 *          （{@code lighttex} / {@code lighttex0} / {@code lighttex1} / {@code voxeltex}）。</li>
 *      <li>② 删掉这些 sampler 不绑 —— <b>否决</b>：布局多于 SPIR-V 是无害的，
 *          但删掉会让「包声明了它」不可见；且真正类型正确的解法成本极低
 *          （{@code createTexture} 本来就有 {@code depthOrLayers}）。</li>
 *      <li>③ 造一张有内容的假体积光照图 —— <b>否决</b>：那是<b>编一个假的输入</b>，
 *          本项目最讨厌的失败形态（X9 不猜）。</li>
 *    </ul>
 * 3. 我们的差异点：
 *    <ul>
 *      <li>🔖 <b>缺省值有语义依据</b>：{@code lighttex0} 是 OF 的体积光照贴图（3D），
 *          本引擎<b>没有</b>它（不引入、不假装有）⇒ 语义等价于「无光照贡献」
 *          ⇒ 全 0 纹理。<b>不是</b>喂图集（那会把图集的颜色当成体积光照强度）。</li>
 *      <li>🔖 <b>4×4×4 而非 1×1×1</b>：与 {@code NeutralMaterialMaps} 同理由 ——
 *          规避部分驱动对非二次幂 mip 链的限制（本机 lavapipe 与真实显卡都要能跑）。</li>
 *      <li>🔖 <b>必须在 render pass 打开之前建好</b>：贴图上传走 command encoder，
 *          pass 打开期间新建/上传会被 RenderPearl 拒绝
 *          （{@code IllegalStateException: Close the existing render pass ...}，
 *          {@code h10} 已实测踩到，且是<b>每帧</b>抛）。</li>
 *      <li>🔖 <b>永不作 render pass 附件</b>：与 {@link ShadowStubs} 同一条纪律 ——
 *          同一 image 既作读写附件又作采样器是 UB。</li>
 *    </ul>
 * 4. 许可证核对：本项目 MIT；只调用公开 API，无代码复制。
 * 5. 性能基线：❄️ 懒建一次（4×4×4 RGBA8 = 256 字节），渲染期零分配（只读视图）。
 */
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;

/**
 * 一张 4×4×4 的 3D 桩纹理，供包的 {@code sampler3D} 使用。
 *
 * <p>🔖 <b>语义 = 「无体积光照 / 无体素数据」</b>（全 0），不是随便找张图糊上去：
 * {@code lighttex0} 是 OptiFine 语义的<b>体积</b>光照贴图，
 * 本引擎没有这套资源（不引入第三方材质/光照资产，见 GAP-009 裁决）⇒ 全 0 是可解释的缺省。
 *
 * <p>⚠️ <b>明确不承诺</b>：包基于体积光照的体积光 / AO 效果在本引擎上<b>不成立</b>。
 * 这比「喂 2D 图集」诚实得多 —— 后者是静默的 UB，可能碰巧「看起来有东西」，
 * 换台机器 / 换个驱动就变成另一种结果。
 */
final class VolumeStubs {

    /** 边长：4 而非 1（与 {@code NeutralMaterialMaps} 同理由：规避非二次幂 mip 链限制）。 */
    private static final int SIZE = 4;

    /** 桩纹理视图（懒建；{@code null} = 未建）。 */
    private static GpuTextureView volumeView;

    private VolumeStubs() {
    }

    /**
     * 🔴 **必须在 render pass 打开之前调用**（渲染线程）。
     *
     * <p>贴图上传走 {@code CommandEncoder#writeToTexture}，而原版规定
     * 「pass 打开期间不得再对 encoder 下命令」⇒ 在 pass 内懒建会抛
     * {@code IllegalStateException} 且<b>每帧</b>抛（懒建永远失败 ⇒ 永远重建）。
     *
     * <p>⇒ 调用点安排在 {@code MrtTerrainPass#ensureTargets}（建 pass 描述符之前），
     * 与 {@link ShadowStubs#init()} 同一位置。
     */
    static void init() {
        ensure();
    }

    private static synchronized void ensure() {
        if (volumeView != null && !volumeView.isClosed()) {
            return;
        }
        var device = RenderSystem.getDevice();
        // 🔖 用标志 **不用** RENDER_ATTACHMENT：这张图永不作 render pass 附件，
        //   少一个「同一 image 既作读写附件又作采样器」的别名 UB 机会（与 ShadowStubs 同纪律）。
        // 🔖 不用 COPY_DST：内容是全 0 ⇒ 走 clearColorTexture 一次清掉整个 3D 纹理，
        //   而 writeToTexture 的 ByteBuffer 重载**只写单层**（参数含 depthOrLayer），
        //   3D 纹理要逐层调 4 次 —— 为一个「恒为 0」的内容付 4 次上传不值得，
        //   且 clear 路径没有「忘填某一层」这种静默失效的可能。
        GpuTexture texture = device.createTexture(
                () -> "vkdisp volume stub",
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
                GpuFormat.RGBA8_UNORM, SIZE, SIZE, SIZE, 1);
        // 🔖 clear 走附件路径 ⇒ 必须有 RENDER_ATTACHMENT（这也是上面带上的原因）。
        //   清成全 0 = 「无光照贡献 / 无体素数据」。
        //   🔖 CommandEncoder 的 clear* 一律返回 void（sources jar 逐行核实）⇒ 必须分两句写，
        //   链式调用编译不过；而 submit() 才是落盘动作（写成 close 会静默不上传）。
        var encoder = device.createCommandEncoder();
        encoder.clearColorTexture(texture, new org.joml.Vector4f(0.0F, 0.0F, 0.0F, 0.0F));
        encoder.submit();
        volumeView = device.createTextureView(texture);
        dev.vkdisp.VkDisp.LOGGER.info(
                "vkdisp: [GAP-003] volume stub ready ({}x{}x{} RGBA8@0) —— sampler3D 的"
                        + " lighttex0/1、voxeltex 现在绑**类型匹配的 3D 视图**"
                        + "（此前喂 2D 图集 = 描述符类型不匹配 = Vulkan UB 且不报错）",
                SIZE, SIZE, SIZE);
    }

    /** 3D 桩视图；未建时抛（调用点必须先 {@link #init()}）。 */
    static GpuTextureView view() {
        if (volumeView == null || volumeView.isClosed()) {
            throw new IllegalStateException(
                    "vkdisp: volume stub 未初始化 —— 必须在 render pass 之前调 VolumeStubs.init()");
        }
        return volumeView;
    }
}
