package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】E 线单测 / BindGroupLayout 声明中间表示（任务 ③：纯数据 + 序列化打印比对）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① docs/04-SPEC.md §3.3（BindGroupLayout.builder().withSampler("colortex0")/
 *    .withUniform("OfSceneParams", UNIFORM_BUFFER) 的官方用例形状）；② docs/18-PARALLEL.md §4 E 线
 *    「中间表示能被序列化后打印比对」。
 *    许可证：JUnit 5 = EPL-2.0（仅 testImplementation 依赖，不进分发 jar）；本仓库 docs 为 MIT 项目自有文档。
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触 —— 按 docs/07-CONSTRAINTS.md L12
 *    「判不过就换参考，不再读它的代码」。
 *    → 能否并入本项目（MIT）：可以（本文件为独立编写的测试）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：JUnit 5 标准断言（assertEquals / assertThrows / assertTrue），用例取值照抄本仓库
 *    docs/04-SPEC.md §3.3（绑定声明顺序 / 例子里的 colortex0、depthtex0、OfSceneParams）与 §4 属性表。
 * 2. 备选：手写 main() 断言 —— 否决（F4 已接好 JUnit 5，失败要能在 ./gradlew test 里显式红）。
 * 3. 我们的差异点：断言「声明顺序即索引」、规范文本逐字符一致、往返解析后值相等，以及篡改（未知 kind / 索引错位 /
 *    行格式坏 / 头坏）必须显式报错而非静默接受（T11）。
 * 4. 许可证核对：本项目 MIT；JUnit EPL-2.0 仅测试期依赖；零第三方代码复制。
 * 5. 性能基线：测试代码不进运行时，无性能影响（18-PARALLEL §7.7）。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** BindGroupLayout 中间表示：索引语义、规范文本往返、边界与篡改检测。 */
class BindGroupLayoutIrTest {

    private static BindGroupLayoutIr sampleLayout() {
        return BindGroupLayoutIr.builder()
                .sampler("colortex0")
                .sampler("depthtex0")
                .uniformBuffer("OfSceneParams")
                .build();
    }

    @Test
    void bindingIndexFollowsDeclarationOrder() {
        BindGroupLayoutIr layout = sampleLayout();
        assertEquals(3, layout.size());
        assertEquals(0, layout.bindingIndexOf("colortex0"));
        assertEquals(1, layout.bindingIndexOf("depthtex0"));
        assertEquals(2, layout.bindingIndexOf("OfSceneParams"));
        assertEquals(BindGroupLayoutIr.BindingKind.SAMPLER, layout.bindings().get(0).kind());
        assertEquals(BindGroupLayoutIr.BindingKind.UNIFORM_BUFFER, layout.bindings().get(2).kind());
        assertFalse(layout.hasErrors(), layout.diagnostics().toString());
        assertTrue(layout.diagnostics().isEmpty(), "合法输入不产生诊断");
    }

    @Test
    void canonicalTextIsExactAndStable() {
        String expected = "bind-group-layout v1\n"
                + "0 SAMPLER colortex0\n"
                + "1 SAMPLER depthtex0\n"
                + "2 UNIFORM_BUFFER OfSceneParams\n";
        assertEquals(expected, sampleLayout().canonicalText(), "打印比对：规范文本逐字符一致");
        assertEquals(expected, sampleLayout().canonicalText(), "同一输入两次生成必须完全一致");
    }

    @Test
    void canonicalTextRoundTrips() {
        BindGroupLayoutIr layout = sampleLayout();
        BindGroupLayoutIr parsed = BindGroupLayoutIr.parseCanonicalText(layout.canonicalText());
        assertFalse(parsed.hasErrors(), "往返解析不应有问题：" + parsed.diagnostics());
        assertEquals(layout.bindings(), parsed.bindings());
        assertEquals(layout.canonicalText(), parsed.canonicalText());
    }

    @Test
    void emptyLayoutIsValidForFullscreenPipeline() {
        BindGroupLayoutIr layout = BindGroupLayoutIr.builder().build();
        assertEquals(0, layout.size());
        assertEquals("bind-group-layout v1\n", layout.canonicalText());
        assertTrue(layout.diagnostics().isEmpty(), "无绑定不是降级，不需要诊断");
        assertEquals(0, BindGroupLayoutIr.parseCanonicalText(layout.canonicalText()).size());
    }

