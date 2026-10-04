package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】地形 MRT pass 的**逐槽清屏色**决策（修「诊断色泄漏进用户可见画面」）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库自有的 {@code dev.vkdisp.bridge.MrtTerrainPass#diagnosticClear}
 *    （被替换掉的旧实现，MIT 自有代码）与其上的注释 —— 该注释写明了诊断色当初**为什么**这么选；
 *    ② docs/13-GAP-REGISTRY.md GAP-009 / GAP-011 与 {@code evidence/h27b-isolated-lane-replication.md} §六
 *    （绿天空的实测定位：本类要修的就是那一条）。
 *    全部为仓库内自有文档与自有代码，不受版权保护。
 *    → 能否并入本项目（MIT）：可以（本文件为独立编写的纯 Java 决策类）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：无（原版没有「gbuffer 槽清屏成什么」这个概念）。
 * 2. 备选：
 *    <ul>
 *      <li>① 直接把槽 0 从纯绿改成黑 —— <b>否决</b>：旧实现的注释明确写了原因，
 *          {@code MrtPlan} 给槽 0 的<b>指纹恰好是 0.0 = 黑</b>，
 *          一旦「什么都没画」与「画了但很暗」同时发生，两者在截图上<b>无法区分</b>。
 *          这是本项目反复消灭的那类「看着更干净、实际丢了可诊断性」的改动。</li>
 *      <li>② 保持纯绿、只在文档里写「这是已知的」—— <b>否决</b>：绿天空会直接进最终画面
 *          （h27b §六已实测：composite 采 colortex0，而天空那片从没被画进 gbuffer
 *          ⇒ 保持纯绿 ⇒ 绿天空）。留着它就是明知有缺陷还交付。</li>
 *    </ul>
 * 3. 我们的差异点（本类的全部设计）：
 *    <ul>
 *      <li>🔖 <b>区分两种模式</b>：
 *      <ul>
 *        <li><b>诊断模式</b>（{@code mrt.terrain} 开着、且调试视图激活）⇒ <b>保留</b>高对比诊断色。
 *        它是<b>刻意</b>选的：槽 0 用非黑且不代表任何语义的绿，
 *        这样「一个片元都没出」与「画了但很暗」在一张截图里就能分开。</li>
 *        <li><b>生产模式</b>（我们要让这一帧给用户看）⇒ 用<b>零值</b>清屏，
 *        天空那片变成黑而不是绿。<b>不是</b>「换成另一个好看的颜色」——
 *        任何非零颜色都会以「一片假色天空」的形式出现在用户画面里。</li>
 *      </ul></li>
 *      <li>🔖 <b>诊断色只在它真的是「诊断」时才用</b>。判据 = 调试视图是否激活，
 *      而不是「这个 pass 有没有开着」—— 后者在取证时经常两者同时开（h27b 就是），
 *      但用户看到的画面应该是生产语义。</li>
 *      <li>🔖 <b>零值 ≠ 放弃可诊断性</b>：生产模式下「没画 vs 很暗」仍然可区分 ——
 *      用 {@link #isNeutralClear} 这个显式判据 + 一次 WARN 埋点说明
 *      「本帧清屏是零值 ⇒ 截图里天空黑是<b>预期</b>，不是故障」，
 *      这样取证者不会把「设计如此」误读成「又坏了」。</li>
 *    </ul>
 * 4. 许可证核对：本项目 MIT；零第三方代码复制（决策表为本文件独立定义）。
 * 5. 性能基线：❄️ 冷路径（建 pass 描述符时算一次，每帧一次 switch）；不做优化（T14）。
 */
import java.util.Objects;

/**
 * 逐槽清屏色的决策（可单测的纯逻辑；GPU 侧只照表取值）。
 *
 * <p><b>它修什么</b>（实测见 {@code evidence/h27b-…} §六）：
 * 旧实现无条件把槽 0 清成<b>纯绿</b> {@code RGB(0,255,0)}，而我方 pass <b>只画地形</b> ——
 * 天空那片区域因此<b>保持纯绿</b>，包的 composite 又采 {@code colortex0}
 * ⇒ <b>绿天空直接出现在最终画面上</b>。那是诊断色泄漏进产品路径，属真实缺陷。
 *
 * <p><b>为什么不只是改成黑色</b>：旧实现的注释写明了诊断色的用意 ——
 * {@code MrtPlan} 给槽 0 的指纹恰好是 {@code 0.0}（黑），
 * 于是「什么都没画」与「画了但很暗」在截图上无法区分。
 * 本类保留这项区分能力，但把它<b>限定在诊断模式</b>。
 */
public final class TerrainSlotClear {

    /** 清屏模式（诊断视图激活与否决定）。 */
    public enum Mode {
        /**
         * 诊断模式：高对比逐槽色（绿 / 蓝 / 品红）。
         *
         * <p>🔖 保留的**唯一**理由：让「一个片元都没出」与「画了但很暗」在一张截图里可区分。
         * 这些颜色<b>不代表任何渲染语义</b>，绝不能出现在用户画面里。
         */
        DIAGNOSTIC,
        /**
         * 生产模式：零值清屏（黑）。
         *
         * <p>🔖 天空那片因此是<b>黑</b>而不是绿。这仍然不是「正确的天空」
         * （正确天空要由包的 gbuffer 程序去画，M-04 未做），
         * 但它<b>不含假信息</b> —— 而纯绿会让用户以为本项目画了绿天。
         */
        NEUTRAL
    }

    private final Mode mode;

    private TerrainSlotClear(Mode mode) {
        this.mode = mode;
    }

    /** 按模式取决策。 */
    public static TerrainSlotClear of(Mode mode) {
        return new TerrainSlotClear(Objects.requireNonNull(mode, "mode"));
    }

    /**
     * 生产模式决策（零值清屏）。
     *
     * <p>🔶 用它而不只是 {@code of(NEUTRAL)}：让「生产路径」在调用点一眼可辨，
     * 避免读代码时把两种模式搞混（那正是本缺陷的成因形态）。
     */
    public static TerrainSlotClear production() {
        return new TerrainSlotClear(Mode.NEUTRAL);
    }

    /** 诊断模式决策（高对比逐槽色）。 */
    public static TerrainSlotClear diagnostic() {
        return new TerrainSlotClear(Mode.DIAGNOSTIC);
    }

    /** 当前模式。 */
    public Mode mode() {
        return this.mode;
    }

    /**
     * 某槽的清屏色四元组（RGBA，线性 0..1）。
     *
     * @param slot 槽下标（越界按 {@code >=2} 处理，与旧实现同口径）
     */
    public float[] rgba(int slot) {
        if (this.mode == Mode.NEUTRAL) {
            // 生产模式：全 0（含 alpha）。天空那片因此是黑，不是绿。
            return new float[] {0.0F, 0.0F, 0.0F, 0.0F};
        }
        return switch (slot) {
            case 0 -> new float[] {0.0F, 1.0F, 0.0F, 1.0F};
            case 1 -> new float[] {0.0F, 0.0F, 1.0F, 1.0F};
            default -> new float[] {1.0F, 0.0F, 1.0F, 1.0F};
        };
    }

    /** 该色是否为「零值清屏」（生产模式；日志与单测的判据）。 */
    public boolean isNeutralClear() {
        return this.mode == Mode.NEUTRAL;
    }

    /** 该色是否**不代表任何渲染语义**（诊断色恒为真；生产零值亦然）。 */
    public boolean carriesNoRenderSemantics() {
        return true;
    }

    /**
     * 一次性的说明文案（绑定/建 pass 时打一次，不逐帧刷屏 —— 热路径日志 I/O 是真实开销，
     * 见 M-01 埋点 600→250000 的教训）。
     *
     * @param slots 实际槽数
     */
    public String explainOnce(int slots) {
        if (this.mode == Mode.NEUTRAL) {
            return "vkdisp: [GAP-003/A] terrain slot clear = NEUTRAL (RGBA 0,0,0,0) for " + slots
                    + " slot(s) —— 我方 pass 只画地形，天空那片区域不会被画进 gbuffer；"
                    + "因此它显示为**黑**是预期行为，不是故障（此前是纯绿 = 诊断色泄漏进产品画面，"
                    + "见 evidence/h27b §六）。要看高对比逐槽诊断色请开 mrt.enabled 调试视图。";
        }
        return "vkdisp: [GAP-003/A] terrain slot clear = DIAGNOSTIC (green/blue/magenta) for "
                + slots + " slot(s) —— 这些颜色**不代表任何渲染语义**，"
                + "仅用于区分「一个片元都没出」与「画了但很暗」；不要在诊断视图之外启用。";
    }

    /** 单行打印形式（证据行 / 单测比对）。 */
    public String format(int slot) {
        float[] c = rgba(slot);
        return mode + "[" + slot + "]=(" + c[0] + "," + c[1] + "," + c[2] + "," + c[3] + ")";
    }
}
