package dev.vkdisp.pack;

import dev.vkdisp.glsl.GlslPipeline;
import dev.vkdisp.glsl.TranslateDiagnostic;
import dev.vkdisp.glsl.TranslateResult;
import dev.vkdisp.glsl.preprocess.IncludeResolver;
import dev.vkdisp.glsl.translate.ShaderStage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 【参考调研】ShaderPackCompiler（包 → 最终 GLSL 源；把 C+D 汇合到包模型上）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/18-PARALLEL.md §6「汇合点与并入顺序」——C + D 在 P0.4 之后并入，
 *    立即解锁的主线任务是 P2.3「含 {@code #include} 的 program 能编译通过」；
 *    ② docs/01-DEV-LOOP.md §10 的 P2.3 完成标准、docs/08-TESTING.md §4 的解析验收；
 *    ③ 本仓库既有产出：B 线 {@link ShaderPackRepository}（只读 IO）、本环境的 {@link ShaderPackService}
 *    （程序枚举）、D 线 {@link GlslPipeline}（预处理 + 转译编排）。
 *    许可证：本文件为独立编写的纯 Java 编排层，不含任何第三方项目代码。
 *    → 能否并入本项目（MIT）：可以（仅编排本项目自研组件）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：无外部实现可参考 —— 两个参考模组（VulkanMod / Sulkan）都没有 OF/Iris 包加载器
 *    （AGENT_CONTEXT §1「也没有任何参考模组做过 OF 包加载器」），故本类按本项目契约自行编排。
 * 2. 备选：把「遍历程序 + 读源 + 调管线」直接写进主线 —— 会让冷路径逻辑散落进热路径类，
 *    且主线类属关键路径（18-PARALLEL §8.1 env-1 独占），并行线无法单测。故独立成类。
 * 3. 我们的差异点：
 *    ① **只产「最终 GLSL 源文本」，不做驱动级编译** —— 把源交给 RenderPipeline / SPIR-V 编译器
 *       是主线的事，需要 GPU 证据（01-DEV-LOOP §0）；本类在冷路径上把「源」准备到位；
 *    ② **逐阶段独立降级**：某个阶段失败不株连其它阶段，也不让整包不可用（T11：显式可见，不静默）；
 *    ③ 复用 {@link ShaderPackService#load} 的程序枚举结果，不重复实现「哪些文件算程序」的判定；
 *    ④ 输入输出全是内存数据（文本 + POJO），不触碰 {@code com.mojang.*}
 *       （18-PARALLEL §2 并行判据四条）。
 * 4. 许可证核对结论：本项目 MIT；零第三方代码并入（07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径（切包/重载时调用一次），清晰优先，不做任何性能优化
 *    （18-PARALLEL §7.7、07-CONSTRAINTS T14 / X14）。
 */
public final class ShaderPackCompiler {

    /** 一个 program 的一个阶段的编译产物。 */
    public record CompiledStage(
            String programName,
            ShaderStage stage,
            String sourceFile,
            TranslateResult result) {

        /** 归一构造：字段校验 + 拒绝 null 阶段（阶段方向不许猜，与 D 线的 UNKNOWN 语义一致）。 */
        public CompiledStage {
            if (programName == null || programName.isBlank()) {
                throw new IllegalArgumentException("vkdisp: 编译产物的程序名不许为空白");
            }
            if (stage == null) {
                throw new IllegalArgumentException("vkdisp: 编译产物 '" + programName + "' 的着色器阶段不许为 null");
            }
            if (sourceFile == null || sourceFile.isBlank()) {
                throw new IllegalArgumentException("vkdisp: 编译产物 '" + programName + "' 的源文件路径不许为空白");
            }
            if (result == null) {
                throw new IllegalArgumentException("vkdisp: 编译产物 '" + programName + "' 的结果不许为 null");
            }
        }

        /** 该阶段是否成功（产出的源文本可用）。 */
        public boolean isSuccess() {
            return result.isSuccess();
        }
    }

    /**
     * 一个包的编译产物。
     *
     * @param pack        归属的包模型；{@code null} 表示包连模型都没组装出来（诊断里必有原因）
     * @param stages      各程序各阶段的产出（顺序与 {@link ShaderPack#programs()} 一致）
     * @param diagnostics 全部诊断的汇总（含 {@link ShaderPackService#load} 的包级诊断
     *                    与各阶段自身的诊断；阶段级明细同样可从 {@link CompiledStage#result()} 取）
     */
    public record CompileResult(
            ShaderPack pack,
            List<CompiledStage> stages,
            List<TranslateDiagnostic> diagnostics) {

        /** 归一构造：两个列表冻结为不可变。 */
        public CompileResult {
            stages = stages == null ? List.of() : List.copyOf(stages);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }

        /**
         * 是否全部阶段都成功。
         *
         * <p>没有阶段的包（例如只有 {@code shaders.properties} 的包）视为成功 —— 没有失败项，
         * 而不是失败；调用方若要求「至少一个阶段」，自行判断 {@link #stages()} 是否为空。
         */
        public boolean isSuccess() {
            return pack != null && stages.stream().allMatch(CompiledStage::isSuccess);
        }

        /** 失败的阶段清单（{@link #isSuccess()} == false 时非空）。 */
        public List<CompiledStage> failedStages() {
            return stages.stream().filter(stage -> !stage.isSuccess()).toList();
        }
    }

    private ShaderPackCompiler() {}

    /**
     * 把一个包编译成各阶段的最终 GLSL 源。
     *
     * <p>永不抛非受检异常：解包 / 读源 / 管线执行中的任何失败都降级为诊断（T11）。
     *
     * @param discovered 扫描器产出的合法包
     */
    public static CompileResult compile(ShaderPackScanner.DiscoveredPack discovered) {
        List<TranslateDiagnostic> diagnostics = new ArrayList<>();
        if (discovered == null) {
            diagnostics.add(TranslateDiagnostic.of(
                    TranslateDiagnostic.Severity.ERROR, "vkdisp: 待编译的包为 null，已跳过"));
            return new CompileResult(null, List.of(), diagnostics);
        }

        ShaderPackService.LoadResult loaded = ShaderPackService.load(discovered);
        diagnostics.addAll(loaded.diagnostics());
        ShaderPack pack = loaded.pack();
        if (pack == null) {
            // 模型没组装出来 → 没有可靠的程序清单可编译；load 的诊断已说明原因。
            return new CompileResult(null, List.of(), diagnostics);
        }

        // 需要挂载计划来读源文件。ShaderPackService.load 内部已 plan 过一次；
        // 冷路径上重复一次只读枚举可以接受（换来实现上的清晰：编译器不必依赖 load 的内部状态）。
        ShaderPackRepository.MountPlan plan;
        try {
            plan = ShaderPackRepository.plan(discovered);
        } catch (IOException | RuntimeException e) {
            diagnostics.add(TranslateDiagnostic.of(
                    TranslateDiagnostic.Severity.ERROR,
                    "vkdisp: 读取包文件索引失败，无法编译: " + e.getMessage(),
                    String.valueOf(discovered.source()), TranslateDiagnostic.UNKNOWN_LINE));
            return new CompileResult(pack, List.of(), diagnostics);
        }

        IncludeResolver resolver = ShaderPackService.resolverFor(plan);
        List<CompiledStage> stages = new ArrayList<>();
        for (Program program : pack.programs()) {
            String qualifiedName = program.dimensionFolder().isEmpty()
                    ? program.name()
                    : program.dimensionFolder() + "/" + program.name();
            compileStage(plan, resolver, qualifiedName, ShaderStage.VERTEX,
                    program.vertexShader(), stages, diagnostics);
            compileStage(plan, resolver, qualifiedName, ShaderStage.FRAGMENT,
                    program.fragmentShader(), stages, diagnostics);
        }
        return new CompileResult(pack, stages, diagnostics);
    }

    /**
     * 编译单个阶段：读源 → 跑 {@link GlslPipeline}（预处理 + 转译）→ 收进产出。
     *
     * <p>{@code sourcePath} 为 null 表示该阶段文件缺失 —— 这是 {@link Program} 契约允许的显式降级
     * （08-TESTING §4），直接跳过，不算失败也不打噪音日志。
     */
    private static void compileStage(
            ShaderPackRepository.MountPlan plan,
            IncludeResolver resolver,
            String qualifiedName,
            ShaderStage stage,
            String sourcePath,
            List<CompiledStage> sink,
            List<TranslateDiagnostic> diagnostics) {
        if (sourcePath == null) {
            return;
        }
        String source = ShaderPackService.readText(plan, sourcePath);
        if (source == null) {
            diagnostics.add(TranslateDiagnostic.of(
                    TranslateDiagnostic.Severity.WARN,
                    "vkdisp: 程序 '" + qualifiedName + "' 的 " + stage + " 源文件不可读，跳过该阶段",
                    sourcePath, TranslateDiagnostic.UNKNOWN_LINE));
            return;
        }
        TranslateResult result;
        try {
            result = GlslPipeline.run(stage, sourcePath, source, resolver);
        } catch (RuntimeException e) {
            diagnostics.add(TranslateDiagnostic.of(
                    TranslateDiagnostic.Severity.WARN,
                    "vkdisp: 程序 '" + qualifiedName + "' 的 " + stage + " 阶段执行管线时异常，已跳过: "
                            + e.getMessage(),
                    sourcePath, TranslateDiagnostic.UNKNOWN_LINE));
            return;
        }
        diagnostics.addAll(result.diagnostics());
        sink.add(new CompiledStage(qualifiedName, stage, sourcePath, result));
    }
}
