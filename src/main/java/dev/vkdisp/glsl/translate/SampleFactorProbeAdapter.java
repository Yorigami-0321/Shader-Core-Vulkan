package dev.vkdisp.glsl.translate;
/**
 * 🔖🔖 **KEEP_OUT：本类不得接进 {@link OfGlslTranslator} 的转译段链**（2026-10-05 实测）。
 *
 * <p>原因：{@code OfGlslTranslator.translate(stage, preProcessed)} <b>不知道自己在翻哪个程序</b>
 * （签名里只有阶段，没有程序名）⇒ 任何「用静态开关在这里生效」的探针都必然泄漏到
 * composite / deferred / final。
 * 🔴 <b>实测后果</b>：探针改写了 composite 里的
 * {@code float cloudViewLength = texture(gaux1, screenPos.xy).r * (far * 2.0);}
 * ⇒ 那一臂的<b>最终画面</b>被探针改过 ⇒ 该臂的全部数字<b>作废</b>，
 * 而日志里除了探针自己的自报行之外看不出任何异样。
 *
 * <p>⇒ 正确用法：由 {@code VkDispVirtualPack#generateTerrainSource} 在拿到
 * <b>地形片元源</b>之后直接调用本类 —— 作用域天然就是「地形源」，不需要任何全局可变状态，
 * 也就没有「后台预编译线程与渲染线程抢同一个静态开关」那条竞态。
 */
/**
 * 【参考调研】纹理采样乘法链的**两侧分离**探针（判「采样为 0」还是「乘子为 0」）
 * 0. 合规核对（第 0 步闸门）：
 *    参考对象 = ① 本仓库自有的 BSL v10.1.8 {@code shaders/program/gbuffers_terrain.glsl}
 *    第 164 行 {@code vec4 albedo = texture2D(texture, texCoord) * vec4(color.rgb, 1.0);}
 *    的**实测文本形态**（MIT 自有的实测观察，不搬运任何着色器文本到产品路径；
 *    本类只做**通用语法形态**的匹配，包特有的标识符名一律从源里解析出来，不硬编码）；
 *    ② GLSL 公开语言事实：{@code vec4} 乘法逐分量；采样函数返回 {@code vec4}。
 *    以上事实均不受版权保护。
 *    → 能否并入本项目（MIT）：可以（本文件为独立编写的纯 Java 行内改写器）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码，也不含任何 Mojang 着色器文本
 * 1. 官方/主实现：无（原版 core/terrain 没有这种乘法链；那是包自己的写法）。
 * 2. 备选：
 *    <ul>
 *      <li>① 顶点侧强制 {@code color = vec4(1)}（{@code h15}/{@code mrt.terrainColorProbe}）——
 *          <b>否决（本类的成因）</b>：那改的是<b>顶点适配层供的 varying</b>，
 *          而包里还可能用别的路径重算它；它排除的是「供值错」，
 *          <b>排除不了</b>「供值对、但纹理采样本身返回 0」。两边必须能分开测。</li>
 *      <li>② 把整个右值替换成常量 —— <b>部分否决</b>：它能证明「乘法链下游有没有问题」，
 *          但会让采样与乘子<b>一起</b>被换掉 ⇒ 分不出是哪一侧为 0。
 *          ⇒ 本类提供<b>两个独立开关</b>，各自只动一侧。</li>
 *      <li>③ 在 GPU 侧加 compute 通道取中间值 —— 否决：那要新增管线与附件，
 *          且本轮目标是「给已有观测面配判据」，不是新造渲染通道。</li>
 *    </ul>
 * 3. 我们的差异点：把「乘法链的左侧（采样结果）」与「右侧（乘子）」拆成两个可单独开启的
 *   探针，命中时<b>自报命中数与命中的那一行原文</b>（X45：不自报就分不清「开关没生效」
 *   与「结论不成立」）。等行数改写 ⇒ 行号映射不受影响（同 ⑦½ / 7.75 段口径）。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 冷路径（包加载期一次，默认关 ⇒ 零开销）。
 */
import dev.vkdisp.glsl.TranslateDiagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 {@code X = <采样调用> * <乘子>;} 拆开、分别可强制成已知常量的转译段（等行数）。
 *
 * <p><b>它回答的问题</b>（{@code h44} 把问题收窄到这一步）：
 * 「albedo ≡ 0」是一个<b>乘积</b>为 0，而乘法链两侧都可能为 0：
 * <pre>
 *   vec4 albedo = texture(texture_0, texCoord) * vec4(color.rgb, 1.0);
 *                       └─ 左侧：采样结果 ─┘   └── 右侧：乘子 ──┘
 * </pre>
 * ⇒ 强制<b>左侧</b>为非零常量：若 albedo 变亮 ⇒ 采样返回 0（输入侧问题：图集/坐标/采样器）；
 * 若仍黑 ⇒ 问题在右侧或更下游。
 * ⇒ 强制<b>右侧</b>为 {@code vec4(1,1,1,1)}：若变亮 ⇒ 乘子为 0（顶点供值侧）。
 *
 * <p>🔖 <b>两个开关必须独立</b>：同时开就等于把两侧一起换掉，等于什么都没分开。
 */
