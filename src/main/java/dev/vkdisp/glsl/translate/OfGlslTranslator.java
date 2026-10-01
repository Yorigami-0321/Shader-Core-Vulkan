package dev.vkdisp.glsl.translate;

import java.util.ArrayList;
import java.util.List;

import dev.vkdisp.glsl.SourceLineMap;
import dev.vkdisp.glsl.TranslateDiagnostic;
import dev.vkdisp.glsl.TranslateResult;

/**
 * 【参考调研】D 线 GLSL 转译入口 / 04-SPEC §3.3 + 18-PARALLEL §4 D 线 + F3 行号契约
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/04-SPEC.md §3.3（OfGlslTranslator：OF GLSL → M GLSL 的入口）、
 *    §3.2（OF 内建 uniform 表）、docs/18-PARALLEL.md §4 D 线（独占路径、完成标准：幂等 +
 *    内建 uniform 注入完整 + gl_ 内建差异转换）、§7（硬边界 / 证据规范）、
 *    docs/18-PARALLEL.md §3 F3 与 src/main/java/dev/vkdisp/glsl/ 的冻结契约
 *    （TranslateResult / TranslateDiagnostic / SourceLineMap，仅消费、不修改）—— 仓库内文档与
 *    本项目自研契约，不受第三方版权约束；另加 GLSL 官方公开语义（1.20 内建 → 330 core 的变化）。
 *    外部候选 IrisShaders/glsl-transformer（GPL-3.0 + 例外条款，18-PARALLEL §4 D 线明示"按禁止处理"）→
 *    按禁止处理（07-CONSTRAINTS L12 §1.3 陷阱 2 / X21），本任务不读其代码、零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 实现，不含任何外部项目代码。
 *    → 能否并入本项目（MIT）：可以 —— 只采纳不受版权保护的事实性信息（转译范围、行号契约）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：F3 契约类给出的 C / D 背靠背用法（{@code dMap.compose(cMap)} 合成端到端映射、
 *    {@code TranslateDiagnostic.locatedAt(...)} 回填原文件位置）—— 本类是该用法的落地；
 *    转译内容（按 04-SPEC §3.3 的转译职责 + 公开 GLSL 语义）：
 *    ① AttributeRewriter（attribute/varying → in/out）、② UniformInjector（04-SPEC §3.2 内建 uniform）、
 *    ③ 二期补充：TextureFunctionRenamer（旧纹理 / shadow 函数 → texture 家族）、
 *    FtransformExpander（ftransform → 显式矩阵乘）、FragmentOutputAdapter（gl_FragColor /
 *    gl_FragData[n] → layout(location = N) out vec4）、
 *    P4.1.2 驱动层补充：VersionAdapter（#version 三段升 410：&lt;140 恒升 shaderc 地板 /
 *    140–409 无活跃 SSO 扩展升 glslang location 门控 / ≥410 与 ES profile 原样）、IoLocationAdapter
 *    （片元 in/out 与顶点 out 补 layout(location)，顶点属性按 §4 名字绑定跳过）。
 * 2. 备选：无 —— 不引入任何转译框架；七级文本变换 + 两次插入映射合成，够用即停。
 * 3. 我们的差异点：① 入口只做"编排 + 定位"，各级变换各自独立可测；
 *    ② **诊断定位按级取映射**：行内 / 等行数变换（①–⑤ 级）的诊断行号在 C 线输出坐标系里，
 *    直接经上游映射回填；⑦ 级（内建 uniform 注入）在 ⑥ 级插入之后运行，其诊断行号落在
 *    "⑥ 级输出"坐标系，必须先 {@code ⑥级映射.compose(上游映射)} 再回填 —— 否则插入行之后的
 *    诊断会整体错位；③ 端到端行号映射把两次插入的位移直接合成到一张映射上、再 compose 一次上游映射
 *    （不能写成"⑦ 级映射 compose ⑥ 级映射再 compose 上游"：F3 的 compose 在上游该行是合成行时会
 *    回退成"保留本阶段起源"，会让 ⑥ 级插入的合成声明被误标成某个真实源行号）；
 *    ④ 幂等性口径：**输出文本是转译的不动点**（再转译逐字节不变），且二次转译不产生任何
 *    WARN/ERROR；因为首次转译会插入新行（内建 uniform / 合成片元输出），输出行号映射本身按 F3
 *    语义必须变化，故幂等断言以文本为准（单测同时断言"全量已声明样本"下整个 TranslateResult 相等）。
 * 4. 许可证核对结论：本项目 MIT；GPL-3.0+例外参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径（包加载期一次）；七级线性扫描 + 两次映射合成，无缓存、无预优化
 *    （18-PARALLEL §7.7、T14 达标即停）。
 */
