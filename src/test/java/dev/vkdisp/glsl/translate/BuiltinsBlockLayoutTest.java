package dev.vkdisp.glsl.translate;
/**
 * 【参考调研】P4.1.3 块布局解析单测 / std140 规则 + UniformInjector 真实输出对账
 * 0. 合规核对（第 0 步闸门，通过）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §3.2（表 + 上传注记「布局与缓冲」）；
 *    ② std140 公开事实性规则（偏移期望值全部在本文件手算写出，可复核）；
 *    ③ 本仓库 UniformInjector / OfGlslTranslator 的真实输出（对账用例）。
 *    许可证：本文件为独立编写的测试代码（MIT）。
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：合成块源 → {@link BuiltinsBlockLayout#parse} → 断言成员序/偏移/字节数；
 *    末例跑真转译（OfGlslTranslator）解析终稿，验证「解析器认得注入器的发射形态」。
 * 2. 备选：无（布局解析无第二实现，错误共享面由手算期望值对冲）。
 * 3. 我们的差异点：期望偏移是**手算常数**而非"再算一遍" —— 解析器与断言不共享实现。
 * 4. 许可证核对结论：本项目 MIT。
 * 5. 性能基线：❄️ 冷路径单测，不评估性能（T14）。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class BuiltinsBlockLayoutTest {

    /** 合成"注入器形态"的块源（开/闭行与 UniformInjector 逐字节一致）。 */
    private static String blockSource(String... memberLines) {
        StringBuilder source = new StringBuilder();
        source.append(UniformInjector.BLOCK_HEADER).append('\n');
        source.append(UniformInjector.BLOCK_OPEN).append('\n');
        for (String line : memberLines) {
            source.append(line).append('\n');
        }
        source.append(UniformInjector.BLOCK_CLOSE).append('\n');
        return source.toString();
    }

    /** 成员偏移锚点断言（无此成员直接失败）。 */
    private static int offsetOf(BuiltinsBlockLayout layout, String name) {
        BuiltinsBlockLayout.Member member = layout.find(name);
        assertNotNull(member, "成员缺失: " + name);
        return member.offset();
    }

    @Test
    void catalogOrderBlockHasHandComputedStd140Offsets() {
        // 23 条按 UniformCatalog（= 04-SPEC §3.2 表）顺序 —— 全缺失包的兜底形态。
        StringBuilder source = new StringBuilder();
        source.append(UniformInjector.BLOCK_HEADER).append('\n');
        source.append(UniformInjector.BLOCK_OPEN).append('\n');
        for (BuiltinUniform uniform : UniformCatalog.uniforms()) {
            source.append(uniform.type()).append(' ').append(uniform.name()).append(";\n");
        }
        source.append(UniformInjector.BLOCK_CLOSE).append('\n');

        BuiltinsBlockLayout layout = BuiltinsBlockLayout.parse(source.toString());
        assertNull(layout.failure());
        assertEquals(23, layout.members().size());

        // 手算（std140）：mat4×6 = 0/64/128/192/256/320 → 384；
        // vec3 各对齐 16：cameraPosition 384、sun 400、moon 416、shadowLight 432 → 444；
        // float frameTimeCounter 444（对齐 4 恰在 vec3 尾）→ 448；
        // int frameCounter 448；float×6 viewWidth..rainStrength 452..472 → 476；
        // int×3 isEyeInWater/worldTime/worldDay 476/480/484 → 488；
        // ivec2 对齐 8：atlasSize 488 → 496、eyeBrightnessSmooth 496 → 504；
        // 块尾 roundUp(504,16) = 512。
        int[] expected = {
                0, 64, 128, 192, 256, 320,       // mat4 ×6
                384, 400, 416, 432,              // vec3 ×4
                444,                             // frameTimeCounter
                448,                             // frameCounter
                452, 456, 460, 464, 468, 472,    // viewWidth/Height/near/far/wetness/rainStrength
                476, 480, 484,                   // isEyeInWater/worldTime/worldDay
                488, 496                         // atlasSize/eyeBrightnessSmooth
        };
        List<BuiltinUniform> catalog = UniformCatalog.uniforms();
        for (int i = 0; i < catalog.size(); i++) {
            assertEquals(expected[i], offsetOf(layout, catalog.get(i).name()),
                    "offset[" + i + "] " + catalog.get(i).name());
        }
        assertEquals(512, layout.byteSize());
        assertFalse(layout.isEmpty());
    }

    @Test
    void adoptedCommaDeclarationComesFirstAndAlignsUp() {
        // BSL 形态：收编的 "float far, near;" 在目录缺失项之前；vec3 须对齐 16。
        BuiltinsBlockLayout layout = BuiltinsBlockLayout.parse(blockSource(
                "float far, near;",
                "vec3 cameraPosition;"));
        assertNull(layout.failure());
        assertEquals(3, layout.members().size());
        assertEquals(0, offsetOf(layout, "far"));
        assertEquals(4, offsetOf(layout, "near"));
        assertEquals(16, offsetOf(layout, "cameraPosition"), "vec3 对齐 16，4..15 空洞");
        assertEquals(32, layout.byteSize(), "roundUp(16+12,16) = 32");
    }

    @Test
    void noBlockIsNotAFailure() {
        BuiltinsBlockLayout layout = BuiltinsBlockLayout.parse(
                "#version 410\nvoid main() { gl_FragColor = vec4(1.0); }\n");
        assertNull(layout.failure(), "passthrough 兜底无块 = 正常态，不是失败");
        assertTrue(layout.isEmpty());
        assertEquals(0, layout.byteSize());
        assertNull(layout.find("far"));

        assertTrue(BuiltinsBlockLayout.parse(null).isEmpty());
        assertTrue(BuiltinsBlockLayout.parse("").isEmpty());
    }

    @Test
    void unsupportedTypeFailsClosedToEmpty() {
        BuiltinsBlockLayout layout = BuiltinsBlockLayout.parse(blockSource(
                "double weird;",
                "float after;"));
        assertNotNull(layout.failure(), "未知类型必须失败（不猜偏移）");
        assertTrue(layout.failure().contains("unsupported member type 'double'"),
                "failure 原文应可定位: " + layout.failure());
        assertTrue(layout.isEmpty(), "失败态 = 空布局（回退零填充）");
        assertEquals(0, layout.byteSize());
    }

    @Test
    void arrayMembersUseStd140Stride() {
        // float[4]：步进 roundUp(4,16) = 16 → 总 64；vec4 对齐 16 → 64；块尾 80。
        BuiltinsBlockLayout layout = BuiltinsBlockLayout.parse(blockSource(
                "float weights[4];",
                "vec4 next;"));
        assertNull(layout.failure());
        assertEquals(0, offsetOf(layout, "weights"));
        assertEquals(64, layout.find("weights").size(), "数组步进 16 × 4");
        assertEquals(64, offsetOf(layout, "next"));
        assertEquals(80, layout.byteSize(), "roundUp(64+16,16) = 80");
    }

    @Test
    void nonLiteralArraySizeFails() {
        BuiltinsBlockLayout layout = BuiltinsBlockLayout.parse(blockSource(
                "float weights[N * 2];"));
        assertNotNull(layout.failure());
        assertTrue(layout.failure().contains("non-literal array size"),
                "原文: " + layout.failure());
        assertTrue(layout.isEmpty());
    }

    @Test
    void precisionQualifierAndCommaVec2() {
        BuiltinsBlockLayout layout = BuiltinsBlockLayout.parse(blockSource(
                "mediump float a;",
                "highp vec2 b, c;"));
        assertNull(layout.failure());
        assertEquals(0, offsetOf(layout, "a"));
        assertEquals(8, offsetOf(layout, "b"), "vec2 对齐 8");
        assertEquals(16, offsetOf(layout, "c"));
        assertEquals(32, layout.byteSize(), "roundUp(16+8,16) = 32");
    }

    @Test
    void matrixFamilySizes() {
        // std140：mat2 = 2 列 × 16 = 32、mat3 = 48、mat4 = 64，全对齐 16。
        BuiltinsBlockLayout layout = BuiltinsBlockLayout.parse(blockSource(
                "mat2 m2;",
                "mat3 m3;",
                "mat4 m4;"));
        assertNull(layout.failure());
        assertEquals(0, offsetOf(layout, "m2"));
        assertEquals(32, layout.find("m2").size());
        assertEquals(32, offsetOf(layout, "m3"));
        assertEquals(48, layout.find("m3").size());
        assertEquals(80, offsetOf(layout, "m4"));
        assertEquals(64, layout.find("m4").size());
        assertEquals(144, layout.byteSize(), "roundUp(80+64,16) = 144");
    }

    @Test
    void duplicateMemberNameFails() {
        BuiltinsBlockLayout layout = BuiltinsBlockLayout.parse(blockSource(
                "float x;",
                "float x;"));
        assertNotNull(layout.failure());
        assertTrue(layout.failure().contains("duplicate member name"), layout.failure());
        assertTrue(layout.isEmpty());
    }

    @Test
    void trailingLineCommentIsStripped() {
        // 收编行可能带原作者行尾注释（"float far; // depth"）。
        BuiltinsBlockLayout layout = BuiltinsBlockLayout.parse(blockSource(
                "float far; // pack depth note",
                "int worldTime; /* tick */"));
        assertNull(layout.failure());
        assertEquals(0, offsetOf(layout, "far"));
        assertEquals(4, offsetOf(layout, "worldTime"));
        assertEquals(16, layout.byteSize(), "roundUp(4+4,16) = 16");
    }

    @Test
    void parsesRealTranslatorOutputAdoptedFirstThenCatalogTail() {
        // 自造 OF 方言片元（§7.6 自制样本）：游离 uniform 被收编进块、缺失目录补尾。
        String sample = """
                #version 120
                uniform float rainStrength;
                varying vec2 texcoord;
                void main() { gl_FragColor = vec4(texcoord, rainStrength, 1.0); }
                """;
        String text = OfGlslTranslator.translate(ShaderStage.FRAGMENT, sample).text();
        BuiltinsBlockLayout layout = BuiltinsBlockLayout.parse(text);
        assertNull(layout.failure(), "必须认得注入器真实发射形态");
        assertEquals(23, layout.members().size(), "收编 1 + 目录缺失 22");
        assertEquals(0, offsetOf(layout, "rainStrength"), "收编行在块首");
        assertEquals(16, offsetOf(layout, "gbufferModelView"),
                "float 收编后 mat4 对齐 16（4..15 空洞）");
        for (BuiltinUniform uniform : UniformCatalog.uniforms()) {
            assertNotNull(layout.find(uniform.name()), "目录成员齐全: " + uniform.name());
        }
        // 幂等：终稿再解析一次，偏移完全一致。
        BuiltinsBlockLayout again = BuiltinsBlockLayout.parse(text);
        assertEquals(layout.byteSize(), again.byteSize());
        assertEquals(layout.members(), again.members());
    }
}
