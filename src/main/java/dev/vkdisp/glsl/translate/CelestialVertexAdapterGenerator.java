package dev.vkdisp.glsl.translate;
/**
 * 【自行补充】GAP-027 天空线第三刀：按包 {@code gbuffers_skytextured} 的契约生成<b>日月顶点适配层</b>
 * （输入是原版 {@code POSITION_TEX} 那条顶点流）/ 参考对象 = 本仓库自有的 {@link PackVertexAdapterGenerator}
 * 0. 合规核对（第 0 条闸门）：参考对象 = ① 本仓库自有 {@link PackVertexAdapterGenerator}
 *    （三档供值、逐位置逐名字对齐、供不出来就常量 + WARN 那一族形状）；
 *    ② 原版 26.3 的<b>格式事实</b>（只取「有哪些元素、叫什么名」这一层，不取任何着色器文本）：
 *    {@code DefaultVertexFormat.CELESTIAL} 那条顶点流是 {@code POSITION_TEX}
 *    （{@code RenderPipelines.java:1092} 的 {@code withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)}，
 *    元素名 {@code Position}/{@code UV0} —— {@code DefaultVertexFormat.java:78}）。
 *    外部候选（Iris / OptiFine / VulkanMod / Sulkan / Beryl）零接触（07-CONSTRAINTS L12）。
 *    许可证：独立编写（MIT），零第三方代码与零 Mojang 着色器文本。
 * 1. 官方/主实现：没有「按包契约给原版顶点流生成适配层」这回事（原版日月用 {@code core/position_tex}）。
 * 2. 备选：① 直接复用 {@link PackVertexAdapterGenerator} —— <b>否决</b>：它的属性表与位置算式是
 *       <b>chunk 那一族</b>的（{@code ChunkPosition}/{@code CameraBlockPos}/{@code CameraOffset}/{@code UV2}
 *       光照），而日月顶点流<b>一个都没有</b>：喂进去要么链接期抛（属性名查不到）要么把日月算成地表几何。
 *    ② 用包的 {@code .vsh} 走 post 恒等 MVP（天空穹顶那一族的做法）—— <b>否决</b>：{@code skytextured}
 *       的片元要读几何（uv 决定采图集哪一块），不像 {@code skybasic} 是纯屏幕空间；
 *    ③ 我方自开 pass 画日月 —— <b>否决</b>（见 {@code docs/13} GAP-028 的 10-16 行）：那样必须自己交
 *       天体姿态矩阵，而原版把它放在 {@code DynamicTransforms} 里、由 {@code SkyRenderer} 的 pose 链现算。
 *       ⇒ 本设计走「<b>让原版驱动 draw、只换管线</b>」，所以位置算式必须与原版 {@code core/position_tex}
 *       <b>同语义</b>（{@code ProjMat * ModelViewMat * Position}）而不是复制它的文本。
 * 3. 我们的差异点：
 *       ① <b>只有两条输入</b>（{@code Position}/{@code UV0}），其余一律由矩阵与内建量算出来；
 *       ② 🔴 {@code color} 只能中性供 {@code vec4(1.0)} —— {@code POSITION_TEX} <b>没有 Color 元素</b>，
 *          而包逐字写 {@code color = gl_Color;}。这不是猜：格式里没有这个通道 ⇒ 中性乘法单位元是唯一
 *          不编造数据的选法，并且<b>必须出 WARN 点名</b>（否则下一轮有人以为日月被原版色调过）；
 *       ③ 契约里出现<b>本生成器不认识</b>的名字 ⇒ 按类型零值 + 逐条 WARN，绝不「挑一个像的」（X9）。
 * 4. 许可证核对：本项目 MIT；零第三方代码并入。
 * 5. 性能基线：❄️ 冷路径（换包 / 换配置生成一次字符串）。渲染期与原版 {@code position_tex} 同形
 *    （一次 4 顶点 + 一次 6 索引的 draw 由原版发），不另加计算。
 */
import dev.vkdisp.glsl.TranslateDiagnostic;
import dev.vkdisp.pipeline.model.PackTerrainProgram;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 按包 {@code gbuffers_skytextured} 的 varying 契约，为原版 {@code POSITION_TEX} 顶点流生成适配层。 */
public final class CelestialVertexAdapterGenerator {

    /** 原版日月顶点流的两条属性（名字与 {@code DefaultVertexFormat.POSITION_TEX} 逐字一致）。 */
    private static final String ATTRIBUTES = """
            layout(location = 0) in vec3 Position;
            layout(location = 1) in vec2 UV0;
            """;

    private static final String NL = String.valueOf((char) 10);

    /** 头部：引擎的三个内建块（与 {@code GLOBALS}/{@code PROJECTION}/{@code DYNAMIC_TRANSFORMS} 一套布局同源）。 */
    private static final String HEADER = """
            #version 330
            #extension GL_ARB_separate_shader_objects : require

            // vkdisp 日月顶点适配层（按所选包 gbuffers_skytextured 的 varying 契约**自动生成**）
            //
            // 输入只有原版 POSITION_TEX 的两条属性；位置算式与原版 core/position_tex 同语义
            // （ProjMat * ModelViewMat * Position）—— 天体姿态由原版留在 DynamicTransforms 里，本层不重算。
            #include <minecraft:globals.glsl>
            #include <minecraft:projection.glsl>
            #include <minecraft:dynamictransforms.glsl>

            """ + ATTRIBUTES + """

            // GAP-004 的自定义块：眼空间太阳方向（块名必须与绑定组布局逐字一致）
            layout(std140) uniform VkDispTerrainParams {
                vec4 SunDir;
            };

            """;

