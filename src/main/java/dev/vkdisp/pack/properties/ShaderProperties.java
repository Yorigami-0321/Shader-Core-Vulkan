package dev.vkdisp.pack.properties;

import dev.vkdisp.pack.model.BufferObject;
import dev.vkdisp.pack.model.ImageInformation;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【参考调研】shaders.properties 解析（OF/Iris 主配置）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = OptiFine 官方文档 shaders.properties 语法（directive=value，指令顺序无关；支持 #ifdef/#ifndef/#if/#else/#endif
 *    条件编译；不支持 #define/#include）+ Iris 同语义（LGPL-3.0，只读事实）；零代码复制。ARR 项目不碰。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以（仅含格式事实）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：指令族含屏幕布局（screen= / screen.<NAME>= / screen.columns / screen.<NAME>.columns）、
 *    全局唯一 sliders=、预设 profile.<NAME>=、程序开关 program.<NAME>.enabled=<布尔表达式>、以及大量管线/视口开关
 *    （shadow.enabled / clouds / blend.<program>.* / texture.<stage>.* 等）。「选项屏幕布局」只是字符串列表，
 *    不需要 Option 对象（Option 模型归 F2，见 18-PARALLEL 交接 §5.4）。
 * 2. 备选：无。
 * 3. 我们的差异点：① 只处理条件编译，不实现 #define 展开/#include（shaders.properties 不支持，见交接 §6.2）；
 *    ② 选项宏的真正发现（扫 .fsh/.vsh）属 C 线，本类仅消费调用方提供的已定义宏集合；
 *    ③ lang 标签层（option.NAME / value.NAME）按交接 §6.7 先不做，等 F2 落地后与主线确认范围。
 *    🔖 2026-10-17 移植轮：本类现在**消费**两个 LGPL 移植件（{@code pack/model/BufferObject}、
 *    {@code pack/model/ImageInformation}），读法与上限都是 Iris 定的、不再是我方自创（台账见 NOTICE.md）。
 *    ⚠️ 与上游的差别只在视图：被拒的行本类**丢弃并点名**，同时原文仍留在 directives()（T11 不丢键）。
 * 4. 许可证核对结论：本项目 LGPL-3.0-or-later；本类自有，但消费 pack/model 的 Vitrail/Iris 移植件。
 * 5. 性能基线：❄️ 冷路径，不做优化（18-PARALLEL §7.7）。
 */
public final class ShaderProperties {

    private final Map<String, String> directives;
    private final List<String> sliders;
    private final Map<String, List<String>> screens;
    private final Map<String, Integer> screenColumns;
    private final Map<String, List<String>> profiles;
    private final Map<String, String> programSwitches;
    private final BufferObject.Reading bufferObjects;
    private final ImageInformation.Reading images;

    private ShaderProperties(Builder b) {
        this.directives = Map.copyOf(b.directives);
        this.sliders = List.copyOf(b.sliders);
        this.screens = Map.copyOf(b.screens);
        this.screenColumns = Map.copyOf(b.screenColumns);
        this.profiles = Map.copyOf(b.profiles);
        this.programSwitches = Map.copyOf(b.programSwitches);
        this.bufferObjects = new BufferObject.Reading(
                new ArrayList<>(b.bufferObjects.values()), b.bufferObjectDropped);
        this.images = new ImageInformation.Reading(b.images, b.imageDropped);
    }

    public static ShaderProperties parse(Reader reader) {
        return parse(IdMapProperties.readLines(reader), null, Map.of());
    }

    public static ShaderProperties parse(Reader reader, Set<String> definedMacros) {
        return parse(IdMapProperties.readLines(reader), definedMacros, Map.of());
    }

    public static ShaderProperties parse(String text) {
        return parse(List.of(text.split("\n", -1)), null, Map.of());
    }

    public static ShaderProperties parse(String text, Set<String> definedMacros) {
        return parse(List.of(text.split("\n", -1)), definedMacros, Map.of());
    }

    /**
     * 带「包当前设置值」的读法。
     *
     * <p>🔴 存在的理由是一条 Iris 的规则：{@code image.NAME} 的尺寸位可以写一个**选项名**而不是数字，
     * 由包的设置把它落成整数（{@code pack/model/ImageInformation#dimension}）。不给这张表，
     * 那条本来有效的声明会以「size is not a number」被丢弃。
     */
    public static ShaderProperties parse(String text, Map<String, String> defines) {
        return parse(List.of(text.split("\n", -1)), null, defines);
    }

