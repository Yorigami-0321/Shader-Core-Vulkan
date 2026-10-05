package dev.vkdisp.glsl.translate;
/**
 * 【参考调研】包地形片元的 varying 契约 → 我方顶点适配层 GLSL 生成 / 只观察本项目自产的转译终稿
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 {@code dev.vkdisp.glsl.translate} 的产物格式（{@code PackTerrainProgram} 里
 *    由转译终稿解析出的 {@code layout(location = N) in <type> <name>;} 签名）与既有 8 段管线；
 *    ② 原版 {@code core/terrain.vsh} 的**位置算式**（Mojang EULA：只保留数学事实，
 *    本文件是**独立重写**的等价表达，不逐行搬运原版着色器文本）。
 *    同为 MIT 自有代码 + 公开数学事实，无任何第三方代码或着色器文本并入。
 *    → 能否并入本项目（MIT）：可以
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码，也不含 Mojang 着色器文本
 * 1. 官方/主实现：原版只提供 core/terrain 这一对固定顶点/片元，没有「按包生成适配层」这回事。
 * 2. 备选：① 手写一份覆盖全部 OF varying 的静态适配层 —— 否决（实测 BSL 开高级材质后
 *    片元要 **15** 条 varying，默认配置只要 **9** 条；静态文件对另一个配置就是「少供」，
 *    驱动层直接抛 {@code ShaderCompileException: missing output at location 14}，
 *    资源加载失败 ⇒ 客户端起不来。② 拒接多槽路径 —— 否决：那就把本轮成果退回到「只支持默认配置」。
 * 3. 我们的差异点：**按包的契约生成**适配层，逐条 varying 声明 + 逐条供值，
 *    供不出来的显式记成常量 + 出 WARN（不静默、不假装）。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：冷路径（每次资源重载生成一次字符串，产物缓存在虚拟包资源里）。
 */
import dev.vkdisp.glsl.TranslateDiagnostic;
import dev.vkdisp.pipeline.model.PackTerrainProgram;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 按 {@link PackTerrainProgram} 的 varying 契约生成顶点适配层 GLSL。
 *
 * <p>🔖 <b>为什么必须生成而不是手写</b>（本轮 runClient 实测的唯一失败点）：
 * BSL 默认配置的地形片元要 <b>9</b> 条 varying，开 {@code ADVANCED_MATERIALS} 后要 <b>15</b> 条
 * （多出 dist / binormal / tangent / viewVector / vTexCoord / vTexCoordAM）。
 * 静态适配层只写死 9 条 ⇒ 驱动层在资源加载期抛
 * {@code ShaderCompileException: Vertex shader missing output at location 14}，
 * <b>客户端直接起不来</b>。按契约生成则两类配置都自洽。
 *
 * <p>🔖 <b>供值分三档，绝不假装</b>：① <b>真值</b> —— 能从原版地形顶点格式
 * （Position/Color/UV0/UV2）或 {@code VkDispTerrainParams} 真算出来的；
 * ② <b>常量</b> —— 原版顶点缓冲**没有**对应属性（方块 id、法线、切线副法线…），
 *    按零值/常量供并出 WARN（登记为 GAP-007）；③ <b>未知名字</b> —— 同样按类型零值供，
 *    且每条都出 WARN（「猜一个像的」是本项目最讨厌的失败形态）。
 */
public final class PackVertexAdapterGenerator {

    /** 适配层可读取的原版地形顶点属性（{@code DefaultVertexFormat.BLOCK}，字节码核实）。 */
    private static final String ATTRIBUTES = """
            layout(location = 0) in vec3 Position;
            layout(location = 1) in vec4 Color;
            layout(location = 2) in vec2 UV0;
            layout(location = 3) in ivec2 UV2;
            #ifdef MULTIDRAW_TERRAIN
            layout(location = 4) in ivec3 ChunkPosition;
            layout(location = 5) in float ChunkVisibility;
            #endif
            """;

    private PackVertexAdapterGenerator() {}

    /** 生成结果（纯数据 + GLSL 文本，无原版类型）。 */
    public record Result(String glsl, List<TranslateDiagnostic> diagnostics, List<String> constantSupplies,
            List<String> partialSupplies) {

        public Result {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            constantSupplies = constantSupplies == null ? List.of() : List.copyOf(constantSupplies);
            partialSupplies = partialSupplies == null ? List.of() : List.copyOf(partialSupplies);
        }
    }