    /** {@code main()} 开头：先把「这一帧这片几何的视空间位置」算出来，下面几条 varying 都从它取。 */
    private static final String MAIN_HEAD = NL + """
            void main() {
                gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
                vec3 vkdispAdapterViewPos = (ModelViewMat * vec4(Position, 1.0)).xyz;
            """ + NL;

    private CelestialVertexAdapterGenerator() {
    }

    /** 生成结果（与 {@link PackVertexAdapterGenerator.Result} 同一形状，调用方一套逻辑吃两条路）。 */
    public record Result(String glsl, List<TranslateDiagnostic> diagnostics, List<String> constantSupplies) {

        public Result {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            constantSupplies = constantSupplies == null ? List.of() : List.copyOf(constantSupplies);
        }
    }

    /**
     * 生成适配层。
     *
     * @param inputs 包<b>片元</b>声明的 varying 契约（逐位置逐名字对齐的目标）
     */
    public static Result generate(List<PackTerrainProgram.Input> inputs) {
        List<TranslateDiagnostic> diagnostics = new ArrayList<>();
        Set<String> constants = new LinkedHashSet<>();
        StringBuilder declarations = new StringBuilder();
        StringBuilder body = new StringBuilder();
        for (PackTerrainProgram.Input input : inputs == null ? List.<PackTerrainProgram.Input>of() : inputs) {
            String assignment = supply(input, constants);
            if (assignment == null) {
                diagnostics.add(TranslateDiagnostic.warn(
                        "日月适配层不认识 varying '" + input.name() + "'（类型 " + input.type()
                                + "，location " + input.location() + "），按类型零值供值 —— 该通道本轮"
                                + "**不承诺正确**（不猜，X9）", null, 0));
                String zero = zeroValue(input.type());
                if (zero == null) {
                    diagnostics.add(TranslateDiagnostic.of(TranslateDiagnostic.Severity.ERROR,
                            "日月适配层供不了类型 '" + input.type() + "'（名字 " + input.name()
                                    + "）⇒ 本条程序<b>不接线</b>（交回原版 position_tex，不半接）", null, 0));
                    return new Result(null, diagnostics, List.of());
                }
                assignment = input.name() + " = " + zero;
                constants.add(input.name());
            }
            declarations.append("layout(location = ").append(input.location()).append(") out ")
                    .append(input.type()).append(' ').append(input.name()).append(';').append(NL);
            body.append("    ").append(assignment).append(';').append(NL);
        }
        diagnostics.add(TranslateDiagnostic.info(
                "日月顶点适配层已生成：varyings=" + (inputs == null ? 0 : inputs.size())
                        + "（常量/中性供值 " + constants.size() + " 条："
                        + (constants.isEmpty() ? "无" : String.join(", ", constants)) + "）"
                        + "；输入属性=POSITION_TEX 的 Position/UV0 两条", null, 0));
        return new Result(HEADER + declarations + MAIN_HEAD + body + "}" + NL, diagnostics,
                List.copyOf(constants));
    }

    /** 三档供值表；返回 {@code null} = 这个名字本层不认识。 */
    private static String supply(PackTerrainProgram.Input input, Set<String> constants) {
        String name = input.name();
        // 🔴 类型也要对上：同名不同类型（包写了 vec2 的 texCoord 而本层交 vec2 是好的，
        //    但反过来若包把 normal 声明成 vec4）就只能按零值供 —— 下面每条都先校验类型。
        switch (name) {
            case "texCoord", "vUv", "uv", "texCoord0" -> {
                if (is("vec2", input)) {
                    return name + " = UV0";
                }
            }
            case "sunVec" -> {
                if (is("vec3", input)) {
                    constants.add(name);
                    return name + " = normalize(SunDir.xyz)";
                }
            }
            case "upVec" -> {
                if (is("vec3", input)) {
                    constants.add(name);
                    return name + " = normalize(ModelViewMat[1].xyz)";
                }
            }
            case "eastVec" -> {
                if (is("vec3", input)) {
                    constants.add(name);
                    return name + " = normalize(ModelViewMat[0].xyz)";
                }
            }
            case "viewVector" -> {
                if (is("vec3", input)) {
                    return name + " = normalize(vkdispAdapterViewPos)";
                }
            }
            case "dist" -> {
                if (is("float", input)) {
                    return name + " = length(vkdispAdapterViewPos)";
                }
            }
            // 🔴 原版日月顶点流**没有 Color 元素**（POSITION_TEX 只有 Position/UV0）⇒ 只能给乘法单位元。
            //   这不是「凑一个值」：1.0 是 albedo 乘法的中性元，不改包算出来的颜色；而绑错通道
            //   （拿图集或方块色）才会编造数据。缺的那半（原版日月的雨亮度过调制）属
            //   「按笔的 vertexColor 供值」另一格，见 docs/13 GAP-027 的日月行。
            case "color" -> {
                if (is("vec4", input)) {
                    constants.add(name);
                    return name + " = vec4(1.0)";
                }
            }
            default -> {
                return null;
            }
        }
        return null;
    }

    /** 类型对得上才交真值；对不上就落到调用方的「零值 + WARN」那一路（不猜，X9）。 */
    private static boolean is(String type, PackTerrainProgram.Input input) {
        return type.equals(input.type());
    }

    /** GLSL 类型零值（不认识返回 {@code null} ⇒ 调用方判整条不接线，绝不猜）。 */
    private static String zeroValue(String type) {
        return switch (type) {
            case "float" -> "0.0";
            case "vec2" -> "vec2(0.0)";
            case "vec3" -> "vec3(0.0)";
            case "vec4" -> "vec4(0.0)";
            case "int" -> "0";
            case "ivec2" -> "ivec2(0)";
            case "ivec3" -> "ivec3(0)";
            case "ivec4" -> "ivec4(0)";
            default -> null;
        };
    }
}
