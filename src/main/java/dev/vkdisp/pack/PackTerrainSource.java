package dev.vkdisp.pack;
/**
 * 【参考调研】GAP-003「包自己的地形片元」选取（库存包 → 转译终稿 + 渲染契约）/ 本仓库自研编排
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库自研的 B 线 ShaderPackScanner / ShaderPackService / ShaderPackCompiler /
 *    PackCompileCache 与 P4.2 建立的包选择三态口径（docs/18-PARALLEL.md §5 P4.2）——
 *    同为 MIT 自有代码，无任何外部参考对象。外部候选 IrisShaders / glsl-transformer（GPL-3.0）
 *    按禁止处理（07-CONSTRAINTS L12），本任务不读其代码、零代码行并入。
 *    → 能否并入本项目（MIT）：可以（仅编排本项目自研组件）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：无外部实现可参考（原版没有包加载器），按本项目契约自行编排。
 * 2. 备选：① 复用 PackCompositeSource 的 generate 顺带返回地形源 —— 否决：那条链的语义是
 *    「必有源（兜底 passthrough）」，而地形片的正确兜底是**不接线**（用原版 core/terrain）；
 *    把「没有就退回原版」硬塞进「必有源」的记录里会让兜底与真实产出长得一样（X42 教训）。
 *    ② 渲染期现扫包 —— 否决：那是秒级冷路径，绝不能进渲染线程（支柱③）。
 * 3. 我们的差异点：
 *    ① **包选择与 composite 同口径**（三态：空=扫描顺序 / none=强制不接线 / 其它=精确包名），
 *       保证「composite 用的包」与「地形用的包」永远是同一个；
 *    ② **无产出即显式 null**（调用方据此保持原版 core/terrain），并 WARN 说明原因；
 *    ③ **维度偏好 world0 > 根 > 其它**（与 composite 同规则，主世界地形语义最常用）。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，只读思路，零代码并入。
 * 5. 性能基线：冷路径（虚拟包 openResources 时一次），清晰优先，不做性能优化。
 */
import dev.vkdisp.config.OptionDiagnostic;
import dev.vkdisp.config.PackCapabilityGate;
import dev.vkdisp.config.PackOptionOverride;
import dev.vkdisp.config.PackOptionStore;
import dev.vkdisp.config.PackOptions;
import dev.vkdisp.config.PackOptionsSession;
import dev.vkdisp.glsl.TranslateDiagnostic;
import dev.vkdisp.glsl.translate.ShaderStage;
import dev.vkdisp.pack.properties.PackLangFile;
import dev.vkdisp.pipeline.model.PackTerrainProgram;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 选出「所选包的 gbuffers_terrain 片元转译终稿」，并解析成 {@link PackTerrainProgram} 契约。
 *
 * <p>被 {@code dev.vkdisp.VkDispVirtualPack} 在虚拟资源包 openResources 时调用（与 composite 源同时机）。
 * 永不抛非受检异常：任何失败都降级成 {@code program == null} + 诊断，调用方据此**保持原版管线**。
 */
public final class PackTerrainSource {

    /** 参与选择的地形程序名（OF 语义：gbuffers 族；根命名空间名，维度目录由限定名体现）。 */
    public static final String TERRAIN_PROGRAM = "gbuffers_terrain";

    /**
     * GAP-027 第一条被接的非地形程序。
     *
     * <p>🔖 <b>为什么先挑它</b>（判据不是「哪个容易」而是「哪个复用已证的收口点」）：
     * 水的几何走的是<b>与地形同一个</b> {@code ChunkSectionLayer} 体系（TRANSLUCENT 层），
     * 而我方的 M-01 注入点今天<b>已经覆盖</b>那一层、只是对它返回「不换」。
     * 且它产出的 colortex1 被 {@code composite.glsl:45} / {@code composite5.glsl:31} /
     * {@code final.glsl:16} 读回去 ⇒ 接上就有可观察后果，不是「接了没人看」。
     */
    public static final String WATER_PROGRAM = "gbuffers_water";

    /** 保留名：强制「不接线」（与 PackCompositeSource.SELECTION_NONE 同义，避免两处字面量）。 */
    public static final String SELECTION_NONE = PackCompositeSource.SELECTION_NONE;

