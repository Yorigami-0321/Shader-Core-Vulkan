package dev.vkdisp.pack;

import dev.vkdisp.glsl.TranslateDiagnostic;
import dev.vkdisp.glsl.preprocess.ConstEvaluator;
import dev.vkdisp.glsl.preprocess.GlslPreprocessor;
import dev.vkdisp.glsl.preprocess.IncludeResolver;
import dev.vkdisp.pack.properties.ShaderProperties;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 【参考调研】ShaderPackService（A + B 汇合入口，含 C 线选项发现）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/18-PARALLEL.md §6「汇合点与并入顺序」——A+B 在 P0.4 之后并入、
 *    解锁 P2.1（扫包）/P2.2（解析 shaders.properties）；本类即该汇合动作的串联入口（CHANGE_LOG 记的
 *    「A+B 汇合的 ShaderPackService」）；
 *    ② docs/08-TESTING.md §4 的解析验收点（zip 与目录两种形态、维度子目录、选项枚举）；
 *    ③ 本仓库 A/B/C 三条线的既有产出（ShaderPackScanner / ShaderPackRepository / ShaderProperties /
 *    GlslPreprocessor），本类只做编排，不复制它们的逻辑。
 *    许可证：本文件为独立编写的纯 Java 编排层，不含任何第三方项目代码。
 *    → 能否并入本项目（MIT）：可以（仅编排本项目自研组件）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：无外部实现可参考 —— 两个参考模组（VulkanMod / Sulkan）都没有 OF/Iris 包加载器
 *    （AGENT_CONTEXT §1「也没有任何参考模组做过 OF 包加载器」），故本类按本项目契约自行编排。
 * 2. 备选：把编排写进 VkDisp 主类 —— 会让冷路径（解析）与热路径（渲染挂钩）耦合，
 *    且主类属关键路径（18-PARALLEL §8.1 env-1 独占），并行线无法单测。故独立成类。
 * 3. 我们的差异点：
 *    ① 只读不写用户包（04-SPEC §3.1 关键约束）：全部通过 MountPlan.openShader 只读读取；
 *    ② **不注册**虚拟资源包 —— 注册要碰原版资源系统，属关键路径（18-PARALLEL §4 B 线「不许做」）；
 *    ③ 输入输出全是内存数据、不触碰 com.mojang.*、可用 JUnit 自证（18-PARALLEL §2 并行判据四条）；
 *    ④ 程序级设置（settings）按「程序名段」匹配，支持维度前缀全名（如 world-1/gbuffers_water）；
 *    ⑤ 跨文件同名选项默认值冲突 → 禁用该选项并打 WARN（OF 语义，Option 类 javadoc 差异点①）；
 *    ⑥ 本阶段不填 Program#uniforms / #vertexAttributes —— 二者需 GLSL 声明扫描，
 *       归 D 线（UniformInjector / UniformCatalog）与顶点属性解析，留待后续汇合（F2 文档明确「空 = 未解析出声明」）。
 * 4. 许可证核对结论：本项目 MIT；零第三方代码并入（07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径（切包/重载时调用一次，之后只读），清晰优先，不做任何性能优化
 *    （18-PARALLEL §7.7、07-CONSTRAINTS T14 / X14）。
 */
public final class ShaderPackService {

    /** OF 维度子目录命名（world0 = 主世界、world-1 = 下界、world1 = 末地、world&lt;id&gt; = 模组维度）。 */
    private static final Pattern DIMENSION_FOLDER = Pattern.compile("world-?\\d+");

    /** shaders.properties 在包内的固定位置（相对 shaders/ 根）。 */
    public static final String PROPERTIES_FILE = "shaders.properties";

    /** 单个包的加载结果：模型 + 诊断；{@code pack == null} 表示该包未能组装成模型（诊断里必有说明）。 */
    public record LoadResult(ShaderPack pack, List<TranslateDiagnostic> diagnostics) {

