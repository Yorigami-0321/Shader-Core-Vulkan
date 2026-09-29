package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】E 线二期单测 / 多顶点绑定槽（binding>0）布局与语义
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §4「顶点格式扩展」属性表；
 *    ② docs/08-TESTING.md §5「顶点 stride 自检」（mesh stride != binding stride = 彩色尖刺且无报错）；
 *    ③ docs/18-PARALLEL.md §4 E 线「只产中间表示」与 §7.3 证据规范；
 *    ④ 公开图形事实：Vulkan 规范保证 maxVertexInputBindings >= 16，多绑定槽各自独立 stride，
 *      属性以其 (binding, offset) 定位，同一属性只能由一个槽提供。
 *    许可证：JUnit 5 = EPL-2.0（仅 testImplementation 依赖，不进分发 jar）；本仓库 docs 为 MIT 项目自有文档。
 *    ⚠️ VulkanMod = LGPL-3.0 → 按 docs/07-CONSTRAINTS.md L12「判不过就换参考、不读它的代码」零接触；
 *    数值期望值全部来自 04-SPEC §4 与一期已冻结计算结果。
 *    → 能否并入本项目（MIT）：可以（本文件为独立编写的测试）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：JUnit 5 标准断言；槽 0 的期望值照抄一期数值（回归保护），
 *    槽 1 用 F2 的 at_tangent/at_velocity（OF 官方属性表事实，E 线二期多槽样本）。
 * 2. 备选：手写 main() 断言 —— 否决（F4 已接好 JUnit 5，失败要能在 ./gradlew test 里显式红）。
 * 3. 我们的差异点：除数值断言外，把「槽号越界 / 重复槽 / 空槽 / 属性归属不明」四类 ERROR 与
 *    canonical text 篡改检测逐条断言（T11 不静默）。
 * 4. 许可证核对：本项目 MIT；JUnit EPL-2.0 仅测试期依赖；零第三方代码复制。
 * 5. 性能基线：测试代码不进运行时，无性能影响（18-PARALLEL §7.7）。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.vkdisp.pack.VertexAttribute;

/** 多绑定槽：槽 0 回归、多槽数值、四类显式诊断、canonical text 往返与篡改检测、缓存键接线。 */
class VertexBindingLayoutTest {

    /** 04-SPEC §4 属性表顺序（与一期测试同源）。 */
    private static final List<VertexAttribute> SPEC_TABLE_ORDER = List.of(
            VertexAttribute.Position,
            VertexAttribute.Color,
            VertexAttribute.UV0,
            VertexAttribute.UV1,
            VertexAttribute.UV2,
            VertexAttribute.Normal,
            VertexAttribute.mc_Entity,
            VertexAttribute.mc_midTexCoord);

    /** 04-SPEC §4 逐项 offset（一期结论）。 */
    private static final int[] SPEC_OFFSETS = {0, 12, 16, 24, 28, 32, 35, 39};

    /** 04-SPEC §4 逐项字节数（一期结论）。 */
    private static final int[] SPEC_SIZES = {12, 4, 8, 4, 4, 3, 4, 8};

    /** 槽 1 的 OF 扩展属性流（at_tangent vec4f + at_velocity vec3f，与槽 0 属性名不重叠）。 */
    private static final List<VertexAttribute> SECOND_STREAM = List.of(
            VertexAttribute.at_tangent,
            VertexAttribute.at_velocity);

    private static List<AttributeDecl> declarations(List<VertexAttribute> attributes) {
        List<AttributeDecl> declarations = new ArrayList<>(attributes.size());
        for (VertexAttribute attribute : attributes) {
            declarations.add(AttributeDecl.of(attribute));
        }
        return declarations;
    }

    private static VertexBindingLayout twoSlotLayout() {
        return VertexBindingLayout.ofSlots(List.of(
                new VertexBindingSlot(0, VertexLayout.of(SPEC_TABLE_ORDER)),
                new VertexBindingSlot(1, VertexLayout.of(SECOND_STREAM))));
    }

    private static PipelineSpecIr terrainSpec() {
        return new PipelineSpecIr("gbuffers_terrain",
                "vkdisp:pipeline/gbuffers_terrain",
                "vkdisp:gbuffers_terrain",
                "vkdisp:gbuffers_terrain",
                List.of("POSITION_COLOR_TEXTURE"),
                false);
    }

