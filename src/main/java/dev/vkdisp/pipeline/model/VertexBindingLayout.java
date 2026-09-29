package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】E 线二期 / 多顶点绑定槽的布局中间表示（纯数据 + canonical text 往返）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §4（属性表）、docs/08-TESTING.md §5（顶点 stride 自检：
 *      mesh stride != binding stride 会画成拉伸的彩色尖刺且不报错）、docs/18-PARALLEL.md §4 E 线
 *      （「只产中间表示」「stride 与 §4 逐项一致」）；
 *    ② 公开图形事实：Vulkan 规范保证 maxVertexInputBindings >= 16（vkCmdBindVertexBuffers 的
 *      firstBinding/bindingCount + 每槽 VkVertexInputBindingDescription），每个绑定槽有独立 stride，
 *      属性以其 (binding, offset) 定位；同一属性只能由一个槽提供。
 *    许可证：本仓库自有文档（MIT 项目）+ 通用图形事实（不受版权保护）→ 可并入。
 *    ⚠️ VulkanMod（LGPL-3.0）按 docs/07-CONSTRAINTS.md L12「判不过就换参考、不读它的代码」零接触；
 *    事实来源 = 04-SPEC §4 + 公开图形事实。P-1d 未定稿项（mc_Entity 底层元素类型 → 是否影响 stride 47）
 *    本文件一律不改：槽 0 数值必须与一期逐位一致，定稿走 §3.2 由 env-1 统一改。
 *    → 能否并入本项目（MIT）：本文件为独立实现，可并入
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：多槽绑定布局 = 槽列表，每槽（binding index → 自身的 stride/offset 表）；
 *    槽内规则与一期 VertexLayout 完全一致（紧凑累加、无隐式对齐填充）。
 * 2. 备选：把槽号编码进属性名（如 "1:UV0"）—— 否决（污染 GLSL 字面名，且无法表达「同一槽多属性」的
 *    归属校验）；再开一个 pipeline/multislot 子包 —— 否决（18-PARALLEL §4 只给了 pipeline/model/ 一个独占路径）。
 * 3. 我们的差异点：① canonical text 沿用一期口径（同一属性行格式，最前面加槽号 token，槽头行给
 *    slot=<i> stride=<n>），解析时逐项重算并比对，篡改必报错；② 显式诊断补齐槽号越界 / 重复槽 /
 *    空槽 / 属性归属不明（跨槽同名属性）四类 ERROR（T11）；③ 空绑定集（无顶点缓冲的全屏管线）
 *    是 INFO 而非错误 —— 与一期 EMPTY_ATTRIBUTE_SET 口径一致；④ isConsistent() 按槽逐槽判定。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制，只含文档事实与公开图形事实。
 * 5. 性能基线：❄️ 冷路径（构建顶点格式 / 切包时一次性计算），不做任何性能优化（18-PARALLEL §7.7）。
 */
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 多顶点绑定槽的布局中间表示（E 线二期交付物）：槽列表 + 显式诊断。
 *
 * <p><b>槽内规则</b>：与一期 {@link VertexLayout} 完全一致（紧凑交错累加、无隐式对齐填充）；
 * 每个槽的 offset 从 0 起算，stride = 槽内属性字节数之和。
 *
 * <p><b>损坏输入一律显式回报</b>（07-CONSTRAINTS T11）：
 * <ul>
 *   <li>槽号越界（&lt; 0 或 &gt;= {@link #MAX_BINDING_SLOTS}）→ ERROR {@code BINDING_SLOT_OUT_OF_RANGE}，该槽跳过</li>
 *   <li>重复槽号 → ERROR {@code DUPLICATE_BINDING_SLOT}，首个生效</li>
 *   <li>空槽（无属性）→ ERROR {@code EMPTY_BINDING_SLOT}，该槽跳过（无顶点缓冲的管线请用空槽列表）</li>
 *   <li>属性归属不明（同一属性名出现在多个槽）→ ERROR {@code AMBIGUOUS_ATTRIBUTE_OWNERSHIP}，槽保留不静默丢弃</li>
 *   <li>null 槽 → ERROR {@code NULL_BINDING_SLOT}；槽内布局的 ERROR 诊断一并聚合</li>
 *   <li>空槽列表 → INFO {@code EMPTY_VERTEX_BINDING_LAYOUT}（全屏管线合法，同一期口径）</li>
 * </ul>
 * 只要 {@link #hasErrors()} 为 true，调用方就**不得**据此构建真实管线（也不得算缓存键）。
 *
 * @param slots       绑定槽列表（不可变；顺序即 canonical text 的打印顺序，不强制升序）
 * @param diagnostics 聚合诊断（不可变；含各槽自身诊断的扁平化副本）
 */
public record VertexBindingLayout(List<VertexBindingSlot> slots, List<ModelDiagnostic> diagnostics) {

    /** 规范文本头（版本化）。 */
    public static final String CANONICAL_HEADER = "vertex-binding-layout v1";

    /**
     * 受支持的绑定槽号上限（槽号合法范围 = 0 .. MAX_BINDING_SLOTS-1）。
     *
     * <p>取 16 的公开图形事实依据：Vulkan 规范保证 {@code maxVertexInputBindings >= 16}，
     * 且 OpenGL 的最小顶点属性数组数同为 16 —— 超出此范围在任何合规实现上都不可用，必须显式报错。
     */
    public static final int MAX_BINDING_SLOTS = 16;

    private static final String SLOT_PREFIX = "slot=";
    private static final String STRIDE_PREFIX = "stride=";
    private static final String OFFSET_PREFIX = "offset=";
    private static final String SIZE_PREFIX = "size=";

    /** 紧凑构造器：列表不可变、非 null。 */
    public VertexBindingLayout {
        slots = List.copyOf(slots);
        diagnostics = List.copyOf(diagnostics);
    }

    /**
     * 从槽列表生成中间表示（顺序原样保留），并逐槽校验 + 跨槽校验。
     *
     * @param slots 槽列表（可为空 = 无顶点缓冲的全屏管线；不可为 null，元素可为 null → 显式诊断）
     * @return 中间表示 + 显式诊断
     */
    public static VertexBindingLayout ofSlots(List<VertexBindingSlot> slots) {
        Objects.requireNonNull(slots, "slots");
        List<VertexBindingSlot> accepted = new ArrayList<>(slots.size());
        List<ModelDiagnostic> diagnostics = new ArrayList<>();
        Set<Integer> acceptedIndices = new HashSet<>();

        if (slots.isEmpty()) {
            diagnostics.add(ModelDiagnostic.info("EMPTY_VERTEX_BINDING_LAYOUT",
                    "no vertex binding slots declared: valid only for pipelines without a vertex buffer"));
        }

        for (VertexBindingSlot slot : slots) {
            if (slot == null) {
                diagnostics.add(ModelDiagnostic.error("NULL_BINDING_SLOT",
                        "null binding slot at position " + accepted.size() + " ignored"));
                continue;
            }
            if (slot.layout() == null) {
                diagnostics.add(ModelDiagnostic.error("NULL_SLOT_LAYOUT",
                        "binding slot " + slot.bindingIndex() + " has no layout; slot ignored"));
                continue;
            }
            // 槽内诊断先聚合（含「这个槽为什么是空的」这类原因），再决定是否接受该槽；
            // 否则被跳过的槽会把原因一起吞掉（T11 不许静默失败）。
            diagnostics.addAll(slot.layout().diagnostics());
            if (slot.bindingIndex() < 0 || slot.bindingIndex() >= MAX_BINDING_SLOTS) {
                diagnostics.add(ModelDiagnostic.error("BINDING_SLOT_OUT_OF_RANGE",
                        "binding slot " + slot.bindingIndex() + " out of range 0.." + (MAX_BINDING_SLOTS - 1)
                                + "; slot ignored"));
                continue;
            }
            if (!acceptedIndices.add(slot.bindingIndex())) {
                diagnostics.add(ModelDiagnostic.error("DUPLICATE_BINDING_SLOT",
                        "binding slot " + slot.bindingIndex() + " declared more than once; first declaration wins"));
                continue;
            }
            if (slot.attributeCount() == 0) {
                diagnostics.add(ModelDiagnostic.error("EMPTY_BINDING_SLOT",
                        "binding slot " + slot.bindingIndex() + " has no vertex attributes; slot ignored"
                                + " (for pipelines without a vertex buffer use an empty slot list)"));
                continue;
            }
            accepted.add(slot);
        }

        Map<String, Integer> firstOwner = new HashMap<>();
        for (VertexBindingSlot slot : accepted) {
            for (VertexLayout.AttributeOffset attribute : slot.layout().attributes()) {
                Integer owner = firstOwner.putIfAbsent(attribute.name(), slot.bindingIndex());
                if (owner != null && owner != slot.bindingIndex()) {
                    diagnostics.add(ModelDiagnostic.error("AMBIGUOUS_ATTRIBUTE_OWNERSHIP",
                            "attribute '" + attribute.name() + "' is provided by binding slots " + owner
                                    + " and " + slot.bindingIndex() + "; a shader attribute must have one owner"));
                }
            }
        }
        return new VertexBindingLayout(accepted, diagnostics);
    }

    /**
     * 从「槽号 → 属性声明」映射生成中间表示。
     *
     * <p>按槽号**升序**逐个构造槽内布局（无论传入 Map 的遍历顺序如何，结果规范文本都一致 ——
     * 确定性来自这里，便于缓存键稳定）。null 槽值 → NPE（显式失败，不静默）。
     *
     * @param declarationsBySlot 槽号到属性声明的映射（不可为 null；键为 null 会由 TreeMap 直接抛 NPE）
     * @return 中间表示 + 显式诊断
     */
    public static VertexBindingLayout fromDeclarations(Map<Integer, List<AttributeDecl>> declarationsBySlot) {
        Objects.requireNonNull(declarationsBySlot, "declarationsBySlot");
        List<VertexBindingSlot> slots = new ArrayList<>(declarationsBySlot.size());
        for (Map.Entry<Integer, List<AttributeDecl>> entry : new TreeMap<>(declarationsBySlot).entrySet()) {
            slots.add(new VertexBindingSlot(entry.getKey(), VertexLayout.ofDeclarations(entry.getValue())));
        }
        return ofSlots(slots);
    }

    /**
     * 单个槽（槽号 0）的便捷构造 —— 一期单绑定形态的多槽表达。
     *
     * <p>注意：传入空布局会得到显式 ERROR {@code EMPTY_BINDING_SLOT}（T11）；
     * 无顶点缓冲的管线应使用空槽列表（{@link #ofSlots(List)} 传空列表）。
     *
     * @param layout 槽 0 的交错布局（不可为 null）
     */
    public static VertexBindingLayout singleSlot(VertexLayout layout) {
        Objects.requireNonNull(layout, "layout");
        return ofSlots(List.of(new VertexBindingSlot(0, layout)));
    }

    /** 槽数量。 */
    public int slotCount() {
        return this.slots.size();
    }

    /** 全部槽的属性条目总数。 */
    public int attributeCount() {
        int total = 0;
        for (VertexBindingSlot slot : this.slots) {
            total += slot.attributeCount();
        }
        return total;
    }

    /** 槽号列表（打印顺序）。 */
    public List<Integer> bindingIndices() {
        List<Integer> indices = new ArrayList<>(this.slots.size());
        for (VertexBindingSlot slot : this.slots) {
            indices.add(slot.bindingIndex());
        }
        return List.copyOf(indices);
    }

    /** 按槽号查槽（不存在 → empty）。 */
    public Optional<VertexBindingSlot> findSlot(int bindingIndex) {
        for (VertexBindingSlot slot : this.slots) {
            if (slot.bindingIndex() == bindingIndex) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    /** 只返回 ERROR 级诊断（含各槽聚合进来的）。 */
    public List<ModelDiagnostic> errors() {
        List<ModelDiagnostic> errors = new ArrayList<>();
        for (ModelDiagnostic diagnostic : this.diagnostics) {
            if (diagnostic.isError()) {
                errors.add(diagnostic);
            }
        }
        return List.copyOf(errors);
    }

    /** 是否存在 ERROR 级诊断。 */
    public boolean hasErrors() {
        for (ModelDiagnostic diagnostic : this.diagnostics) {
            if (diagnostic.isError()) {
                return true;
            }
        }
        return false;
    }

    /** 是否存在指定诊断码的条目（单测断言用，避免依赖诊断顺序）。 */
    public boolean hasDiagnostic(String code) {
        for (ModelDiagnostic diagnostic : this.diagnostics) {
            if (diagnostic.code().equals(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 自检：无 ERROR，且每个槽自身自洽（一期判定）、槽号两两不同且在合法范围内。
     *
     * <p>这是 08-TESTING §5「mesh stride != binding stride」双侧校验里 binding 一侧的按槽版本：
     * 主线逐槽拿 {@link VertexBindingSlot#stride()} 与 mesh stride 比对（T9 / X8）。
     */
    public boolean isConsistent() {
        if (this.hasErrors()) {
            return false;
        }
        Set<Integer> indices = new HashSet<>();
        for (VertexBindingSlot slot : this.slots) {
            if (slot.bindingIndex() < 0 || slot.bindingIndex() >= MAX_BINDING_SLOTS) {
                return false;
            }
            if (!indices.add(slot.bindingIndex())) {
                return false;
            }
            if (!slot.isConsistent()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 规范文本：头 + 每槽一个槽头行（{@code slot=<i> stride=<n>}）+ 每属性一行
     * （{@code <槽号> <name> <type> offset=<n> size=<n>}，属性部分复用一期格式）。
     *
     * <p>可打印、可比对；{@link #parseCanonicalText(String)} 可反向解析并逐项重算比对。
     */
    public String canonicalText() {
        StringBuilder text = new StringBuilder(CANONICAL_HEADER).append('\n');
        for (VertexBindingSlot slot : this.slots) {
            text.append(SLOT_PREFIX).append(slot.bindingIndex())
                    .append(' ').append(STRIDE_PREFIX).append(slot.stride()).append('\n');
            for (VertexLayout.AttributeOffset attribute : slot.layout().attributes()) {
                text.append(slot.bindingIndex()).append(' ').append(attribute.canonicalText()).append('\n');
            }
        }
        return text.toString();
    }

    /**
     * 反解规范文本（沿用一期口径：重新推导 offset / size / stride 并与文本里印的值逐项比对，
     * 任何不一致都是 ERROR；返回值以推导值为准）。
     *
     * <p>额外校验：属性行必须落在最近一个槽头之下、行内槽号必须等于当前槽号、
     * 槽头重复会被合并并报 {@code DUPLICATE_BINDING_SLOT}。
     *
     * @param text 规范文本（不可为 null；空文本 → BAD_HEADER 显式诊断）
     * @return 中间表示 + 解析诊断
     */
    public static VertexBindingLayout parseCanonicalText(String text) {
        Objects.requireNonNull(text, "text");
        List<ModelDiagnostic> diagnostics = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        if (!CANONICAL_HEADER.equals(lines[0].trim())) {
            diagnostics.add(ModelDiagnostic.error("BAD_HEADER",
                    "expected first line '" + CANONICAL_HEADER + "' but was: '" + lines[0] + "'"));
            return new VertexBindingLayout(List.of(), diagnostics);
        }

        List<Integer> slotOrder = new ArrayList<>();
        Map<Integer, Integer> declaredStrides = new LinkedHashMap<>();
        Map<Integer, List<AttributeDecl>> declarationsBySlot = new LinkedHashMap<>();
        Map<Integer, List<int[]>> printedValuesBySlot = new LinkedHashMap<>();
        int currentSlot = -1;

        for (int lineIndex = 1; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex].trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith(SLOT_PREFIX)) {
                currentSlot = parseSlotHeader(line, declaredStrides, slotOrder, diagnostics);
                if (currentSlot >= 0) {
                    declarationsBySlot.computeIfAbsent(currentSlot, key -> new ArrayList<>());
                    printedValuesBySlot.computeIfAbsent(currentSlot, key -> new ArrayList<>());
                }
                continue;
            }
            parseAttributeLine(line, currentSlot, declarationsBySlot, printedValuesBySlot, diagnostics);
        }

        List<VertexBindingSlot> parsedSlots = new ArrayList<>(slotOrder.size());
        for (Integer slotIndex : slotOrder) {
            List<AttributeDecl> declarations = declarationsBySlot.get(slotIndex);
            VertexLayout computed = VertexLayout.ofDeclarations(declarations);
            List<int[]> printedValues = printedValuesBySlot.get(slotIndex);
            for (int i = 0; i < declarations.size(); i++) {
                Optional<VertexLayout.AttributeOffset> actual = computed.findAttribute(declarations.get(i).name());
                if (actual.isEmpty()) {
                    continue;
                }
                int[] printed = printedValues.get(i);
                if (actual.get().offset() != printed[0]) {
                    diagnostics.add(ModelDiagnostic.error("ATTRIBUTE_OFFSET_MISMATCH",
                            "slot " + slotIndex + " attribute '" + declarations.get(i).name() + "': printed offset="
                                    + printed[0] + " but recomputed " + actual.get().offset()));
                }
                if (actual.get().byteSize() != printed[1]) {
                    diagnostics.add(ModelDiagnostic.error("ATTRIBUTE_SIZE_MISMATCH",
                            "slot " + slotIndex + " attribute '" + declarations.get(i).name() + "': printed size="
                                    + printed[1] + " but format " + actual.get().format().glslName()
                                    + " is " + actual.get().byteSize() + " bytes"));
                }
            }
            int declared = declaredStrides.get(slotIndex);
            if (declared != computed.stride()) {
                diagnostics.add(ModelDiagnostic.error("SLOT_STRIDE_MISMATCH",
                        "slot " + slotIndex + ": printed stride=" + declared + " but recomputed " + computed.stride()));
            }
            parsedSlots.add(new VertexBindingSlot(slotIndex, computed));
        }

        VertexBindingLayout validated = ofSlots(parsedSlots);
        diagnostics.addAll(validated.diagnostics());
        return new VertexBindingLayout(validated.slots(), diagnostics);
    }

    private static int parseSlotHeader(
            String line,
            Map<Integer, Integer> declaredStrides,
            List<Integer> slotOrder,
            List<ModelDiagnostic> diagnostics) {
        String[] parts = line.split("\\s+");
        boolean wellFormed = parts.length == 2 && parts[1].startsWith(STRIDE_PREFIX);
        Integer slotIndex = wellFormed ? parseIntOrNull(parts[0].substring(SLOT_PREFIX.length())) : null;
        Integer stride = wellFormed ? parseIntOrNull(parts[1].substring(STRIDE_PREFIX.length())) : null;
        if (slotIndex == null || stride == null) {
            diagnostics.add(ModelDiagnostic.error("BAD_SLOT_LINE",
                    "expected 'slot=<int> stride=<int>' but was: '" + line + "'"));
            return -1;
        }
        if (declaredStrides.putIfAbsent(slotIndex, stride) == null) {
            slotOrder.add(slotIndex);
        } else {
            diagnostics.add(ModelDiagnostic.error("DUPLICATE_BINDING_SLOT",
                    "binding slot " + slotIndex + " declared more than once in canonical text; declarations merged"));
        }
        return slotIndex;
    }

    private static void parseAttributeLine(
            String line,
            int currentSlot,
            Map<Integer, List<AttributeDecl>> declarationsBySlot,
            Map<Integer, List<int[]>> printedValuesBySlot,
            List<ModelDiagnostic> diagnostics) {
        String[] parts = line.split("\\s+");
        boolean wellFormed = parts.length == 5
                && parts[3].startsWith(OFFSET_PREFIX)
                && parts[4].startsWith(SIZE_PREFIX);
        if (!wellFormed) {
            diagnostics.add(ModelDiagnostic.error("MALFORMED_ATTRIBUTE_LINE",
                    "expected '<slot> <name> <type> offset=<int> size=<int>' but was: '" + line + "'"));
            return;
        }
        Integer slotIndex = parseIntOrNull(parts[0]);
        Integer offset = parseIntOrNull(parts[3].substring(OFFSET_PREFIX.length()));
        Integer size = parseIntOrNull(parts[4].substring(SIZE_PREFIX.length()));
        if (slotIndex == null || offset == null || size == null) {
            diagnostics.add(ModelDiagnostic.error("MALFORMED_ATTRIBUTE_LINE",
                    "slot/offset/size value is not an integer: '" + line + "'"));
            return;
        }
        if (currentSlot == -1) {
            diagnostics.add(ModelDiagnostic.error("ATTRIBUTE_WITHOUT_SLOT_HEADER",
                    "attribute line appears before any 'slot=' header: '" + line + "'"));
            return;
        }
        if (slotIndex != currentSlot) {
            diagnostics.add(ModelDiagnostic.error("ATTRIBUTE_SLOT_MISMATCH",
                    "attribute line declares slot " + slotIndex + " but the current slot header is " + currentSlot));
            return;
        }
        declarationsBySlot.get(currentSlot).add(new AttributeDecl(parts[1], parts[2]));
        printedValuesBySlot.get(currentSlot).add(new int[] {offset, size});
    }

    private static Integer parseIntOrNull(String raw) {
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException notAnInteger) {
            return null;
        }
    }
}
