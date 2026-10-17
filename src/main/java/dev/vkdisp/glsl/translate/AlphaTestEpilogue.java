package dev.vkdisp.glsl.translate;

import dev.vkdisp.glsl.TranslateDiagnostic;
import dev.vkdisp.pack.model.AlphaTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【参考调研】把 {@code alphaTest.<程序>} 兑现成片元里的 {@code discard}
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本项目移植件 {@code pack/model/AlphaTest}（Vitrail {@code 4a45f44}，LGPL-3.0，
 *    阈值/比较名/取反形态都由那张表决定）；② **注入条件这条规则**取自上游公开记载：Vitrail
 *    {@code NOTICE} 里写明它把 alpha 测试写进片元阶段的条件是「该 stage 经 {@code gl_FragColor}
 *    或字面下标的 {@code gl_FragData[0]}  reaches draw buffer nought」，并点名该规则来自
 *    Iris {@code pipeline/transform/transformer/CommonTransformer}（{@code :280}，配合 {@code :239}
 *    把 {@code gl_FragColor} 改名到 {@code gl_FragData[0]}），读取日期 2026-09-10（26.2 分支）。
 *    🔴 <b>取的是规则，不是代码</b>（07 L13 / §1.3）：那一段在 Iris 侧链 AGPL-3.0 的
 *    glsl-transformer，本项目**不引库也不搬其代码**；本文件是独立编写的纯 Java 实现。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以（本文件零外部代码，只采纳不受版权保护的规则与 GLSL 公开语义）
 *    → 例外条款：无
 * 1. 官方/主实现：GLSL core profile 没有 {@code glAlphaFunc}，固定功能时代的 alpha 测试必须在
 *    着色器里写成 {@code discard}；Iris/Sodium 都是这么补的（阈值见 {@link AlphaTest}）。
 * 3. 我们的差异点：① 判定发生在**转译之后**的文本上 —— 那时 {@code gl_FragColor}/{@code gl_FragData[0]}
 *    已被 ⑦ 段落成 {@code layout(location = 0) out vec4 X}，所以「有没有写 drawbuffer 0」在这里
 *    等价于「声明里有没有 location 0 的 vec4 输出」，比按裸拼法判定更稳；② 只在 {@code FRAGMENT}
 *    阶段动手；③ 注入落在 **{@code void main()} 的收尾花括号之内**（按深度配对，不假定 main 是最后一个
 *    函数）；④ 三种「不注入」都出一条 WARN 点名原因（T11 不静默）：没有 location 0 输出、
 *    找不到 main、main 的花括号不配对。
 * 4. 许可证核对结论：本项目 LGPL-3.0-or-later；本文件独立编写，引用的上游规则已注明「取规则不取代码」。
 * 5. 性能基线：❄️ 冷路径（切包/重载时对每个片元源走一遍），只求清晰不做优化（18-PARALLEL §7.7）。
 */
public final class AlphaTestEpilogue {

    /** 转译⑦ 之后才会存在的形态：location 0 的 vec4 片元输出声明。 */
    private static final Pattern SLOT_ZERO_OUTPUT = Pattern.compile(
            "layout\\s*\\(\\s*location\\s*=\\s*0\\s*\\)\\s*(?:flat\\s+|noperspective\\s+|centroffs\\s+)*"
                    + "out\\s+vec4\\s+(\\w+)\\s*;");

    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*\\s*\\)\\s*\\{");

    private AlphaTestEpilogue() {
    }

    /**
     * @param injected 是否真的写了那条测试
     * @param reason   没写时的原因（写了则为 null）
     */
    public record Result(String text, List<TranslateDiagnostic> diagnostics, boolean injected,
            String reason) {

        public Result {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /**
     * 把测试写进片元源。
     *
     * @param stage  着色器阶段；非 {@link ShaderStage#FRAGMENT} 原样返回
     * @param source 转译后的片元源码
     * @param test   该程序的 alpha 测试；{@code null} 或 {@link AlphaTest#OFF} 原样返回
     */
    public static Result apply(ShaderStage stage, String source, AlphaTest test) {
        List<TranslateDiagnostic> diagnostics = new ArrayList<>();
        if (source == null || source.isEmpty()) {
            return new Result(source == null ? "" : source, diagnostics, false, "源文本为空");
        }
        if (stage != ShaderStage.FRAGMENT) {
            return new Result(source, diagnostics, false, "非片元阶段");
        }
        if (test == null) {
            return new Result(source, diagnostics, false, "该程序没有 alphaTest 声明");
        }
        if (!test.tests()) {
            return new Result(source, diagnostics, false, "alphaTest 是 ALWAYS/off ⇒ 本来就不测");
        }

        Matcher out = SLOT_ZERO_OUTPUT.matcher(source);
        if (!out.find()) {
            diagnostics.add(TranslateDiagnostic.warn(
                    "vkdisp: [alphaTest] 未注入：本片元没有 layout(location = 0) out vec4 输出"
                            + "（它不写 drawbuffer 0，按上游口径这条测试无处可测）",
                    null, TranslateDiagnostic.UNKNOWN_LINE));
            return new Result(source, diagnostics, false, "没有 drawbuffer 0 的输出声明");
        }
        String alphaExpr = out.group(1) + ".a";
        String statement = test.discard(alphaExpr);

        Matcher main = MAIN.matcher(source);
        if (!main.find()) {
            diagnostics.add(TranslateDiagnostic.warn(
                    "vkdisp: [alphaTest] 未注入：找不到 void main() { —— " + statement
                            + " 没有可落脚的位置",
                    null, TranslateDiagnostic.UNKNOWN_LINE));
            return new Result(source, diagnostics, false, "找不到 void main()");
        }

        int close = closingBrace(source, main.end() - 1);
        if (close < 0) {
            diagnostics.add(TranslateDiagnostic.warn(
                    "vkdisp: [alphaTest] 未注入：void main() 的花括号不配对，不敢在未知深度插行",
                    null, TranslateDiagnostic.UNKNOWN_LINE));
            return new Result(source, diagnostics, false, "main 的花括号不配对");
        }

        // 🔴 **等行数**变换：语句插在收尾 } 的**同一行**前面，不新增行 ⇒ 行号映射与本仓
        //     既有的等行数段（①–④ / ⑥ / ⑦½ / 7.75）同一口径，诊断归属不会因这一眼右移一行。
        String injected = source.substring(0, close) + statement + " " + source.substring(close);
        diagnostics.add(TranslateDiagnostic.of(TranslateDiagnostic.Severity.INFO,
                "vkdisp: [alphaTest] 已注入 " + statement + "（输出 " + out.group(1) + "）",
                null, TranslateDiagnostic.UNKNOWN_LINE));
        return new Result(injected, diagnostics, true, null);
    }

    /** 从 {@code open}（main 的起始 {@code &#123;}）按深度找到配对的收尾 {@code &#125;}；不配对返回 -1。 */
    private static int closingBrace(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 便于调用方「有就取、没有就 null」的查表helper。 */
    public static Optional<AlphaTest> of(Optional<AlphaTest> parsed) {
        return parsed == null ? Optional.empty() : parsed;
    }
}