    @Test
    void slotZeroNumbersMatchPhaseOneItemByItem() {
        VertexBindingSlot slotZero = VertexBindingLayout.singleSlot(VertexLayout.of(SPEC_TABLE_ORDER)).findSlot(0)
                .orElseThrow();
        assertEquals(47, slotZero.stride(), "槽 0 stride 必须与一期一致");
        assertEquals(8, slotZero.attributeCount());
        for (int i = 0; i < SPEC_TABLE_ORDER.size(); i++) {
            VertexLayout.AttributeOffset attribute = slotZero.layout()
                    .findAttribute(SPEC_TABLE_ORDER.get(i).name()).orElseThrow();
            assertEquals(SPEC_OFFSETS[i], attribute.offset(), SPEC_TABLE_ORDER.get(i).name() + " offset");
            assertEquals(SPEC_SIZES[i], attribute.byteSize(), SPEC_TABLE_ORDER.get(i).name() + " size");
        }
        assertEquals(VertexLayout.of(SPEC_TABLE_ORDER), slotZero.layout(),
                "槽 0 的布局对象必须与一期 VertexLayout 输出值相等（回归保护）");
        assertEquals(VertexLayout.of(SPEC_TABLE_ORDER).canonicalText(), slotZero.layout().canonicalText());
    }

    @Test
    void multiSlotComputesIndependentPerSlotStrides() {
        VertexBindingLayout layout = twoSlotLayout();
        assertFalse(layout.hasErrors(), layout.diagnostics().toString());
        assertTrue(layout.isConsistent());
        assertEquals(2, layout.slotCount());
        assertEquals(10, layout.attributeCount());
        assertEquals(List.of(0, 1), layout.bindingIndices());

        VertexBindingSlot second = layout.findSlot(1).orElseThrow();
        assertEquals(28, second.stride(), "槽 1 stride = vec4f(16) + vec3f(12)");
        assertEquals(0, second.layout().findAttribute("at_tangent").orElseThrow().offset());
        assertEquals(16, second.layout().findAttribute("at_velocity").orElseThrow().offset());
        assertEquals(List.of("at_tangent", "at_velocity"), second.attributeNames());
    }

    @Test
    void fromDeclarationsIsDeterministicRegardlessOfMapOrder() {
        Map<Integer, List<AttributeDecl>> reversedOrder = new LinkedHashMap<>();
        reversedOrder.put(1, declarations(SECOND_STREAM));
        reversedOrder.put(0, declarations(SPEC_TABLE_ORDER));

        Map<Integer, List<AttributeDecl>> forwardOrder = new HashMap<>();
        forwardOrder.put(0, declarations(SPEC_TABLE_ORDER));
        forwardOrder.put(1, declarations(SECOND_STREAM));

        VertexBindingLayout fromReversed = VertexBindingLayout.fromDeclarations(reversedOrder);
        VertexBindingLayout fromForward = VertexBindingLayout.fromDeclarations(forwardOrder);

        assertEquals(List.of(0, 1), fromReversed.bindingIndices(), "槽号必须升序（确定性来源）");
        assertEquals(fromReversed.canonicalText(), fromForward.canonicalText(),
                "传入 Map 的遍历顺序不得影响规范文本");
        assertEquals(47, fromReversed.findSlot(0).orElseThrow().stride());
        assertEquals(28, fromReversed.findSlot(1).orElseThrow().stride());
    }

    @Test
    void emptySlotListIsInfoNotError() {
        VertexBindingLayout layout = VertexBindingLayout.ofSlots(List.of());
        assertEquals(0, layout.slotCount());
        assertEquals(0, layout.attributeCount());
        assertFalse(layout.hasErrors(), "无顶点缓冲的全屏管线合法");
        assertTrue(layout.hasDiagnostic("EMPTY_VERTEX_BINDING_LAYOUT"), layout.diagnostics().toString());
        assertTrue(layout.isConsistent());
        assertEquals("vertex-binding-layout v1\n", layout.canonicalText());
    }

    @Test
    void emptySlotIsExplicitError() {
        VertexBindingLayout layout = VertexBindingLayout.singleSlot(VertexLayout.of());
        assertTrue(layout.hasDiagnostic("EMPTY_BINDING_SLOT"), layout.diagnostics().toString());
        assertTrue(layout.hasErrors(), "空槽是 ERROR（T11）：无顶点缓冲请用空槽列表");
        assertEquals(0, layout.slotCount(), "空槽被跳过");
        assertFalse(layout.isConsistent());
    }

    @Test
    void negativeSlotIndexIsError() {
        VertexBindingLayout layout = VertexBindingLayout.ofSlots(List.of(
                new VertexBindingSlot(-1, VertexLayout.of(SPEC_TABLE_ORDER))));
        assertTrue(layout.hasDiagnostic("BINDING_SLOT_OUT_OF_RANGE"), layout.diagnostics().toString());
        assertEquals(0, layout.slotCount());
    }

