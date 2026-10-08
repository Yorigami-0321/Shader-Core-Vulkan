package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】像素回读探针的**测哪一槽**决策（把「挑一个槽」变成可单测的纯逻辑）
 * 0. 合规核对（第 0 步闸门）：
 *    参考对象 = ① 本仓库自有的 {@code dev.vkdisp.bridge.TargetReadback}（被本类取代的那段挑槽逻辑，
 *    MIT 自有代码）—— 它暴露出「硬编码挑槽」这个缺陷形态；② 本仓库自有的
 *    {@code dev.vkdisp.glsl.translate.DrawBuffersSlotAdapter} 的槽位表语义（同样 MIT 自有）；
 *    ③ BSL v10.1.8 的实测数字（{@code run/h27/logs/latest.log} 里 {@code colorTargets=8} /
 *    {@code slots=8} 两行 + {@code shaders/program/gbuffers_terrain.glsl} 的四条 DRAWBUFFERS 标记）。
 *    🔖 <b>那两个 8 不属于「包默认档」</b>（2026-10-08 h48r 更正）：打出 8 的那些臂带着
 *    {@code config/vkdisp-pack-options.properties} 里残留的 {@code ADVANCED_MATERIALS=true} 编译
 *    （扫包日志逐字 {@code option name=ADVANCED_MATERIALS … default=false}）⇒ 它们是<b>残留档</b>的
 *    实测数字；真默认档由 {@code TerrainProductionOutputCountTest} 实测为<b>只写 1 个颜色输出</b>。
 *    ⇒ 本类的<b>逻辑</b>不受影响（它防的是「附件存在 ≠ 附件被写了」，两档都成立），
 *      受影响的是「拿这组数字当默认档」的判读。
 *    全部为仓库内自有代码 / 自有实测 / 不受版权保护的事实，不搬运任何第三方或 Mojang 源码。
 *    → 能否并入本项目（MIT）：可以（本文件为独立编写的纯 Java 决策类）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码，也不含任何 Mojang 着色器文本
 * 1. 官方/主实现：无（Vulkan / 原版里都不存在「诊断该读哪一张图」这个概念）。
 * 2. 备选：
 *    <ul>
 *      <li>① 继续在 {@code TargetReadback} 里挑槽并加注释说明为什么是槽 1 —— <b>否决（本类的成因）</b>：
 *          它把一个**可判定的数据问题**（包声明写哪些槽）换成了**一个魔数**，
 *          而魔数在包配置一变（开 MCBL_SS / 关 ADVANCED_MATERIALS）就静默指错槽。</li>
 *      <li>② 干脆退回「按附件下标依次全测 0..N-1」—— <b>否决</b>：附件存在 ≠ 附件被写。
 *          BSL 默认档实测 {@code declaredOutputSlots=[0,3,6,7]}/{@code outputCount=8}，
 *          于是槽 1/2/4/5 <b>永远只有清屏值</b>；把它们一起测进来，
 *          日志会出现 4 行「全黑」，读起来像「包的四路输出都是黑的」，
 *          而真相是「那四张图这一帧不是附件」。</li>
 *      <li>③ 不挑槽、只报主目标 —— 否决：那会退回 h42 §4.3 登记的「两种成因分不开」，
 *          而分开它们正是这次做探针的全部理由。</li>
 *    </ul>
 * 3. 我们的差异点：
 *    <ul>
 *      <li>🔖 <b>输入是「包声明写的槽」，输出是「要回读的槽」，两者不能互相推导</b>：
 *          {@code outputCount} 只回答「附件有几个」，本类回答「哪几个附件里有包片元的输出」。</li>
 *      <li>🔖 <b>{@code terrainToMain} 档必须剔除槽 0</b>：那一档附件 0 已被换成主目标视图，
 *          槽 0 这一帧<b>不是附件</b>，读它必然得到「零填充的旧内容」。</li>
 *      <li>🔖 <b>「不知道」是一条独立结论</b>：包片元没接线时 {@code declaredOutputSlots} 为空，
 *          此时<b>既不产出对照结论，也不假装知道</b>（{@link #comparable()} 为 false），
 *          并在 {@link #note()} 里写清为什么。宁可不出结论，也不拿一个不存在的数字充数。</li>
 *      <li>🔖 <b>截断必须自报</b>：槽数上限由 {@link #MAX_COLORTEX_PROBES} 封顶（显存预算），
 *          截断时 {@link #truncated()} 为 true 并在 note 里点名被丢掉的槽。</li>
 *    </ul>
 * 4. 许可证核对：本项目 MIT；零第三方代码复制（判据全部来自本仓库自有实测与自有代码）。
 * 5. 性能基线：❄️ 冷路径（决策每探针轮算一次，默认 300 帧一轮）。
 */
import java.util.List;

/**
 * 像素回读探针该测哪一（几）个 colortex 槽 —— 纯决策，零 GPU / 零原版类型依赖 ⇒ 可单测。
 *
 * <p><b>它防的是哪一种失败</b>（2026-10-05 实测定位，本项目同族的第五例）：
 * 「附件存在」被当成「附件被写了」。下面这组是<b>残留档</b>（store 带 {@code ADVANCED_MATERIALS=true}）
 * 的实测数字，<b>不是</b>包默认档（默认档只写槽 0）：
 * <pre>
 *   outputCount         = 8            （日志：colorTargets=8 / targets ready: slots=8）
 *   declaredOutputSlots = [0, 3, 6, 7] （源：program/gbuffers_terrain.glsl 活标记 DRAWBUFFERS:0 + 0367）
 *   ⇒ 附件 1 / 2 / 4 / 5 存在，但**没有任何片元输出**
 * </pre>
 * 于是 {@code terrainToMain=true} 那一档若「顺手挑槽 1」，读到的必然是清屏值
 * ⇒ 日志报 {@code colortex1 allZero=true} ⇒ 会被读成「包片元输出是黑的」（= GAP-008 的形态之一），
 * 而真相是「那张图这一帧不是包的输出」。**诊断给出一个看起来像证据的假数字** ——
 * 与 h31 收尾被推翻、{@code terrainToMain} 档误测槽 0 是同一族，必须在代码层堵死。
 *
 * <p><b>因此本类的存在形式是「决策 + 说明」而不是「挑一个槽」</b>：
 * 拿不到可对照的槽时返回空列表 + {@link #comparable()} = false + 一句能直接进日志的
 * {@link #note()}，让「本轮没有对照结论」成为**自报的事实**，而不是日志里的沉默。
 */
public record PixelProbePlan(
        List<Integer> colortexSlots,
        boolean declaredSlotsKnown,
        boolean comparable,
        boolean truncated,
        String note) {

    /**
     * 一轮探针最多回读几个 colortex 槽（<b>加主目标共 +1 个源</b>）。
     *
     * <p>🔖 <b>为什么要有上限</b>：每个源一个 mappable 回读缓冲，字节数 = 宽 × 高 × 4。
     * 1080p 下每源约 8 MB、4K 下约 33 MB；OF 语义允许最多 8 个输出槽（实测 BSL + MCBL_SS 会要 9 个
     * ⇒ 已由 {@code DrawBuffersSlotAdapter} 显式拒绝），若无上限就是「一次开 8 个 4K 缓冲」。
     * ⇒ 上限 4 恰好覆盖实测的 {@code [0,3,6,7]} 全集，且被截断时 {@link #truncated()} 会自报。
     */
    public static final int MAX_COLORTEX_PROBES = 4;

    /** 归一构造：列表冻结去重升序；说明不许为空白（空白说明 = 读日志的人无从判断有没有结论）。 */
    public PixelProbePlan {
        colortexSlots = colortexSlots == null ? List.of() : colortexSlots.stream()
                .filter(slot -> slot != null && slot >= 0)
                .distinct().sorted().toList();
        note = note == null ? "" : note;
        if (note.isBlank()) {
            throw new IllegalArgumentException(
                    "vkdisp: 探针决策必须带一句说明（「为什么测这些 / 为什么不出对照结论」）");
        }
    }

    /** 本轮要回读的 colortex 槽（升序；不含被主目标占用的槽）。 */
    public List<Integer> colortexSlots() {
        return colortexSlots;
    }

    /** 是否知道当前挂的地形片元<b>声明</b>写哪些槽（false = 只能报原始数字，不产出对照结论）。 */
    public boolean declaredSlotsKnown() {
        return declaredSlotsKnown;
    }

    /**
     * 本轮能否产出「主目标 vs colortex」的两源对照结论。
     *
     * <p>🔖 为 false 有三类**互不相同**的原因，必须在 {@link #note()} 里分开说：
     * ① 槽位未知；② {@code terrainToMain} 档下没有第二个被写的槽；③ pass 压根没建附件。
     * 把三者揉成一句「无法对照」就等于把 h42 §4.3 那个待分辨因素又糊回去。
     */
    public boolean comparable() {
        return comparable;
    }

    /** 是否因 {@link #MAX_COLORTEX_PROBES} 上限丢掉了部分被写的槽（丢了必须在 note 里点名）。 */
    public boolean truncated() {
        return truncated;
    }

    /** 一次性说明（直接进日志；判读对象必须自报）。 */
    public String note() {
        return note;
    }

    /**
     * 决策。
     *
     * @param attachments          地形 MRT pass 的实际附件数（{@code MrtTerrainPass#actualSlots()}）
     * @param toMain               {@code mrt.terrainToMain}：附件 0 是否已被换成主目标视图
     * @param declaredOutputSlots  包片元<b>声明</b>写的槽（升序）；<b>空 = 未知/不接包片元</b>
     * @param requestedViewSlot    {@code mrt.viewSlot}（只在槽位未知时作为兜底观测面；越界则不用）
     */
    public static PixelProbePlan decide(int attachments, boolean toMain,
            List<Integer> declaredOutputSlots, int requestedViewSlot) {
        if (attachments <= 0) {
            return new PixelProbePlan(List.of(), false, false, false,
                    "地形 MRT pass 还没有附件（attachments=" + attachments
                            + "）⇒ 本轮不产出任何像素数字（这与「数字是 0」是两件事）");
        }
        if (declaredOutputSlots == null || declaredOutputSlots.isEmpty()) {
            return unknownSlots(attachments, toMain, requestedViewSlot);
        }
        // 🔖🔖 声明槽必须**逐个**落在附件范围内。静默滤掉越界项是错的：
        //   「契约要第 9 个附件、pass 只有 8 个」属于 X42 的响亮失败（管线颜色目标数与
        //   render pass 附件数不等 ⇒ setPipeline 抛 IllegalStateException 崩客户端），
        //   由一个诊断决策悄悄夹掉，会让它看起来还在正常工作。
        List<Integer> declared = declaredOutputSlots.stream()
                .filter(java.util.Objects::nonNull)
                .distinct().sorted().toList();
        for (Integer slot : declared) {
            if (slot < 0 || slot >= attachments) {
                return new PixelProbePlan(List.of(), true, false, false,
                        "包片元声明写的槽 " + declared + " 含越界项 " + slot
                                + "（本 pass 只有 0.." + (attachments - 1)
                                + "）⇒ 契约与 pass 已不一致（X42，属响亮失败而非静默夹取）；"
                                + "本轮不产出对照结论");
            }
        }
        if (declared.isEmpty()) {
            return new PixelProbePlan(List.of(), true, false, false,
                    "包片元声明写的槽集合为空但**不是**未知态（未知走另一条分支）"
                            + " ⇒ 契约本身不可解析，本轮不产出对照结论");
        }
        if (toMain) {
            return toMainLane(attachments, declared);
        }
        return sideBySideLane(attachments, declared, requestedViewSlot);
    }

    /** {@code terrainToMain=true}：附件 0 就是主目标视图 ⇒ 槽 0 必须从候选里剔掉。 */
    private static PixelProbePlan toMainLane(int attachments, List<Integer> declared) {
        List<Integer> candidates = declared.stream().filter(slot -> slot != 0).sorted().toList();
        if (candidates.isEmpty()) {
            return new PixelProbePlan(List.of(), true, false, false,
                    "mrt.terrainToMain=true ⇒ 附件 0 已被换成主目标视图，colortex0 这一帧**不是附件**；"
                            + "而包片元声明只写 " + declared
                            + " ⇒ 除主目标外没有第二个被写的 colortex ⇒ 本档**不产出**两源对照结论。"
                            + "⚠️ 拿 colortex0（或任何未被写的槽）当对照只会得到清屏值，"
                            + "那会被读成「包片元输出黑」—— 假证据");
        }
        List<Integer> kept = candidates.size() <= MAX_COLORTEX_PROBES
                ? candidates
                : candidates.subList(0, MAX_COLORTEX_PROBES);
        List<Integer> dropped = candidates.size() <= MAX_COLORTEX_PROBES
                ? List.of()
                : candidates.subList(MAX_COLORTEX_PROBES, candidates.size());
        return new PixelProbePlan(kept, true, true, !dropped.isEmpty(),
                "mrt.terrainToMain=true ⇒ 槽 0 已被换成主目标视图，colortex0 这一帧**不是附件**"
                        + "（读它只会得到零填充的旧内容）；主目标因此就是包的槽 0（albedo）本身，"
                        + "对照改测同档**确实被写**的槽 "
                        + kept + "（共 " + attachments + " 个附件，包声明写 " + declared + "）"
                        + (dropped.isEmpty() ? ""
                                : "。⚠️ 受 MAX_COLORTEX_PROBES=" + MAX_COLORTEX_PROBES
                                        + " 限制，本轮**未测** " + dropped)
                        + " ⇒ 「主目标黑 + 该槽不黑」= 包确实写了内容、只有 albedo 那一路是黑的");
    }

    /** {@code terrainToMain=false}：主目标是原版画面，colortex 各槽是我们自己 pass 的输出。 */
    private static PixelProbePlan sideBySideLane(int attachments, List<Integer> declared,
            int requestedViewSlot) {
        List<Integer> kept = declared.size() <= MAX_COLORTEX_PROBES
                ? declared
                : declared.subList(0, MAX_COLORTEX_PROBES);
        List<Integer> dropped = declared.size() <= MAX_COLORTEX_PROBES
                ? List.of()
                : declared.subList(MAX_COLORTEX_PROBES, declared.size());
        // 🔖 mrt.viewSlot 只影响「诊断视图 blit 显示哪一张」，**不影响**探针测哪一槽：
        //   探针测的是「包声明写的全部槽」，这样「哪一路输出是黑的」才是可判读的。
        //   若用户配的 viewSlot 恰好不是被写的槽，顺手提醒 —— 那张图同样不是包的输出。
        String viewSlotNote = declared.contains(requestedViewSlot)
                ? ""
                : "。⚠️ mrt.viewSlot=" + requestedViewSlot
                        + "（范围 0.." + (attachments - 1) + "）**不在**包声明写的槽 " + declared
                        + " 里 ⇒ 诊断视图显示的那张图不是包的输出，别拿它当包片元的证据";
        return new PixelProbePlan(kept, true, true, !dropped.isEmpty(),
                "mrt.terrainToMain=false ⇒ 主目标是原版画面；探针测本 pass **确实被包写**的槽 " + kept
                        + "（共 " + attachments + " 个附件，包声明写 " + declared
                        + "；未测的附件只有清屏值，测了只会产出假证据）"
                        + (dropped.isEmpty() ? "" : "。⚠️ 受 MAX_COLORTEX_PROBES=" + MAX_COLORTEX_PROBES
                                + " 限制，本轮**未测** " + dropped)
                        + viewSlotNote);
    }

    /** 槽位未知（不接包片元 / 契约不可得）：可以报原始数字，但**不产出**对照结论。 */
    private static PixelProbePlan unknownSlots(int attachments, boolean toMain, int requestedViewSlot) {
        if (toMain) {
            return new PixelProbePlan(List.of(), false, false, false,
                    "mrt.terrainToMain=true 且**不知道**当前片元声明写哪些槽"
                            + "（未接包片元，或契约未解析）⇒ 本轮只报主目标的数字，不产出两源对照结论。"
                            + "⚠️ 该档下槽 0 就是主目标视图，任何 colortex 槽都不能当作「包的输出」来读");
        }
        boolean inRange = requestedViewSlot >= 0 && requestedViewSlot < attachments;
        List<Integer> fallback = inRange ? List.of(requestedViewSlot) : List.of();
        return new PixelProbePlan(fallback, false, false, false,
                "**不知道**当前地形片元声明写哪些槽（未接包片元，或契约未解析）"
                        + (inRange
                                ? " ⇒ 只把 mrt.viewSlot=" + requestedViewSlot + " 当**原始观测面**回读一次，"
                                        + "但不产出两源对照结论（读了也不能声称那是包的输出）"
                                : "；且 mrt.viewSlot=" + requestedViewSlot + " 越出附件范围 0.."
                                        + (attachments - 1) + " ⇒ 本轮不读任何 colortex"));
    }
}