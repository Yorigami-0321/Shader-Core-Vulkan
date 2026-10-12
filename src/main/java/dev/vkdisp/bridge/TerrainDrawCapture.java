package dev.vkdisp.bridge;
/**
 * 【参考调研】R2 / M-06：在原版 {@code LevelRenderer#addMainPass} 的 HEAD 只读捕获地形 draw 与雾切片
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 原版 26.3 {@code net/minecraft/client/renderer/LevelRenderer.java} 的
 *    第 **396-402** 行（{@code private void addMainPass(FrameGraphBuilder,
 *    FeatureRenderDispatcher$PreparedFrame, GpuBufferSlice terrainFog,
 *    ChunkSectionsToRender, boolean)}）与第 **277** 行那一次<b>无条件</b>调用
 *    （indirect 分支第 272 行、常规分支第 274 行 ⇒ 两支在此汇合），以及第 **436** 行
 *    {@code RenderSystem.setShaderFog(terrainFog)} 那一行（GAP-038 的官方口径就长这样）；
 *    ② Mixin 0.8.7 的 {@code @Inject}/{@code @At} **注解签名**（javap 核实常量池，不读实现）。
 *    许可证：Mojang EULA（只观察签名、语句与时序）+ Mixin MIT。
 *    → 能否并入本项目（MIT）：可以 —— 本文件是独立编写的桥接侧，零源码搬运
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：原版自己就在 pass 体第一句把 {@code terrainFog} 交给 {@code setShaderFog}
 *    （第 436 行，位于第 455-463 行建 pass 之前）⇒ 我方 pass 体照同一个口径补一次，不自造第二套雾。
 * 2. 备选：① 旧 M-05（{@code prepareChunkRenders*} 两个 {@code @At("RETURN")}）—— **已被 M-06 替代**
 *    （两点收一点，且本点同时带来 {@code terrainFog}，旧点根本拿不到它）；
 *    ② {@code FrameGraphSetupEvent} —— **已源码级证伪**：事件在第 249 行触发，而
 *    {@code chunkSectionsToRender} 在第 271-275 行才建出来 ⇒ 事件实参里没有这两个东西。
 * 3. 我们的差异点：**只读捕获**（X25 / X11）。本类不取消、不改写任何原版行为；
 *    关掉它，地形渲染与原版逐字一致，代价只是我方 MRT pass 不画。
 *    🔖 与 M-05 的第二个差别：字段从 {@code volatile Object} 变成<b>类型化</b>的
 *    {@code ChunkSectionsToRender} + {@code GpuBufferSlice} ⇒ 调用点的 {@code instanceof} 那一段
 *    「捕获到别的东西」的兜底分支<b>物理消失</b>（类型由注入点保证，不再靠运行时猜）。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：热路径但极轻 —— 每帧两次引用赋值；不做性能优化（17 §3.2）。
 */
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.vkdisp.VkDisp;
import dev.vkdisp.VkDispConfig;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.jspecify.annotations.Nullable;

/**
 * M-06 捕获侧：保存原版 {@code addMainPass} 形参里的地形 draw 数据 + 雾切片（渲染线程写、任意线程读）。
 *
 * <p><b>它回答的两件事</b>（与旧 M-05 同问，换了取法）：
 * <ol>
 *   <li><b>捕获得到</b>：注入点真被走到，且两个引用都非 null；</li>
 *   <li><b>时序成立</b>：捕获在帧图 pass 体执行<b>之前</b> ⇒ 我方 pass 体拿到的是本帧数据。
 *       原版顺序：{@code fireFrameGraphSetup(249)} → {@code prepareChunkRenders*(271-275)}
 *       → {@code addMainPass(277)}（= 本注入点）→ {@code frame.execute(286)}（pass 体在此才跑）。</li>
 * </ol>
 */
public final class TerrainDrawCapture {

    /** 本帧捕获到的地形 draw 数据（null = 未启用 / 未捕获）。类型由注入点保证，不再存 Object。 */
    private static volatile @Nullable ChunkSectionsToRender captured;

