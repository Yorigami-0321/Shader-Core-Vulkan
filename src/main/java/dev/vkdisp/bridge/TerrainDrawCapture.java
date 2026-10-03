package dev.vkdisp.bridge;
/**
 * 【参考调研】H 线 M-05 捕获侧 / 原版帧图执行顺序（`FrameGraphBuilder.execute`）+ NeoForge `FrameGraphSetupEvent`
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 原版 26.3 `LevelRenderer#render` 的语句顺序（随 MDG 分发的 sources jar：
 *    第 249 行 `fireFrameGraphSetup` → 第 271-275 行 `prepareChunkRenders*` →
 *    第 277 行 `addMainPass` → 第 286 行 `frame.execute(...)`）；
 *    ② 原版 `FrameGraphBuilder.execute` / `FramePass#executes` 的**延迟执行**语义
 *    （pass 体是 `Runnable`，在 `execute()` 里才跑，不是加 pass 时就跑）；
 *    ③ NeoForge 26.3 `FrameGraphSetupEvent`（LGPL-2.1：只观察事件签名与触发时机）。
 *    许可证：Mojang EULA + NeoForge LGPL-2.1。**只读源码得出「顺序事实」，零代码搬运。**
 *    → 能否并入本项目（MIT）：可以 —— 本文件为独立编写的状态持有类
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：NeoForge 官方 {@code FrameGraphSetupEvent}（帧图装配期唯一官方扩展点）。
 * 2. 备选：① 在 `addMainPass`（M-04）里做 ⇒ 那是**方案 B**，要改原版 pass 的附件语义，
 *    风险高一档；② 在事件里就地画地形 ⇒ **不可行**，事件触发时（249 行）地形 draw 数据
 *    尚未创建（271-275 行），且事件拿不到 `FrameGraphBuilder` 之外的可写句柄。
 * 3. 我们的差异点：**只保存引用、只打埋点，不驱动任何渲染**。
 *    本轮只验证「捕获 + 时序」这一条前提，**不**在本类里画地形
 *    （那是下一步，且必须先证明多附件地形管线可用）。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：热路径但极轻（每帧 1 次 volatile 写）；不做性能优化。
 */
import dev.vkdisp.VkDisp;
import dev.vkdisp.VkDispConfig;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M-05 捕获侧：保存 {@code LevelRenderer#prepareChunkRenders*} 的返回值引用 + 埋点。
 *
 * <p><b>本轮只回答一个问题</b>：「方案 A 需要的两个前提成立吗？」
 * <ol>
 *   <li><b>捕获得到</b>：注入点真的被走到，且拿到的不是 null；</li>
 *   <li><b>时序成立</b>：捕获发生在帧图 pass 体执行<b>之前</b>
 *       ⇒ 我方 pass 体执行时引用已就绪（这是整个方案 A 成立的前提，
 *       若反了则我方 pass 只能拿到上一帧的引用）。</li>
 * </ol>
 *
 * <p>🔖 <b>为什么时序要单独验</b>：原版顺序是
 * {@code fireFrameGraphSetup(249)} → {@code prepareChunkRenders*(271-275)} →
 * {@code addMainPass(277)} → {@code frame.execute(286)}。
 * pass 体是 {@code Runnable}，在 {@code execute()} 里才跑（286 > 271）⇒ 时序成立。
 * 但这是**读源码得出的推论**，必须实测确认 —— 推论对而运行时不成立，是本项目踩过的坑
 * （见 {@code evidence/h01-…} §4 的截图矛盾）。
 */
public final class TerrainDrawCapture {

    /** 本帧捕获到的地形 draw 数据（null = 未启用 / 未捕获）。volatile：渲染线程写、任意线程读。 */
    private static volatile Object captured;

    /** 命中的注入方法名（诊断用；「哪条分支」是本机能力的关键事实）。 */
    private static volatile String capturedFrom;

    /** 累计捕获次数（每帧一次 ⇒ 数值本身可当帧数校验）。 */
    private static final AtomicLong CAPTURE_COUNT = new AtomicLong();

    /** 首次成功捕获已打日志。 */
    private static boolean firstLogged;

    private TerrainDrawCapture() {
    }

    /** M-05 开关（{@code mixin.captureTerrainDraws}）。 */
    public static boolean enabled() {
        return VkDispConfig.MIXIN_CAPTURE_TERRAIN_DRAWS.get();
    }

    /**
     * 注入点回调：保存引用（**只读捕获，不改原版返回值**）。
     *
     * @param source   命中的注入方法名（{@code prepareChunkRenders} / {@code …Indirect}）
     * @param returned 原版返回值（类型在 bridge 侧刻意保持为 {@code Object}，避免把原版类型泄进业务层）
     */
    public static void onCaptured(String source, Object returned) {
        if (!enabled()) {
            return;
        }
        captured = returned;
        capturedFrom = source;
        long count = CAPTURE_COUNT.incrementAndGet();
        if (!firstLogged && returned != null) {
            firstLogged = true;
            VkDisp.LOGGER.info(
                    "vkdisp: [M-05] terrain draws captured from {} (capture#{}, non-null=true;"
                            + " read-only capture, vanilla behaviour unchanged)",
                    source, count);
        }
    }

    /** 本帧捕获到的地形 draw 数据；未启用 / 未捕获 → {@code null}。 */
    public static Object current() {
        return captured;
    }

    /** 命中的注入方法名；未捕获 → {@code null}。 */
    public static String capturedFrom() {
        return capturedFrom;
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
        capturedFrom = null;
        CAPTURE_COUNT.set(0L);
        firstLogged = false;
    }
}