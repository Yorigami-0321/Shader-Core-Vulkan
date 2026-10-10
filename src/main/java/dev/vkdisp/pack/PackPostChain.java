package dev.vkdisp.pack;

import dev.vkdisp.glsl.TranslateDiagnostic;
import dev.vkdisp.glsl.translate.ShaderStage;
import dev.vkdisp.pipeline.model.PostOutputRenumber;
import dev.vkdisp.pipeline.model.PostPassContract;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 包的完整后处理链（OF 执行序：deferred* → composite*（含无号的 composite=0 号）→ final）。
 *
 * <p><b>为什么必须整链跑</b>（P3.3/P4.1.4 的三步固定链是「单程序」时代遗物）：
 * BSL v10.1.8 实测有 {@code deferred, deferred1, composite, composite1..composite7, final}
 * 十个全屏步，各自写各自读（{@code composite4/5} 无 enabled 条件 ⇒ 恒定在场）。
 * 只跑其中三步 = 其余七步的功能整体缺席 —— 而这正是支柱①「完整运行 OptiFine/Iris 包」
 * 要消灭的形态。选不到 ≠ 不需要（X27：不许拿能力当借口砍包特性；这里是把没接的接上）。
 *
 * <p>🔖 <b>与 {@link PackCompositeSource} 同一次编译产物</b>：所有程序已经由
 * {@link ShaderPackCompiler} 整包转译 + 驱动级编译；本类只做「按族+序号选齐 → 解析契约 →
 * location 重编号」的纯编排，不重复合成/编译。
 *
 * <p><b>维度口径</b>（与 composite 链一致的已知未覆盖项）：只在
 * 「与基准程序同维度 → world0 → 根命名空间」里择优；其它维度目录的程序**不进链**并逐条 INFO
 * （把它们混进主世界链比不跑更糟 —— 与 {@link PackCompositeSource} 的串链教训同源）。
 *
 * <p>🔴 <b>GAP-024：包自己写的 {@code program.<名>.enabled} 在这里生效</b>。
 * 这些数据从 {@code ShaderProperties.programSwitches} → {@code ShaderPackService.deriveSettings}
 * 一路活到 {@code Program#settings().get("enabled")}，但本类的装配循环<b>曾经从不读它</b>
 * ⇒ 包里明确关着的特性级照跑（BSL 默认档的 MOTION_BLUR / DOF 两级 composite 每帧白烧，
 * 且画面与「按包声明跑」之间<b>没有可观测的区别</b>）。现在：
 * <ul>
 *   <li>决策与自报在 {@link ChainEnableGating}（纯逻辑，可单测），三值求值在
 *       {@link ProgramEnableGate}（本类不重造求值器，X17）；</li>
 *   <li>只有表达式<b>确定为假</b>的步被丢；看不懂（UNKNOWN）的步<b>照样进链</b>，
 *       但它的名字会出现在自报行里 —— 那是我方知识的边界，不是包的错；</li>
 *   <li>选项值由调用方（{@link PackCompositeSource}）传入，且<b>必须是编译用的同一份</b>，
 *       否则就是「按 A 编、按 B 跑」；</li>
 *   <li>每次装配出<b>一行</b> {@code [GAP-024] post chain enable-gating:} INFO，
 *       跳过 0 级时也照打（否则「没这行」与「这行说没跳过」不可区分），
 *       并带 {@code gating=on/off} 说明本臂跑的是哪个行为
 *       （{@code pack.chainEnableGating}，见 {@link PackChainGatingSwitch}）。</li>
 * </ul>
 */
public final class PackPostChain {

    /** 全屏 pass 上限（OF 的 composite/deferred 族各到 99，实际包远小于；池与管线按此定长）。 */
    public static final int MAX_POST_PASSES = 16;

    /** 单 pass 附件帧宽（= 后处理管线的固定颜色目标数；超出的写槽拒绝进链并 WARN）。 */
    public static final int FRAME_WIDTH = 8;

    /** 链里一个可执行的全屏步（重编号后的源 + 附件槽 + sampler 清单 + 输入 varying 契约）。 */
    public record Pass(
            String programName,
            String qualifiedName,
            List<Integer> attachmentSlots,
            List<String> samplerNames,
            List<PostPassContract.FragmentInput> inputs,
            List<Integer> mipEnabledSlots,
            String renumberedSource,
            String vertexSource) {

        public Pass {
            attachmentSlots = attachmentSlots == null ? List.of() : List.copyOf(attachmentSlots);
            samplerNames = samplerNames == null ? List.of() : List.copyOf(samplerNames);
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
            mipEnabledSlots = mipEnabledSlots == null ? List.of() : List.copyOf(mipEnabledSlots);
        }

        /** final 步（族序 6）—— 它的附件 0 在运行期换成主目标视图。 */
        public boolean isFinal() {
            return programName.equals("final");
        }

        /**
         * 🔴 GAP-030：本步是否有<b>包自己的</b>顶点程序源（已按片元契约对齐过）。
         *
         * <p>{@code null} = 本步用我方顶点适配层（世界向量按零值供 = 镜像虚影那一档），
         * 调用方<b>必须</b>把这一格打进自报行 —— 不许让「包顶点没接上」读起来像「包没有顶点程序」。
         */
        public boolean hasPackVertexSource() {
            return vertexSource != null && !vertexSource.isBlank();
        }
    }

    /** 生成结果：有序链 + 诊断（链可以为空 = 包没有后处理程序，FrameApi 走旧三步）。 */
    public record Chain(List<Pass> passes, List<TranslateDiagnostic> diagnostics) {

        public Chain {
            passes = passes == null ? List.of() : List.copyOf(passes);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }

        public static final Chain EMPTY = new Chain(List.of(), List.of());

        /** 全链声明需要 mip 链的 colortex 槽（GAP-017：降采样金字塔只为这些槽生成）。 */
        public java.util.Set<Integer> mipEnabledSlots() {
            java.util.Set<Integer> out = new java.util.LinkedHashSet<>();
            for (Pass pass : passes) {
                out.addAll(pass.mipEnabledSlots());
            }
            return out;
        }

        /** 全链用到的最大 colortex 槽（colortex 池按它扩；空链 = -1）。 */
        public int maxSlot() {
            int max = -1;
            for (Pass pass : passes) {
                for (int slot : pass.attachmentSlots()) {
                    max = Math.max(max, slot);
                }
            }
            return max;
        }
    }

    private PackPostChain() {}

    /**
     * 从**已编译**的包产物构建有序链。
     *
     * @param pack               包模型（提供程序清单与族/序号，以及每级的 {@code enabled} 表达式）
     * @param compiled           同一次 {@link ShaderPackCompiler} 的产物（FRAGMENT 终稿）
     * @param preferredDimension 基准维度（= composite 选中的维度目录；可为空串 = 根）
     * @param optionValues       当前生效的<b>选项值</b>（选项名 → 值），GAP-024 用它求 {@code enabled}。
     *                           🔴 必须是<b>编译用的那一份</b>（{@code PackOptionsSession#options()}
     *                           的 {@code values()}）—— 另取一份就是「按 A 编、按 B 跑」。
     *                           没有选项可给时传<b>空表</b>（不许传 null）：空表 ⇒ 每条表达式都
     *                           认不出 ⇒ 全部保留 + 自报行把它们逐条点名（保守且不静默）
     */
    public static Chain build(ShaderPack pack, ShaderPackCompiler.CompileResult compiled,
            String preferredDimension, Map<String, String> optionValues) {
        List<TranslateDiagnostic> diagnostics = new ArrayList<>();
        List<Pass> passes = new ArrayList<>();
        // OF 执行序：族序（DEFERRED < COMPOSITE < FINAL）→ 族内序号（composite=0 先于 composite1）。
        List<Program> candidates = new ArrayList<>();
        java.util.Map<String, Integer> rankByName = new java.util.HashMap<>();
        java.util.Map<String, Program> byName = new java.util.LinkedHashMap<>();
        for (Program program : pack.programs()) {
            ProgramStage stage = program.stage();
            if (stage != ProgramStage.DEFERRED && stage != ProgramStage.COMPOSITE
                    && stage != ProgramStage.FINAL) {
                continue;
            }
            // 🔖 按**程序名**去重：多维度包里 deferred 有 world-1/world0/world1 三条 Program，
            // 不去重就会进链三次 —— 实测 BSL：16 个槽位预算被 5 个程序 ×3 维度吃光，
            // 尾部（composite5..final）整体消失，而**没有任何一步报错**。
            //
            // 🔴 但「留哪一条」**必须与取源用同一套维度优先级**（{@link #chainDimensionRank}）。
            //   旧实现是 `Set.add` 先到先得，而 BSL 的枚举顺序是 world-1 → world0 → world1
            //   ⇒ 留在候选里的是 **world-1 那条 Program**，可它的片元源**永远不会被选中**
            //   （{@code selectFragment} 给非偏好维度打 MAX_VALUE）。在此之前它只是
            //   「settings 里的 blend/alphaTest 取错维度」的潜在坑；GAP-024 接上 {@code enabled}
            //   之后它立刻变成**功能回归**：BSL 逐字写着
            //     program.world0/composite1.enabled = LIGHT_SHAFT                              （真）
            //     program.world-1/composite1.enabled = LIGHT_SHAFT && MULTICOLORED_BLOCKLIGHT   （假）
            //   ⇒ 光柱被一个**根本不进链的维度**的表达式砍掉（h48u 实测 {@code skipped=[composite1,…]}，
            //   而我方选项表逐字写着 {@code option name=LIGHT_SHAFT … default=true}）。
            String dimension = program.dimensionFolder() == null ? "" : program.dimensionFolder();
            int rank = chainDimensionRank(dimension, preferredDimension);
            String name = program.name();
            Integer held = rankByName.get(name);
            if (held == null || rank < held) {
                rankByName.put(name, rank);
                byName.put(name, program);
            }
        }
        candidates.addAll(byName.values());
        candidates.sort(Comparator
                .comparingInt((Program p) -> p.stage().order())
                .thenComparingInt(Program::stageIndex)
                .thenComparing(Program::name));
        // 🔴 GAP-024：包自己写的 program.*.enabled 在这里生效（跳过谁、留下谁都只有一条自报行）。
        ChainEnableGating.Plan gating = applyEnableGating(pack, candidates, optionValues, diagnostics);
        for (Program program : candidates) {
            // 门控掉的步<b>先于</b>槽位上限检查：它不进链就不该占 16 个槽位预算，
            // 更不该在「已达上限」的那条 break 里被当成「没轮到」—— 那是两种不同的原因。
            if (!gating.keeps(program.name())) {
                continue;
            }
            if (passes.size() >= MAX_POST_PASSES) {
                diagnostics.add(TranslateDiagnostic.warn(
                        "vkdisp: post 链已达上限 " + MAX_POST_PASSES + "，后续程序（含 "
                                + program.name() + "）**不进链** —— 这不是「不需要」，是上限",
                        program.name(), TranslateDiagnostic.UNKNOWN_LINE));
                break;
            }
            Selection selection = selectFragment(compiled, program.name(), preferredDimension);
            if (selection == null) {
                diagnostics.add(TranslateDiagnostic.of(
                        TranslateDiagnostic.Severity.WARN,
                        "vkdisp: post 程序 '" + program.name() + "' 无可用片元终稿（无成功编译，"
                                + "或只存在于其它维度目录）⇒ **本步不进链**（不是关掉它）",
                        pack.name(), TranslateDiagnostic.UNKNOWN_LINE));
                continue;
            }
            PostPassContract contract;
            try {
                contract = PostPassContract.parse(selection.qualifiedName(), selection.source());
            } catch (IllegalArgumentException refusal) {
                diagnostics.add(TranslateDiagnostic.of(
                        TranslateDiagnostic.Severity.WARN,
                        "vkdisp: post 程序 '" + selection.qualifiedName() + "' 契约不成立 → 不进链："
                                + refusal.getMessage(),
                        pack.name(), TranslateDiagnostic.UNKNOWN_LINE));
                continue;
            }
            if (contract.outputSlots().size() > FRAME_WIDTH) {
                diagnostics.add(TranslateDiagnostic.of(
                        TranslateDiagnostic.Severity.WARN,
                        "vkdisp: post 程序 '" + selection.qualifiedName() + "' 写 "
                                + contract.outputSlots().size() + " 个槽 > 附件帧宽 " + FRAME_WIDTH
                                + " ⇒ 不进链（超出本引擎上限，不猜裁剪）",
                        pack.name(), TranslateDiagnostic.UNKNOWN_LINE));
                continue;
            }
            // 🔴 编排期闸门：sampler 名必须全在绑定组超集里。超集外的名字**运行期**会被
            //   validateDraw 抛 Missing uniform —— 那炸的是整帧而不是那一步 ⇒ 必须在进链前
            //   响亮排除（并把缺的名字打出来，换包时一眼知道要扩哪一条）。
            if (!dev.vkdisp.pipeline.model.PostSamplerSuperset.containsAll(contract.samplerNames())) {
                java.util.List<String> unknown = new ArrayList<>(contract.samplerNames());
                unknown.removeAll(dev.vkdisp.pipeline.model.PostSamplerSuperset.NAMES);
                diagnostics.add(TranslateDiagnostic.of(
                        TranslateDiagnostic.Severity.WARN,
                        "vkdisp: post 程序 '" + selection.qualifiedName() + "' 声明了超集外的 sampler "
                                + unknown + " ⇒ 不进链（扩 PostSamplerSuperset 后再收；"
                                + "硬跑会在 draw 时抛 Missing uniform 炸整帧）",
                        pack.name(), TranslateDiagnostic.UNKNOWN_LINE));
                continue;
            }
            String renumbered;
            try {
                renumbered = PostOutputRenumber.apply(selection.source(), contract.outputSlots());
            } catch (IllegalArgumentException refusal) {
                diagnostics.add(TranslateDiagnostic.of(
                        TranslateDiagnostic.Severity.WARN,
                        "vkdisp: post 程序 '" + selection.qualifiedName() + "' 重编号失败 → 不进链："
                                + refusal.getMessage(),
                        pack.name(), TranslateDiagnostic.UNKNOWN_LINE));
                continue;
            }
            // 🔴 GAP-030：接**包自己的** post 顶点程序；接口对不齐就整槽回落适配层（不半套）。
            VertexLink link = linkVertex(program, compiled, preferredDimension, selection,
                    contract, renumbered, diagnostics);
            passes.add(new Pass(program.name(), selection.qualifiedName(),
                    contract.outputSlots(), contract.samplerNames(), contract.inputs(),
                    contract.mipEnabledSlots(), link.fragmentSource(), link.vertexSource()));
        }
        if (!passes.isEmpty()) {
            StringBuilder summary = new StringBuilder("vkdisp: post chain ready: passes=[");
            for (int i = 0; i < passes.size(); i++) {
                if (i > 0) {
                    summary.append(", ");
                }
                Pass pass = passes.get(i);
                summary.append(pass.programName()).append("{slots=").append(pass.attachmentSlots())
                        .append(",samplers=").append(pass.samplerNames().size())
                        // 🔴 GAP-030：这一格决定该级顶点是「包自己写的」还是「我方适配层（零向量档）」，
                        //   必须逐槽打进日志 —— 否则「包顶点没接上」与「包没有顶点程序」读起来一样。
                        .append(",vertex=").append(pass.hasPackVertexSource() ? "pack" : "adapter")
                        .append('}');
            }
            summary.append("] maxSlot=").append(new Chain(passes, List.of()).maxSlot());
            diagnostics.add(TranslateDiagnostic.info(summary.toString(), pack.name(),
                    TranslateDiagnostic.UNKNOWN_LINE));
        }
        reportPostVertexSources(pack, passes, diagnostics);
        return new Chain(passes, diagnostics);
    }

    /**
     * 🔴 GAP-024 的门控点（单独成方法 = 让 {@code build} 不因为「新增一次决策」再变长，
     * 也是 {@link ChainEnableGating} 那条自报行唯一的产生地）。
     *
     * <p>本方法只做三件事：① 读总开关；② 把候选步的 {@code enabled} 表达式交给
     * {@link ChainEnableGating} 求值（求值口径在 {@link ProgramEnableGate}，这里不重造）；
     * ③ <b>无条件</b>留下一条 INFO 自报 —— 包括一行都没跳过的那一次。
     *
     * @param diagnostics 诊断收集器（自报行与开关读取失败都往这里追加，随链一起交付给调用方）
     * @return 门控决策（调用方按 {@link ChainEnableGating.Plan#keeps(String)} 过滤候选）
     */
    private static ChainEnableGating.Plan applyEnableGating(ShaderPack pack, List<Program> candidates,
            Map<String, String> optionValues, List<TranslateDiagnostic> diagnostics) {
        boolean gatingEnabled = PackChainGatingSwitch.enabled();
        List<ChainEnableGating.Step> steps = new ArrayList<>(candidates.size());
        for (Program program : candidates) {
            steps.add(new ChainEnableGating.Step(program.name(), program.settings().get("enabled")));
        }
        ChainEnableGating.Plan plan = ChainEnableGating.plan(steps, optionValues, gatingEnabled);
        diagnostics.add(TranslateDiagnostic.info(plan.report(pack.name()), pack.name(),
                TranslateDiagnostic.UNKNOWN_LINE));
        // 🔴 开关读不到（字段被改名/被删）⇒ 开关会恒取默认值，而「我把它写成 false 但它没生效」
        //   在日志里完全看不出来 —— h33 实测的正是这一族。必须 ERROR 点名，不能只按默认继续跑。
        String switchFailure = PackChainGatingSwitch.reflectionFailure();
        if (switchFailure != null) {
            diagnostics.add(TranslateDiagnostic.of(TranslateDiagnostic.Severity.ERROR,
                    "vkdisp: [GAP-024] 链门控开关 " + PackChainGatingSwitch.CONFIG_KEY
                            + " 读取失败：" + switchFailure
                            + " ⇒ 它将**恒为 " + PackChainGatingSwitch.DEFAULT_ENABLED
                            + "（默认开）**，即该配置写了也不生效。这是真错误不是正常状态。",
                    pack.name(), TranslateDiagnostic.UNKNOWN_LINE));
        }
        return plan;
    }

    /** 一次程序选中的结果：转译终稿 + 其限定名。 */
    private record Selection(String source, String qualifiedName) {}

    /** GAP-030 的一次顶点链接结果：本槽最终用的片元源（可能因追加块成员而变）+ 顶点源（可空）。 */
    private record VertexLink(String fragmentSource, String vertexSource) {}

    /**
     * 🔴 GAP-030：取<b>同一条程序</b>的顶点终稿并对齐接口。
     *
     * <p>回落条件有三条，每一条都<b>点名</b>（X11：缺席必须可读出）：
     * ① 总开关 {@code pack.postVertexProgram} 关着（= 已知错的适配层档，只用于 A/B）；
     * ② 该程序<b>没有</b>成功编译的顶点终稿；③ {@link PostVertexLinker} 判定接口对不齐
     * （缺共用块 / 统一后两阶段布局仍不一致）。
     *
     * <p>🔖 <b>顶点必须与片元同名同维度，不按维度优先级另挑一条</b>：多维度包里
     * {@code world-1/composite.vsh} 与 {@code world0/composite.fsh} 拼一起 = 一个维度目录的
     * 顶点算法配另一个维度的片元 —— 与 {@link #selectFragment} 的串链教训同源，只是这次
     * 串的是阶段。所以这里只认<b>限定名逐字相等</b>那一条。
     */
    private static VertexLink linkVertex(Program program, ShaderPackCompiler.CompileResult compiled,
            String preferredDimension, Selection fragment, PostPassContract contract,
            String renumbered, List<TranslateDiagnostic> diagnostics) {
        String packName = program.name();
        if (!PackPostVertexSwitch.enabled()) {
            diagnostics.add(TranslateDiagnostic.of(TranslateDiagnostic.Severity.ERROR,
                    "vkdisp: [GAP-030] 开关 " + PackPostVertexSwitch.CONFIG_KEY + "=false ⇒ post 程序 '"
                            + fragment.qualifiedName() + "' 用<b>顶点适配层</b>（世界向量按零值供 ="
                            + " 2026-10-10 镜像虚影 / 长条云的已定案根因档，仅供 A/B 取证）",
                    packName, TranslateDiagnostic.UNKNOWN_LINE));
            return new VertexLink(renumbered, null);
        }
        Selection vertex = selectVertex(compiled, fragment.qualifiedName());
        if (vertex == null) {
            diagnostics.add(TranslateDiagnostic.info(
                    "vkdisp: [GAP-030] post 程序 '" + fragment.qualifiedName() + "' 没有成功编译的"
                            + "顶点终稿（包未提供 .vsh，或该阶段转译失败）⇒ 本槽用顶点适配层",
                    packName, TranslateDiagnostic.UNKNOWN_LINE));
            return new VertexLink(renumbered, null);
        }
        dev.vkdisp.glsl.translate.PostVertexLinker.Result linked =
                dev.vkdisp.glsl.translate.PostVertexLinker.link(fragment.qualifiedName(),
                        vertex.source(), renumbered, contract.inputs());
        diagnostics.addAll(linked.diagnostics());
        if (!linked.packVertexSupplied()) {
            return new VertexLink(linked.fragmentSource(), null);
        }
        return new VertexLink(linked.fragmentSource(), linked.vertexSource());
    }

    /**
     * 取<b>限定名逐字相等</b>的顶点终稿（与 {@link #selectFragment} 的维度择优不同：这里不许跨维度凑）。
     */
    private static Selection selectVertex(ShaderPackCompiler.CompileResult compiled,
            String qualifiedName) {
        for (ShaderPackCompiler.CompiledStage stage : compiled.stages()) {
            if (stage.stage() == ShaderStage.VERTEX && stage.isSuccess()
                    && qualifiedName.equals(stage.programName())) {
                return new Selection(stage.result().text(), stage.programName());
            }
        }
        return null;
    }

    /**
     * GAP-030 的每装配一次自报：几个槽接了包顶点、几个回落、回落的是谁。
     *
     * <p>🔖 为什么单独一行而不去翻 {@code vertex=} 那些字段：这一族的事故形态就是
     * 「画面不对但每行日志都正常」，可数的一行（{@code pack=7 adapter=3}）才读得出「没接全」。
     */
    private static void reportPostVertexSources(ShaderPack pack, List<Pass> passes,
            List<TranslateDiagnostic> diagnostics) {
        if (passes.isEmpty()) {
            return;
        }
        List<String> adapters = new ArrayList<>();
        for (Pass pass : passes) {
            if (!pass.hasPackVertexSource()) {
                adapters.add(pass.programName());
            }
        }
        diagnostics.add(TranslateDiagnostic.info("vkdisp: [GAP-030] post 顶点程序来源: pack="
                + (passes.size() - adapters.size()) + " adapter=" + adapters.size()
                + (adapters.isEmpty() ? "" : " 回落适配层=" + adapters)
                + "（适配层 = 世界向量零值供值档，见 PackPostVertexAdapter）", pack.name(),
                TranslateDiagnostic.UNKNOWN_LINE));
        String switchFailure = PackPostVertexSwitch.reflectionFailure();
        if (switchFailure != null) {
            diagnostics.add(TranslateDiagnostic.of(TranslateDiagnostic.Severity.ERROR,
                    "vkdisp: [GAP-030] 开关 " + PackPostVertexSwitch.CONFIG_KEY + " 读取失败："
                            + switchFailure + " ⇒ 它将**恒为 " + PackPostVertexSwitch.DEFAULT_ENABLED
                            + "（默认开）**，即该配置写了也不生效。这是真错误不是正常状态。",
                    pack.name(), TranslateDiagnostic.UNKNOWN_LINE));
        }
    }

    /**
     * 按维度偏好（preferred → world0 → 根；**其它维度一律不进链**）取 FRAGMENT 终稿。
     */
    private static Selection selectFragment(ShaderPackCompiler.CompileResult compiled,
            String programName, String preferredDimension) {
        Selection best = null;
        int bestRank = Integer.MAX_VALUE;
        for (ShaderPackCompiler.CompiledStage stage : compiled.stages()) {
            if (stage.stage() != ShaderStage.FRAGMENT || !stage.isSuccess()
                    || !isProgramName(stage.programName(), programName)) {
                continue;
            }
            int rank = chainDimensionRank(dimensionOf(stage.programName()), preferredDimension);
            if (rank < bestRank) {
                best = new Selection(stage.result().text(), stage.programName());
                bestRank = rank;
            }
        }
        return best;
    }

    /** 比 {@link PackCompositeSource} 的口径更严：其它维度目录直接排除（返回不可用 = 正数上界）。 */
    private static int chainDimensionRank(String dimension, String preferred) {
        if (preferred != null && !preferred.isEmpty() && preferred.equals(dimension)) {
            return 0;
        }
        if ("world0".equals(dimension)) {
            return 1;
        }
        if (dimension.isEmpty()) {
            return 2;
        }
        return Integer.MAX_VALUE;
    }

    private static String dimensionOf(String qualifiedName) {
        int slash = qualifiedName.indexOf('/');
        return slash < 0 ? "" : qualifiedName.substring(0, slash);
    }

    private static boolean isProgramName(String qualifiedName, String programName) {
        return programName.equals(qualifiedName) || qualifiedName.endsWith("/" + programName);
    }
}
