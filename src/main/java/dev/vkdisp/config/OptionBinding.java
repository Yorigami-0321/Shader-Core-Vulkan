package dev.vkdisp.config;
/**
 * 【参考调研】F 线选项模型 / 选项值 → #define 表与 uniform 值 OptionBinding（04-SPEC §3.5）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §3.1（Option = 包自定义选项；GLSL 侧选项来源为
 *    {#define NAME 值 // [v1 v2 v3]} 与 {const int NAME = 值; // [v1 v2 v3]}）与 §3.5（OptionBinding =
 *    选项 → 着色器 #define / uniform 的绑定）；
 *    ② docs/18-PARALLEL.md §4 F 线（完成标准："#define 表生成结果可对比"）与 §7.3（并行线证据规范）；
 *    ③ docs/08-TESTING.md §4（"const int X = 0; // [0 1 2]" 型选项常量能被识别）；
 *    ④ F2 已冻结的 dev.vkdisp.pack.Option / OptionType（直接消费，不改）。
 *    许可证：本仓库自有文档与自有冻结契约（本项目 MIT）→ 可直接消费；
 *    OptiFine（无 LICENSE = ARR）只取不受版权保护的格式事实（#define 是宏名 + 替换文本这一语言事实）；
 *    Iris（LGPL-3.0）同口径；参考模组零接触 —— 按 07-CONSTRAINTS L12「判不过就换参考，不再读它的代码」。
 *    → 能否并入本项目（MIT）：本文件为独立实现，可并入
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：C 预处理器的语言事实 —— {@code #define NAME 替换文本}（有替换文本）与
 *    {@code #define NAME}（空替换文本）、{@code #undef NAME}；布尔选项在 OF 语境里既可能被写成
 *    "#ifdef NAME"（要求真值时只定义、假值时未定义），也可能被写成 "#if NAME"（要求真值是 1）。
 *    这两种写法互斥，因此本类把风格做成显式枚举 DefineStyle，默认 LITERAL，各自都有快照单测。
 * 2. 备选：只支持一种风格、硬编码进代码 —— 否决（会在没有 GPU 证据的情况下把一处未经验证的假设
 *    固化进契约；07-CONSTRAINTS X9 禁止猜值，故把它做成显式开关 + 文档化差异点，等真实包验证后再定默认）。
 *    备选：让 OptionBinding 直接产出最终 GLSL 文本 —— 否决（#include / 条件编译是 C 线的活；
 *    F 线只产"宏名 → 替换文本"这张表，由 C/D 线决定怎么拼接）。
 * 3. 我们的差异点：① 主产物是 Map<宏名, 替换文本>（空串 = 只有宏名没有替换文本），
 *    另有 undefines 集合承载 "#undef"；② 绑定是**一次性快照**：改选项值后要重建 binding
 *    （冷路径清晰优先，见 18-PARALLEL §7.7；不做增量 / 不做缓存）；
 *    ③ 不是所有选项都能变成 #define —— 宏名不是 GLSL 标识符、自由文本含空白、值超出 32 位整数范围等情况
 *    一律"跳过 + 显式 WARN"（T11），绝不产出会编译失败的垃圾文本；④ TEXT 选项只接受单个 GLSL 标识符
 *    （自由文本怎么进 GLSL 属于包语义，留给后续按真实包决定，不在本类里猜）。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制，只消费本项目 F2 冻结契约与自有文档事实。
 * 5. 性能基线：❄️ 冷路径（切包 / 改选项时构造一次，之后只读），不做任何性能优化（18-PARALLEL §7.7）。
 */
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import dev.vkdisp.pack.Option;
import dev.vkdisp.pack.OptionType;

