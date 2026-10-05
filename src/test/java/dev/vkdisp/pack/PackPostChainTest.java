package dev.vkdisp.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vkdisp.glsl.TranslateDiagnostic;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 🔴 通用后处理链的编排守卫（用户点名「colortex 接进后处理链」的机制层）。
 *
 * <p>钉住四件此前没有任何东西保证的事：
 * ① OF 执行序（deferred → composite → composite1..N → final）；
 * ② 每一步的**声明槽集合**（抽自转译终稿的 {@code layout(location=N) out}）；
 * ③ 其它维度目录的程序**不混进**当前链（串链比断链更糟，P4.1 同源判据）；
 * ④ 重编号后的源 location 是附件下标（0..m-1），不是 colortex 槽号。
 */
class PackPostChainTest {

    @TempDir
    Path inventory;

    private static final String POST_FSH = """
            #version 150
            uniform sampler2D colortex0;
            uniform sampler2D depthtex0;
            layout(location = 0) out vec4 fragColor;
            void main() {
                fragColor = texture(colortex0, vec2(0.5)) + texture(depthtex0, vec2(0.25)).r;
            }
            """;

    private void write(String relPath, String body) throws IOException {
        Path file = inventory.resolve("fixture").resolve("shaders").resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("🔴 链 = 整包后处理序列，按 OF 族序+序号排序；final 打 isFinal 标")
    void chainOrdersAllPostProgramsByOfSequence() throws IOException {
        write("composite.fsh", POST_FSH);
        write("deferred.fsh", POST_FSH);
        write("deferred1.fsh", POST_FSH);
        write("composite1.fsh", POST_FSH);
        write("composite7.fsh", POST_FSH);
        write("final.fsh", POST_FSH);

        PackCompositeSource.Result result = PackCompositeSource.generate(inventory, "");

        assertFalse(result.fallback(), "可用包不该兜底");
        List<String> names = result.chain().passes().stream()
                .map(PackPostChain.Pass::programName).toList();
        assertEquals(List.of("deferred", "deferred1", "composite", "composite1", "composite7", "final"),
                names, "OF 执行序：deferred* → composite（0 号）→ composite*（≥1）→ final");
        assertTrue(result.chain().passes().stream()
                        .filter(PackPostChain.Pass::isFinal).count() == 1,
                "只有 final 步带 isFinal —— 它的附件 0 在运行期换成主目标");
    }

    @Test
    @DisplayName("🔴 每步契约：声明槽 + sampler 名单 + 重编号后 location 是下标")
    void contractCarriesSlotsAndSamplersAndRenumberedLocations() throws IOException {
        write("composite.fsh", POST_FSH);
        write("composite3.fsh", """
                #version 150
                uniform sampler2D colortex4;
                layout(location = 0) out vec4 a;
                layout(location = 1) out vec4 b;
                void main() { a = texture(colortex4, vec2(0.5)); b = a; }
                """);

        PackCompositeSource.Result result = PackCompositeSource.generate(inventory, "");
        assertEquals(2, result.chain().passes().size());
        PackPostChain.Pass third = result.chain().passes().get(1);
        assertEquals("composite3", third.programName());
        assertTrue(third.samplerNames().contains("colortex4"),
                () -> "包声明的 sampler 名必须进契约（绑定按它逐条接），实际: " + third.samplerNames());
        assertTrue(third.renumberedSource().contains("layout(location = 0) out vec4 a")
                        && third.renumberedSource().contains("layout(location = 1) out vec4 b"),
                () -> "重编号后 location = 附件下标；实际源:\n" + third.renumberedSource());
    }

    @Test
    @DisplayName("🔖 只存在于其它维度目录的后处理程序不进链，且 WARN 可见（不静默丢）")
    void otherDimensionProgramsAreExcludedVisibly() throws IOException {
        write("composite.fsh", POST_FSH);
        write("world-1/composite2.fsh", POST_FSH);

        PackCompositeSource.Result result = PackCompositeSource.generate(inventory, "");

        List<String> names = result.chain().passes().stream()
                .map(PackPostChain.Pass::programName).toList();
        assertFalse(names.contains("composite2"),
                "world-1 专属程序不得混进当前链（串链比断链更糟）");
        assertTrue(result.diagnostics().stream().anyMatch(d ->
                        d.severity() == TranslateDiagnostic.Severity.WARN
                                && d.message().contains("composite2")
                                && d.message().contains("不进链")),
                () -> "跳过必须逐条可见（T11），实际: " + result.diagnostics());
    }

    @Test
    @DisplayName("🔴 超集外的 sampler 名：编排期就踢出链（而不是运行期 Missing uniform 炸整帧）")
    void samplerOutsideSupersetIsRefusedAtPlanning() throws IOException {
        write("composite.fsh", POST_FSH);
        write("composite2.fsh", """
                #version 150
                uniform sampler2D mysteryTex;
                layout(location = 0) out vec4 fragColor;
                void main() { fragColor = texture(mysteryTex, vec2(0.5)); }
                """);

        PackCompositeSource.Result result = PackCompositeSource.generate(inventory, "");

        List<String> names = result.chain().passes().stream()
                .map(PackPostChain.Pass::programName).toList();
        assertFalse(names.contains("composite2"),
                "声明了绑定组超集外 sampler 的 pass 不得进链（draw 时抛 Missing uniform = 整帧陪葬）");
        assertTrue(result.diagnostics().stream().anyMatch(d ->
                        d.message().contains("mysteryTex") && d.message().contains("不进链")),
                () -> "踢出必须点名 mysteryTex（换包时一眼知道要扩哪条），实际: " + result.diagnostics());
    }

    @Test
    @DisplayName("🔴 mip 声明必须穿透转译活到契约（BSL 的 bloom 判据来源；解析断=金字塔永不跑）")
    void mipEnabledConstSurvivesIntoContract() throws IOException {
        write("composite.fsh", POST_FSH);
        write("composite4.fsh", """
                #version 150
                const bool colortex0MipmapEnabled = true;
                uniform sampler2D colortex0;
                layout(location = 0) out vec4 fragColor;
                void main() { fragColor = texture(colortex0, vec2(0.5)); }
                """);

        PackCompositeSource.Result result = PackCompositeSource.generate(inventory, "");
        PackPostChain.Pass bloom = result.chain().passes().stream()
                .filter(p -> p.programName().equals("composite4")).findFirst().orElseThrow();
        assertEquals(java.util.List.of(0), bloom.mipEnabledSlots(),
                () -> "const 必须被契约解析出来；pass: " + bloom.samplerNames());
        assertEquals(java.util.Set.of(0), result.chain().mipEnabledSlots(),
                "Chain 聚合出的声明槽 = 金字塔只对这些槽生成的依据");
    }

    @Test
    @DisplayName("🔖 池上界：maxSlot() 报告全链最大 colortex 槽（池按它扩）")
    void maxSlotCoversWholeChain() throws IOException {
        write("composite.fsh", """
                #version 150
                uniform sampler2D colortex0;
                layout(location = 0) out vec4 fragColor;
                void main() { fragColor = texture(colortex0, vec2(0.5)); }
                """);
        write("composite5.fsh", """
                #version 150
                uniform sampler2D colortex0;
                layout(location = 0) out vec4 a;
                layout(location = 1) out vec4 b;
                void main() { a = texture(colortex0, vec2(0.5)); b = a; }
                """);

        PackCompositeSource.Result result = PackCompositeSource.generate(inventory, "");

        PackPostChain.Chain chain = result.chain();
        assertEquals(2, chain.passes().size());
        // composite5 的重编号源把「colortex 0 与 1」落到附件下标 0/1 ⇒ 池只需 2 槽；
        // 若哪天某步声明了 location=7，maxSlot 必须是 7（池扩到 8）。
        assertEquals(1, chain.maxSlot(), () -> "全链最大槽，实际链: " + chain.passes());
    }
}
