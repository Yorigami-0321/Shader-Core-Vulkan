package dev.vkdisp.config;
/**
 * 【参考调研】**单选项强制覆盖**（取证用的单变量 A/B 入口）
 * 0. 合规核对（第 0 步闸门）：
 *    参考对象 = ① 本仓库 {@code docs/13-GAP-REGISTRY.md} GAP-008 条目里 h42 §4.2 登记的
 *    「两组不是单变量」（门控改写 9 个选项并改变派生程序形状）与 §五 登记的下一步
 *    「做真正<b>单变量</b>的 {@code PARALLAX} A/B（需单独关它而不动其余 8 项）」；
 *    ② 本仓库自有的 {@code PackOptions#set}（本类只编排，不改其归一化语义）与
 *    {@code PackCapabilityGate}（同一族「只在内存里改值 + 显式诊断 + 不写用户文件」的处置形态）；
 *    ③ {@code docs/07-CONSTRAINTS.md} T11（降级必须可见）/ X9（不猜）/ X27（不许无谓砍包特性）。
 *    全部为仓库内自有文档事实与自有代码，不受版权保护。
 *    → 能否并入本项目（MIT）：可以（本文件为独立编写的纯 Java 逻辑类）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：无（原版没有「按名字强制某个包选项值」这个概念 —— 它没有包加载器）。
 * 2. 备选：
 *    <ul>
 *      <li>① 每次取证去改包的 {@code optionsv2.txt} / {@code shaders.properties} ——
 *          <b>否决</b>：① 会<b>写用户的文件</b>（能力门控那条裁决已明确「不写用户配置」，
 *          理由是 Iris 自己从不因能力缺失改写用户配置、无先例）；② 更要紧的是它<b>改的是包</b>，
 *          两次 A/B 之间要靠「记得改回去」来保证不串味 —— h27 就实测过这种串味
 *          （前一轮的值被孤儿客户端回写覆盖 ⇒ B 臂根本没测到想测的东西，而日志看起来完全正常）。
 *          ③ 还得处理「包重启后又被覆盖回去」。</li>
 *      <li>② 再加一个「只关 PARALLAX」的专用开关 —— <b>否决</b>：那等于把
 *          {@code PARALLAX} 这个名字<b>硬编码</b>进产品配置。能力门控那条已经立过规矩：
 *          硬编码选项名会砍掉其它包的可用特性（Complementary 同样有视差却零外部依赖，X27）。
 *          ⇒ 必须是<b>按名字任意指定</b>的通用机制，具体关哪个由取证者自己填。</li>
 *      <li>③ 复用能力门控（{@code PackCapabilityGate}）—— <b>否决</b>：门控的判据是
 *          「包<b>自己声明</b>依赖了缺失能力」，它的产物必然是一个<b>闭包</b>（BSL 上是 9 项）。
 *          而 h42 要的恰恰是<b>只动一项</b>。⇒ 两者是不同工具，本类不合并进门控。</li>
 *    </ul>
 * 3. 我们的差异点：
 *    <ul>
 *      <li>🔖 <b>通用按名覆盖，不硬编码任何包特性名</b>：语法 {@code NAME=value}，
 *          多条用 {@code ;} 分隔。取值者自己决定改哪个 —— 这才可能做单变量实验。</li>
 *      <li>🔖 <b>只改内存，一个字节都不写用户文件</b>（与能力门控同一条裁决）。</li>
 *      <li>🔖 <b>解析失败与「包里没这个选项」都必须吵出来</b>：
 *          静默空转是本项目最该消灭的失败形态（h33 死开关那一次：配置写对了、日志照打、
 *          就是不生效、毫无异常）。本类对这两类各给一个独立诊断码。</li>
 *      <li>🔖 <b>「值本来就是那样」不算覆盖</b>：不记进结果表，
 *          否则日志会说「我们改了它」而真相是「它本来就是这个值」——
 *          下次取证会据此误判归因（与 {@code PackCapabilityGate} 同一处理）。</li>
 *    </ul>
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 冷路径（切包 / 资源重载时跑一次；条目数与用户填写量成正比，量级为个位数）。
 */
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 按名字强制覆盖包选项的值（<b>只改内存</b>）—— 取证用的单变量 A/B 入口。
 *
 * <p><b>它解决什么</b>（h42 §4.2 / §五 登记的下一步）：能力门控在 BSL 上一次关掉
 * <b>9 个</b>选项，并把派生程序形状从 {@code outputs 8 → 1}、{@code samplers 7 → 5}、
 * {@code varyings 15 → 9} 一起改掉 ⇒ 两组之间<b>不是单变量</b>，
 * 于是既不能证明「视差无关」，也不能证明「是另外 8 项导致的」。
 * ⇒ 要做单变量实验，就必须有一个「<b>只改一个指定名字</b>」的入口。
 *
 * <p><b>与能力门控的分工</b>（两者都只改内存、都不写用户文件）：
 * <table border="1">
 *   <caption>两个工具的区别</caption>
 *   <tr><th></th><th>能力门控 {@code PackCapabilityGate}</th><th>本类</th></tr>
 *   <tr><td>判据</td><td>包<b>自己声明</b>依赖缺失能力</td><td>用户/取证者<b>按名字</b>指定</td></tr>
 *   <tr><td>改几项</td><td>一个<b>闭包</b>（BSL 上 9 项）</td><td><b>恰好指定的那些</b></td></tr>
 *   <tr><td>用途</td><td>产品止血（X27：按能力砍特性）</td><td>单变量取证</td></tr>
 * </table>
 *
 * <p><b>语法</b>：{@code NAME=value}，多条用 {@code ;} 分隔，例如
 * {@code "PARALLAX=false"} 或 {@code "PARALLAX=false;SHARPEN=0"}。
 * 空串 / 空白 = 不覆盖（默认态）。
 */
