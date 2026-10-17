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
 *    许可证：独立编写（LGPL-3.0-or-later），零第三方代码与零 Mojang 着色器文本。
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
 * 4. 许可证核对：本项目 LGPL-3.0-or-later；零第三方代码并入。
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
    private static final String ATTRIBUTES_TEX = """
            layout(location = 0) in vec3 Position;
            layout(location = 1) in vec2 UV0;
            """;

    /** 原版天空穹顶顶点流的单条属性（{@code DefaultVertexFormat.POSITION}：{@code RenderPipelines.java:1042}）。 */
    private static final String ATTRIBUTES_POSITION = """
            layout(location = 0) in vec3 Position;
            """;

    private static final String NL = String.valueOf((char) 10);

    /** 顶点流形状（= 原版那条管线声明的 {@code DefaultVertexFormat}）：决定本层能读哪几条属性。 */
    public enum Format {
        /** 日月：{@code POSITION_TEX}（有 UV0，无 Color）。 */
        POSITION_TEX(true),
        /** 穹顶（{@code gbuffers_skybasic}）：{@code POSITION}（既无 UV0 也无 Color）。 */
        POSITION(false);

        final boolean hasUv0;

        Format(boolean hasUv0) {
            this.hasUv0 = hasUv0;
        }

        String attributes() {
            return this == POSITION_TEX ? ATTRIBUTES_TEX : ATTRIBUTES_POSITION;
        }
    }

    /** 头部：引擎的三个内建块（与 {@code GLOBALS}/{@code PROJECTION}/{@code DYNAMIC_TRANSFORMS} 一套布局同源）。 */
    private static String header(Format format) {
        return """
                #version 330
                #extension GL_ARB_separate_shader_objects : require

                // vkdisp 天空顶点适配层（按所选包天空程序的 varying 契约**自动生成**）
                //
                // 输入只有原版顶点流的属性；位置算式与原版同语义（ProjMat * ModelViewMat * Position）——
                // 天体姿态由原版留在 DynamicTransforms 里，本层不重算。
                #include <minecraft:globals.glsl>
                #include <minecraft:projection.glsl>
                #include <minecraft:dynamictransforms.glsl>

                """ + format.attributes() + """

                // GAP-004 的自定义块：眼空间太阳方向（块名必须与绑定组布局逐字一致）
                layout(std140) uniform VkDispTerrainParams {
                    vec4 SunDir;
                };

                """;
    }

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
     * 生成适配层（默认 {@link Format#POSITION_TEX} = 日月那条顶点流）。
     *
     * @param inputs 包<b>片元</b>声明的 varying 契约（逐位置逐名字对齐的目标）
     */
    public static Result generate(List<PackTerrainProgram.Input> inputs) {
        return generate(Format.POSITION_TEX, inputs);
    }

    /**
     * 生成适配层。
     *
     * @param format 原版喂给这笔 draw 的顶点流形状（决定本层能读哪几条属性）
     * @param inputs 包<b>片元</b>声明的 varying 契约（逐位置逐名字对齐的目标）
     */
    public static Result generate(Format format, List<PackTerrainProgram.Input> inputs) {
        Format shape = format == null ? Format.POSITION_TEX : format;
        List<TranslateDiagnostic> diagnostics = new ArrayList<>();
        Set<String> constants = new LinkedHashSet<>();
        StringBuilder declarations = new StringBuilder();
        StringBuilder body = new StringBuilder();
        for (PackTerrainProgram.Input input : inputs == null ? List.<PackTerrainProgram.Input>of() : inputs) {
            String assignment = supply(input, shape, constants);
            if (assignment == null) {
                assignment = fallbackAssignment(input, constants, diagnostics);
                if (assignment == null) {
                    return new Result(null, diagnostics, List.of());
                }
            }
            declarations.append("layout(location = ").append(input.location()).append(") out ")
                    .append(input.type()).append(' ').append(input.name()).append(';').append(NL);
            body.append("    ").append(assignment).append(';').append(NL);
        }
        diagnostics.add(TranslateDiagnostic.info(
                "天空顶点适配层已生成：shape=" + shape + " varyings=" + (inputs == null ? 0 : inputs.size())
                        + "（常量/中性供值 " + constants.size() + " 条："
                        + (constants.isEmpty() ? "无" : String.join(", ", constants)) + "）", null, 0));
        return new Result(header(shape) + declarations + MAIN_HEAD + body + "}" + NL, diagnostics,
                List.copyOf(constants));
    }

    /**
     * 本层不认名字时的<b>零值兜底</b>：先 WARN（该通道本轮不承诺），类型不认识则 ERROR 并返回 {@code null}
     * （调用方据此<b>整条不接线</b>，绝不半接 —— 半接线的症状是加载期抛，不是「效果差」）。
     */
    private static String fallbackAssignment(PackTerrainProgram.Input input, Set<String> constants,
            List<TranslateDiagnostic> diagnostics) {
        diagnostics.add(TranslateDiagnostic.warn(
                "天空适配层不认识 varying '" + input.name() + "'（类型 " + input.type()
                        + "，location " + input.location() + "），按类型零值供值 —— 该通道本轮"
                        + "**不承诺正确**（不猜，X9）", null, 0));
        String zero = zeroValue(input.type());
        if (zero == null) {
            diagnostics.add(TranslateDiagnostic.of(TranslateDiagnostic.Severity.ERROR,
                    "天空适配层供不了类型 '" + input.type() + "'（名字 " + input.name()
                            + "）⇒ 本条程序<b>不接线</b>（交回原版天空，不半接）", null, 0));
            return null;
        }
        constants.add(input.name());
        return input.name() + " = " + zero;
    }

    /** 三档供值表；返回 {@code null} = 这个名字本层不认识（或形状对不上）。 */
    private static String supply(PackTerrainProgram.Input input, Format format, Set<String> constants) {
        String name = input.name();
        // 🔴 类型必须对上（同名不同类型只能按零值供）；属性可用性也必须对上形状：
        //    {@code POSITION}（穹顶）没有 UV0，只有 {@code POSITION_TEX}（日月）有 —— 引用一条本顶点流
        //    没有的属性就是加载期抛的半接线，所以 texCoord 只在 hasUv0 时交 UV0，否则落零值 + WARN。
        //    中性常量（sunVec/upVec/eastVec/alpha/color）一律进 constants 名单，供自报逐条读回。
        boolean v3 = is("vec3", input);
        switch (name) {
            case "texCoord", "vUv", "uv", "texCoord0" -> { if (format.hasUv0 && is("vec2", input)) return name + " = UV0"; }
            case "sunVec" -> { if (v3) { constants.add(name); return name + " = normalize(SunDir.xyz)"; } }
            case "upVec" -> { if (v3) { constants.add(name); return name + " = normalize(ModelViewMat[1].xyz)"; } }
            case "eastVec" -> { if (v3) { constants.add(name); return name + " = normalize(ModelViewMat[0].xyz)"; } }
            case "viewVector" -> { if (v3) return name + " = normalize(vkdispAdapterViewPos)"; }
            case "dist" -> { if (is("float", input)) return name + " = length(vkdispAdapterViewPos)"; }
            // 穹顶（SKY 站）的 alpha 应不透明 ⇒ 中性 1.0（不改包算出的天空色）；STARS/MOON 站 alpha=0
            // 是<b>逐站</b>抑制口径（渲染期读 renderStage），不属生成期常量。缺 alpha 通道 = 半接线。
            case "alpha" -> { if (is("float", input)) { constants.add(name); return name + " = 1.0"; } }
            // 日月顶点流（POSITION_TEX）没有 Color 元素 ⇒ 只能交乘法单位元 vec4(1.0)（绑错通道才是编造数据）。
            case "color" -> { if (is("vec4", input)) { constants.add(name); return name + " = vec4(1.0)"; } }
            default -> { return null; }
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