    /** 本帧捕获到的地形雾切片（原版 {@code setShaderFog} 用的就是它，{@code GAP-038}）。 */
    private static volatile @Nullable GpuBufferSlice capturedFog;

    /** 捕获对象的<b>运行期变体名</b>（{@code DrawIndirect} / {@code DrawSeparate}）—— 本机走哪条分支的关键事实。 */
    private static volatile @Nullable String capturedVariant;

    /** 累计捕获次数（每帧一次 ⇒ 数值本身可当帧数校验）。 */
    private static final AtomicLong CAPTURE_COUNT = new AtomicLong();

    /** 首次成功捕获已打日志。 */
    private static boolean firstLogged;

    private TerrainDrawCapture() {
    }

    /** M-06 开关（{@code mixin.captureMainPassArgs}）。 */
    public static boolean enabled() {
        return VkDispConfig.MIXIN_CAPTURE_MAIN_PASS_ARGS.get();
    }

    /**
     * 注入点回调：保存两个引用（<b>只读捕获，不改原版任何行为</b>）。
     *
     * @param draws 原版形参 —— 地形 draw 数据（两支汇成这一点，故不用像旧 M-05 那样注两个方法）
     * @param terrainFog 原版形参 —— 地形雾切片（第 436 行交给 {@code setShaderFog} 的就是它）
     */
    public static void onCaptured(@Nullable ChunkSectionsToRender draws, @Nullable GpuBufferSlice terrainFog) {
        if (!enabled()) {
            return;
        }
        captured = draws;
        capturedFog = terrainFog;
        capturedVariant = draws == null ? null : draws.getClass().getSimpleName();
        long count = CAPTURE_COUNT.incrementAndGet();
        if (!firstLogged && draws != null) {
            firstLogged = true;
            VkDisp.LOGGER.info("vkdisp: [M-06] terrain draws + fog captured at LevelRenderer#addMainPass HEAD"
                    + " (capture#{}, variant={}, fogPresent={}; read-only capture, vanilla behaviour unchanged)",
                    count, capturedVariant, terrainFog != null);
        }
    }

    /**
     * 本帧捕获到的地形 draw 数据；未启用 / 未捕获 → {@code null}。
     *
     * <p>🔖 调用点（{@code MrtTerrainPass#drawTerrain}）必须显式判 {@code null} ——
     * 「M-06 未开启」与「已开启但本帧没捕获」都会走到这里。
     */
    @Nullable
    public static ChunkSectionsToRender current() {
        return captured;
    }

    /**
     * 本帧的地形雾切片（{@code GAP-038}：我方 pass 体第一行要用它调 {@code setShaderFog}）。
     *
     * <p>⚠️ 与 {@link #current()} 同源同代 ⇒ 拿到 draws 时它必然也非 null（两者是同一次形参赋值），
     * 因此调用点<b>不需要</b>再各自判空一次；但<b>不要</b>把这条当编译期保证 ——
     * 判空仍由调用点的 draws 检查承担。
     */
    @Nullable
    public static GpuBufferSlice currentFog() {
        return capturedFog;
    }

    /** 捕获对象的运行期变体名（诊断用；未捕获 → {@code null}）。 */
    @Nullable
    public static String capturedVariant() {
        return capturedVariant;
    }

    /** 累计捕获次数（纯 long 视图，供埋点与测试语义对齐）。 */
    public static long captureCount() {
        return CAPTURE_COUNT.get();
    }

    /** 是否至少成功捕获过一次非 null 引用（门闩）。 */
    public static boolean hasCaptured() {
        return captured != null;
    }

    /** 清空状态（仅供单测；生产路径不清 —— 清空会让 pass 体拿到 null 而静默跳过）。 */
    static void resetForTest() {
        captured = null;
        capturedFog = null;
        capturedVariant = null;
        CAPTURE_COUNT.set(0L);
        firstLogged = false;
    }
}
