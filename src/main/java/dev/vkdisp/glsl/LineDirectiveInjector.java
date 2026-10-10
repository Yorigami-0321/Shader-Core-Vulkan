package dev.vkdisp.glsl;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【参考调研】A3 · `#line` 发射 + 驱动错误归因（`19` §2.6-A3 / §2.2-c）
 * 0. 合规：参考对象 = GLSL 规范 §3.3（`#line` 预处理指令的语义：「下一条物理行的行号变为 N，
 *    源文件变为 S」—— 语言规范事实，不受版权保护）与本仓库自有 `SourceLineMap`（F3 冻结契约）。
 *    外部候选 glsl-transformer / glsl-preprocessor（GPL-3.0+例外）按禁止处理（`07` L12 / X20 / X21）：
 *    不读其代码、零并入。本项目 MIT。
 * 1. 职责：把转译产物的**行号映射**变成 shaderc 能理解的 `#line` 指令 ——
 *    驱动报错因此直接指向**包内原文件:行**，而不是「转译后文本的第 N 行」（§2.2-c 的病根）。
 * 2. 差异点：`#line` 只在**送进 shaderc 的那份临时文本**里发射，不改变存储的转译产物
 *    （幂等性不受影响：`GlslPipelineTest` 的不动点断言仍然逐字节成立）。
 * 3. 非显然约束：
 *    ① GLSL 要求 `#version` 必须是第一条非注释指令 ⇒ 首条 `#line` 只能落在 `#version` 之后；
 *    ② 合成行（`SourceLineMap` 里 origin.line == 0）不发射 `#line` —— 它们是我们注入的，
 *      没有「包内原行」可指；shaderc 对它们的报错会沿用上一条 `#line` 的上下文（可接受：
 *      合成行的错误归因走 {@link #attributeError} 的「本项目 ERROR」分支）；
 *    ③ `#line` 本身占一行 ⇒ 注入后文本行数增长，但 shaderc 报的行号是 `#line` 指定的值，
 *      不是物理行号，所以增长不影响错误定位。
 * 4. 性能：❄️ 冷路径（每阶段编译一次）；线性扫描 + StringBuilder，无缓存。
 */
public final class LineDirectiveInjector {

    private static final String VERSION_PREFIX = "#version";

    /** shaderc 错误原文里的 `file:line:col` 模式（glslang 标准输出格式）。 */
    private static final Pattern ERROR_LOCATION = Pattern.compile(
            "([^\\s:]+):(\\d+):(\\d+)");

    private LineDirectiveInjector() {
    }

    /**
     * 把 `#line` 指令注入转译产物（只用于送 shaderc 的临时副本，不改存储文本）。
     *
     * <p>算法：逐行走 {@code lineMap}，当起源（文件, 行号）相对上一条**不连续**时发射
     * {@code #line <originLine> "<originFile>"}。首条 `#line` 必须落在 `#version` 之后。
     *
     * @param translatedText 转译产物（`TranslateResult.text()`）
     * @param lineMap        端到端行号映射（`TranslateResult.lineMap()`）
     * @return 注入 `#line` 后的文本（送 shaderc 用）；lineMap 为 unmapped 时原样返回
     */
    public static String inject(String translatedText, SourceLineMap lineMap) {
        if (translatedText == null || translatedText.isEmpty()) {
            return translatedText == null ? "" : translatedText;
        }
        if (lineMap == null || lineMap == SourceLineMap.unmapped() || lineMap.outputLineCount() == 0) {
            return translatedText;
        }
        String[] lines = translatedText.split("\n", -1);
        StringBuilder out = new StringBuilder(translatedText.length() + lines.length * 8);
        boolean passedVersion = false;
        String prevFile = null;
        int prevLine = -1;

        for (int i = 0; i < lines.length && i < lineMap.outputLineCount(); i++) {
            String line = lines[i];
            if (!passedVersion) {
                out.append(line).append('\n');
                if (line.strip().startsWith(VERSION_PREFIX)) {
                    passedVersion = true;
                }
                continue;
            }
            SourceLineMap.LineOrigin origin = lineMap.originOf(i + 1);
            if (origin.lineKnown() && origin.sourceFile() != null) {
                boolean discontinuous = !origin.sourceFile().equals(prevFile)
                        || origin.sourceLine() != prevLine + 1;
                if (discontinuous) {
                    out.append("#line ").append(origin.sourceLine())
                            .append(" \"").append(escapeFileName(origin.sourceFile()))
                            .append("\"\n");
                }
                prevFile = origin.sourceFile();
                prevLine = origin.sourceLine();
            }
            out.append(line).append('\n');
        }
        // 超出 lineMap 覆盖范围的尾部行（正常不会出现，防御性保留）
        for (int i = lineMap.outputLineCount(); i < lines.length; i++) {
            out.append(lines[i]).append('\n');
        }
        // split("\n", -1) 对以 \n 结尾的文本会多出一个空尾段；去掉多余的那个换行
        if (out.length() > 0 && out.charAt(out.length() - 1) == '\n'
                && !translatedText.endsWith("\n")) {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    /**
     * 驱动错误归因：把 shaderc 原文里的行号映射回包内原文件:行，并判定因果归属。
     *
     * <p>两种输入形态（由是否注入了 `#line` 决定）：
     * <ul>
     *   <li>有 `#line`：shaderc 报的 file:line **已经是**包内坐标 ⇒ 只需确认该坐标在 lineMap 里有起源</li>
     *   <li>无 `#line`：shaderc 报的是转译产物的输出行号 ⇒ 用 {@code lineMap.originOf} 反查</li>
     * </ul>
     *
     * <p>三种归因：
     * <ul>
     *   <li>{@code TRANSLATION_BUG}：错误落在我们注入的合成行（origin 未知）⇒ 本项目 ERROR</li>
     *   <li>{@code PACK_SOURCE}：错误落在包内原行 ⇒ 包源本身不合法（或我们的转译改坏了它）</li>
     *   <li>{@code UNKNOWN}：无法解析行号</li>
     * </ul>
     */
    public static Attribution attributeError(String shadercError, SourceLineMap lineMap) {
        if (shadercError == null || shadercError.isBlank()) {
            return new Attribution(Cause.UNKNOWN, shadercError == null ? "" : shadercError, List.of());
        }
        Matcher m = ERROR_LOCATION.matcher(shadercError);
        List<MappedLocation> locations = new ArrayList<>();
        Cause overall = Cause.UNKNOWN;
        while (m.find()) {
            String reportedFile = m.group(1);
            int reportedLine = Integer.parseInt(m.group(2));
            MappedLocation mapped = mapLine(reportedFile, reportedLine, lineMap);
            locations.add(mapped);
            if (mapped.cause() == Cause.TRANSLATION_BUG) {
                overall = Cause.TRANSLATION_BUG;
            } else if (mapped.cause() == Cause.PACK_SOURCE && overall != Cause.TRANSLATION_BUG) {
                overall = Cause.PACK_SOURCE;
            }
        }
        return new Attribution(overall, shadercError, List.copyOf(locations));
    }

    /** 错误因果归属。 */
    public enum Cause {
        /** 错误落在本项目注入的合成行 ⇒ 转译 bug（ERROR 级）。 */
        TRANSLATION_BUG,
        /** 错误落在包内原行 ⇒ 包源不合法或转译改坏了它。 */
        PACK_SOURCE,
        /** 无法解析行号 / 无映射。 */
        UNKNOWN
    }

    /** 一个被映射的错误位置。 */
    public record MappedLocation(int reportedLine, String originFile, int originLine, Cause cause) {

        public String readable() {
            if (originFile != null && originLine > 0) {
                return originFile + ":" + originLine;
            }
            return "(合成行，无包内起源)";
        }
    }

    /** 归因结果。 */
    public record Attribution(Cause cause, String rawError, List<MappedLocation> locations) {

        /** 归因后的可读摘要（日志用）。 */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("归因=").append(cause);
            for (MappedLocation loc : locations) {
                sb.append(" | 报告行=").append(loc.reportedLine())
                        .append(" → ").append(loc.readable());
            }
            return sb.toString();
        }
    }

    private static MappedLocation mapLine(String reportedFile, int reportedLine,
            SourceLineMap lineMap) {
        if (lineMap == null || lineMap == SourceLineMap.unmapped()) {
            return new MappedLocation(reportedLine, null, 0, Cause.UNKNOWN);
        }
        // 路径 1（无 #line）：reportedLine 是转译产物的输出行号 → 直接反查起源。
        SourceLineMap.LineOrigin origin = lineMap.originOf(reportedLine);
        if (origin.lineKnown() && origin.sourceFile() != null) {
            return new MappedLocation(reportedLine, origin.sourceFile(), origin.sourceLine(),
                    Cause.PACK_SOURCE);
        }
        // 路径 1b：输出行号在映射范围内但起源是合成行 → 本项目注入的代码有错。
        if (reportedLine <= lineMap.outputLineCount()) {
            return new MappedLocation(reportedLine, null, 0, Cause.TRANSLATION_BUG);
        }
        // 路径 2（有 #line）：reportedLine 已经是包内行号（shaderc 按 #line 报的）。
        // 在 lineMap 里搜索是否有任何输出行的起源 == (reportedFile, reportedLine)。
        for (int i = 1; i <= lineMap.outputLineCount(); i++) {
            SourceLineMap.LineOrigin candidate = lineMap.originOf(i);
            if (candidate.sourceLine() == reportedLine
                    && (reportedFile == null || reportedFile.endsWith(candidate.sourceFile())
                        || candidate.sourceFile() == null
                        || candidate.sourceFile().endsWith(reportedFile))) {
                return new MappedLocation(reportedLine, candidate.sourceFile(),
                        candidate.sourceLine(), Cause.PACK_SOURCE);
            }
        }
        // 找不到对应起源：可能是 #line 上下文沿用到了合成行区域。
        return new MappedLocation(reportedLine, reportedFile, reportedLine, Cause.TRANSLATION_BUG);
    }

    private static String escapeFileName(String file) {
        return file.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
