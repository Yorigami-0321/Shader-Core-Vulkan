package dev.vkdisp.config;
/**
 * 【参考调研】F 线选项模型 / 选项值运行时容器 PackOptions（04-SPEC §3.5）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §3.1（Option.js/OptionType.js = 包自定义选项；ShadersScreen 只读不写用户的包）
 *    与 §3.5（config/PackOptions.java = 用户包声明的选项的运行时值）；
 *    ② docs/18-PARALLEL.md §4 F 线（完成标准：选项默认值正确、值越界钳制并打 WARN、与 Option 模型对接通）
 *    与 §7.3（并行线证据：单测全绿 + 边界用例 + 降级不静默）；
 *    ③ docs/08-TESTING.md §4（shaders.properties 的选项能被枚举、sliders/profiles、"const int X = 0; // [0 1 2]" 型
 *    选项常量能被识别）；④ docs/07-CONSTRAINTS.md T11（降级必须显式 WARN）；⑤ F2 已冻结的
 *    dev.vkdisp.pack.Option / OptionType / ShaderPack（字段与语义直接消费，不改）。
 *    许可证：本仓库自有文档与自有冻结契约（本项目 MIT）→ 可直接消费；
 *    OptiFine（sp614x/optifine）无 LICENSE 文件 → ARR，按 07-CONSTRAINTS X20 只取不受版权保护的格式事实
 *    （选项取值形态与 profile.NAME 条目语法），零文本复制；Iris（LGPL-3.0）同口径；
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触 —— 按 L12「判不过就换参考，不再读它的代码」。
 *    → 能否并入本项目（MIT）：本文件为独立实现，可并入
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：OF 选项语义的格式事实（经 F2 冻结契约的 Javadoc 转述，不读 OF 源码）：
 *    properties 侧 {key=v1|v2|v3}；GLSL 侧 {#define NAME 值 // [v1 v2 v3]} 与 {const int NAME = 值; // [v1 v2 v3]}；
 *    sliders= 标出滑条选项；profile.NAME= 条目形如 {OPTION:值} / {OPTION=值} / {OPTION}（开）/ {!OPTION}（关）/
 *    {profile.其他名}（继承）/ {!program.名}（禁程序，程序名可带维度前缀）。
 * 2. 备选：把值运算交给 GUI（PackOptionsScreen）—— 否决（18-PARALLEL §2.1 明确 GUI 只能走关键路径，
 *    且值语义必须能在无 GPU 的单测里自证）。备选：非法值直接抛异常 —— 否决（用户在界面上点错一个值不该让游戏崩；
 *    T11 要求的是"显式降级 + WARN"，不是崩溃）。
 * 3. 我们的差异点：① 取值一律用规范化文本（String）承载，整数/浮点/布尔各有确定性文本形态，
 *    使 "#define 表" 与单测快照断言逐字符可对比；② 越界钳制的口径是"钳到允许值列表里数值最近的一端"，
 *    整数自由取值超出 32 位时钳到 int 边界，均打 WARN；③ 文本值不在允许列表里时无法"钳制"，
 *    采用"拒绝本次赋值 + 保留旧值 + WARN"（越界钳制只对有序数值成立，文本没有中间态）；
 *    ④ 默认值缺失（F2 约定 defaultValue 为空串）时不静默使用 0，而是按确定性口径推导 + WARN，
 *    并把推导结果记为可重置的 effective default：BOOLEAN 优先取允许值里的"关"项（false/off/no/0），
 *    INTEGER / FLOAT / STRING 取允许值首项，无允许值列表时取类型零值；⑤ profile 继承用访问栈检测环；{!program.名} 这类
 *    非选项条目由本线显式 WARN 并跳过（program 开关归管线层，不是选项值）；⑥ 选项名匹配是精确匹配
 *    （不做大小写折叠猜测，避免把"猜"写进契约）。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制，只消费本项目 F2 冻结契约与自有文档事实。
 * 5. 性能基线：❄️ 冷路径（切包 / 改选项 / 应用 profile 时调用，事后只读），不做任何性能优化（18-PARALLEL §7.7）；
 *    不做缓存、不做增量，只用 LinkedHashMap / ArrayList 这类最直白的数据结构。
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
import dev.vkdisp.pack.ShaderPack;

/**
 * 用户着色器包声明的选项的运行时值容器（04-SPEC §3.5；F2 {@link Option} 的消费方）。
 *
 * <p>职责边界：本类只管"选项名 → 当前值"的求值、钳制、默认值与 profile 应用；
 * 把值变成 {@code #define} / uniform 是 {@link OptionBinding} 的事；渲染 UI 是 {@code screen/PackOptionsScreen}
 * 的事（18-PARALLEL §2.1 反例清单：本线不许写界面）。
 *
 * <p>只读红线（04-SPEC §3.1）：本类只读用户包解析出的 {@link Option} 定义，绝不写回用户的包。
 *
 * <p><b>线程模型</b>：非线程安全，按"客户端单线程"使用（切包 / 改选项都发生在客户端主线程）。
 *
 * <p><b>降级与诊断</b>（T11）：所有降级都会生成 {@link OptionDiagnostic}，
 * 既留在 {@link #diagnostics()} 里供单测逐条断言，也转发给 {@link OptionDiagnosticSink}（默认写 JDK 日志）。
 *
 * @see OptionBinding
 */