    /**
     * 生成适配层顶点着色器。
     *
     * @param inputs 包片元声明的 varying 契约（{@link PackTerrainProgram#inputs()}）
     */
    /**
     * 生成适配层顶点着色器。
     *
     * @param inputs         包片元声明的 varying 契约
     * @param fullLightProbe **诊断单变量实验**：把 {@code lmCoord} 强制成 (1,1)（满光照）。
     *        用来判定「画面全黑是不是由 {@code lmCoord}（天光通道）导致」——
     *        {@code h10} 实测 BSL 高级材质路径里有 {@code sceneLighting *= skylightSqr}，
     *        而 {@code skylightSqr = lightmap.y²}、{@code lightmap = clamp(lmCoord, 0, 1)}。
     *        默认关；开启时只改这一个 varying，其余全部不动（单变量）。
     */
    public static Result generate(List<PackTerrainProgram.Input> inputs, boolean fullLightProbe,
            boolean parallaxSkipProbe, boolean colorProbe) {
        List<TranslateDiagnostic> diagnostics = new ArrayList<>();
        Set<String> constants = new LinkedHashSet<>();
        Set<String> partial = new LinkedHashSet<>();
        StringBuilder decls = new StringBuilder();
        StringBuilder body = new StringBuilder();
        for (PackTerrainProgram.Input input : inputs) {
            String assignment = supply(input, constants, partial, fullLightProbe, parallaxSkipProbe,
                    colorProbe);
            if (assignment == null) {
                diagnostics.add(TranslateDiagnostic.warn(
                        "适配层不认识 varying '" + input.name() + "'（类型 " + input.type()
                                + "），按类型零值供值 —— 该通道本轮**不承诺正确**（不猜，X9）",
                        null, 0));
                assignment = input.name() + " = " + zeroValue(input.type());
                constants.add(input.name());
            } else if (partial.contains(input.name())) {
                // 🔖 h41：这一档必须与「纯常量」**分开说**，否则诊断会误导人。
                //   vTexCoord / vTexCoordAM 的 .xy 就是真实的 UV0（BLOCK 格式确有该属性），
                //   缺的只是**图集重映射**（依赖 GAP-007 的 mat）与 z/w 分量。
                //   旧诊断统一说「原版顶点缓冲无对应属性」⇒ 读者会以为这两个
                //   varying 完全没有数据源，与事实不符（X9：不许让文档/诊断说假话）。
                diagnostics.add(TranslateDiagnostic.warn(
                        "varying '" + input.name() + "' 只能**部分**供值：.xy 取自真实的 "
                                + "UV0（BLOCK 格式确有该属性），但缺图集重映射矩阵"
                                + "（依赖 mat，同样是 GAP-007）与 z/w 分量"
                                + " ⇒ 该通道的坐标**不是图集坐标**，相关视差/材质细节不成立",
                        null, 0));
            } else if (constants.contains(input.name())) {
                diagnostics.add(TranslateDiagnostic.warn(
                        "varying '" + input.name() + "' 只能按常量供值（原版地形顶点缓冲无对应属性，"
                                + "登记为 GAP-007）⇒ 相关光照细节本轮不成立", null, 0));
            }
            decls.append("layout(location = ").append(input.location())
                    .append(") out ").append(input.type()).append(' ').append(input.name())
                    .append(';').append(String.valueOf((char) 10));
            body.append("    ").append(assignment).append(';')
                    .append(String.valueOf((char) 10));
        }
        String glsl = header() + decls + MAIN_HEAD + body + "}" + String.valueOf((char) 10);
        // U0001f50d X45：诊断开关必须自报状态。
        //   否则「开关没生效」与「结论不成立」无法区分 ⇒ 实验结论不可信（h11 笈的被该榁则挡住了）。
        diagnostics.add(TranslateDiagnostic.info(
                "顶点适配层已生成：varyings=" + inputs.size()
                        + "（纯常量供值 " + constants.size() + " 条："
                        + (constants.isEmpty() ? "无" : String.join(", ", constants))
                        + "；部分真值供值 " + partial.size() + " 条："
                        + (partial.isEmpty() ? "无" : String.join(", ", partial)) + "）"
                        + (fullLightProbe ? " **lmCoord=满光照诊断开关=已开启**"
                                  : " lmCoord=满光照诊断开关=关")
                        + (parallaxSkipProbe ? " **dist=视差跳过诊断开关=已开启**"
                                  : " dist=视差跳过诊断开关=关")
                        + (colorProbe ? " **color=强制白诊断开关=已开启**"
                                  : " color=强制白诊断开关=关"),
                null, 0));
        if (fullLightProbe) {
            diagnostics.add(TranslateDiagnostic.warn(
                    "[诊断] lmCoord 已强制为 vec2(1.0)：若渲染结果与常量一致，说明**开关没生效**（不是结论不成立）",
                    null, 0));
        }
        if (colorProbe) {
            diagnostics.add(TranslateDiagnostic.warn(
                    "[诊断] color 已强制为 vec4(1.0)（原版 Color 属性的真值被完全绕过）。"
                            + "若画面变亮 ⇒ **color.rgb 本来就是 0**（根因坐实）；"
                            + "若仍全黑 ⇒ color 也被排除，albedo 的零点在更上游",
                    null, 0));
        }
        if (parallaxSkipProbe) {
            diagnostics.add(TranslateDiagnostic.warn(
                    "[诊断] dist 已强制为 1000.0：parallaxFade = 1.0 ⇒ 视差分支整体跳过。"
                            + "若画面亮起来 ⇒ 压零项在**视差分支**（即 GAP-007 的 vTexCoord/vTexCoordAM 常量供值）；"
                            + "若仍黑 ⇒ 候选 3/4/5 全部否定，须换切分方向",
                    null, 0));
        }
        return new Result(glsl, diagnostics, List.copyOf(constants), List.copyOf(partial));
    }

