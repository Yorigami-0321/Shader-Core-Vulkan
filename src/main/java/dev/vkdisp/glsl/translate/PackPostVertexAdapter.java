package dev.vkdisp.glsl.translate;

import dev.vkdisp.pipeline.model.PostPassContract;
import java.util.ArrayList;
import java.util.List;

/**
 * 后处理步的**屏幕空间顶点适配层**（每个槽位一份 {@code postK.vsh}，按该片元的输入契约生成）。
 *
 * <p><b>为什么必须有它</b>（h46 首轮 Vulkan 实测钉出来的缺口）：后处理管线挂的是引擎全屏三角形 VS
 * （{@code vkdisp:fullscreen}，只输出 location 0..2），而 BSL 的 deferred1/composite* 片元声明了
 * 第 4 条输入（{@code eastVec} @location 3）⇒ 原版 PipelineBuilder 链接期直接抛
 * {@code missing output at location 3}，**required 管线编译失败会砸掉整次资源重载**。
 * OF/Iris 在这里的做法是让包自己的 post VS 跑（ftransform + gl_MultiTexCoord0），
 * 但那要求执行包 VS 的固定管线语义 —— 与地形侧同一层工程（未做）。
 *
 * <p>🔖 <b>供值口径（逐条显式，不假装）</b>：
 * {@code texCoord}/{@code vTexCoords} 这类屏幕 uv 语义的 vec2 = 全屏三角形的 uv（与 vUv 同值，
 * 行序 = 场景行序，p416 采样源规则）；{@code sunVec}/{@code upVec}/{@code eastVec} 与其余
 * 类型按零值供（与既有 {@code fullscreen.vsh} 的占位一致 —— 它本来就给 sun/up 供 0），
 * 每条生成一行 WARN 诊断登记「该 varying 本轮不成立」（01-DEV-LOOP §5.1 降级点）。
 */
public final class PackPostVertexAdapter {

    private PackPostVertexAdapter() {}

    public record Result(String glsl, List<String> zeroSupplied) {}

    /** 生成适配层 VSH 源。 */
    public static Result generate(List<PostPassContract.FragmentInput> inputs) {
        StringBuilder decls = new StringBuilder();
        StringBuilder body = new StringBuilder();
        List<String> zeroSupplied = new ArrayList<>();
        // 🔖 vUv@0 只在**契约没有占 location 0** 时输出（兜底 passthrough 读它）；
        //   同时输出会与契约输出**重叠 location**（glslang 直接拒：'overlapping use of location 0'，
        //   h46 实测砸掉全部 16 条 required 管线 —— 一个模板细节放大成全链失败）。
        boolean uvNeeded = inputs.stream().noneMatch(i -> i.location() == 0);
        for (PostPassContract.FragmentInput input : inputs) {
            decls.append("layout(location = ").append(input.location())
                    .append(") out ").append(input.type()).append(' ').append(input.name())
                    .append(';').append('\n');
            String expr = supply(input);
            if (expr == null) {
                expr = zeroValue(input.type());
                zeroSupplied.add(input.name());
            }
            body.append("    ").append(input.name()).append(" = ").append(expr).append(";\n");
        }
        String header = """
                #version 330
                #extension GL_ARB_separate_shader_objects : require
                // vkdisp 后处理步顶点适配层（按该片元的输入 varying 契约生成；h46）
                """;
        StringBuilder declBlock = new StringBuilder(header);
        if (uvNeeded) {
            declBlock.append("layout(location = 0) out vec2 vUv;\n");
        }
        declBlock.append(decls);
        String main = """

                void main() {
                    vec2 uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
                    gl_Position = vec4(uv * vec2(2, 2) + vec2(-1, -1), 0, 1);
                """ + "\n";
        StringBuilder mainBody = new StringBuilder(main);
        if (uvNeeded) {
            mainBody.append("    vUv = uv;\n");
        }
        mainBody.append(body).append("}\n");
        return new Result(declBlock.append(mainBody).toString(), List.copyOf(zeroSupplied));
    }

    /** 已知屏幕语义的名字给真值，其它返回 null（= 走零值 + 登记）。 */
    private static String supply(PostPassContract.FragmentInput input) {
        if (input.type().equals("vec2")
                && (input.name().equals("texCoord") || input.name().equals("vTexCoords")
                        || input.name().equals("coord") || input.name().equals("vUv"))) {
            return "uv";
        }
        return null;
    }

    private static String zeroValue(String type) {
        return switch (type) {
            case "float" -> "0.0";
            case "vec2" -> "vec2(0.0)";
            case "vec3" -> "vec3(0.0)";
            case "vec4" -> "vec4(0.0)";
            case "ivec2" -> "ivec2(0)";
            case "ivec3" -> "ivec3(0)";
            case "ivec4" -> "ivec4(0)";
            default -> throw new IllegalArgumentException(
                    "vkdisp: 后处理适配层不认识的类型 '" + type + "'（不猜，X9）");
        };
    }
}
