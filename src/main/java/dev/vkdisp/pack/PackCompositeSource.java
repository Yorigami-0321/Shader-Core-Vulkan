package dev.vkdisp.pack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import dev.vkdisp.config.OptionDiagnostic;
import dev.vkdisp.config.OptionDiagnosticSink;
import dev.vkdisp.config.PackOptions;
import dev.vkdisp.glsl.TranslateDiagnostic;

/**
 * 【参考调研】P2.4 composite 源生成（库存包 → 可绘制的 composite 片元源）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/04-SPEC.md §2（虚拟资源包命名空间）/ §3.1（只读不写用户包）、
 *    docs/18-PARALLEL.md §5 P2.4 设计 ③④（源生成时机与选项覆盖链）与 docs/07-CONSTRAINTS.md T11
 *    （降级必须显式 WARN）。仓库内文档事实，不受版权保护。
 *    外部候选 IrisShaders / glsl-transformer（GPL-3.0 + 例外条款）→ 按禁止处理
 *    （07-CONSTRAINTS L12 §1.3 陷阱 2 / X21），本任务不读其代码、零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 编排类，不含任何第三方项目代码；
 *    输入数据（用户 shaderpacks 目录）只读，绝不写回（04-SPEC §3.1）。
 *    → 能否并入本项目（MIT）：可以（仅编排本项目自研组件）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：无外部实现可参考（两个参考模组都不做 OF 包加载器），按本项目契约自行编排。
 * 2. 备选：① 直接读包里 composite.fsh 原文当源 —— 否决：选项覆盖没法进（宏要在预处理**前**改写），
 *    且不经 OF 转译的方言源过不了驱动；② 只认第一个包 —— 否决：第一个包坏掉时后面的好包被浪费，
 *    逐包尝试的降级代价只是一次冷路径编译。③ 无 composite 的包直接整体兜底 —— 否决：
 *    库存可能同时有多个包，应逐包找，全军覆没才兜底。
 * 3. 我们的差异点：
 *    ① **逐包尝试 + 单点兜底**：每个含 {@code composite} 程序的包走 load → profile → 覆盖差分 →
 *       编译；片元成功即返回，全部失败/缺失才落到内置 passthrough（T11：兜底必有 WARN）；
 *    ② **profile 空名跳过**（{@code PackOptions.applyProfile("")} 是 EMPTY_PROFILE_NAME 错误，
 *       默认值路径不该产生噪音诊断）；
 *    ③ **覆盖表 = values 与 defaults 的差分**：只把真正被 profile 改掉的选项送进
 *       {@code ShaderPackCompiler}，未动的选项保持包内默认行（改写器零命中 → 零改动）；
 *    ④ 输出全是内存数据，不触碰 {@code com.mojang.*}（18-PARALLEL §2 并行判据）。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径（虚拟包 openResources 时调用一次），清晰优先，不做任何性能优化
 *    （18-PARALLEL §7.7、07-CONSTRAINTS T14 / X14）。
 */
/**
 * 生成 Pass 3 所需的 composite 片元源（P2.4 ③）+ P3.3 deferred 步片元源（同一次扫描双产出）。
 *
 * <p>被 {@code dev.vkdisp.VkDispVirtualPack} 在虚拟资源包 {@code openResources} 时调用
 * （时机：ClientModLoader.finish 之后、首次资源加载之前 —— 此刻配置已加载，见 18-PARALLEL §5 P2.4 ②）。
 *
 * <p><b>已知未覆盖</b>（显式登记，不假装完整）：多维度包只取根命名空间的 {@code composite}
 * （dimensionFolder 前缀如 {@code world0/composite} 暂不参与 Pass 3 选择，P3.x 接维度时再定）；
 * profile 命中但覆盖名未进任何声明行的告警由 {@link ShaderPackCompiler} 包级判定负责。
 */
public final class PackCompositeSource {

    /** 参与 Pass 3 选择的程序名（根命名空间；见类 javadoc 未覆盖项）。 */
    public static final String COMPOSITE_PROGRAM = "composite";

    /**
     * P3.3 deferred 步的程序名（与 {@link #COMPOSITE_PROGRAM} 同一次选包扫描产出）。
     *
     * <p>「同一次扫描」不是优化而是语义：deferred 链步必须与 composite 出自**同一个包**
     * （不同包的选项语义可能冲突，链两端不一致比没有链更糟）。
     */
    public static final String DEFERRED_PROGRAM = "deferred";

    /**
     * 内置兜底源（T11）：passthrough —— 采样 InSampler 原样输出。
     *
     * <p>顶点侧用的是 {@code fullscreen_flipv}（1-v 已在顶点完成），故片元用**原始 vUv**，
     * 与旧行为（blit.fsh 在片元翻转）数学等价。无 {@code #include}、无选项行，
     * 在没有库存包 / 总开关关闭时保证 composite 管线必有源可编（required 管线编译失败会砸启动）。
     * P3.3 起同一文本也兜底 {@code shaders/deferred.fsh}（deferred 亦是 required 管线）。
     */
    public static final String FALLBACK_GLSL = """
            #version 330
            #extension GL_ARB_separate_shader_objects : require
            // vkdisp 内置兜底源（P2.4 composite / P3.3 deferred T11）：passthrough。
            // 顶点 fullscreen_flipv 已完成 1-v 翻转（P-1f），此处用原始 vUv。
            uniform sampler2D InSampler;

            layout(location = 0) in vec2 vUv;
            layout(location = 0) out vec4 fragColor;

            void main() {
                fragColor = vec4(texture(InSampler, vUv).rgb, 1.0);
            }
            """;