        /** 归一构造：诊断列表冻结为不可变。 */
        public LoadResult {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    /** 整个 inventory 的加载结果：成功组装的包 + 全部诊断 + 扫描阶段原样透传的问题。 */
    public record InventoryResult(
            List<ShaderPack> packs,
            List<TranslateDiagnostic> diagnostics,
            List<ShaderPackScanner.PackProblem> scanProblems) {

        /** 归一构造：三个列表都冻结为不可变。 */
        public InventoryResult {
            packs = packs == null ? List.of() : List.copyOf(packs);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            scanProblems = scanProblems == null ? List.of() : List.copyOf(scanProblems);
        }
    }

    private ShaderPackService() {}

    /**
     * 加载 shaderpacks/ 目录下的全部合法包。
     *
     * <p>永不抛非受检异常：扫描与解包的任何失败都降级为诊断 / 问题清单，保证调用方不会崩（T11）。
     *
     * @param inventoryDir shaderpacks/ 库存目录（每个子条目是一个包）
     */
    public static InventoryResult loadAll(Path inventoryDir) {
        ShaderPackScanner.ScanResult scan = ShaderPackScanner.scan(inventoryDir);
        List<ShaderPack> packs = new ArrayList<>();
        List<TranslateDiagnostic> diagnostics = new ArrayList<>();
        for (ShaderPackScanner.DiscoveredPack discovered : scan.packs()) {
            LoadResult result = load(discovered);
            if (result.pack() != null) {
                packs.add(result.pack());
            }
            diagnostics.addAll(result.diagnostics());
        }
        return new InventoryResult(packs, diagnostics, scan.problems());
    }

    /**
     * 加载单个已发现的包：解包索引 → 解析 shaders.properties → 配对程序 → C 线选项发现 → 组装 {@link ShaderPack}。
     *
     * <p>永不抛非受检异常：任一步失败都记入诊断并尽量继续（包级降级），只有「无法解包」才返回
     * {@code pack == null}。降级一律显式可见，不许静默（07-CONSTRAINTS T11）。
     *
     * @param discovered 扫描器产出的合法包
     */
    public static LoadResult load(ShaderPackScanner.DiscoveredPack discovered) {
        List<TranslateDiagnostic> diagnostics = new ArrayList<>();
        if (discovered == null) {
            diagnostics.add(TranslateDiagnostic.of(
                    TranslateDiagnostic.Severity.ERROR, "vkdisp: 待加载的包为 null，已跳过"));
            return new LoadResult(null, diagnostics);
        }

        String origin = String.valueOf(discovered.source());

        ShaderPackRepository.MountPlan plan;
        try {
            plan = ShaderPackRepository.plan(discovered);
        } catch (IOException | RuntimeException e) {
            diagnostics.add(TranslateDiagnostic.of(
                    TranslateDiagnostic.Severity.ERROR,
                    "vkdisp: 解包索引失败，该包已跳过: " + e.getMessage(), origin, TranslateDiagnostic.UNKNOWN_LINE));
            return new LoadResult(null, diagnostics);
        }

        Set<String> dimensionFolders = enumerateDimensionFolders(plan, diagnostics);
        ShaderProperties shaderProperties = readShaderProperties(plan, diagnostics);

        List<String> sliders = shaderProperties == null ? List.of() : shaderProperties.sliders();
        Map<String, List<String>> screens = shaderProperties == null ? Map.of() : shaderProperties.screens();
        Map<String, List<String>> profiles = shaderProperties == null ? Map.of() : shaderProperties.profiles();
        // properties 收 A 线认定为「通用指令」的原始键值（07 T11：未识别键原样保留、不丢弃）。
        // sliders / screen / profile / program.X.enabled 在 ShaderProperties 里已各走结构化去向
        // （profiles → ShaderPack#profiles；sliders/screen → Option#slider / #screen；
        // programSwitches → Program#settings），故不在此重复收录。
        Map<String, String> directives = shaderProperties == null ? Map.of() : shaderProperties.directives();

        Map<String, ProgramBlueprint> blueprints = collectPrograms(plan, diagnostics);
        Map<String, Map<String, String>> settings = deriveSettings(shaderProperties, blueprints.keySet());

        IncludeResolver resolver = resolverFor(plan);
        List<Program> programs = new ArrayList<>(blueprints.size());
        List<ConstEvaluator.OptionConstant> discoveredOptions = new ArrayList<>();
        for (Map.Entry<String, ProgramBlueprint> entry : blueprints.entrySet()) {
            ProgramBlueprint blueprint = entry.getValue();
            programs.add(new Program(
                    blueprint.name,
                    ProgramStage.parse(blueprint.name),
                    ProgramStage.stageIndex(blueprint.name),
                    blueprint.dimensionFolder,
                    blueprint.vertexShader,
                    blueprint.fragmentShader,
                    List.of(),
                    List.of(),
                    settings.getOrDefault(entry.getKey(), Map.of())));
            collectOptions(plan, resolver, blueprint, discoveredOptions, diagnostics);
        }

        List<Option> options = assembleOptions(discoveredOptions, sliders, screens, diagnostics);

        ShaderPack pack;
        try {
            pack = new ShaderPack(
                    discovered.name(),
                    origin,
                    discovered.kind() == ShaderPackScanner.Kind.ZIP,
                    programs,
                    options,
                    profiles,
                    directives,
                    dimensionFolders);
        } catch (RuntimeException e) {
            diagnostics.add(TranslateDiagnostic.of(
                    TranslateDiagnostic.Severity.ERROR,
                    "vkdisp: 组装 ShaderPack 失败（契约不变量冲突）: " + e.getMessage(), origin,
                    TranslateDiagnostic.UNKNOWN_LINE));
            return new LoadResult(null, diagnostics);
        }
        return new LoadResult(pack, diagnostics);
    }

    // ------------------------------------------------------------------ 维度目录

    /**
     * 枚举包内存在的维度子目录。
     *
     * <p>两条来源合并：① 真实的目录条目（**含空目录** —— OF 语义里空的世界目录意味着该维度禁用着色器，
     * 这个信息无法从文件列表推导，故必须直接读目录）；② 由 shaderFiles 的路径前缀兜底
     * （防止 zip 目录条目缺失时丢失维度信息，否则 {@link ShaderPack} 的不变量会拒绝组装）。
     */
    private static Set<String> enumerateDimensionFolders(
            ShaderPackRepository.MountPlan plan, List<TranslateDiagnostic> diagnostics) {
        Set<String> folders = new LinkedHashSet<>();
        try {
            if (plan.kind() == ShaderPackScanner.Kind.DIRECTORY) {
                Path root = plan.source().resolve(plan.shadersPrefix());
                if (Files.isDirectory(root)) {
                    try (Stream<Path> children = Files.list(root)) {
                        children.filter(Files::isDirectory)
                                .map(path -> path.getFileName().toString())
                                .filter(name -> DIMENSION_FOLDER.matcher(name).matches())
                                .forEach(folders::add);
                    }
                }
            } else {
                try (ZipFile zip = new ZipFile(plan.source().toFile())) {
                    String prefix = plan.shadersPrefix();
                    zip.stream()
                            .filter(ZipEntry::isDirectory)
                            .map(ZipEntry::getName)
                            .filter(name -> name.startsWith(prefix))
                            .map(name -> name.substring(prefix.length()))
                            .filter(relative -> relative.endsWith("/")
                                    && relative.indexOf('/') == relative.length() - 1)
                            .map(relative -> relative.substring(0, relative.length() - 1))
                            .filter(name -> DIMENSION_FOLDER.matcher(name).matches())
                            .forEach(folders::add);
                }
            }
        } catch (IOException e) {
            diagnostics.add(TranslateDiagnostic.of(
                    TranslateDiagnostic.Severity.WARN,
                    "vkdisp: 枚举维度目录失败，维度语义可能不完整: " + e.getMessage(),
                    String.valueOf(plan.source()), TranslateDiagnostic.UNKNOWN_LINE));
        }
        for (String file : plan.shaderFiles()) {
            int slash = file.indexOf('/');
            if (slash > 0) {
                String head = file.substring(0, slash);
                if (DIMENSION_FOLDER.matcher(head).matches()) {
                    folders.add(head);
                }
            }
        }
        return folders;
    }

    // ------------------------------------------------------------------ shaders.properties

    /** 读取并解析 {@code shaders.properties}（A 线）；文件缺失返回 null，解析失败记 WARN 后按「无配置」继续。 */
    private static ShaderProperties readShaderProperties(
            ShaderPackRepository.MountPlan plan, List<TranslateDiagnostic> diagnostics) {
        if (!plan.shaderFiles().contains(PROPERTIES_FILE)) {
            diagnostics.add(TranslateDiagnostic.of(
                    TranslateDiagnostic.Severity.INFO,
                    "vkdisp: 包内没有 " + PROPERTIES_FILE + "，按无配置继续（选项/预设将为空）",
                    String.valueOf(plan.source()), TranslateDiagnostic.UNKNOWN_LINE));
            return null;
        }
        String text = readText(plan, PROPERTIES_FILE);
        if (text == null) {
            diagnostics.add(TranslateDiagnostic.of(
                    TranslateDiagnostic.Severity.WARN,
                    "vkdisp: " + PROPERTIES_FILE + " 存在但读取失败，按无配置继续",
                    PROPERTIES_FILE, TranslateDiagnostic.UNKNOWN_LINE));
            return null;
        }
        try {
            return ShaderProperties.parse(text);
        } catch (RuntimeException e) {
            // 解析失败必须显式可见（T11），但不应让整个包不可用 —— 按无配置继续。
            diagnostics.add(TranslateDiagnostic.of(
                    TranslateDiagnostic.Severity.WARN,
                    "vkdisp: " + PROPERTIES_FILE + " 解析失败，按无配置继续: " + e.getMessage(),
                    PROPERTIES_FILE, TranslateDiagnostic.UNKNOWN_LINE));
            return null;
        }
    }

    /** 读一个相对 shaders/ 根的文件文本；不存在或不可读返回 null（调用方负责显式降级）。 */
    private static String readText(ShaderPackRepository.MountPlan plan, String relativePath) {
        try (InputStream in = plan.openShader(relativePath)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 程序配对

    /** 一个程序在配对过程中的中间态（名字 + 维度目录 + 已发现的 .vsh / .fsh 路径）。 */
    private static final class ProgramBlueprint {
        private final String name;
        private final String dimensionFolder;
        private String vertexShader;
        private String fragmentShader;

        private ProgramBlueprint(String name, String dimensionFolder) {
            this.name = name;
            this.dimensionFolder = dimensionFolder;
        }
    }

    /**
     * 扫描 shaderFiles，把 {@code .vsh} / {@code .fsh} 按「维度目录 + 去扩展名文件名」配对成程序。
     *
     * <p>只接受两种标准位置：{@code shaders/&lt;名&gt;.fsh} 与 {@code shaders/worldN/&lt;名&gt;.fsh}
     * （OF 语义）；更深的路径记 WARN 并忽略，绝不静默丢弃（T11）。
     * 返回按键（带维度前缀的全名）排序的映射，保证程序顺序稳定可比。
     */
    private static Map<String, ProgramBlueprint> collectPrograms(
            ShaderPackRepository.MountPlan plan, List<TranslateDiagnostic> diagnostics) {
        Map<String, ProgramBlueprint> blueprints = new TreeMap<>();
        for (String file : plan.shaderFiles()) {
            String lower = file.toLowerCase(Locale.ROOT);
            boolean vertex = lower.endsWith(".vsh");
            boolean fragment = lower.endsWith(".fsh");
            if (!vertex && !fragment) {
                continue;
            }
            String[] segments = file.split("/");
            String dimensionFolder;
            String fileName;
            if (segments.length == 1) {
                dimensionFolder = "";
                fileName = segments[0];
            } else if (segments.length == 2 && DIMENSION_FOLDER.matcher(segments[0]).matches()) {
                dimensionFolder = segments[0];
                fileName = segments[1];
            } else {
                diagnostics.add(TranslateDiagnostic.of(
                        TranslateDiagnostic.Severity.WARN,
                        "vkdisp: 程序文件不在标准位置（应为 shaders/ 根或 shaders/worldN/），已忽略: " + file,
                        file, TranslateDiagnostic.UNKNOWN_LINE));
                continue;
            }
            String name = fileName.substring(0, fileName.length() - 4);
            if (name.isBlank()) {
                diagnostics.add(TranslateDiagnostic.of(
                        TranslateDiagnostic.Severity.WARN,
                        "vkdisp: 程序文件名去掉扩展名后为空，已忽略: " + file,
                        file, TranslateDiagnostic.UNKNOWN_LINE));
                continue;
            }
            String qualifiedName = dimensionFolder.isEmpty() ? name : dimensionFolder + "/" + name;
            ProgramBlueprint blueprint = blueprints.computeIfAbsent(
                    qualifiedName, key -> new ProgramBlueprint(name, dimensionFolder));
            if (vertex) {
                if (blueprint.vertexShader != null) {
                    diagnostics.add(TranslateDiagnostic.of(
                            TranslateDiagnostic.Severity.WARN,
                            "vkdisp: 程序 '" + qualifiedName + "' 出现重复 .vsh，保留先发现的一个并忽略: " + file,
                            file, TranslateDiagnostic.UNKNOWN_LINE));
                } else {
                    blueprint.vertexShader = file;
                }
            } else {
                if (blueprint.fragmentShader != null) {
                    diagnostics.add(TranslateDiagnostic.of(
                            TranslateDiagnostic.Severity.WARN,
                            "vkdisp: 程序 '" + qualifiedName + "' 出现重复 .fsh，保留先发现的一个并忽略: " + file,
                            file, TranslateDiagnostic.UNKNOWN_LINE));
                } else {
                    blueprint.fragmentShader = file;
                }
            }
        }
        return blueprints;
    }

    /**
     * 把 shaders.properties 里的程序级键归到各程序的 {@link Program#settings()}。
     *
     * <p>匹配的键形态（程序名段用「带维度前缀的全名」，与 OF 允许 {@code world-1/gbuffers_water} 写法一致）：
     * {@code <名>.enabled}、{@code blend.<名>}、{@code alphaTest.<名>}、{@code scale.<名>}、
     * {@code flip.<名>.<缓冲>}，以及 Iris 风格的 {@code program.<名>.enabled}。
     * 键值一律原样透传（未知值不解析、不丢弃，T11）。
     */
    private static Map<String, Map<String, String>> deriveSettings(
            ShaderProperties shaderProperties, Set<String> qualifiedNames) {
        if (shaderProperties == null || qualifiedNames.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, String>> settings = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : shaderProperties.directives().entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            for (String qualifiedName : qualifiedNames) {
                if (key.equals(qualifiedName + ".enabled")) {
                    putSetting(settings, qualifiedName, "enabled", value);
                } else if (key.equals("blend." + qualifiedName)) {
                    putSetting(settings, qualifiedName, "blend", value);
                } else if (key.equals("alphaTest." + qualifiedName)) {
                    putSetting(settings, qualifiedName, "alphaTest", value);
                } else if (key.equals("scale." + qualifiedName)) {
                    putSetting(settings, qualifiedName, "scale", value);
                } else {
                    String flipPrefix = "flip." + qualifiedName + ".";
                    if (key.startsWith(flipPrefix) && key.length() > flipPrefix.length()) {
                        putSetting(settings, qualifiedName, "flip." + key.substring(flipPrefix.length()), value);
                    }
                }
            }
        }
        for (Map.Entry<String, String> entry : shaderProperties.programSwitches().entrySet()) {
            if (qualifiedNames.contains(entry.getKey())) {
                putSetting(settings, entry.getKey(), "enabled", entry.getValue());
            }
        }
        return settings;
    }

    private static void putSetting(
            Map<String, Map<String, String>> settings, String qualifiedName, String key, String value) {
        settings.computeIfAbsent(qualifiedName, name -> new LinkedHashMap<>()).put(key, value);
    }

    // ------------------------------------------------------------------ 选项发现（C 线）

    /** 对程序的两个源文件跑 C 线预处理，收集诊断与识别出的选项常量。 */
    private static void collectOptions(
            ShaderPackRepository.MountPlan plan,
            IncludeResolver resolver,
            ProgramBlueprint blueprint,
            List<ConstEvaluator.OptionConstant> sink,
            List<TranslateDiagnostic> diagnostics) {
        // 程序允许只有一侧文件存在（Program 契约：缺失侧为 null，消费方显式降级，08-TESTING §4）。
        // 注意不能写成 List.of(vertexShader, fragmentShader) —— List.of 遇到 null 元素会抛 NPE。
        List<String> sourcePaths = new ArrayList<>(2);
        if (blueprint.vertexShader != null) {
            sourcePaths.add(blueprint.vertexShader);
        }
        if (blueprint.fragmentShader != null) {
            sourcePaths.add(blueprint.fragmentShader);
        }
        for (String sourcePath : sourcePaths) {
            String source = readText(plan, sourcePath);
            if (source == null) {
                diagnostics.add(TranslateDiagnostic.of(
                        TranslateDiagnostic.Severity.WARN,
                        "vkdisp: 程序源文件不可读，跳过选项发现: " + sourcePath,
                        sourcePath, TranslateDiagnostic.UNKNOWN_LINE));
                continue;
            }
            GlslPreprocessor.PreprocessReport report;
            try {
                report = GlslPreprocessor.analyze(sourcePath, source, resolver);
            } catch (RuntimeException e) {
                diagnostics.add(TranslateDiagnostic.of(
                        TranslateDiagnostic.Severity.WARN,
                        "vkdisp: 预处理该程序失败，已跳过其选项发现: " + e.getMessage(),
                        sourcePath, TranslateDiagnostic.UNKNOWN_LINE));
                continue;
            }
            diagnostics.addAll(report.result().diagnostics());
            sink.addAll(report.options());
        }
    }

    /**
     * 把 C 线识别出的选项常量组装成 F2 的 {@link Option}。
     *
     * <p>规则：① 不可见（{@code visible=false}）或已被判歧义（{@code disabled=true}）的常量不进模型；
     * ② 跨文件同名选项默认值一致 → 取先发现的一条（OF 联动语义）；默认值冲突 → **禁用该选项并打 WARN**
     * （OF 语义，见 {@link Option} 类 javadoc 差异点①）；③ 类型按 {@link OptionType} 的归类约定推导；
     * ④ slider / screen 归属由 shaders.properties 的 {@code sliders=} 与 {@code screen/=} 决定。
     */
    private static List<Option> assembleOptions(
            List<ConstEvaluator.OptionConstant> discovered,
            List<String> sliders,
            Map<String, List<String>> screens,
            List<TranslateDiagnostic> diagnostics) {
        Map<String, List<ConstEvaluator.OptionConstant>> byName = new LinkedHashMap<>();
        for (ConstEvaluator.OptionConstant option : discovered) {
            if (option == null || option.name() == null || option.name().isBlank()) {
                continue;
            }
            if (!option.visible() || option.disabled()) {
                // 解析层策略：不可见 / 歧义选项不进模型（ConstEvaluator 已为歧义项发过 WARN）。
                continue;
            }
            byName.computeIfAbsent(option.name(), name -> new ArrayList<>()).add(option);
        }

        List<Option> options = new ArrayList<>(byName.size());
        for (Map.Entry<String, List<ConstEvaluator.OptionConstant>> entry : byName.entrySet()) {
            String name = entry.getKey();
            List<ConstEvaluator.OptionConstant> group = entry.getValue();
            ConstEvaluator.OptionConstant first = group.get(0);
            String defaultValue = first.defaultValue() == null ? "" : first.defaultValue();
            boolean conflict = false;
            for (ConstEvaluator.OptionConstant other : group) {
                String otherDefault = other.defaultValue() == null ? "" : other.defaultValue();
                if (!Objects.equals(otherDefault.trim(), defaultValue.trim())) {
                    conflict = true;
                    break;
                }
            }
            if (conflict) {
                diagnostics.add(TranslateDiagnostic.of(
                        TranslateDiagnostic.Severity.WARN,
                        "vkdisp: 选项 '" + name + "' 在不同程序文件里默认值不一致，按 OF 语义禁用该选项",
                        first.sourceFile(), first.sourceLine()));
                continue;
            }
            OptionType type = classify(first);
            List<String> values = first.candidates() == null ? List.of() : first.candidates();
            if (values.isEmpty() && type == OptionType.BOOLEAN) {
                // OptionType 约定：布尔选项的 values 为 ["true","false"]。
                values = List.of("true", "false");
            }
            options.add(new Option(
                    name, type, defaultValue, values, sliders.contains(name), screenOf(name, screens)));
        }
        return options;
    }

    /** 按 {@link OptionType} 的归类约定推导类型：常量种类 → 候选值形态。 */
    private static OptionType classify(ConstEvaluator.OptionConstant option) {
        String kind = option.kind() == null ? "" : option.kind();
        if (kind.contains("bool")) {
            return OptionType.BOOLEAN;
        }
        List<String> candidates = option.candidates();
        if (candidates == null || candidates.isEmpty()) {
            return OptionType.STRING;
        }
        boolean allBoolean = true;
        boolean allInteger = true;
        boolean allNumeric = true;
        for (String candidate : candidates) {
            String value = candidate.trim();
            if (!value.equals("true") && !value.equals("false")) {
                allBoolean = false;
            }
            if (!isInteger(value)) {
                allInteger = false;
            }
            if (!isNumeric(value)) {
                allNumeric = false;
            }
        }
        if (allBoolean) {
            return OptionType.BOOLEAN;
        }
        if (allInteger) {
            return OptionType.INTEGER;
        }
        if (allNumeric) {
            return OptionType.FLOAT;
        }
        return OptionType.STRING;
    }

    private static boolean isInteger(String value) {
        if (value.isEmpty()) {
            return false;
        }
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException notAnInteger) {
            return false;
        }
    }

    private static boolean isNumeric(String value) {
        if (value.isEmpty()) {
            return false;
        }
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    /**
     * 该选项属于哪个子屏：显式列出的屏优先；否则归给第一个含通配 {@code *} 的屏；都没有则 {@code ""}（主屏）。
     *
     * <p>{@code screen}= 的完整语法（链接 {@code [NAME]}、{@code <profile>}、columns）由 A 线
     * {@link ShaderProperties} 承载，本方法只做「选项 → 屏」这一步归属判断。
     */
    private static String screenOf(String optionName, Map<String, List<String>> screens) {
        String wildcardOwner = "";
        boolean wildcardSeen = false;
        for (Map.Entry<String, List<String>> entry : screens.entrySet()) {
            List<String> tokens = entry.getValue();
            if (tokens.contains(optionName)) {
                return entry.getKey();
            }
            if (!wildcardSeen && tokens.contains("*")) {
                wildcardOwner = entry.getKey();
                wildcardSeen = true;
            }
        }
        return wildcardOwner;
    }

    /** 基于包内文件索引的 #include 解析器：直接复用 B 线的只读打开逻辑，不额外访问磁盘布局。 */
    private static IncludeResolver resolverFor(ShaderPackRepository.MountPlan plan) {
        return path -> (path == null || path.isBlank()) ? null : readText(plan, path);
    }
}