public final class SampleFactorProbeAdapter {

    /** 要替换成的常量（左侧用；非零，便于一眼看出「是不是被探针换过」）。 */
    public static final String PROBE_SAMPLE = "vec4(1.0, 0.5, 0.25, 1.0)";

    /** 右侧乘子的单位元（保持乘积不变 ⇒ 单独开它只检验「乘子是否为 0」）。 */
    public static final String PROBE_MULTIPLIER = "vec4(1.0, 1.0, 1.0, 1.0)";

    /**
     * 乘法链赋值/声明行：{@code [type ]lhs = <rhs>;}，其中 rhs 含一个采样调用与一个 {@code *}。
     *
     * <p>🔖 <b>必须同时匹配「声明」与「纯赋值」两种形态</b>：BSL 实测第 164 行是
     * {@code vec4 albedo = texture2D(texture, texCoord) * vec4(color.rgb, 1.0);} ——
     * 带 {@code vec4} 类型前缀的<b>声明</b>。第一版只匹配纯赋值（首部无类型词），
     * 结果**一处理都没命中**、而日志只报「已开启」⇒ 单变量实验静默空转
     * （与 {@code h14} 导数探针「漏了 y 方向」完全同族）。
     *
     * <p>🔖 <b>只认「右值里恰有一个顶层 {@code *}」</b>：
     * {@code a = f(x) * g(y);} 匹配，而 {@code a = f(x) * g(y) + h(z);} 不匹配
     * （那是复合表达式，替换掉会改变表达式结构而不只是换因子）。
     * 宁可漏匹配也不误匹配 —— 误匹配会把无关代码改掉，产出**假证据**。
     */
    private static final Pattern MULTIPLY_ASSIGN = Pattern.compile(
            "^(\\s*)((?:[A-Za-z_]\\w*\\s+)*)([A-Za-z_]\\w*(?:\\.[xyzwrgbastpq]+)?)"
                    + "\\s*=\\s*([^=*][^;]*?)\\s*\\*\\s*([^;]+?)\\s*(;.*)$");

    /** 采样调用（GLSL 1.20 时代的老式 texture2D / texture2DGradARB 等；转译后会变名，这里宽松匹配）。 */
    private static final Pattern SAMPLE_CALL = Pattern.compile(
            "\\b(texture2D(?:GradARB|Proj|ProjGrad|Lod|Grad)?|texture(?:Grad|Lod|Proj)?|textureCube"
                    + "(?:Grad|Lod)?)\\s*\\(");

    /** 左侧强制开关（把采样结果换成 {@link #PROBE_SAMPLE}）。 */
    private static volatile boolean forceSample;

    /** 右侧强制开关（把乘子换成 {@link #PROBE_MULTIPLIER}）。 */
    private static volatile boolean forceMultiplier;

    private SampleFactorProbeAdapter() {
    }

    /** 开关左侧（采样侧）。 */
    public static void setForceSample(boolean value) {
        forceSample = value;
    }

    /** 开关右侧（乘子侧）。 */
    public static void setForceMultiplier(boolean value) {
        forceMultiplier = value;
    }

    /** 当前是否开启了任一侧。 */
    public static boolean anyEnabled() {
        return forceSample || forceMultiplier;
    }

    /** 是否开启了左侧（供调用点自报配置）。 */
    public static boolean forceSampleEnabled() {
        return forceSample;
    }

    /** 是否开启了右侧。 */
    public static boolean forceMultiplierEnabled() {
        return forceMultiplier;
    }

