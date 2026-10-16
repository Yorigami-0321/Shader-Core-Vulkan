package dev.vkdisp.glsl.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vkdisp.pipeline.model.PackTerrainProgram;
import dev.vkdisp.pack.PackTerrainSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GAP-007 导数接管的守卫。
 *
 * <p>🔖 <b>为什么每条都要能跑</b>：这一格砸的是「<b>改了但只改一半</b>」——预处理器不认作用域，
 * 少包一处就把包的某个读点留在旧的常量法线上，而那种画面（一层高光对得上、一层波纹对不上）
 * 在日志里是全绿的。⇒ 守卫必须覆盖：改名数与契约数相符、屏蔽跨度算得出来、全局块在<b>第一个读点之前</b>、
 * 以及见到罩不住的形态时<b>整条退回</b>。
 */
class DerivativeTbnTest {

    private static final Path INVENTORY = Path.of("run/shaderpacks");

    private static PackTerrainProgram.Input in(int location, String type, String name) {
        return new PackTerrainProgram.Input(location, type, name);
    }

    /** BSL 水的 in 签名里那三条基向量（实测 location 4/5/6）。 */
    private static List<PackTerrainProgram.Input> waterLike() {
        return List.of(in(0, "float", "mat"), in(4, "vec3", "normal"), in(5, "vec3", "binormal"),
                in(6, "vec3", "tangent"), in(13, "vec4", "vTexCoordAM"));
    }

    /** 一条最小片元：三条基向量在<b>同一行</b>声明（BSL 终稿的真实形态），一个读全局的辅助函数，main。 */
    private static String fragment(String body) {
        return """
                #version 330
                #extension GL_ARB_separate_shader_objects : require

                """ + body + """

                void main() {
                    fragColor = vec4(normalize(normal).x);
                }
                """;
    }

    private static final String THREE_INPUTS_ON_ONE_LINE = """
            layout(location = 0) out vec4 fragColor;
            layout(location = 4) in vec3 normal; layout(location = 5) in vec3 binormal; layout(location = 6) in vec3 tangent;
            """;

    @Test
    @DisplayName("🔴 闸门关着 / 没声明基向量 / 只有切线没有法线 / location 越界 ⇒ 四种都不做")
    void planRefusesWhenPremisesMissing() {
        assertFalse(DerivativeTbn.plan(waterLike(), false).applies(),
                "mrt.derivativeTbn=false ⇒ 必须逐字等于今天（退回常量供值）");
        assertFalse(DerivativeTbn.plan(List.of(in(0, "vec2", "texCoord")), true).applies(),
                "片元没声明那三条 ⇒ 没有可接管的东西");
        assertFalse(DerivativeTbn.plan(List.of(in(0, "vec3", "tangent")), true).applies(),
                "没有 normal 就没有面法线，接管无从谈起");
        PackTerrainProgram.Input[] full = new PackTerrainProgram.Input[15];
        for (int i = 0; i < 14; i++) {
            full[i] = in(i, "float", "v" + i);
        }
        full[14] = in(14, "vec3", "normal");
        assertFalse(DerivativeTbn.plan(List.of(full), true).applies(),
                "补两条辅助 varying 会越过 16 个 location ⇒ 宁可不做，也不在注册期硬抛");
        DerivativeTbn.Plan plan = DerivativeTbn.plan(waterLike(), true);
        assertTrue(plan.applies());
        assertEquals(List.of("normal", "tangent", "binormal"), plan.substituted());
        assertEquals(14, plan.viewPositionLocation(), "BSL 水用到 13 ⇒ 辅助两条顺延到 14/15");
        assertEquals(15, plan.uvLocation());
    }

