package dev.vkdisp.render;
/**
 * 【自行补充】GAP-022 的**接线闸门**：深度那一半与投影矩阵那一半**成对**切换的唯一出口。
 * 0. 合规核对（第 0 步闸门，通过）：参考对象 = 本仓库自有
 *    ① {@link GlDepthConvention}（矩阵那一半的换算，h48w 已证 + 四条数值判据）；
 *    ② {@code bridge/DepthGlProxy}（深度那一半，开关同为 {@code mrt.depthGlProxy}）；
 *    ③ {@code evidence/h48w-gap022-real-matrices.md} §四那张「可写进实现的那一份」表
 *    （{@code gbufferProjection = D2·P} / {@code gbufferProjectionInverse = P⁻¹·D2inv} /
 *    {@code depthtex0 = 1 − z_engine} / previous 用**各自那一帧**的矩阵）。
 *    外部候选（Iris / OptiFine / VulkanMod）零接触；许可证：本文件为独立编写的纯 Java 实现（MIT），
 *    不含任何 GPL / LGPL / ARR 代码。
 * 1. 官方/主实现：{@link #forward} + {@link #backward} + {@link #takePreviousEngine}
 *    三个出口，配 {@link #reportLine} / {@link #reportThrottled} 那条自报。
 * 2. 备选：在 {@code OfUniformManager#gather} 里就地写两个三元式 —— <b>否决</b>：
 *    「上一帧」那个值背后有**历史存储**，两处各抄一遍表达式就会有一份存成「出口翻完之后」的矩阵，
 *    下一帧再翻一次 = 把「半翻」从深度那一侧搬到矩阵这一侧（D2 <b>不是</b>对合：
 *    {@code z'' = 4z − w}，所以双翻不会「看起来像没翻」，而是像「阴影有了但位置全歪」）。
 *    而 {@code gather} 依赖原版运行态、单测跑不到 ⇒ 那条错构建期永远抓不住（QD-02 同族）。
 * 3. 我们的差异点：历史槽（<b>引擎口径</b>）搬进本类 ⇒「存什么」与「只在出口翻」在同一文件里可证，
 *    {@code DepthConventionPairTest} 因此能离线钉住双翻。
 * 4. 许可证核对结论：本项目 MIT；零第三方代码并入（07-CONSTRAINTS §〇 P1 / L5-L8）。
 * 5. 性能基线：每帧一次 gather ⇒ 三个矩阵小对象 + 一次求逆（求逆本来就有）；
 *    开关关掉时只多一次 {@code new Matrix4f} 拷贝，与 gather 里既有的那几次同量级，不做结论（T14）。
 */