    /**
     * 表达式里是否有<b>顶层</b>（括号深度 0）的 {@code +} 或 {@code -}。
     *
     * <p>🔖 深度判定是必需的：{@code texture(t - 1.0, uv)} 的减号在括号内，
     * 不改变表达式结构；而 {@code f(x) * g(y) + h(z)} 的加号在顶层，
     * 把它换掉就不再是「只换了一个因子」。
     * 🔖 前导 {@code -}（一元负号）不算顶层加减 —— 它是字面量符号的一部分。
     */
    private static boolean hasTopLevelAdditive(String expression) {
        int depth = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && (c == '+' || c == '-') && i > 0) {
                return true;
            }
        }
        return false;
    }

    /** 结果（纯数据 + 文本；等行数 ⇒ 行号映射不受影响）。 */
    public record Result(String text, List<TranslateDiagnostic> diagnostics,
            int patchedSample, int patchedMultiplier, List<String> hitLines) {

        public Result {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            hitLines = hitLines == null ? List.of() : List.copyOf(hitLines);
        }

        /** 总命中数（两侧之和）。 */
        public int patched() {
            return patchedSample + patchedMultiplier;
        }
    }

    /**
     * 等行数改写。
     *
     * @param stage 只有 FRAGMENT 会被改（乘法链在片元里）
     * @param text  7.75 段输出
     */
    public static Result apply(ShaderStage stage, String text) {
        List<TranslateDiagnostic> diagnostics = new ArrayList<>();
        if (!anyEnabled() || text == null || stage != ShaderStage.FRAGMENT) {
            return new Result(text, diagnostics, 0, 0, List.of());
        }
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length() + 64);
        int sampleHits = 0;
        int multiplierHits = 0;
        List<String> hitLines = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher matcher = MULTIPLY_ASSIGN.matcher(line);
            String rewritten = line;
            if (matcher.matches()) {
                String indent = matcher.group(1);
                // 🔖 类型前缀（声明形态）与被赋值名字分开保留：改写时两者都要原样写回，
                //   少写类型就把「声明」变成了「赋值」，语义从「定义」变成「改写已有变量」。
                String typePrefix = matcher.group(2);
                String lhs = matcher.group(3);
                String left = matcher.group(4).strip();
                String right = matcher.group(5).strip();
                String tail = matcher.group(6);
                boolean hasSample = SAMPLE_CALL.matcher(left).find();
                // 🔖🔖 **顶层加减法一律不改写**：那会把「换因子」变成「改表达式结构」，
                //   产出的画面差异不再只归因于被换掉的那个因子 ⇒ **假证据**。
                //   括号内的加减（如 texture(a - b, c) 里的减号）不算。
                boolean additive = hasTopLevelAdditive(left) || hasTopLevelAdditive(right);
                boolean changed = false;
                String newLeft = left;
                String newRight = right;
                if (!additive && hasSample) {
                    // 🔖🔖🔖 **两个开关都只认「左值是采样调用」的那一行**（h45 实测踩到）：
                    //   首版让「乘子探针」命中**任何**乘法赋值 ⇒ 实测在 BSL 地形片元上
                    //   命中 **124 处**，其中绝大多数是 `float f = a * b;` 这类标量运算
                    //   ⇒ 改写后变成 `float f = a * vec4(1,1,1,1);` = **类型错误**
                    //   ⇒ 片元编译直接抛 ShaderCompileException、地形契约掉回原版。
                    //   🔖 是「命中数自报」把它现形了：预期 1 处、实测 124 处。
                    //   ⇒ 两侧探针都锚在**同一条** albedo 乘法链上，才是真正的单变量。
                    if (forceSample) {
                        newLeft = PROBE_SAMPLE;
                        sampleHits++;
                        changed = true;
                    }
                    if (forceMultiplier) {
                        newRight = PROBE_MULTIPLIER;
                        multiplierHits++;
                        changed = true;
                    }
                }
                if (changed) {
                    rewritten = indent + typePrefix + lhs + " = " + newLeft + " * " + newRight + tail;
                    // 🔖 自报命中的**那一行原文**：这是「改的到底是哪一句」的唯一直接证据。
                    //   只报「已改 N 处」不够 —— N>0 也可能改在了无关的乘法上。
                    hitLines.add("第 " + (i + 1) + " 行: " + line.strip());
                }
            }
            out.append(rewritten);
            if (i + 1 < lines.length) {
                out.append('\n');
            }
        }
        String text2 = out.toString();
        if (sampleHits + multiplierHits == 0) {
            // 🔖🔖 自报「没命中」并明说它意味着什么：开关生效与否，本行是唯一判据。
            diagnostics.add(TranslateDiagnostic.warn(
                    "采样因子探针**已开启但一处都没命中** ⇒ 本次画面若与默认一致，"
                            + "说明**开关没生效**（不是结论不成立）：本包的 albedo 乘法链"
                            + "形态与本段识别的「右值恰含一个顶层 *」不同。"
                            + "⚠️ 判据只认这一行，不认「画面没变」",
                    null, 0));
        } else {
            StringBuilder message = new StringBuilder("采样因子探针命中：左侧(采样) ")
                    .append(sampleHits).append(" 处、右侧(乘子) ").append(multiplierHits)
                    .append(" 处");
            if (forceSample && forceMultiplier) {
                message.append("。🔴 **两侧同时开启** ⇒ 乘法链两侧都被换掉，"
                        + "**不能**据此分出是哪一侧为 0（要分开判定必须只开一侧）");
            }
            if (forceSample && sampleHits > 0) {
                message.append("。左侧已强制为 ").append(PROBE_SAMPLE)
                        .append("：若画面**变亮** ⇒ 原采样结果为 0（输入侧：图集/坐标/采样器）；"
                                + "若仍全黑 ⇒ 采样不是原因");
            }
            if (forceMultiplier && multiplierHits > 0) {
                message.append("。右侧已强制为 ").append(PROBE_MULTIPLIER)
                        .append("：若画面**变亮** ⇒ 原乘子为 0（顶点供值侧，"
                                + "注意 mrt.terrainColorProbe 是顶点侧、只排除「供值错」）；"
                                + "若仍全黑 ⇒ 乘子不是原因");
            }
            diagnostics.add(TranslateDiagnostic.warn(message.toString(), null, 0));
            for (String hit : hitLines) {
                diagnostics.add(TranslateDiagnostic.info("采样因子探针命中: " + hit, null, 0));
            }
        }
        return new Result(text2, diagnostics, sampleHits, multiplierHits, List.copyOf(hitLines));
    }
}