    @Test
    @DisplayName("🔴 三条声明写在<b>同一行</b>（BSL 终稿形态）必须全部改名，少认一条就整条退回")
    void multiDeclarationLineRenamingIsAllOrNothing() {
        DerivativeTbn.Result r = DerivativeTbn.apply("world0/gbuffers_water",
                fragment(THREE_INPUTS_ON_ONE_LINE), DerivativeTbn.plan(waterLike(), true));
        assertTrue(r.applied(), () -> "该落地的情形被拒了：\n" + r.diagnostics());
        assertTrue(r.text().contains("in vec3 vkdispInterpNormal;"),
                () -> "normal 的声明必须改名（保住 location 4，包读点经 #define 走我方函数）；实际:\n" + r.text());
        assertTrue(r.text().contains("in vec3 vkdispInterpBinormal;"));
        assertTrue(r.text().contains("in vec3 vkdispInterpTangent;"));
        assertFalse(r.text().contains("in vec3 normal;"), "不许留下没人改的第二种形态（半改 = 最难查的一类）");

        // 契约说有、源里认不出来 ⇒ 半改一定坏 ⇒ 整条退回原文。
        DerivativeTbn.Result halfWired = DerivativeTbn.apply("world0/gbuffers_water",
                fragment("layout(location = 4) in vec3 normal;\n"),
                DerivativeTbn.plan(waterLike(), true));
        assertFalse(halfWired.applied(), "只认出 1/3 条声明时必须拒绝，而不是改到一半");
        assertTrue(halfWired.diagnostics().stream().anyMatch(d -> d.severity().isError()
                && d.message().contains("整条不做")), () -> "拒绝必须点名：\n" + halfWired.diagnostics());
    }

    @Test
    @DisplayName("🔴 全局块必须落在<b>第一个读点之前</b>（预处理是顺序的，落在 main 前就漏掉上面的函数）")
    void globalBlockPrecedesFirstUseSite() {
        String source = fragment(THREE_INPUTS_ON_ONE_LINE + """
                vec3 GetWaterNormal(vec3 worldPos, vec3 viewPos, vec3 viewVector) {
                    float fresnel = pow(clamp(1.0 + dot(normalize(normal), normalize(viewPos)), 0.0, 1.0), 8.0);
                    return vec3(fresnel);
                }
                """);
        DerivativeTbn.Result r = DerivativeTbn.apply("world0/gbuffers_water", source,
                DerivativeTbn.plan(waterLike(), true));
        assertTrue(r.applied(), () -> "该落地的情形被拒了：\n" + r.diagnostics());
        int define = r.text().indexOf("#define normal vkdispTbnNormal()");
        int reader = r.text().indexOf("GetWaterNormal(vec3 worldPos");
        assertTrue(define >= 0 && reader > define,
                "包在文件作用域的函数里读 normal（BSL 的 GetWaterNormal 逐字如此）⇒ #define 必须在它之前，"
                        + "否则那一处仍是旧的常量法线 = 「改了但只改一半」。define=" + define + " reader=" + reader);
        // 辅助 varying 的声明也得在读点之前（GLSL 不许用未声明的名字）。
        assertTrue(r.text().indexOf("in vec3 " + DerivativeTbn.VIEW_POSITION + ";") < reader,
                () -> "辅助 in 声明位置不对；实际:\n" + r.text());
    }

    @Test
    @DisplayName("🔴 参数表里声明了同名变量的函数必须整段 #undef/#define 包住（否则声明本身被换掉）")
    void parameterShadowingFunctionsAreGuarded() {
        String source = fragment(THREE_INPUTS_ON_ONE_LINE + """
                vec3 GetReflectedCameraPos(vec3 worldPos, vec3 normal) {
                    return normal * 2.0;
                }

                vec4 BasicReflect(vec3 viewPos, vec3 normal, out float border) {
                    border = dot(normal, normal);
                    return vec4(border);
                }
                """);
        DerivativeTbn.Result r = DerivativeTbn.apply("world0/gbuffers_water", source,
                DerivativeTbn.plan(waterLike(), true));
        assertTrue(r.applied(), () -> "该落地的情形被拒了：\n" + r.diagnostics());
        assertEquals(2, r.guardedFunctions(),
                () -> "两个函数各自声明了 vec3 normal 参数 ⇒ 都得被包住；实际文本:\n" + r.text());
        assertTrue(r.text().contains("vec3 GetReflectedCameraPos(vec3 worldPos, vec3 normal)"),
                () -> "参数声明不许被宏换掉（那是声明，不是读点）；实际:\n" + r.text());
        assertTrue(r.text().contains("vec4 BasicReflect(vec3 viewPos, vec3 normal, out float border)"),
                () -> "同上；实际:\n" + r.text());
        int undef = r.text().indexOf("#undef normal");
        int defineAgain = r.text().indexOf("#define normal vkdispTbnNormal()", undef + 1);
        assertTrue(undef >= 0 && defineAgain > undef,
                "每个屏蔽函数必须是「进来 #undef、出去 #define 回来」，否则后面的函数全丢接管");
        // 屏蔽段之内不许出现被换掉的读点：`normal * 2.0` 必须原样（那是包的参数）。
        assertTrue(r.text().contains("return normal * 2.0;"),
                () -> "函数体内的同名参数读点必须留给包自己算；实际:\n" + r.text());
    }

