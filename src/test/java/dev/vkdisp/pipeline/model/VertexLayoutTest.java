package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】E 线单测 / 顶点属性 → stride/offset 计算表（任务 ①，08-TESTING §5 数值断言）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §4 属性表（Position/vec3f、Color/vec4ub、UV0/vec2f、UV1/vec2s、
 *    UV2/vec2s、Normal/vec3b、mc_Entity/vec2s、mc_midTexCoord/vec2f）；
 *    ② docs/08-TESTING.md §5「顶点 stride 自检」；③ F2 冻结的 dev.vkdisp.pack.VertexAttribute。
 *    许可证：JUnit 5 = EPL-2.0（仅 testImplementation 依赖，不进分发 jar）；本仓库 docs 为 MIT 项目自有文档。
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触 —— 按 docs/07-CONSTRAINTS.md L12
 *    「判不过就换参考，不再读它的代码」。
 *    → 能否并入本项目（MIT）：可以（本文件为独立编写的测试）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：JUnit 5 标准断言（assertEquals / assertThrows / assertTrue），用例取值全部照抄
 *    本仓库 docs/04-SPEC.md §4 的属性表与 docs/08-TESTING.md §5 的 stride 自检要求。
 * 2. 备选：手写 main() 断言 —— 否决（F4 已接好 JUnit 5，且失败要能在 ./gradlew test 里显式红）。
 * 3. 我们的差异点：期望值（offset / size / stride）逐项写在测试里做数值断言，并额外覆盖 08-TESTING §7 的边界清单
 *    （空集合 / 重复项 / 未知类型 / null / 篡改文本 / 非法头）。
 * 4. 许可证核对：本项目 MIT；JUnit EPL-2.0 仅测试期依赖；零第三方代码复制。
 * 5. 性能基线：测试代码不进运行时，无性能影响（18-PARALLEL §7.7）。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.vkdisp.pack.VertexAttribute;

/** 顶点布局计算：04-SPEC §4 逐项数值断言 + 边界与篡改检测。 */
class VertexLayoutTest {

    /** 04-SPEC §4 属性表顺序（权威顺序，用于数值断言）。 */
    private static final List<VertexAttribute> SPEC_TABLE_ORDER = List.of(
            VertexAttribute.Position,
            VertexAttribute.Color,
            VertexAttribute.UV0,
            VertexAttribute.UV1,
            VertexAttribute.UV2,
            VertexAttribute.Normal,
            VertexAttribute.mc_Entity,
            VertexAttribute.mc_midTexCoord);

    /** 04-SPEC §4 逐项 offset（紧凑累加，无隐式对齐填充）。 */
    private static final int[] SPEC_OFFSETS = {0, 12, 16, 24, 28, 32, 35, 39};

    /** 04-SPEC §4 逐项字节数。 */
    private static final int[] SPEC_SIZES = {12, 4, 8, 4, 4, 3, 4, 8};

    /** 04-SPEC §4 全表 stride = 47。 */
    private static final int SPEC_STRIDE = 47;

    @Test
    void offsetsAndSizesMatch04SpecTableItemByItem() {
        VertexLayout layout = VertexLayout.of(SPEC_TABLE_ORDER);

        assertFalse(layout.hasErrors(), "合法属性集不应产生 ERROR："
                + layout.errors());
        assertEquals(SPEC_TABLE_ORDER.size(), layout.attributeCount());
        for (int i = 0; i < SPEC_TABLE_ORDER.size(); i++) {
            VertexAttribute attribute = SPEC_TABLE_ORDER.get(i);
            VertexLayout.AttributeOffset offset = layout.findAttribute(attribute.name())
                    .orElseThrow(() -> new AssertionError("missing attribute " + attribute.name()));
            assertEquals(SPEC_OFFSETS[i], offset.offset(), attribute.name() + " offset");
            assertEquals(SPEC_SIZES[i], offset.byteSize(), attribute.name() + " byteSize");
            assertEquals(attribute.glslType(), offset.format().glslName(), attribute.name() + " type");
        }
        assertEquals(SPEC_STRIDE, layout.stride(), "全表 stride");
        assertEquals(47, layout.stride(), "stride 必须等于各项 size 之和（12+4+8+4+4+3+4+8）");
        assertTrue(layout.isConsistent(), "布局必须自洽（offset 为前序 size 累加、stride 为总和）");
    }

    @Test
    void f2VertexAttributeTypesMatch04SpecTable() {
        List<String> expectedTypes = List.of(
                "vec3f", "vec4ub", "vec2f", "vec2s", "vec2s", "vec3b", "vec2s", "vec2f");
        for (int i = 0; i < SPEC_TABLE_ORDER.size(); i++) {
            assertEquals(expectedTypes.get(i), SPEC_TABLE_ORDER.get(i).glslType(),
                    SPEC_TABLE_ORDER.get(i).name() + " 的 04-SPEC §4 类型");
        }
    }