    /** 三档供值表；返回 {@code null} 表示「不认识这个名字」。 */
    private static String supply(PackTerrainProgram.Input input, Set<String> constants,
            Set<String> partial, boolean fullLightProbe, boolean parallaxSkipProbe,
            boolean colorProbe) {
        String name = input.name();
        return switch (name) {
            case "texCoord" -> "texCoord = UV0";
            // 🔴 X44 现场复现（本轮真踩到）：供值表达式里带行尾注释时，后面拼上去的分号
            //   会被注释吃掉 ⇒ 生成出 `lmCoord = vec2(1.0)  // ...;` ⇒ 驱动层报
            //   "Couldn't parse GLSL ...: syntax error, unexpected IDENTIFIER, expecting ... SEMICOLON"
            //   ⇒ 6 条地形管线全部加载失败 ⇒ **资源重载抛异常 ⇒ 世界根本进不去**。
            //   ⇒ 供值表达式里一律不带注释；注释只能单独成行。
            case "lmCoord" -> fullLightProbe
                    ? "lmCoord = vec2(1.0)"
                    : "lmCoord = clamp((vec2(UV2) / 16.0 - 0.03125) * 1.06667,"
                    + " vec2(0.0), vec2(0.9333, 1.0))";
            // U0001f534 h15 定的新首要嫌疑：首行 albedo = texture(...) * vec4(color.rgb, 1.0)
            //   若 color.rgb 为 0 ⇒ albedo ≡ 0，与 texture / textureGrad / 光照**全无关**。
            //   六个候选至此全部排除，而 color 是**唯一没被实验触及的因子**。
            //   此探针只改 color 一个 varying（单变量）；候选因素应为 vec4(1.0)。
            case "color" -> colorProbe
                    ? "color = vec4(1.0)"
                    : "color = vkdispAdapterColor";
            case "sunVec" -> "sunVec = normalize(SunDir.xyz)";
            case "upVec" -> "upVec = normalize(ModelViewMat[1].xyz)";
            case "eastVec" -> "eastVec = normalize(ModelViewMat[0].xyz)";
            case "viewVector" -> "viewVector = normalize(vkdispAdapterViewPos)";
            // U0001f50d h12 根因探针：强制 dist = 1000 ⇒ parallaxFade = clamp((1000-64)/32,0,1) = 1.0
            //   ⇒ 命中 GetParallaxCoord 里的早退 `if (parallaxFade >= 1.0 || ...) return texCoord;`
            //   ⇒ 视差分支**整体跳过**、newCoord = texCoord ⇒ albedo 按普通方式采样。
            //   只改 dist 一个 varying（单变量）；若画面亮起来 ⇒
            //   压零项在**视差分支**，而不是光照/材质/采样器。
            case "dist" -> parallaxSkipProbe
                    ? "dist = 1000.0"
                    : "dist = length(vkdispAdapterViewPos)";
            case "mat", "recolor" -> mark(constants, name) + name + " = 0.0";
            case "normal" -> mark(constants, name) + name + " = vec3(0.0, 1.0, 0.0)";
            case "binormal" -> mark(constants, name) + name + " = vec3(1.0, 0.0, 0.0)";
            case "tangent" -> mark(constants, name) + name + " = vec3(0.0, 0.0, 1.0)";
            case "vTexCoord" -> markPartial(partial, name)
                    + name + " = vec4(UV0, 0.0, 0.0)";
            case "vTexCoordAM" -> markPartial(partial, name)
                    + name + " = vec4(UV0, 0.0, 1.0)";
            default -> null;
        };
    }

