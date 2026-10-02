package dev.vkdisp.pack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import dev.vkdisp.config.OptionDiagnostic;
import dev.vkdisp.config.PackOptionStore;
import dev.vkdisp.config.PackOptions;
import dev.vkdisp.config.PackOptionsSession;
import dev.vkdisp.glsl.TranslateDiagnostic;

/**
 * 【参考调研】P2.4 composite 源生成（库存包 → 可绘制的 composite 片元源）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/04-SPEC.md §2（虚拟资源包命名空间）/ §3.1（只读不写用户包）、
 *    docs/18-PARALLEL.md §5 P2.4 设计 ③④（源生成时机与选项覆盖链）、docs/08-TESTING.md §1
 *    P4.3 行（"pack 声明的选项能渲染并能改" —— "改"经 PackOptionStore 回放进本生效链）与
 *    docs/07-CONSTRAINTS.md T11（降级必须显式 WARN）。仓库内文档事实，不受版权保护。
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
 *    ③ **覆盖表 = values 与 defaults 的差分**：只把真正被 profile / 选项屏幕改掉的选项送进
 *       {@code ShaderPackCompiler}，未动的选项保持包内默认行（改写器零命中 → 零改动）；
 *       P4.3 起 profile 之后回放 {@link PackOptionStore}（GUI 改动优先于 profile，与
 *       {@link PackOptionsSession} 同链 —— 选项屏幕与生效路径共用一条编排，单一真源）；
 *    ④ 输出全是内存数据，不触碰 {@code com.mojang.*}（18-PARALLEL §2 并行判据）。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径（虚拟包 openResources 时调用一次），清晰优先，不做任何性能优化
 *    （18-PARALLEL §7.7、07-CONSTRAINTS T14 / X14）。
 */
