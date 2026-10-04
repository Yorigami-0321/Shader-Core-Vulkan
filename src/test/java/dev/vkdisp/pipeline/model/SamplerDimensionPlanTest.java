package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】sampler 维度绑定决策单测 / 修「sampler3D 被喂 2D 视图」的 Vulkan UB
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库自有的 {@link PackTerrainProgram}（同层解析口径：只看转译终稿、
 *    跳过块内成员）与 GLSL / Vulkan 的维度语义事实（不受版权保护）。
 *    实测依据 = BSL v10.1.8 全包 sampler 声明统计（sampler3D 共 4 个：
 *    lighttex / lighttex0 / lighttex1 / voxeltex）—— **该数字来自扫 zip 的统计，
 *    本测试不复制包的任何源码文本**，只用这 4 个**名字**作为夹具（名字是事实，不是版权内容）。
 *    → 能否并入本项目（MIT）：可以（测试代码不进分发 jar）
 *    → 例外条款：无
 * 1. 官方/主实现：无（本类为本项目自研的决策层）。
 * 2. 备选：无。
 * 3. 我们的差异点（每条测试钉一个具体主张）：
 *    <ul>
 *      <li>🔴 <b>sampler3D 必须拿到 3D 视图</b>，绝不能是 2D 占位（这就是本轮修的 UB）。</li>
 *      <li>🔴 <b>维度来自声明的类型，不来自名字</b> —— 换个名字照样判对（X39）。</li>
 *      <li>🔴 <b>cube / 不认识的类型不绑</b>，宁可 Missing uniform 抛也不喂错维度。</li>
 *      <li>🔴 <b>同名两种类型</b>取先出现 + 警告（不静默取最后一个）。</li>
 *      <li>🔴 <b>块内成员不被当成 sampler</b>（VkDispBuiltins 的成员名不是自由声明）。</li>
 *    </ul>
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：测试代码不进运行时。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** sampler 维度绑定决策单测。 */
class SamplerDimensionPlanTest {

    /** 🔴 夹具：BSL 实测存在的 4 个 {@code sampler3D}（名字是事实，源码零复制）。 */
    private static final List<String> BSL_VOLUME_SAMPLERS =
            List.of("lighttex", "lighttex0", "lighttex1", "voxeltex");

    @Test
    @DisplayName("🔴 sampler3D ⇒ 3D 视图，绝不是 2D 占位（本轮修的 UB）")
    void sampler3dGetsVolumeStub() {
        String source = """
                uniform sampler2D texture_0;
                uniform sampler3D lighttex0;
                uniform sampler3D lighttex1;
                uniform sampler3D lighttex;
                uniform sampler3D voxeltex;
                layout(location = 0) out vec4 fragColor;
                """;
        SamplerDimensionPlan.Plan plan = SamplerDimensionPlan.fromFragmentSource(source);
        for (String name : BSL_VOLUME_SAMPLERS) {
            assertEquals(SamplerDimensionPlan.ViewKind.VOLUME_3D, plan.kindOf(name),
                    "sampler3D 的 '" + name + "' 必须绑 3D 视图；"
                            + "旧实现把它落到 default->atlas（2D）= 描述符类型不匹配 = Vulkan UB 且不报错");
            assertTrue(plan.binding(name).orElseThrow().bindable());
        }
    }

    @Test
    @DisplayName("🔴 维度来自声明的类型，不来自名字（换个名字照样判对，X39）")
    void dimensionComesFromDeclaredTypeNotName() {
        // 🔖 关键主张：不能靠「名字眼熟」判维度 —— 换个包用别的名字，维度仍必须正确。
        SamplerDimensionPlan.Plan plan = SamplerDimensionPlan.fromFragmentSource(
                "uniform sampler3D someVendorVolumeLookup;\n"
                        + "uniform sampler2D someVendorFlatLookup;\n"
                        + "layout(location = 0) out vec4 fragColor;\n");
        assertEquals(SamplerDimensionPlan.ViewKind.VOLUME_3D, plan.kindOf("someVendorVolumeLookup"));
        assertEquals(SamplerDimensionPlan.ViewKind.PLACEHOLDER_2D, plan.kindOf("someVendorFlatLookup"));
    }