    @Test
    void outOfRangeSlotIndexIsErrorAndRangeIsSixteen() {
        assertEquals(16, VertexBindingLayout.MAX_BINDING_SLOTS, "Vulkan 保证 maxVertexInputBindings >= 16");
        VertexBindingLayout outOfRange = VertexBindingLayout.ofSlots(List.of(
                new VertexBindingSlot(16, VertexLayout.of(SPEC_TABLE_ORDER))));
        assertTrue(outOfRange.hasDiagnostic("BINDING_SLOT_OUT_OF_RANGE"), outOfRange.diagnostics().toString());

        VertexBindingLayout lastLegal = VertexBindingLayout.ofSlots(List.of(
                new VertexBindingSlot(15, VertexLayout.of(SPEC_TABLE_ORDER))));
        assertFalse(lastLegal.hasErrors(), "槽 15 仍在合法范围内");
        assertEquals(15, lastLegal.bindingIndices().get(0));
    }

    @Test
    void duplicateSlotIsErrorAndFirstWins() {
        VertexBindingLayout layout = VertexBindingLayout.ofSlots(List.of(
                new VertexBindingSlot(1, VertexLayout.of(SPEC_TABLE_ORDER)),
                new VertexBindingSlot(1, VertexLayout.of(SECOND_STREAM))));
        assertEquals(1, layout.slotCount());
        assertTrue(layout.hasDiagnostic("DUPLICATE_BINDING_SLOT"), layout.diagnostics().toString());
        assertTrue(layout.hasErrors());
        assertEquals(47, layout.findSlot(1).orElseThrow().stride(), "首个声明生效");
    }

    @Test
    void nullSlotIsExplicitError() {
        List<VertexBindingSlot> slots = new ArrayList<>();
        slots.add(new VertexBindingSlot(0, VertexLayout.of(SPEC_TABLE_ORDER)));
        slots.add(null);
        VertexBindingLayout layout = VertexBindingLayout.ofSlots(slots);
        assertEquals(1, layout.slotCount());
        assertTrue(layout.hasDiagnostic("NULL_BINDING_SLOT"), layout.diagnostics().toString());
    }

    @Test
    void slotLayoutDiagnosticsAreAggregated() {
        VertexLayout brokenSlot = VertexLayout.ofDeclarations(List.of(new AttributeDecl("Bogus", "vec5f")));
        VertexBindingLayout layout = VertexBindingLayout.ofSlots(List.of(
                new VertexBindingSlot(0, VertexLayout.of(SPEC_TABLE_ORDER)),
                new VertexBindingSlot(2, brokenSlot)));
        assertTrue(layout.hasDiagnostic("UNKNOWN_ATTRIBUTE_TYPE"), layout.diagnostics().toString());
        assertTrue(layout.hasDiagnostic("EMPTY_BINDING_SLOT"), "未知类型被跳过后该槽为空 → 同时显式报空槽");
        assertTrue(layout.hasErrors());
        assertEquals(1, layout.slotCount(), "只有槽 0 被接受");
    }

    @Test
    void crossSlotDuplicateAttributeIsAmbiguousOwnershipError() {
        VertexBindingLayout layout = VertexBindingLayout.ofSlots(List.of(
                new VertexBindingSlot(0, VertexLayout.of(SPEC_TABLE_ORDER)),
                new VertexBindingSlot(1, VertexLayout.of(VertexAttribute.UV0, VertexAttribute.UV1))));
        assertTrue(layout.hasDiagnostic("AMBIGUOUS_ATTRIBUTE_OWNERSHIP"), layout.diagnostics().toString());
        assertTrue(layout.hasErrors());
        assertEquals(2, layout.slotCount(), "归属不明只报错，不静默丢槽（保证诊断可定位）");
        assertFalse(layout.isConsistent());
    }

    @Test
    void canonicalTextIsExactAndStable() {
        String expected = "vertex-binding-layout v1\n"
                + "slot=0 stride=47\n"
                + "0 Position vec3f offset=0 size=12\n"
                + "0 Color vec4ub offset=12 size=4\n"
                + "0 UV0 vec2f offset=16 size=8\n"
                + "0 UV1 vec2s offset=24 size=4\n"
                + "0 UV2 vec2s offset=28 size=4\n"
                + "0 Normal vec3b offset=32 size=3\n"
                + "0 mc_Entity vec2s offset=35 size=4\n"
                + "0 mc_midTexCoord vec2f offset=39 size=8\n"
                + "slot=1 stride=28\n"
                + "1 at_tangent vec4f offset=0 size=16\n"
                + "1 at_velocity vec3f offset=16 size=12\n";
        assertEquals(expected, twoSlotLayout().canonicalText(), "打印比对：规范文本逐字符一致");
        assertEquals(expected, twoSlotLayout().canonicalText(), "同一输入两次生成必须完全一致");
    }

