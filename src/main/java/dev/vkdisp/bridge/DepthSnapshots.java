package dev.vkdisp.bridge;
/**
 * 【自行补充】GAP-023：把 gbuffer 深度在**各个时刻**存成快照，让 {@code depthtex0/1/2} 有可能不同源。
 *
 * <p>0. 合规核对：只调用公开 API（{@code GpuDevice#createTexture / createTextureView}、
 *    {@code CommandEncoder#copyTextureToTexture}）—— 与原版
 *    {@code RenderTarget#copyDepthFrom} 同一条 API
 *    （{@code blaze3d/pipeline/RenderTarget.java:75} 逐字就是这次调用）。
 *    许可证：Mojang EULA（原版）⇒ 只观察公开签名与调用形状，零源码搬运；参考模组零接触。
 *    → 能否并入本项目（MIT）：可以，本文件是独立实现。
 *
 * <p>1. 要修的缺陷（{@code docs/13-GAP-REGISTRY.md} GAP-023）：
 *    {@code FrameApi.chainResolver} 里 {@code depthtex*} 一律回同一张
 *    {@code MrtTerrainPass.depthView()} ⇒ 包里所有「比较两个深度层」的逻辑恒等失效。
 *    已知直接受害者：BSL {@code composite.glsl:333 z1 > z0}（半透明/水体识别）恒假；
 *    而 {@code gbuffers_water} 的自由 sampler 清单里逐字就有 {@code depthtex1}（真机 h48 实测）
 *    ⇒ 接水之前必须先有这一格。
 *
 * <p>2. OF 语义（按登记表那条口径实现）：三个名字是<b>同一张深度在三个时刻的快照</b>
 *    —— 0 = 不透明之后，1 = 半透明之后，2 = 常驻顶层之后。⇒ 不需要三套渲染，只需要若干次 blit。
 *
 * <p>3. 本类现在只填了<b>第一格</b>（{@link #OPAQUE}）。它买到什么、没买到什么，都要说清：
 *    ① 买到的：{@code depthtex0} 从「活深度」变成<b>不可变快照</b> ⇒ 之后任何写深度的 pass
 *      （云那一格关着 {@code mrt.cloudsNoDepthWrite} 时就会写深度）都改不了链看到的深度。
 *      这是一条今天没有的正确性属性。
 *    ② 没买到的：{@code depthtex1/2} 仍与 0 号同源。它俩今天回退到<b>活深度</b>，而
 *      「本帧没有半透明几何进 gbuffer」时这个回退在 OF 语义下恰好是正确值
 *      （没有半透明 ⇒ 半透明之后的深度 ≡ 不透明之后的深度）。
 *      🔴 所以本类<b>不关</b> GAP-023：要让 {@code z1 > z0} 真有意义，必须把地形 pass 拆成
 *      「不透明一段 + 半透明一段」并各取一格 —— blit 是 encoder 命令，
 *      <b>render pass 打开期间不能发</b>（h10 实测规则：pass 开着时新建 encoder 被 RenderPearl 拒绝）。
 *      那一步登记在 GAP-023 的「下一步」，不在这里假装做过。
 *
 * <p>4. 许可证核对：本项目 MIT；零第三方代码复制。
 * <p>5. 性能基线：每帧 1~3 次全屏 D32 blit。按用户指令本轮不做性能结论（{@code 17-NATIVE.md} §3.2）。
 */
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.vkdisp.VkDisp;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** 只在渲染线程调用；无锁（与 {@code ColortexPool} / {@code DepthGlProxy} 同口径）。 */
public final class DepthSnapshots {

    /** OF {@code depthtex0}：不透明几何之后。 */
    public static final int OPAQUE = 0;
    /** OF {@code depthtex1}：半透明（水）之后。 */
    public static final int TRANSLUCENT = 1;
    /** OF {@code depthtex2}：常驻顶层之后。 */
    public static final int TOP_LAYER = 2;

    /** 快照个数 = 三个 OF 时刻。 */
    public static final int COUNT = 3;

