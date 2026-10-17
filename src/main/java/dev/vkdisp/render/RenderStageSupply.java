package dev.vkdisp.render;
/**
 * 【自行补充】GAP-028 ②：{@code renderStage} 这个 uniform 的<b>逐 draw 供值</b>
 *
 * <p>0. 合规核对（第 0 步闸门）：参考对象 = ① 本仓库自有的 {@code glsl/preprocess/RenderStages}
 *    （宏名与编号口径都在那里，本类<b>不另立第二份编号</b>）；② 原版 26.3 的绘制时机事实
 *    （{@code SkyRenderer#renderSun} 第 218-222 行 / {@code renderMoon} 第 224-229 行：太阳与月亮
 *    是<b>两次独立 draw</b>，同一张 {@code RenderPipelines.CELESTIAL} 管线）。
 *    外部候选（Iris / OptiFine）零接触（07-CONSTRAINTS L12）；许可证：本文件独立编写（LGPL-3.0-or-later）。
 *
 * <p>1. 官方/主实现：OF/Iris 在每次 draw 前把「这一批几何是天空里的哪一层」写进 {@code renderStage}。
 *
 * <p>2. 备选：① 让包侧改用别的量区分阶段 —— <b>否决</b>：包的写法是既成事实
 *       （BSL 逐字 {@code renderStage == MC_RENDER_STAGE_SUN}，{@code gbuffers_skytextured.glsl:89,92,101,107}），
 *       改包等于替包做决定；② 恒 0（= 之前的状态）—— <b>否决</b>：那意味着<b>所有</b>「是某阶段」的分支
 *       恒假，包会拿不到日月盘与自定义天空（GAP-028 ③ 那格），而它<b>编译得过</b>，所以症状是
 *       「画面少东西但日志全绿」这一族，不是响亮失败。
 *
 * <p>3. 我们的差异点：
 *       ① <b>编号只有单一真源</b>：值一律从 {@link dev.vkdisp.glsl.preprocess.RenderStages#valueOf}
 *          取（宏与供值必须同数，两处各写一遍迟早漂移 —— QD-02 口径）；
 *       ② <b>栈语义而不是裸 setter</b>：天空那一族的 draw 是嵌套在别人的 pass 里的，
 *          裸 setter 会「设了忘还原」，而漏还原的症状是<b>后面所有</b>程序读到上一笔的阶段
 *          —— 与 {@code CloudsIntoGbuffer} 当年删掉「压栈/还原」那次（M-10）同族事故；
 *          {@code Scope implements AutoCloseable} + try-with-resources 让「还原」不可能被漏；
 *       ③ <b>不认识的名字 ⇒ NONE + 点名</b>（不猜，X9），并把失败原文留着给自报行打印；
 *       ④ <b>不参与判定</b>：计数只是仪器（记忆里「纯函数三态 + 计数不参与判定」那条口径）。
 *
 * <p>4. 许可证核对：本项目 LGPL-3.0-or-later；零第三方代码并入。
 * <p>5. 性能基线：渲染线程上一个 {@code int} 读写 + 一次对象分配（scope 只在<b>开 pass 时</b>分配，
 *    不是每顶点/每帧热路径）。
 */
import dev.vkdisp.glsl.preprocess.RenderStages;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/** 当前 draw 的 {@code renderStage}（渲染线程独占；供值出口在 {@link OfUniformManager}）。 */
public final class RenderStageSupply {

    /** {@code MC_RENDER_STAGE_NONE} 的值（= 0；口径唯一真源在 {@link RenderStages}）。 */
    public static final int NONE = RenderStages.valueOf(RenderStages.MACRO_PREFIX + "NONE");

    private static final ThreadLocal<Integer> CURRENT = ThreadLocal.withInitial(() -> NONE);

    /** 仪器（不参与判定）：进入过几次阶段、最后一次是哪个、以及读错名字的次数。 */
    private static final AtomicInteger ENTERS = new AtomicInteger();
    private static final AtomicInteger UNKNOWN_NAMES = new AtomicInteger();

    /** 第一次读到不认识的名字时记原文（一次性 WARN 用）。 */
    private static volatile String @Nullable [] lastUnknown;

    private RenderStageSupply() {
    }

    /**
     * 进入某个阶段，返回的 scope 必须用 try-with-resources 关掉（关掉即恢复上一层阶段）。
     *
     * @param stage {@link RenderStages#STAGES} 里的名字（<b>不含</b>前缀），如 {@code "SKY"} / {@code "SUN"}
     */
    public static Scope enter(String stage) {
        int previous = CURRENT.get();
        Integer value = RenderStages.valueOf(RenderStages.MACRO_PREFIX + stage);
        if (value == null) {
            UNKNOWN_NAMES.incrementAndGet();
            lastUnknown = new String[] {stage};
            // 不认识 ⇒ 按 NONE 供（不是「沿用上一层」：沿用会把上一笔的阶段带到这一笔上，
            //    那正是本类要消灭的那一类事故）。
            value = NONE;
        } else {
            ENTERS.incrementAndGet();
        }
        CURRENT.set(value);
        return new Scope(previous);
    }

    /** 本帧这次 draw 该供的 {@code renderStage} 值。 */
    public static int current() {
        return CURRENT.get();
    }

    /** 仪器读数：进入过多少次真阶段 / 读过多少个不认识的名字 / 最后一次是哪个。 */
    public static int enters() {
        return ENTERS.get();
    }

    public static int unknownNameReads() {
        return UNKNOWN_NAMES.get();
    }

    /** 不认识的那个名字原文（{@code null} = 至今没有）；调用方<b>必须</b>把它打进自报行。 */
    public static @Nullable String lastUnknownName() {
        String[] record = lastUnknown;
        return record == null ? null : record[0];
    }

    /** 清仪器（测试隔离用；产品路径不调）。 */
    public static void resetForTests() {
        CURRENT.set(NONE);
        ENTERS.set(0);
        UNKNOWN_NAMES.set(0);
        lastUnknown = null;
    }

    /** 关掉即恢复进入前的阶段（漏关 = 后面的程序读到上一笔的阶段，所以做成 AutoCloseable）。 */
    public static final class Scope implements AutoCloseable {

        private final int previous;

        Scope(int previous) {
            this.previous = previous;
        }

        /** 本次进入后生效的阶段值。 */
        public int stage() {
            return CURRENT.get();
        }

        @Override
        public void close() {
            CURRENT.set(previous);
        }
    }
}