    @Test
    void canonicalTextRoundTrips() {
        VertexBindingLayout layout = twoSlotLayout();
        VertexBindingLayout parsed = VertexBindingLayout.parseCanonicalText(layout.canonicalText());
        assertFalse(parsed.hasErrors(), "往返解析不应有问题：" + parsed.diagnostics());
        assertTrue(parsed.diagnostics().isEmpty(), parsed.diagnostics().toString());
        assertEquals(layout.slots(), parsed.slots());
        assertEquals(layout.canonicalText(), parsed.canonicalText());
        assertEquals(List.of(0, 1), parsed.bindingIndices());
    }

    @Test
    void emptyLayoutRoundTrips() {
        VertexBindingLayout layout = VertexBindingLayout.ofSlots(List.of());
        VertexBindingLayout parsed = VertexBindingLayout.parseCanonicalText(layout.canonicalText());
        assertEquals(0, parsed.slotCount());
        assertEquals(layout.canonicalText(), parsed.canonicalText());
    }

    @Test
    void tamperedSlotStrideIsDetected() {
        String tampered = twoSlotLayout().canonicalText().replace("slot=1 stride=28", "slot=1 stride=29");
        VertexBindingLayout parsed = VertexBindingLayout.parseCanonicalText(tampered);
        assertTrue(parsed.hasDiagnostic("SLOT_STRIDE_MISMATCH"), parsed.diagnostics().toString());
        assertEquals(28, parsed.findSlot(1).orElseThrow().stride(), "以重新推导值为准");
    }

    @Test
    void tamperedAttributeOffsetIsDetected() {
        String tampered = twoSlotLayout().canonicalText()
                .replace("1 at_velocity vec3f offset=16", "1 at_velocity vec3f offset=17");
        VertexBindingLayout parsed = VertexBindingLayout.parseCanonicalText(tampered);
        assertTrue(parsed.hasDiagnostic("ATTRIBUTE_OFFSET_MISMATCH"), parsed.diagnostics().toString());
    }

    @Test
    void tamperedAttributeSizeIsDetected() {
        String tampered = twoSlotLayout().canonicalText()
                .replace("1 at_tangent vec4f offset=0 size=16", "1 at_tangent vec4f offset=0 size=12");
        VertexBindingLayout parsed = VertexBindingLayout.parseCanonicalText(tampered);
        assertTrue(parsed.hasDiagnostic("ATTRIBUTE_SIZE_MISMATCH"), parsed.diagnostics().toString());
    }

    @Test
    void attributeSlotTokenMismatchIsDetected() {
        String tampered = twoSlotLayout().canonicalText().replace("1 at_tangent ", "0 at_tangent ");
        VertexBindingLayout parsed = VertexBindingLayout.parseCanonicalText(tampered);
        assertTrue(parsed.hasDiagnostic("ATTRIBUTE_SLOT_MISMATCH"), parsed.diagnostics().toString());
    }

    @Test
    void attributeWithoutSlotHeaderIsDetected() {
        String text = "vertex-binding-layout v1\n0 Position vec3f offset=0 size=12\n";
        VertexBindingLayout parsed = VertexBindingLayout.parseCanonicalText(text);
        assertTrue(parsed.hasDiagnostic("ATTRIBUTE_WITHOUT_SLOT_HEADER"), parsed.diagnostics().toString());
        assertEquals(0, parsed.slotCount());
    }

    @Test
    void duplicateSlotHeaderInTextIsDetected() {
        String text = "vertex-binding-layout v1\n"
                + "slot=1 stride=8\n"
                + "1 UV0 vec2f offset=0 size=8\n"
                + "slot=1 stride=8\n";
        VertexBindingLayout parsed = VertexBindingLayout.parseCanonicalText(text);
        assertTrue(parsed.hasDiagnostic("DUPLICATE_BINDING_SLOT"), parsed.diagnostics().toString());
    }