public final class PackOptionOverride {

    /** 语法错误（条目里没有 {@code =}）的诊断码。 */
    public static final String MALFORMED_ENTRY = "OPTION_OVERRIDE_MALFORMED";

    /** 空名字（{@code "=value"}）的诊断码。 */
    public static final String EMPTY_NAME = "OPTION_OVERRIDE_EMPTY_NAME";

    /** 空值的诊断码。 */
    public static final String EMPTY_VALUE = "OPTION_OVERRIDE_EMPTY_VALUE";

    /** 包里没有这个选项的诊断码。 */
    public static final String UNKNOWN_OPTION = "OPTION_OVERRIDE_UNKNOWN_OPTION";

    /** 赋值被拒（值越界 / 类型不符）的诊断码。 */
    public static final String SET_REJECTED = "OPTION_OVERRIDE_SET_REJECTED";

    /**
     * 值被<b>钳制</b>的诊断码（生效的不是用户写的那个值）。
     *
     * <p>🔖 <b>为什么必须与 {@link #SET_REJECTED} 分开</b>：钳制是「生效了，但不是你要的值」，
     * 拒绝是「没生效」。两者在日志上若都写成「已覆盖」，下一次取证会据此误判归因
     * （写了 {@code SHARPEN=99} 实际拿到 3，却以为覆盖精确生效）。
     */
    public static final String VALUE_CLAMPED = "OPTION_OVERRIDE_VALUE_CLAMPED";

    /** 值本来就等于请求值的诊断码（<b>不是</b>失败，是「不必覆盖」）。 */
    public static final String ALREADY_EQUAL = "OPTION_OVERRIDE_ALREADY_EQUAL";

    /** 多条之间的分隔符。 */
    public static final char SEPARATOR = ';';

    /**
     * 一次覆盖的结果。
     *
     * @param applied 真正被改动的选项（名字 → 生效值；顺序 = 请求顺序）
     * @param unchanged 请求覆盖但值本就相同的选项名
     * @param diagnostics 全部诊断（不可变）
     */
    public record Result(
            Map<String, String> applied,
            List<String> unchanged,
            List<OptionDiagnostic> diagnostics) {

        /** 归一构造：全部冻结。 */
        public Result {
            applied = applied == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(applied));
            unchanged = unchanged == null ? List.of() : List.copyOf(unchanged);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }

