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
