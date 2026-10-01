package dev.vkdisp.pack.properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * 【参考调研】A 线 ShaderProperties 单测 / OF shaders.properties 语法事实
 * 0. 合规核对：参考 = OptiFine 官方文档 shaders.properties 语法（仓库外事实，零代码复制）；本测试为独立硬编码断言，
 *    不含任何第三方着色器包片段（18-PARALLEL §7.6）。→ 能否并入本项目 MIT：可以。
 * 1. 主实现：逐字段断言「做对了」（不是「看起来对」），期望值独立写出，避免用实现验证实现。
 * 2. 备选：无。
 * 3. 差异点：无。
 * 4. 许可证核对结论：MIT；零第三方代码。
 * 5. 性能基线：测试不进运行时。
 */
class ShaderPropertiesTest {

    @Test
    void parsesScreensSlidersProfilesAndProgramSwitches() {
        String text = """
                shadow.enabled=true
                clouds=false

                sliders=SHADOW_DARKNESS WETNESS

                screen=* [SHADOW] <profile>
                screen.SHADOW=SHADOW_DARKNESS SHADOW_DISTANCE
                screen.columns=2
                screen.SHADOW.columns=3

                profile.LOW=SHADOW_DARKNESS:0.05 WETNESS:0.0
                profile.HIGH=SHADOW_DARKNESS:0.20

                program.BLOOM.enabled=(BLOOM || SSAO) && !RAIN
                """;
        ShaderProperties p = ShaderProperties.parse(text);

        assertEquals("true", p.directives().get("shadow.enabled"));
        assertEquals("false", p.directives().get("clouds"));

        assertEquals(List.of("SHADOW_DARKNESS", "WETNESS"), p.sliders());

        assertEquals(List.of("*", "[SHADOW]", "<profile>"), p.screens().get(""));
        assertEquals(List.of("SHADOW_DARKNESS", "SHADOW_DISTANCE"), p.screens().get("SHADOW"));
        assertEquals(2, p.screenColumns().get(""));
        assertEquals(3, p.screenColumns().get("SHADOW"));

        assertEquals(List.of("SHADOW_DARKNESS:0.05", "WETNESS:0.0"), p.profiles().get("LOW"));
        assertEquals(List.of("SHADOW_DARKNESS:0.20"), p.profiles().get("HIGH"));

        assertEquals("(BLOOM || SSAO) && !RAIN", p.programSwitches().get("BLOOM"));
    }