    @Test
    void subsetLayoutIsOrderSensitive() {
        VertexLayout uvFirst = VertexLayout.of(VertexAttribute.UV0, VertexAttribute.Position);
        assertEquals(0, uvFirst.findAttribute("UV0").orElseThrow().offset());
        assertEquals(8, uvFirst.findAttribute("Position").orElseThrow().offset());
        assertEquals(20, uvFirst.stride());

        VertexLayout positionFirst = VertexLayout.of(VertexAttribute.Position, VertexAttribute.UV0);
        assertEquals(0, positionFirst.findAttribute("Position").orElseThrow().offset());
        assertEquals(12, positionFirst.findAttribute("UV0").orElseThrow().offset());
        assertEquals(20, positionFirst.stride(), "同一集合、不同顺序 → stride 相同但 offset 不同");
    }

    @Test
    void emptyAttributeSetIsExplicitInfoWithZeroStride() {
        VertexLayout layout = VertexLayout.of();
        assertEquals(0, layout.attributeCount());
        assertEquals(0, layout.stride());
        assertFalse(layout.hasErrors(), "空属性集对无顶点缓冲的全屏管线是合法情形");
        assertTrue(layout.hasDiagnostic("EMPTY_ATTRIBUTE_SET"), "但必须显式 INFO，不许静默：" + layout.diagnostics());
        assertTrue(layout.isConsistent());
    }

    @Test
    void duplicateAttributeIsWarnAndFirstDeclarationWins() {
        VertexLayout layout = VertexLayout.of(VertexAttribute.UV0, VertexAttribute.UV0);
        assertEquals(1, layout.attributeCount(), "重复属性只保留首个");
        assertEquals(8, layout.stride(), "重复项不得被重复计数");
        assertFalse(layout.hasErrors());
        assertTrue(layout.hasDiagnostic("DUPLICATE_ATTRIBUTE"), layout.diagnostics().toString());
    }

    @Test
    void unknownTypeIsErrorAndDeclarationIsSkipped() {
        List<AttributeDecl> declarations = List.of(
                AttributeDecl.of(VertexAttribute.Position),
                new AttributeDecl("Bogus", "vec5f"),
                AttributeDecl.of(VertexAttribute.UV0));
        VertexLayout layout = VertexLayout.ofDeclarations(declarations);

        assertEquals(2, layout.attributeCount(), "未知类型条目必须被跳过，不猜字节数");
        assertEquals(0, layout.findAttribute("Position").orElseThrow().offset());
        assertEquals(12, layout.findAttribute("UV0").orElseThrow().offset());
        assertEquals(20, layout.stride());
        assertTrue(layout.hasDiagnostic("UNKNOWN_ATTRIBUTE_TYPE"), layout.diagnostics().toString());
        assertTrue(layout.hasErrors(), "未知类型是 ERROR（T11）");
        assertEquals(1, layout.errors().size());
        assertFalse(layout.isConsistent(), "带 ERROR 的布局不得被判为自洽");
    }

    @Test
    void invalidAttributeNameIsErrorAndDeclarationIsSkipped() {
        VertexLayout layout = VertexLayout.ofDeclarations(List.of(
                new AttributeDecl("bad name", "vec3f"),
                AttributeDecl.of(VertexAttribute.Position)));
        assertEquals(1, layout.attributeCount());
        assertTrue(layout.hasDiagnostic("BAD_ATTRIBUTE_NAME"), layout.diagnostics().toString());
    }

    @Test
    void nullDeclarationIsErrorNotCrash() {
        List<AttributeDecl> declarations = new ArrayList<>();
        declarations.add(AttributeDecl.of(VertexAttribute.Position));
        declarations.add(null);
        VertexLayout layout = VertexLayout.ofDeclarations(declarations);
        assertEquals(1, layout.attributeCount());
        assertTrue(layout.hasDiagnostic("NULL_DECLARATION"), layout.diagnostics().toString());
    }

    @Test
    void nullDeclarationListIsRejected() {
        assertThrows(NullPointerException.class, () -> VertexLayout.ofDeclarations(null));
    }

    @Test
    void attributeListIsUnmodifiable() {
        VertexLayout layout = VertexLayout.of(SPEC_TABLE_ORDER);
        assertThrows(UnsupportedOperationException.class,
                () -> layout.attributes().add(new VertexLayout.AttributeOffset("X", VertexElementFormat.VEC2F, 0, 8)));
        assertThrows(UnsupportedOperationException.class, () -> layout.diagnostics().clear());
    }

    @Test
    void canonicalTextIsExactAndStable() {
        String expected = "vertex-layout v1\n"
                + "stride=47\n"
                + "Position vec3f offset=0 size=12\n"
                + "Color vec4ub offset=12 size=4\n"
                + "UV0 vec2f offset=16 size=8\n"
                + "UV1 vec2s offset=24 size=4\n"
                + "UV2 vec2s offset=28 size=4\n"
                + "Normal vec3b offset=32 size=3\n"
                + "mc_Entity vec2s offset=35 size=4\n"
                + "mc_midTexCoord vec2f offset=39 size=8\n";
        VertexLayout layout = VertexLayout.of(SPEC_TABLE_ORDER);
        assertEquals(expected, layout.canonicalText(), "打印比对：规范文本逐字符一致");
    }

