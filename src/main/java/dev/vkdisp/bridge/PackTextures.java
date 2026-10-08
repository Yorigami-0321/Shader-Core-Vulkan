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
 * OF {@code texture.<sampler>} 自定义纹理的**真值**加载（GAP-009 素材线的 noisetex 落点，
 * GAP-025 把它升级成「包声明优先、内置只兜底」）。
 *
 * <p>🔖 <b>为什么在渲染线程、开 pass 之前加载</b>：上传走 {@code createCommandEncoder}，
 * render pass 打开期间新建 encoder 会被拒（h10 实测原文 "Close the existing render pass..."）；
 * 而解码（zip 读 + PNG）放冷路径会拿不到设备。**懒建 + 指纹比对** ⇒ 每次重载只付一次钱。
 *
 * <p>🔴 <b>失败不静默</b>：任一张解码/上传失败 ⇒ 该采样器缺席 + ERROR 原文，
 * 使用侧（chainResolver）回落到显式占位 —— 缺席是可见的，不是猜的。
 * {@code noisetex} 另有内置兜底，故兜底原因必须一次性写在日志里（{@link NoiseSamplerSource}）。
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
        String fp = fingerprint();
        if (fp.equals(loadedFingerprint)) {
            return;
        }
        loadedFingerprint = fp;
        loaded = loadAll();
        NoiseSamplerSource.Decision noise = NoiseSamplerSource.decide(
                desired, loaded.keySet(), desiredPack != null);
        // 🔴 内置噪声的预热**必须在这里**：视图若在 render pass **打开之后**才首建，
        //   createCommandEncoder 会被原版拒（h10 实测原文 "Close the existing render pass..."）。
        //   包自己供给了噪声 ⇒ 一张用不上的 64×64 都不建（日志与显存都不许假装还在兜底）。
        if (noise.usesBuiltin()) {
            builtinNoiseView();
        }
        // 🔖 一次性 = 每份绑定表一次：本方法只在指纹变化时走到，天然不刷屏（h34 的 499 行教训）；
        //   而「噪声频率与包设计不符」这件事必须留在日志里（X11 不容静默降级）。
        if (noise.mustDisclose()) {
            VkDisp.LOGGER.warn("vkdisp: [GAP-025] {}", noise.disclosure());
        } else if (noise.source() == NoiseSamplerSource.Source.PACK) {
            VkDisp.LOGGER.info("vkdisp: [GAP-025] noisetex 用包声明的真值："
                    + "texture.{}='{}'（内置 {}x{} 不参与）",
                    noise.declaredName(), noise.declaredPath(),
                    NoiseSamplerSource.BUILTIN_SIZE, NoiseSamplerSource.BUILTIN_SIZE);
        }
    }

    /** 按当前绑定表把每张自定义纹理解码 + 上传；单张失败只让它**缺席**（可见，不静默）。 */
    private static Map<String, TextureTarget> loadAll() {
        Map<String, TextureTarget> next = new LinkedHashMap<>();
        String pack = desiredPack;
        java.nio.file.Path inventory = desiredInventory;
        if (pack == null || inventory == null || desired.isEmpty()) {
            return next;
        }
        ShaderPackScanner.DiscoveredPack discovered = ShaderPackScanner.scan(inventory).packs().stream()
                .filter(p -> pack.equals(p.name()))
                .findFirst().orElse(null);
        if (discovered == null) {
            VkDisp.LOGGER.error("vkdisp: [GAP-009] 自定义纹理无法加载：库存里找不到包 '{}'（bindings={}）",
                    pack, desired.size());
            return next;
        }
        ShaderPackRepository.MountPlan plan;
        try {
            plan = ShaderPackRepository.plan(discovered);
        } catch (Exception e) {
            VkDisp.LOGGER.error("vkdisp: [GAP-009] 挂载规划失败，自定义纹理全部缺席", e);
            return next;
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
        return next;
    }

    /** 某采样器的真值视图；未加载/失败返回 {@code null}（调用方必须给显式占位）。 */
    public static @Nullable GpuTextureView view(String samplerName) {
        // 🔴 GAP-025：包声明的自定义纹理**优先于**内置噪声 —— 旧实现在这里无条件返回内置图，
        //   于是 BSL 的 texture.noise=tex/noise.png（512x512）永远取不到，噪声/抖动频率全错。
        //   查找按「声明名」而非采样器名：noisetex 的声明名是 noise（别名表见 NoiseSamplerSource）。
        for (String declaredName : NoiseSamplerSource.declarationNames(samplerName)) {
            TextureTarget target = loaded.get(declaredName);
            if (target != null) {
                return target.getColorTextureView();
            }
        }
        if (NoiseSamplerSource.NOISE_SAMPLER.equals(samplerName)) {
            // 兜底：内置噪声（缺席原因已在 ensureReady 一次性点名，这里不再吭声）。
            return builtinNoiseView();
        }
        return null;
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // 🔖 **内置 noisetex = 兜底，不是首选**（GAP-025）。
    //   OptiFine 公开 API 事实：包不声明时 `noisetex` 由引擎供给一张噪声图（见 04-SPEC 的 OF 内建
    //   uniform 表口径）；但包**声明了** `texture.noise=...` 时，OF 语义是用包图覆盖它 ——
    //   BSL 正是这种包（shaders.properties:141 + program/final.glsl:41 `noiseTextureResolution=512`）。
    //   BSL 的 blue-noise 抖动 / 胶片颗粒 / 云（composite/composite5）采的就是这个采样器 ——
    //   完全不供图 = 这些效果采 colortex0 占位，数字会变但语义是假的（X9 不容许）；
    //   恒供内置 64×64 = 频率按 512 设计却采 64，同样是假语义（GAP-025）。
    //   确定性生成（固定种子）⇒ 同一构建两次运行逐 texel 相同，取证可比。
    // ────────────────────────────────────────────────────────────────────────────────

    /** 边长的唯一真源在 {@link NoiseSamplerSource}（日志文案与生成必须同一个数）。 */
    private static final int BUILTIN_NOISE_SIZE = NoiseSamplerSource.BUILTIN_SIZE;
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
                VkDisp.LOGGER.info("vkdisp: [GAP-025] builtin noisetex created: {}x{} (deterministic seed)",
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
