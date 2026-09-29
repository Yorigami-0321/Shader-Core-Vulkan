package dev.vkdisp.pack;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【参考调研】单个阶段程序模型（vsh/fsh 文件 + 阶段归类 + 声明引用）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §3.1（Program = 一个阶段程序，如 gbuffers_terrain.vsh/fsh）、
 *    §3.2（内建 uniform 语义 → Program#uniforms）、§4（顶点扩展属性 → Program#vertexAttributes）；
 *    docs/08-TESTING.md §4（缺文件——例如没有 shadow.fsh——必须显式降级并打日志，不静默跳过）与 §5（stride 自检的消费前提）；
 *    ② OptiFine 官方文档（sp614x/optifine，OptiFineDoc/doc/shaders.txt 与 shaders.properties）中程序文件与
 *    program 级键（enabled/alphaTest/blend/scale/flip）的事实。
 *    许可证：sp614x/optifine 无 LICENSE（GitHub license API 404）→ ARR，按 07-CONSTRAINTS X20 不并入其文本表达，
 *    仅用不受版权保护的事实性信息（文件扩展名/键名/前缀规则），零文本复制；Iris（LGPL-3.0，已核 LICENSE）同口径；
 *    参考模组（VulkanMod / Sulkan / Beryl）零接触。
 *    → 能否并入本项目（MIT）：本文件为独立实现的 record，只含格式事实，可并入
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：OF 官方事实——程序以名字命名，.vsh/.fsh 同名成对（另有 .gsh/.csh，本项目不冻结，见第 3 条）；
 *    程序可位于维度子目录（路径含维度前缀）；shaders.properties 可按程序配置 program.<名>.enabled、
 *    alphaTest.<名>、blend.<名>、scale.<名>、flip.<名>.<缓冲>（程序名段可带维度前缀，如 world-1/gbuffers_water）。
 * 2. 备选：Iris 的 program 模型（格式事实：名字 + 阶段 + 文件对）；只取要素，不读代码。
 * 3. 我们的差异点：① stage/stageIndex 不由调用方自由填写——构造时按 ProgramStage.parse/stageIndex 从 name 推导并校验一致，
 *    不一致直接抛异常（防止"名字与阶段脱节"这类静默错误）；② 只冻结 .vsh/.fsh 两类文件，
 *    OF 的几何/计算着色器（.gsh/.csh）不在本契约内（本项目管线走原版 RenderPipeline，04-SPEC §3.3），
 *    需要时走 18-PARALLEL §3.2 增补；③ 路径统一约定为"相对 shaders/ 根、含维度前缀"（如 world-1/gbuffers_water.vsh），
 *    构造时校验维度前缀一致；④ 缺失的一侧允许为 null——这正是 08-TESTING §4"显式降级"的载体，
 *    两侧全缺直接抛异常（该程序必须被解析器丢弃并打 WARN）；⑤ settings 只做原样透传，未知键不丢弃（T11）。
 * 4. 许可证核对：本项目 MIT；本文件零第三方代码复制，仅含文档事实。
 * 5. 性能基线：❄️ 冷路径（加载/重载时构造，之后只读），不做任何性能优化（18-PARALLEL §7.7）。
 */

/**
 * 一个阶段程序的运行时模型（record，不可变；04-SPEC §3.1）。
 *
 * <p><b>冻结契约</b>：字段已按 {@code docs/18-PARALLEL.md} §3 F2 冻结，供 A/B/C/D/E/F 并行线与关键路径共同消费。
 * 任何字段/语义变更必须走 {@code 18-PARALLEL.md} §3.2 流程（提出方说明 → env-1 统一改 → 契约版本号 +1 → 通知各环境 rebase），
 * 任何一方不得自行增删改（{@code 07-CONSTRAINTS.md} X12）。冷路径数据，只求清晰、不做性能优化（§7.7）。
 *
 * <p><b>不变量</b>（构造时强制，违反即抛 IllegalArgumentException，绝不静默）：
 * {@code stage == ProgramStage.parse(name)}、{@code stageIndex == ProgramStage.stageIndex(name)}、
 * {@code vertexShader / fragmentShader 至少一侧非 null}、维度目录非空时路径必须以其为前缀。
 *
 * @param name             程序名（不含扩展名，如 "gbuffers_terrain"、"composite3"）；非空白，构造时裁剪
 * @param stage            阶段族（必须与 name 的推导结果一致，见 {@link ProgramStage#parse(String)}）
 * @param stageIndex       名称末尾数字后缀（必须与推导一致，见 {@link ProgramStage#stageIndex(String)}）
 * @param dimensionFolder  维度子目录名（"" = shaders/ 根；"world0" / "world-1" / "world1" / 模组维度 "world&lt;id&gt;"）
 * @param vertexShader     顶点着色器路径，相对 shaders/ 根且含维度前缀（如 "world-1/gbuffers_water.vsh"）；
 *                         null = 该文件缺失（消费方必须显式降级并打日志，08-TESTING §4）
 * @param fragmentShader   片段着色器路径，规则同上；null = 缺失；与 vertexShader 不可同时为 null
 * @param uniforms         本程序源码声明的 uniform 列表（见 {@link UniformDecl}）；不可为 null，可为空
 * @param vertexAttributes 本程序使用的顶点属性引用（04-SPEC §4）；不可为 null，可为空（空 = 未解析出声明，由管线层按阶段决定默认格式）
 * @param settings         program 级配置的原样透传：键为官方键去掉"程序名段"后的剩余部分
 *                         （"enabled" / "alphaTest" / "blend" / "scale" / "flip.&lt;缓冲&gt;"），值为原文；未知键保留（T11）
 */
