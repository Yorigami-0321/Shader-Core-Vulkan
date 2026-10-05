package dev.vkdisp.bridge;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.vkdisp.VkDisp;
import dev.vkdisp.pack.ShaderPackRepository;
import dev.vkdisp.pack.ShaderPackScanner;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * OF {@code texture.<sampler>} 自定义纹理的**真值**加载（GAP-009 素材线的 noisetex 落点）。
 *
 * <p>🔖 <b>为什么在渲染线程、开 pass 之前加载</b>：上传走 {@code createCommandEncoder}，
 * render pass 打开期间新建 encoder 会被拒（h10 实测原文 "Close the existing render pass..."）；
 * 而解码（zip 读 + PNG）放冷路径会拿不到设备。**懒建 + 指纹比对** ⇒ 每次重载只付一次钱。
 *
 * <p>🔴 <b>失败不静默</b>：任一张解码/上传失败 ⇒ 该采样器缺席 + ERROR 原文，
 * 使用侧（chainResolver）回落到显式占位 —— 缺席是可见的，不是猜的。
 */
public final class PackTextures {

    /** 重载线程写（desired）、渲染线程读 —— 与 {@code VkDispVirtualPack} 契约同款 volatile。 */
    private static volatile Map<String, String> desired = Map.of();
    /** 所选包名；{@code null} = 无包（此时不加载任何自定义纹理）。 */
    private static volatile String desiredPack;
    /** 库存目录（由冷路径传入；渲染线程不再自己找 gameDir）；{@code null} = 不可用。 */
    private static volatile java.nio.file.Path desiredInventory;
    /** 已加载：sampler 名 → 目标。渲染线程独占，无需同步。 */
    private static Map<String, TextureTarget> loaded = Map.of();
    /** 上次成功加载时的指纹（pack + 绑定表），相同则不重载。 */
    private static String loadedFingerprint = "";

    private PackTextures() {}

    /** 资源重载（冷路径）：记下所选包与绑定表；实际上传留给渲染线程的 {@link #ensureReady()}。
     *  {@code inventory}/{@code packName} 允许 {@code null} = 无包（清空绑定）。 */
    public static void setDesired(java.nio.file.Path inventory, String packName,
            Map<String, String> bindings) {
        desired = bindings == null ? Map.of() : Map.copyOf(bindings);
        desiredPack = packName;
        desiredInventory = inventory;
    }

    /** 当前绑定表的稳定指纹。 */
    private static String fingerprint() {
        StringBuilder sb = new StringBuilder(String.valueOf(desiredPack)).append('|');
        new TreeMap<>(desired).forEach((k, v) -> sb.append(k).append('=').append(v).append(';'));
        return sb.toString();
    }

    /**
     * 渲染线程、任何 pass 打开之前调用：绑定表变了就重建全部自定义纹理。
     */
    public static void ensureReady() {
        RenderSystem.assertOnRenderThread();
        // 🔴 内置噪声的懒建**必须在这里预热**：视图若在 render pass **打开之后**才首建，
        //   createCommandEncoder 会被原版拒（h10 实测原文 "Close the existing render pass..."）。
        builtinNoiseView();
        String fp = fingerprint();
        if (fp.equals(loadedFingerprint)) {
            return;
        }
        loadedFingerprint = fp;
        Map<String, TextureTarget> next = new LinkedHashMap<>();
        String pack = desiredPack;
        java.nio.file.Path inventory = desiredInventory;
        if (pack == null || inventory == null || desired.isEmpty()) {
            loaded = next;
            return;
        }
        ShaderPackScanner.DiscoveredPack discovered = ShaderPackScanner.scan(inventory).packs().stream()
                .filter(p -> pack.equals(p.name()))
                .findFirst().orElse(null);
        if (discovered == null) {
            VkDisp.LOGGER.error("vkdisp: [GAP-009] 自定义纹理无法加载：库存里找不到包 '{}'（bindings={}）",
                    pack, desired.size());
            loaded = next;
            return;
        }
        ShaderPackRepository.MountPlan plan;
        try {
            plan = ShaderPackRepository.plan(discovered);
        } catch (Exception e) {
            VkDisp.LOGGER.error("vkdisp: [GAP-009] 挂载规划失败，自定义纹理全部缺席", e);
            loaded = next;
            return;
        }
        for (Map.Entry<String, String> entry : desired.entrySet()) {
            String sampler = entry.getKey();
            String relPath = entry.getValue();
            try (InputStream in = plan.openShader(relPath);
                    NativeImage image = NativeImage.read(in)) {
                TextureTarget target = new TextureTarget(
                        "vkdisp pack tex " + sampler,
                        image.getWidth(), image.getHeight(), GpuFormat.RGBA8_UNORM, null);
                RenderSystem.getDevice().createCommandEncoder()
                        .writeToTexture(target.getColorTexture(), image);
                next.put(sampler, target);
                VkDisp.LOGGER.info(
                        "vkdisp: [GAP-009] custom texture loaded: sampler='{}' path='{}' {}x{}",
                        sampler, relPath, image.getWidth(), image.getHeight());
            } catch (Throwable t) {
                VkDisp.LOGGER.error(
                        "vkdisp: [GAP-009] custom texture FAILED: sampler='{}' path='{}'"
                                + " —— 该采样器回落到显式占位（缺席可见，不静默）",
                        sampler, relPath, t);
            }
        }
        loaded = next;
    }

