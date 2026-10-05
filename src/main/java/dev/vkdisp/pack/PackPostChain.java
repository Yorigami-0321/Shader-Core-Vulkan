package dev.vkdisp.pack;

import dev.vkdisp.glsl.TranslateDiagnostic;
import dev.vkdisp.glsl.translate.ShaderStage;
import dev.vkdisp.pipeline.model.PostOutputRenumber;
import dev.vkdisp.pipeline.model.PostPassContract;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
            String renumberedSource) {

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
     * @param pack               包模型（提供程序清单与族/序号）
     * @param compiled           同一次 {@link ShaderPackCompiler} 的产物（FRAGMENT 终稿）
     * @param preferredDimension 基准维度（= composite 选中的维度目录；可为空串 = 根）
     */
    public static Chain build(ShaderPack pack, ShaderPackCompiler.CompileResult compiled,
            String preferredDimension) {
        List<TranslateDiagnostic> diagnostics = new ArrayList<>();
        List<Pass> passes = new ArrayList<>();
        // OF 执行序：族序（DEFERRED < COMPOSITE < FINAL）→ 族内序号（composite=0 先于 composite1）。
        List<Program> candidates = new ArrayList<>();
        java.util.Set<String> seenNames = new java.util.HashSet<>();
        for (Program program : pack.programs()) {
            ProgramStage stage = program.stage();
            if (stage != ProgramStage.DEFERRED && stage != ProgramStage.COMPOSITE
                    && stage != ProgramStage.FINAL) {
                continue;
            }
            // 🔖 按**程序名**去重：多维度包里 deferred 有 world-1/world0/world1 三条 Program，
            // 不去重就会进链三次 —— 实测 BSL：16 个槽位预算被 5 个程序 ×3 维度吃光，
            // 尾部（composite5..final）整体消失，而**没有任何一步报错**。
            if (!seenNames.add(program.name())) {
                continue;
            }
            candidates.add(program);
        }
        candidates.sort(Comparator
                .comparingInt((Program p) -> p.stage().order())
                .thenComparingInt(Program::stageIndex)
                .thenComparing(Program::name));
        for (Program program : candidates) {
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
            passes.add(new Pass(program.name(), selection.qualifiedName(),
                    contract.outputSlots(), contract.samplerNames(), contract.inputs(),
                    contract.mipEnabledSlots(), renumbered));
        }
        if (!passes.isEmpty()) {
            StringBuilder summary = new StringBuilder("vkdisp: post chain ready: passes=[");
            for (int i = 0; i < passes.size(); i++) {
                if (i > 0) {
                    summary.append(", ");
                }
                Pass pass = passes.get(i);
                summary.append(pass.programName()).append("{slots=").append(pass.attachmentSlots())
                        .append(",samplers=").append(pass.samplerNames().size()).append('}');
            }
            summary.append("] maxSlot=").append(new Chain(passes, List.of()).maxSlot());
            diagnostics.add(TranslateDiagnostic.info(summary.toString(), pack.name(),
                    TranslateDiagnostic.UNKNOWN_LINE));
        }
        return new Chain(passes, diagnostics);
    }

    /** 一次程序选中的结果：转译终稿 + 其限定名。 */
    private record Selection(String source, String qualifiedName) {}

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