public record Program(
        String name,
        ProgramStage stage,
        int stageIndex,
        String dimensionFolder,
        String vertexShader,
        String fragmentShader,
        List<UniformDecl> uniforms,
        List<VertexAttribute> vertexAttributes,
        Map<String, String> settings) {

    /** 冻结契约声明见类级 Javadoc。 */
    public Program {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("vkdisp: program name must not be blank");
        }
        name = name.trim();
        ProgramStage expectedStage = ProgramStage.parse(name);
        if (stage != expectedStage) {
            throw new IllegalArgumentException(
                    "vkdisp: program '" + name + "' stage mismatch: given=" + stage + ", expected=" + expectedStage);
        }
        int expectedIndex = ProgramStage.stageIndex(name);
        if (stageIndex != expectedIndex) {
            throw new IllegalArgumentException(
                    "vkdisp: program '" + name + "' stageIndex mismatch: given=" + stageIndex + ", expected=" + expectedIndex);
        }
        if (dimensionFolder == null) {
            throw new IllegalArgumentException(
                    "vkdisp: program '" + name + "' dimensionFolder must not be null (use \"\" for the shaders root)");
        }
        dimensionFolder = dimensionFolder.trim();
        if (dimensionFolder.contains("/")) {
            throw new IllegalArgumentException(
                    "vkdisp: program '" + name + "' dimensionFolder must be a single folder name: '" + dimensionFolder + "'");
        }
        vertexShader = trimOptionalPath(name, "vertexShader", vertexShader);
        fragmentShader = trimOptionalPath(name, "fragmentShader", fragmentShader);
        if (vertexShader == null && fragmentShader == null) {
            throw new IllegalArgumentException(
                    "vkdisp: program '" + name + "' has neither .vsh nor .fsh; drop it and log an explicit warning (08-TESTING §4)");
        }
        requireDimensionPrefix(name, "vertexShader", vertexShader, dimensionFolder);
        requireDimensionPrefix(name, "fragmentShader", fragmentShader, dimensionFolder);
        if (uniforms == null) {
            throw new IllegalArgumentException("vkdisp: program '" + name + "' uniforms must not be null (use List.of() when none)");
        }
        uniforms = List.copyOf(uniforms);
        if (vertexAttributes == null) {
            throw new IllegalArgumentException("vkdisp: program '" + name + "' vertexAttributes must not be null (use List.of() when none)");
        }
        vertexAttributes = List.copyOf(vertexAttributes);
        if (settings == null) {
            throw new IllegalArgumentException("vkdisp: program '" + name + "' settings must not be null (use Map.of() when none)");
        }
        LinkedHashMap<String, String> copiedSettings = new LinkedHashMap<>(settings.size());
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException("vkdisp: program '" + name + "' settings keys must not be null/blank");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException(
                        "vkdisp: program '" + name + "' settings value must not be null (key='" + entry.getKey() + "')");
            }
            copiedSettings.put(entry.getKey(), entry.getValue());
        }
        settings = Collections.unmodifiableMap(copiedSettings);
    }

    /**
     * 便捷构造：阶段族与编号从 name 自动推导，uniform/settings 置空。
     * 需要携带 uniform 或 settings 时直接用规范构造器。
     *
     * @param name            程序名
     * @param dimensionFolder 维度子目录（null → ""）
     * @param vertexShader    顶点着色器路径（null = 缺失）
     * @param fragmentShader  片段着色器路径（null = 缺失）
     * @param vertexAttributes 顶点属性引用（null → 空列表）
     */
    public static Program of(
            String name,
            String dimensionFolder,
            String vertexShader,
            String fragmentShader,
            List<VertexAttribute> vertexAttributes) {
        String trimmedName = name == null ? "" : name.trim();
        return new Program(
                trimmedName,
                ProgramStage.parse(trimmedName),
                ProgramStage.stageIndex(trimmedName),
                dimensionFolder == null ? "" : dimensionFolder,
                vertexShader,
                fragmentShader,
                List.of(),
                vertexAttributes == null ? List.of() : vertexAttributes,
                Map.of());
    }

    private static String trimOptionalPath(String programName, String label, String path) {
        if (path == null) {
            return null;
        }
        String trimmed = path.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(
                    "vkdisp: program '" + programName + "' " + label + " is blank (use null when the file is missing)");
        }
        return trimmed;
    }

    private static void requireDimensionPrefix(String programName, String label, String path, String dimensionFolder) {
        if (path == null || dimensionFolder.isEmpty()) {
            return;
        }
        if (!path.startsWith(dimensionFolder + "/")) {
            throw new IllegalArgumentException(
                    "vkdisp: program '" + programName + "' " + label + "='" + path + "' must live under '"
                            + dimensionFolder + "/' (paths are relative to the shaders root)");
        }
    }
}
