package dev.vkdisp.glsl.preprocess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.vkdisp.glsl.SourceLineMap;
import dev.vkdisp.glsl.TranslateDiagnostic;

/**
 * 【参考调研】C 线单测 — IncludeProcessor
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = OptiFine / Iris 官方文档 #include 语义（handover §6.5：绝对 / 相对、最大嵌套 10、
 *    可嵌套、循环包含必须报错）—— 格式事实，不受版权保护。外部候选 IrisShaders/glsl-preprocessor
 *    （GPL-3.0 + 例外条款）→ 一律按禁止处理（handover §2.1 / 07-CONSTRAINTS X20/X21），
 *    本任务不读其代码、零代码行并入。许可证：本文件为独立编写的纯 Java 测试，样本全部自造
 *    （18-PARALLEL §7.6：禁止复制第三方 pack 的 .glsl 片段）。
 *    → 能否并入本项目（MIT）：可以（仅采纳不受版权保护的事实性语义）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：handover §6.5 的 #include 语义 + F3 的 SourceLineMap 契约（行号指回原文件）。
 * 2. 备选：无 —— 文本级断言最直观。
 * 3. 我们的差异点：循环检测用"include 栈是否含归一路径"，边界用例（循环 / 超深 / 缺文件 /
 *    相对基于当前文件目录）全部显式断言诊断与行号映射。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：测试代码不进运行时；冷路径无性能要求（18-PARALLEL §7.7）。
 */
class IncludeProcessorTest {

    private static IncludeResolver map(Map<String, String> m) {
        return IncludeResolver.of(new LinkedHashMap<>(m));
    }

    @Test
    void absoluteAndRelativeIncludeExpand() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("lib/common.glsl", "// common\nextern float c();\n");
        files.put("lib/local.glsl", "// local\nextern float l();\n");
        String src = "#version 150\n#include \"/lib/common.glsl\"\n#include \"lib/local.glsl\"\nvoid main(){}\n";

        IncludeProcessor.Result r =
                IncludeProcessor.process("composite.fsh", src, map(files));