    @Test
    @DisplayName("🔴 成员访问（data.normal）与别的同名声明 ⇒ 预处理器罩不住 ⇒ 整条不做")
    void memberAccessAndOtherDeclaratorsRefuseWholesale() {
        DerivativeTbn.Result member = DerivativeTbn.apply("world0/gbuffers_water",
                fragment(THREE_INPUTS_ON_ONE_LINE + """
                        float GetSpec(vec4 data) {
                            return data.normal.x;
                        }
                        """),
                DerivativeTbn.plan(waterLike(), true));
        assertFalse(member.applied(), "成员访问会被 #define 一起换掉 ⇒ 必须整条不做");
        assertTrue(member.diagnostics().stream().anyMatch(d -> d.severity().isError()
                && d.message().contains("成员访问")), () -> "拒绝要点名形态：\n" + member.diagnostics());

        DerivativeTbn.Result uniform = DerivativeTbn.apply("world0/gbuffers_water",
                fragment(THREE_INPUTS_ON_ONE_LINE + "uniform vec3 tangent;\n"),
                DerivativeTbn.plan(waterLike(), true));
        assertFalse(uniform.applied(), "别的声明形态（uniform 同名）同样罩不住");

        DerivativeTbn.Result empty = DerivativeTbn.apply("world0/gbuffers_water", "   ",
                DerivativeTbn.plan(waterLike(), true));
        assertFalse(empty.applied(), "空源必须判否，不许回一个「已改写」的假成功");
    }

    @Test
    @DisplayName("🔖 顶点侧的辅助两条必须与片元侧同源：不适用时一行都不加")
    void vertexSideFollowsTheSamePlan() {
        DerivativeTbn.Plan off = DerivativeTbn.plan(List.of(in(0, "vec2", "texCoord")), true);
        assertEquals("", DerivativeTbn.vertexDeclarations(off), "不适用时不许产生空行噪声（源文本要逐字可比）");
        assertEquals("", DerivativeTbn.vertexAssignments(off));

        DerivativeTbn.Plan on = DerivativeTbn.plan(waterLike(), true);
        String decls = DerivativeTbn.vertexDeclarations(on);
        assertTrue(decls.contains("layout(location = 14) out vec3 " + DerivativeTbn.VIEW_POSITION + ";"),
                () -> "location 必须与片元侧那次判定同值；实际: " + decls);
        assertTrue(decls.contains("layout(location = 15) out vec2 " + DerivativeTbn.AUX_UV + ";"));
        // 🔴 两条都只取适配层已经算过的量 ⇒ 接管不给渲染期添新计算
        assertTrue(DerivativeTbn.vertexAssignments(on).contains("= vkdispAdapterViewPos;"),
                "视空间位置取适配层已有的 vkdispAdapterViewPos");
        assertTrue(DerivativeTbn.vertexAssignments(on).contains("= UV0;"), "uv 取原版 UV0");
    }

