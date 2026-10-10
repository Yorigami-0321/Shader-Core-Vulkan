package dev.vkdisp.pipeline.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * C1 守卫：全屏步 sampler 的**布局超集由规则生成**，**声明快照来自包自己的转译终稿**，
 * 两者不符必须点名（QD-11① / `19` §4.2）。
 */
class PackSamplerSupersetTest {

    @AfterEach
    void resetSnapshot() {
        PackSamplerSuperset.reset();
    }

    @Test
    @DisplayName("🔖 规则生成的超集必须**覆盖旧的 18 名**（换来源不许丢任何一条已接线的名字）")
    void generatedSupersetCoversTheLegacyBslList() {
        List<String> legacy = List.of(
                "colortex0", "colortex1", "colortex6", "colortex8", "colortex9",
                "depthtex0", "depthtex1", "noisetex",
                "shadowcolor0", "shadowtex0", "shadowtex1",
                "gaux1", "lighttex0", "lighttex1",
                "vxDepthTexOpaque", "vxDepthTexTrans",
                "dhDepthTex0", "dhDepthTex1");
        for (String name : legacy) {
            assertTrue(PackSamplerSuperset.NAMES.contains(name),
                    "旧名单里的 " + name + " 必须仍在超集里，否则 BSL 直接少绑一条");
        }
        // 规则化的意义：同一族里「这个包恰好没用」不该等于「引擎不支持」。
        assertTrue(PackSamplerSuperset.NAMES.contains("colortex15"), "colortex0..15 是 OF 定义的族");
        assertTrue(PackSamplerSuperset.NAMES.contains("depthtex2"), "depthtex0..2 同理");
        assertTrue(PackSamplerSuperset.NAMES.contains("gaux4"), "gaux1..4 同理");
    }

    @Test
    @DisplayName("声明快照：类型决定视图类别，超集之外的名字必须被点名")
    void snapshotReadsPackDeclarations() {
        PackSamplerSuperset.install(
                "#version 430\nuniform sampler2D colortex0;\nuniform sampler3D lighttex0;\n",
                "#version 430\nuniform sampler2D myCustomMask;\n",
                "#version 430\nuniform sampler2D noisetex;\n");

        PackSamplerSuperset.Snapshot snapshot = PackSamplerSuperset.current();
        assertTrue(snapshot.derivedFromPack(), "包有声明 ⇒ 快照必须是派生态");
        assertEquals(4, snapshot.declared().size(), "四条声明（composite 2 + deferred 1 + final 1）");
        assertEquals(SamplerDimensionPlan.ViewKind.VOLUME_3D,
                snapshot.declared("lighttex0").orElseThrow().kind(),
                "sampler3D ⇒ 3D 桩，不再被当 2D 颜色视图喂（旧实现就是这里静默 UB）");
        assertEquals("composite", snapshot.declared("colortex0").orElseThrow().origin());
        assertEquals("final", snapshot.declared("noisetex").orElseThrow().origin());
        assertEquals(List.of("myCustomMask"), snapshot.outsideSuperset(),
                "包自造名不在超集里 ⇒ 必须逐名点名（C3 的输入）");
        assertTrue(snapshot.supersetNotDeclared().contains("colortex15"),
                "超集里这张包没用的名字要能被数出来（无害，但报告要写）");
    }

    @Test
    @DisplayName("注释里的声明不算数（与 SamplerDimensionPlan 同口径）")
    void commentedDeclarationIsIgnored() {
        PackSamplerSuperset.install("// uniform sampler2D colortex7;\nuniform sampler2D colortex0;\n",
                null, null);
        assertFalse(PackSamplerSuperset.current().declared().containsKey("colortex7"),
                "注释里的声明不该进快照");
        assertEquals(1, PackSamplerSuperset.current().declared().size());
    }

    @Test
    @DisplayName("同一 sampler 在不同步里声明成两种类型 ⇒ 取先出现的并点名")
    void conflictingTypesAcrossStepsAreReported() {
        PackSamplerSuperset.install(
                "uniform sampler2D gaux1;\n",
                "uniform sampler3D gaux1;\n",
                null);
        PackSamplerSuperset.Snapshot snapshot = PackSamplerSuperset.current();
        assertEquals("sampler2D", snapshot.declared("gaux1").orElseThrow().declaredType(),
                "取先出现的那条（composite），不静默取最后");
        assertTrue(snapshot.planWarnings().stream().anyMatch(w -> w.contains("gaux1")
                        && w.contains("两种类型")),
                "跨步类型冲突必须点名，实测：" + snapshot.planWarnings());
    }

    @Test
    @DisplayName("兜底/关包路径必须把快照清干净（QD-12：上一张包的声明不许留着）")
    void resetClearsPreviousPackState() {
        PackSamplerSuperset.install("uniform sampler2D colortex0;\n", null, null);
        assertTrue(PackSamplerSuperset.current().derivedFromPack());
        PackSamplerSuperset.reset();
        PackSamplerSuperset.Snapshot snapshot = PackSamplerSuperset.current();
        assertFalse(snapshot.derivedFromPack());
        assertEquals(List.of(), snapshot.outsideSuperset());
        // 布局条目与快照是两件事：复位只清快照，超集恒定。
        assertEquals(PackSamplerSuperset.NAMES, PackSamplerSuperset.layoutNames());
    }
}
