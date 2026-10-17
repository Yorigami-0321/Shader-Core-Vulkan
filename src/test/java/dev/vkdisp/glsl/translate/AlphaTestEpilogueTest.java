package dev.vkdisp.glsl.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vkdisp.pack.model.AlphaTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 【参考调研】alphaTest 注入段的判据
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓 {@code glsl/translate/AlphaTestEpilogue}（独立实现，
 *    规则取自 Vitrail/Iris 的公开记载，「取规则不取代码」见该类文件头）与 {@code pack/model/AlphaTest}
 *    （Vitrail LGPL-3.0 移植件）。本文件只写断言与自造样本，零外部代码。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以（测试代码不进分发 jar）
 * 4. 许可证核对结论：本项目 LGPL-3.0-or-later；零第三方代码复制。
 */
final class AlphaTestEpilogueTest {

    /** 转译⑦ 之后应有的形态：包写 gl_FragColor，⑦ 落成一个 location 0 的 out vec4。 */
    private static final String FRAGMENT = """
            #version 330
            layout(location = 0) out vec4 vkdispFragOut0;
            void main() {
                vec4 c = texture(vkdispSampler0, vec2(0.5));
                vkdispFragOut0 = c;
            }
            """;

    @Test
    @DisplayName("片元写 drawbuffer 0 ⇒ 测试写进 main 收尾之内")
    void injectsInsideMain() {
        AlphaTestEpilogue.Result r =
                AlphaTestEpilogue.apply(ShaderStage.FRAGMENT, FRAGMENT, AlphaTest.CUTOUT);
        assertTrue(r.injected(), r.reason());
        assertTrue(r.text().contains("if (!(vkdispFragOut0.a > 0.5)) { discard; }"), r.text());
        int discard = r.text().indexOf("discard; }");
        int lastBrace = r.text().lastIndexOf('}');
        assertTrue(discard < lastBrace, "必须写在 main 的收尾花括号**之内**，不是文件末尾之后");
    }

    @Test
    @DisplayName("main 后面还有别的函数时，仍写进 main 而不是最后一个 }")
    void injectsIntoMainNotFileEnd() {
        String src = FRAGMENT + "\nvec4 helper() { return vec4(1.0); }\n";
        AlphaTestEpilogue.Result r = AlphaTestEpilogue.apply(ShaderStage.FRAGMENT, src, AlphaTest.CUTOUT);
        assertTrue(r.injected(), r.reason());
        int discard = r.text().indexOf("discard; }");
        int helper = r.text().indexOf("vec4 helper()");
        assertTrue(discard < helper, "注入点必须在 main 内：" + r.text());
    }

    @Test
    @DisplayName("非片元阶段 / null 测试 / ALWAYS 都不动手，且不出诊断")
    void skipsQuietly() {
        AlphaTestEpilogue.Result vertex =
                AlphaTestEpilogue.apply(ShaderStage.VERTEX, FRAGMENT, AlphaTest.CUTOUT);
        assertFalse(vertex.injected());
        assertEquals(FRAGMENT, vertex.text());
        assertTrue(vertex.diagnostics().isEmpty());

        AlphaTestEpilogue.Result none =
                AlphaTestEpilogue.apply(ShaderStage.FRAGMENT, FRAGMENT, null);
        assertFalse(none.injected());
        assertTrue(none.diagnostics().isEmpty(), "没声明 alphaTest 的程序不该有噪音");

        AlphaTestEpilogue.Result off =
                AlphaTestEpilogue.apply(ShaderStage.FRAGMENT, FRAGMENT, AlphaTest.OFF);
        assertFalse(off.injected());
        assertTrue(off.text().equals(FRAGMENT));
    }

    @Test
    @DisplayName("三种「不敢注入」各自出一条 WARN 点名原因（T11 不静默）")
    void refusalsAreNamed() {
        AlphaTestEpilogue.Result noSlot = AlphaTestEpilogue.apply(ShaderStage.FRAGMENT,
                "#version 330\nvoid main() { }\n", AlphaTest.CUTOUT);
        assertFalse(noSlot.injected());
        assertTrue(noSlot.diagnostics().stream().anyMatch(d -> d.message().contains("未注入")
                        && d.message().contains("location = 0")),
                noSlot.diagnostics().toString());

        AlphaTestEpilogue.Result noMain = AlphaTestEpilogue.apply(ShaderStage.FRAGMENT,
                "layout(location = 0) out vec4 o;\nvec4 f() { return o; }\n", AlphaTest.CUTOUT);
        assertFalse(noMain.injected());
        assertTrue(noMain.diagnostics().stream().anyMatch(d -> d.message().contains("void main")),
                noMain.diagnostics().toString());

        AlphaTestEpilogue.Result unbalanced = AlphaTestEpilogue.apply(ShaderStage.FRAGMENT,
                "layout(location = 0) out vec4 o;\nvoid main() {\n    o = vec4(1.0);\n",
                AlphaTest.CUTOUT);
        assertFalse(unbalanced.injected());
        assertTrue(unbalanced.diagnostics().stream().anyMatch(d -> d.message().contains("不配对")),
                unbalanced.diagnostics().toString());
        assertEquals(unbalanced.reason(), "main 的花括号不配对");
    }

    @Test
    @DisplayName("NEVER 直接一句 discard;，不比较")
    void neverFunction() {
        AlphaTestEpilogue.Result r = AlphaTestEpilogue.apply(ShaderStage.FRAGMENT, FRAGMENT,
                new AlphaTest(AlphaTest.Function.NEVER, 0F));
        // 等行数注入：语句落在 main 收尾 } 的同一行 ⇒ 该行读作 `discard; }`
        assertTrue(r.text().contains("discard; }"), r.text());
        assertEquals(FRAGMENT.lines().count(), r.text().lines().count());
    }

    @Test
    @DisplayName("🔴 等行数：注入不许动行数（行号映射与本仓其它等行数段同一口径）")
    void injectionIsLineNeutral() {
        AlphaTestEpilogue.Result r =
                AlphaTestEpilogue.apply(ShaderStage.FRAGMENT, FRAGMENT, AlphaTest.CUTOUT);
        assertTrue(r.injected(), r.reason());
        assertEquals(FRAGMENT.lines().count(), r.text().lines().count(),
                "语句写在收尾 } 的同一行前面 ⇒ 行数必须一字不变");
    }

    @Test
    @DisplayName("回归针：兑现挂在会读 program.settings() 的那一层，不在不知道程序身份的翻译链里")
    void wiredAtTheProgramAwareLayer() throws Exception {
        java.nio.file.Path compiler =
                java.nio.file.Path.of("src/main/java/dev/vkdisp/pack/ShaderPackCompiler.java");
        java.nio.file.Path translator =
                java.nio.file.Path.of("src/main/java/dev/vkdisp/glsl/translate/OfGlslTranslator.java");
        if (!java.nio.file.Files.isReadable(compiler) || !java.nio.file.Files.isReadable(translator)) {
            return;
        }
        String c = java.nio.file.Files.readString(compiler);
        String t = java.nio.file.Files.readString(translator);
        assertTrue(c.contains("AlphaTestEpilogue.apply(stage, result.text(), alphaTest)"),
                "片元阶段必须在知道程序身份的那一层兑现 alphaTest");
        assertTrue(c.contains("alphaTestOf(program)"), "阈值来源是 program.settings() 的 alphaTest 键");
        assertFalse(t.contains("AlphaTestEpilogue.apply("),
                "翻译链不知道自己翻的是哪个程序 ⇒ 这一族不许挂在链里（同 h45 探针泄漏 composite 的形状）");
    }
}