public final class PackOptions {

    /** 一次赋值的结局。 */
    public enum SetStatus {
        /** 值合法且被采纳。 */
        ACCEPTED,
        /** 值越界，已钳制到最近的允许值（必有 WARN）。 */
        CLAMPED,
        /** 值非法 / 选项不存在，已拒绝并保留旧值（必有 WARN）。 */
        REJECTED,
        /** 已重置回有效默认值。 */
        RESET
    }

    /**
     * 一次 {@link PackOptions#set(String, String)} / {@link PackOptions#reset(String)} 的结果快照。
     *
     * @param name           选项名
     * @param previousValue  赋值前的值（选项不存在时为 null）
     * @param requestedValue 调用方请求的原始值（reset 时为 null）
     * @param appliedValue   实际生效的值（被拒绝时等于 previousValue）
     * @param changed        是否真的改变了当前值
     * @param status         结局
     * @param diagnostics    本次操作产生的诊断（不可变副本）
     */
    public record SetOutcome(
            String name,
            String previousValue,
            String requestedValue,
            String appliedValue,
            boolean changed,
            SetStatus status,
            List<OptionDiagnostic> diagnostics) {

        /** 紧凑构造器：name / status 非空，诊断列表不可变。 */
        public SetOutcome {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(status, "status");
            diagnostics = List.copyOf(diagnostics);
        }

        /** 本次赋值是否生效（ACCEPTED / CLAMPED / RESET）。 */
        public boolean applied() {
            return this.status != SetStatus.REJECTED;
        }

        /** 单行打印形式，供日志与单测比对。 */
        public String format() {
            return this.status + " " + this.name + ": requested=" + this.requestedValue
                    + " previous=" + this.previousValue + " applied=" + this.appliedValue;
        }
    }

    /** 允许值列表里的一个可比较数值项（number 用于比较与钳制，text 是原样输出的允许值文本）。 */
    private record NumberEntry(String text, double number) {
    }

    /** 一次规范化尝试的结果。 */
    private record Normalization(String value, SetStatus status, List<OptionDiagnostic> diagnostics) {
    }

    private final LinkedHashMap<String, Option> definitions;
    private final LinkedHashMap<String, String> effectiveDefaults;
    private final LinkedHashMap<String, String> values;
    private final LinkedHashMap<String, List<NumberEntry>> numberTables;
    private final OptionDiagnosticSink sink;
    private final List<OptionDiagnostic> diagnostics = new ArrayList<>();

    /**
     * 从包选项定义构造（默认诊断输出 = JDK 日志）。
     *
     * @param options 选项定义列表（可为空 —— 空包/无选项是合法情形）
     */
    public static PackOptions of(List<Option> options) {
        return of(options, OptionDiagnosticSink.systemLogger());
    }

    /**
     * 从包选项定义构造。
     *
     * @param options 选项定义列表（可为空）
     * @param sink    诊断输出口（非空；单测传 OptionDiagnosticSink.collecting(...)）
     */
    public static PackOptions of(List<Option> options, OptionDiagnosticSink sink) {
        return new PackOptions(options, sink);
    }

    /** 从 F2 冻结契约的 {@link ShaderPack} 构造（消费 {@code pack.options()}）。 */
    public static PackOptions of(ShaderPack pack) {
        return of(pack, OptionDiagnosticSink.systemLogger());
    }

