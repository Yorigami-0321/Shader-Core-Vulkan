package dev.vkdisp.glsl.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.vkdisp.glsl.TranslateDiagnostic;

/**
 * 【参考调研】D 线二期单测（旧纹理函数改名）/ GLSL 1.20 → 330 core 公开函数表
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/04-SPEC.md §3.3、docs/18-PARALLEL.md §4 D 线完成标准与 §7.3 证据规范 ——
 *    仓库内文档事实；另加 GLSL 官方公开函数语义（texture / textureProj / textureLod 重载、
 *    shadow2D 的 vec4 → texture(sampler2DShadow) 的 float 返回类型差异）。
 *    外部候选 IrisShaders/glsl-transformer（GPL-3.0 + 例外条款）→ 按禁止处理
 *    （07-CONSTRAINTS L12 §1.3 陷阱 2 / X21），本任务不读其代码、零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 测试；样本全部为本任务自造（18-PARALLEL §7.6）。
 *    → 能否并入本项目（MIT）：可以（仅采纳不受版权保护的事实性信息）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：JUnit 5 字符串逐字比对 —— 旧名全家福逐条断言、shadow 包装的返回类型适配、
 *    嵌套调用、跨行调用的显式降级。
 * 2. 备选：无 —— 文本级转译用字符串断言最直观。
 * 3. 我们的差异点：边界全部写成断言 —— 同名变量 / 成员访问 / 注释 / 字符串 / #define 续行不改写、
 *    跨行调用 WARN、幂等、CRLF 保留、恶意输入不抛异常。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：测试代码不进运行时；冷路径无性能要求（18-PARALLEL §7.7）。
 */
/**
 * {@link TextureFunctionRenamer} 的单测：旧纹理 / 阴影函数 → GLSL 330 core texture 家族的映射、
 * shadow 返回类型适配，以及"只改真正的调用"这条边界。
 */
class TextureFunctionRenamerTest {

    /** 旧名 → 现代名（与实现里的公开表逐条对应；表变了这里必须同步，否则测试红）。 */
    private static final String[][] RENAME_CASES = {
        {"texture1D", "texture"},
        {"texture2D", "texture"},
        {"texture3D", "texture"},
        {"textureCube", "texture"},
        {"texture2DProj", "textureProj"},
        {"texture3DProj", "textureProj"},
        {"texture2DLod", "textureLod"},
        {"texture3DLod", "textureLod"},
        {"textureCubeLod", "textureLod"},
        {"texture2DProjLod", "textureProjLod"},
        {"texture3DProjLod", "textureProjLod"},
    };

    /** shadow 系列：旧名 → 现代名（实现在调用点包 vec4(...)）。 */
    private static final String[][] SHADOW_CASES = {
        {"shadow1D", "texture"},
        {"shadow2D", "texture"},
        {"shadow1DProj", "textureProj"},
        {"shadow2DProj", "textureProj"},
    };

    @Test
    void oldTextureNamesAreRenamedGolden() {
        String source = """
                void main() {
                    vec4 c = texture2D(gtexture, texcoord);
                    vec4 d = textureCube(envMap, dir);
                    vec4 e = texture3D(volume, pos);
                }
                """;
        TextureFunctionRenamer.Result result = TextureFunctionRenamer.rename(source);
        assertEquals("""
                void main() {
                    vec4 c = texture(gtexture, texcoord);
                    vec4 d = texture(envMap, dir);
                    vec4 e = texture(volume, pos);
                }
                """, result.text());
        assertEquals(3, result.renamedCount());
        assertTrue(result.diagnostics().isEmpty(), "普通改名不产生诊断（成功不刷噪声）");
    }

    @Test
    void everyLegacyTextureNameMapsAsDocumented() {
        for (String[] pair : RENAME_CASES) {
            String source = "vec4 c = " + pair[0] + "(a, b);\n";
            TextureFunctionRenamer.Result result = TextureFunctionRenamer.rename(source);
            assertEquals("vec4 c = " + pair[1] + "(a, b);\n", result.text(), pair[0]);
            assertEquals(1, result.renamedCount(), pair[0]);
            assertTrue(result.diagnostics().isEmpty(), pair[0]);
        }
    }

    @Test
    void shadowCallsAreWrappedToPreserveVec4ReturnType() {
        for (String[] pair : SHADOW_CASES) {
            String source = "vec4 c = " + pair[0] + "(a, b);\n";
            TextureFunctionRenamer.Result result = TextureFunctionRenamer.rename(source);
            assertEquals("vec4 c = vec4(" + pair[1] + "(a, b));\n", result.text(), pair[0]);
            assertEquals(1, result.renamedCount(), pair[0]);
            assertEquals(1, result.diagnostics().size(), pair[0]);
            TranslateDiagnostic info = result.diagnostics().get(0);
            assertEquals(TranslateDiagnostic.Severity.INFO, info.severity());
            assertEquals(1, info.line());
            assertTrue(info.message().contains("vec4"), info.format());
        }
    }