    /** 某采样器的真值视图；未加载/失败返回 {@code null}（调用方必须给显式占位）。 */
    public static @Nullable GpuTextureView view(String samplerName) {
        if (samplerName.equals("noisetex")) {
            return builtinNoiseView();
        }
        TextureTarget target = loaded.get(samplerName);
        return target == null ? null : target.getColorTextureView();
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // 🔖 **内置 noisetex**（OptiFine 公开 API 事实：`noisetex` 由引擎提供一张 64×64 噪声图，
    //   包不需要声明文件；见 04-SPEC 的 OF 内建 uniform 表口径）。
    //   BSL 的 blue-noise 抖动 / 胶片颗粒（composite/composite5）采的就是它 ——
    //   不供这张图 = 这些效果采 colortex0 占位，数字会变但语义是假的（X9 不容许）。
    //   确定性生成（固定种子）⇒ 同一构建两次运行逐 texel 相同，取证可比。
    // ────────────────────────────────────────────────────────────────────────────────

    private static final int BUILTIN_NOISE_SIZE = 64;
    private static TextureTarget builtinNoise;

    /** 内置噪声视图（渲染线程、开 pass 之前取用）。 */
    private static @Nullable GpuTextureView builtinNoiseView() {
        RenderSystem.assertOnRenderThread();
        if (builtinNoise == null) {
            try (NativeImage image = new NativeImage(BUILTIN_NOISE_SIZE, BUILTIN_NOISE_SIZE, true)) {
                java.util.Random random = new java.util.Random(0x7E57L);
                for (int y = 0; y < BUILTIN_NOISE_SIZE; y++) {
                    for (int x = 0; x < BUILTIN_NOISE_SIZE; x++) {
                        int r = random.nextInt(256);
                        int g = random.nextInt(256);
                        int b = random.nextInt(256);
                        // setPixelABGR 的字面序 = A<<24|B<<16|G<<8|R（内存里即 RGBA，little-endian）
                        image.setPixelABGR(x, y, 0xFF << 24 | b << 16 | g << 8 | r);
                    }
                }
                TextureTarget target = new TextureTarget("vkdisp builtin noisetex",
                        BUILTIN_NOISE_SIZE, BUILTIN_NOISE_SIZE, GpuFormat.RGBA8_UNORM, null);
                RenderSystem.getDevice().createCommandEncoder()
                        .writeToTexture(target.getColorTexture(), image);
                builtinNoise = target;
                VkDisp.LOGGER.info("vkdisp: [GAP-009] builtin noisetex created: {}x{} (deterministic seed)",
                        BUILTIN_NOISE_SIZE, BUILTIN_NOISE_SIZE);
            } catch (Throwable t) {
                VkDisp.LOGGER.error("vkdisp: [GAP-009] builtin noisetex FAILED —— "
                        + "noisetex 回落显式占位（缺席可见）", t);
                return null;
            }
        }
        return builtinNoise.getColorTextureView();
    }
}