/**
 * 选项值 → {@code #define} 表与 uniform 值（04-SPEC §3.5；供 GLSL 预处理 / 转译消费）。
 *
 * <p>输入 = F2 {@link Option} 定义列表 + 当前值快照（通常来自 {@link PackOptions#values()}）；
 * 输出 = {@link #defines()}（宏名 → 替换文本）、{@link #undefines()}、{@link #uniforms()}。
 *
 * <p><b>快照语义</b>：构造时一次性求值。改完选项值后必须重建 binding
 * （{@code OptionBinding.of(packOptions)}），不要长期持有后指望它跟着变 —— 冷路径故意不做增量。
 *
 * <p><b>降级纪律</b>（T11）：任何"跳过"都必须留一条 {@link OptionDiagnostic}（WARN），
 * 见 {@link #diagnostics()}；默认输出口复用 {@link PackOptions#sink()}，也可显式注入。
 *
 * <p><b>P-1e 真值表结论</b>（18-PARALLEL §10 P-1e 的对比材料；对比测试 =
 * {@code dev.vkdisp.config.OptionDefineStyleTruthTableTest}，并排表可直接粘给 P4.2 / P4.3）：
 * <ul>
 *   <li><b>非布尔（INTEGER / FLOAT / 合法 STRING）在两种风格下逐字节相同</b> —— DefineStyle 只影响布尔选项；</li>
 *   <li><b>布尔为真</b>：{@link DefineStyle#LITERAL} → {@code #define NAME <原始词>}
 *       （true / on / yes / 1 / TRUE … 原样保留、不归一化）；{@link DefineStyle#IFDEF_TRUE} → {@code #define NAME}
 *       （空替换文本）；</li>
 *   <li><b>布尔为假</b>：{@link DefineStyle#LITERAL} → 仍出表 {@code #define NAME <原始词>}；
 *       {@link DefineStyle#IFDEF_TRUE} → 不进 {@link #defines()}，改列 {@link #undefines()}（生成 {@code #undef NAME}）；</li>
 *   <li><b>{@link #uniforms()} 与 {@link #diagnostics()} 与风格无关</b>：同输入逐字符相同（单测断言）；
 *       "跳过"行为（宏名非法 / 值非法 / 自由文本 / 快照缺值）也与风格无关，两风格同一诊断码、同一顺序；</li>
 *   <li><b>消费方式（语言事实，非猜测）</b>：{@code IFDEF_TRUE} 的空替换文本只能配 {@code #ifdef / #ifndef}
 *       （{@code #if NAME} 下表达式为空 → 预处理器报错）；{@code LITERAL} 的宏在真假两态下都"已定义"
 *       （{@code #ifdef NAME} 不区分真假），且 {@code #if NAME} 会把 {@code true / false} 这类非预处理器常量
 *       带进表达式 —— 它在真实包里的实际求值行为留给 P4.2 实测，本类不下结论。</li>
 * </ul>
 *
 * <p><b>仍未决（07-CONSTRAINTS X9：不填猜值）</b>：默认 {@link DefineStyle} 的选定缺真实包证据 ——
 * 真实包里布尔选项到底用 {@code #ifdef} 还是 {@code #if} 消费尚未统计，故默认暂留 {@link DefineStyle#LITERAL}
 * （切换是一行改动，见 {@link #of(PackOptions, DefineStyle)}）。定稿判据：P4.2 切主流包 / P4.3 选项 GUI 时
 * 取真实包的条件编译写法据实选定，再由 env-1 同步 18-PARALLEL §10 的 P-1e 状态行。
 *
 * <p><b>该对比测试未覆盖</b>（18-PARALLEL §7.3 要求显式列出）：① 经 C 线拼接后的真实预处理语义
 * （需真实包 + GPU）；② STRING 自由文本（含空白 / 引号 / 路径）如何进 GLSL —— 本类一律跳过 + WARN，
 * 包语义留待按真实包决定；③ profile 批量套用后的表；④ 非 ASCII 宏名与超长值。
 */
public final class OptionBinding {

    /** {@link #definesText()} 的版本化头。 */
    public static final String DEFINES_HEADER = "option-defines v1";

    /** {@link #uniformsText()} 的版本化头。 */
    public static final String UNIFORMS_HEADER = "option-uniforms v1";

    /** 布尔选项的 {@code #define} 风格（两种写法互斥，见类级【参考调研】第 1 条）。 */
    public enum DefineStyle {
        /**
         * 每个选项都落成 {@code #define NAME 替换文本}，布尔选项的替换文本就是 {@code true} / {@code false}。
         * 默认风格：与"选项值 → 表"一一对应，C/D 线可直接拿表做常量求值；配合 {@code #if NAME} 之外
         * 的 {@code #if NAME} 布尔写法时需注意 true/false 不是预处理器常量（见未覆盖情况说明）。
         */
        LITERAL,

        /**
         * OF 兼容风格：布尔选项为真 → {@code #define NAME}（空替换文本），为假 → 从表中移除并列入
         * {@link #undefines()}（生成 {@code #undef NAME}）；非布尔选项与 {@link #LITERAL} 相同。
         * 采用 {@code #ifdef} / {@code #ifndef} 写法的包需要这一风格。
         */
        IFDEF_TRUE
    }

    private final DefineStyle style;
    private final OptionDiagnosticSink sink;
    private final LinkedHashMap<String, String> defines = new LinkedHashMap<>();
    private final LinkedHashSet<String> undefines = new LinkedHashSet<>();
    private final List<OptionUniformValue> uniforms = new ArrayList<>();
    private final List<OptionDiagnostic> diagnostics = new ArrayList<>();

