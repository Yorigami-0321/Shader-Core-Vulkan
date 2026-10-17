package dev.vkdisp.pack;

import dev.vkdisp.pack.properties.ShaderProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 包声明了哪些光影包特性、我方各自走到哪一步（生效 / 只读进模型 / 根本没接）。
 *
 * <p>存在的理由有两条，都是本仓反复踩过之后定下的口径：
 * ① <b>不许静默</b>（07 T11 / X46）：一条指令躺在 {@link ShaderProperties#directives()} 里没人读，
 *    与「这条特性已经生效」在画面与日志上目前无法区分 —— 而 {@code 13-GAP-REGISTRY} 的 GAP-021
 *    就是从这个混淆里长出来的（{@code shadowFade} 等键在表里、无消费方，恒 0 却被当成包的问题）。
 * ② <b>目标口径</b>：「所有光影包特性都正常生效」需要一张按族的可核对清单，而不是逐条印象。
 *
 * <p>三档的判据（写死在这张表里，改了代码要改表，别让表领先于代码）：
 * <ul>
 *   <li>{@link Status#EFFECTIVE} —— 声明已被渲染/编译路径消费，画面里应能看到；
 *   <li>{@link Status#MODEL_ONLY} —— 已按上游文法与上限读进模型并自报，<b>设备侧还没接</b>；
 *   <li>{@link Status#UNHONORED} —— 只在 {@code directives()} 里当字符串存着，没有任何消费方；
 *   <li>{@link Status#UNCLASSIFIED} —— 不属于下面任何一族（新的键族，需要人判档，别默认它会生效）。
 * </ul>
 *
 * <p>本类不判定「渲染结果对不对」—— 那要真机臂与画面判据（{@code 08-TESTING.md} §4 / §6）。
 * 它只回答「这一族我方有没有人接」。
 */
public final class PackFeatureCoverage {

    /** 一族的兑现程度。 */
    public enum Status {
        EFFECTIVE,
        MODEL_ONLY,
        UNHONORED,
        UNCLASSIFIED
    }

    /**
     * 一族：前缀集合 + 判档 + 一句话说明（说明里点名「卡在哪」，方便下一轮直接接手）。
     *
     * @param prefixes 指令名前缀（按声明顺序匹配，第一个命中的算这族）
     */
    public record Family(String name, Status status, String note, List<String> prefixes) {

        public Family {
            prefixes = List.copyOf(prefixes);
        }
    }

    /** 🔴 这张表的判档以 {@code 19} §7-9 与 {@code 13} 的 GAP 条目为准；新增消费方时要在这里改档。 */
    private static final List<Family> FAMILIES = List.of(
            new Family("program.*.enabled", Status.EFFECTIVE,
                    "表达式经 PackPostChain/ChainEnableGating 消费（GAP-024）",
                    List.of("program.")),
            new Family("选项与预设（screen / sliders / profile / const）", Status.EFFECTIVE,
                    "screen 布局与 profile 预设进 Option 模型；const 与选项宏进预处理与转译",
                    List.of("screen", "sliders", "profile.")),
            new Family("alphaTest.<程序>", Status.EFFECTIVE,
                    "2026-10-17 起由 glsl/translate/AlphaTestEpilogue 写进片元 main 收尾",
                    List.of("alphaTest.")),
            new Family("bufferObject.N（SSBO）", Status.MODEL_ONLY,
                    "文法与上限已读入；官方 26.3 API 无 storage 表达 ⇒ 需 T2 闸门例外才能接设备侧",
                    List.of("bufferObject.")),
            new Family("image.NAME（自定义图像）", Status.MODEL_ONLY,
                    "同上：已按 Iris 文法读入，分配/绑定受官方 API 限制未接",
                    List.of("image.")),
            new Family("blend.<程序>[.<缓冲>]", Status.EFFECTIVE,
                    "链上的步按程序取派生管线（FrameApi#runPostPass → bridge/PackStepPipelines.compiled）；"
                            + "上一轮写的「进不了 setPipeline」是误判 —— RenderSystem.getCompiledPipelineNullable"
                            + "把入参交给 PipelineCache#get（blaze3d/pipeline/PipelineCache.java:29-38），"
                            + "那里未命中就当场用原版 ShaderSource 编译并按对象身份记结果。"
                            + "🔴 半覆盖：只有链（composite/deferred/final）族接了，地形/实体等 gbuffers 族仍在族管线上；"
                            + "逐缓冲形态 blend.<程序>.<缓冲> 仍未读",
                    List.of("blend.")),
            new Family("size.buffer.* / autoRes.* / relative.*", Status.UNHONORED,
                    "每目标独立分辨率与 Vulkan「同一帧缓冲附件同尺寸」冲突 ⇒ 需按程序分帧缓冲/分 pass",
                    List.of("size.", "autoRes", "relative.")),
            new Family("filters.<目标>", Status.UNHONORED,
                    "链上目前一个 sampler 服务全部采样名（FrameApi#chainSampler）⇒ 要按名选 FilterMode",
                    List.of("filters.")),
            new Family("clear.<目标> / clearEachFrame", Status.UNHONORED,
                    "附件清屏色/时机目前不分目标",
                    List.of("clear.", "clearEachFrame")),
            new Family("texture.<阶段>.<名> / customTexture / 内建覆盖", Status.UNHONORED,
                    "包自带纹理的供给在 PackTextures 一条线上；按阶段覆盖内建绑点未接"
                            + "（移植件 pack/model/PackTexture 已备好形状与 Raw.bytes() 判据）",
                    List.of("texture.", "customTexture", "texredports")),
            new Family("shadow.*（分辨率 / 剔除 / gcolor / voxelization）", Status.UNHONORED,
                    "阴影侧目前是 ShadowStubs；这批指令等真阴影路径",
                    List.of("shadow.", "shadowMapResolution", "shadowVoxelizationDimension",
                            "shadowHardwareDepthCompare", "sunPathRotation", "voxelDistance")),
            new Family("PBR 材质图（normal. / specular. / specularGloss.）", Status.UNHONORED,
                    "LabPBR 图集缺口见 13 GAP-009",
                    List.of("normal.", "specular.", "specularGloss.")),
            new Family("biome.* / noise.*", Status.UNHONORED,
                    "生物群系取样与噪声图族未接",
                    List.of("biome.", "noise.")),
            new Family("旧渲染开关（oldLighting / underwaterOverlay / tweakBlockDamage 等）",
                    Status.UNHONORED,
                    "影响原版着色兼容性的零星开关，逐条判：多数要求我方在地形/水面侧配合",
                    List.of("oldLighting", "underwaterOverlay", "tweakBlockDamage", "sunTweaks",
                            "tweakExposure")),
            new Family("变量与优先级（variablesDeferred / variablesMin / variablePriority）",
                    Status.UNHONORED,
                    "延迟求值与优先级影响自定义 uniform 的求值次序",
                    List.of("variablesDeferred", "variablesMin", "variablesMax", "variablePriority")),
            new Family("flip.<缓冲> / scale.<程序>", Status.UNHONORED,
                    "翻转与缩放族：我方目前按程序族统一处理 1-v 翻转，未按缓冲声明",
                    List.of("flip.", "scale.")));

    /** 结构化视图里才有计数的那两族的名字（必须与 {@link #FAMILIES} 里的 name 逐字相同）。 */
    private static final String PROGRAM_FAMILY = "program.*.enabled";

    private static final String OPTION_FAMILY = "选项与预设（screen / sliders / profile / const）";

    private PackFeatureCoverage() {
    }

    /** 一族在一份包里的读数：条数 + 首条原文（点名用），供日志与判据共用。 */
    public record Reading(String family, Status status, String note, int count, String firstLine) {

        public Reading {
            firstLine = firstLine == null ? "" : firstLine;
        }
    }

    /**
     * 把包的通用指令按族归类。未命中任何族的键记为 {@link Status#UNCLASSIFIED}，
     * 这样新出现的键族是「冒出来要被判档」，而不是被默默算成已支持。
     *
     * @return 只含**有条目**的族，按 {@link #FAMILIES} 顺序，最后可能追加一条「未归类」
     */
    public static List<Reading> of(ShaderProperties properties) {
        if (properties == null) {
            return List.of();
        }
        // 每条键只归一族；计数与首条原文各存一份，顺序按 FAMILIES 声明序输出。
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, String> firsts = new LinkedHashMap<>();
        int unclassified = 0;
        String unclassifiedFirst = null;
        for (Map.Entry<String, String> entry : properties.directives().entrySet()) {
            Family family = familyOf(entry.getKey());
            String line = entry.getKey() + "=" + entry.getValue();
            if (family == null) {
                unclassified++;
                if (unclassifiedFirst == null) {
                    unclassifiedFirst = line;
                }
                continue;
            }
            counts.computeIfAbsent(family.name(), k -> new int[1])[0]++;
            firsts.putIfAbsent(family.name(), line);
        }
        // 🔴 结构化视图里的族也要算：program.*.enabled 与 screen/sliders/profile 在分派时
        //     就离开了通用表（见 ShaderProperties#classify），只扫 directives() 会把
        //     「已兑现的两族」读成 0 条 —— 那正是本类要消灭的那种「静默的不一致」。
        if (!properties.programSwitches().isEmpty()) {
            Map.Entry<String, String> first = properties.programSwitches().entrySet().iterator().next();
            counts.computeIfAbsent(PROGRAM_FAMILY, k -> new int[1])[0] +=
                    properties.programSwitches().size();
            firsts.putIfAbsent(PROGRAM_FAMILY, "program." + first.getKey() + ".enabled=" + first.getValue());
        }
        int optionKeys = properties.sliders().size() + properties.screens().size()
                + properties.profiles().size();
        if (optionKeys > 0) {
            counts.computeIfAbsent(OPTION_FAMILY, k -> new int[1])[0] += optionKeys;
            firsts.putIfAbsent(OPTION_FAMILY, properties.screens().isEmpty()
                    ? "sliders=" + String.join(" ", properties.sliders())
                    : "screen=" + properties.screens().keySet().iterator().next());
        }

        List<Reading> out = new ArrayList<>();
        for (Family family : FAMILIES) {
            int[] hit = counts.get(family.name());
            if (hit == null) {
                continue;
            }
            out.add(new Reading(family.name(), family.status(), family.note(), hit[0],
                    firsts.get(family.name())));
        }
        if (unclassified > 0) {
            out.add(new Reading("未归类键", Status.UNCLASSIFIED,
                    "不属于任何已知族 ⇒ 需要人判档，不许默认它已生效", unclassified, unclassifiedFirst));
        }
        return List.copyOf(out);
    }

    /** 该指令名属于哪一族（第一个前缀命中即算）；不属于任何族返回 null。 */
    public static Family familyOf(String key) {
        for (Family family : FAMILIES) {
            for (String prefix : family.prefixes) {
                if (key.equals(prefix) || key.startsWith(prefix)) {
                    return family;
                }
            }
        }
        return null;
    }

    /** 供日志与判据用的一行汇总：{@code EFFECTIVE=a MODEL_ONLY=b UNHONORED=c UNCLASSIFIED=d}。 */
    public static String summarize(List<Reading> readings) {
        Map<Status, Integer> byStatus = new LinkedHashMap<>();
        for (Status status : Status.values()) {
            byStatus.put(status, 0);
        }
        for (Reading reading : readings) {
            byStatus.merge(reading.status(), reading.count(), Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        byStatus.forEach((status, count) -> sb.append(status.name()).append('=').append(count).append(' '));
        return sb.toString().trim();
    }
}
