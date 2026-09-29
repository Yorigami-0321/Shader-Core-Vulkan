package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】E 线二期 / 一个顶点绑定槽（binding index + 该槽的交错布局）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §4「顶点格式扩展」属性表（槽内属性类型/顺序的权威依据）；
 *    ② 公开图形事实：顶点输入可以由多个独立绑定槽组成（Vulkan vkCmdBindVertexBuffers 的
 *      firstBinding/bindingCount + VkVertexInputBindingDescription，OpenGL 的多个 VBO + glVertexAttribPointer
 *      的 stride/offset 语义），每个槽有自己的 stride，属性用 (binding, offset) 定位；
 *    ③ 本仓库 docs/18-PARALLEL.md §10 P-1d（04-SPEC §4 与 OF 官方属性表在 mc_Entity 上仍有未定稿出入 →
 *      E 线沿用旧值，任何线不许私改，07 X9）。
 *    许可证：本仓库自有文档（MIT 项目）+ 通用图形事实 → 可并入。
 *    ⚠️ VulkanMod = LGPL-3.0，与 MIT 不同族 → 按 docs/07-CONSTRAINTS.md L12「判不过就换参考，
 *    不再读它的代码」：本文件不读、不参考其任何源码；事实来源 = 04-SPEC §4 + 公开图形事实。
 *    → 能否并入本项目（MIT）：本文件为独立实现，可并入
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：一个绑定槽 =（binding index，槽内交错布局），槽内规则与一期 VertexLayout 完全一致
 *    （offset = 前序属性字节数之和，stride = 槽内属性字节数之和）。
 * 2. 备选：把 binding index 塞进每个属性记录（AttributeOffset 加字段）—— 否决（会改动一期已冻结的
 *    VertexLayout.canonicalText 口径与一期 64 条断言；槽号属于「槽」这一层，上面再加一层记录更小侵入）。
 * 3. 我们的差异点：① 槽号与布局分层的纯数据 record；② 槽 0 的 layout 必须是 VertexLayout 的原样输出，
 *    保证一期数值（stride 47 / offset 0,12,16,24,28,32,35,39）零变化（回归保护）；
 *    ③ 空槽由 VertexBindingLayout 显式报 EMPTY_BINDING_SLOT（T11），本类不做静默兜底。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制，只含文档事实与公开图形事实。
 * 5. 性能基线：❄️ 冷路径（构建顶点格式时一次性计算），不做任何性能优化（18-PARALLEL §7.7）。
 */
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 一个顶点绑定槽：{@code binding index} + 该槽内独立计算的交错布局。
 *
 * <p>槽内布局直接复用一期的 {@link VertexLayout}（未做任何改动），因此槽 0 的 stride/offset
 * 与一期结果逐位一致（回归保护）。多槽语义：不同槽各自 stride、各自从 0 起算 offset，
 * 属性用「槽号 + 槽内 offset」定位（公开图形事实）。
 *
 * @param bindingIndex 绑定槽号（Vulkan binding index；合法范围由
 *                     {@link VertexBindingLayout#MAX_BINDING_SLOTS} 约束）
 * @param layout       该槽的交错布局（不可为 null；空布局由上层显式诊断，见 EMPTY_BINDING_SLOT）
 */
public record VertexBindingSlot(int bindingIndex, VertexLayout layout) {

    /** 紧凑构造器：布局非 null。 */
    public VertexBindingSlot {
        Objects.requireNonNull(layout, "layout");
    }

    /** 该槽的顶点字节数（= 槽内布局 stride）。 */
    public int stride() {
        return this.layout.stride();
    }

    /** 该槽的属性条目数。 */
    public int attributeCount() {
        return this.layout.attributeCount();
    }

    /** 该槽的属性名（按槽内顺序）。 */
    public List<String> attributeNames() {
        List<String> names = new ArrayList<>(this.layout.attributeCount());
        for (VertexLayout.AttributeOffset attribute : this.layout.attributes()) {
            names.add(attribute.name());
        }
        return List.copyOf(names);
    }

    /** 该槽是否自洽（转发槽内布局的一期判定）。 */
    public boolean isConsistent() {
        return this.layout.isConsistent();
    }
}
