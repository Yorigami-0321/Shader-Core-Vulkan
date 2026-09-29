package dev.vkdisp.glsl.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.vkdisp.glsl.TranslateDiagnostic;

/**
 * 【参考调研】D 线单测（内建 uniform 注入）/ 04-SPEC §3.2 表
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/04-SPEC.md §3.2 与 docs/18-PARALLEL.md §4 D 线完成标准 ——
 *    仓库内文档事实，不受版权保护。外部候选 IrisShaders/glsl-transformer（GPL-3.0 + 例外条款）→
 *    按禁止处理（07-CONSTRAINTS L12 §1.3 陷阱 2 / X21），本任务不读其代码、零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 测试，样本全部为本任务自造（18-PARALLEL §7.6）。
 *    → 能否并入本项目（MIT）：可以（仅采纳不受版权保护的事实性信息）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：JUnit 5 断言"注入完整（23 条全在）+ 不重复 + 幂等 + 注入点不越过 #version /
 *    #extension"这四件事，对应 04-SPEC §3.2 与 18-PARALLEL §4 D 线完成标准。
 * 2. 备选：无 —— 文本级断言足够，不引入快照框架。
 * 3. 我们的差异点：边界用例（空输入 / 仅注释 / 重复声明 / 类型不符 / 未闭合注释 / CRLF）
 *    全部显式断言"诊断而非崩溃"，对应 18-PARALLEL §7.3 的边界用例清单要求。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：测试代码不进运行时；冷路径无性能要求（18-PARALLEL §7.7）。
 */
/**
 * {@link UniformInjector} 的单测：注入完整性、不重复、注入点位置、幂等与边界输入。
 */
class UniformInjectorTest {

    private static final String MINIMAL = "#version 330 core\nvoid main() {}\n";

    @Test
    void injectsAllTwentyThreeBuiltinsWhenAbsent() {
        UniformInjector.Result result = UniformInjector.inject(MINIMAL);
        assertEquals(23, result.injected().size(), "04-SPEC §3.2 的 23 条一条都不能少");
        assertEquals(24, result.insertedLineCount(), "23 条声明 + 1 行识别注释");
        assertEquals(1, result.insertIndex(), "#version 之后、首条代码之前");
        assertTrue(result.diagnostics().isEmpty());
        for (BuiltinUniform uniform : UniformCatalog.uniforms()) {
            assertEquals(1, count(result.text(), uniform.declaration()),
                    "声明必须出现且仅出现一次：" + uniform.name());
        }
    }

    @Test
    void injectionPointIsAfterPreprocessorHeaderOnly() {
        String source = """
                #version 330 core
                #define FOO 1
                #extension GL_ARB_shading_language_include : enable
                void main() {}
                """;
        UniformInjector.Result result = UniformInjector.inject(source);
        List<String> lines = List.of(result.text().split("\n", -1));
        assertEquals("#version 330 core", lines.get(0), "#version 必须仍在第一行");
        assertEquals("#define FOO 1", lines.get(1));
        assertEquals("#extension GL_ARB_shading_language_include : enable", lines.get(2),
                "#extension 必须仍在任何非预处理记号之前");
        assertEquals(UniformInjector.BLOCK_HEADER, lines.get(3));
        assertEquals("uniform mat4 gbufferModelView;", lines.get(4));
        assertEquals(3, result.insertIndex());
    }