    // 🔴 位值取字面量，不写 `GpuTexture.USAGE_*`：测试源集<b>没有</b> renderpearl 类路径
    //   （build.gradle 只往 testImplementation 加 junit + joml；同一条边界记在
    //   DepthGlProxyTest 第 169 行起），而「名字 → 时刻」这条映射正是本缺陷的语义核心、
    //   必须能离线证死。纯函数体里一旦出现对 GpuTexture 的静态引用，测试一调它就 NoClassDefFoundError。
    //   字面量的口径出处 = ColortexPool.java:29-30（与原版 RenderTarget 同值，字节码核实：
    //   COPY_DST=1 | COPY_SRC=2 | TEXTURE_BINDING=4 | RENDER_ATTACHMENT=8 = 15）。
    static final int COPY_DST = 1;
    static final int COPY_SRC = 2;
    static final int TEXTURE_BINDING = 4;
    static final int RENDER_ATTACHMENT = 8;

    /** {@code COPY_DST | COPY_SRC | TEXTURE_BINDING} = 7；<b>不含</b> RENDER_ATTACHMENT（从不画进它）。 */
    public static int snapshotUsage() {
        return COPY_DST | COPY_SRC | TEXTURE_BINDING;
    }

    /** 快照格式名的唯一真源 = 活深度的格式（今天 {@code MrtTerrainPass} 建的是 D32_FLOAT）。 */
    public static String snapshotFormatName() {
        return "D32_FLOAT";
    }

    /** 唯一一处把字符串翻成枚举的地方（保持上面那条是纯字符串、可离线测）。 */
    private static GpuFormat snapshotFormat() {
        return GpuFormat.valueOf(snapshotFormatName());
    }

    /**
     * {@code depthtex<N>} → 第 N 号快照。<b>纯函数</b>：名字不是这个形态就返回 -1（调用方决定回退）。
     */
    public static int slotOf(String samplerName) {
        if (samplerName == null || !samplerName.startsWith("depthtex")) {
            return -1;
        }
        String digits = samplerName.substring("depthtex".length());
        if (digits.length() != 1 || digits.charAt(0) < '0' || digits.charAt(0) > '2') {
            return -1;
        }
        return digits.charAt(0) - '0';
    }

    // ── 每帧记账（纯 Java，可离线测） ────────────────────────────────────────────────

    /** 本帧已取过快照的下标；同一帧重复取同一格 = 「什么时候取」理解错了，不做幂等容忍。 */
    private static final List<Integer> TAKEN_THIS_FRAME = new ArrayList<>();
    private static long frames;
    private static boolean copyFailedWarned;
    private static boolean createdLogged;

    /** 每帧开头一次（调用点 = {@code MrtTerrainPass#ensureTargets}，在开任何 pass 之前）。 */
    public static void beginFrame() {
        TAKEN_THIS_FRAME.clear();
        frames++;
    }

    /** 本帧是否已为 {@code depthtex1} 备好<b>独立</b>内容（= 水真的画进了 gbuffer）。 */
    public static boolean hasTranslucentSnapshot() {
        return TAKEN_THIS_FRAME.contains(TRANSLUCENT);
    }

    /**
     * 第 slot 号快照本帧是否就位。<b>纯函数</b>，且刻意与 {@link #view(int)} 分开：
     * {@code view} 的返回类型是 renderpearl 的 {@code GpuTextureView}，测试源集里没有它
     * ⇒ javac 连调用都不允许（本轮实测：{@code error: cannot access GpuTextureView}）。
     * 「能不能问它有没有」这件事必须可离线证死，所以单独开这一格。
     */
    public static boolean has(int slot) {
        return slot >= 0 && slot < COUNT && TAKEN_THIS_FRAME.contains(slot);
    }

    /** 自报用：本帧取了几格、哪几格。 */
    public static String describeFrame() {
        return "taken=" + TAKEN_THIS_FRAME + "/3 (0=opaque,1=translucent,2=top) terrainFrames=" + frames;
    }

