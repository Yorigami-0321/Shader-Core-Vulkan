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
import dev.vkdisp.config.PackOptionStore;
import dev.vkdisp.config.PackOptions;
import dev.vkdisp.config.PackOptionsSession;
import dev.vkdisp.glsl.TranslateDiagnostic;
import dev.vkdisp.glsl.translate.ShaderStage;
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
                    .anyMatch(program -> TERRAIN_PROGRAM.equals(program.name()));
            if (!declaresTerrain) {
                diagnostics.add(TranslateDiagnostic.of(TranslateDiagnostic.Severity.INFO,
                        "vkdisp: 包 '" + pack.name() + "' 不含 " + TERRAIN_PROGRAM
                                + " 程序，地形片元不接线（继续找下一个包）",
                        pack.name(), TranslateDiagnostic.UNKNOWN_LINE));
                continue;
            }
            PackOptionsSession session = PackOptionsSession.create(pack, profile, store);
            Map<String, String> overrides = diffAgainstDefaults(session.options());

            ShaderPackCompiler.CompileResult compiled =
                    PackCompileCache.getOrCompile(discovered, overrides);
            diagnostics.addAll(compiled.diagnostics());
            String source = selectTerrainFragment(compiled);
            if (source == null) {
                diagnostics.add(TranslateDiagnostic.of(TranslateDiagnostic.Severity.WARN,
                        named
                                ? "vkdisp: 指定包 '" + pack.name() + "' 的 " + TERRAIN_PROGRAM
                                        + " 片元阶段无成功产出（shaderPack 指定下不换包，地形片元不接线）"
                                : "vkdisp: 包 '" + pack.name() + "' 的 " + TERRAIN_PROGRAM
                                        + " 片元阶段无成功产出，尝试下一个包",
                        pack.name(), TranslateDiagnostic.UNKNOWN_LINE));
                continue;
            }
            String qualified = terrainQualifiedName(compiled, source);
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
                        ? "vkdisp: 指定包 '" + selection + "' 无可用的 " + TERRAIN_PROGRAM
                                + " 片元源（inventory=" + inventoryDir + "），地形片元不接线"
                        : "vkdisp: 库存中没有可用的 " + TERRAIN_PROGRAM + " 片元源，地形片元不接线"
                                + "（inventory=" + inventoryDir + ", profile='" + profile + "'）",
                selection.isEmpty() ? String.valueOf(inventoryDir) : selection,
                TranslateDiagnostic.UNKNOWN_LINE));
        return new Result(null, null, profile, diagnostics);
    }

    /**
     * 维度偏好：world0（主世界）> 根命名空间 > 其它维度；并列取先出现者（结果确定）。
     *
     * <p>🔖 与 composite 的 {@code dimensionRank} 同规则，但**不跨包配对** ——
     * 地形是唯一的几何来源，没有第二个阶段要与它配维度。
     */
    private static String selectTerrainFragment(ShaderPackCompiler.CompileResult compiled) {
        String best = null;
        int bestRank = Integer.MAX_VALUE;
        for (ShaderPackCompiler.CompiledStage stage : compiled.stages()) {
            if (stage.stage() != ShaderStage.FRAGMENT
                    || !stage.isSuccess()
                    || !isTerrainProgram(stage.programName())) {
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
            if (stage.stage() != ShaderStage.FRAGMENT || !isTerrainProgram(stage.programName())) {
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
    private static String terrainQualifiedName(ShaderPackCompiler.CompileResult compiled, String source) {
        for (ShaderPackCompiler.CompiledStage stage : compiled.stages()) {
            if (stage.stage() == ShaderStage.FRAGMENT
                    && stage.isSuccess()
                    && isTerrainProgram(stage.programName())
                    && stage.result().text().equals(source)) {
                return stage.programName();
            }
        }
        return TERRAIN_PROGRAM;
    }

    private static boolean isTerrainProgram(String qualifiedName) {
        return TERRAIN_PROGRAM.equals(qualifiedName)
                || qualifiedName.endsWith("/" + TERRAIN_PROGRAM);
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

    private static TranslateDiagnostic.Severity severityOf(ShaderPackScanner.ProblemKind kind) {
        return switch (kind) {
            case BROKEN_ZIP -> TranslateDiagnostic.Severity.ERROR;
            case INVENTORY_MISSING, NO_PACKS_FOUND -> TranslateDiagnostic.Severity.INFO;
            default -> TranslateDiagnostic.Severity.WARN;
        };
    }
}