/**
 * D 线入口：把 OF 方言 GLSL 转译成现代 core profile GLSL（M 语法）。
 *
 * <p><b>转译流水线</b>（顺序固定，前一线的输出是后一线的输入）：
 * <ol>
 *   <li>{@link AttributeRewriter}：{@code attribute} / {@code varying} → {@code in} / {@code out}
 *       （按阶段定向，行内替换、行数不变）；</li>
 *   <li>{@link TextureFunctionRenamer}：旧纹理 / 阴影查找函数 → GLSL 330 core 的 texture 家族
 *       （行数不变）；</li>
 *   <li>{@link FtransformExpander}：{@code ftransform()} → {@code (gbufferProjection *
 *       gbufferModelView * vec4(Position, 1.0))}（行数不变）；</li>
 *   <li>{@link VersionAdapter}：{@code #version} 按三段规则就地升到 410 —— N &lt; 140 恒升
 *       （shaderc 地板）、140 ≤ N &lt; 410 且无活跃 SSO 扩展升（glslang location 门控）、
 *       N ≥ 410 / 已有 SSO / ES profile 原样（P4.1.2 驱动层，行数不变）；</li>
 *   <li>{@link IoLocationAdapter}：片元 in/out 与顶点 out 补 {@code layout(location = N)}
 *       （P4.1.2 驱动层；顶点 in 属性按 04-SPEC §4 名字绑定跳过，行数不变）；</li>
 *   <li>{@link FragmentOutputAdapter}：{@code gl_FragColor} / {@code gl_FragData[n]} →
 *       {@code layout(location = N) out vec4} 声明 + 标识符改写（可能插入合成声明行）；</li>
 *   <li>{@link UniformInjector}：补齐 04-SPEC §3.2 的 23 条 OF 内建 uniform 声明（只补缺失项，
 *       在文件头部区插入若干行）。</li>
 * </ol>
 * {@code #include} / {@code #define} 属 C 线，本入口既不解析也不改写（18-PARALLEL §4 D 线"不许做"）。
 *
 * <p><b>输入输出都是 F3 契约</b>：C 线产出 {@link TranslateResult}，D 线消费它再产出同类型。
 * ①–⑤ 级的诊断行号在本阶段输入（= C 线输出）坐标系里；⑦ 级的诊断行号在⑥级输出坐标系里，
 * 出口按级取对应映射反查后再用 {@link TranslateDiagnostic#locatedAt} 回填原文件与原始行号；
 * 行号映射：把两次插入（⑥ 级合成输出声明、⑦ 级内建 uniform）的位移合成一张"最终行 → C 线输出行"
 * 的映射，再 {@link SourceLineMap#compose} 一次上游映射得到端到端映射（C 线 → D 线不变契约，不需要改 F3）。
 *
 * <p><b>幂等口径</b>：{@code translate(stage, translate(stage, x).text()).text()}
 * 与 {@code translate(stage, x).text()} 逐字节相同 —— 输出文本是转译的不动点。
 * 注意：**行号映射不参与该断言** —— 首轮插入了新行，按 F3 语义输出行号必须相对首轮输入变化；
 * 若输入本就"内建 uniform 全已声明、片元输出全已声明"（无插入），则整个 {@link TranslateResult}
 * 两轮完全相等（含诊断与映射），单测对此有专门断言。
 *
 * <p><b>失败语义</b>：绝不抛异常、绝不静默降级（T11）。半截声明、片元阶段的 attribute、
 * 阶段未知、块注释未闭合、片元内建输出用于非片元阶段、{@code ftransform()} 用于非顶点阶段、
 * {@code gl_FragData} 下标非法 / 越界、片元输出槽位被不可用声明占用 → ERROR 诊断
 * （{@link TranslateResult#isSuccess()} = false）；重复声明、跨行声明、内建 uniform 类型不符、
 * 合成片元输出、无代码行、shadow 调用跨行 → WARN / INFO 诊断（结果仍可用）。
 */
public final class OfGlslTranslator {

    private OfGlslTranslator() {}

    /**
     * 纯文本入口：等价于 {@code translate(stage, TranslateResult.success(source))}。
     *
     * @param stage  着色器阶段；{@code null} 按 UNKNOWN 处理（出现 attribute/varying 即 ERROR）
     * @param source OF 方言 GLSL（{@code null} 按空串处理）
     * @return 转译结果；永不返回 {@code null}
     */
    public static TranslateResult translate(ShaderStage stage, String source) {
        return translate(stage, TranslateResult.success(source));
    }