    @Test
    @DisplayName("🔴 适配层真产物：接了导数接管就必须把那两条 out 写进顶点源（两侧缺一即半接线）")
    void adapterEmitsAuxVaryingsForTheSamePlan() {
        DerivativeTbn.Plan on = DerivativeTbn.plan(waterLike(), true);
        PackVertexAdapterGenerator.Result withTbn =
                PackVertexAdapterGenerator.generate(waterLike(), false, false, false, on);
        assertTrue(withTbn.glsl().contains("out vec3 " + DerivativeTbn.VIEW_POSITION),
                () -> "顶点侧缺这两条 ⇒ 片元读到的是未定义输入；实际:\n" + withTbn.glsl());
        assertTrue(withTbn.glsl().contains("out vec2 " + DerivativeTbn.AUX_UV));
        assertTrue(withTbn.glsl().indexOf("out vec3 " + DerivativeTbn.VIEW_POSITION)
                        < withTbn.glsl().indexOf("void main()"),
                "声明必须在 main 之前（GLSL 不许用未声明的名字）");

        PackVertexAdapterGenerator.Result withoutTbn =
                PackVertexAdapterGenerator.generate(waterLike(), false, false, false, null);
        assertFalse(withoutTbn.glsl().contains(DerivativeTbn.VIEW_POSITION),
                "闸门关着 ⇒ 适配层逐字等于今天（这条是「关掉能退回」的硬条件）");
        assertFalse(withoutTbn.glsl().contains(DerivativeTbn.AUX_UV));
    }

    @Test
    @DisplayName("🔬 真包证明：BSL v10.1.8 的水走完整生成期后，落进包资源的那份片元已被接管")
    void realBslWaterIsRewrittenByTheGenerationPath() {
        Assumptions.assumeTrue(Files.isDirectory(INVENTORY), "库存目录不在本地（run/shaderpacks/）");
        PackTerrainSource.Result off = PackTerrainSource.generate(
                INVENTORY, "", "", null, PackTerrainSource.WATER_PROGRAM);
        Assumptions.assumeTrue(off.wired(), "本机没选出水片元，跳过真包对账");
        assertFalse(off.linkedFragment() != null,
                "闸门默认关 ⇒ 落进资源的必须是**未改写**的那一份（「关掉逐字等于今天」的硬条件）");
        assertTrue(off.tbnPlan() != null && !off.tbnPlan().applies(),
                () -> "关档时判定必须存在且判否；实际: " + off.tbnPlan());

        dev.vkdisp.pack.DerivativeTbnSwitch.override(() -> true);
        try {
            PackTerrainSource.Result on = PackTerrainSource.generate(
                    INVENTORY, "", "", null, PackTerrainSource.WATER_PROGRAM);
            assertTrue(on.wired(), "开档不该影响选包本身");
            PackTerrainProgram program = on.program();
            assertTrue(on.tbnPlan().applies(),
                    () -> "BSL 水的契约应当满足前提；实际判定: " + on.tbnPlan().refusal());
            assertEquals(List.of("normal", "tangent", "binormal"), on.tbnPlan().substituted(),
                    "BSL 水无条件声明这三条（实测 in 签名 location 4/5/6）");
            String rewritten = on.linkedFragment();
            assertTrue(rewritten != null, "开档必须产出改写后的那一份（否则「写了但没落地」）");
            assertTrue(rewritten.contains("#define binormal vkdispTbnBinormal()"),
                    () -> "三条都要有指向；实际:\n" + head(rewritten));
            assertTrue(rewritten.contains("cross(dFdx(" + DerivativeTbn.VIEW_POSITION + "), dFdy("),
                    "面法线必须来自位置偏导（chunk 面是轴对齐平面 ⇒ 这就是真法线，不是近似）");
            assertFalse(rewritten.contains("in vec3 normal;"),
                    "包的三条声明必须都改名（留一条 = 那个通道还是常量）");
            // 🔖 改写只加不减：不许丢包的文本。
            assertTrue(rewritten.length() > program.fragmentSource().length(),
                    "改写是「加东西」，比原文短就是删了包的东西");
            // 契约必须仍从**未改写**的那一份解析（location/输出槽不许被接管动到）。
            assertEquals(List.of(0, 1), program.declaredOutputSlots(),
                    "接管不许改包的输出槽声明（那是 pass 附件数的依据）");
        } finally {
            dev.vkdisp.pack.DerivativeTbnSwitch.override(null);
        }
    }

    private static String head(String text) {
        return text.substring(0, Math.min(text.length(), 3000));
    }