    @Test
    void nestedShadowAndTextureCallsAreBothRewritten() {
        String source = "vec4 v = shadow2D(t, vec3(texture2D(u, uv)));\n";
        TextureFunctionRenamer.Result result = TextureFunctionRenamer.rename(source);
        assertEquals("vec4 v = vec4(texture(t, vec3(texture(u, uv))));\n", result.text());
        assertEquals(2, result.renamedCount());
        assertEquals(1, result.diagnostics().size(), "INFO 每个名字只报一次");
    }

    @Test
    void callSplitAcrossLinesWarnsAndOnlyRenames() {
        String source = "vec4 s = shadow2D(\n    shadowtex0, shadowPos);\n";
        TextureFunctionRenamer.Result result = TextureFunctionRenamer.rename(source);
        assertEquals("vec4 s = texture(\n    shadowtex0, shadowPos);\n", result.text(),
                "跨行调用不猜参数边界：只改名，不包 vec4");
        assertEquals(1, result.renamedCount());
        assertEquals(1, result.diagnostics().size());
        TranslateDiagnostic warning = result.diagnostics().get(0);
        assertEquals(TranslateDiagnostic.Severity.WARN, warning.severity());
        assertEquals(1, warning.line());
        assertTrue(warning.message().contains("跨行"), warning.format());
    }

    @Test
    void identifiersThatAreNotCallsAreUntouched() {
        String source = """
                float texture2D = 1.0;
                vec4 c = obj.texture2D(a, b);
                """;
        TextureFunctionRenamer.Result result = TextureFunctionRenamer.rename(source);
        assertEquals(source, result.text(), "同名变量与成员访问都不是调用，不动");
        assertEquals(0, result.renamedCount());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void commentsStringsAndMacroBodiesAreUntouched() {
        String source = """
                #define SAMPLE texture2D
                #define WRAP(a, b) texture2D(a, b) \\
                    + textureCube(a, b)
                // texture2D(commented, out)
                "texture2D(quoted, out)"
                vec4 c = texture2D(t, uv);
                """;
        TextureFunctionRenamer.Result result = TextureFunctionRenamer.rename(source);
        List<String> lines = List.of(result.text().split("\n", -1));
        assertEquals("#define SAMPLE texture2D", lines.get(0));
        assertEquals("#define WRAP(a, b) texture2D(a, b) \\", lines.get(1));
        assertEquals("    + textureCube(a, b)", lines.get(2), "#define 的续行也不改写");
        assertEquals("// texture2D(commented, out)", lines.get(3));
        assertEquals("\"texture2D(quoted, out)\"", lines.get(4), "字符串内一字不动");
        assertEquals("vec4 c = texture(t, uv);", lines.get(5), "只有真正的调用被改名");
        assertEquals(1, result.renamedCount());
    }

    @Test
    void renamingIsIdempotentOnItsOwnOutput() {
        String source = """
                vec4 c = texture2D(a, b);
                vec4 s = shadow2D(c, d).r;
                """;
        TextureFunctionRenamer.Result first = TextureFunctionRenamer.rename(source);
        TextureFunctionRenamer.Result second = TextureFunctionRenamer.rename(first.text());
        assertEquals(first.text(), second.text(), "输出即不动点");
        assertEquals(0, second.renamedCount());
        assertTrue(second.diagnostics().isEmpty(), "第二遍没有旧函数名，不该再有诊断");
        assertTrue(first.text().contains("vec4(texture(c, d)).r"));
    }

    @Test
    void crlfLineEndingsArePreserved() {
        TextureFunctionRenamer.Result result = TextureFunctionRenamer.rename(
                "vec4 c = texture2D(t, uv);\r\n");
        assertEquals("vec4 c = texture(t, uv);\r\n", result.text());
    }

    @Test
    void emptyAndNullInputAreUntouched() {
        TextureFunctionRenamer.Result empty = TextureFunctionRenamer.rename("");
        assertEquals("", empty.text());
        assertEquals(0, empty.renamedCount());
        assertTrue(empty.diagnostics().isEmpty());

        TextureFunctionRenamer.Result nullInput = TextureFunctionRenamer.rename(null);
        assertEquals("", nullInput.text());
        assertTrue(nullInput.diagnostics().isEmpty());
    }

    @Test
    void hostileInputsNeverThrow() {
        List<String> hostile = List.of("", "\n", "texture2D", "texture2D(", "shadow2D(",
                "shadow2D()", "texture2DLod", "texture2DProjLod(", "}", "#", "// texture2D(",
                "\"texture2D\"", "texture2D(", "x.texture2D", "\\u0000");
        for (String source : hostile) {
            TextureFunctionRenamer.Result result = TextureFunctionRenamer.rename(source);
            assertNotNull(result, "任何输入都必须返回结果而不是抛异常：" + source);
        }
    }

    @Test
    void noLegacyNameMeansTextIsUntouched() {
        String source = "void main() {\n    gl_FragColor = vec4(1.0);\n}\n";
        TextureFunctionRenamer.Result result = TextureFunctionRenamer.rename(source);
        assertEquals(source, result.text());
        assertEquals(0, result.renamedCount());
        assertFalse(result.text().contains("texture("), "不该引入任何新调用");
    }
}
