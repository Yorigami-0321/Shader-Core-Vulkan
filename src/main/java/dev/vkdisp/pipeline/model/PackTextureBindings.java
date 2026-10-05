package dev.vkdisp.pipeline.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * OF {@code shaders.properties} 里 {@code texture.<sampler>=<相对路径>} 指令的纯解析。
 *
 * <p>🔖 <b>为什么单独一档</b>：BSL 的 {@code noisetex}（光柱抖动 / TAA 噪声）真值是
 * {@code shaders/tex/noise.png} —— 不接它就等于这些效果采一张**别的图**，
 * 画面能亮但语义错（「数字不在日志里、结论从画面反推」那一族的素材版）。
 * 解析必须发生在冷路径（包加载），且**拒绝**（路径穿越 / 非法名）要能被点名 ——
 * 猜一个路径是 X9，静默吞掉是 X11。
 */
public final class PackTextureBindings {

    /** 采样器名：GLSL 标识符形态。 */
    private static final Pattern NAME = Pattern.compile("^[A-Za-z_]\\w*$");

    /** 相对路径：不允许绝对、不允许 {@code ..} 上跳（只读包内，但路径穿越不接）。 */
    private static final Pattern PATH = Pattern.compile("^[^/\\\\][^\\\\]*$");

    public record Result(Map<String, String> bindings, List<String> rejected) {

        public Result {
            bindings = bindings == null ? Map.of() : Map.copyOf(bindings);
            rejected = rejected == null ? List.of() : List.copyOf(rejected);
        }
    }

    private PackTextureBindings() {}

    /**
     * 从 directives（键 = properties 原样 key）提取纹理绑定。
     *
     * @param directives {@code ShaderProperties.directives()}
     */
    public static Result fromDirectives(Map<String, String> directives) {
        Map<String, String> bindings = new LinkedHashMap<>();
        List<String> rejected = new ArrayList<>();
        if (directives == null) {
            return new Result(bindings, rejected);
        }
        for (Map.Entry<String, String> entry : directives.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("texture.")) {
                continue;
            }
            String sampler = key.substring("texture.".length()).strip();
            String path = entry.getValue() == null ? "" : entry.getValue().strip();
            if (!NAME.matcher(sampler).matches()) {
                rejected.add("非法 sampler 名: '" + key + "'");
                continue;
            }
            if (!PATH.matcher(path).matches() || path.contains("..")) {
                rejected.add("非法纹理路径: " + key + "=" + path + "（必须是 shaders/ 内相对路径）");
                continue;
            }
            bindings.put(sampler, path);
        }
        return new Result(bindings, rejected);
    }
}