    @Test
    @DisplayName("🔴 cube 采样器不绑（宁可 Missing uniform 抛，也不喂 2D 冒充）")
    void cubeSamplerIsUnbindable() {
        SamplerDimensionPlan.Plan plan = SamplerDimensionPlan.fromFragmentSource(
                "uniform samplerCube skyLookup;\nlayout(location = 0) out vec4 fragColor;\n");
        assertEquals(SamplerDimensionPlan.ViewKind.UNSUPPORTED, plan.kindOf("skyLookup"));
        assertFalse(plan.binding("skyLookup").orElseThrow().bindable());
        assertEquals(List.of("skyLookup"), plan.unsupportedNames());
        assertFalse(plan.warnings().isEmpty(), "不可绑必须有警告（否则调用方只会看到「少绑了一条」）");
    }

    @Test
    @DisplayName("🔴 不认识的 sampler 类型不猜维度（X9）")
    void unknownSamplerTypeIsUnbindable() {
        SamplerDimensionPlan.Plan plan = SamplerDimensionPlan.fromFragmentSource(
                "uniform sampler whatever;\nlayout(location = 0) out vec4 fragColor;\n");
        assertEquals(SamplerDimensionPlan.ViewKind.UNSUPPORTED, plan.kindOf("whatever"),
                "裸 sampler（无维度后缀）无法判断维度 ⇒ 不绑，绝不默认当 2D");
    }

    @Test
    @DisplayName("已命名的 2D 约定保持原语义（图集 / 中性材质 / 阴影桩）")
    void namedSamplersKeepTheirSemantics() {
        SamplerDimensionPlan.Plan plan = SamplerDimensionPlan.fromFragmentSource("""
                uniform sampler2D texture_0;
                uniform sampler2D specular;
                uniform sampler2D normals;
                uniform sampler2DShadow shadowtex0;
                uniform sampler2D shadowcolor0;
                layout(location = 0) out vec4 fragColor;
                """);
        assertEquals(SamplerDimensionPlan.ViewKind.ATLAS_2D, plan.kindOf("texture_0"));
        assertEquals(SamplerDimensionPlan.ViewKind.NEUTRAL_MATERIAL_2D, plan.kindOf("specular"));
        assertEquals(SamplerDimensionPlan.ViewKind.NEUTRAL_MATERIAL_2D, plan.kindOf("normals"));
        assertEquals(SamplerDimensionPlan.ViewKind.SHADOW_DEPTH_2D, plan.kindOf("shadowtex0"));
        assertEquals(SamplerDimensionPlan.ViewKind.SHADOW_COLOR_2D, plan.kindOf("shadowcolor0"));
    }

    @Test
    @DisplayName("🔴 同名两种类型 ⇒ 取先出现 + 警告（不静默取最后一个）")
    void duplicateTypeTakesFirstAndWarns() {
        SamplerDimensionPlan.Plan plan = SamplerDimensionPlan.fromFragmentSource(
                "uniform sampler3D dup;\nuniform sampler2D dup;\n"
                        + "layout(location = 0) out vec4 fragColor;\n");
        assertEquals("sampler3D", plan.binding("dup").orElseThrow().declaredType(),
                "取先出现的（结果可复算）；取最后一个会让「取哪条」变成偶然");
        assertTrue(plan.warnings().stream().anyMatch(w -> w.contains("两种类型")),
                "包自身的不一致必须可见：" + plan.warnings());
    }

    @Test
    @DisplayName("🔴 块内成员不被当成 sampler（VkDispBuiltins 的成员名）")
    void blockMembersAreNotSamplers() {
        SamplerDimensionPlan.Plan plan = SamplerDimensionPlan.fromFragmentSource("""
                layout(std140) uniform VkDispBuiltins {
                    vec4 cameraPosition;
                    float frameTimeCounter;
                };
                uniform sampler2D texture_0;
                layout(location = 0) out vec4 fragColor;
                """);
        assertFalse(plan.binding("cameraPosition").isPresent(),
                "块内成员是 uniform 成员，不是自由声明的 sampler");
        assertEquals(SamplerDimensionPlan.ViewKind.ATLAS_2D, plan.kindOf("texture_0"));
    }