    @Test
    void doesNotDuplicateAlreadyDeclaredBuiltin() {
        String source = "#version 330 core\nuniform mat4 gbufferModelView;\nvoid main() {}\n";
        UniformInjector.Result result = UniformInjector.inject(source);
        assertEquals(22, result.injected().size());
        assertEquals(1, count(result.text(), "uniform mat4 gbufferModelView;"),
                "包内已声明的内建 uniform 不许重复注入");
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void allDeclaredMeansTextIsUntouched() {
        StringBuilder source = new StringBuilder("#version 330 core\n");
        for (BuiltinUniform uniform : UniformCatalog.uniforms()) {
            source.append(uniform.declaration()).append('\n');
        }
        source.append("void main() {}\n");
        UniformInjector.Result result = UniformInjector.inject(source.toString());
        assertEquals(source.toString(), result.text(), "无缺失项时文本逐字节不变");
        assertTrue(result.injected().isEmpty());
        assertEquals(0, result.insertedLineCount());
    }

    @Test
    void typeMismatchWarnsAndKeepsPackDeclaration() {
        String source = "#version 330 core\nuniform vec4 cameraPosition;\nvoid main() {}\n";
        UniformInjector.Result result = UniformInjector.inject(source);
        assertEquals(22, result.injected().size(), "已声明的名字不再注入");
        assertEquals(1, count(result.text(), "uniform vec4 cameraPosition;"), "包内声明不许被改写");
        assertEquals(0, count(result.text(), "uniform vec3 cameraPosition;"));
        assertEquals(1, result.diagnostics().size());
        TranslateDiagnostic diagnostic = result.diagnostics().get(0);
        assertEquals(TranslateDiagnostic.Severity.WARN, diagnostic.severity());
        assertEquals(2, diagnostic.line());
        assertTrue(diagnostic.message().contains("vec4") && diagnostic.message().contains("vec3"));
    }

    @Test
    void duplicateUniformDeclarationWarns() {
        String source = "#version 330 core\nuniform vec4 colortex0;\nuniform vec4 colortex0;\nvoid main() {}\n";
        UniformInjector.Result result = UniformInjector.inject(source);
        assertEquals(23, result.injected().size());
        assertEquals(1, result.diagnostics().size());
        assertEquals(TranslateDiagnostic.Severity.WARN, result.diagnostics().get(0).severity());
        assertEquals(3, result.diagnostics().get(0).line());
    }

    @Test
    void uniformBlockIsIgnoredNotMisparsed() {
        String source = """
                #version 330 core
                uniform OfSceneParams {
                    mat4 gbufferModelView;
                };
                void main() {}
                """;
        UniformInjector.Result result = UniformInjector.inject(source);
        assertEquals(23, result.injected().size(),
                "块内字段不是全局 uniform 声明，gbufferModelView 仍需注入");
        assertEquals(1, count(result.text(), "uniform mat4 gbufferModelView;"));
    }

    @Test
    void injectionIsIdempotent() {
        UniformInjector.Result first = UniformInjector.inject(MINIMAL);
        UniformInjector.Result second = UniformInjector.inject(first.text());
        assertEquals(first.text(), second.text(), "第二遍必须逐字节相同");
        assertTrue(second.injected().isEmpty());
        assertEquals(0, second.insertedLineCount());
        assertTrue(second.diagnostics().isEmpty());
    }

    @Test
    void emptyInputSkipsInjectionWithWarning() {
        UniformInjector.Result result = UniformInjector.inject("");
        assertEquals("", result.text());
        assertTrue(result.injected().isEmpty());
        assertEquals(1, result.diagnostics().size());
        assertEquals(TranslateDiagnostic.Severity.WARN, result.diagnostics().get(0).severity());
        assertTrue(result.diagnostics().get(0).message().contains("不含任何代码行"));
    }

    @Test
    void commentOnlyInputSkipsInjectionWithWarning() {
        String source = "// only a comment\n/* and a block comment */\n";
        UniformInjector.Result result = UniformInjector.inject(source);
        assertEquals(source, result.text());
        assertTrue(result.injected().isEmpty());
        assertTrue(result.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.message().contains("不含任何代码行")));
    }

    @Test
    void unterminatedBlockCommentIsError() {
        String source = "#version 330 core\nvoid main() {}\n/* open\n";
        UniformInjector.Result result = UniformInjector.inject(source);
        assertEquals(TranslateDiagnostic.Severity.ERROR, result.diagnostics().get(0).severity());
        assertTrue(result.diagnostics().get(0).message().contains("未闭合"));
    }

    @Test
    void crlfInputProducesCrlfOutputOnly() {
        String source = "#version 330 core\r\nvoid main() {}\r\n";
        UniformInjector.Result result = UniformInjector.inject(source);
        assertTrue(result.text().contains("\r\n"));
        assertFalse(result.text().replace("\r\n", "").contains("\n"),
                "CRLF 文件里不许混入裸 LF");
        assertTrue(result.text().startsWith("#version 330 core\r\n"), "首行内容不许被改动");
    }

    private static int count(String text, String needle) {
        int total = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            total++;
            index = text.indexOf(needle, index + needle.length());
        }
        return total;
    }
}
