package dev.vkdisp.glsl.translate;

import dev.vkdisp.glsl.TranslateDiagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 诊断用转译段：把片元里的 {@code vec2 dcdx = dFdx(…);} 改写成 {@code vec2 dcdx = vec2(0.0);}。
 *
 * <p>【参考调研】GLSL 屏幕空间派生函数与显式 LOD 采样 / GLSL 3.30 规范（公开语言事实）
 * <ol>
 * <li>合规核对：参考对象 = GLSL 3.30 规范的公开语言事实（dFdx 在片元着色器中求屏幕空间导数；
 *     textureGrad(sampler, P, dPdx, dPdy) 用显式导数决定 LOD）+ 本仓库自有 BSL 转译终稿的事实观察，
 *     无任何第三方代码并入 → 可并入本项目（MIT）；例外条款：无。纯正则行内改写 + 诊断。</li>
 * <li>官方/主实现：原版 core/terrain 根本不声明 dcdx/dcdy（那是 OF/Iris 包自己的写法）。</li>
 * <li>备选：① 去掉整段视差逻辑 —— 否决（那是改包语义，不是量问题）；
 *     ② 无视差异直接换绑 —— 否决（X9：改一个看起来对的绑定再宣布修好，就是假绿）。</li>
 * <li>差异点：只把导数的初值换成 0，一个字节的包语义都不改。按 GLSL 规定 dPdx/dPdy 为 0 时，
 *     textureGrad 的 LOD 选取与 texture() 的隐式导数同为 0 ⇒ 采样结果应与 texture() 完全一致，
 *     所以「改写后画面是否变亮」是一个干净的二值判据。</li>
 * <li>许可证：本项目 MIT，零第三方代码复制。</li>
 * <li>性能基线：等行数正则改写，只在诊断开关打开时执行一次。</li>
 * </ol>
 *
 * <p>🔖 <b>为什么必须做成转译段</b>（而不是顶点侧探针）：这两行声明在**片元着色器里**
 * （BSL 高级材质路径实测第 293 行），顶点适配层根本够不着。
 *
 * <p>⚠️ <b>默认关</b>；开启时按 X45 <b>自报状态与命中数</b>，否则无法区分
 * 「开关没生效」与「结论不成立」。
 */
public final class DerivativeProbeAdapter {

    /**
     * 形如 {@code vec2 dcdx = dFdx(texCoord);} 的声明行。
     * 🔖 第 2 组把行尾的分号与注释一起原样捕获（不是丢弃）⇒ 不会出现 X44 那种
     * 「分号被行尾注释吃掉」的问题。
     */
    private static final Pattern DECL = Pattern.compile(
            "^(\\s*vec2\\s+dc(dx|dy)\\s*=\\s*)dF(dx|dy)\\w*\\s*\\([^;]*?\\)\\s*(;.*)$");

    private static volatile boolean enabled;

    private DerivativeProbeAdapter() {}

    /** 诊断总闸（默认关）。 */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /** 当前是否开启。 */
    public static boolean enabled() {
        return enabled;
    }

    /** 结果（纯数据 + 文本；等行数 ⇒ 行号映射不受影响）。 */
    public record Result(String text, List<TranslateDiagnostic> diagnostics, int patched) {

        public Result {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    /**
     * 等行数改写：把每个 {@code vec2 dcdx|dcdy = dFdx(…);} 的右值换成 {@code vec2(0.0)}。
     *
     * @param stage 诊断上下文
     * @param text  7.5 段输出
     */
    public static Result apply(ShaderStage stage, String text) {
        List<TranslateDiagnostic> diagnostics = new ArrayList<>();
        if (!enabled || text == null) {
            return new Result(text, diagnostics, 0);
        }
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length() + 64);
        int patched = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher matcher = DECL.matcher(line);
            // U0001f534 必须核对「名字的方向」与「函数的方向」一致（dcdx↔dFdx、dcdy↔dFdy）：
            //   BSL 实测第 293/294 行正是 dFdx / dFdy **各一个**。
            //   U0001f501 首版只匹配了 dFdx ⇒ dcdy 那条**探针完全没生效**（单变量实验只剩半个变量），
            //   而日志只报「已把 N 处」不会说「漏了 y 方向」⇒ 由单测当场抓住。
            if (matcher.matches() && matcher.group(2).equals(matcher.group(3))) {
                patched++;
                out.append(matcher.group(1)).append("vec2(0.0)").append(matcher.group(4));
            } else {
                out.append(line);
            }
            if (i + 1 < lines.length) {
                out.append('\n');
            }
        }
        diagnostics.add(TranslateDiagnostic.info(
                "派生导数探针**已开启**：已把 " + patched + " 处 dFdx/dFdy 初值改成 vec2(0.0)"
                        + "（按 GLSL 规定，此时 textureGrad 的 LOD 选取应与 texture() 相同）",
                null, 0));
        if (patched == 0) {
            diagnostics.add(TranslateDiagnostic.warn(
                    "派生导数探针**已开启但一处都没命中** ⇒ 画面若与默认一致，说明**开关没生效**"
                            + "（不是结论不成立）：本包可能根本没声明 dcdx/dcdy",
                    null, 0));
        } else {
            diagnostics.add(TranslateDiagnostic.warn(
                    "[诊断] 导数已被置零：" + patched + " 处。若画面**仍然全黑**，"
                            + "则 textureGrad 本身在本链路下就有问题（不是 LOD 选取的问题）",
                    null, 0));
        }
        return new Result(out.toString(), diagnostics, patched);
    }
}