    /** 从 F2 冻结契约的 {@link ShaderPack} 构造（消费 {@code pack.options()}）。 */
    public static PackOptions of(ShaderPack pack, OptionDiagnosticSink sink) {
        Objects.requireNonNull(pack, "pack");
        return new PackOptions(pack.options(), sink);
    }

    private PackOptions(List<Option> options, OptionDiagnosticSink sink) {
        Objects.requireNonNull(options, "options");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.definitions = new LinkedHashMap<>();
        for (Option option : options) {
            if (option == null) {
                report(OptionDiagnostic.warn("NULL_OPTION",
                        "option list contains a null entry; skipped (T11: 不静默)"));
                continue;
            }
            Option existing = this.definitions.putIfAbsent(option.name(), option);
            if (existing != null) {
                report(OptionDiagnostic.warn("DUPLICATE_OPTION",
                        "option '" + option.name() + "' is declared more than once; keeping the first declaration"));
            }
        }
        this.numberTables = new LinkedHashMap<>();
        this.effectiveDefaults = new LinkedHashMap<>();
        for (Option option : this.definitions.values()) {
            List<NumberEntry> table = buildNumberTable(option);
            if (table != null) {
                this.numberTables.put(option.name(), table);
            }
            this.effectiveDefaults.put(option.name(), initialValue(option));
        }
        this.values = new LinkedHashMap<>(this.effectiveDefaults);
    }

    // ---------------------------------------------------------------- 取值

