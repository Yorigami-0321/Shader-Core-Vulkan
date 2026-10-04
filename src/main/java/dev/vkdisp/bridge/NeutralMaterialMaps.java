package dev.vkdisp.bridge;
/**
 * 【参考调研】包材质贴图（specular / normals）在缺资源包时的**中性值** / 原版 {@code GpuDevice} 公开 API
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 原版 26.3 {@code com.mojang.renderpearl.api.device.GpuDevice#createTexture} 与
 *    {@code com.mojang.renderpearl.api.commands.CommandEncoder#writeToTexture}
 *    （随 MDG 分发的 sources jar 逐行读出**签名与用法形状**，不搬运实现语句）；
 *    另有 {@code com.mojang.renderpearl.api.textures.GpuTexture} 的 {@code USAGE_*} 常量
 *    （字节码核实数值）。许可证：Mojang EULA —— 只调用公开 API。
 *    → 能否并入本项目（MIT）：可以 —— 独立编写的桥接封装，零源码搬运
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：原版没有「包材质贴图」这个概念（它没有包加载器）；
 *    OptiFine/Iris 的 `specular` / `normals` 是**资源包附带的逐方块材质贴图集**，
 *    本引擎目前**没有**这套资源（不引入、也不假装有）。
 * 2. 备选：① 继续绑方块图集当占位 —— **否决，且这正是本轮要修的 bug**：
 *    图集的蓝通道（`ao`）与红/绿通道（`smoothness`/`f0`）都不是材质语义，
 *    乘进 albedo 会把画面压成全黑（`h10` 实测）。
 *    ② 填一个「看起来像材质」的随机噪声 —— 否决：那是**编一个假的输入**，
 *    本项目最讨厌的失败形态（X9 不猜）。中性值有数学依据，不是猜的。
 * 3. 我们的差异点：给出**乘法单位元**并写清推导：
 *    <pre>
 *      specular = (0,0,0,1)  ⇒ smoothness=0, f0=0 ⇒ metalness=0, porosity=0, emissionMat=0
 *                               ⇒ albedo *= (1 - metalness*smoothness) = *1（无变化）
 *      normals  = (128,128,255,255) ⇒ normalMap=(0,0,1), ao = .z = 1.0
 *                               ⇒ albedo *= ao*ao = *1（无变化）
 *    </pre>
 *    即「**这张方块没有材质覆盖、没有 AO、法线朝上**」—— 这是可解释的缺省，
 *    不是「随手找个贴图糊上去」。
 * 4. 许可证核对：本项目 MIT；只调用公开 API，无代码复制。
 * 5. 性能基线：❄️ 懒建一次（4×4，非 1×1：规避部分驱动对 1×1 非二次幂 mip 链的限制），
 *    渲染期零分配（只读视图）。
 */
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 两张 4×4 中性材质贴图（{@code specular} / {@code normals}）。
 *
 * <p>🔖 <b>它们不是「材质贴图」，是「缺材质贴图时的乘法单位元」</b>。
 * 类注释给出了逐通道推导；本类的存在是为了让「包里要材质贴图、包没提供」这件事
 * 变成一个**可解释的缺省**，而不是把不相干的图集绑上去让画面全黑（`h10` 实测）。
 *
 * <p>⚠️ <b>明确不承诺</b>：高级材质路径的材质细节（光滑度、金属度、材质法线、自发光遮罩）
 * 在本引擎上**不成立**，直到接入 OptiFine 式材质贴图集。这一条已登记为 GAP-009。
 */
public final class NeutralMaterialMaps {

    /** 边长：4 而非 1 —— 规避部分驱动对非二次幂 mip 链的限制（本机 lavapipe + 真实显卡都要能跑）。 */
    private static final int SIZE = 4;

    /** 中性 specular：RGB=0（无光滑度/无金属度/无孔隙）、A=1（不遮自发光）。 */
    private static final int[] SPECULAR_RGBA = {0, 0, 0, 255};

    /** 中性 normals：RG=(128,128) ⇒ 解码为 (0,0) 的 xy；B=255 ⇒ ao = 1.0。 */
    private static final int[] NORMALS_RGBA = {128, 128, 255, 255};

    private static GpuTexture specular;
    private static GpuTexture normals;

    private static GpuTextureView specularViewCache;
    private static GpuTextureView normalsViewCache;

    private NeutralMaterialMaps() {}


    /**
     * 🔴 **必须在 render pass 打开之前调用**（h10 实测踩到）：
     * 贴图上传走 {@code CommandEncoder#writeToTexture}，而原版规定
     * 「pass 打开期间不得再对 encoder 下命令」⇒ 在 pass 内懒建会抛
     * {@code IllegalStateException: Close the existing render pass before performing additional commands}，
     * 且是**每帧**抛（懒建永远失败 ⇒ 永远重建）。
     *
     * <p>🔖 这与本项目既有的 {@code MappableRingBuffer} 纪律同源：
     * map/close 与贴图上传都必须在开 pass 前完成。
     */
    public static synchronized void ensureCreated() {
        if (specular == null || specular.isClosed()) {
            specular = create("vkdisp neutral specular", SPECULAR_RGBA);
            specularViewCache = RenderSystem.getDevice().createTextureView(specular);
            dev.vkdisp.VkDisp.LOGGER.info(
                    "vkdisp: [GAP-009] neutral material map created: specular ({}x{}, RGBA=0,0,0,255)"
                            + " -> 包要材质贴图而本引擎没有该资源集，绑**乘法单位元**而非不相干的贴图",
                    SIZE);
        }
        if (normals == null || normals.isClosed()) {
            normals = create("vkdisp neutral normals", NORMALS_RGBA);
            normalsViewCache = RenderSystem.getDevice().createTextureView(normals);
            dev.vkdisp.VkDisp.LOGGER.info(
                    "vkdisp: [GAP-009] neutral material map created: normals ({}x{}, RGBA=128,128,255,255)"
                            + " -> .z = 1.0 ⇒ ao = 1.0 ⇒ albedo *= ao*ao 是恒等",
                    SIZE);
        }
    }

    /** 中性 specular 视图（必须先 {@link #ensureCreated()}）。 */
    public static GpuTextureView specularView() {
        return require(specularViewCache, "specular");
    }

    /** 中性 normals 视图（必须先 {@link #ensureCreated()}）。 */
    public static GpuTextureView normalsView() {
        return require(normalsViewCache, "normals");
    }

    private static GpuTextureView require(GpuTextureView view, String name) {
        if (view == null) {
            throw new IllegalStateException(
                    "vkdisp: neutral material map  + name +  未创建"
                            + "（必须在 render pass 打开之前调用 ensureCreated()）");
        }
        return view;
    }

    private static GpuTexture create(String label, int[] rgba) {
        ByteBuffer pixels = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder());
        for (int i = 0; i < SIZE * SIZE; i++) {
            pixels.put((byte) rgba[0]).put((byte) rgba[1]).put((byte) rgba[2]).put((byte) rgba[3]);
        }
        pixels.flip();
        GpuTexture texture = RenderSystem.getDevice().createTexture(
                label,
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
        // 🔖 CommandEncoder 用 submit() 落盘（不是 close）：逐行核实 CommandEncoder 的方法清单，
        //   写错这里会静默不上传 ⇒ 贴图全 0 ⇒ 又变成「全黑」，症状与本轮要修的 bug 一模一样。
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.writeToTexture(texture, pixels, 0, 0, 0, 0, SIZE, SIZE);
        encoder.submit();
        return texture;
    }
}
