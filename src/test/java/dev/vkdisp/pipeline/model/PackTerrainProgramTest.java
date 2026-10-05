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
    @DisplayName("🔖🔖 声明写哪些槽 ≠ 有几个附件（BSL 默认档实测 8 附件但只写 4 槽）")
    void declaredSlotsAreSeparateFromOutputCount() {
        // 🔖🔖 这是本轮实测定位的**假证据**来源：附件存在 ≠ 附件被写。
        //   BSL v10.1.8 默认档（MCBL_SS=包声明的 false / ADVANCED_MATERIALS=无 option 行 ⇒ 默认 true）
        //   走 #else 分支里那条活标记 `/* DRAWBUFFERS:0367 */` ⇒ 声明输出槽 [0,3,6,7]。
        //   若只保留 outputCount=8 而丢掉槽位集合，下游就会去读附件 1/2/4/5
        //   —— 那四张图只有清屏值，读出来是「全黑」，会被当成「包片元输出黑」。
        String bslShape = FIXTURE
                + "layout(location = 3) out vec4 vkdispFragOut3;\n"
                + "layout(location = 6) out vec4 vkdispFragOut6;\n"
                + "layout(location = 7) out vec4 vkdispFragOut7;\n";
        PackTerrainProgram program = PackTerrainProgram.parse("f", "world0/gbuffers_terrain", bslShape);
        assertEquals(8, program.outputCount(), "最大 location 7 ⇒ 8 个颜色目标");
        assertEquals(List.of(0, 3, 6, 7), program.declaredOutputSlots(),
                "声明写的只有 4 槽；附件 1/2/4/5 存在但没有任何片元输出");
        for (int slot : List.of(0, 3, 6, 7)) {
            assertTrue(program.declaresOutputSlot(slot));
        }
        for (int slot : List.of(1, 2, 4, 5)) {
            assertFalse(program.declaresOutputSlot(slot),
                    "槽 " + slot + " 没有片元输出；声称它有 = 造出一个不存在的槽位事实");
        }
    }

    @Test
    @DisplayName("🔖 槽位集合去重升序（重复声明同一 location 不该产生两个待测槽）")
    void declaredSlotsAreDedupedAndSorted() {
        String dup = FIXTURE + "layout(location = 3) out vec4 a;\n"
                + "layout(location = 3) out vec4 b;\n"
                + "layout(location = 1) out vec4 c;\n";
        PackTerrainProgram program = PackTerrainProgram.parse("f", "gbuffers_terrain", dup);
        assertEquals(List.of(0, 1, 3), program.declaredOutputSlots());
        assertEquals(4, program.outputCount());
    }

    @Test
    @DisplayName("🔖 自相矛盾的契约（声明槽越出 outputCount）显式抛，不接线")
    void selfInconsistentContractRejected() {
        // 🔖 「附件数 1 但声明写槽 5」若被放过去，下游会以为存在一个第 6 个附件
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new PackTerrainProgram("f", "gbuffers_terrain", FIXTURE, 1,
                        List.of(), List.of(), List.of(5)));
        assertTrue(error.getMessage().contains("gbuffers_terrain"),
                "错误原文要带上程序名，否则线上只能看到一个裸 IllegalArgumentException");
    }

    @Test
    @DisplayName("🔖 空槽位集合 = **未知**，不是一个可判定的「什么都没写」")
    void emptyDeclaredSlotsMeansUnknown() {
        // 🔖 空集合在语义上表示「本项目没有该片元的输出契约」（未接包片元），
        //   不是「包声明了 0 个输出」—— 后者与 parse 的抛错路径重复且不可能发生。
        PackTerrainProgram program =
                new PackTerrainProgram("f", "gbuffers_terrain", FIXTURE, 3, List.of(), List.of(), null);
        assertTrue(program.declaredOutputSlotsUnknown());
        assertFalse(program.declaresOutputSlot(0), "未知 ≠ 声称 0 号槽没写");
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
