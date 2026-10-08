package dev.vkdisp.pack.uniform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 【参考调研】GAP-021 包自写 uniform 的集合（解析入口 + 每帧求值入口）
 * 0. 合规核对（第 0 步闸门，通过）：
 *    参考对象 = shaders.properties 官方参考「Custom Uniforms」页的键格式
 *    （{@code uniform.<类型>.<名>} / {@code variable.<类型>.<名>}）+ 「uniform/variable 可互相引用」
 *    这条事实（BSL v10.1.8 实测：{@code uniform.float.timeAngle} 引用 4 个 {@code variable.float.*}，
 *    {@code uniform.float.isCold} 引用同段稍后定义的 {@code yCold2/yCold3} ⇒ 必须先建依赖图再求值）。
 *    → 能否并入本项目（MIT）：可以（独立编写，零第三方代码）
 *    → 例外条款：无
 * 1. 官方/主实现：无外部可读实现（Iris 源码禁读 / OptiFine 闭源）。
 * 2. 备选：把求值塞进 {@code ShaderProperties.classify}（否决：那是冷路径的**分派**层，
 *    求值要每帧跑；混在一起会让「解析」与「逐帧」两种性能口径纠缠，18-PARALLEL §7.7）。
 * 3. 我们的差异点：
 *    ① <b>后定义可引用先定义，反之也行</b> —— 求值序由依赖决定，书写序只影响同名覆盖；
 *    ② 同名重复（properties 语义 = 后写覆盖前写）在 {@link #fromProperties} 就按 Map
 *       天然收敛，与 {@code ShaderProperties.directives} 的既有口径一致；
 *    ③ 坏定义（语法错 / 不支持的类型）不进 {@code definitions}，只进
 *       {@link #rejections()} —— 引用坏名字的表达式会得到「未求出」这条**可见**理由，
 *       而不是 0（X9/X11）；
 *    ④ 本类不做日志：诊断以数据形式交给调用方（冷路径 = {@code PackCompositeSource} 的
 *       诊断列表；每帧 = {@code render/PackUniformSupply} 的一次性 WARN），
 *       这样本包既能进渲染路径也能进纯 JUnit 车道（同 {@code NightVisionSupply} 的隔离理由）。
 * 4. 许可证核对结论：本项目 MIT；零第三方代码并入。
 * 5. 性能基线：❄️ 冷路径构造；每帧一次求值（每定义一条小 AST 遍历）。
 */
public final class PackUniformSet {

    /** 空集（未选包，或包里一行 `uniform.` / `variable.` 都没有 —— 走这条时不产生任何日志噪声）。 */
    public static final PackUniformSet EMPTY = new PackUniformSet(Map.of(), List.of());

    /** 键前缀（文档口径：uniform 上传、variable 不上传）。 */
    private static final String UNIFORM_PREFIX = "uniform.";
    private static final String VARIABLE_PREFIX = "variable.";

    private final Map<String, PackUniformDefinition> definitions;
    private final List<String> rejections;

    private PackUniformSet(Map<String, PackUniformDefinition> definitions, List<String> rejections) {
        this.definitions = definitions;
        this.rejections = rejections;
    }

    /** 一次求值的产物（值 + 两类可见原因）。 */
    public record Outcome(
            Map<String, Object> values,
            List<String> skips,
            List<String> unresolvedInputs) {

        public Outcome {
            values = values == null ? Map.of() : Map.copyOf(values);
            skips = skips == null ? List.of() : List.copyOf(skips);
            unresolvedInputs = unresolvedInputs == null ? List.of() : List.copyOf(unresolvedInputs);
        }
    }

    // ------------------------------------------------------------------------ 冷路径

    /**
     * 从 {@code shaders.properties} 的通用指令表（{@code ShaderPack#properties()}）里取出
     * 包自写的 uniform/variable 并解析表达式。
     *
     * <p>永不抛：单行语法错只进 {@link #rejections()}（T11「降级要看得见」，
     * 一条坏表达式不该让整个包加载失败 —— 那会连带把**别的**好定义一起丢掉）。
     */
    public static PackUniformSet fromProperties(Map<String, String> properties) {
        Map<String, PackUniformDefinition> definitions = new LinkedHashMap<>();
        List<String> rejections = new ArrayList<>();
        if (properties == null) {
            return new PackUniformSet(definitions, rejections);
        }
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String key = entry.getKey();
            if (!startsWithUniformKey(key)) {
                continue;
            }
            accept(key, entry.getValue(), definitions, rejections);
        }
        return new PackUniformSet(definitions, List.copyOf(rejections));
    }

    private static boolean startsWithUniformKey(String key) {
        return key != null && (key.startsWith(UNIFORM_PREFIX) || key.startsWith(VARIABLE_PREFIX));
    }

    private static void accept(String key, String value,
            Map<String, PackUniformDefinition> definitions, List<String> rejections) {
        PackUniformDefinition.Kind kind = key.startsWith(UNIFORM_PREFIX)
                ? PackUniformDefinition.Kind.UNIFORM : PackUniformDefinition.Kind.VARIABLE;
        String rest = key.substring(kind.prefix().length() + 1);
        int dot = rest.indexOf('.');
        if (dot <= 0 || dot == rest.length() - 1) {
            rejections.add(key + ": 键格式必须是 " + kind.prefix() + ".<类型>.<名>");
            return;
        }
        PackUniformDefinition.Type type = PackUniformDefinition.Type.of(rest.substring(0, dot));
        String name = rest.substring(dot + 1).strip();
        if (type == PackUniformDefinition.Type.UNKNOWN) {
            rejections.add(key + ": 文档之外的声明类型 '" + rest.substring(0, dot) + "'");
            return;
        }
        if (!type.supported()) {
            rejections.add(key + ": 类型 " + type + " 已识别但本项目暂不求值（BSL 未使用，登记）");
            return;
        }
        if (!isIdentifier(name)) {
            rejections.add(key + ": 非法的 uniform 名 '" + name + "'");
            return;
        }
        String source = value == null ? "" : value.strip();
        try {
            definitions.put(name, new PackUniformDefinition(name, kind, type,
                    UniformExpressionParser.parse(source), source));
        } catch (UniformExpressionParser.SyntaxException e) {
            rejections.add(key + ": " + e.getMessage());
        }
    }

    /** GLSL 标识符形态（uniform 名最终要落进块成员，名字必须与包声明逐字符一致）。 */
    private static boolean isIdentifier(String name) {
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------------ 每帧

    /**
     * 在给定输入上求值全部定义。
     *
     * @param inputs 引擎内建 + 跨帧平滑状态（由 {@code render/PackUniformSupply} 组装）
     */
    public Outcome evaluate(PackUniformInputs inputs) {
        if (definitions.isEmpty()) {
            return new Outcome(Map.of(), List.of(), List.of());
        }
        PackUniformEvaluator evaluator = new PackUniformEvaluator(definitions, inputs);
        Map<String, Object> values = evaluator.evaluateAll();
        return new Outcome(values, evaluator.skips(), evaluator.unresolvedInputs());
    }

    /** 包自写的 uniform 名（排序后给出，日志与判据都要求可比对）。 */
    public List<String> uniformNames() {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, PackUniformDefinition> entry : definitions.entrySet()) {
            if (entry.getValue().kind() == PackUniformDefinition.Kind.UNIFORM) {
                names.add(entry.getKey());
            }
        }
        names.sort(java.util.Comparator.naturalOrder());
        return names;
    }

    /** 全部定义名（含 variable）；排序后便于跨轮比对。 */
    public List<String> allNames() {
        return new ArrayList<>(new TreeSet<>(definitions.keySet()));
    }

    /** 解析期就被拒掉的行（含原因）；由包激活路径转成诊断（X11）。 */
    public List<String> rejections() {
        return rejections;
    }

    public boolean isEmpty() {
        return definitions.isEmpty() && rejections.isEmpty();
    }

    /** 定义条数（自报行用：`[uniforms] pack-authored definitions=…`）。 */
    public int size() {
        return definitions.size();
    }

    /**
     * 把求值结果并进 {@code gather()} 的映射（<b>包赢</b>）。
     *
     * <p>🔖 覆盖方向是 OF 语义、不是实现细节：包作者写
     * {@code uniform.float.timeAngle=…}（BSL shaders.properties:151）就是要替换引擎给的值；
     * 反过来让内建压回去 = 包作者的时间曲线整条失效，而日志看不出来。
     * 之所以在纯 Java 层做这一步：这样「谁覆盖谁」能在单测里钉住，
     * 不必去碰 {@code render/OfUniformManager.gather} 那条依赖 Minecraft 运行态的车道。
     */
    public static void mergeOverrides(Map<String, Object> values, Outcome outcome) {
        if (values == null || outcome == null) {
            return;
        }
        values.putAll(outcome.values());
    }
}
