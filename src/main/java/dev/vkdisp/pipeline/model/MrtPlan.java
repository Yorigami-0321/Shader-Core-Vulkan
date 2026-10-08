package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】GAP-003 多附件（MRT）能力规格表 / 原版 {@code RenderPassDescriptor} + {@code RenderPipeline} 的多附件公开 API
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 原版 26.3 {@code com.mojang.renderpearl.api.commands.RenderPassDescriptor}
 *    （{@code withColorAttachment} 可**重复调用**追加附件，{@code build()} 的
 *    {@code defaultRenderArea} 取最后一个非空附件的尺寸）与 {@code ColorTargetState}
 *    （{@code MAX_COLOR_TARGETS = 8}）；② 原版 {@code RenderPipeline.Builder#withColorTargetStates}
 *    （{@code (startIndex, endIndex, Supplier<ColorTargetState>)} 批量设置）。
 *    许可证：Mojang EULA（原版）。**只提取「附件数上限」「批量设置 API 形状」这类事实与公开签名**，
 *    不搬运实现语句、不搬运着色器文本。
 *    → 能否并入本项目（MIT）：可以 —— 事实性上限与公开 API 形状不受版权保护；本文件为独立编写的规格表
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：原版 {@code RenderPassDescriptor} 与 {@code RenderPipeline} 的多附件通道本身 ——
 *    **本项目此前从未用过**（全部 15 条管线都是单附件），GAP-003 的「管线侧」其实一直是通的，
 *    真正卡住的是 pass 侧（证据见 {@code evidence/h01-terrain-pipeline-wire.md} §1/§5.0.2）。
 * 2. 备选：① 直接用 {@code RenderTarget} 子类多挂纹理 —— 否决（{@code RenderTarget} 只有
 *    {@code getColorTextureView()} **一个**颜色纹理，且 {@code FrameGraphBuilder} 的
 *    {@code createInternal} 返回单个 {@code RenderTarget}；改原版类型属侵入，07-M1 不允许）；
 *    ② 只在着色器里多写几个 out 而不改 pass —— 否决（Vulkan 要求管线颜色附件数与 pass 附件数**相等**，
 *    否则 validation error；这不是可以「先只写不接」的中间态）。
 * 3. 我们的差异点：把「槽位数 / 每槽格式 / 槽位指纹编码」摊成**纯数据表**，
 *    让「三槽各自拿到不同内容」成为可单测、可量化判读的事实，而不是靠肉眼。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 冷路径（管线注册一次）+ 可关闭的按需绘制（默认关闭 ⇒ 常规帧零开销）。
 */
import java.util.ArrayList;
import java.util.List;

/**
 * GAP-003 多附件能力的**规格表**（纯数据，零原版渲染类型依赖 ⇒ 可在无 GPU 单测里自证）。
 *
 * <p><b>本轮为什么先做它，而不是直接做 M-04</b>（先测后优，17-NATIVE §2–§3）：
 * GAP-003 的完整形态需要「地形 draw 走多附件 pass」，而源码级核实表明**原版主 pass 把地形、
 * 实体、特性、云、描边画在同一个 pass、同一个单附件里**（`LevelRenderer.addMainPass`，
 * 见 {@code evidence/h01-terrain-pipeline-wire.md} §5.0.2）⇒ 直接把那个 pass 改成多附件会让
 * 所有原版管线（都声明 1 个附件）与 pass 不匹配而全部 validation error。
 * 所以先在**我方自己的 pass** 里把 MRT 原语跑通并量化验证，再谈怎么把地形接进来。
 *
 * <p><b>槽位数取 3 的依据</b>：OptiFine / Iris 的 gbuffer 语义核心三槽就是
 * {@code colortex0}（albedo）/ {@code colortex1}（normal + lightmap）/
 * {@code colortex2}（material / matID）（`13-GAP-REGISTRY.md` GAP-003 条目原文）。
 * 原版 {@code ColorTargetState.MAX_COLOR_TARGETS = 8} 是上限，本轮**不追满** ——
 * 每多一槽就多一份带宽与显存，X27 要求「不为性能砍特性」但也不要求「无理由地多开」。
 */
public final class MrtPlan {

    /**
     * GAP-003 语义核心槽位数（albedo / normal+lightmap / material）。
     *
     * <p>🔴🔖 <b>这个 3 是按 _Iris_ 的 gbuffer 语义定的，对 BSL 不够</b>
     * （2026-10-03 核实，`evidence/h06-bsl-terrain-semantics-and-translate-baseline.md`）：
     * BSL 用 OF 式 {@code gl_FragData[N]} + {@code /* DRAWBUFFERS:… *}{@code /} 映射，
     * 实测其全部 gbuffer 程序的 DRAWBUFFERS 集合都是 {@code {0, 0367, 08, 08367}}
     * ⇒ 最多写 <b>5</b> 个槽，且 {@code gl_FragData[1]}→<b>colortex3</b>、
     * {@code [2]}→<b>colortex6</b>（法线）、{@code [3]}→colortex7 —— <b>不是下标</b>。
     * ⇒ <b>槽位数与顺序都必须由该包自己的 DRAWBUFFERS 决定</b>，
     * 按附件下标硬绑会**静默绑错槽**（画面有内容但每个通道都错，且没有一行日志会抱怨）。
     *
     * <p>🔖🔖 <b>2026-10-05（h45）第二次更正：关于「默认档」的两次说法都要以实测为准。</b>
     * 本类的旧注释（「{@code ADVANCED_MATERIALS}/{@code MCBL_SS} 在 BSL 里<b>默认注释掉</b>
     * ⇒ 默认配置下地形只写 colortex0」）经实测是<b>对的</b>；错的不是注释，而是
     * <b>我们一直在测的那一臂不是默认档</b>：
     * <ol>
     *   <li>实测（扫包日志逐字）：{@code option name=ADVANCED_MATERIALS type=BOOLEAN
     *       <b>default=false</b>}、{@code MCBL_SS … <b>default=false</b>}、
     *       {@code PARALLAX … default=true}。⇒ <b>真正的包默认</b>只写槽 0
     *       （实测 {@code outputs=1 samplers=5 varyings=9}）。</li>
     *   <li>而 h43/h44 那些「8 附件 / 声明写 [0,3,6,7]」的臂，是
     *       {@code config/vkdisp-pack-options.properties} 里<b>残留</b>的
     *       {@code ADVANCED_MATERIALS=true; PARALLAX=false} 造成的
     *       ⇒ 那一臂<b>同时</b>不是默认档、也不是「只开了视差」的单变量臂。</li>
     * </ol>
     * 🔖 <b>教训（比结论更重要）</b>：我曾把「store 里 {@code ADVANCED_MATERIALS=true}」
     * 反推成「包默认是 true」并据此<b>改掉了本注释</b>——那是<b>用残留状态反推默认值</b>，
     * 与 h43 记录的「B 臂漏带覆盖 ⇒ 静默空转」是同一个坑的两面。
     * ⇒ 现在由 {@code PixelProbePlan} / {@code PixelStats} / {@code PackOptionEvidence}
     * 让「当前实际生效的覆盖」每轮打进证据行，从机制上堵掉这条路。
     *
     * <p>本常量暂留 3（Iris 口径，且是当前诊断路径的实测值），上调前先改这一处与
     * {@link #slotCount()}。
     */
    public static final int SLOT_COUNT = 3;

    /** 原版 {@code ColorTargetState.MAX_COLOR_TARGETS}（8）—— 本项目自设上限的合法上界。 */
    public static final int HARD_MAX_SLOTS = 8;

    /**
     * 本次运行**实际**使用的附件数（诊断可调）。
     *
     * <p>🔖 <b>为什么做成可调</b>：Vulkan 动态渲染下「管线颜色附件数 ≠ render pass 附件数」
     * 是 validation error，但**本机没有装 validation layer**（实测：无
     * {@code VK_LAYER_KHRONOS_validation}）⇒ 这种不匹配是**静默未定义行为**，
     * 症状就是「draw 提交了、一条片元都没出、日志全绿」。
     * 为了把「附件数不匹配」与「其他原因」分开，必须能**同时**把两侧改成同一个数：
     * 1 = 与原版同形的管线（若这样能出画面 ⇒ 问题就在多附件本身），3 = 出问题的配置。
     *
     * <p>🔖 管线与 pass **必须取同一个值**（单点真源），否则又落回不匹配。
     */
    public static int slotCount() {
        int pack = packOutputCount();
        if (pack > 0) {
            return Math.max(1, Math.min(HARD_MAX_SLOTS, pack));
        }
        int requested = dev.vkdisp.VkDispConfig.MRT_ATTACHMENTS.get();
        return Math.max(1, Math.min(HARD_MAX_SLOTS, requested));
    }

    /**
     * 🔖🔖 注册期**冻结**的包片元契约（输出数 + 声明写的槽位集合）。
     *
     * <p>🔖 <b>为什么必须冻结而不是每帧现算</b>（同 {@link #frozenPackOutputCount} 的理由，
     * X42 的最后一环）：管线注册与 pass 每帧取附件数，两侧若各自现算就会出现
     * 「注册时读到 8、画的时候读到 1」⇒ render pass 附件数与管线颜色目标数不等
     * ⇒ {@code setPipeline} 抛 IllegalStateException <b>崩客户端</b>。
     *
     * <p>🔖 <b>为什么两个数必须由同一次调用一起冻结</b>：若让「附件数」与「被写的槽」
     * 走两条独立的 freeze 通道，就可能出现「附件数按新契约、被写的槽按旧契约」——
     * 两者互相矛盾而日志完全正常。造键与校验必须同源是本项目已吃过一次的教训
     * （{@code VkDispVirtualPack#currentTerrainMemoKey} 的「造键用 A、校验用 B」）。
     */
    private record FrozenPackContract(int outputCount, List<Integer> declaredSlots,
            List<String> programNames) {

        FrozenPackContract {
            declaredSlots = declaredSlots == null ? List.of() : List.copyOf(declaredSlots);
            programNames = programNames == null ? List.of() : List.copyOf(programNames);
        }
    }

    /** 冻结值；默认 = 「不接包片元」。volatile：注册线程写、渲染线程每帧读。 */
    private static volatile FrozenPackContract frozenPack =
            new FrozenPackContract(0, List.of(), List.of());

    /**
     * 注册期一次性冻结包地形片元契约（<b>两个数一起</b>，见 {@link FrozenPackContract}）。
     *
     * <p>等价于 {@code freezePackPrograms(List.of(new PackProgram("(terrain)", outputs, slots)))}
     * —— 保留这个单程序入口是为了让「只有一个 gbuffer 程序被接」这条常见路径不必到处构造列表，
     * 也保住既有调用方与单测的口径。
     *
     * @param outputs        包片元输出数；{@code <= 0} 表示「本次不接包片元」（附件数回到配置值）
     * @param declaredSlots  包片元<b>声明</b>写的槽位（升序）；空 = 未知/不接包片元
     */
    public static void freezePackProgram(int outputs, List<Integer> declaredSlots) {
        freezePackPrograms(List.of(new PackProgram("(terrain)", outputs, declaredSlots)));
    }

    /** 一个被接进 MRT pass 的包 gbuffer 程序的契约。 */
    public record PackProgram(String name, int outputs, List<Integer> declaredSlots) {
    }

    /**
     * GAP-027：冻结<b>多条</b> gbuffer 程序的契约（地形 + 水 + …）。
     *
     * <p>🔖 <b>为什么必须一次算完、一次写入</b>：附件数与「被写的槽」若分两次冻结，
     * 就可能出现「附件数按地形、被写的槽按水」——两者互相矛盾而日志完全正常。
     * 更硬的后果是 {@code setPipeline} 校验「render pass 附件数 == 管线颜色目标数」，
     * 注册侧与绘制侧读到不同的数会<b>直接崩客户端</b>（X42 那条时序铁律的延续）。
     *
     * <p>🔖 <b>附件数取 max 而不是取地形那一条</b>：BSL 的 {@code gbuffers_water} 在
     * {@code ADVANCED_MATERIALS}/{@code MCBL_SS} 都关的默认档下也<b>无条件</b>写两条
     * （{@code /* DRAWBUFFERS:01 *}/{@code /} + {@code gl_FragData[0..1]}，
     * 见 {@code program/gbuffers_water.glsl:726-728}），而默认档地形只写 1 条。
     * 一个 pass 只能有一套附件数 ⇒ 必须容纳写得最多的那条程序；
     * 少写的程序只是不碰多出来的附件（合法，且 {@link #packDeclaredOutputSlots()} 会如实少报）。
     */
    public static void freezePackPrograms(List<PackProgram> programs) {
        int maxOutputs = 0;
        java.util.TreeSet<Integer> slots = new java.util.TreeSet<>();
        List<String> names = new ArrayList<>();
        for (PackProgram program : programs) {
            if (program == null || program.outputs() <= 0) {
                continue; // 「这条不接」是正常状态（例如包没有 gbuffers_water），不是错误
            }
            int clamped = Math.min(HARD_MAX_SLOTS, program.outputs());
            maxOutputs = Math.max(maxOutputs, clamped);
            if (!program.name().isBlank()) {
                names.add(program.name());
            }
        }
        if (maxOutputs == 0) {
            frozenPack = new FrozenPackContract(0, List.of(), List.of());
            return;
        }
        for (PackProgram program : programs) {
            if (program == null || program.declaredSlots() == null) {
                continue;
            }
            for (Integer slot : program.declaredSlots()) {
                if (slot != null && slot >= 0 && slot < maxOutputs) {
                    slots.add(slot);
                }
            }
        }
        frozenPack = new FrozenPackContract(maxOutputs, List.copyOf(slots), List.copyOf(names));
    }

    /** 被接进 MRT pass 的包程序名（自报用；空 = 一条都没接）。 */
    public static List<String> packProgramNames() {
        return frozenPack.programNames();
    }

    /** 冻结后的包片元输出数（0 = 不接包片元，附件数用 {@code mrt.attachments}）。 */
    public static int packOutputCount() {
        return frozenPack.outputCount();
    }

    /**
     * 冻结后的「包片元<b>声明</b>写的槽位」（升序）。
     *
     * <p>🔖🔖 <b>空列表 = 未知，不是「全部都写」</b>。两种来源：
     * ① 本次不接包片元（沿用原版 {@code core/terrain}）—— 那时挂的片元是原版的，
     * 本项目<b>没有</b>它的输出契约（也没去解析原版资源）⇒ 只能承认不知道；
     * ② 契约解析不出声明（正常路径下由 {@code PackTerrainProgram} 显式抛错挡住）。
     *
     * <p>⇒ 调用方<b>不得</b>把空列表当成「0..attachments-1 全被写」——
     * 那会退化成「按附件下标猜槽」，正是 {@link dev.vkdisp.glsl.translate.DrawBuffersSlotAdapter}
     * 要消灭的那一类静默绑错槽。
     */
    public static List<Integer> packDeclaredOutputSlots() {
        return frozenPack.declaredSlots();
    }

    /** 槽位的 OF 身份（仅日志/文档口径，不参与任何渲染逻辑）。 */
    public static final List<String> SLOT_ROLES = List.of("colortex0/albedo", "colortex1/normal+lightmap",
            "colortex2/material");

    /** 调试视图选择槽位的配置值下界。 */
    public static final int VIEW_SLOT_MIN = 0;

    private MrtPlan() {
    }

    /**
     * 一条槽位规格。
     *
     * @param slot     槽位下标（= OF 的 {@code location}，= 附件下标）
     * @param role     OF 身份（纯说明）
     * @param format   该槽颜色格式名（与 {@code GpuFormat} 常量名逐字对应，便于日志对账）
     * @param fingerprintR 写入该槽 R 通道的指纹值（可量化判读用，见 {@link #fingerprintR(int)}）
     */
    public record SlotSpec(int slot, String role, String format, float fingerprintR) {

        public SlotSpec {
            if (slot < 0 || slot >= HARD_MAX_SLOTS) {
                throw new IllegalArgumentException("slot out of range 0.." + (HARD_MAX_SLOTS - 1) + ": " + slot);
            }
        }
    }

    /**
     * 槽位指纹：写入 R 通道的常量。
     *
     * <p><b>为什么需要它</b>：「三个附件都拿到了内容」无法用截图区分 ——
     * 三张都显示同一个全屏渐变时，看不出谁是谁。写入**逐槽不同的常量**后，
     * 任何一张截图的 R 通道均值就能唯一指出它拍的是哪一槽（可量化，不靠肉眼）。
     */
    public static float fingerprintR(int slot) {
        return (float) slot / (float) SLOT_COUNT;
    }

    /** 全量槽位规格（顺序稳定 = 槽位下标升序）。 */
    public static List<SlotSpec> slots() {
        List<SlotSpec> specs = new ArrayList<>(SLOT_COUNT);
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            // 格式统一 RGBA8_UNORM：与原版主目标同格式，避免格式不一致引入的额外变量
            // （本轮验证的是「附件分槽生效」，不是「格式兼容性」——后者另测）。
            specs.add(new SlotSpec(slot, SLOT_ROLES.get(slot), "RGBA8_UNORM", fingerprintR(slot)));
        }
        return List.copyOf(specs);
    }

    /**
     * 把实际附件数收敛到设备与原版都允许的范围。
     *
     * <p>🔖 <b>为什么必须收敛而不是直接用 SLOT_COUNT</b>：Vulkan 的
     * {@code maxColorAttachments} 是设备能力；本机是 lavapipe（软件 Vulkan），
     * 真实硬件与别的驱动可能给不同的值。**超限直接创建 render pass 会得到 validation error**，
     * 而那属于「我方管线配置错」不是「设备不支持」—— 两者必须分开报，否则没法定位（T11）。
     *
     * @param deviceMaxColorAttachments 设备上报的上限（{@code DeviceLimits#maxColorAttachments}）
     * @return 实际可用槽位数，至少 1（1 = 退回单附件，等价于本轮之前的全部管线）
     */
    public static int clampSlots(int deviceMaxColorAttachments) {
        return Math.max(1, Math.min(SLOT_COUNT, deviceMaxColorAttachments));
    }

    /**
     * 校验配置指定的视图槽位。
     *
     * @param requested 配置值
     * @param actualSlots {@link #clampSlots} 的结果
     * @return 合法槽位下标
     * @throws IllegalArgumentException 越界时**显式抛**（不静默夹取）——
     *     静默夹取会让「我配了 5 号槽」看起来生效了，实际看的是 2 号槽（X9 不猜）
     */
    public static int requireViewSlot(int requested, int actualSlots) {
        if (requested < VIEW_SLOT_MIN || requested >= actualSlots) {
            throw new IllegalArgumentException(
                    "mrt view slot " + requested + " out of range 0.." + (actualSlots - 1)
                            + " (device maxColorAttachments -> " + actualSlots + " slots)");
        }
        return requested;
    }
}