    /**
     * 一次选取的结果。
     *
     * @param program     契约；{@code null} = **不接线**（保持原版 core/terrain），不是错误
     * @param packName    产出该契约的包名；{@code null} = 没选到包
     * @param profile     实际生效的 profile 名（空串 = 默认值路径）
     * @param diagnostics 本次选取的全部诊断（不可变）
     */
    public record Result(PackTerrainProgram program, String packName, String profile,
            List<TranslateDiagnostic> diagnostics) {

        public Result {
            profile = profile == null ? "" : profile;
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }

        /** 是否真的取到了可接线的包地形片元（= 派生 MRT 管线该换片元着色器）。 */
        public boolean wired() {
            return program != null;
        }
    }

    private PackTerrainSource() {}

    /**
     * 扫描库存并选出包地形片元（无选项存储的等价形式）。
     *
     * @param inventoryDir {@code <gameDir>/shaderpacks}；null / 不存在 → 直接不接线（附诊断）
     * @param profileName  profile 预设名；null / 空白 = 使用包默认值
     * @param packSelection 包选择三态，语义同 {@link PackCompositeSource#generate} 的同名参数
     */
    public static Result generate(Path inventoryDir, String profileName, String packSelection) {
        return generate(inventoryDir, profileName, packSelection, null);
    }

    /**
     * 扫描库存并选出包地形片元（P4.2 三态选择 + P4.3 选项存储回放，与 composite 同一套链）。
     *
     * <p>🔖 <b>选包口径必须与 composite 一致</b>：composite 与地形分属不同阶段，若两侧各自
     * 选包就可能「合成的画面来自 A 包、地形的画面来自 B 包」—— 那是跨包串链，
     * 比不接线更难归因（同 P3.3 的 deferred 配对理由）。
     */
    public static Result generate(Path inventoryDir, String profileName, String packSelection,
            PackOptionStore store) {
        return generate(inventoryDir, profileName, packSelection, store, TERRAIN_PROGRAM);
    }

