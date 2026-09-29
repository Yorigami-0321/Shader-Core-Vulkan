package dev.vkdisp.pack;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【参考调研】着色器包运行时模型（元数据 + 阶段映射 + 选项定义）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §3.1（ShaderPack = 包的运行时模型：元数据 + 阶段映射 + 选项定义；
 *    只读不写用户的包）、docs/08-TESTING.md §4（.zip 与目录两种形态、维度子目录、选项枚举的解析验收）
 *    与 docs/18-PARALLEL.md §3 F2（本契约的冻结依据）；
 *    ② OptiFine 官方文档（sp614x/optifine，OptiFineDoc/doc/shaders.properties 与 shaders.txt）中
 *    profile / sliders / 维度目录语义的事实。
 *    许可证：sp614x/optifine 无 LICENSE（GitHub license API 404）→ ARR，按 07-CONSTRAINTS X20 不并入其文本表达，
 *    仅用不受版权保护的事实性信息（键名/条目语法/目录语义），零文本复制；Iris（LGPL-3.0，已核 LICENSE）同口径；
 *    参考模组（VulkanMod / Sulkan / Beryl）零接触。
 *    → 能否并入本项目（MIT）：本文件为独立实现的 record，只含格式事实，可并入
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：OF 官方事实——profile.NAME= 由条目构成（OPTION:值、OPTION=值、OPTION 开、!OPTION 关、
 *    profile.其他名 继承、!program.名 禁程序，程序名可带维度前缀）；sliders= 列出滑条选项；
 *    shaders/ 下可有 world&lt;id&gt; 维度子目录（存在即该维度只从其加载，空目录 = 该维度禁用）；
 *    shaders.properties 其余键原样保留不丢弃。
 * 2. 备选：Iris 的 shaderpack 容器模型（格式事实：包名 + 程序集合 + 选项集合）；只取要素，不读代码。
 * 3. 我们的差异点：① profiles 存"原始条目 token 列表"而不是解析后的键值——继承/禁程序/否定等语法由 A 线解析、F 线求值，
 *    模型只保证顺序与原文不丢失；② 单独存 dimensionFolders（存在的维度子目录名集合），
 *    因为"空目录禁用维度"这一语义无法从 programs 列表推导（08-TESTING §4 的 world0/world-1/world1/world_end 覆盖）；
 *    ③ properties 存原始单值视图（重复键后写覆盖），结构化解读（screen/blend/flip 等）归 A 线 pack/properties/ 的 ShaderProperties；
 *    ④ 关键约束照抄 04-SPEC §3.1：本对象及其消费方只读用户包，绝不写回（不学 Sulkan 反复写 options.resourcePacks）。
 * 4. 许可证核对：本项目 MIT；本文件零第三方代码复制，仅含文档事实。
 * 5. 性能基线：❄️ 冷路径（切包/重载时构造一次，之后只读），不做任何性能优化（18-PARALLEL §7.7）。
 */

/**
 * 着色器包的运行时模型（record，不可变；04-SPEC §3.1）：元数据 + 阶段映射 + 选项定义。
 *
 * <p><b>冻结契约</b>：字段已按 {@code docs/18-PARALLEL.md} §3 F2 冻结，供 A/B/C/D/E/F 并行线与关键路径共同消费
 * （B 线扫描产出本对象，A 线解析填充 options/profiles/properties，C/D/E/F 线只读消费）。
 * 任何字段/语义变更必须走 {@code 18-PARALLEL.md} §3.2 流程（提出方说明 → env-1 统一改 → 契约版本号 +1 → 通知各环境 rebase），
 * 任何一方不得自行增删改（{@code 07-CONSTRAINTS.md} X12）。冷路径数据，只求清晰、不做性能优化（§7.7）。
 *
 * <p><b>只读红线</b>：本模型代表用户包，加载/解析全程只读，不许写回用户包（04-SPEC §3.1 关键约束）。
 *
 * <p><b>不变量</b>（构造时强制）：每个程序的非空 dimensionFolder 必须出现在 dimensionFolders 中
 * （文件只能来自已存在的目录），违反即抛 IllegalArgumentException。
 *
 * @param name              包标识（扫描器给定的显示名，如目录名或 zip 文件名；不含路径）
 * @param rootPath          包源根路径（目录或 .zip 的路径；只读，不写回）
 * @param fromArchive       源形态：true = .zip 归档，false = 文件夹（08-TESTING §4 要求两种形态都识别）
 * @param programs          全部维度的程序（扁平列表；每条自带 dimensionFolder；可为空——空包必须由解析层显式报错，T11）
 * @param options           选项定义（来源：shader 内 #define/const 注释 + properties 键值，见 {@link Option}）
 * @param profiles          profile 预设：profile 名 → 原始条目 token 列表（顺序保留；
 *                          条目语法见类级【参考调研】第 1 条）
 * @param properties        shaders.properties 原始键值（单值视图；未知/未识别键原样保留，T11）
 * @param dimensionFolders  存在的维度子目录名（不含 ""；**含空目录**——空目录意味着该维度按 OF 语义禁用着色器）
 */