    /** 从 {@link PackOptions} 的当前值快照构造（默认风格 {@link DefineStyle#LITERAL}，输出口复用容器）。 */
    public static OptionBinding of(PackOptions options) {
        return of(options, options.sink(), DefineStyle.LITERAL);
    }

    /** 从 {@link PackOptions} 的当前值快照构造（输出口复用容器）。 */
    public static OptionBinding of(PackOptions options, DefineStyle style) {
        return of(options, options.sink(), style);
    }

    /** 从 {@link PackOptions} 的当前值快照构造。 */
    public static OptionBinding of(PackOptions options, OptionDiagnosticSink sink, DefineStyle style) {
        Objects.requireNonNull(options, "options");
        return new OptionBinding(options.definitions(), options.values(), sink, style);
    }

    /** 从定义列表 + 值快照构造（默认诊断输出 = JDK 日志，默认风格 {@link DefineStyle#LITERAL}）。 */
    public static OptionBinding of(List<Option> definitions, Map<String, String> values) {
        return new OptionBinding(definitions, values, OptionDiagnosticSink.systemLogger(), DefineStyle.LITERAL);
    }

    /** 从定义列表 + 值快照构造（供单测 / 主线显式指定输出口与风格）。 */
    public static OptionBinding of(
            List<Option> definitions,
            Map<String, String> values,
            OptionDiagnosticSink sink,
            DefineStyle style) {
        return new OptionBinding(definitions, values, sink, style);
    }

    private OptionBinding(
            List<Option> definitions,
            Map<String, String> values,
            OptionDiagnosticSink sink,
            DefineStyle style) {
        Objects.requireNonNull(definitions, "definitions");
        Objects.requireNonNull(values, "values");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.style = Objects.requireNonNull(style, "style");
        for (Option option : definitions) {
            if (option == null) {
                report(OptionDiagnostic.warn("NULL_OPTION",
                        "binding input contains a null option entry; skipped (T11)"));
                continue;
            }
            String value = values.get(option.name());
            if (value == null) {
                report(OptionDiagnostic.warn("MISSING_VALUE",
                        "option '" + option.name() + "' has no value in the supplied snapshot;"
                                + " no #define / uniform emitted (T11)"));
                continue;
            }
            bind(option, value);
        }
    }

    private void bind(Option option, String value) {
        if (!OptionText.isGlslIdentifier(option.name())) {
            report(OptionDiagnostic.warn("DEFINE_SKIPPED_UNSAFE_NAME",
                    "option name '" + option.name() + "' is not a GLSL identifier;"
                            + " no #define / uniform emitted (T11)"));
            return;
        }
        if (value.isEmpty()) {
            report(OptionDiagnostic.warn("DEFINE_SKIPPED_EMPTY_VALUE",
                    "option '" + option.name() + "' has no value (unset / unknown free default);"
                            + " no #define / uniform emitted (T11)"));
            return;
        }
        switch (option.type()) {
            case BOOLEAN -> bindBoolean(option, value);
            case INTEGER -> bindInteger(option, value);
            case FLOAT -> bindFloat(option, value);
            case STRING -> bindString(option, value);
        }
    }

    private void bindBoolean(Option option, String value) {
        if (!OptionText.isGlslIdentifier(value) && !"0".equals(value) && !"1".equals(value)) {
            report(OptionDiagnostic.warn("DEFINE_SKIPPED_UNSAFE_VALUE",
                    "boolean option '" + option.name() + "' value '" + value
                            + "' is not a boolean token; no #define / uniform emitted (T11)"));
            return;
        }
        boolean truthy = isTruthy(value);
        if (this.style == DefineStyle.IFDEF_TRUE) {
            if (truthy) {
                this.defines.put(option.name(), "");
            } else {
                this.undefines.add(option.name());
            }
        } else {
            this.defines.put(option.name(), value);
        }
        this.uniforms.add(new OptionUniformValue.BoolValue(option.name(), truthy, value));
    }

    private void bindInteger(Option option, String value) {
        Long parsed = OptionText.isIntegerText(value) ? OptionText.parseLongOrNull(value) : null;
        if (parsed == null || parsed.longValue() < Integer.MIN_VALUE || parsed.longValue() > Integer.MAX_VALUE) {
            report(OptionDiagnostic.warn("DEFINE_SKIPPED_UNSAFE_VALUE",
                    "integer option '" + option.name() + "' value '" + value
                            + "' is not a valid 32-bit integer; no #define / uniform emitted (T11)"));
            return;
        }
        this.defines.put(option.name(), value);
        this.uniforms.add(new OptionUniformValue.IntValue(option.name(), (int) parsed.longValue(), value));
    }

