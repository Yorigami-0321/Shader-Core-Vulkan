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

    /** GAP-003 语义核心槽位数（albedo / normal+lightmap / material）。 */
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
        int requested = dev.vkdisp.VkDispConfig.MRT_ATTACHMENTS.get();
        return Math.max(1, Math.min(HARD_MAX_SLOTS, requested));
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