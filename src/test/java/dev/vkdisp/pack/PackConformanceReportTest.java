package dev.vkdisp.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vkdisp.pipeline.model.PackSamplerSuperset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * C3 守卫：一致性报告必须**覆盖 §4.1 的全部名单**，并且每条计数都由当场读到的集合算出来
 * （报告里不许有手抄数字 —— `19` §5.1）。
 */
class PackConformanceReportTest {

    private static final String COMPOSITE = """
            #version 430
            uniform sampler2D colortex0;
            uniform sampler2D depthtex0;
            """;

    private static PackPostChain.Pass pass(String programName, List<Integer> slots,
            List<String> samplers, String fragmentSource, String vertexSource) {
        return new PackPostChain.Pass(programName, "world0/" + programName, slots, samplers,
                List.of(), List.of(), fragmentSource, vertexSource);
    }

    private static PackConformanceReport.Report report(PackPostChain.Chain chain) {
        return PackConformanceReport.build(new PackConformanceReport.Facts(
                "fixture", COMPOSITE, COMPOSITE, COMPOSITE, chain, Map.of()));
    }

    @AfterEach
    void resetSnapshot() {
        PackSamplerSuperset.reset();
    }

    @Test
    @DisplayName("🔖 §4.1 那张表的 10 项必须各有一节，且每节都写明引擎侧真源在哪")
    void everySection41ListIsCovered() {
        PackSamplerSuperset.install(COMPOSITE, COMPOSITE, COMPOSITE);
        PackConformanceReport.Report report = report(PackPostChain.Chain.EMPTY);

        List<String> expected = List.of(
                PackConformanceReport.LIST_FRAGMENT_SAMPLERS,
                PackConformanceReport.LIST_POST_SAMPLERS,
                PackConformanceReport.LIST_LIGHT_DIRECTION,
                PackConformanceReport.LIST_CASCADE_ORTHO,
                PackConformanceReport.LIST_BUILTIN_CATALOG,
                PackConformanceReport.LIST_DECLARED_UNSUPPLIED,
                PackConformanceReport.LIST_ATTRIBUTE_ALIASES,
                PackConformanceReport.LIST_SAMPLER_ROUTING,
                PackConformanceReport.LIST_POST_VERTEX_ATTRIBUTES,
                PackConformanceReport.LIST_CHAIN_LIMITS);
        assertEquals(expected, report.sections().stream()
                        .map(PackConformanceReport.Section::list).toList(),
                "报告的小节必须与 §4.1 的名单一一对应（少一节 = 那张名单仍然没人管）");
        for (PackConformanceReport.Section section : report.sections()) {
            assertFalse(section.engineSource().isBlank(),
                    section.list() + " 必须写明引擎侧真源（否则读者无法复核）");
        }
    }

    @Test
    @DisplayName("🔖 包声明了引擎名单之外的名字 ⇒ 逐名点名，且计数与集合一致")
    void packOnlyNamesAreReportedByName() {
        PackSamplerSuperset.install(
                "uniform sampler2D colortex0;\nuniform sampler2D myCustomMask;\n", null, null);

        PackConformanceReport.Section section =
                report(PackPostChain.Chain.EMPTY).section(PackConformanceReport.LIST_FRAGMENT_SAMPLERS)
                        .orElseThrow();

        assertEquals(PackSamplerSuperset.NAMES.size() + 1, section.entries().size(),
                "超集每一条 + 包多出来的那一条；不许有第二份名单");
        assertEquals(1, section.count(PackConformanceReport.Status.PACK_ONLY));
        assertTrue(section.entries().stream()
                        .anyMatch(e -> e.name().equals("myCustomMask")
                                && e.status() == PackConformanceReport.Status.PACK_ONLY),
                "myCustomMask 必须以 PACK_ONLY 出现");
        assertTrue(report(PackPostChain.Chain.EMPTY).warnings().stream()
                        .anyMatch(w -> w.contains("myCustomMask")
                                && w.contains(PackConformanceReport.LIST_FRAGMENT_SAMPLERS)),
                "报告必须产出一条点名它的 WARN（T11：不许静默）");
    }

    @Test
    @DisplayName("🔖 链长/槽位上界与这张包的实际值当场对差（「BSL 10 < 16」这类断言要可数）")
    void chainLimitsCompareEngineCapsAgainstActualChain() {
        List<PackPostChain.Pass> passes = new java.util.ArrayList<>();
        for (int i = 0; i < PackPostChain.MAX_POST_PASSES + 1; i++) {
            passes.add(pass("composite" + i, List.of(0), List.of(), "uniform sampler2D colortex0;", null));
        }
        PackConformanceReport.Section section =
                report(new PackPostChain.Chain(passes, List.of()))
                        .section(PackConformanceReport.LIST_CHAIN_LIMITS).orElseThrow();

        assertEquals(2, section.entries().size(), "两条：链长 + 颜色槽");
        PackConformanceReport.Entry length = section.entries().get(0);
        assertEquals(PackConformanceReport.Status.PACK_ONLY, length.status(),
                "链长超过 MAX_POST_PASSES ⇒ 必须点名，实测 note=" + length.note());
        assertTrue(length.note().contains("MAX_POST_PASSES=" + PackPostChain.MAX_POST_PASSES),
                "现值由代码算出并写进 note（不手抄），实测：" + length.note());
    }

    @Test
    @DisplayName("🔖 内建目录一节：包收编的成员与目录不符要分得出来")
    void builtinCatalogSectionDistinguishesCollectedMembers() {
        PackSamplerSuperset.install(COMPOSITE, COMPOSITE, COMPOSITE);
        // 🔖 块必须写成引擎发射的那个形状（`BuiltinsBlockLayout.parse` 认的是**整行**等于
        //   `UniformInjector.BLOCK_OPEN` 的那一行）—— 用常量而不是抄一份字符串，免得两处漂。
        String withBuiltins = "#version 430\n" + dev.vkdisp.glsl.translate.UniformInjector.BLOCK_OPEN
                + "\nmat4 gbufferModelView;\nfloat frameTimeCounter;\nfloat notInCatalog;\n};\n";
        PackConformanceReport.Report report = PackConformanceReport.build(
                new PackConformanceReport.Facts("fixture", withBuiltins, withBuiltins, withBuiltins,
                        PackPostChain.Chain.EMPTY, Map.of()));
        PackConformanceReport.Section section =
                report.section(PackConformanceReport.LIST_BUILTIN_CATALOG).orElseThrow();

        assertTrue(section.entries().stream().anyMatch(
                        e -> e.name().equals("notInCatalog")
                                && e.status() == PackConformanceReport.Status.PACK_ONLY),
                "包引用但目录没有 ⇒ PACK_ONLY（GAP-021 那一族），实测：" + section.entries().size());
        assertTrue(section.entries().stream().anyMatch(
                        e -> e.name().equals("frameTimeCounter")
                                && e.status() == PackConformanceReport.Status.HIT),
                "目录有且包收编了 ⇒ HIT");
    }

    @Test
    @DisplayName("矩阵文本：一单一行，可直接接进 08-TESTING §10 的兼容矩阵")
    void matrixHasOneLinePerList() {
        PackSamplerSuperset.install(COMPOSITE, COMPOSITE, COMPOSITE);
        String[] lines = report(PackPostChain.Chain.EMPTY).asMatrix().strip().split("\n");
        assertEquals(10, lines.length, "10 张名单 ⇒ 10 行矩阵");
        assertTrue(lines[0].startsWith("list=" + PackConformanceReport.LIST_FRAGMENT_SAMPLERS),
                "第一行是全屏 sampler，实测：" + lines[0]);
    }
}