    /**
     * 一次生成的结果（P3.3 起双源：composite + deferred 同一次扫描产出）。
     *
     * @param source             Pass 3 composite 片元源（永不 null / 空）
     * @param deferredSource     P3.3 deferred 步片元源（永不 null / 空：取不到 → 内置 passthrough）
     * @param hasDeferredProgram 所选包真实产出了 deferred 片元（false = 链路不开 deferred 步）
     * @param packName           产出源的包名；兜底时为 {@code null}
     * @param fallback           true = 使用了内置兜底（必然伴随 WARN 诊断）
     * @param profile            实际生效的 profile 名（空串 = 默认值路径）
     * @param diagnostics        本次生成的全部诊断（扫描问题 + 包装载 + profile/覆盖 + 阶段编译；不可变）
     */
    public record Result(
            String source,
            String deferredSource,
            boolean hasDeferredProgram,
            String packName,
            boolean fallback,
            String profile,
            List<TranslateDiagnostic> diagnostics) {

        /** 归一构造：两源非空（空视为调用方错误直接抛），profile 归一，列表冻结。 */
        public Result {
            Objects.requireNonNull(source, "source");
            if (source.isBlank()) {
                throw new IllegalArgumentException("vkdisp: composite 源不许为空白");
            }
            Objects.requireNonNull(deferredSource, "deferredSource");
            if (deferredSource.isBlank()) {
                throw new IllegalArgumentException("vkdisp: deferred 源不许为空白");
            }
            profile = profile == null ? "" : profile;
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    private PackCompositeSource() {}

    /**
     * 扫描库存并生成 composite 片元源。永不抛非受检异常（所有失败降级为诊断 + 最终兜底）。
     *
     * @param inventoryDir {@code <gameDir>/shaderpacks}；null / 不存在 → 直接兜底（扫描器产出诊断）
     * @param profileName  profile 预设名；null / 空白 = 使用包默认值
     */
    public static Result generate(Path inventoryDir, String profileName) {
        List<TranslateDiagnostic> diagnostics = new ArrayList<>();
        String profile = profileName == null ? "" : profileName.trim();

        ShaderPackScanner.ScanResult scan = ShaderPackScanner.scan(inventoryDir);
        for (ShaderPackScanner.PackProblem problem : scan.problems()) {
            diagnostics.add(scanProblemDiagnostic(problem));
        }

        for (ShaderPackScanner.DiscoveredPack discovered : scan.packs()) {
            ShaderPackService.LoadResult loaded = ShaderPackService.load(discovered);
            diagnostics.addAll(loaded.diagnostics());
            ShaderPack pack = loaded.pack();
            if (pack == null) {
                continue; // load 诊断已说明原因，逐包继续找
            }
            boolean hasComposite = pack.programs().stream()
                    .anyMatch(program -> COMPOSITE_PROGRAM.equals(program.name()));
            if (!hasComposite) {
                diagnostics.add(TranslateDiagnostic.of(
                        TranslateDiagnostic.Severity.INFO,
                        "vkdisp: 包 '" + pack.name() + "' 不含 composite 程序，跳过（继续找下一个包）",
                        pack.name(), TranslateDiagnostic.UNKNOWN_LINE));
                continue;
            }

            // F 线选项链：默认值容器 → 应用 profile（空名跳过）→ 与默认值差分出覆盖表。
            List<OptionDiagnostic> optionDiagnostics = new ArrayList<>();
            PackOptions options = PackOptions.of(pack, OptionDiagnosticSink.collecting(optionDiagnostics));
            if (!profile.isEmpty()) {
                options.applyProfile(profile, pack.profiles());
            }
            for (OptionDiagnostic diagnostic : optionDiagnostics) {
                diagnostics.add(optionDiagnostic(diagnostic, pack.name()));
            }
            Map<String, String> overrides = diffAgainstDefaults(options);

            ShaderPackCompiler.CompileResult compiled = ShaderPackCompiler.compile(discovered, overrides);
            diagnostics.addAll(compiled.diagnostics());
            String compositeSource = firstProgramFragment(compiled, COMPOSITE_PROGRAM);
            if (compositeSource != null) {
                // P3.3：同一次编译产物里顺带取 deferred 片元（不另开选包循环 —— composite 与
                // deferred 必须出自**同一个包**，否则链路两端选项语义不一致）。
                String deferredSource = firstProgramFragment(compiled, DEFERRED_PROGRAM);
                boolean hasDeferred = deferredSource != null;
                if (!hasDeferred) {
                    deferredSource = FALLBACK_GLSL;
                    // 包声明了 deferred 但片元阶段无成功产出 → 按无 deferred 处理（T11：显式
                    // 可见，不硬开一个喂兜底源的步；编译失败本身的 ERROR 已随 stages 进诊断）。
                    boolean declared = pack.programs().stream()
                            .anyMatch(program -> DEFERRED_PROGRAM.equals(program.name()));
                    if (declared) {
                        diagnostics.add(TranslateDiagnostic.of(
                                TranslateDiagnostic.Severity.WARN,
                                "vkdisp: 包 '" + pack.name() + "' 声明了 deferred 但片元阶段无成功产出"
                                        + "，P3.3 deferred 步按未启用处理",
                                pack.name(), TranslateDiagnostic.UNKNOWN_LINE));
                    } else {
                        diagnostics.add(TranslateDiagnostic.of(
                                TranslateDiagnostic.Severity.INFO,
                                "vkdisp: 包 '" + pack.name() + "' 不含 deferred 程序，"
                                        + "P3.3 deferred 步按未启用处理（链路保持 P3.2 直连）",
                                pack.name(), TranslateDiagnostic.UNKNOWN_LINE));
                    }
                }
                return new Result(compositeSource, deferredSource, hasDeferred,
                        pack.name(), false, profile, diagnostics);
            }
            diagnostics.add(TranslateDiagnostic.of(
                    TranslateDiagnostic.Severity.WARN,
                    "vkdisp: 包 '" + pack.name() + "' 的 composite 片元阶段无成功产出，尝试下一个包",
                    pack.name(), TranslateDiagnostic.UNKNOWN_LINE));
        }

        diagnostics.add(TranslateDiagnostic.of(
                TranslateDiagnostic.Severity.WARN,
                "vkdisp: 库存中没有可用的 composite 片元源，使用内置 passthrough 兜底"
                        + "（inventory=" + inventoryDir + ", profile='" + profile + "')",
                String.valueOf(inventoryDir), TranslateDiagnostic.UNKNOWN_LINE));
        return new Result(FALLBACK_GLSL, FALLBACK_GLSL, false, null, true, profile, diagnostics);
    }

    /** 覆盖表 = 当前值与有效默认值的差分（只送真正被改掉的选项进改写器）。 */
    private static Map<String, String> diffAgainstDefaults(PackOptions options) {
        Map<String, String> defaults = options.defaults();
        Map<String, String> overrides = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : options.values().entrySet()) {
            if (!entry.getValue().equals(defaults.get(entry.getKey()))) {
                overrides.put(entry.getKey(), entry.getValue());
            }
        }
        return overrides;
    }

    /** 取第一个成功的指定程序 FRAGMENT 源（composite / deferred 共用）；没有返回 null。 */
    private static String firstProgramFragment(ShaderPackCompiler.CompileResult compiled, String programName) {
        for (ShaderPackCompiler.CompiledStage stage : compiled.stages()) {
            if (stage.stage() == dev.vkdisp.glsl.translate.ShaderStage.FRAGMENT
                    && stage.isSuccess()
                    && isProgramName(stage.programName(), programName)) {
                return stage.result().text();
            }
        }
        return null;
    }

    /** 根程序 {@code composite} 或维度程序 {@code world0/composite} 都算（qualifiedName 形态）。 */
    private static boolean isProgramName(String qualifiedName, String programName) {
        return programName.equals(qualifiedName) || qualifiedName.endsWith("/" + programName);
    }

    /** 扫描问题 → 诊断（分级与 VkDispPackScan 同口径：损坏=ERROR、缺失/空=INFO、结构=WARN）。 */
    private static TranslateDiagnostic scanProblemDiagnostic(ShaderPackScanner.PackProblem problem) {
        TranslateDiagnostic.Severity severity = switch (problem.kind()) {
            case BROKEN_ZIP -> TranslateDiagnostic.Severity.ERROR;
            case INVENTORY_MISSING, NO_PACKS_FOUND -> TranslateDiagnostic.Severity.INFO;
            default -> TranslateDiagnostic.Severity.WARN;
        };
        return TranslateDiagnostic.of(severity,
                "shaderpacks 扫描问题 [" + problem.kind() + "]: " + problem.message(),
                String.valueOf(problem.entry()), TranslateDiagnostic.UNKNOWN_LINE);
    }

    /** F 线 OptionDiagnostic → TranslateDiagnostic（severity 直映射，message 带上稳定诊断码）。 */
    private static TranslateDiagnostic optionDiagnostic(OptionDiagnostic diagnostic, String sourceFile) {
        TranslateDiagnostic.Severity severity = switch (diagnostic.severity()) {
            case ERROR -> TranslateDiagnostic.Severity.ERROR;
            case WARN -> TranslateDiagnostic.Severity.WARN;
            case INFO -> TranslateDiagnostic.Severity.INFO;
        };
        return TranslateDiagnostic.of(severity,
                "选项 [" + diagnostic.code() + "] " + diagnostic.message(),
                sourceFile, TranslateDiagnostic.UNKNOWN_LINE);
    }
}