    @Test
    void badSlotLineAndMalformedAttributeLineAreDetected() {
        VertexBindingLayout badSlot = VertexBindingLayout.parseCanonicalText(
                "vertex-binding-layout v1\nslot=x stride=8\n");
        assertTrue(badSlot.hasDiagnostic("BAD_SLOT_LINE"), badSlot.diagnostics().toString());

        VertexBindingLayout missingStride = VertexBindingLayout.parseCanonicalText(
                "vertex-binding-layout v1\nslot=0\n");
        assertTrue(missingStride.hasDiagnostic("BAD_SLOT_LINE"), missingStride.diagnostics().toString());

        VertexBindingLayout badAttribute = VertexBindingLayout.parseCanonicalText(
                "vertex-binding-layout v1\nslot=0 stride=8\n0 UV0 vec2f\n");
        assertTrue(badAttribute.hasDiagnostic("MALFORMED_ATTRIBUTE_LINE"), badAttribute.diagnostics().toString());
    }

    @Test
    void badHeaderAndNullTextAreExplicit() {
        VertexBindingLayout blank = VertexBindingLayout.parseCanonicalText("");
        assertTrue(blank.hasDiagnostic("BAD_HEADER"), blank.diagnostics().toString());
        assertEquals(0, blank.slotCount());

        VertexBindingLayout wrongVersion = VertexBindingLayout.parseCanonicalText("vertex-binding-layout v2\n");
        assertTrue(wrongVersion.hasDiagnostic("BAD_HEADER"), "版本头不同必须显式拒绝");

        assertThrows(NullPointerException.class, () -> VertexBindingLayout.parseCanonicalText(null));
    }

    @Test
    void nullInputsAreExplicit() {
        assertThrows(NullPointerException.class, () -> VertexBindingLayout.ofSlots(null));
        assertThrows(NullPointerException.class, () -> VertexBindingLayout.fromDeclarations(null));
        assertThrows(NullPointerException.class, () -> VertexBindingLayout.singleSlot(null));
        assertThrows(NullPointerException.class, () -> new VertexBindingSlot(0, null));

        Map<Integer, List<AttributeDecl>> withNullValue = new HashMap<>();
        withNullValue.put(0, null);
        assertThrows(NullPointerException.class, () -> VertexBindingLayout.fromDeclarations(withNullValue));
    }

    @Test
    void slotsAreUnmodifiableAndMissingSlotIsEmpty() {
        VertexBindingLayout layout = twoSlotLayout();
        assertThrows(UnsupportedOperationException.class, layout.slots()::clear);
        assertThrows(UnsupportedOperationException.class, layout.diagnostics()::clear);
        assertTrue(layout.findSlot(7).isEmpty());
        assertTrue(layout.bindingIndices().indexOf(1) == 1);
        assertThrows(UnsupportedOperationException.class, layout.bindingIndices()::clear);
    }

    @Test
    void multiSlotCacheKeyIsStableAndRejectsBrokenLayouts() {
        BindGroupLayoutIr bindings = BindGroupLayoutIr.builder().sampler("colortex0").build();
        PipelineSpecIr spec = terrainSpec();
        VertexBindingLayout twoSlots = twoSlotLayout();

        PipelineCacheKey first = PipelineCacheKey.ofMultiSlot(spec, twoSlots, bindings);
        PipelineCacheKey second = PipelineCacheKey.ofMultiSlot(spec, twoSlots, bindings);
        assertEquals(first, second, "多槽键必须稳定");
        assertEquals(first.fingerprint(), second.fingerprint());

        VertexBindingLayout otherSecondStream = VertexBindingLayout.ofSlots(List.of(
                new VertexBindingSlot(0, VertexLayout.of(SPEC_TABLE_ORDER)),
                new VertexBindingSlot(1, VertexLayout.of(VertexAttribute.at_midBlock))));
        assertNotEquals(first, PipelineCacheKey.ofMultiSlot(spec, otherSecondStream, bindings),
                "槽 1 内容变了必须换键");

        PipelineCacheKey singleSlotForm = PipelineCacheKey.of(spec, VertexLayout.of(SPEC_TABLE_ORDER), bindings);
        assertNotEquals(first, singleSlotForm,
                "单槽入口与多槽入口是两种声明形态，不得混用（宁可 cache miss，不可错复用）");

        VertexBindingLayout broken = VertexBindingLayout.ofSlots(List.of(
                new VertexBindingSlot(1, VertexLayout.of(SPEC_TABLE_ORDER)),
                new VertexBindingSlot(1, VertexLayout.of(SECOND_STREAM))));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> PipelineCacheKey.ofMultiSlot(spec, broken, bindings));
        assertTrue(failure.getMessage().contains("DUPLICATE_BINDING_SLOT"), failure.getMessage());

        assertThrows(NullPointerException.class, () -> PipelineCacheKey.ofMultiSlot(spec, null, bindings));
    }
}