    @Test
    void duplicateSlidersLineIsRejected() {
        String text = "sliders=A\n sliders=B\n";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> ShaderProperties.parse(text));
        assertTrue(ex.getMessage().contains("sliders"), ex.getMessage());
    }

    @Test
    void conditionalBlockExcludedWhenMacroUndefined() {
        String text = "separateAo=false\n#ifdef SSAO\nseparateAo=true\n#endif\n";
        ShaderProperties off = ShaderProperties.parse(text);
        assertEquals("false", off.directives().get("separateAo"));

        ShaderProperties on = ShaderProperties.parse(text, Set.of("SSAO"));
        assertEquals("true", on.directives().get("separateAo"));
    }

    @Test
    void numericComparisonEvaluatesDefinedStateAsZeroOrOne() {
        // 未定义标识符 → 0（与 GLSL 侧 DefineProcessor.ExprEval 同口径）：0 >= 11800 假 → 走 #else。
        String text = """
                #if MC_VERSION >= 11800
                newStyle=true
                #else
                oldStyle=true
                #endif
                """;
        ShaderProperties undef = ShaderProperties.parse(text);
        assertFalse(undef.directives().containsKey("newStyle"));
        assertEquals("true", undef.directives().get("oldStyle"));

        // 定义态按 1 计：1 >= 11800 仍为假（值环境未取证，X9 不猜 —— 见类 javadoc 登记）。
        ShaderProperties defined = ShaderProperties.parse(text, Set.of("MC_VERSION"));
        assertFalse(defined.directives().containsKey("newStyle"));
        assertEquals("true", defined.directives().get("oldStyle"));

        // 等值比较：#if FLAG == 1 只在 FLAG 已定义时命中。
        String flag = """
                #if FLAG == 1
                selected=yes
                #endif
                """;
        assertFalse(ShaderProperties.parse(flag).directives().containsKey("selected"));
        assertEquals("yes", ShaderProperties.parse(flag, Set.of("FLAG")).directives().get("selected"));
    }

    @Test
    void elifChainPicksFirstTrueBranch() {
        String text = """
                #if A == 1
                branch=first
                #elif B == 1
                branch=second
                #elif C == 1
                branch=third
                #else
                branch=fallback
                #endif
                """;
        assertEquals("second", ShaderProperties.parse(text, Set.of("B")).directives().get("branch"));
        assertEquals("first", ShaderProperties.parse(text, Set.of("A")).directives().get("branch"));
        assertEquals("fallback", ShaderProperties.parse(text).directives().get("branch"));
        assertEquals("third", ShaderProperties.parse(text, Set.of("C")).directives().get("branch"));
    }

    @Test
    void elseInsideExcludedParentStaysExcluded() {
        // 回归：嵌套在被剔除父级下的 #else 不得放行（旧实现用 !include 反转会错误放行）。
        String text = """
                #ifdef NEVER
                #if A == 1
                leaked=bad
                #else
                leaked=bad
                #endif
                #endif
                keep=ok
                """;
        ShaderProperties p = ShaderProperties.parse(text);
        assertFalse(p.directives().containsKey("leaked"));
        assertEquals("ok", p.directives().get("keep"));
    }

    @Test
    void orChainConsumesRightOperandWhenLeftIsTrue() {
        // 回归：旧实现用 Java 短路求值，A 为真时右侧 token 不被消费 → 误报「多余符号」。
        String text = """
                #if A || B
                hit=yes
                #endif
                """;
        assertEquals("yes", ShaderProperties.parse(text, Set.of("A")).directives().get("hit"));
        assertEquals("yes", ShaderProperties.parse(text, Set.of("B")).directives().get("hit"));
        assertFalse(ShaderProperties.parse(text).directives().containsKey("hit"));

        String mixed = """
                #if (A || B) && !C
                hit=yes
                #endif
                """;
        assertEquals("yes", ShaderProperties.parse(mixed, Set.of("A")).directives().get("hit"));
        assertEquals("yes", ShaderProperties.parse(mixed, Set.of("B")).directives().get("hit"));
        assertFalse(ShaderProperties.parse(mixed, Set.of("A", "C")).directives().containsKey("hit"));
    }

    @Test
    void comparisonSyntaxErrorsAreExplicit() {
        IllegalArgumentException incomplete = assertThrows(IllegalArgumentException.class,
                () -> ShaderProperties.parse("#if A >=\n"));
        assertTrue(incomplete.getMessage().contains("不完整"), incomplete.getMessage());

        IllegalArgumentException illegal = assertThrows(IllegalArgumentException.class,
                () -> ShaderProperties.parse("#if A @ B\n"));
        assertTrue(illegal.getMessage().contains("非法字符 '@'"), illegal.getMessage());

        IllegalArgumentException extra = assertThrows(IllegalArgumentException.class,
                () -> ShaderProperties.parse("#if A B\n"));
        assertTrue(extra.getMessage().contains("多余符号"), extra.getMessage());

        IllegalArgumentException danglingElif = assertThrows(IllegalArgumentException.class,
                () -> ShaderProperties.parse("#elif A == 1\n"));
        assertTrue(danglingElif.getMessage().contains("没有匹配"), danglingElif.getMessage());

        IllegalArgumentException duplicateElse = assertThrows(IllegalArgumentException.class,
                () -> ShaderProperties.parse("#ifdef X\n#else\n#else\n#endif\n"));
        assertTrue(duplicateElse.getMessage().contains("重复"), duplicateElse.getMessage());

        IllegalArgumentException elifAfterElse = assertThrows(IllegalArgumentException.class,
                () -> ShaderProperties.parse("#if A == 1\nx=1\n#else\ny=1\n#elif B == 1\nz=1\n#endif\n"));
        assertTrue(elifAfterElse.getMessage().contains("#else 之后"), elifAfterElse.getMessage());
    }

    @Test
    void decimalComparisonIsParsedWithoutThrowing() {
        String text = """
                #if RATIO >= 1.5
                big=yes
                #else
                big=no
                #endif
                """;
        assertEquals("no", ShaderProperties.parse(text).directives().get("big"));
        assertEquals("no", ShaderProperties.parse(text, Set.of("RATIO")).directives().get("big"));
    }

    @Test
    void lineContinuationJoinsBeforeDirectiveRecognition() {
        String value = """
                name=v1 \\
                v2
                """;
        assertEquals("v1 v2", ShaderProperties.parse(value).directives().get("name"));

        // 续行合并发生在条件识别**之前**：#if 的条件本身可以跨行。
        String condition = """
                #if A && \\
                B
                hit=yes
                #endif
                """;
        assertEquals("yes", ShaderProperties.parse(condition, Set.of("A", "B")).directives().get("hit"));
        assertFalse(ShaderProperties.parse(condition, Set.of("A")).directives().containsKey("hit"));

        // 行尾偶数个反斜杠 = 转义出的字面反斜杠，不是续行。
        String escaped = "name=v1\\\\\nnext=x\n";
        assertEquals("v1\\\\", ShaderProperties.parse(escaped).directives().get("name"));
        assertEquals("x", ShaderProperties.parse(escaped).directives().get("next"));
    }

    @Test
    void crlfLineEndingsStillJoinContinuations() {
        // BSL shaders.properties 全文 CRLF：`\` 后紧跟 \r，续行判定必须先摘 \r（p415 run1 缺陷回归）。
        String text = "name=v1 \\\r\nv2\r\n#ifdef EXTRA\r\nmore=on\r\n#endif\r\n";
        ShaderProperties p = ShaderProperties.parse(text, Set.of("EXTRA"));
        assertEquals("v1 v2", p.directives().get("name"));
        assertEquals("on", p.directives().get("more"));
    }

    @Test
    void unsupportedDefineDirectiveIsRejected() {
        String text = "#define SSAO\n";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> ShaderProperties.parse(text));
        assertTrue(ex.getMessage().contains("#define"), ex.getMessage());
    }

    @Test
    void unbalancedIfEndifIsRejected() {
        String text = "#ifdef SSAO\nseparateAo=true\n";
        assertThrows(IllegalArgumentException.class, () -> ShaderProperties.parse(text));
    }

    @Test
    void fixtureMinimalPackParses() {
        InputStream in = ShaderPropertiesTest.class.getResourceAsStream("/packs/minimal/shaders.properties");
        assertTrue(in != null, "fixture packs/minimal/shaders.properties 必须存在");
        ShaderProperties p = ShaderProperties.parse(new java.io.InputStreamReader(in));
        assertFalse(p.sliders().isEmpty(), "minimal 样本应含 sliders");
        assertTrue(p.screens().containsKey("SHADOW"), "minimal 样本应含 screen.SHADOW");
        assertTrue(p.profiles().containsKey("LOW"), "minimal 样本应含 profile.LOW");
    }
}