    @Test
    @DisplayName("行注释与块注释里的 sampler 不计入")
    void commentedSamplersAreIgnored() {
        SamplerDimensionPlan.Plan plan = SamplerDimensionPlan.fromFragmentSource("""
                // uniform sampler3D commentedOut;
                /* uniform sampler3D alsoCommented; */
                uniform sampler2D texture_0;
                layout(location = 0) out vec4 fragColor;
                """);
        assertEquals(1, plan.bindings().size());
        assertEquals(SamplerDimensionPlan.ViewKind.ATLAS_2D, plan.kindOf("texture_0"));
    }

    @Test
    @DisplayName("一行多个声明全部认出（BSL 地形片元就是两两并排的写法）")
    void multipleDeclarationsPerLine() {
        SamplerDimensionPlan.Plan plan = SamplerDimensionPlan.fromFragmentSource(
                "uniform sampler2D a; uniform sampler3D b;\n"
                        + "layout(location = 0) out vec4 fragColor;\n");
        assertEquals(2, plan.bindings().size());
        assertEquals(SamplerDimensionPlan.ViewKind.PLACEHOLDER_2D, plan.kindOf("a"));
        assertEquals(SamplerDimensionPlan.ViewKind.VOLUME_3D, plan.kindOf("b"));
    }

    @Test
    @DisplayName("摘要按视图类别计数（绑定埋点用；不逐帧刷屏）")
    void summaryCountsByKind() {
        SamplerDimensionPlan.Plan plan = SamplerDimensionPlan.fromFragmentSource("""
                uniform sampler2D texture_0;
                uniform sampler2D specular;
                uniform sampler3D lighttex0;
                layout(location = 0) out vec4 fragColor;
                """);
        String summary = plan.summary();
        assertTrue(summary.contains("ATLAS_2D=1"), summary);
        assertTrue(summary.contains("NEUTRAL_MATERIAL_2D=1"), summary);
        assertTrue(summary.contains("VOLUME_3D=1"), summary);
    }

    @Test
    @DisplayName("未声明的 sampler 查询返回 UNSUPPORTED（不是静默 null）")
    void undeclaredLookupIsUnsupported() {
        SamplerDimensionPlan.Plan plan = SamplerDimensionPlan.fromFragmentSource(
                "layout(location = 0) out vec4 fragColor;\n");
        assertEquals(SamplerDimensionPlan.ViewKind.UNSUPPORTED, plan.kindOf("neverDeclared"));
        assertTrue(plan.unsupportedNames().isEmpty());
    }

    @Test
    @DisplayName("精度限定符不影响判定（lowp sampler3D 仍是 3D）")
    void precisionQualifierIsAccepted() {
        SamplerDimensionPlan.Plan plan = SamplerDimensionPlan.fromFragmentSource(
                "uniform lowp sampler3D lighttex0;\nlayout(location = 0) out vec4 fragColor;\n");
        assertEquals(SamplerDimensionPlan.ViewKind.VOLUME_3D, plan.kindOf("lighttex0"));
    }

    @Test
    @DisplayName("由「名字 → 类型」表构造（包级入口，供诊断与单测直接驱动）")
    void fromDeclaredTypesWorks() {
        Map<String, String> declared = new LinkedHashMap<>();
        declared.put("texture_0", "sampler2D");
        declared.put("lighttex0", "sampler3D");
        SamplerDimensionPlan.Plan plan = SamplerDimensionPlan.fromDeclaredTypes(declared);
        assertEquals(2, plan.bindings().size());
        assertEquals(SamplerDimensionPlan.ViewKind.VOLUME_3D, plan.kindOf("lighttex0"));
    }

    @Test
    @DisplayName("🔴 全不可绑时摘要仍可用（不会抛，便于打日志）")
    void allUnsupportedStillSummarizes() {
        SamplerDimensionPlan.Plan plan = SamplerDimensionPlan.fromFragmentSource(
                "uniform samplerCube a; uniform samplerCube b;\n"
                        + "layout(location = 0) out vec4 fragColor;\n");
        assertTrue(plan.summary().contains("UNSUPPORTED=2"), plan.summary());
        assertTrue(SamplerDimensionPlan.describe(plan).contains("warnings="));
    }
}