    private void bindFloat(Option option, String value) {
        Float parsed = OptionText.isDecimalText(value) ? OptionText.parseFloatOrNull(value) : null;
        if (parsed == null) {
            report(OptionDiagnostic.warn("DEFINE_SKIPPED_UNSAFE_VALUE",
                    "float option '" + option.name() + "' value '" + value
                            + "' is not a finite decimal number; no #define / uniform emitted (T11)"));
            return;
        }
        this.defines.put(option.name(), value);
        this.uniforms.add(new OptionUniformValue.FloatValue(option.name(), parsed.floatValue(), value));
    }

    private void bindString(Option option, String value) {
        if (!OptionText.isGlslIdentifier(value)) {
            report(OptionDiagnostic.warn("DEFINE_SKIPPED_UNSAFE_VALUE",
                    "text option '" + option.name() + "' value '" + value
                            + "' is not a single GLSL identifier; no #define / uniform emitted"
                            + " (自由文本如何进 GLSL 属于包语义，需 C 线按包决定，T11)"));
            return;
        }
        this.defines.put(option.name(), value);
        this.uniforms.add(new OptionUniformValue.TextValue(option.name(), value, value));
    }

    /** 布尔真值口径：{@code true/on/yes/1} 为真，其余（含 {@code false/off/no/0}）为假。 */
    private static boolean isTruthy(String value) {
        String text = value.toLowerCase(java.util.Locale.ROOT);
        return !("false".equals(text) || "off".equals(text) || "no".equals(text) || "0".equals(text));
    }

    // ---------------------------------------------------------------- 产物

    /** 宏名 → 替换文本（声明顺序，不可变；空串 = 只有宏名没有替换文本）。 */
    public Map<String, String> defines() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(this.defines));
    }

    /** 需要显式 {@code #undef} 的宏名（仅 {@link DefineStyle#IFDEF_TRUE} 下可能非空；声明顺序，不可变）。 */
    public Set<String> undefines() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(this.undefines));
    }

    /** uniform 值列表（声明顺序，不可变）。 */
    public List<OptionUniformValue> uniforms() {
        return List.copyOf(this.uniforms);
    }

    /** 绑定时产生的诊断（不可变快照；任何"跳过"都在这里，T11）。 */
    public List<OptionDiagnostic> diagnostics() {
        return List.copyOf(this.diagnostics);
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

    /** 本次绑定使用的风格。 */
    public DefineStyle style() {
        return this.style;
    }

    /** 指定宏名的替换文本（被跳过时为空 Optional）。 */
    public Optional<String> define(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.defines.get(name.trim()));
    }

    /** 指定选项名的 uniform 值（被跳过时为空 Optional）。 */
    public Optional<OptionUniformValue> uniform(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String key = name.trim();
        for (OptionUniformValue uniform : this.uniforms) {
            if (uniform.name().equals(key)) {
                return Optional.of(uniform);
            }
        }
        return Optional.empty();
    }

    /** {@code #define} 行的规范文本（快照式断言用；逐字符可比对）。 */
    public String definesText() {
        StringBuilder text = new StringBuilder(DEFINES_HEADER).append('\n');
        text.append("style=").append(this.style).append('\n');
        for (Map.Entry<String, String> entry : this.defines.entrySet()) {
            text.append("#define ").append(entry.getKey());
            if (!entry.getValue().isEmpty()) {
                text.append(' ').append(entry.getValue());
            }
            text.append('\n');
        }
        for (String name : this.undefines) {
            text.append("#undef ").append(name).append('\n');
        }
        return text.toString();
    }

    /** uniform 值的规范文本（快照式断言用；逐字符可比对）。 */
    public String uniformsText() {
        StringBuilder text = new StringBuilder(UNIFORMS_HEADER).append('\n');
        for (OptionUniformValue uniform : this.uniforms) {
            text.append(uniform.format()).append('\n');
        }
        return text.toString();
    }

    /** 直接把 {@code #define} / {@code #undef} 行追加到给定 builder（供预处理 / 转译拼接）。 */
    public void appendDefinesTo(StringBuilder target) {
        Objects.requireNonNull(target, "target");
        target.append(definesText());
    }

    private void report(OptionDiagnostic diagnostic) {
        this.diagnostics.add(diagnostic);
        this.sink.accept(diagnostic);
    }

    @Override
    public String toString() {
        return "OptionBinding style=" + this.style + " defines=" + this.defines
                + " undefines=" + this.undefines + " diagnostics=" + this.diagnostics.size();
    }
}