    /**
     * 契约入口：消费 C 线的 {@link TranslateResult}（文本 + 行号映射 + 诊断），产出新的 {@link TranslateResult}。
     *
     * @param stage 着色器阶段；{@code null} 按 UNKNOWN 处理（出现 attribute/varying 即 ERROR）
     * @param input C 线输出；{@code null} 按空输入处理（不抛异常，出 WARN）
     * @return 转译结果；永不返回 {@code null}
     */
    public static TranslateResult translate(ShaderStage stage, TranslateResult input) {
        TranslateResult upstream = input == null ? TranslateResult.success("") : input;
        List<TranslateDiagnostic> diagnostics = new ArrayList<>();
        if (stage == null) {
            diagnostics.add(TranslateDiagnostic.warn("着色器阶段为 null，按 UNKNOWN 处理", null, 0));
        }
        if (upstream.text().isBlank()) {
            diagnostics.add(TranslateDiagnostic.warn(
                    "输入文本为空或仅含空白（null 亦按此处理），未做任何转译", null, 0));
            return TranslateResult.withDiagnostics(upstream.text(), upstream.lineMap(), diagnostics);
        }

        // ①–⑤ 行内 / 等行数变换：输出行号与输入行号一一对应，诊断行号 = C 线输出行号。
        AttributeRewriter.Result rewritten = AttributeRewriter.rewrite(stage, upstream.text());
        TextureFunctionRenamer.Result renamed = TextureFunctionRenamer.rename(rewritten.text());
        FtransformExpander.Result transformed = FtransformExpander.expand(stage, renamed.text());
        VersionAdapter.Result versioned = VersionAdapter.upgrade(transformed.text());
        IoLocationAdapter.Result located = IoLocationAdapter.locate(stage, versioned.text());

        // ⑥ 可能插入合成的片元输出声明：其诊断行号仍是插入前的（= C 线输出）行号。
        FragmentOutputAdapter.Result adapted = FragmentOutputAdapter.adapt(stage, located.text());

        // ⑦ 内建 uniform 注入：运行在 ⑥ 的输出上，诊断行号是 ⑥ 输出的行号（可能已被 ⑥ 右移）。
        UniformInjector.Result injected = UniformInjector.inject(adapted.text());

        for (TranslateDiagnostic diagnostic : rewritten.diagnostics()) {
            diagnostics.add(locate(diagnostic, upstream.lineMap()));
        }
        for (TranslateDiagnostic diagnostic : renamed.diagnostics()) {
            diagnostics.add(locate(diagnostic, upstream.lineMap()));
        }
        for (TranslateDiagnostic diagnostic : transformed.diagnostics()) {
            diagnostics.add(locate(diagnostic, upstream.lineMap()));
        }
        for (TranslateDiagnostic diagnostic : versioned.diagnostics()) {
            diagnostics.add(locate(diagnostic, upstream.lineMap()));
        }
        for (TranslateDiagnostic diagnostic : located.diagnostics()) {
            diagnostics.add(locate(diagnostic, upstream.lineMap()));
        }
        for (TranslateDiagnostic diagnostic : adapted.diagnostics()) {
            diagnostics.add(locate(diagnostic, upstream.lineMap()));
        }

        // ⑥ 级映射：⑥ 输出行 → C 线输出行；⑦ 的输入坐标 = ⑥ 的输出坐标。
        SourceLineMap adaptedMap = buildStageMap(
                SourceLines.of(located.text()).lineCount(),
                adapted.insertIndex(), adapted.insertedLineCount());
        SourceLineMap preInject = adaptedMap.compose(upstream.lineMap());
        for (TranslateDiagnostic diagnostic : injected.diagnostics()) {
            diagnostics.add(locate(diagnostic, preInject));
        }

        // 端到端映射：两次插入叠加。不能写成 injectedMap.compose(preInject) —— F3 的 compose 在
        // 上游"该行是合成行"时会回退成"保留本阶段起源"，于是"⑦ 级插入点之后、⑥ 级合成行"会被
        // 误标成上游的某个真实行号。这里按两次插入的位移直接算出"最终行 → ⑤ 级输出行"，
        // 合成行显式保留为合成行，再 compose 一次上游映射（仍然只消费 F3 契约，不改它）。
        SourceLineMap endToEnd = buildCombinedStageMap(
                SourceLines.of(adapted.text()).lineCount(),
                adapted.insertIndex(), adapted.insertedLineCount(),
                injected.insertIndex(), injected.insertedLineCount()).compose(upstream.lineMap());
        return TranslateResult.withDiagnostics(injected.text(), endToEnd, diagnostics);
    }