    /**
     * GAP-027：按<b>指定程序名</b>选片元 —— 地形之外的 {@code gbuffers_*} 走<b>同一条</b>
     * 选包 / 门控 / 选项覆盖 / 编译 / 槽位兑现链。
     *
     * <p>🔖 <b>为什么必须复用而不是另写一份</b>：这条链上有三处「两侧口径必须一致」的地方
     * （选哪个包、能力门控改了什么选项、槽位兑现被不被拒绝）。另写一份迟早会有一份忘了改，
     * 而那类错的形状永远是「画面有内容、通道全错、日志全绿」。
     */
    public static Result generate(Path inventoryDir, String profileName, String packSelection,
            PackOptionStore store, String programName) {
        List<TranslateDiagnostic> diagnostics = new ArrayList<>();
        String profile = profileName == null ? "" : profileName.trim();
        String selection = packSelection == null ? "" : packSelection.trim();

        if (SELECTION_NONE.equals(selection)) {
            diagnostics.add(TranslateDiagnostic.of(TranslateDiagnostic.Severity.WARN,
                    "vkdisp: 包选择 = 'none'（配置 shaderPack=none），地形片元**不接线**"
                            + "（派生 MRT 管线沿用原版 core/terrain）",
                    SELECTION_NONE, TranslateDiagnostic.UNKNOWN_LINE));
            return new Result(null, null, profile, diagnostics);
        }
        boolean named = !selection.isEmpty();

        ShaderPackScanner.ScanResult scan = ShaderPackScanner.scan(inventoryDir);
        for (ShaderPackScanner.PackProblem problem : scan.problems()) {
            diagnostics.add(TranslateDiagnostic.of(severityOf(problem.kind()),
                    "shaderpacks 扫描问题 [" + problem.kind() + "]: " + problem.message(),
                    String.valueOf(problem.entry()), TranslateDiagnostic.UNKNOWN_LINE));
        }

        for (ShaderPackScanner.DiscoveredPack discovered : scan.packs()) {
            if (named && !selection.equals(discovered.name())) {
                continue; // 指定选择下其它包不参与（不加载、不留诊断噪音）
            }
            ShaderPack pack = ShaderPackService.load(discovered).pack();
            if (pack == null) {
                continue; // load 诊断已由 PackCompositeSource 侧打过；逐包继续找
            }
            boolean declaresTerrain = pack.programs().stream()
                    .anyMatch(program -> programName.equals(program.name()));
            if (!declaresTerrain) {
                diagnostics.add(TranslateDiagnostic.of(TranslateDiagnostic.Severity.INFO,
                        "vkdisp: 包 '" + pack.name() + "' 不含 " + programName
                                + " 程序，该程序不接线（继续找下一个包）",
                        pack.name(), TranslateDiagnostic.UNKNOWN_LINE));
                continue;
            }
            PackOptionsSession session = PackOptionsSession.create(pack, profile, store);
            // GAP-009 方案 A：能力门控 —— 门控的产物是「选项被改成 false」，
            // 而覆盖表是「当前值 vs 默认值」的差分 ⇒ 被门控的项默认是 true ⇒ **必须**在差分之前跑。
            // 放在之后 ⇒ 日志说「已门控」而画面没变 = 最坏的失败形态（静默空转，X9）。
            diagnostics.addAll(applyCapabilityGate(discovered, pack, session, diagnostics));
            // 🔬 单变量取证覆盖（h42 §4.2 登记的未做项）：门控一次改一整个闭包（BSL 上 9 项）
            //   且同时改变派生程序形状 ⇒ 两臂之间不是单变量。本项只改**用户点名的那几个**，
            //   放在门控**之后**：门控是产品止血（要最终生效），覆盖是取证（要压过门控）。
            diagnostics.addAll(applyOptionOverrides(pack, session));
            Map<String, String> overrides = diffAgainstDefaults(session.options());

            ShaderPackCompiler.CompileResult compiled =
                    PackCompileCache.getOrCompile(discovered, overrides);
            diagnostics.addAll(compiled.diagnostics());
            String source = selectTerrainFragment(compiled, programName);
            if (source == null) {
                diagnostics.add(TranslateDiagnostic.of(TranslateDiagnostic.Severity.WARN,
                        named
                                ? "vkdisp: 指定包 '" + pack.name() + "' 的 " + programName
                                        + " 片元阶段无成功产出（shaderPack 指定下不换包，该程序不接线）"
                                : "vkdisp: 包 '" + pack.name() + "' 的 " + programName
                                        + " 片元阶段无成功产出，尝试下一个包",
                        pack.name(), TranslateDiagnostic.UNKNOWN_LINE));
                continue;
            }
            String qualified = terrainQualifiedName(compiled, source, programName);
            // 🔴 槽位语义是否被兑现，由转译链的 ⑦½ 段（DrawBuffersSlotAdapter）判定；
            //   它若拒绝（歧义 / 槽位重复 / 超过 maxColorAttachments），这里必须**跟着拒绝接线**。
            //   理由：此时片元仍按「下标 = location」写着；若照样接上去，材质会静默写进 colortex1、
            //   法线写进 colortex2 —— 画面「有内容」但每个通道都是错的，且**没有任何日志会抱怨**（h06 预言）。
            String refusal = slotRefusal(compiled, qualified);
            if (refusal != null) {
                diagnostics.add(TranslateDiagnostic.of(TranslateDiagnostic.Severity.WARN,
                        "vkdisp: " + qualified + " 的 " + MARKER + " 槽位语义未被兑现，拒绝接线"
                                + "（派生 MRT 地形管线沿用原版 core/terrain）：" + refusal,
                        pack.name(), TranslateDiagnostic.UNKNOWN_LINE));
                continue;
            }
            PackTerrainProgram program =
                    PackTerrainProgram.parse(pack.name(), qualified, source);
            diagnostics.add(TranslateDiagnostic.of(TranslateDiagnostic.Severity.INFO,
                    "vkdisp: 地形片元契约解析完成: program=" + qualified
                            + " outputs=" + program.outputCount()
                            + " samplers=" + program.fragmentSamplers().size()
                            + " varyings=" + program.inputs().size(),
                    pack.name(), TranslateDiagnostic.UNKNOWN_LINE));
            return new Result(program, pack.name(), profile, diagnostics);
        }

        diagnostics.add(TranslateDiagnostic.of(TranslateDiagnostic.Severity.WARN,
                named
                        ? "vkdisp: 指定包 '" + selection + "' 无可用的 " + programName
                                + " 片元源（inventory=" + inventoryDir + "），该程序不接线"
                        : "vkdisp: 库存中没有可用的 " + programName + " 片元源，该程序不接线"
                                + "（inventory=" + inventoryDir + ", profile='" + profile + "'）",
                selection.isEmpty() ? String.valueOf(inventoryDir) : selection,
                TranslateDiagnostic.UNKNOWN_LINE));
        return new Result(null, null, profile, diagnostics);
    }