    /** 记为「部分真值」：.xy 来自真实属性，缺的是图集重映射与 z/w（h41）。 */
    private static String markPartial(Set<String> partial, String name) {
        partial.add(name);
        return "";
    }

    private static String mark(Set<String> constants, String name) {
        constants.add(name);
        return "";
    }

    /** GLSL 类型零值（不认识的名字按此供，绝不编一个像的值）。 */
    private static String zeroValue(String type) {
        return switch (type) {
            case "float" -> "0.0";
            case "vec2" -> "vec2(0.0)";
            case "vec3" -> "vec3(0.0)";
            case "vec4" -> "vec4(0.0)";
            case "ivec2" -> "ivec2(0)";
            case "ivec3" -> "ivec3(0)";
            case "ivec4" -> "ivec4(0)";
            case "mat4" -> "mat4(1.0)";
            case "mat3" -> "mat3(1.0)";
            case "mat2" -> "mat2(1.0)";
            default -> null;
        };
    }

    private static final String NL = String.valueOf((char) 10);

    /** 头部：与原版 core/terrain.vsh 同款 include 与属性声明（GLSL 是公开语言事实）。 */
    private static String header() {
        return """
                #version 330
                #extension GL_ARB_separate_shader_objects : require

                // vkdisp 地形顶点适配层（按所选包地形片元的 varying 契约**自动生成**）
                //
                // 为什么不能手写一份固定的：BSL 默认配置要 9 条 varying，开 ADVANCED_MATERIALS 后
                // 要 15 条（多出 dist/binormal/tangent/viewVector/vTexCoord/vTexCoordAM）。
                // 写死一份对另一个配置就是「少供」⇒ 驱动层在资源加载期抛
                // ShaderCompileException（missing output at location 14），客户端起不来。
                //
                // 供值三档：真值（可从原版地形顶点格式真算）/ 常量（顶点缓冲无对应属性，GAP-007）
                //           / 类型零值（不认识的名字，逐条 WARN，绝不猜）。
                #include <minecraft:globals.glsl>
                #include <minecraft:projection.glsl>
                #include <minecraft:terrainglobals.glsl>
                #ifndef MULTIDRAW_TERRAIN
                    #include <minecraft:chunksection.glsl>
                #endif

                """ + ATTRIBUTES + NL + """
                // GAP-004 的自定义块：眼空间太阳方向（块名必须与绑定组布局逐字一致）
                layout(std140) uniform VkDispTerrainParams {
                    vec4 SunDir;
                };

                """;
    }

    /** main() 的固定前半段：位置算式与两条中间量。 */
    private static final String MAIN_HEAD = NL + """
            void main() {
                vec3 pos = Position + (vec3(ChunkPosition) - vec3(CameraBlockPos)) + CameraOffset;
                gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
                vec3 vkdispAdapterViewPos = (ModelViewMat * vec4(pos, 1.0)).xyz;
                vec4 vkdispAdapterColor = Color;
                if (vkdispAdapterColor.a < 0.1) {
                    vkdispAdapterColor.a = 1.0;
                }
            """ + NL;
}