import java.util.function.Consumer;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * 「本帧交给包的投影矩阵」的唯一出口（含上一帧那一本）。
 *
 * <p><b>它修的是什么</b>：{@code mrt.depthGlProxy} 此前<b>只翻深度、矩阵一个没动</b> ——
 * 那正是 {@link GlDepthConvention} 类注释里「半翻比不翻更坏」的状态：包先读
 * {@code depthtex0 = 1 − z}（GL 口径），再拿<b>引擎</b>的 {@code gbufferProjectionInverse}
 * 去反解同一个像素 ⇒ 两套口径在同一帧里混用，比整套留在引擎口径更难归因。
 * 本类让开关同时决定<b>深度与四个投影矩阵</b>，一个帧内只有一个答案。
 *
 * <p>🔖 <b>表示不变式（本类存在的全部理由）</b>：历史槽里存的永远是<b>引擎口径</b>矩阵，
 * 翻只发生在出口（{@link #forward}）。于是「上一帧被翻了几次」这个问答题的机械化答案是
 * <b>恰好一次</b>，与调用次序、与开关在运行中被改过几次都无关。
 *
 * <p>🔖 无 {@code net.minecraft} / {@code com.mojang.renderpearl} 类型 ⇒ 这一段口径逻辑能离线单测
 * （{@code gather} 本身不能 —— 隔离理由与 {@code NightVisionSupply} 同一条）。
 */
final class DepthConventionPair {

    /**
     * 自报节流周期（帧）：与 {@code [GAP-022/matrix]} 那行同频。
     * 🔖 不节流的代价写在这里而不是「反正没人看」：gather 每帧最多被调 4 次
     * （{@code FrameApi} 两处 + {@code TerrainPipelineApi} 两处），一条不节流的 INFO
     * 就是 h34 那 499 行 / h33 那 2702 行的形状。
     */
    static final int REPORT_EVERY_FRAMES = 300;

    /**
     * 上一帧交给包的投影矩阵 —— <b>引擎口径</b>那一份（不是出口翻完之后的那一份，见类注释的不变式）。
     * 首帧前为 null；换世界由调用方经 {@link #takePreviousEngine} 的 {@code worldSwitch} 对齐。
     */
    private static @Nullable Matrix4f previousEngineProjection;

    /** 上一次自报的口径（null = 从未自报 ⇒ 第一帧必打）与帧号。 */
    private static @Nullable Boolean reportedConvention;
    private static int reportedFrame;

    /** 本帧快照：true = GL 口径（与 {@code DepthGlProxy} 的翻转同帧），false = 引擎原样。 */
    private final boolean glConvention;

    private DepthConventionPair(boolean glConvention) {
        this.glConvention = glConvention;
    }

    /**
     * 取本帧的口径快照。
     *
     * @param glConvention {@code mrt.depthGlProxy} 的值 —— <b>一次读取、四个出口共用</b>，
     *                     所以同帧内不可能出现「深度翻了、矩阵没翻」这种半翻。
     */
    static DepthConventionPair forFrame(boolean glConvention) {
        return new DepthConventionPair(glConvention);
    }

    boolean glConvention() {
        return glConvention;
    }

    /** 交给包的 {@code gbufferProjection}（关 = 引擎原样，开 = {@code D2·P}）。 */
    Matrix4f forward(Matrix4f engineProjection) {
        return glConvention
                ? GlDepthConvention.glProjection(engineProjection)
                : new Matrix4f(engineProjection);
    }

    /**
     * 交给包的 {@code gbufferProjectionInverse}（关 = 原样，开 = {@code P⁻¹·D2inv}）。
     *
     * <p>🔖 入参是<b>引擎口径的逆</b>而不是引擎投影：h48w §三 判据 C 证的是
     * {@code (D2·P)⁻¹ = P⁻¹·D2inv}，复合方向与 {@link #forward} <b>相反</b>
     * （{@code D2inv} 作用在输入上）。所以这里必须吃「已经求好逆的那一份」，
     * 不能顺手在这里再求一次逆 —— 再求一次就得到 {@code (P⁻¹)⁻¹ = P}，
     * 一个看着像矩阵、实际是正矩阵的东西（{@link GlDepthConvention#glProjectionInverse} 同一条警告）。
     */
    Matrix4f backward(Matrix4f engineProjectionInverse) {
        return glConvention
                ? GlDepthConvention.glProjectionInverse(engineProjectionInverse)
                : new Matrix4f(engineProjectionInverse);
    }

    /**
     * 取出「上一帧」的投影（<b>引擎口径</b>），同时把本帧那一份存成新历史。
     *
     * <p>取与存在同一个方法里：调用方拿到的永远是<b>没翻过</b>的那一份，于是出口只可能是
     * {@link #forward} 那一次翻。返回引擎口径而不是返回「可以交给包的矩阵」，
     * 是为了让 {@code values.put("gbufferPreviousProjection", pair.forward(...))} 这一行
     * 与当帧那一行<b>共用同一个出口</b>（两个三元式各写一遍 = 本轮要修的东西）。
     *
     * @param worldSwitch 换世界（调用方比对的**世界引用**变了）⇒ 历史与当帧对齐，
     *                    跨维度的旧相机当历史没有意义（沿用 gather 既有口径）。
     */
    Matrix4f takePreviousEngine(Matrix4f engineThisFrame, boolean worldSwitch) {
        Matrix4f previous = worldSwitch || previousEngineProjection == null
                ? engineThisFrame
                : previousEngineProjection;
        previousEngineProjection = engineThisFrame;
        return previous;
    }

    /**
     * 自报行的<b>文本本体</b> —— 判据对象自己说自己是哪种口径（本项目核心纪律；
     * {@code evidence/h48-flicker-and-readback.md} §二十二 的反面教训是
     * 「判据只在没人读的字段里」，而<b>缺一行</b>同样不能留：所以两种状态<b>各有一条</b>，
     * 日志里没有这行 = 接线没跑到，而不是「开关是关的」。
     */
    static String reportLine(boolean glConvention, int frame) {
        return "vkdisp: [GAP-022] depth convention = " + (glConvention
                ? "GL (depthtex=1-z, gbufferProjection=D2·P, gbufferProjectionInverse=Pinv·D2inv,"
                        + " previous 同口径；深度与矩阵同一帧一起切) frame=" + frame
                : "ENGINE (reverse Z, 原样；深度代理不写、投影矩阵不翻 —— 两者一起保持不翻)"
                        + " frame=" + frame);
    }

    /**
     * 节流自报：口径<b>变了立刻打</b>，否则每 {@link #REPORT_EVERY_FRAMES} 帧一次。
     *
     * <p>🔖 sink 由调用方给（生产 = {@code VkDisp.LOGGER::info}，单测 = 收集器）：
     * 本类不许依赖 {@code VkDisp} / {@code com.mojang.logging} —— 那两个在测试运行时里加载不了，
     * 而「自报在两种状态下都存在」正是必须能被构建期证明的一条。
     */
    static void reportThrottled(boolean glConvention, int frame, Consumer<String> sink) {
        // 🔖 必须走 booleanValue()：`Boolean == boolean` 靠自动拆箱才比的是值，
        //    而一旦两边都是包装类型（例如有人把参数改成 Boolean）就退化成引用比较
        //    —— 于是「口径没变也每帧打一条」这种既不报错又刷屏的形态。显式拆箱一次，钉死语义。
        boolean unchanged = reportedConvention != null
                && reportedConvention.booleanValue() == glConvention;
        boolean due = !unchanged || frame - reportedFrame >= REPORT_EVERY_FRAMES;
        if (!due) {
            return;
        }
        reportedConvention = glConvention;
        reportedFrame = frame;
        sink.accept(reportLine(glConvention, frame));
    }

    /** 清历史与自报状态（<b>仅供单测</b>；生产不清 —— 清了会把本帧当成上一帧）。 */
    static void resetForTest() {
        previousEngineProjection = null;
        reportedConvention = null;
        reportedFrame = 0;
    }
}
