package dev.vkdisp.glsl.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.vkdisp.glsl.TranslateDiagnostic;

/**
 * 【参考调研】D 线二期单测（片元输出适配）/ GLSL 1.20 → 330 core 内建输出差异 + 04-SPEC §3.3
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/04-SPEC.md §3.3 / §3.4（colortex 0..15 的输出槽位规划）、
 *    docs/18-PARALLEL.md §4 D 线完成标准与 §7.3 证据规范 —— 仓库内文档事实；
 *    另加 GLSL 官方公开语义（gl_FragColor / gl_FragData 的片元内建定义、core profile 的
 *    layout(location = N) out 要求）。外部候选 IrisShaders/glsl-transformer（GPL-3.0 + 例外条款）→
 *    按禁止处理（07-CONSTRAINTS L12 §1.3 陷阱 2 / X21），本任务不读其代码、零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 测试；样本全部为本任务自造
 *    （18-PARALLEL §7.6：禁止把第三方 pack 的 .glsl 片段复制进单测预期值）。
 *    → 能否并入本项目（MIT）：可以（仅采纳不受版权保护的事实性信息）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：JUnit 5 字符串逐字比对（§7.3 要求"输入 OF 方言样本 → 输出与预期字符串比对"）：
 *    gl_FragColor / gl_FragData[n] 的槽位复用与合成、插入点、诊断行号。
 * 2. 备选：无 —— 文本级转译用字符串断言最直观，不引入 golden-file 框架。
 * 3. 我们的差异点：把"边界"逐条写成断言 —— 注释 / 字符串内不改写、已声明 out 不重复声明、
 *    多个未标 location 的 out 时拒绝猜测（WARN + 合成）、非整数字面量下标 ERROR、
 *    越界下标 ERROR、非片元阶段 ERROR、CRLF 保留、幂等、恶意输入不抛异常。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：测试代码不进运行时；冷路径无性能要求（18-PARALLEL §7.7）。
 */
/**
 * {@link FragmentOutputAdapter} 的单测：内建输出 → 显式 out 声明的适配、槽位解析、
 * 插入点与诊断行号，以及全部边界输入必须"诊断而非崩溃"。
 */
class FragmentOutputAdapterTest {

    @Test
    void glFragColorGetsSyntheticDeclarationAndRewrite() {
        String source = "#version 120\nvoid main() {\n    gl_FragColor = vec4(1.0);\n}\n";
        FragmentOutputAdapter.Result result = FragmentOutputAdapter.adapt(ShaderStage.FRAGMENT, source);
        assertEquals("""
                #version 120
                layout(location = 0) out vec4 vkdispFragOut0;
                void main() {
                    vkdispFragOut0 = vec4(1.0);
                }
                """, result.text());
        assertEquals(1, result.rewrittenCount());
        assertEquals(1, result.insertIndex());
        assertEquals(1, result.insertedLineCount());
        assertEquals(List.of("layout(location = 0) out vec4 vkdispFragOut0;"),
                result.insertedDeclarations());
        assertEquals(1, result.diagnostics().size());
        TranslateDiagnostic info = result.diagnostics().get(0);
        assertEquals(TranslateDiagnostic.Severity.INFO, info.severity());
        assertEquals(3, info.line(), "诊断指向使用内建的那一行");
        assertTrue(info.message().contains("合成"), info.format());
    }

    @Test
    void singleUndeclaredOutIsReusedWithoutDuplicateDeclaration() {
        String source = "out vec4 fragColor;\nvoid main() {\n    gl_FragColor = vec4(1.0);\n}\n";
        FragmentOutputAdapter.Result result = FragmentOutputAdapter.adapt(ShaderStage.FRAGMENT, source);
        assertEquals("""
                out vec4 fragColor;
                void main() {
                    fragColor = vec4(1.0);
                }
                """, result.text(), "包内已有唯一 out vec4 → 复用它，不重复声明");
        assertEquals(0, result.insertedLineCount());
        assertTrue(result.diagnostics().isEmpty(), "正常复用不该产生诊断：" + result.diagnostics());
    }

