package dev.vkdisp.pipeline.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 包地形片元**契约解析**的单测（纯 Java，不碰 GPU、不碰库存包）。
 *
 * <p>🔖 <b>为什么这些断言必须存在</b>：契约解析错一个数字，后果不是「画面差一点」而是
 * <b>崩客户端</b>（附件数 ≠ 颜色目标数 ⇒ setPipeline 抛 IllegalStateException，X42）。
 * 而解析器最容易错的地方恰恰是「看起来对」——
 * 首个实现按行首锚定匹配声明，遇到
 * {@code layout(location = 0) in float mat; layout(location = 1) in float recolor;}
 * 这种一行两声明的写法就只认得出第一个（实测：location 0 被错配成 recolor、location 2 变成
 * lmCoord，静默少认 4 个 varying）。⇒ 每个坑都留一条钉死它的断言。
 */
class PackTerrainProgramTest {

    /** 最小可解析源：一个块 + 5 个 varying（两两并排）+ 2 个 sampler。 */
    private static final String FIXTURE = """
            #version 410
            layout(std140) uniform VkDispBuiltins {
            float viewWidth;
            vec3 cameraPosition;
            mat4 gbufferModelView;
            };
            layout(location = 0) in float mat; layout(location = 1) in float recolor;
            layout(location = 2) in vec2 texCoord; layout(location = 3) in vec2 lmCoord;
            uniform sampler2D texture_0;
            uniform sampler2DShadow shadowtex0;
            layout(location = 0) out vec4 vkdispFragOut0;
            void main() { }
            """;

    @Test
    @DisplayName("🔖 一行两个声明时**两个都要认出来**（首版只认第一个 ⇒ location 错配）")
    void parsesTwoDeclarationsOnOneLine() {
        PackTerrainProgram program = PackTerrainProgram.parse("fixture", "gbuffers_terrain", FIXTURE);
        assertEquals(List.of(
                        new PackTerrainProgram.Input(0, "float", "mat"),
                        new PackTerrainProgram.Input(1, "float", "recolor"),
                        new PackTerrainProgram.Input(2, "vec2", "texCoord"),
                        new PackTerrainProgram.Input(3, "vec2", "lmCoord")),
                program.inputs(),
                "一行两声明必须逐个解析；只认第一个会让 location 与名字错配（静默失败）");
    }

    @Test
    @DisplayName("🔖 块内成员不得被当成自由 sampler（cameraPosition 不是采样器）")
    void blockMembersAreNotSamplers() {
        PackTerrainProgram program = PackTerrainProgram.parse("fixture", "gbuffers_terrain", FIXTURE);
        assertEquals(List.of("texture_0", "shadowtex0"), program.fragmentSamplers(),
                "VkDispBuiltins 的成员是块内声明，不是自由 uniform；混进去会让绑定组登记错条目");
        assertFalse(program.declaresSampler("cameraPosition"));
        assertTrue(program.declaresSampler("texture_0"));
    }

    @Test
    @DisplayName("🔖 输出数 = 最大 location + 1（附件数的唯一真源）")
    void outputCountFollowsMaxLocation() {
        assertEquals(1, PackTerrainProgram.parse("f", "gbuffers_terrain", FIXTURE).outputCount());
        String three = FIXTURE + "layout(location = 3) out vec4 vkdispFragOut3;";
        assertEquals(4, PackTerrainProgram.parse("f", "gbuffers_terrain", three).outputCount(),
                "location 0..3 ⇒ 4 个颜色目标；中间空号也算一个槽（Vulkan 按下标分配）");
    }

    @Test
    @DisplayName("🔖 解析不出任何输出 ⇒ 显式抛（不返回默认值，不静默按 1 槽接线）")
    void noOutputIsLoudFailure() {
        String noOut = """
                #version 410
                uniform sampler2D texture_0;
                void main() { }
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> PackTerrainProgram.parse("f", "gbuffers_terrain", noOut));
        assertTrue(error.getMessage().contains("gbuffers_terrain"),
                "错误原文要带上程序名，否则线上只能看到一个裸 IllegalArgumentException");
    }

    @Test
    @DisplayName("🔖 绑定组条目顺序 = 内建块在前、sampler 按源序")
    void bindGroupNamesAreStable() {
        PackTerrainProgram program = PackTerrainProgram.parse("fixture", "gbuffers_terrain", FIXTURE);
        assertEquals(List.of(PackTerrainProgram.BUILTINS_BLOCK, "texture_0", "shadowtex0"),
                program.bindGroupUniformNames(),
                "块名必须与 GLSL 里的 uniform VkDispBuiltins 逐字一致，否则布局与 SPIR-V 对不上");
    }

    @Test
    @DisplayName("🔖 行注释与块注释不得被当成声明")
    void commentsAreStripped() {
        String withComments = """
                #version 410
                // layout(location = 7) out vec4 commentedOut;
                /* layout(location = 8) out vec4 alsoCommented; */
                layout(location = 0) out vec4 real;
                """;
        PackTerrainProgram program = PackTerrainProgram.parse("f", "gbuffers_terrain", withComments);
        assertEquals(1, program.outputCount(),
                "注释里的 location 8 若被读进来，附件数会多算 7 个 ⇒ 管线与 pass 直接不匹配");
    }

    @Test
    @DisplayName("🔖 空白源显式拒绝（空文本当 0 槽会把「没取到源」伪装成「单槽包」）")
    void blankSourceRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> PackTerrainProgram.parse("f", "gbuffers_terrain", "   \n  "));
    }
}