    @Test
    void canonicalTextRoundTrips() {
        VertexLayout layout = VertexLayout.of(SPEC_TABLE_ORDER);
        VertexLayout parsed = VertexLayout.parseCanonicalText(layout.canonicalText());
        assertFalse(parsed.hasErrors(), "往返解析不应有问题：" + parsed.diagnostics());
        assertEquals(layout.attributes(), parsed.attributes());
        assertEquals(layout.stride(), parsed.stride());
        assertEquals(layout.canonicalText(), parsed.canonicalText());
    }

    @Test
    void emptyLayoutRoundTrips() {
        VertexLayout layout = VertexLayout.of();
        VertexLayout parsed = VertexLayout.parseCanonicalText(layout.canonicalText());
        assertEquals(0, parsed.attributeCount());
        assertEquals(0, parsed.stride());
        assertEquals(layout.canonicalText(), parsed.canonicalText());
    }

    @Test
    void tamperedStrideIsDetected() {
        String tampered = VertexLayout.of(SPEC_TABLE_ORDER).canonicalText().replace("stride=47", "stride=48");
        VertexLayout parsed = VertexLayout.parseCanonicalText(tampered);
        assertTrue(parsed.hasDiagnostic("STRIDE_MISMATCH"), parsed.diagnostics().toString());
        assertEquals(47, parsed.stride(), "以重新推导值为准");
    }

    @Test
    void tamperedOffsetIsDetected() {
        String tampered = VertexLayout.of(SPEC_TABLE_ORDER).canonicalText().replace("offset=24", "offset=25");
        VertexLayout parsed = VertexLayout.parseCanonicalText(tampered);
        assertTrue(parsed.hasDiagnostic("ATTRIBUTE_OFFSET_MISMATCH"), parsed.diagnostics().toString());
    }

    @Test
    void tamperedSizeIsDetected() {
        String tampered = VertexLayout.of(SPEC_TABLE_ORDER).canonicalText().replace("size=3", "size=4");
        VertexLayout parsed = VertexLayout.parseCanonicalText(tampered);
        assertTrue(parsed.hasDiagnostic("ATTRIBUTE_SIZE_MISMATCH"), parsed.diagnostics().toString());
    }

    @Test
    void unknownTypeInsideCanonicalTextIsDetected() {
        String text = "vertex-layout v1\nstride=8\nBogus vec5f offset=0 size=8\n";
        VertexLayout parsed = VertexLayout.parseCanonicalText(text);
        assertTrue(parsed.hasDiagnostic("UNKNOWN_ATTRIBUTE_TYPE"), parsed.diagnostics().toString());
        assertEquals(0, parsed.attributeCount());
    }

    @Test
    void blankTextIsExplicitHeaderError() {
        VertexLayout parsed = VertexLayout.parseCanonicalText("");
        assertTrue(parsed.hasDiagnostic("BAD_HEADER"), parsed.diagnostics().toString());
        assertEquals(0, parsed.stride(), "头不合法时不猜布局，直接空布局 + 显式 ERROR");

        VertexLayout missingStride = VertexLayout.parseCanonicalText("vertex-layout v1\nUV0 vec2f offset=0 size=8\n");
        assertTrue(missingStride.hasDiagnostic("MISSING_STRIDE"), missingStride.diagnostics().toString());
    }

    @Test
    void malformedAttributeLineIsDetected() {
        String text = "vertex-layout v1\nstride=8\nUV0 vec2f\n";
        VertexLayout parsed = VertexLayout.parseCanonicalText(text);
        assertTrue(parsed.hasDiagnostic("MALFORMED_ATTRIBUTE_LINE"), parsed.diagnostics().toString());
    }

    @Test
    void nullTextIsRejected() {
        assertThrows(NullPointerException.class, () -> VertexLayout.parseCanonicalText(null));
    }

    @Test
    void findAttributeOnMissingNameReturnsEmpty() {
        Optional<VertexLayout.AttributeOffset> missing = VertexLayout.of(SPEC_TABLE_ORDER).findAttribute("Nope");
        assertTrue(missing.isEmpty());
    }

    @Test
    void diagnosticsAreImmutableSnapshot() {
        VertexLayout layout = VertexLayout.ofDeclarations(List.of(
                AttributeDecl.of(VertexAttribute.Position),
                new AttributeDecl("Bogus", "vec5f")));
        List<ModelDiagnostic> snapshot = new ArrayList<>(layout.diagnostics());
        assertEquals(1, snapshot.size(), "未知类型恰好一条 ERROR 诊断");
        assertEquals("UNKNOWN_ATTRIBUTE_TYPE", snapshot.get(0).code());
        assertEquals(ModelDiagnostic.Severity.ERROR, snapshot.get(0).severity());
        assertTrue(snapshot.get(0).format().startsWith("[ERROR] UNKNOWN_ATTRIBUTE_TYPE:"));
    }
}