    /** 当前值（选项不存在时空 Optional）。 */
    public Optional<String> findValue(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.values.get(name.trim()));
    }

    /**
     * 当前值。
     *
     * @throws IllegalArgumentException 选项不存在（显式抛，不返回 null，T11）
     */
    public String value(String name) {
        Objects.requireNonNull(name, "name");
        String key = name.trim();
        String value = this.values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("vkdisp: no option named '" + name + "' is declared by this pack");
        }
        return value;
    }

    /** 是否存在该选项。 */
    public boolean contains(String name) {
        return name != null && this.definitions.containsKey(name.trim());
    }

    /** 选项定义（选项不存在时空 Optional）。 */
    public Optional<Option> findDefinition(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.definitions.get(name.trim()));
    }

    /**
     * 选项定义。
     *
     * @throws IllegalArgumentException 选项不存在（显式抛，不返回 null，T11）
     */
    public Option definition(String name) {
        Objects.requireNonNull(name, "name");
        Option option = this.definitions.get(name.trim());
        if (option == null) {
            throw new IllegalArgumentException("vkdisp: no option named '" + name + "' is declared by this pack");
        }
        return option;
    }

    /** 全部选项定义（声明顺序，不可变）。 */
    public List<Option> definitions() {
        return List.copyOf(this.definitions.values());
    }

    /** 当前值快照（声明顺序，不可变；修改快照不影响本容器）。 */
    public Map<String, String> values() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(this.values));
    }

    /** 有效默认值快照（声明顺序，不可变；{@link #reset(String)} 回到这里）。 */
    public Map<String, String> defaults() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(this.effectiveDefaults));
    }

    /** 选项个数。 */
    public int size() {
        return this.definitions.size();
    }

    /** 至今产生的全部诊断（不可变快照）。 */
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

    /** 诊断输出口（{@link OptionBinding} 复用它，使一条链上的诊断出口一致）。 */
    public OptionDiagnosticSink sink() {
        return this.sink;
    }

    // ---------------------------------------------------------------- 赋值

    /**
     * 赋值（越界钳制 / 非法值拒绝，均打 WARN，T11）。
     *
     * @param name     选项名（精确匹配；未知选项 = 显式 WARN + REJECTED）
     * @param rawValue 请求值（null = 显式 WARN + REJECTED，不抛 NPE）
     * @return 本次赋值的结果快照
     */
    public SetOutcome set(String name, String rawValue) {
        Objects.requireNonNull(name, "name");
        String key = name.trim();
        Option option = this.definitions.get(key);
        if (option == null) {
            OptionDiagnostic diagnostic = OptionDiagnostic.warn("UNKNOWN_OPTION",
                    "no option named '" + name + "' is declared by this pack; value ignored (T11)");
            report(diagnostic);
            return new SetOutcome(key, null, rawValue, null, false, SetStatus.REJECTED, List.of(diagnostic));
        }
        String previous = this.values.get(key);
        if (rawValue == null) {
            OptionDiagnostic diagnostic = OptionDiagnostic.warn("NULL_VALUE",
                    "option '" + key + "' received a null value; keeping previous value (" + previous + ")");
            report(diagnostic);
            return new SetOutcome(key, previous, null, previous, false, SetStatus.REJECTED, List.of(diagnostic));
        }
        Normalization normalization = normalize(option, rawValue);
        for (OptionDiagnostic diagnostic : normalization.diagnostics()) {
            report(diagnostic);
        }
        if (normalization.status() == SetStatus.REJECTED) {
            return new SetOutcome(key, previous, rawValue, previous, false, SetStatus.REJECTED,
                    normalization.diagnostics());
        }
        this.values.put(key, normalization.value());
        return new SetOutcome(key, previous, rawValue, normalization.value(),
                !normalization.value().equals(previous), normalization.status(), normalization.diagnostics());
    }

    /** 重置单个选项到有效默认值（选项不存在 = 显式 WARN + REJECTED）。 */
    public SetOutcome reset(String name) {
        Objects.requireNonNull(name, "name");
        String key = name.trim();
        if (!this.definitions.containsKey(key)) {
            OptionDiagnostic diagnostic = OptionDiagnostic.warn("UNKNOWN_OPTION",
                    "no option named '" + name + "' is declared by this pack; reset ignored (T11)");
            report(diagnostic);
            return new SetOutcome(key, null, null, null, false, SetStatus.REJECTED, List.of(diagnostic));
        }
        String previous = this.values.get(key);
        String restored = this.effectiveDefaults.get(key);
        this.values.put(key, restored);
        return new SetOutcome(key, previous, null, restored, !restored.equals(previous), SetStatus.RESET, List.of());
    }

    /** 全部重置到有效默认值，返回真正发生变化的选项个数。 */
    public int resetAll() {
        int changed = 0;
        for (Map.Entry<String, String> entry : this.effectiveDefaults.entrySet()) {
            String previous = this.values.put(entry.getKey(), entry.getValue());
            if (!entry.getValue().equals(previous)) {
                changed++;
            }
        }
        return changed;
    }

    // ---------------------------------------------------------------- profile

    /** 应用 F2 冻结契约里某个包的 profile 预设（{@code pack.profiles()}）。 */
    public List<SetOutcome> applyProfile(ShaderPack pack, String profileName) {
        Objects.requireNonNull(pack, "pack");
        return applyProfile(profileName, pack.profiles());
    }

    /**
     * 应用一个 profile 预设（OF 条目语法见类级【参考调研】第 1 条）。
     *
     * <p>条目语义：{@code OPTION:值} / {@code OPTION=值} = 赋值；{@code OPTION} = 开（true）；
     * {@code !OPTION} = 关（false）；{@code profile.其他名} = 先应用被继承的 profile；
     * {@code program.名} / {@code !program.名} = 程序开关，不是选项值 → 显式 WARN 跳过；
     * 未知选项 / 未知 profile / 继承环 / 空条目都会产生显式诊断。
     *
     * @param profileName profile 名
     * @param profiles    profile 名 → 原始条目 token 列表（来自 F2 {@code ShaderPack#profiles()}）
     * @return 每个被采纳条目对应的赋值结果（按应用顺序；未知 profile 时为空列表 + ERROR 诊断）
     */
    public List<SetOutcome> applyProfile(String profileName, Map<String, List<String>> profiles) {
        Objects.requireNonNull(profileName, "profileName");
        Objects.requireNonNull(profiles, "profiles");
        List<SetOutcome> outcomes = new ArrayList<>();
        applyProfileRecursive(profileName.trim(), profiles, new LinkedHashSet<>(), outcomes);
        return List.copyOf(outcomes);
    }

    private void applyProfileRecursive(
            String profileName,
            Map<String, List<String>> profiles,
            Set<String> visiting,
            List<SetOutcome> outcomes) {
        if (profileName.isEmpty()) {
            report(OptionDiagnostic.error("EMPTY_PROFILE_NAME",
                    "profile reference with an empty name; nothing applied"));
            return;
        }
        if (!visiting.add(profileName)) {
            report(OptionDiagnostic.error("PROFILE_CYCLE",
                    "profile '" + profileName + "' inherits itself (cycle); inheritance stopped"));
            return;
        }
        try {
            List<String> entries = profiles.get(profileName);
            if (entries == null) {
                report(OptionDiagnostic.error("UNKNOWN_PROFILE",
                        "profile '" + profileName + "' is not defined by this pack; nothing applied"));
                return;
            }
            if (entries.isEmpty()) {
                report(OptionDiagnostic.warn("EMPTY_PROFILE",
                        "profile '" + profileName + "' has no entries; nothing applied"));
                return;
            }
            for (String rawEntry : entries) {
                applyProfileEntry(profileName, rawEntry, profiles, visiting, outcomes);
            }
        } finally {
            visiting.remove(profileName);
        }
    }

    private void applyProfileEntry(
            String profileName,
            String rawEntry,
            Map<String, List<String>> profiles,
            Set<String> visiting,
            List<SetOutcome> outcomes) {
        if (rawEntry == null) {
            report(OptionDiagnostic.warn("NULL_PROFILE_ENTRY",
                    "profile '" + profileName + "' contains a null entry; skipped (T11)"));
            return;
        }
        String entry = rawEntry.trim();
        if (entry.isEmpty()) {
            report(OptionDiagnostic.warn("EMPTY_PROFILE_ENTRY",
                    "profile '" + profileName + "' contains an empty entry; skipped (T11)"));
            return;
        }
        if (entry.startsWith("profile.")) {
            applyProfileRecursive(entry.substring("profile.".length()).trim(), profiles, visiting, outcomes);
            return;
        }
        boolean negated = entry.startsWith("!");
        String body = negated ? entry.substring(1).trim() : entry;
        if (body.isEmpty()) {
            report(OptionDiagnostic.warn("EMPTY_PROFILE_ENTRY",
                    "profile '" + profileName + "' entry '" + rawEntry + "' is only a negation marker; skipped (T11)"));
            return;
        }
        if (body.startsWith("program.")) {
            report(OptionDiagnostic.warn("PROFILE_PROGRAM_TOGGLE_IGNORED",
                    "profile '" + profileName + "' entry '" + rawEntry + "' toggles a program, not an option value;"
                            + " ignored (program 开关归管线层，F 线只管选项值)"));
            return;
        }
        int separator = indexOfSeparator(body);
        String optionName;
        String optionValue;
        if (separator < 0) {
            optionName = body;
            optionValue = negated ? "false" : "true";
        } else {
            optionName = body.substring(0, separator).trim();
            optionValue = body.substring(separator + 1).trim();
            if (negated) {
                report(OptionDiagnostic.warn("PROFILE_NEGATION_WITH_VALUE",
                        "profile '" + profileName + "' entry '" + rawEntry + "' combines '!' with an explicit value;"
                                + " the negation wins (option set to false)"));
                optionValue = "false";
            }
        }
        if (optionName.isEmpty()) {
            report(OptionDiagnostic.warn("EMPTY_PROFILE_ENTRY",
                    "profile '" + profileName + "' entry '" + rawEntry + "' has no option name; skipped (T11)"));
            return;
        }
        outcomes.add(set(optionName, optionValue));
    }

    private static int indexOfSeparator(String body) {
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == ':' || c == '=') {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- 默认值与规范化

    /** 有效默认值：来自 F2 {@code Option#defaultValue()}；为空/非法时按确定性规则推导并打 WARN。 */
    private String initialValue(Option option) {
        if (option.defaultValue().isEmpty()) {
            String derived = derivedDefault(option);
            report(OptionDiagnostic.warn("DEFAULT_MISSING",
                    "option '" + option.name() + "' (" + option.type() + ") has no default value; derived '"
                            + derived + "' (T11: 不静默)"));
            return derived;
        }
        Normalization normalization = normalize(option, option.defaultValue());
        for (OptionDiagnostic diagnostic : normalization.diagnostics()) {
            report(diagnostic);
        }
        if (normalization.status() == SetStatus.REJECTED) {
            String derived = derivedDefault(option);
            report(OptionDiagnostic.warn("INVALID_DEFAULT_VALUE",
                    "option '" + option.name() + "' default '" + option.defaultValue() + "' is invalid for "
                            + option.type() + "; derived '" + derived + "'"));
            return derived;
        }
        return normalization.value();
    }

    /**
     * 推导默认值（仅在 F2 的 defaultValue 缺失/非法时使用，且必然伴随 WARN）。
     *
     * <p>口径：BOOLEAN 优先取允许值列表里表示"关"的那一项（false / off / no / 0，大小写不敏感），
     * 没有则取首项、再无列表则 false —— 与 OF 对"默认值未知"的处置方向一致（宁可关掉，不要意外打开）；
     * INTEGER / FLOAT / STRING 取允许值首项（原文顺序），无列表时取类型零值。
     */
    private static String derivedDefault(Option option) {
        if (option.type() == OptionType.BOOLEAN) {
            for (String declared : option.values()) {
                if (isOffToken(declared)) {
                    return declared;
                }
            }
            return option.values().isEmpty() ? "false" : option.values().get(0);
        }
        if (!option.values().isEmpty()) {
            return option.values().get(0);
        }
        return switch (option.type()) {
            case BOOLEAN -> "false";
            case INTEGER -> "0";
            case FLOAT -> "0.0";
            case STRING -> "";
        };
    }

    /** 是否为表示"关"的布尔取值 token。 */
    private static boolean isOffToken(String text) {
        return text.equalsIgnoreCase("false") || text.equalsIgnoreCase("off")
                || text.equalsIgnoreCase("no") || "0".equals(text);
    }

    /**
     * 允许值列表的数值视图（仅 INTEGER / FLOAT；其它类型返回 null）。
     *
     * <p>列表里无法按声明类型解析的条目会打 WARN 并被忽略（不猜值，07-CONSTRAINTS X9）。
     */
    private List<NumberEntry> buildNumberTable(Option option) {
        if (option.type() != OptionType.INTEGER && option.type() != OptionType.FLOAT) {
            return null;
        }
        List<NumberEntry> table = new ArrayList<>(option.values().size());
        for (String declared : option.values()) {
            Double parsed = option.type() == OptionType.INTEGER ? parseIntegerValue(declared) : parseFloatValue(declared);
            if (parsed == null) {
                report(OptionDiagnostic.warn("OPTION_VALUE_UNPARSEABLE",
                        "option '" + option.name() + "' declares value '" + declared + "' which is not a valid "
                                + option.type() + " value; entry ignored for range clamping"));
                continue;
            }
            table.add(new NumberEntry(declared, parsed.doubleValue()));
        }
        return table;
    }

    private static Double parseIntegerValue(String text) {
        if (!OptionText.isIntegerText(text)) {
            return null;
        }
        Long parsed = OptionText.parseLongOrNull(text);
        return parsed == null ? null : Double.valueOf(parsed.doubleValue());
    }

    private static Double parseFloatValue(String text) {
        if (!OptionText.isDecimalText(text)) {
            return null;
        }
        Float parsed = OptionText.parseFloatOrNull(text);
        return parsed == null ? null : Double.valueOf(parsed.doubleValue());
    }

    private Normalization normalize(Option option, String requested) {
        String text = requested.trim();
        return switch (option.type()) {
            case BOOLEAN -> normalizeBoolean(option, text, requested);
            case INTEGER -> normalizeNumeric(option, text, requested, true);
            case FLOAT -> normalizeNumeric(option, text, requested, false);
            case STRING -> normalizeString(option, text, requested);
        };
    }

    private static Normalization normalizeBoolean(Option option, String text, String requested) {
        if (!text.isEmpty()) {
            for (String allowed : option.values()) {
                if (allowed.equalsIgnoreCase(text)) {
                    return accepted(allowed);
                }
            }
        }
        if (!option.values().isEmpty()) {
            return rejected("VALUE_NOT_ALLOWED",
                    "boolean option '" + option.name() + "' rejects '" + requested + "' (allowed "
                            + option.values() + "); keeping previous value");
        }
        if (text.equalsIgnoreCase("true")) {
            return accepted("true");
        }
        if (text.equalsIgnoreCase("false")) {
            return accepted("false");
        }
        return rejected("INVALID_VALUE",
                "boolean option '" + option.name() + "' rejects '" + requested
                        + "' (expected true|false); keeping previous value");
    }

    private Normalization normalizeNumeric(Option option, String text, String requested, boolean integer) {
        double requestedNumber;
        if (integer) {
            if (!OptionText.isIntegerText(text)) {
                return rejected("INVALID_VALUE",
                        "integer option '" + option.name() + "' rejects '" + requested
                                + "' (not an integer); keeping previous value");
            }
            Long parsed = OptionText.parseLongOrNull(text);
            if (parsed == null) {
                String boundary = text.startsWith("-")
                        ? String.valueOf(Integer.MIN_VALUE) : String.valueOf(Integer.MAX_VALUE);
                return clamped(boundary, "OUT_OF_RANGE_CLAMPED",
                        "integer option '" + option.name() + "' value '" + requested
                                + "' exceeds the 64-bit integer range; clamped to " + boundary);
            }
            if (parsed.longValue() < Integer.MIN_VALUE || parsed.longValue() > Integer.MAX_VALUE) {
                String boundary = parsed.longValue() < 0
                        ? String.valueOf(Integer.MIN_VALUE) : String.valueOf(Integer.MAX_VALUE);
                return clamped(boundary, "OUT_OF_RANGE_CLAMPED",
                        "integer option '" + option.name() + "' value '" + requested
                                + "' is outside the 32-bit integer range; clamped to " + boundary);
            }
            requestedNumber = parsed.doubleValue();
        } else {
            if (!OptionText.isDecimalText(text)) {
                return rejected("INVALID_VALUE",
                        "float option '" + option.name() + "' rejects '" + requested
                                + "' (not a decimal number); keeping previous value");
            }
            Float parsed = OptionText.parseFloatOrNull(text);
            if (parsed == null) {
                return rejected("INVALID_VALUE",
                        "float option '" + option.name() + "' rejects '" + requested
                                + "' (not a finite 32-bit float); keeping previous value");
            }
            requestedNumber = parsed.doubleValue();
        }
        List<NumberEntry> table = this.numberTables.get(option.name());
        if (table != null && !table.isEmpty()) {
            for (NumberEntry entry : table) {
                if (Double.compare(entry.number(), requestedNumber) == 0) {
                    return accepted(entry.text());
                }
            }
            NumberEntry min = table.get(0);
            NumberEntry max = table.get(0);
            for (NumberEntry entry : table) {
                if (entry.number() < min.number()) {
                    min = entry;
                }
                if (entry.number() > max.number()) {
                    max = entry;
                }
            }
            NumberEntry boundary = requestedNumber < min.number() ? min : max;
            return clamped(boundary.text(), "OUT_OF_RANGE_CLAMPED",
                    option.type() + " option '" + option.name() + "' value '" + requested + "' is outside ["
                            + min.text() + " .. " + max.text() + "]; clamped to " + boundary.text());
        }
        return accepted(integer
                ? String.valueOf((long) requestedNumber)
                : OptionText.canonicalFloat((float) requestedNumber));
    }

    private static Normalization normalizeString(Option option, String text, String requested) {
        if (option.values().isEmpty()) {
            if (text.isEmpty()) {
                return rejected("INVALID_VALUE",
                        "free-text option '" + option.name() + "' rejects an empty value"
                                + " (it cannot be emitted as a #define); keeping previous value");
            }
            return accepted(text);
        }
        for (String allowed : option.values()) {
            if (allowed.equals(text)) {
                return accepted(allowed);
            }
        }
        return rejected("VALUE_NOT_ALLOWED",
                "text option '" + option.name() + "' rejects '" + requested + "' (allowed "
                        + option.values() + "); keeping previous value");
    }

    private static Normalization accepted(String value) {
        return new Normalization(value, SetStatus.ACCEPTED, List.of());
    }

    private static Normalization clamped(String value, String code, String message) {
        return new Normalization(value, SetStatus.CLAMPED, List.of(OptionDiagnostic.warn(code, message)));
    }

    private static Normalization rejected(String code, String message) {
        return new Normalization(null, SetStatus.REJECTED, List.of(OptionDiagnostic.warn(code, message)));
    }

    /** 落一条诊断：进列表（供单测断言）+ 转发输出口（T11 不静默）。 */
    private void report(OptionDiagnostic diagnostic) {
        this.diagnostics.add(diagnostic);
        this.sink.accept(diagnostic);
    }

    @Override
    public String toString() {
        return "PackOptions values=" + this.values + " diagnostics=" + this.diagnostics.size();
    }
}