    /**
     * 在<b>指定程序</b>的各维度变体里按 world0 &gt; 根命名空间 &gt; 其它 选一份片元终稿；
     * 并列取先出现者（结果确定）。
     *
     * <p>🔖 与 composite 的 {@code dimensionRank} 同规则。GAP-027 之后<b>每条程序各自选维度</b>
     * （地形选 world0 不代表水也选 world0）—— 这不是新决定：包本来就可以只给某个维度写水程序，
     * 跨程序共用一次选择会让「水用了 world-1 的程序、地形用了 world0 的」这种混搭无法被发现。
     * 🔖 但「候选里留哪一条 Program」在 {@code PackPostChain} 那边曾经踩过同族坑
     * （先到先得 ⇒ 留下一个片源永不入选的维度），本方法的显式 rank 就是为避开它。
     */
    private static String selectTerrainFragment(ShaderPackCompiler.CompileResult compiled,
            String programName) {
        String best = null;
        int bestRank = Integer.MAX_VALUE;
        for (ShaderPackCompiler.CompiledStage stage : compiled.stages()) {
            if (stage.stage() != ShaderStage.FRAGMENT
                    || !stage.isSuccess()
                    || !isTerrainProgram(stage.programName(), programName)) {
                continue;
            }
            int rank = dimensionRank(dimensionOf(stage.programName()));
            if (rank < bestRank) {
                best = stage.result().text();
                bestRank = rank;
            }
        }
        return best;
    }

    /** 回查被选中源对应的限定名（供日志对账；找不到则回退到首见的维度程序名）。 */
    /**
     * 转译诊断里若带 {@code DrawBuffersSlotAdapter} 的拒绝理由，返回原文；否则 {@code null}。
     *
     * <p>🔖 靠**诊断原文**而不是另一份状态：拒绝理由只在那一段产生，是它的唯一真源；
     * 再存一份布尔位就等于把「是否兑现」复制成两个可能漂移的量（X9 不猜、单一真源）。
     */
    private static String slotRefusal(ShaderPackCompiler.CompileResult compiled, String qualified) {
        for (ShaderPackCompiler.CompiledStage stage : compiled.stages()) {
            // 🔖 按**被选中的那条限定名**精确匹配，而不是按程序名后缀匹配所有维度：
            //   GAP-027 之后同一条程序会有多个维度变体，用后缀会把「没被选中的那个维度」的
            //   拒绝理由也算到本次接线头上（那会误拒一次本来可接的接线）。
            if (stage.stage() != ShaderStage.FRAGMENT
                    || !qualified.equals(stage.programName())) {
                continue;
            }
            for (TranslateDiagnostic diagnostic : stage.result().diagnostics()) {
                if (!diagnostic.severity().isError()) {
                    continue;
                }
                String message = diagnostic.message();
                if (message.contains("DRAWBUFFERS") && message.contains("无法兑现")) {
                    return stage.programName() + ": " + message;
                }
            }
        }
        return null;
    }

    /** 复用转译段的标记名常量（不散落字面量）。 */
    private static final String MARKER =
            dev.vkdisp.glsl.translate.DrawBuffersSlotAdapter.MARKER;
    private static String terrainQualifiedName(ShaderPackCompiler.CompileResult compiled,
            String source, String programName) {
        for (ShaderPackCompiler.CompiledStage stage : compiled.stages()) {
            if (stage.stage() == ShaderStage.FRAGMENT
                    && stage.isSuccess()
                    && isTerrainProgram(stage.programName(), programName)
                    && stage.result().text().equals(source)) {
                return stage.programName();
            }
        }
        return programName;
    }