    private static ShaderProperties parse(List<String> rawLines, Set<String> definedMacros,
            Map<String, String> defines) {
        List<String> lines = ConditionalPreprocessor.preprocess(rawLines, definedMacros);
        Builder b = new Builder();
        b.defines = defines == null ? Map.of() : defines;
        int idx = 0;
        for (String line : lines) {
            idx++;
            int eq = line.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("vkdisp: shaders.properties 行缺少 '='（行 " + idx + "）：" + line);
            }
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            classify(b, key, value, idx);
        }
        return new ShaderProperties(b);
    }

    private static void classify(Builder b, String key, String value, int idx) {
        if (key.equals("sliders")) {
            if (!b.sliders.isEmpty()) {
                throw new IllegalArgumentException("vkdisp: sliders= 全局只允许一行（T11，行 " + idx + "）");
            }
            b.sliders.addAll(splitWs(value));
        } else if (key.equals("screen")) {
            b.screens.computeIfAbsent("", k -> new ArrayList<>()).addAll(splitWs(value));
        } else if (key.startsWith("screen.")) {
            String rest = key.substring("screen.".length());
            if (rest.equals("columns")) {
                b.screenColumns.put("", parseInt(value, idx));
            } else if (rest.endsWith(".columns")) {
                String name = rest.substring(0, rest.length() - ".columns".length());
                b.screenColumns.put(name, parseInt(value, idx));
            } else {
                b.screens.computeIfAbsent(rest, k -> new ArrayList<>()).addAll(splitWs(value));
            }
        } else if (key.startsWith("profile.")) {
            String name = key.substring("profile.".length());
            b.profiles.computeIfAbsent(name, k -> new ArrayList<>()).addAll(splitWs(value));
        } else if (key.startsWith("program.") && key.endsWith(".enabled")) {
            String name = key.substring("program.".length(), key.length() - ".enabled".length());
            b.programSwitches.put(name, value);
        } else if (key.startsWith("bufferObject.")) {
            // 🔴 上限与词数都是 Iris 定的，本类不重算：读不出来就**丢弃并点名**（不是 log 一句然后当没事）。
            String reason = BufferObject.parse(key.substring("bufferObject.".length()), value,
                    b.bufferObjects);
            if (reason != null) {
                b.bufferObjectDropped.add(key + "=" + value + " : " + reason);
            }
            b.directives.put(key, value);
        } else if (key.startsWith("image.")) {
            String reason = ImageInformation.parse(key.substring("image.".length()), value,
                    b.defines, b.images);
            if (reason != null) {
                b.imageDropped.add(key + "=" + value + " : " + reason);
            }
            b.directives.put(key, value);
        } else {
            // 🔴 GAP-021：`uniform.<类型>.<名>=` / `variable.<类型>.<名>=` **故意**留在通用表里，
            //   不在这里开分支 —— 本类只负责分派，CPU 侧的表达式语法属
            //   {@code pack/uniform/PackUniformSet#fromProperties}（那里才有函数表与依赖图）。
            //   留在这里的好处：① 不重复一份键语法；② 分派层与求值层各自可单测。
            //   ⚠️ 但「留在通用表」不等于「没人管」：此前这两族正是因为在 directives 里
            //   而**没有任何消费方**，BSL 的 shadowFade / timeBrightness 才恒 0（GAP-021）。
            b.directives.put(key, value);
        }
    }

    private static List<String> splitWs(String value) {
        List<String> out = new ArrayList<>();
        for (String tok : value.split("\\s+")) {
            if (!tok.isEmpty()) {
                out.add(tok);
            }
        }
        return out;
    }

    private static int parseInt(String value, int idx) {
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("vkdisp: " + "columns 必须是整数（行 " + idx + "）：" + value);
        }
    }

    /** 全部通用指令（管线/视口/纹理等），不含 screen/sliders/profile/program 开关（不可变）。 */
    public Map<String, String> directives() {
        return directives;
    }

    /** 全局滑块选项名列表（shaders.properties 只允许一行 sliders=）。 */
    public List<String> sliders() {
        return sliders;
    }

    /** 选项界面分组：键 "" = 主屏（screen=），其余为子屏名（screen.NAME）；值为 token 列表（含 [NAME]、星号 *、<profile> 等）。 */
    public Map<String, List<String>> screens() {
        return screens;
    }

    /** 各屏列数：键 "" = 主屏，其余为子屏名。 */
    public Map<String, Integer> screenColumns() {
        return screenColumns;
    }

    /** 选项预设：profile.<NAME> → 条目 token 列表。 */
    public Map<String, List<String>> profiles() {
        return profiles;
    }

    /** 程序开关表达式：program.<NAME>.enabled → 布尔表达式原文。 */
    public Map<String, String> programSwitches() {
        return programSwitches;
    }

    /**
     * {@code bufferObject.N}（SSBO）的结构化读法，含被拒的行。
     *
     * <p>原文仍留在 {@link #directives()}（T11「未识别键原样保留」）：这里加的是**消费入口**，不是替代视图。
     */
    public BufferObject.Reading bufferObjects() {
        return bufferObjects;
    }

    /** {@code image.NAME}（自定义图像）的结构化读法，含被拒的行；原文同 {@link #directives()}。 */
    public ImageInformation.Reading images() {
        return images;
    }

    private static final class Builder {
        final LinkedHashMap<String, String> directives = new LinkedHashMap<>();
        final List<String> sliders = new ArrayList<>();
        final LinkedHashMap<String, List<String>> screens = new LinkedHashMap<>();
        final LinkedHashMap<String, Integer> screenColumns = new LinkedHashMap<>();
        final LinkedHashMap<String, List<String>> profiles = new LinkedHashMap<>();
        final LinkedHashMap<String, String> programSwitches = new LinkedHashMap<>();
        final LinkedHashMap<Integer, BufferObject> bufferObjects = new LinkedHashMap<>();
        final List<String> bufferObjectDropped = new ArrayList<>();
        final ArrayList<ImageInformation> images = new ArrayList<>();
        final List<String> imageDropped = new ArrayList<>();
        /** {@code image.NAME} 尺寸位写选项名时用它落整数；由带 defines 的 parse 重载注入。 */
        Map<String, String> defines = Map.of();
    }
}