    /**
     * 本阶段输出行 → 本阶段输入行（插入点之后整体后移 K 行，插入的 K 行是合成行）。
     *
     * <p>与 F3 的用法一致：先建 D 自己的映射，再 {@code compose(上游)} 得到端到端映射。
     * 无插入（{@code insertedLineCount == 0}）时得到的就是恒等映射。
     *
     * @param inputLineCount    本阶段输入的行数
     * @param insertIndex       插入点之前原有行数（0 基）
     * @param insertedLineCount 插入的行数
     */
    private static SourceLineMap buildStageMap(int inputLineCount, int insertIndex, int insertedLineCount) {
        SourceLineMap.Builder builder = SourceLineMap.builder(null);
        int at = Math.max(0, Math.min(insertIndex, inputLineCount));
        for (int line = 1; line <= at; line++) {
            builder.add(line);
        }
        for (int count = 0; count < Math.max(0, insertedLineCount); count++) {
            builder.addSynthetic();
        }
        for (int line = at + 1; line <= inputLineCount; line++) {
            builder.add(line);
        }
        return builder.build();
    }

    /**
     * 两次插入叠加后的"最终输出行 → ⑤ 级输出（= 上游输入）行"映射。
     *
     * <p>{@code resolveInsertedLine} 把某个阶段的输出行还原成该阶段的输入行：插入点之前原样、
     * 插入区间内为 {@code null}（合成行）、之后整体前移插入行数。两级依次还原即可。
     *
     * @param adaptedLineCount     ⑥ 级输出的行数（= ⑦ 级输入行数）
     * @param adaptedInsertIndex   ⑥ 级插入点之前原有行数（0 基）
     * @param adaptedInsertedCount ⑥ 级插入行数
     * @param injectedInsertIndex  ⑦ 级插入点之前原有行数（0 基）
     * @param injectedInsertedCount ⑦ 级插入行数
     */
    private static SourceLineMap buildCombinedStageMap(int adaptedLineCount,
            int adaptedInsertIndex, int adaptedInsertedCount,
            int injectedInsertIndex, int injectedInsertedCount) {
        SourceLineMap.Builder builder = SourceLineMap.builder(null);
        int outputLineCount = adaptedLineCount + Math.max(0, injectedInsertedCount);
        for (int outputLine = 1; outputLine <= outputLineCount; outputLine++) {
            Integer adaptedLine = resolveInsertedLine(outputLine, injectedInsertIndex, injectedInsertedCount);
            if (adaptedLine == null) {
                builder.addSynthetic();
                continue;
            }
            Integer transformedLine = resolveInsertedLine(adaptedLine, adaptedInsertIndex, adaptedInsertedCount);
            if (transformedLine == null) {
                builder.addSynthetic();
                continue;
            }
            builder.add(transformedLine);
        }
        return builder.build();
    }

    /**
     * 某阶段的输出行 → 该阶段的输入行。
     *
     * @return 输入行号（1 起）；该输出行是本阶段插入的合成行时返回 {@code null}
     */
    private static Integer resolveInsertedLine(int outputLine, int insertIndex, int insertedCount) {
        if (insertedCount <= 0) {
            return outputLine;
        }
        if (outputLine <= insertIndex) {
            return outputLine;
        }
        if (outputLine <= insertIndex + insertedCount) {
            return null;
        }
        return outputLine - insertedCount;
    }

    /**
     * 把本阶段坐标的诊断回填成原文件坐标。
     *
     * <p>上游映射未命中（或本阶段行号未知）时原样返回 —— {@link TranslateDiagnostic#format()} 会逐级
     * 省略缺失的位置段，不会 NPE、不会编造位置（不许猜，X9）。
     */
    private static TranslateDiagnostic locate(TranslateDiagnostic diagnostic, SourceLineMap upstream) {
        if (diagnostic.line() <= TranslateDiagnostic.UNKNOWN_LINE) {
            return diagnostic;
        }
        SourceLineMap.LineOrigin origin = upstream.originOf(diagnostic.line());
        if (origin.sourceFile() == null && origin.sourceLine() <= TranslateDiagnostic.UNKNOWN_LINE) {
            return diagnostic;
        }
        return diagnostic.locatedAt(origin.sourceFile(), origin.sourceLine(), diagnostic.column());
    }
}