public record ShaderPack(
        String name,
        String rootPath,
        boolean fromArchive,
        List<Program> programs,
        List<Option> options,
        Map<String, List<String>> profiles,
        Map<String, String> properties,
        Set<String> dimensionFolders) {

    /** 包内着色器根目录名（04-SPEC §3.1 的目录结构：&lt;rootPath&gt;/shaders/...）。 */
    public static final String SHADERS_DIRECTORY = "shaders";

    /** 冻结契约声明见类级 Javadoc。 */
    public ShaderPack {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("vkdisp: shader pack name must not be blank");
        }
        name = name.trim();
        if (rootPath == null || rootPath.isBlank()) {
            throw new IllegalArgumentException("vkdisp: shader pack '" + name + "' rootPath must not be blank");
        }
        rootPath = rootPath.trim();
        if (programs == null) {
            throw new IllegalArgumentException("vkdisp: shader pack '" + name + "' programs must not be null (use List.of())");
        }
        programs = List.copyOf(programs);
        if (options == null) {
            throw new IllegalArgumentException("vkdisp: shader pack '" + name + "' options must not be null (use List.of())");
        }
        options = List.copyOf(options);
        if (profiles == null) {
            throw new IllegalArgumentException("vkdisp: shader pack '" + name + "' profiles must not be null (use Map.of())");
        }
        LinkedHashMap<String, List<String>> copiedProfiles = new LinkedHashMap<>(profiles.size());
        for (Map.Entry<String, List<String>> entry : profiles.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException("vkdisp: shader pack '" + name + "' profile names must not be null/blank");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("vkdisp: shader pack '" + name + "' profile '" + entry.getKey() + "' entries must not be null");
            }
            copiedProfiles.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        profiles = Collections.unmodifiableMap(copiedProfiles);
        if (properties == null) {
            throw new IllegalArgumentException("vkdisp: shader pack '" + name + "' properties must not be null (use Map.of())");
        }
        LinkedHashMap<String, String> copiedProperties = new LinkedHashMap<>(properties.size());
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException("vkdisp: shader pack '" + name + "' property keys must not be null/blank");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException(
                        "vkdisp: shader pack '" + name + "' property value must not be null (key='" + entry.getKey() + "')");
            }
            copiedProperties.put(entry.getKey(), entry.getValue());
        }
        properties = Collections.unmodifiableMap(copiedProperties);
        if (dimensionFolders == null) {
            throw new IllegalArgumentException("vkdisp: shader pack '" + name + "' dimensionFolders must not be null (use Set.of())");
        }
        LinkedHashSet<String> copiedFolders = new LinkedHashSet<>(dimensionFolders.size());
        for (String folder : dimensionFolders) {
            if (folder == null || folder.isBlank()) {
                throw new IllegalArgumentException("vkdisp: shader pack '" + name + "' dimensionFolders must not contain null/blank names");
            }
            if (folder.contains("/")) {
                throw new IllegalArgumentException(
                        "vkdisp: shader pack '" + name + "' dimensionFolders entries must be single folder names: '" + folder + "'");
            }
            copiedFolders.add(folder);
        }
        dimensionFolders = Collections.unmodifiableSet(copiedFolders);
        for (Program program : programs) {
            if (program == null) {
                throw new IllegalArgumentException("vkdisp: shader pack '" + name + "' programs must not contain null entries");
            }
            String folder = program.dimensionFolder();
            if (!folder.isEmpty() && !dimensionFolders.contains(folder)) {
                throw new IllegalArgumentException(
                        "vkdisp: shader pack '" + name + "' program '" + program.name() + "' lives in folder '"
                                + folder + "' which was not listed in dimensionFolders");
            }
        }
        for (Option option : options) {
            if (option == null) {
                throw new IllegalArgumentException("vkdisp: shader pack '" + name + "' options must not contain null entries");
            }
        }
    }
}