/**
 * 生成 Pass 3 所需的 composite 片元源（P2.4 ③）+ P3.3 deferred 步片元源 + P4.1.4 final 步片元源
 * （同一次扫描三产出，后两者与 composite 同包同维度配对）。
 *
 * <p>被 {@code dev.vkdisp.VkDispVirtualPack} 在虚拟资源包 {@code openResources} 时调用
 * （时机：ClientModLoader.finish 之后、首次资源加载之前 —— 此刻配置已加载，见 18-PARALLEL §5 P2.4 ②）。
 *
 * <p><b>维度选择</b>（P4.1）：多维度包按 {@code world0 > 根命名空间 > 其它维度} 择优取
 * {@code composite}，{@code deferred} 与之**同维度配对**（避免「下界 composite + 主世界 deferred」串链）。
 * <p><b>已知未覆盖</b>（显式登记，不假装完整）：选择维度**硬编码主世界偏好**，不随玩家当前维度动态切换
 * （接维度切换时再定，18-PARALLEL 注册缺口照旧）；profile 命中但覆盖名未进任何声明行的告警由
 * {@link ShaderPackCompiler} 包级判定负责。
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
     * P4.1.4 final 步的程序名（与 {@link #COMPOSITE_PROGRAM} 同一次选包扫描产出）。
     *
     * <p>同包同维度配对（与 deferred 同规则）：final 是「present 到主目标前的最后一步」，
     * 它的输入语义建立在同包 composite 链的输出之上，跨包/跨维度串链比没有 final 更糟。
     */
    public static final String FINAL_PROGRAM = "final";

    /**
     * P4.2 切包（{@code 08-TESTING.md} §6）：包选择的保留值 —— 强制内置 passthrough，
     * 不加载任何库存包。是<b>保留名</b>：即使库存里真有叫 {@code none} 的包也不参与选择
     * （选择语义优先；想要该包请改用其真实名的其它写法，登记于 {@link #generate} javadoc）。
     */
    public static final String SELECTION_NONE = "none";

    /**
     * 内置兜底源（T11）：passthrough —— 采样 InSampler 原样输出。
     *
     * <p>顶点侧用的是 {@code fullscreen_flipv}（1-v 已在顶点完成），故片元用**原始 vUv**，
     * 与旧行为（blit.fsh 在片元翻转）数学等价。无 {@code #include}、无选项行，
     * 在没有库存包 / 总开关关闭时保证 composite 管线必有源可编（required 管线编译失败会砸启动）。
     * P3.3 起同一文本也兜底 {@code shaders/deferred.fsh}；P4.1.4 起兜底 {@code shaders/final.fsh}
     * （deferred / final 亦是 required 管线，必须总有源可编）。
     */
    public static final String FALLBACK_GLSL = """
            #version 330
            #extension GL_ARB_separate_shader_objects : require
            // vkdisp 内置兜底源（P2.4 composite / P3.3 deferred / P4.1.4 final T11）：passthrough。
            // 顶点 fullscreen_flipv 已完成 1-v 翻转（P-1f），此处用原始 vUv。
            uniform sampler2D InSampler;

            layout(location = 0) in vec2 vUv;
            layout(location = 0) out vec4 fragColor;

            void main() {
                fragColor = vec4(texture(InSampler, vUv).rgb, 1.0);
            }
            """;

    /**
     * 一次生成的结果（P3.3 双源 → P4.1.4 三源：composite + deferred + final 同一次扫描产出）。
     *
     * @param source             Pass 3 composite 片元源（永不 null / 空）
     * @param deferredSource     P3.3 deferred 步片元源（永不 null / 空：取不到 → 内置 passthrough）
     * @param hasDeferredProgram 所选包真实产出了 deferred 片元（false = 链路不开 deferred 步）
     * @param finalSource        P4.1.4 final 步片元源（永不 null / 空：取不到 → 内置 passthrough）
     * @param hasFinalProgram    所选包真实产出了 final 片元（false = 链路不开 final 步）
     * @param packName           产出源的包名；兜底时为 {@code null}
     * @param fallback           true = 使用了内置兜底（必然伴随 WARN 诊断）
     * @param profile            实际生效的 profile 名（空串 = 默认值路径）
     * @param diagnostics        本次生成的全部诊断（扫描问题 + 包装载 + profile/覆盖 + 阶段编译；不可变）
     */
    public record Result(
            String source,
            String deferredSource,
            boolean hasDeferredProgram,
            String finalSource,
            boolean hasFinalProgram,
            String packName,
            boolean fallback,
            String profile,
            List<TranslateDiagnostic> diagnostics) {

        /** 归一构造：三源非空（空视为调用方错误直接抛），profile 归一，列表冻结。 */
        public Result {
            Objects.requireNonNull(source, "source");
            if (source.isBlank()) {
                throw new IllegalArgumentException("vkdisp: composite 源不许为空白");
            }
            Objects.requireNonNull(deferredSource, "deferredSource");
            if (deferredSource.isBlank()) {
                throw new IllegalArgumentException("vkdisp: deferred 源不许为空白");
            }
            Objects.requireNonNull(finalSource, "finalSource");
            if (finalSource.isBlank()) {
                throw new IllegalArgumentException("vkdisp: final 源不许为空白");
            }
            profile = profile == null ? "" : profile;
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    private PackCompositeSource() {}

    /**
     * 旧签名 = <b>无包选择过滤</b>（自动：扫描顺序取第一个能编出 composite 的包，P2.4 既有行为）。
     * P4.2 起委托 {@link #generate(Path, String, String)}，{@code packSelection=""}。
     *
     * @param inventoryDir {@code <gameDir>/shaderpacks}；null / 不存在 → 直接兜底（扫描器产出诊断）
     * @param profileName  profile 预设名；null / 空白 = 使用包默认值
     */
    public static Result generate(Path inventoryDir, String profileName) {
        return generate(inventoryDir, profileName, "");
    }

    /**
     * 扫描库存并生成 composite 片元源（P4.2 起带包选择过滤，{@code 08-TESTING.md} §6 切包驱动）。
     * 无选项存储（P4.3 {@link #generate(Path, String, String, PackOptionStore)} 传 null 的等价形式）。
     *
     * @param inventoryDir  {@code <gameDir>/shaderpacks}；null / 不存在 → 直接兜底（扫描器产出诊断）
     * @param profileName   profile 预设名；null / 空白 = 使用包默认值
     * @param packSelection 包选择三态，语义见 {@link #generate(Path, String, String, PackOptionStore)}
     */
    public static Result generate(Path inventoryDir, String profileName, String packSelection) {
        return generate(inventoryDir, profileName, packSelection, null);
    }

    /**
     * 扫描库存并生成 composite 片元源（P4.2 三态选择 + P4.3 选项存储回放）。
     * 永不抛非受检异常（所有失败降级为诊断 + 最终兜底）。
     *
     * @param inventoryDir  {@code <gameDir>/shaderpacks}；null / 不存在 → 直接兜底（扫描器产出诊断）
     * @param profileName   profile 预设名；null / 空白 = 使用包默认值
     * @param packSelection 包选择三态：{@code ""} = 自动（扫描顺序）；{@link #SELECTION_NONE} =
     *                      强制内置 passthrough（保留名，不扫包）；其它 = 按包名<b>精确匹配</b>
     *                      （{@code DiscoveredPack.name} = 扫包日志 {@code pack[N] name=}）——
     *                      只有该包参与选择，匹配失败 / 无成功产出 → 显式 WARN + 兜底，
     *                      <b>绝不静默落到别的包</b>（否则 §6 的 S2 判据失去意义）
     * @param store         P4.3 选项存储：在 profile 之后、覆盖差分之前按包回放（GUI 优先于
     *                      profile）；null = 无存储（与旧行为逐字节等价）。只回放<b>所选包</b>
     *                      自己的条目（存储按键的包段过滤）
     */
    public static Result generate(Path inventoryDir, String profileName, String packSelection,
            PackOptionStore store) {
        List<TranslateDiagnostic> diagnostics = new ArrayList<>();
        String profile = profileName == null ? "" : profileName.trim();
        String selection = packSelection == null ? "" : packSelection.trim();

        if (SELECTION_NONE.equals(selection)) {
            // 保留名强制兜底：不扫包（用户意图明确，T11 仍用 WARN 标出兜底事实）。
            diagnostics.add(TranslateDiagnostic.of(
                    TranslateDiagnostic.Severity.WARN,
                    "vkdisp: 包选择 = 'none'（配置 shaderPack=none），不加载库存包，"
                            + "使用内置 passthrough 兜底",
                    SELECTION_NONE, TranslateDiagnostic.UNKNOWN_LINE));
            return fallbackResult(profile, diagnostics);
        }
        boolean named = !selection.isEmpty();

        ShaderPackScanner.ScanResult scan = ShaderPackScanner.scan(inventoryDir);
        for (ShaderPackScanner.PackProblem problem : scan.problems()) {
            diagnostics.add(scanProblemDiagnostic(problem));
        }
        if (named) {
            // 过滤口径显式可见（T11）：选了名却没有候选 = 后面必走兜底，先留证据。
            long candidates = scan.packs().stream()
                    .filter(discovered -> selection.equals(discovered.name()))
                    .count();
            diagnostics.add(TranslateDiagnostic.of(
                    TranslateDiagnostic.Severity.INFO,
                    "vkdisp: 包选择过滤：仅接受 name='" + selection + "'（库存 "
                            + scan.packs().size() + " 个包，候选 " + candidates + " 个）",
                    selection, TranslateDiagnostic.UNKNOWN_LINE));
        }
        boolean nameSeen = false;

        for (ShaderPackScanner.DiscoveredPack discovered : scan.packs()) {
            if (named && !selection.equals(discovered.name())) {
                continue; // 指定选择下其它包不参与（不加载、不留诊断噪音）
            }
            nameSeen = true;
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

            // F 线选项链：默认 → profile → 选项存储回放（P4.3，GUI 优先）→ 与默认值差分出覆盖表。
            // 与选项屏幕共用 PackOptionsSession（同一编排 = 单一真源，两侧永不漂移）。
            PackOptionsSession session = PackOptionsSession.create(pack, profile, store);
            for (OptionDiagnostic diagnostic : session.buildDiagnostics()) {
                diagnostics.add(optionDiagnostic(diagnostic, pack.name()));
            }
            Map<String, String> overrides = diffAgainstDefaults(session.options());

            ShaderPackCompiler.CompileResult compiled =
                    PackCompileCache.getOrCompile(discovered, overrides);
            diagnostics.addAll(compiled.diagnostics());
            Selection composite = selectProgramFragment(compiled, COMPOSITE_PROGRAM, null);
            if (composite != null) {
                // P3.3：同一次编译产物里顺带取 deferred 片元（不另开选包循环 —— composite 与
                // deferred 必须出自**同一个包**，否则链路两端选项语义不一致）。
                // P4.1：维度同配 —— deferred 优先取与 composite **同维度目录**的程序
                // （BLS 类多维度包 world-1 会先于 world0 排序，不配对会链起「下界 composite + 主世界 deferred」）。
                String compositeDimension = dimensionOf(composite.qualifiedName());
                diagnostics.add(TranslateDiagnostic.of(
                        TranslateDiagnostic.Severity.INFO,
                        "vkdisp: composite 程序选中 '" + composite.qualifiedName() + "'（维度偏好 world0 > 根 > 其它）",
                        pack.name(), TranslateDiagnostic.UNKNOWN_LINE));
                String deferredSource = selectDeferredSource(compiled, compositeDimension);
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
                // P4.1.4：同一次编译产物里再顺带取 final 片元（同包 + 与 composite 同维度配对，
                // 与 deferred 完全同规则 —— final 是 present 前最后一步，输入语义建立在本包链输出上）。
                String finalSource = selectFinalSource(compiled, compositeDimension);
                boolean hasFinal = finalSource != null;
                if (!hasFinal) {
                    finalSource = FALLBACK_GLSL;
                    boolean declaredFinal = pack.programs().stream()
                            .anyMatch(program -> FINAL_PROGRAM.equals(program.name()));
                    if (declaredFinal) {
                        diagnostics.add(TranslateDiagnostic.of(
                                TranslateDiagnostic.Severity.WARN,
                                "vkdisp: 包 '" + pack.name() + "' 声明了 final 但片元阶段无成功产出"
                                        + "，P4.1.4 final 步按未启用处理",
                                pack.name(), TranslateDiagnostic.UNKNOWN_LINE));
                    } else {
                        diagnostics.add(TranslateDiagnostic.of(
                                TranslateDiagnostic.Severity.INFO,
                                "vkdisp: 包 '" + pack.name() + "' 不含 final 程序，"
                                        + "P4.1.4 final 步按未启用处理（composite 直写主目标）",
                                pack.name(), TranslateDiagnostic.UNKNOWN_LINE));
                    }
                }
                return new Result(composite.source(), deferredSource, hasDeferred,
                        finalSource, hasFinal, pack.name(), false, profile, diagnostics);
            }
            diagnostics.add(TranslateDiagnostic.of(
                    TranslateDiagnostic.Severity.WARN,
                    named
                            ? "vkdisp: 指定包 '" + pack.name()
                            + "' 的 composite 片元阶段无成功产出（shaderPack 指定下不换包，将走兜底）"
                            : "vkdisp: 包 '" + pack.name() + "' 的 composite 片元阶段无成功产出，尝试下一个包",
                    pack.name(), TranslateDiagnostic.UNKNOWN_LINE));
        }

        // 全军覆没 → 兜底（T11：原因按选择模式显式区分，不混成一句「没有可用的」）。
        if (named) {
            diagnostics.add(TranslateDiagnostic.of(
                    TranslateDiagnostic.Severity.WARN,
                    (nameSeen
                            ? "vkdisp: 指定包 '" + selection + "' 无成功产出"
                            : "vkdisp: 指定包 '" + selection + "' 不在库存中")
                            + "（inventory=" + inventoryDir + "），使用内置 passthrough 兜底"
                            + "（不落到其它包）",
                    selection, TranslateDiagnostic.UNKNOWN_LINE));
        } else {
            diagnostics.add(TranslateDiagnostic.of(
                    TranslateDiagnostic.Severity.WARN,
                    "vkdisp: 库存中没有可用的 composite 片元源，使用内置 passthrough 兜底"
                            + "（inventory=" + inventoryDir + ", profile='" + profile + "')",
                    String.valueOf(inventoryDir), TranslateDiagnostic.UNKNOWN_LINE));
        }
        return fallbackResult(profile, diagnostics);
    }

    /** 统一兜底出口：三源全 passthrough、链路开关全 false、{@code packName=null}（T11 诊断由调用方先落）。 */
    private static Result fallbackResult(String profile, List<TranslateDiagnostic> diagnostics) {
        return new Result(FALLBACK_GLSL, FALLBACK_GLSL, false, FALLBACK_GLSL, false,
                null, true, profile, diagnostics);
    }

    /**
     * 选项屏幕（P4.3）取"当前生效包"的模型：与 {@link #generate} <b>同一套三态选择</b>
     * （{@code ""} 扫描顺序 / {@link #SELECTION_NONE} 保留名 / 其它精确匹配），但<b>只到装载</b>为止
     * —— 不编译（开屏只读选项表，编译是秒级冷路径，屏幕上没有它的语义）。
     *
     * <p><b>已知未覆盖（登记，不假装一致）</b>：本方法按「有 composite 程序」选包，
     * 而 {@code generate} 按「composite <b>编译成功</b>」选包 —— 当某个包 composite 编译失败、
     * 库存里还有下一个可用包时，两者会选出不同的包（屏幕改的是 A，画面用的是 B）。
     * 该分叉只在"能装载但编译不过"的坏包上出现，出现时 generate 侧必有 ERROR 诊断可见；
     * 修复方向（让屏幕也走一次编译选包）留待真实复现时再定（X9：没有实证不预修）。
     *
     * @param inventoryDir  {@code <gameDir>/shaderpacks}；null / 不存在 → 空
     * @param packSelection 三态同 {@link #generate(Path, String, String, PackOptionStore)}
     * @return 所选包的模型；无可选包（含 {@code none}、指定名不存在）= 空
     */
    public static Optional<ShaderPack> loadSelectedForOptions(Path inventoryDir, String packSelection) {
        String selection = packSelection == null ? "" : packSelection.trim();
        if (SELECTION_NONE.equals(selection)) {
            return Optional.empty(); // 保留名 = 强制无包（与 generate 的兜底语义对齐）
        }
        ShaderPackScanner.ScanResult scan = ShaderPackScanner.scan(inventoryDir);
        boolean named = !selection.isEmpty();
        for (ShaderPackScanner.DiscoveredPack discovered : scan.packs()) {
            if (named && !selection.equals(discovered.name())) {
                continue;
            }
            ShaderPack pack = ShaderPackService.load(discovered).pack();
            if (pack == null) {
                continue; // load 诊断已产生（本方法不转交，屏幕侧只要"选不到"这一事实）
            }
            boolean hasComposite = pack.programs().stream()
                    .anyMatch(program -> COMPOSITE_PROGRAM.equals(program.name()));
            if (hasComposite) {
                return Optional.of(pack);
            }
        }
        return Optional.empty();
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

    /** 一次程序选中的结果：产出源文本 + 其限定名（如 {@code world0/composite}，供维度配对）。 */
    private record Selection(String source, String qualifiedName) {}

    /** 取指定程序的 FRAGMENT 源并按维度偏好择优；没有成功产出返回 null。 */
    private static Selection selectProgramFragment(
            ShaderPackCompiler.CompileResult compiled, String programName, String preferredDimension) {
        Selection best = null;
        int bestRank = Integer.MAX_VALUE;
        for (ShaderPackCompiler.CompiledStage stage : compiled.stages()) {
            if (stage.stage() != dev.vkdisp.glsl.translate.ShaderStage.FRAGMENT
                    || !stage.isSuccess()
                    || !isProgramName(stage.programName(), programName)) {
                continue;
            }
            int rank = dimensionRank(dimensionOf(stage.programName()), preferredDimension);
            if (rank < bestRank) {
                best = new Selection(stage.result().text(), stage.programName());
                bestRank = rank;
            }
        }
        return best;
    }

    /**
     * deferred 片元选择：优先与 composite **同维度目录**，否则按维度偏好
     * （world0 > 根 > 其它）；无成功产出返回 null。
     */
    private static String selectDeferredSource(ShaderPackCompiler.CompileResult compiled,
            String compositeDimension) {
        Selection selection = selectProgramFragment(compiled, DEFERRED_PROGRAM, compositeDimension);
        return selection == null ? null : selection.source();
    }

    /**
     * final 片元选择（P4.1.4）：与 deferred 同规则 —— 优先与 composite **同维度目录**，
     * 否则按维度偏好（world0 > 根 > 其它）；无成功产出返回 null。
     */
    private static String selectFinalSource(ShaderPackCompiler.CompileResult compiled,
            String compositeDimension) {
        Selection selection = selectProgramFragment(compiled, FINAL_PROGRAM, compositeDimension);
        return selection == null ? null : selection.source();
    }

    /**
     * 维度目录权重（越小越优先）：完全匹配 {@code preferred} → 0；
     * 否则 world0（主世界，当前渲染维度）→ 1、根命名空间（无维度覆盖，OF 语义的默认）→ 2、其它维度 → 3。
     * 并列取**先出现者**（stages 按限定名排序，结果确定）。
     */
    private static int dimensionRank(String dimension, String preferred) {
        if (preferred != null && preferred.equals(dimension)) {
            return 0;
        }
        if ("world0".equals(dimension)) {
            return 1;
        }
        if (dimension.isEmpty()) {
            return 2;
        }
        return 3;
    }

    /** 限定名前缀的维度目录（{@code world0/composite} → {@code world0}；根 {@code composite} → 空串）。 */
    private static String dimensionOf(String qualifiedName) {
        int slash = qualifiedName.indexOf('/');
        return slash < 0 ? "" : qualifiedName.substring(0, slash);
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
