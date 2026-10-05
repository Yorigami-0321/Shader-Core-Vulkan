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

    /**
     * 🔬 h45 §七① 的「坐标数值」探针：把命中行的整个右值换成 {@code vec4(<采样坐标>, 0.0, 1.0)}。
     * <p>🔴 <b>本形态的判据洞（h46 自查发现）</b>：BSL 的 ADVANCED_MATERIALS 路径在采样赋值
     * <b>之后</b>还有 {@code GetLighting(albedo…)} 一类的乘法 —— 换第一处赋值测的仍是
     * 「采样×后续衰减」的合成品，不是坐标本身。⇒ 保留本形态（它锚在乘法链上，与左右档同源），
     * 但判据改由 <b>输出档</b>（{@link #setForceCoordOutFinal}）承担。
     */
    private static volatile boolean forceCoordOut;

    /**
     * 🔬 h46 修正档：**输出直写坐标** —— 把 {@code gl_FragData[0] = albedo;} /
     * {@code vkdispFragOut0 = albedo;} 的右值整体换成 {@code vec4(<采样坐标>,0,1)}，
     * 跳过其后所有下游衰减 ⇒ 读数 = 纯坐标值。
     */
    private static volatile boolean forceCoordOutFinal;

    /**
     * 🔬 h45 §七② 的「显式 LOD0」探针 / h46 转正的**产品修复路径**：把命中行里的两参数 {@code texture(s, c)} 改成
     * {@code textureLod(s, c, 0.0)} —— 其余一切不动。
     * <p>判据：改后非零 ⇒ 隐式导数在本链上选到了坏 mip（LOD 侧）；仍为零 ⇒ LOD 排除。
     */
    private static volatile boolean forceLodZero;

    /** LOD0 档的命中行原文（供调用方做「只改了一行」的守卫断言）。 */
    private static volatile java.util.List<String> lastLodHitLines = List.of();

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

    /** 开关「坐标数值输出」（整行右值换成采样坐标）。 */
    public static void setForceCoordOut(boolean value) {
        forceCoordOut = value;
    }

    /** 开关「输出直写坐标」（最终输出右值整体换成采样坐标，跳过下游衰减）。 */
    public static void setForceCoordOutFinal(boolean value) {
        forceCoordOutFinal = value;
    }

    /** 是否开启了坐标数值输出探针（供调用点自报配置）。 */
    public static boolean forceCoordOutEnabled() {
        return forceCoordOut;
    }

    /** 是否开启了输出直写坐标探针。 */
    public static boolean forceCoordOutFinalEnabled() {
        return forceCoordOutFinal;
    }

    /** 开关「显式 LOD0」（采样调用换 textureLod(…, 0.0)）。 */
    public static void setForceLodZero(boolean value) {
        forceLodZero = value;
    }

    /** 是否开启了显式 LOD0 探针。 */
    public static boolean forceLodZeroEnabled() {
        return forceLodZero;
    }

    /** 当前是否开启了任一探针。 */
    public static boolean anyEnabled() {
        return forceSample || forceMultiplier || forceCoordOut || forceLodZero || forceCoordOutFinal;
    }

    /**
     * 最终输出对 albedo 的裸赋值（输出直写档的锚点）：
     * {@code gl_FragData[0] = albedo;} / {@code vkdispFragOut0 = albedo;}。
     */
    private static final Pattern OUTPUT0_ASSIGN = Pattern.compile(
            "^(\\s*)(gl_FragData\\[0\\]|vkdispFragOut0)(\\s*=\\s*)([A-Za-z_]\\w*)\\s*(;.*)$");

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

    /**
     * 结果（纯数据 + 文本；等行数 ⇒ 行号映射不受影响）。
     *
     * <p>🔖 {@code coordOutHits} 是「整行右值被换成坐标输出」的计数 —— 它与左右探针
     * <b>互斥</b>（同开时坐标档优先并 WARN），所以它不进 {@link #patched()} 的两侧之和。
     */
    public record Result(String text, List<TranslateDiagnostic> diagnostics,
            int patchedSample, int patchedMultiplier, int patchedCoordOut, int patchedLodZero,
            int patchedCoordOutFinal, List<String> hitLines) {

        public Result {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            hitLines = hitLines == null ? List.of() : List.copyOf(hitLines);
        }

        /** 总命中数（左右两侧之和）。 */
        public int patched() {
            return patchedSample + patchedMultiplier;
        }

        /** 任一探针的命中总数（自报行用它，漏了任何一档就会出现「开了却没数字」）。 */
        public int patchedAny() {
            return patchedSample + patchedMultiplier + patchedCoordOut + patchedLodZero
                    + patchedCoordOutFinal;
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
            return new Result(text, diagnostics, 0, 0, 0, 0, 0, List.of());
        }
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length() + 64);
        int sampleHits = 0;
        int multiplierHits = 0;
        int coordOutHits = 0;
        int coordFinalHits = 0;
        int lodZeroHits = 0;
        boolean coordOutCollision = false;
        // 输出直写档的坐标名：取全文第一个两参采样的第二实参（通常就是 texCoord）。
        final String coordName = firstSampleCoord(text);
        if (forceCoordOutFinal && coordName == null) {
            diagnostics.add(TranslateDiagnostic.warn(
                    "输出直写坐标档**已开启但没找到任何两参采样调用** ⇒ 无法取坐标名，"
                            + "本档未生效（开关没生效 ≠ 结论不成立，X45）", null, 0));
        }
        List<String> hitLines = new ArrayList<>();
        lastLodHitLines = List.of();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String rewritten = line;
            boolean changed = false;
            if (forceCoordOutFinal && coordName != null) {
                Matcher outMatch = OUTPUT0_ASSIGN.matcher(line);
                if (outMatch.matches()) {
                    rewritten = outMatch.group(1) + outMatch.group(2) + outMatch.group(3)
                            + "vec4(" + coordName + ", 0.0, 1.0)" + outMatch.group(5);
                    coordFinalHits++;
                    changed = true;
                    hitLines.add("第 " + (i + 1) + " 行: " + line.strip());
                    if (forceSample || forceMultiplier || forceCoordOut) {
                        coordOutCollision = true;
                    }
                }
            }
            if (!changed) {
                Matcher matcher = MULTIPLY_ASSIGN.matcher(line);
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
                    boolean changedLine = false;
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
                        if (forceCoordOut) {
                            // 坐标档换掉**整个右值** ⇒ 与左右两侧探针在同一条线上互斥。
                            String coord = topLevelSecondArg(left);
                            if (coord != null) {
                                rewritten = indent + typePrefix + lhs + " = vec4(" + coord
                                        + ", 0.0, 1.0);";
                                coordOutHits++;
                                changedLine = true;
                                if (forceSample || forceMultiplier) {
                                    coordOutCollision = true;
                                }
                            }
                        } else {
                            if (forceLodZero) {
                                String lodded = forceLodZeroOnSampleCall(left);
                                if (!lodded.equals(left)) {
                                    newLeft = lodded;
                                    lodZeroHits++;
                                    changedLine = true;
                                }
                            }
                            if (forceSample) {
                                newLeft = PROBE_SAMPLE;
                                sampleHits++;
                                changedLine = true;
                            }
                            if (forceMultiplier) {
                                newRight = PROBE_MULTIPLIER;
                                multiplierHits++;
                                changedLine = true;
                            }
                        }
                    }
                    if (changedLine && !forceCoordOut) {
                        rewritten = indent + typePrefix + lhs + " = " + newLeft + " * " + newRight + tail;
                    }
                    if (changedLine) {
                        // 🔖 自报命中的**那一行原文**：这是「改的到底是哪一句」的唯一直接证据。
                        //   只报「已改 N 处」不够 —— N>0 也可能改在了无关的乘法上。
                        hitLines.add("第 " + (i + 1) + " 行: " + line.strip());
                    }
                    changed = changedLine;
                }
            }
            out.append(rewritten);
            if (i + 1 < lines.length) {
                out.append('\n');
            }
        }
        String text2 = out.toString();
        if (coordOutHits + sampleHits + multiplierHits + lodZeroHits + coordFinalHits == 0) {
            // 🔖🔖 自报「没命中」并明说它意味着什么：开关生效与否，本行是唯一判据。
            diagnostics.add(TranslateDiagnostic.warn(
                    "采样探针**已开启但一处都没命中** ⇒ 本次画面若与默认一致，"
                            + "说明**开关没生效**（不是结论不成立）：本包的 albedo 乘法链"
                            + "形态与本段识别的「右值恰含一个顶层 *」不同。"
                            + "⚠️ 判据只认这一行，不认「画面没变」",
                    null, 0));
        } else {
            StringBuilder message = new StringBuilder("采样因子探针命中：左侧(采样) ")
                    .append(sampleHits).append(" 处、右侧(乘子) ").append(multiplierHits)
                    .append(" 处、坐标输出 ").append(coordOutHits)
                    .append(" 处、显式LOD0 ").append(lodZeroHits)
                    .append(" 处、输出直写坐标 ").append(coordFinalHits).append(" 处");
            if (forceSample && forceMultiplier) {
                message.append("。🔴 **两侧同时开启** ⇒ 乘法链两侧都被换掉，"
                        + "**不能**据此分出是哪一侧为 0（要分开判定必须只开一侧）");
            }
            if (coordOutCollision) {
                message.append("。🔴 坐标输出档与左右探针同时开启 ⇒ 坐标档优先，"
                        + "左右改写在那条线上**未发生**；该臂只剩坐标证据");
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
            if (forceCoordOut && coordOutHits > 0) {
                message.append("。坐标输出档：colortex0 现在是 vec4(texCoord,0,1)×255 —— "
                        + "像素数字直接给出采样落点（≈0 ⇒ 坐标链路坏；图集合理值 ⇒ 采样器侧）");
            }
            if (forceLodZero && lodZeroHits > 0) {
                message.append("。显式LOD0档：texture(…) → textureLod(…, 0.0) —— "
                        + "非零 ⇒ 隐式导数选了坏 mip；仍零 ⇒ LOD 因素排除");
            }
            if (forceCoordOutFinal && coordFinalHits > 0) {
                message.append("。输出直写档：gl_FragData[0]/vkdispFragOut0 的右值整体换成 vec4(")
                        .append(coordName).append(",0,1) —— **跳过全部下游衰减**，"
                                + "读数=纯坐标（≈0 ⇒ 坐标链路坏；图集合理值 ⇒ 排除坐标侧）");
            }
            diagnostics.add(TranslateDiagnostic.warn(message.toString(), null, 0));
            for (String hit : hitLines) {
                diagnostics.add(TranslateDiagnostic.info("采样因子探针命中: " + hit, null, 0));
            }
            lastLodHitLines = List.copyOf(hitLines);
        }
        return new Result(text2, diagnostics, sampleHits, multiplierHits, coordOutHits,
                lodZeroHits, coordFinalHits, List.copyOf(hitLines));
    }

    /** 全文第一个**两参数**采样调用的第二个顶层实参（输出直写档取坐标名用）。 */
    private static String firstSampleCoord(String text) {
        // 🔴 只认**乘法链形态**行上的采样坐标；多个候选时优先 OF 标准名 texCoord，
        //   并把**全部候选**随自报打出（选错可见）。第一版按文件顺序取第一个，
        //   实测抓到 BSL 阴影行的 shadowPosXY ⇒ 整臂作废（h46 F2）——「命中一处」
        //   ≠「命中的是该测的那处」，这次由**顺序**引起，与 124 处误命中同一课。
        List<String> candidates = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            Matcher m = MULTIPLY_ASSIGN.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String left = m.group(4).strip();
            String right = m.group(5).strip();
            if (hasTopLevelAdditive(left) || hasTopLevelAdditive(right)) {
                continue;
            }
            if (SAMPLE_CALL.matcher(left).find()) {
                String coord = topLevelSecondArg(left);
                if (coord != null && !candidates.contains(coord)) {
                    candidates.add(coord);
                }
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.contains("texCoord")) {
            return "texCoord";
        }
        return candidates.get(0);
    }

    /** 上一次 apply() 里 LOD0 档命中的行（空 = 没命中；调用方据此自证「只改了一行」）。 */
    public static List<String> lastLodHitLines() {
        return lastLodHitLines;
    }

    /**
     * 取 {@code left} 中第一个采样调用的**顶层第二个实参**（采样坐标表达式）。
     *
     * <p>🔖 顶层 = 括号深度 1 处按逗号切分；{@code textureGrad(t, c, g1, g2)} 取 {@code c}。
     * 实参数不足 2 个时返回 null（宁可不改，不误改 —— 同乘法链锚点的口径）。
     */
    private static String topLevelSecondArg(String expression) {
        Matcher m = SAMPLE_CALL.matcher(expression);
        if (!m.find()) {
            return null;
        }
        int open = expression.indexOf('(', m.start());
        int depth = 0;
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = open + 1; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '(') {
                depth++;
                current.append(c);
            } else if (c == ')') {
                if (depth == 0) {
                    // 🔖 收尾括号处**最后一段实参还没入列** —— 先冲刷再判定，
                    //   否则两参调用永远只切出 1 个实参（第一版就是这样，测试逼出来的）。
                    if (!current.isEmpty()) {
                        args.add(current.toString().strip());
                    }
                    break;
                }
                depth--;
                if (depth == 0) {
                    args.add(current.toString().strip());
                    current.setLength(0);
                    continue;
                }
                current.append(c);
            } else if (c == ',' && depth == 0) {
                args.add(current.toString().strip());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        return args.size() >= 2 ? args.get(1) : null;
    }

    /**
     * 把 {@code expression} 里**每个**恰好两参数的 {@code texture(}/{@code texture2D(} 调用
     * 换成 {@code textureLod(s, c, 0.0)}；其余调用原样。
     */
    private static String forceLodZeroOnSampleCall(String expression) {
        Matcher m = Pattern.compile("\\b(texture|texture2D)\\s*\\(").matcher(expression);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            int open = expression.indexOf('(', m.start());
            String[] split = splitTopLevelArgs(expression, open);
            if (split == null || split.length != 2) {
                continue;
            }
            sb.append(expression, last, m.start())
                    .append(m.group(1)).append("Lod(")
                    .append(split[0]).append(", ").append(split[1]).append(", 0.0)");
            last = matchingClose(expression, open) + 1;
        }
        sb.append(expression, last, expression.length());
        return sb.toString();
    }

    /** 顶层切分实参表；括号不配平返回 null。 */
    private static String[] splitTopLevelArgs(String expression, int openParen) {
        int depth = 0;
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = openParen + 1; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '(') {
                depth++;
                current.append(c);
            } else if (c == ')') {
                if (depth == 0) {
                    args.add(current.toString().strip());
                    return args.toArray(new String[0]);
                }
                depth--;
                current.append(c);
            } else if (c == ',' && depth == 0) {
                args.add(current.toString().strip());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        return null;
    }

    /** 从 {@code openParen} 起的配对 ')' 下标（假定调用语法正常）。 */
    private static int matchingClose(String expression, int openParen) {
        int depth = 0;
        for (int i = openParen; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return expression.length() - 1;
    }
}