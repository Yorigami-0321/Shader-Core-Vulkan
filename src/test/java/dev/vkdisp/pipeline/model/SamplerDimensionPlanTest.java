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

    // ── h39：名字只选来源，声明类型约束维度 ───────────────────────────────

    @Test
    @DisplayName("🔖🔖 h39：名字**不得**压过非 2D 的声明类型（sampler3D shadowtex0 是 GAP-012 同类 bug）")
    void nameRuleMustNotOverrideNon2DDeclaredType() {
        // 旧实现会让名字无条件生效 ⇒ 一张 2D 深度图被喂给 sampler3D = 描述符类型不匹配。
        SamplerDimensionPlan.Plan plan = SamplerDimensionPlan.fromDeclaredTypes(
                Map.of("shadowtex0", "sampler3D"));
        assertEquals(SamplerDimensionPlan.ViewKind.VOLUME_3D, plan.kindOf("shadowtex0"),
                "sampler3D shadowtex0 必须落到 3D 决策路径，而不是被名字规则塞一张 2D 深度桩");
    }

    @Test
    @DisplayName("🔖🔖 h39：cube 声明同样不得被名字规则救回来（宁可响亮失败）")
    void nameRuleMustNotRescueCubeDeclaration() {
        SamplerDimensionPlan.Plan plan = SamplerDimensionPlan.fromDeclaredTypes(
                Map.of("texture_0", "samplerCube", "shadowcolor0", "samplerCubeShadow"));
        assertEquals(SamplerDimensionPlan.ViewKind.UNSUPPORTED, plan.kindOf("texture_0"),
                "samplerCube texture_0 必须按 cube 处理（UNSUPPORTED），"
                        + "不能因为名字是 texture_0 就绑方块图集");
        assertEquals(SamplerDimensionPlan.ViewKind.UNSUPPORTED, plan.kindOf("shadowcolor0"),
                "samplerCubeShadow 同样不得被名字规则绑成 RGBA 桩");
    }

    @Test
    @DisplayName("🔖 h39：2D 声明与裸 sampler 的既有行为**不变**（名字规则照常生效）")
    void twoDAndBareDeclarationsKeepNameRule() {
        SamplerDimensionPlan.Plan plan = SamplerDimensionPlan.fromDeclaredTypes(
                Map.of("shadowtex0", "sampler2DShadow",
                        "shadowtex1", "sampler2D",
                        "texture_0", "sampler",
                        "specular", "sampler2D"));
        assertEquals(SamplerDimensionPlan.ViewKind.SHADOW_DEPTH_2D, plan.kindOf("shadowtex0"),
                "sampler2DShadow shadowtex0 仍绑 1x1 D32 深度桩（Vulkan 允许非比较方式采样深度图）");
        assertEquals(SamplerDimensionPlan.ViewKind.SHADOW_DEPTH_2D, plan.kindOf("shadowtex1"),
                "sampler2D shadowtex1 同样走深度桩：阴影贴图**本就是**深度纹理，"
                        + "按 OF 语义而不是按 GLSL 后缀改语义");
        assertEquals(SamplerDimensionPlan.ViewKind.ATLAS_2D, plan.kindOf("texture_0"),
                "裸 sampler（维度未知）不该被剥夺名字规则 —— 不猜维度（X9）但也不改既有行为");
        assertEquals(SamplerDimensionPlan.ViewKind.NEUTRAL_MATERIAL_2D, plan.kindOf("specular"));
    }

    @Test
    @DisplayName("🔴🔴 h40：数组纹理采样器必须**不绑**（拿 2D 图冒充数组纹理 = GAP-012/014 同族静默 UB）")
    void arrayDeclarationMustNotBindA2DPlaceholder() {
        SamplerDimensionPlan.Plan plan = SamplerDimensionPlan.fromDeclaredTypes(
                Map.of("arr", "sampler2DArray", "arrShadow", "sampler2DArrayShadow"));
        assertEquals(SamplerDimensionPlan.ViewKind.UNSUPPORTED, plan.kindOf("arr"),
                "sampler2DArray 不得绑普通 2D 图：数组采样器要求 Arrayed=1 的图像视图，"
                        + "喂 2D 图是描述符类型不匹配 = 静默 UB（本机无 validation layer ⇒ 不报错）");
        assertEquals(SamplerDimensionPlan.ViewKind.UNSUPPORTED, plan.kindOf("arrShadow"),
                "sampler2DArrayShadow 同理（且它还叠加了 GAP-015 的比较采样器问题）");
        assertTrue(plan.warnings().stream().anyMatch(w -> w.contains("arr") && w.contains("数组纹理")),
                "必须留下可见告警（T11：降级要可见）—— 实测：" + plan.warnings());
    }

    @Test
    @DisplayName("🔖 h40：数组纹理与 cube / sampler3D 同等待遇（三者都不得绑）")
    void arrayCubeAnd3DShareTheSameTreatment() {
        SamplerDimensionPlan.Plan plan = SamplerDimensionPlan.fromDeclaredTypes(
                Map.of("a", "sampler2DArray", "b", "samplerCube", "c", "sampler3D"));
        assertEquals(SamplerDimensionPlan.ViewKind.UNSUPPORTED, plan.kindOf("a"));
        assertEquals(SamplerDimensionPlan.ViewKind.UNSUPPORTED, plan.kindOf("b"));
        // sampler3D 走 VOLUME_3D：有类型匹配的桩（虽然原版建不出 ⇒ 由 GAP-014 侧记录降级）
        assertEquals(SamplerDimensionPlan.ViewKind.VOLUME_3D, plan.kindOf("c"),
                "sampler3D 仍然声明 VOLUME_3D 决策，只是原版建不出来（GAP-014）"
                        + " —— 决策层与「能不能建」是两件事，不要混为一谈");
    }

    @Test
    @DisplayName("🔖🔖 h40：判定依据是「在本包地形程序里出现几次」，不是「类型像不像」")
    void theCriterionIsOccurrenceCountNotTypeSimilarity() {
        // 同一个「建不出」的家族，处置可以不同 —— 因为判据是出现次数：
        //   shadowtex0/1 在 BSL 地形程序里 SHADOW_DEPTH_2D=2（每种配置都在）⇒ 保留绑定 + 明示
        //   sampler2DArray 在 BSL 全部 274 个着色器源里 0 次            ⇒ 不绑
        SamplerDimensionPlan.Plan plan = SamplerDimensionPlan.fromDeclaredTypes(
                Map.of("shadowtex0", "sampler2DShadow", "arr", "sampler2DArray"));
        assertEquals(SamplerDimensionPlan.ViewKind.SHADOW_DEPTH_2D, plan.kindOf("shadowtex0"),
                "每种配置都在 ⇒ 保留绑定（GAP-015，已有一次性 WARN 明示）");
        assertEquals(SamplerDimensionPlan.ViewKind.UNSUPPORTED, plan.kindOf("arr"),
                "出现 0 次 ⇒ 不绑（GAP-012/014 口径）");
    }

    @Test
    @DisplayName("🔖 h40：普通 2D 采样器的占位行为**不变**（本条只动数组）")
    void plain2DPlaceholderUnchanged() {
        SamplerDimensionPlan.Plan plan = SamplerDimensionPlan.fromDeclaredTypes(
                Map.of("a", "sampler2D", "b", "sampler2DShadow"));
        assertEquals(SamplerDimensionPlan.ViewKind.PLACEHOLDER_2D, plan.kindOf("a"));
        assertEquals(SamplerDimensionPlan.ViewKind.PLACEHOLDER_2D, plan.kindOf("b"),
                "sampler2DShadow 走 2D 占位分支是既有行为，本条不动它"
                        + "（名字命中的 shadowtex* 仍绑深度桩，见 byName）");
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // GAP-023 / GAP-025：OF 家族名前缀必须走**专用来源**，不许落图集占位
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 GAP-025：noisetex 必须走噪声选源，不是方块图集")
    void noisetexIsNotAnAtlasPlaceholder() {
        SamplerDimensionPlan.Plan plan = SamplerDimensionPlan.fromDeclaredTypes(
                Map.of("noisetex", "sampler2D"));
        assertEquals(SamplerDimensionPlan.ViewKind.NOISE_2D, plan.kindOf("noisetex"),
                "noisetex 落 PLACEHOLDER_2D（方块图集）时，包把图集当噪声用 —— "
                        + "不报错、画面里是频率完全不对的假噪声（GAP-025）");
        // 🔖 BSL 的地形与水**都**声明它 ⇒ 这条不是「接水才有」的问题，是既有静默错。
        assertEquals(SamplerDimensionPlan.ViewKind.PLACEHOLDER_2D,
                SamplerDimensionPlan.fromDeclaredTypes(Map.of("unknownTex", "sampler2D"))
                        .kindOf("unknownTex"),
                "对照组：非家族名仍走 2D 占位（本条只动家族名）");
    }

    @Test
    @DisplayName("🔴 GAP-023：depthtex0/1/2（前缀家族）走深度快照，不是方块图集")
    void depthTexFamilyGetsDepthSnapshots() {
        for (String name : List.of("depthtex0", "depthtex1", "depthtex2", "depthtex3")) {
            assertEquals(SamplerDimensionPlan.ViewKind.DEPTH_SNAPSHOT_2D,
                    SamplerDimensionPlan.fromDeclaredTypes(Map.of(name, "sampler2D")).kindOf(name),
                    name + " 必须走深度快照 —— 落图集占位时包拿方块图集当深度读，"
                            + "水的 z1 > z0 那类判据全假而不报错");
        }
        // 🔖 家族名只看**前缀**，与链侧 FrameApi 的 startsWith("depthtex") 同口径 ——
        //   否则同一个名字在两条链上给出不同答案。
        assertEquals(SamplerDimensionPlan.ViewKind.DEPTH_SNAPSHOT_2D,
                SamplerDimensionPlan.fromDeclaredTypes(Map.of("depthtex99", "sampler2D"))
                        .kindOf("depthtex99"));
    }

    @Test
    @DisplayName("🔴 gauxN：与链侧 FrameApi 同口径（不是图集）")
    void gauxFamilyMatchesChainSideSemantics() {
        assertEquals(SamplerDimensionPlan.ViewKind.GAUX_2D,
                SamplerDimensionPlan.fromDeclaredTypes(Map.of("gaux1", "sampler2D")).kindOf("gaux1"),
                "链侧 FrameApi 把 gaux* 绑到 colortex —— gbuffer 侧绑成图集 = 同一名字两个答案");
        assertEquals(SamplerDimensionPlan.ViewKind.GAUX_2D,
                SamplerDimensionPlan.fromDeclaredTypes(Map.of("gaux2", "sampler2D")).kindOf("gaux2"));
    }

    @Test
    @DisplayName("🔴 真 BSL 清单：地形 5 个 / 水 8 个自由 sampler 逐条落到正确来源，且零「不绑」")
    void realBslSamplerListsLandOnCorrectSources() {
        // 夹具 = 实测的真实清单（名字是事实，源码零复制）。
        //   地形（与 PackTerrainSourceTest 同源）：texture_0 / noisetex / shadowtex0 / shadowtex1 / shadowcolor0
        //   水（GAP-027 实测）：      + gaux1 / gaux2 / depthtex1
        Map<String, String> terrain = new LinkedHashMap<>();
        for (String n : List.of("texture_0", "noisetex", "shadowtex0", "shadowtex1", "shadowcolor0")) {
            terrain.put(n, n.startsWith("shadow") ? "sampler2DShadow" : "sampler2D");
        }
        SamplerDimensionPlan.Plan t = SamplerDimensionPlan.fromDeclaredTypes(terrain);
        assertEquals(SamplerDimensionPlan.ViewKind.ATLAS_2D, t.kindOf("texture_0"));
        assertEquals(SamplerDimensionPlan.ViewKind.NOISE_2D, t.kindOf("noisetex"),
                "地形也读 noisetex ⇒ GAP-025 不是「接水才有」的问题");
        assertEquals(SamplerDimensionPlan.ViewKind.SHADOW_DEPTH_2D, t.kindOf("shadowtex0"));
        assertEquals(SamplerDimensionPlan.ViewKind.SHADOW_DEPTH_2D, t.kindOf("shadowtex1"));
        assertEquals(SamplerDimensionPlan.ViewKind.SHADOW_COLOR_2D, t.kindOf("shadowcolor0"));
        assertEquals(0, t.unsupportedNames().size(),
                "地形清单不许有「不绑」—— 一条不绑 = 每个 draw 抛 Missing uniform = 地形整条不渲染");

        Map<String, String> water = new LinkedHashMap<>(terrain);
        water.put("gaux1", "sampler2D");
        water.put("gaux2", "sampler2D");
        water.put("depthtex1", "sampler2D");
        SamplerDimensionPlan.Plan w = SamplerDimensionPlan.fromDeclaredTypes(water);
        assertEquals(SamplerDimensionPlan.ViewKind.GAUX_2D, w.kindOf("gaux1"));
        assertEquals(SamplerDimensionPlan.ViewKind.GAUX_2D, w.kindOf("gaux2"));
        assertEquals(SamplerDimensionPlan.ViewKind.DEPTH_SNAPSHOT_2D, w.kindOf("depthtex1"),
                "水自带 depthtex1：分槽尚未实现（GAP-023），但至少不许是图集");
        assertEquals(0, w.unsupportedNames().size(), "水清单同样不许有「不绑」");
    }
}