        assertTrue(r.success(), "正常 include 应成功");
        assertTrue(r.text().contains("extern float c();"), "绝对 include 应展开");
        assertTrue(r.text().contains("extern float l();"), "相对 include 应展开");
        assertFalse(r.text().contains("#include"), "include 指令应被移除");
    }

    @Test
    void relativeIncludeBasedOnCurrentFileDir() {
        // a.glsl 在 sub/ 下，相对 include "b.glsl" 应解析为 sub/b.glsl 而非根 b.glsl
        Map<String, String> files = new LinkedHashMap<>();
        files.put("sub/a.glsl", "#include \"b.glsl\"\n");
        files.put("sub/b.glsl", "int fromSubB;\n");
        files.put("b.glsl", "int fromRootB;\n");

        IncludeProcessor.Result r =
                IncludeProcessor.process("sub/a.glsl", "#include \"b.glsl\"\n", map(files));

        assertTrue(r.success());
        assertTrue(r.text().contains("fromSubB"), "相对 include 应基于当前文件目录解析");
        assertFalse(r.text().contains("fromRootB"), "不应误解析到根目录同名文件");
    }

    @Test
    void nestedIncludeChainWorks() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("a.glsl", "#include \"b.glsl\"\n");
        files.put("b.glsl", "#include \"c.glsl\"\n");
        files.put("c.glsl", "int deep;\n");

        IncludeProcessor.Result r =
                IncludeProcessor.process("a.glsl", "#include \"b.glsl\"\n", map(files));

        assertTrue(r.success(), "嵌套 include 应成功");
        assertTrue(r.text().contains("int deep;"), "三层嵌套应包含最深层内容");
    }

    @Test
    void cycleIncludeReportsError() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("a.glsl", "#include \"b.glsl\"\n");
        files.put("b.glsl", "#include \"a.glsl\"\n");

        IncludeProcessor.Result r =
                IncludeProcessor.process("a.glsl", "#include \"b.glsl\"\n", map(files));

        assertFalse(r.success(), "循环包含必须失败");
        boolean hasError = r.diagnostics().stream()
                .anyMatch(d -> d.severity() == TranslateDiagnostic.Severity.ERROR
                        && d.message().contains("循环包含"));
        assertTrue(hasError, "必须报循环包含 ERROR");
    }

    @Test
    void depthBeyondLimitReportsError() {
        Map<String, String> files = new LinkedHashMap<>();
        for (int i = 0; i <= 11; i++) {
            files.put("d" + i + ".glsl", i == 11 ? "" : "#include \"d" + (i + 1) + ".glsl\"\n");
        }

        IncludeProcessor.Result r =
                IncludeProcessor.process("d0.glsl", "#include \"d1.glsl\"\n", map(files));

        assertFalse(r.success(), "超过嵌套深度上限必须失败");
        boolean hasError = r.diagnostics().stream()
                .anyMatch(d -> d.severity() == TranslateDiagnostic.Severity.ERROR
                        && d.message().contains("深度超过上限"));
        assertTrue(hasError, "必须报深度超限 ERROR");
    }

    @Test
    void missingIncludeReportsError() {
        IncludeProcessor.Result r =
                IncludeProcessor.process("x.glsl", "#include \"/nope.glsl\"\n", map(Map.of()));

        assertFalse(r.success(), "缺失的包含文件必须失败");
        boolean hasError = r.diagnostics().stream()
                .anyMatch(d -> d.severity() == TranslateDiagnostic.Severity.ERROR
                        && d.message().contains("包含文件不存在"));
        assertTrue(hasError, "必须报文件不存在 ERROR");
    }

    @Test
    void lineMapPointsBackToIncludedFile() {
        // 主文件 3 行：第 2 行是 include，被替换成 included.glsl 的 2 行
        Map<String, String> files = new LinkedHashMap<>();
        files.put("included.glsl", "lineOne();\nlineTwo();\n");
        String src = "pre();\n#include \"included.glsl\"\npost();\n";

        IncludeProcessor.Result r = IncludeProcessor.process("main.glsl", src, map(files));
        assertTrue(r.success());

        // 展开后输出：1 pre(); 2 lineOne(); 3 lineTwo(); 4 post();
        SourceLineMap map = r.lineMap();
        SourceLineMap.LineOrigin o2 = map.originOf(2);
        assertEquals("included.glsl", o2.sourceFile());
        assertEquals(1, o2.sourceLine());
        SourceLineMap.LineOrigin o3 = map.originOf(3);
        assertEquals("included.glsl", o3.sourceFile());
        assertEquals(2, o3.sourceLine());
        SourceLineMap.LineOrigin o4 = map.originOf(4);
        assertEquals("main.glsl", o4.sourceFile());
        assertEquals(3, o4.sourceLine());
    }

    // ---------------------------------------------------------------- A0 止血（19 §2.6-A0 判据③）

    @Test
    void includeInsideBlockCommentMustWarnButKeepsOldBehaviour() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("lib/hidden.glsl", "int fromHidden;\n");
        String src = "// 主文件\n/*\n#include \"lib/hidden.glsl\"\n*/\nvoid main(){}\n";

        IncludeProcessor.Result r = IncludeProcessor.process("main.fsh", src, map(files));

        assertTrue(r.diagnostics().stream().anyMatch(d ->
                        d.severity() == TranslateDiagnostic.Severity.WARN
                                && d.message().contains("注释内")),
                "注释里的 #include 必须 WARN（旧行为全程静默）：" + r.diagnostics());
        assertTrue(r.text().contains("int fromHidden;"),
                "A0 不改语义：现状仍按真指令展开，改成「不展开」属 19 §2.6-A2");
    }
}