        /** 是否什么都没改（<b>不是</b>「失败」—— 可能本来就一致，也可能是解析错误）。 */
        public boolean isEmpty() {
            return this.applied.isEmpty();
        }

        /** 是否出现过指定诊断码（单测断言用）。 */
        public boolean hasDiagnostic(String code) {
            for (OptionDiagnostic diagnostic : this.diagnostics) {
                if (diagnostic.code().equals(code)) {
                    return true;
                }
            }
            return false;
        }
    }

    private PackOptionOverride() {
    }

    /**
     * 解析覆盖串（不施加）—— 供日志与单测核对「我写的这串被理解成什么」。
     *
     * @param spec 覆盖串（null / 空白 → 空表）
     * @return 名字 → 值（保持书写顺序；<b>后者覆盖前者</b>）
     * @throws IllegalArgumentException 条目形如 {@code "=v"}（空名字）或 {@code "NAME="}（空值）
     */
    public static Map<String, String> parse(String spec) {
        Map<String, String> parsed = new LinkedHashMap<>();
        if (spec == null) {
            return parsed;
        }
        for (String rawEntry : spec.split(String.valueOf(SEPARATOR), -1)) {
            String entry = rawEntry.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int eq = entry.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException(
                        "vkdisp: 覆盖条目 '" + entry + "' 缺少 '='（语法：NAME=value）");
            }
            String name = entry.substring(0, eq).trim();
            String value = entry.substring(eq + 1).trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException(
                        "vkdisp: 覆盖条目 '" + entry + "' 的选项名为空");
            }
            if (value.isEmpty()) {
                throw new IllegalArgumentException(
                        "vkdisp: 覆盖条目 '" + entry + "' 的值为空");
            }
            // 后者覆盖前者：同名重复时保留最后一次，并在诊断里说出来（见 apply）。
            parsed.put(name, value);
        }
        return parsed;
    }

    /**
     * 施加覆盖（<b>只改内存</b>；不写 {@code PackOptionStore} 一个字节）。
     *
     * @param options 目标包的选项容器（就地修改）
     * @param spec    覆盖串（null / 空白 = 无覆盖，产生一条 INFO 而不是静默）
     * @return 结果（真正被改的项 + 解析/施加诊断）
     */
    public static Result apply(PackOptions options, String spec) {
        Objects.requireNonNull(options, "options");
        List<OptionDiagnostic> diagnostics = new ArrayList<>();
        Map<String, String> applied = new LinkedHashMap<>();
        List<String> unchanged = new ArrayList<>();

        if (spec == null || spec.isBlank()) {
            diagnostics.add(OptionDiagnostic.info("OPTION_OVERRIDE_OFF",
                    "未指定任何选项覆盖（空串）⇒ 包选项保持包内原值。"
                            + "🔖 本开关用于**单变量**取证：能力门控一次会改一整个闭包（BSL 上 9 项），"
                            + "两臂之间不是单变量（见 GAP-008 / evidence/h42 §4.2）"));
            return new Result(applied, unchanged, diagnostics);
        }

        Map<String, String> parsed;
        try {
            parsed = parse(spec);
        } catch (IllegalArgumentException e) {
            diagnostics.add(OptionDiagnostic.error(MALFORMED_ENTRY,
                    "覆盖串无法解析：" + e.getMessage()
                            + " ⇒ **本帧一条都没改**（不是「部分生效」，T11 不静默）"));
            return new Result(applied, unchanged, diagnostics);
        }

        for (Map.Entry<String, String> entry : parsed.entrySet()) {
            applyOne(options, entry.getKey(), entry.getValue(), applied, unchanged, diagnostics);
        }

        if (!applied.isEmpty()) {
            diagnostics.add(OptionDiagnostic.info("OPTION_OVERRIDE_SUMMARY",
                    "已在内存中强制覆盖 " + applied.size() + " 个选项：" + applied
                            + "（🔖 未写任何用户配置文件；这是**单变量**取证入口）"));
        }
        return new Result(applied, unchanged, diagnostics);
    }

    /**
     * 施加<b>一条</b>覆盖（{@link #apply} 的循环体）。
     *
     * <p>🔖 <b>为什么拆出来</b>：本类第一版把整个循环内联在 {@code apply} 里，
     * 一次 {@code set} 的四种结局（未知选项 / 被拒 / 被钳制 / 本就相同）挤在一起，
     * 方法长度超过 {@code MethodLengthRatchetTest} 的阈值 —— 那个棘轮本轮<b>当场</b>把
     * 本类算成新增超长方法（基线 21 → 22）。拆开后每种结局各自独立可读。
     *
     * <p>🔖 <b>四种结局必须分别可诊断</b>，因为它们在日志上长得不一样但很容易被混为一谈：
     * <ul>
     *   <li>未知选项 ⇒ 什么都没发生（空转，最容易被误读成「生效了但画面没变」）；</li>
     *   <li>被拒 ⇒ 保留原值；</li>
     *   <li>被钳制 ⇒ <b>生效了，但不是用户写的那个值</b>；</li>
     *   <li>本就相同 ⇒ 我们什么都没做。</li>
     * </ul>
     */
    private static void applyOne(PackOptions options, String name, String value,
            Map<String, String> applied, List<String> unchanged,
            List<OptionDiagnostic> diagnostics) {
        if (!options.contains(name)) {
            diagnostics.add(OptionDiagnostic.warn(UNKNOWN_OPTION,
                    "覆盖目标选项 '" + name + "' 不在本包的选项定义中 ⇒ 未施加。"
                            + "⚠️ 空转看起来与「施加了但画面没变」完全一样，"
                            + "所以这条必须吵出来（h33 死开关同族）"));
            return;
        }
        PackOptions.SetOutcome outcome = options.set(name, value);
        if (!outcome.applied()) {
            diagnostics.add(OptionDiagnostic.warn(SET_REJECTED,
                    "把 '" + name + "' 覆盖为 '" + value + "' 被拒绝（" + outcome.status()
                            + "）⇒ 保持原值 '" + outcome.previousValue() + "'"));
            return;
        }
        if (outcome.status() == PackOptions.SetStatus.CLAMPED) {
            // 🔖🔖 **钳制必须在这里再吵一次**，不能只依赖 PackOptions 自己的诊断。
            //   理由：那批诊断进的是 PackOptions 内部的 sink，而本类返回的
            //   {@code Result#diagnostics} 是**另一份**列表 —— 调用方若只读结果里的诊断
            //   （PackTerrainSource 就是这么用的），就会漏掉「你写的值不是你要的值」。
            //   取证者写 SHARPEN=99 实际生效 3 而日志只说「已覆盖」⇒ 归因就错了。
            diagnostics.add(OptionDiagnostic.warn(VALUE_CLAMPED,
                    "'" + name + "' 覆盖为 '" + value + "' 时被**钳制**到 '"
                            + outcome.appliedValue() + "'（" + outcome.status()
                            + "）⇒ 生效的不是你写的那个值"));
        }
        if (!outcome.changed()) {
            // 🔖 本来就是这个值 ⇒ 不记进 applied：否则日志会说「我们改了它」
            //   而真相是「它本来就是这样」，下一次取证会据此误判归因。
            unchanged.add(name);
            diagnostics.add(OptionDiagnostic.info(ALREADY_EQUAL,
                    "选项 '" + name + "' 本来就是 '" + value + "' ⇒ 未改动"));
            return;
        }
        applied.put(name, outcome.appliedValue());
    }

/**
     * 施加覆盖并把诊断转发到给定出口（与 {@code PackCapabilityGate} 同款接线形态）。
     *
 * @param sink 诊断输出口（非空）
     */
    public static Result apply(PackOptions options, String spec, OptionDiagnosticSink sink) {
        Objects.requireNonNull(sink, "sink");
        Result result = apply(options, spec);
        for (OptionDiagnostic diagnostic : result.diagnostics()) {
            sink.accept(diagnostic);
        }
        return result;
    }
}