    private static boolean isTerrainProgram(String qualifiedName, String programName) {
        return programName.equals(qualifiedName)
                || qualifiedName.endsWith("/" + programName);
    }

    private static int dimensionRank(String dimension) {
        if ("world0".equals(dimension)) {
            return 1;
        }
        return dimension.isEmpty() ? 2 : 3;
    }

    private static String dimensionOf(String qualifiedName) {
        int slash = qualifiedName.indexOf('/');
        return slash < 0 ? "" : qualifiedName.substring(0, slash);
    }

    /** 与 PackCompositeSource 同口径的覆盖表 = values 与 defaults 的差分（未动的选项不进表）。 */
    private static Map<String, String> diffAgainstDefaults(PackOptions options) {
        Map<String, String> defaults = options.defaults();
        Map<String, String> overrides = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> entry : options.values().entrySet()) {
            if (!entry.getValue().equals(defaults.get(entry.getKey()))) {
                overrides.put(entry.getKey(), entry.getValue());
            }
        }
        return overrides;
    }

    /**
     * GAP-009 方案 A：能力门控（把「包自己声明依赖、而本引擎确实缺失该能力」的包特性
     * <b>只在内存里</b>关掉，不写任何用户文件），诊断并入本次选取的诊断列表。
     *
     * <p>🔖 <b>为什么接在这里、而不是 composite 那条链</b>：GAP-009 的全部实测证据
     * （h29 定位视差分支、h31 主目标 luma {@code 0.0000 → 96.1485}）都取自<b>地形</b>；
     * 而 {@link PackCompositeSource} 产出的是 composite/deferred/final 三个全屏步。
     * 在那里门控会在「地形片元没接线」时<b>白白砍掉包特性</b>，而那条路径根本不执行（违反 X27）。
     *
     * <p>🔖 <b>作用域恒为「适用」</b>：能走到本方法并选到 {@code gbuffers_terrain}，
     * 就说明包地形片元<b>即将</b>被接到派生 MRT 地形管线上 ⇒ 缺能力的那条路径会执行
     * ⇒ 门控该生效。（若最终因槽位语义被拒而没接线，那次编译根本不会发生，同样不构成「白砍」。）
     *
     * <p>🔖 <b>只改内存</b>：{@link PackOptionStore} 一个字节都不碰（裁决：不改写用户配置）。
     *
     * @param sink 本次选取的诊断表（读 lang 时的读失败警告要并进去）
     * @return 门控步骤自身产生的诊断
     */
    private static List<TranslateDiagnostic> applyCapabilityGate(
            ShaderPackScanner.DiscoveredPack discovered,
            ShaderPack pack,
            PackOptionsSession session,
            List<TranslateDiagnostic> sink) {
        List<TranslateDiagnostic> produced = new ArrayList<>();
        PackLangFile.Result lang = ShaderPackService.readLang(discovered, sink);
        PackCapabilityGate.GateResult gate = PackCapabilityGate.apply(
                new PackCapabilityGate.GateRequest(
                        session.options(), lang.optionLabels(), lang.starMarkedOptions(),
                        lang.hints(), PackCapabilityGateSwitch.enabled(), true));
        for (OptionDiagnostic diagnostic : gate.diagnostics()) {
            produced.add(TranslateDiagnostic.of(
                    TranslateDiagnostic.Severity.WARN,
                    "选项 [" + diagnostic.code() + "] " + diagnostic.message(),
                    pack.name(), TranslateDiagnostic.UNKNOWN_LINE));
        }
        // 🔴 h33：门控开关的反射若失败，开关会**静默恒为默认关** —— 用户写进配置、日志照打、
        //   就是不生效，且毫无异常。必须在这里把它变成一条**看得到的**诊断（T11 / X9）。
        String gateSwitchFailure = PackCapabilityGateSwitch.reflectionFailure();
        if (gateSwitchFailure != null) {
            produced.add(TranslateDiagnostic.of(
                    TranslateDiagnostic.Severity.ERROR,
                    "选项 [CAPABILITY_GATE_SWITCH_UNREADABLE] 能力门控开关读取失败："
                            + gateSwitchFailure
                            + " ⇒ " + PackCapabilityGateSwitch.CONFIG_KEY
                            + " 将**恒为 " + PackCapabilityGateSwitch.DEFAULT_ENABLED
                            + "（默认关）**，即该开关写了也不生效。这是真错误不是正常状态。",
                    pack.name(), TranslateDiagnostic.UNKNOWN_LINE));
        }
        return produced;
    }

    /**
     * 🔬 单变量取证覆盖：把 {@code pack.optionOverrides} 里点名的选项在内存里强制成指定值。
     *
     * <p>🔖 <b>顺序说明（不是随手放的）</b>：本项在
     * {@link #applyCapabilityGate} <b>之后</b>、{@code diffAgainstDefaults} <b>之前</b>。
     * ① 在差分之前 ⇒ 覆盖值一定进覆盖表（否则「改了但没生效」是最坏失败形态：
     * 日志说改了、画面没变）；② 在门控之后 ⇒ 门控是产品止血、覆盖是取证，
     * 取证者要能压过门控（否则「关掉视差」那条指令可能被门控重新打开）。
     *
     * <p>🔖 <b>不写任何用户文件</b>：{@link PackOptionStore} 一个字节都不碰。
     */
    private static List<TranslateDiagnostic> applyOptionOverrides(
            ShaderPack pack, PackOptionsSession session) {
        List<TranslateDiagnostic> produced = new ArrayList<>();
        PackOptionOverride.Result result = PackOptionOverride.apply(
                session.options(), PackOptionOverrideSwitch.spec());
        for (OptionDiagnostic diagnostic : result.diagnostics()) {
            produced.add(TranslateDiagnostic.of(severityOf(diagnostic.severity()),
                    "选项覆盖 [" + diagnostic.code() + "] " + diagnostic.message(),
                    pack.name(), TranslateDiagnostic.UNKNOWN_LINE));
        }
        // 🔴 与能力门控开关同一条纪律（h33 实测）：反射失败会让开关**静默**取默认值，
        //   而「我写了覆盖但它没生效」在日志上完全看不出来 ⇒ 必须变成一条可见诊断。
        String failure = PackOptionOverrideSwitch.reflectionFailure();
        if (failure != null) {
            produced.add(TranslateDiagnostic.of(
                    TranslateDiagnostic.Severity.ERROR,
                    "选项覆盖 [OPTION_OVERRIDES_SWITCH_UNREADABLE] 读取失败：" + failure
                            + " ⇒ " + PackOptionOverrideSwitch.CONFIG_KEY
                            + " 将**恒为空串（= 不覆盖）**，即该配置写了也不生效。这是真错误不是正常状态。",
                    pack.name(), TranslateDiagnostic.UNKNOWN_LINE));
        }
        return produced;
    }

    /**
     * {@link OptionDiagnostic.Severity} → {@link TranslateDiagnostic.Severity} 的<b>逐项</b>映射。
     *
     * <p>🔖 <b>为什么不一律降成 WARN</b>（既有 {@link #applyCapabilityGate} 现在的做法）：
     * 那会把「覆盖串根本解析不了 ⇒ 本帧一条都没改」这条<b>真错误</b>报成 WARN。
     * 解析失败时用户看到的是「配置写了没生效」，那必须与「值被钳制」区分开。
     */
    private static TranslateDiagnostic.Severity severityOf(OptionDiagnostic.Severity severity) {
        return switch (severity) {
            case ERROR -> TranslateDiagnostic.Severity.ERROR;
            case WARN -> TranslateDiagnostic.Severity.WARN;
            case INFO -> TranslateDiagnostic.Severity.INFO;
        };
    }

    private static TranslateDiagnostic.Severity severityOf(ShaderPackScanner.ProblemKind kind) {
        return switch (kind) {
            case BROKEN_ZIP -> TranslateDiagnostic.Severity.ERROR;
            case INVENTORY_MISSING, NO_PACKS_FOUND -> TranslateDiagnostic.Severity.INFO;
            default -> TranslateDiagnostic.Severity.WARN;
        };
    }
}
