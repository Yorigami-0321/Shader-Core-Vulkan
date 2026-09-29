package dev.vkdisp.glsl.preprocess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.vkdisp.glsl.SourceLineMap;
import dev.vkdisp.glsl.TranslateDiagnostic;

/**
 * 【参考调研】C 线单测 — ConstEvaluator
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = OptiFine / Iris 官方文档"选项如何定义"（handover §6.3：
 *    const int X = N; // [候选...] 与 #define OPTION v // 描述 [候选...]）—— 格式事实，
 *    不受版权保护。外部候选 IrisShaders/glsl-preprocessor（GPL-3.0 + 例外条款）→ 一律按禁止处理
 *    （handover §2.1 / 07-CONSTRAINTS X20/X21），本任务不读其代码、零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 测试，样本全部自造。
 *    → 能否并入本项目（MIT）：可以（仅采纳不受版权保护的事实性选项语法）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：handover §6.3 选项定义语法 + §5.3④（白名单 const 才可见、名单外默认不可见）
 *    + F3 的 SourceLineMap 契约（选项指回原文件行）。
 * 2. 备选：无 —— 断言识别出的 OptionConstant 字段。
 * 3. 我们的差异点：边界用例（白名单 / 非白名单不可见 / #define 无候选不识别 / 同名默认值冲突禁用
 *    + WARN）全部显式断言。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：测试代码不进运行时（18-PARALLEL §7.7）。
 */
class ConstEvaluatorTest {

    private static SourceLineMap mapFor(int lines) {
        return SourceLineMap.identity("x.fsh", lines);
    }

    @Test
    void whitelistedConstRecognizedWithCandidates() {
        String src = "const int shadowMapResolution = 2048; // [512 1024 2048]\n";
        ConstEvaluator.Result r = ConstEvaluator.evaluate(src, mapFor(1));

        assertEquals(1, r.options().size());
        ConstEvaluator.OptionConstant oc = r.options().get(0);
        assertEquals("shadowMapResolution", oc.name());
        assertEquals("const-int", oc.kind());
        assertEquals("2048", oc.defaultValue());
        assertEquals(List.of("512", "1024", "2048"), oc.candidates());
        assertTrue(oc.visible());
        assertFalse(oc.disabled());
        assertEquals("x.fsh", oc.sourceFile());
        assertEquals(1, oc.sourceLine());
    }

    @Test
    void nonWhitelistedConstIsInvisible() {
        String src = "const int myCustom = 3; // [1 2 3]\n";
        ConstEvaluator.Result r = ConstEvaluator.evaluate(src, mapFor(1));
        assertTrue(r.options().isEmpty(), "名单外 const 默认不可见");
    }

    @Test
    void defineOptionWithCandidatesRecognized() {
        String src = "#define SHADOW_DARKNESS 0.10 // 阴影浓度 [0.05 0.10]\n";
        ConstEvaluator.Result r = ConstEvaluator.evaluate(src, mapFor(1));

        assertEquals(1, r.options().size());
        ConstEvaluator.OptionConstant oc = r.options().get(0);
        assertEquals("SHADOW_DARKNESS", oc.name());
        assertEquals("define-value", oc.kind());
        assertEquals("0.10", oc.defaultValue());
        assertEquals(List.of("0.05", "0.10"), oc.candidates());
        assertEquals("阴影浓度", oc.description());
        assertTrue(oc.visible());
    }

    @Test
    void defineWithoutCandidatesIsNotAnOption() {
        String src = "#define PLAIN 1\n";
        ConstEvaluator.Result r = ConstEvaluator.evaluate(src, mapFor(1));
        assertTrue(r.options().isEmpty(), "无候选值列表的 #define 不视为选项");
    }

    @Test
    void conflictingDefaultDisablesAndWarns() {
        String src = "const int shadowMapResolution = 2048; // [512 1024 2048]\n"
                + "const int shadowMapResolution = 1024; // [512 1024 2048]\n";
        ConstEvaluator.Result r = ConstEvaluator.evaluate(src, mapFor(2));

        assertEquals(2, r.options().size());
        assertTrue(r.options().stream().allMatch(ConstEvaluator.OptionConstant::disabled),
                "默认值不一致应全部禁用");
        boolean warned = r.diagnostics().stream()
                .anyMatch(d -> d.severity() == TranslateDiagnostic.Severity.WARN
                        && d.message().contains("已禁用"));
        assertTrue(warned, "冲突应显式 WARN（T11）");
    }

    @Test
    void floatConstRecognized() {
        String src = "const float wetnessHalflife = 2.0; // [1.0 2.0 4.0]\n";
        ConstEvaluator.Result r = ConstEvaluator.evaluate(src, mapFor(1));
        assertEquals(1, r.options().size());
        assertEquals("const-float", r.options().get(0).kind());
        assertEquals(List.of("1.0", "2.0", "4.0"), r.options().get(0).candidates());
    }
}
