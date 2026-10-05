package dev.vkdisp.bridge;
/**
 * 【参考调研】GAP-017 修根第一步：带真实 mip 链的 colortex 池 / 原版 `RenderTarget` 的纹理生命周期
 * 0. 合规核对（第 0 步闸门）：参考对象 = 原版 26.3 `com.mojang.blaze3d.pipeline.RenderTarget`
 *    （Mojang EULA：只观察其「懒建 + resize 时整体重建 + close 旧资源」的公开做法与
 *    `device.createTexture(label, usage=15, format, w, h, 1, mipLevels)` 的调用形状，零源码搬运）；
 *    `GpuDevice#createTextureView(tex, baseMipLevel, mipLevels)`（字节码核实存在，javap 一致）。
 *    → 能否并入 MIT：可以（独立实现）。参考模组（VulkanMod/Sulkan）零接触。
 * 1. 官方/主实现：原版主目标/中间目标全部 **mipLevels=1**（源码逐字核实）—— 它从不需要给
 *    采样器暴露 mip 链；而 OF 语义要求包声明 `colortexNMipmapEnabled` 的目标带链 ⇒ 本类是
 *    「原版能力缺 + 包语义要」差集上的自行补充（GAP-017，先登记后编码 ✅）。
 * 2. 备选：① 复用 TextureTarget 再想办法改 mip —— 否决：它的 createTexture 调用在父类私有路径里，
 *    无任何参数可传（源码级核实 `RenderTarget`：`createTexture(..., 1, 1)` 写死）；
 *    ② 每帧重建 —— 否决：GPU 对象 churn = h25/h33 那族资源问题的形状。resize 才重建（与原版同构）。
 * 3. 差异点：每槽一张多级纹理 + **一次性建全**每级的视图（级别数 ~10，视图数量有界；
 *    不每帧造视图）。引擎无 generateMips ⇒ 金字塔由 `FrameApi` 的降采样 pass 逐级填（本类只给能力）。
 * 4. 许可证：MIT，零第三方复制。
 * 5. 性能基线：止血路径（诊断档开着才画）；降采样 pass 每帧 O(槽 × 级) 个小 pass，
 *    lavapipe 上代价可忽略——按指令不做性能结论。
 */
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.jspecify.annotations.Nullable;

/** colortex 池：每槽一张带完整 mip 链的 RGBA8 纹理 + 每级一个视图。渲染线程独占，无锁。 */
final class ColortexPool {

    /** usage=15：COPY_DST|COPY_SRC|TEXTURE_BINDING|RENDER_ATTACHMENT（与原版 RenderTarget 同值，字节码核实）。 */
    private static final int USAGE = 15;

    static final class Slot {
        GpuTexture texture;
        GpuTextureView fullView;
        GpuTextureView[] mipViews;
        int width;
        int height;
        int levels;

        void close() {
            if (mipViews != null) {
                for (GpuTextureView v : mipViews) {
                    if (v != null) {
                        v.close();
                    }
                }
                mipViews = null;
            }
            if (fullView != null) {
                fullView.close();
                fullView = null;
            }
            if (texture != null) {
                texture.close();
                texture = null;
            }
        }
    }

    private Slot[] slots = new Slot[0];

    /** 级别数 = floor(log2(max(w,h))) + 1。纯函数，可单测。 */
    static int levelCount(int width, int height) {
        int m = Math.max(1, Math.max(width, height));
        return 32 - Integer.numberOfLeadingZeros(m - 1 == 0 ? 1 : m - 1) + 1;
    }

    int size() {
        return slots.length;
    }

    @Nullable GpuTexture texture(int slot) {
        return inRange(slot) ? slots[slot].texture : null;
    }

    @Nullable GpuTextureView view(int slot) {
        return inRange(slot) ? slots[slot].fullView : null;
    }

    /** 第 slot 槽第 level 级的视图（level 0 = 全分辨率 ⇒ 与 view(slot) 同物）。 */
    @Nullable GpuTextureView mipView(int slot, int level) {
        if (!inRange(slot) || level < 0 || level >= slots[slot].levels) {
            return null;
        }
        return slots[slot].mipViews[level];
    }

    int levels(int slot) {
        return inRange(slot) ? slots[slot].levels : 0;
    }

    /** 确保 slot 数量与尺寸就绪（尺寸变化 ⇒ 重建 + 关旧，与原版 resize 同构）。 */
    void ensure(int slotCount, int width, int height, com.mojang.renderpearl.api.device.GpuDevice device) {
        if (slots.length != slotCount) {
            closeRange(slots.length, slotCount > slots.length ? slots.length : slotCount);
            Slot[] next = new Slot[Math.max(0, slotCount)];
            System.arraycopy(slots, 0, next, 0, Math.min(slots.length, next.length));
            slots = next;
        }
        for (int i = 0; i < slots.length; i++) {
            Slot s = slots[i];
            if (s == null) {
                slots[i] = create(i, width, height, device);
            } else if (s.width != width || s.height != height) {
                s.close();
                slots[i] = create(i, width, height, device);
            }
        }
    }

    private Slot create(int index, int width, int height, com.mojang.renderpearl.api.device.GpuDevice device) {
        Slot s = new Slot();
        s.width = width;
        s.height = height;
        s.levels = levelCount(width, height);
        s.texture = device.createTexture(
                () -> "vkdisp gbuffer colortex" + index, USAGE, GpuFormat.RGBA8_UNORM,
                width, height, 1, s.levels);
        s.fullView = device.createTextureView(s.texture);
        s.mipViews = new GpuTextureView[s.levels];
        for (int l = 0; l < s.levels; l++) {
            s.mipViews[l] = device.createTextureView(s.texture, l, 1);
        }
        return s;
    }

    private boolean inRange(int slot) {
        return slot >= 0 && slot < slots.length && slots[slot] != null
                && slots[slot].texture != null;
    }

    private void closeRange(int from, int to) {
        for (int i = Math.min(from, slots.length - 1); i >= 0 && i < Math.min(to, slots.length); i++) {
            if (slots[i] != null) {
                slots[i].close();
                slots[i] = null;
            }
        }
    }

    void closeAll() {
        closeRange(0, slots.length);
    }
}