    @Test
    void explicitLocationOutsAreReusedPerSlot() {
        String source = """
                layout(location = 0) out vec4 albedo;
                layout(location = 3) out vec4 gbuf3;
                void main() {
                    gl_FragData[0] = vec4(1.0);
                    gl_FragData[3] = vec4(0.5);
                }
                """;
        FragmentOutputAdapter.Result result = FragmentOutputAdapter.adapt(ShaderStage.FRAGMENT, source);
        assertTrue(result.text().contains("albedo = vec4(1.0);"));
        assertTrue(result.text().contains("gbuf3 = vec4(0.5);"));
        assertFalse(result.text().contains("gl_FragData"));
        assertEquals(2, result.rewrittenCount());
        assertEquals(0, result.insertedLineCount(), "槽位都已在包内声明，不插入任何合成声明");
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void missingSlotIsSynthesisedWithAscendingLocations() {
        String source = """
                /* DRAWBUFFERS:02 */
                layout(location = 0) out vec4 albedo;
                void main() {
                    gl_FragData[2] = vec4(0.25);
                    gl_FragColor = vec4(1.0);
                }
                """;
        FragmentOutputAdapter.Result result = FragmentOutputAdapter.adapt(ShaderStage.FRAGMENT, source);
        assertTrue(result.text().contains("albedo = vec4(1.0);"));
        assertTrue(result.text().contains("vkdispFragOut2 = vec4(0.25);"));
        assertEquals(List.of("layout(location = 2) out vec4 vkdispFragOut2;"),
                result.insertedDeclarations(), "只合成缺失的槽位，且按 location 升序");
        assertEquals(1, result.diagnostics().size(), result.diagnostics().toString());
        assertEquals(TranslateDiagnostic.Severity.INFO, result.diagnostics().get(0).severity());
        assertEquals(4, result.diagnostics().get(0).line(), "只对缺失的 location 2 出 INFO");
    }

    @Test
    void multipleUnlocatedOutsRefuseToGuessAndFail() {
        String source = "out vec4 a;\nout vec4 b;\nvoid main() {\n    gl_FragColor = vec4(1.0);\n}\n";
        FragmentOutputAdapter.Result result = FragmentOutputAdapter.adapt(ShaderStage.FRAGMENT, source);
        assertEquals(source, result.text(), "无法确定隐式槽位归属时不改写、不合成（绝不重复声明）");
        assertEquals(0, result.insertedLineCount());
        assertEquals(1, result.diagnostics().size());
        TranslateDiagnostic error = result.diagnostics().get(0);
        assertEquals(TranslateDiagnostic.Severity.ERROR, error.severity());
        assertTrue(error.message().contains("拒绝猜测"), error.format());
        assertEquals(4, error.line());
    }

    @Test
    void singleUnlocatedOutCannotCoverNonZeroSlot() {
        String source = "out vec4 color;\nvoid main() {\n    gl_FragData[1] = vec4(1.0);\n}\n";
        FragmentOutputAdapter.Result result = FragmentOutputAdapter.adapt(ShaderStage.FRAGMENT, source);
        assertEquals(source, result.text(), "唯一未标 location 的 out 只能确定覆盖槽位 0");
        assertEquals(TranslateDiagnostic.Severity.ERROR, result.diagnostics().get(0).severity());
        assertTrue(result.diagnostics().get(0).message().contains("layout(location = 1)"),
                result.diagnostics().get(0).format());
    }

    @Test
    void fragColorAndFragDataZeroShareOneSyntheticDeclaration() {
        String source = "void main() {\n    gl_FragColor = vec4(1.0);\n    gl_FragData[0].r = 0.5;\n}\n";
        FragmentOutputAdapter.Result result = FragmentOutputAdapter.adapt(ShaderStage.FRAGMENT, source);
        assertEquals("""
                layout(location = 0) out vec4 vkdispFragOut0;
                void main() {
                    vkdispFragOut0 = vec4(1.0);
                    vkdispFragOut0.r = 0.5;
                }
                """, result.text());
        assertEquals(2, result.rewrittenCount());
        assertEquals(List.of("layout(location = 0) out vec4 vkdispFragOut0;"),
                result.insertedDeclarations(), "同一槽位只能有一份声明（否则 location 0 冲突）");
        assertEquals(1, result.diagnostics().size(), "同一槽位的 INFO 只报一次");
    }

    @Test
    void nonIntegerFragDataIndexIsErrorAndTextUntouched() {
        String source = "void main() {\n    gl_FragData[i] = vec4(1.0);\n}\n";
        FragmentOutputAdapter.Result result = FragmentOutputAdapter.adapt(ShaderStage.FRAGMENT, source);
        assertEquals(source, result.text(), "无法确定槽位时拒绝改写");
        assertEquals(1, result.diagnostics().size());
        assertEquals(TranslateDiagnostic.Severity.ERROR, result.diagnostics().get(0).severity());
        assertEquals(2, result.diagnostics().get(0).line());
        assertTrue(result.diagnostics().get(0).message().contains("整数字面量"));
    }

    @Test
    void outOfRangeFragDataIndexIsError() {
        String source = "void main() {\n    gl_FragData[12] = vec4(1.0);\n}\n";
        FragmentOutputAdapter.Result result = FragmentOutputAdapter.adapt(ShaderStage.FRAGMENT, source);
        assertEquals(source, result.text());
        TranslateDiagnostic error = result.diagnostics().get(0);
        assertEquals(TranslateDiagnostic.Severity.ERROR, error.severity());
        assertTrue(error.message().contains("越界"), error.format());
        assertTrue(error.message().contains("0..7"), error.format());
    }

    @Test
    void fragDataWithoutBracketIsError() {
        FragmentOutputAdapter.Result result = FragmentOutputAdapter.adapt(
                ShaderStage.FRAGMENT, "void main() {\n    gl_FragData = vec4(1.0);\n}\n");
        assertEquals(TranslateDiagnostic.Severity.ERROR, result.diagnostics().get(0).severity());
        assertTrue(result.diagnostics().get(0).message().contains("下标"));
    }

    @Test
    void nonFragmentStageIsErrorAndTextUntouched() {
        String source = "void main() {\n    gl_FragColor = vec4(1.0);\n}\n";
        FragmentOutputAdapter.Result vertex = FragmentOutputAdapter.adapt(ShaderStage.VERTEX, source);
        assertEquals(source, vertex.text());
        assertEquals(TranslateDiagnostic.Severity.ERROR, vertex.diagnostics().get(0).severity());
        assertTrue(vertex.diagnostics().get(0).message().contains("顶点阶段"));

        FragmentOutputAdapter.Result unknown = FragmentOutputAdapter.adapt(ShaderStage.UNKNOWN, source);
        assertEquals(source, unknown.text(), "阶段未知时拒绝改写");
        assertEquals(TranslateDiagnostic.Severity.ERROR, unknown.diagnostics().get(0).severity());

        FragmentOutputAdapter.Result nullStage = FragmentOutputAdapter.adapt(null, source);
        assertEquals(TranslateDiagnostic.Severity.ERROR, nullStage.diagnostics().get(0).severity());
    }

    @Test
    void commentsAndStringsAreNeverRewritten() {
        String source = """
                // gl_FragColor = vec4(0.0);
                /* gl_FragData[0] = vec4(0.0); */
                void main() {
                    "gl_FragColor";
                    gl_FragColor = vec4(1.0); // gl_FragData[5]
                }
                """;
        FragmentOutputAdapter.Result result = FragmentOutputAdapter.adapt(ShaderStage.FRAGMENT, source);
        List<String> lines = List.of(result.text().split("\n", -1));
        assertEquals("// gl_FragColor = vec4(0.0);", lines.get(0));
        assertEquals("/* gl_FragData[0] = vec4(0.0); */", lines.get(1));
        assertTrue(result.text().contains("\"gl_FragColor\";"), "字符串内一字不动");
        assertTrue(result.text().contains("vkdispFragOut0 = vec4(1.0); // gl_FragData[5]"),
                "行尾注释一字不动");
        assertEquals(1, result.rewrittenCount());
        assertFalse(result.text().contains("gl_FragData[5] ="), "注释里的下标不参与槽位解析");
    }

    @Test
    void nonVec4OutAtSameLocationIsErrorAndTextUntouched() {
        String source = "layout(location = 0) out vec3 rgb;\nvoid main() {\n    gl_FragColor = vec4(1.0);\n}\n";
        FragmentOutputAdapter.Result result = FragmentOutputAdapter.adapt(ShaderStage.FRAGMENT, source);
        assertEquals(source, result.text(), "槽位被占用时拒绝改写（显式失败）");
        assertEquals(TranslateDiagnostic.Severity.ERROR, result.diagnostics().get(0).severity());
        assertTrue(result.diagnostics().get(0).message().contains("占用"));
    }

    @Test
    void crlfLineEndingsArePreservedOnInsert() {
        FragmentOutputAdapter.Result result = FragmentOutputAdapter.adapt(
                ShaderStage.FRAGMENT, "void main() {\r\n    gl_FragColor = vec4(1.0);\r\n}\r\n");
        assertEquals("layout(location = 0) out vec4 vkdispFragOut0;\r\n"
                + "void main() {\r\n"
                + "    vkdispFragOut0 = vec4(1.0);\r\n"
                + "}\r\n", result.text());
    }

    @Test
    void adaptationIsIdempotentOnItsOwnOutput() {
        String source = "#version 120\nvoid main() {\n    gl_FragColor = vec4(1.0);\n}\n";
        FragmentOutputAdapter.Result first = FragmentOutputAdapter.adapt(ShaderStage.FRAGMENT, source);
        FragmentOutputAdapter.Result second = FragmentOutputAdapter.adapt(ShaderStage.FRAGMENT, first.text());
        assertEquals(first.text(), second.text(), "输出即不动点");
        assertEquals(0, second.rewrittenCount());
        assertEquals(0, second.insertedLineCount(), "已声明的 out 不重复声明");
        assertTrue(second.diagnostics().isEmpty(), "第二遍没有任何内建输出可改，不该再有诊断");
    }

    @Test
    void emptyAndNullInputAreUntouched() {
        FragmentOutputAdapter.Result empty = FragmentOutputAdapter.adapt(ShaderStage.FRAGMENT, "");
        assertEquals("", empty.text());
        assertEquals(0, empty.rewrittenCount());
        assertTrue(empty.diagnostics().isEmpty());

        FragmentOutputAdapter.Result nullInput = FragmentOutputAdapter.adapt(ShaderStage.FRAGMENT, null);
        assertEquals("", nullInput.text());
        assertTrue(nullInput.diagnostics().isEmpty());
    }

    @Test
    void hostileInputsNeverThrow() {
        List<String> hostile = List.of("", "\n", "/*", "*/", "gl_FragColor", "gl_FragData", "gl_FragData[",
                "gl_FragData[]", "gl_FragData[999999999999]", "gl_FragColor;", "}", "#", "#version",
                "layout(location = 0) out vec4", "layout(location = ) out vec4 x;", "out", "out vec4",
                "gl_FragData[0", "\\u0000\\u0001");
        for (String source : hostile) {
            FragmentOutputAdapter.Result result = FragmentOutputAdapter.adapt(ShaderStage.FRAGMENT, source);
            assertNotNull(result, "任何输入都必须返回结果而不是抛异常：" + source);
        }
    }

    @Test
    void lineNumbersInDiagnosticsPointToInputLines() {
        String source = "void main() {\n\n    gl_FragData[i] = vec4(1.0);\n}\n";
        FragmentOutputAdapter.Result result = FragmentOutputAdapter.adapt(ShaderStage.FRAGMENT, source);
        assertEquals(3, result.diagnostics().get(0).line(), "诊断行号是本阶段输入行号（插入之前）");
    }
}