    // ── GPU 资源本体：放在<b>嵌套类</b>里，类初始化才是惰性的 ──────────────────────────
    //
    // 🔴 为什么单独一层：数组字段 `GpuTexture[]` 的<b>创建</b>就要加载 GpuTexture，而它在测试
    //   运行时里不存在 ⇒ 只要这些字段留在外层类，DepthSnapshotsTest 连 slotOf 都调不动
    //   （类初始化先炸）。挪进嵌套类之后，只有真碰 GPU 的路径会加载它。

    private static final class Resources {
        private static final GpuTexture[] TEXTURES = new GpuTexture[COUNT];
        private static final GpuTextureView[] VIEWS = new GpuTextureView[COUNT];
        private static int width;
        private static int height;

        private static void release() {
            for (int i = 0; i < COUNT; i++) {
                GpuTextureView oldView = VIEWS[i];
                GpuTexture oldTexture = TEXTURES[i];
                VIEWS[i] = null;
                TEXTURES[i] = null;
                if (oldView != null) {
                    oldView.close();
                }
                if (oldTexture != null) {
                    oldTexture.close();
                }
            }
            width = 0;
            height = 0;
        }
    }

    /**
     * 懒建 / 随主目标尺寸重建。<b>必须在开任何 render pass 之前</b>调用（建纹理要新 encoder，
     * h10 实测规则）⇒ 调用点 = {@code MrtTerrainPass#ensureTargets}。
     */
    public static void ensure(int targetWidth, int targetHeight) {
        if (targetWidth <= 0 || targetHeight <= 0) {
            return;
        }
        if (Resources.VIEWS[OPAQUE] != null && Resources.width == targetWidth
                && Resources.height == targetHeight) {
            return;
        }
        Resources.release();
        GpuDevice device = RenderSystem.getDevice();
        for (int i = 0; i < COUNT; i++) {
            final int index = i;
            Resources.TEXTURES[i] = device.createTexture(
                    () -> "vkdisp depthtex" + index + " snapshot",
                    snapshotUsage(), snapshotFormat(), targetWidth, targetHeight, 1, 1);
            Resources.VIEWS[i] = device.createTextureView(Resources.TEXTURES[i]);
        }
        Resources.width = targetWidth;
        Resources.height = targetHeight;
        if (!createdLogged) {
            createdLogged = true;
            VkDisp.LOGGER.info("vkdisp: [GAP-023] 深度快照已建: {}x{} format={} usage={}"
                            + "（3 张 = depthtex0/1/2 的三个时刻）",
                    targetWidth, targetHeight, snapshotFormatName(), snapshotUsage());
        }
    }

    /**
     * 把<b>活深度</b> blit 进第 {@code slot} 号快照。
     *
     * @return 真的拷了才 {@code true}；任何前置条件不满足都 {@code false} 并响亮一次（X11）
     */
    public static boolean take(int slot, @Nullable GpuTexture liveDepth) {
        if (slot < 0 || slot >= COUNT || liveDepth == null || Resources.VIEWS[slot] == null) {
            return false;
        }
        if (TAKEN_THIS_FRAME.contains(slot)) {
            return false;
        }
        try {
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                    liveDepth, Resources.TEXTURES[slot], 0, 0, 0, 0, 0, Resources.width, Resources.height);
        } catch (RuntimeException t) {
            if (!copyFailedWarned) {
                copyFailedWarned = true;
                VkDisp.LOGGER.warn("vkdisp: [GAP-023] 深度 blit 抛 ⇒ depthtex* 回退到活深度视图。原因={}"
                        + "（本行只打一次；持续失败会让上面那条退化变成静默）", t.toString());
            }
            return false;
        }
        TAKEN_THIS_FRAME.add(slot);
        return true;
    }

    /** 第 slot 号快照的视图；没建 / 本帧没取过 ⇒ {@code null}（调用方必须<b>显式</b>回退）。 */
    @Nullable
    public static GpuTextureView view(int slot) {
        if (slot < 0 || slot >= COUNT || !TAKEN_THIS_FRAME.contains(slot)) {
            return null;
        }
        return Resources.VIEWS[slot];
    }

    private DepthSnapshots() {
    }
}