    @Test
    @DisplayName("🔖 接线守卫：改写与适配层必须吃**同一个 plan**，且 pack 侧不许直引 FML 配置")
    void wiringIsSingleSourcedAndFmlFree() throws IOException {
        String pack = Files.readString(
                Path.of("src/main/java/dev/vkdisp/VkDispVirtualPack.java"));
        // 🔖 位置针之前先 contains：needle 消失会让 indexOf 回 -1，而 -1 < 任何下标 = 假绿。
        assertTrue(pack.contains("terrain.tbnPlan()"),
                "落进包资源的顶点侧必须按同一次判定补那两条 out（各算一遍 location 就会漂移）");
        assertTrue(pack.contains("terrain.linkedFragment() != null"),
                "改写后的那一份必须优先落资源，否则「片元被改写、资源里还是旧的」");
        assertTrue(pack.indexOf("String fragment = terrain.linkedFragment() != null")
                        < pack.indexOf("chunkAdapter(entry.program, program,"),
                "先定片元、再产顶点适配层（顺序倒了的话 plan 还没算出来就被顶点侧用了）");

        String source = Files.readString(
                Path.of("src/main/java/dev/vkdisp/pack/PackTerrainSource.java"));
        assertTrue(source.contains("DerivativeTbnSwitch.enabled()"),
                "生成期读开关必须走反射桥接（直引 VkDispConfig 会在单测里抛 Cannot get config value…，本轮真踩到）");
        assertFalse(source.contains("VkDispConfig.MRT_DERIVATIVE_TBN"),
                "dev.vkdisp.pack 不该把整个 FML 配置体系拖进测试类路径（PackPostVertexSwitch 类注释第 2 条同族）");

        String hotReload = Files.readString(
                Path.of("src/main/java/dev/vkdisp/VkDispConfigHotReload.java"));
        assertTrue(hotReload.contains("MRT_DERIVATIVE_TBN.get()"),
                "生成期读的键必须进边沿判定快照，否则它是「写了但没生效」的死开关（QD-08 那一族）");
        String memoKey = pack.substring(pack.indexOf("private static String currentTerrainMemoKey()"));
        assertTrue(memoKey.contains("MRT_DERIVATIVE_TBN.get()"),
                "同上：必须进契约 memo 键（造键与校验同一份算法）");
    }

    @Test
    @DisplayName("🔖 地形默认档（BSL）只声明 normal ⇒ 接管只接那一条，切线/副切线一行都不许碰")
    void terrainDefaultTakesOverOnlyTheFaceNormal() {
        Assumptions.assumeTrue(Files.isDirectory(INVENTORY), "库存目录不在本地（run/shaderpacks/）");
        dev.vkdisp.pack.DerivativeTbnSwitch.override(() -> true);
        try {
            PackTerrainSource.Result terrain = PackTerrainSource.generate(INVENTORY, "", "");
            Assumptions.assumeTrue(terrain.wired(), "本机没选出地形片元，跳过");
            List<String> declared = terrain.program().inputs().stream()
                    .map(PackTerrainProgram.Input::name).toList();
            // 🔴 实测（本轮读出来的，不是假设）：BSL 默认档地形 9 条 varying 里<b>有</b> normal（location 4）、
            //   <b>没有</b> tangent/binormal（那两条坐在 ADVANCED_MATERIALS 档里）。
            assertTrue(declared.contains("normal"), () -> "前提变了要重读这条守卫；实际 inputs=" + declared);
            assertFalse(declared.contains("tangent"), () -> "默认档不该有切线声明；实际=" + declared);

            DerivativeTbn.Plan plan = DerivativeTbn.plan(terrain.program().inputs(), true);
            assertTrue(plan.applies(), "地形默认档有 normal ⇒ 面法线这一条该接管（GAP-007 的地形那一半）");
            assertEquals(List.of("normal"), plan.substituted(),
                    "只接管真声明过的那一条 —— 多接就是凭空发明接口");

            String rewritten = terrain.linkedFragment();
            assertTrue(rewritten != null && rewritten.contains("#define normal vkdispTbnNormal()"),
                    () -> "normal 的读点必须被指向；实际:\n" + head(rewritten));
            assertFalse(rewritten.contains("#define tangent"),
                    "包没声明 tangent ⇒ 不许凭空造一条 #define（那会换掉包里同名的局部/参数）");
            assertFalse(rewritten.contains("#define binormal"), "同上");
        } finally {
            dev.vkdisp.pack.DerivativeTbnSwitch.override(null);
        }
    }
}