    @Test
    void duplicateNameIsWarnAndFirstWins() {
        BindGroupLayoutIr layout = BindGroupLayoutIr.builder().sampler("colortex0").sampler("colortex0").build();
        assertEquals(1, layout.size());
        assertTrue(layout.hasDiagnostic("DUPLICATE_BINDING_NAME"), layout.diagnostics().toString());
        assertFalse(layout.hasErrors(), "重名是 WARN 级（确定性降级）");
    }

    @Test
    void blankNameIsErrorAndBindingIsSkipped() {
        BindGroupLayoutIr layout = BindGroupLayoutIr.builder().sampler("   ").sampler("depthtex0").build();
        assertEquals(1, layout.size());
        assertTrue(layout.hasDiagnostic("BAD_BINDING_NAME"), layout.diagnostics().toString());
        assertTrue(layout.hasErrors());
    }

    @Test
    void nonIdentifierNameIsError() {
        BindGroupLayoutIr layout = BindGroupLayoutIr.builder().sampler("colortex 0").build();
        assertEquals(0, layout.size());
        assertTrue(layout.hasDiagnostic("BAD_BINDING_NAME"), layout.diagnostics().toString());
    }

    @Test
    void nullBindingEntryIsErrorNotCrash() {
        List<BindGroupLayoutIr.Binding> bindings = new ArrayList<>();
        bindings.add(new BindGroupLayoutIr.Binding(BindGroupLayoutIr.BindingKind.SAMPLER, "colortex0"));
        bindings.add(null);
        BindGroupLayoutIr layout = BindGroupLayoutIr.ofBindings(bindings);
        assertEquals(1, layout.size());
        assertTrue(layout.hasDiagnostic("NULL_BINDING"), layout.diagnostics().toString());
    }

    @Test
    void unknownKindIsDetectedOnParse() {
        String text = "bind-group-layout v1\n0 PIZZA colortex0\n";
        BindGroupLayoutIr parsed = BindGroupLayoutIr.parseCanonicalText(text);
        assertTrue(parsed.hasDiagnostic("UNKNOWN_BINDING_KIND"), parsed.diagnostics().toString());
        assertEquals(0, parsed.size());
    }

    @Test
    void indexMismatchIsDetectedOnParse() {
        String text = "bind-group-layout v1\n5 SAMPLER colortex0\n";
        BindGroupLayoutIr parsed = BindGroupLayoutIr.parseCanonicalText(text);
        assertTrue(parsed.hasDiagnostic("BINDING_INDEX_MISMATCH"), parsed.diagnostics().toString());
        assertTrue(parsed.hasErrors());
    }

    @Test
    void malformedLineIsDetectedOnParse() {
        String text = "bind-group-layout v1\n0 SAMPLER\n";
        BindGroupLayoutIr parsed = BindGroupLayoutIr.parseCanonicalText(text);
        assertTrue(parsed.hasDiagnostic("MALFORMED_BINDING_LINE"), parsed.diagnostics().toString());
    }

    @Test
    void badHeaderIsDetectedOnParse() {
        BindGroupLayoutIr parsed = BindGroupLayoutIr.parseCanonicalText("");
        assertTrue(parsed.hasDiagnostic("BAD_HEADER"), parsed.diagnostics().toString());
        assertEquals(0, parsed.size());
    }

    @Test
    void blankTextIsBadHeaderAndWrongVersionIsRejected() {
        assertTrue(BindGroupLayoutIr.parseCanonicalText("bind-group-layout v2\n").hasDiagnostic("BAD_HEADER"),
                "版本头不同必须显式拒绝，不许按当前版本硬解析");
    }

    @Test
    void bindingsAreUnmodifiable() {
        BindGroupLayoutIr layout = sampleLayout();
        assertThrows(UnsupportedOperationException.class,
                () -> layout.bindings().add(new BindGroupLayoutIr.Binding(
                        BindGroupLayoutIr.BindingKind.SAMPLER, "colortex1")));
        assertThrows(UnsupportedOperationException.class, () -> layout.diagnostics().clear());
    }

    @Test
    void bindingIndexOfMissingNameReturnsMinusOne() {
        assertEquals(-1, sampleLayout().bindingIndexOf("nope"));
    }

    @Test
    void nullInputsAreRejected() {
        assertThrows(NullPointerException.class, () -> BindGroupLayoutIr.parseCanonicalText(null));
        assertThrows(NullPointerException.class, () -> BindGroupLayoutIr.ofBindings(null));
        assertThrows(NullPointerException.class, () -> BindGroupLayoutIr.builder().sampler(null));
    }
}
