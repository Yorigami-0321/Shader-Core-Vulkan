package dev.vkdisp.pack.uniform;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【参考调研】GAP-021 表达式求值器 + 函数表 + 依赖序
 * 0. 合规核对（第 0 步闸门，通过）：
 *    参考对象 = shaders.properties 官方参考「Custom Uniforms · Functions」的**函数清单与语义**
 *    （本项目只读文档事实；Iris/OptiFine 的实现源码禁止阅读，07-CONSTRAINTS L12 / X19-X21）：
 *    {@code if / in / smooth / sin / cos / tan / asin / acos / atan / exp / exp2 / exp10 / log /
 *    log2 / log10 / sqrt / pow / abs / sign / floor / ceil / frac / min / max / clamp / mix / edge /
 *    fmod / between / equals / torad / todeg}，以及
 *    「{@code smooth([id], val, [fadeUpTime, [fadeDownTime]])}：fade 时间是**半衰期，单位=游戏刻**，
 *    默认 1 刻」与「{@code if(cond, val, [cond2, val2, …], val_else)} 返回第一个成立支的值」。
 *    零代码复制；期望值全部由本文件独立算出。
 *    → 能否并入本项目（MIT）：可以
 *    → 例外条款：无
 * 1. 官方/主实现：无外部可读实现 ⇒ 自研。
 * 2. 备选：① 交给 GLSL 侧求值（否决：`variable.` 根本不上传，OF 语义就是 CPU 逐帧算）；
 *    ② 复用 {@code ConditionalPreprocessor.BoolExpr}（否决：那条口径把「未定义标识符」当 0，
 *       对本条正是 X9 禁止的「猜值」形态）。
 * 3. 我们的差异点：
 *    ① <b>失败不猜值</b>：未知函数 / 未解析输入 / 循环依赖 / 非有限结果 ⇒ 整条定义跳过，
 *       并把原因记进 {@link PackUniformSet.Outcome#skips()}（X11 不许静默）；
 *       调用方据此**一次性 WARN**，不产出 0；
 *    ② <b>{@code if} 的分支必须短路</b>（其余运算符两侧恒定求值）：非短路版会把未命中支里的
 *       {@code smooth()} 也跑一遍 ⇒ 凭空推进一个从不生效的平滑槽位状态；
 *    ③ 函数表里没有的调用一律点名，<b>不回落成「按标识符求值」</b> —— 那会把拼错的函数名
 *       变成「未解析输入」，判读时看不出是打错还是真缺供值；
 *    ④ {@code ==} 用精确相等（文档另有 {@code equals(a,b,epsilon)} 负责容差比较 ⇒
 *       {@code ==} 若也带容差就是两套冲突口径，属猜）；
 *    ⑤ 向量参数（{@code vec3(...)} 构造 / 非标量喂标量函数）不求值：类型在
 *       {@link PackUniformDefinition} 就被拒，BSL 的 28+13 行全是 {@code float}（实测核实）；
 *    ⑥ {@code random()} / {@code randomInt()}（文档有）**不实现** —— 每帧随机数没有可复现的
 *       种子口径，单测无法自证，BSL 也没用；引用它的表达式会得到「未知函数」这条点名，
 *       比悄悄给一个不可复现的值诚实（X9/X11）。
 * 4. 许可证核对结论：本项目 MIT；零第三方代码并入。
 * 5. 性能基线：每帧一次、几十次 double 运算 + 常数字典查表；不做优化（T14 达标即停，
 *    且这条量级远低于 17-NATIVE §2 的 +2% 帧时间预算）。
 */
final class PackUniformEvaluator {

    /** 文档口径：smooth 的 fade 时间默认 1 游戏刻。 */
    private static final double DEFAULT_HALF_LIFE_TICKS = 1.0;

    private final Map<String, PackUniformDefinition> definitions;
    private final PackUniformInputs inputs;
    private final Map<String, Double> resolved = new LinkedHashMap<>();
    private final Set<String> failed = new LinkedHashSet<>();
    private final Deque<String> stack = new ArrayDeque<>();
    private final List<String> skips = new ArrayList<>();
    private final Set<String> skippedNames = new LinkedHashSet<>();
    private final List<String> unresolvedInputs = new ArrayList<>();

    /** 当前正在求值的定义名（{@code smooth} 无 id 时的槽位命名空间）。 */
    private String currentName = "";
    /** 当前定义内第几个无 id 的 smooth 槽位（每帧顺序固定 ⇒ 槽位稳定）。 */
    private int smoothCursor;

    PackUniformEvaluator(Map<String, PackUniformDefinition> definitions, PackUniformInputs inputs) {
        this.definitions = definitions;
        this.inputs = inputs;
    }

    /**
     * 求值全部定义。
     *
     * @return 名字 → 上传值（只含 {@code uniform.*}，{@code variable.*} 按文档不上传）
     */
    Map<String, Object> evaluateAll() {
        Map<String, Object> values = new LinkedHashMap<>();
        for (PackUniformDefinition definition : definitions.values()) {
            Double value = resolveDefinition(definition);
            if (value != null && definition.kind() == PackUniformDefinition.Kind.UNIFORM) {
                values.put(definition.name(), definition.toUploadValue(value));
            }
        }
        return values;
    }

    List<String> skips() {
        return List.copyOf(skips);
    }

    List<String> unresolvedInputs() {
        return List.copyOf(unresolvedInputs);
    }

    // ------------------------------------------------------------------------ 定义层（依赖序）

    /**
     * 求一条定义（记忆化 + 断环 + 失败点名）。
     *
     * <p>🔖 返回 {@code null} 而不是抛：一条坏表达式只该让它**自己**和依赖它的定义消失，
     * 不该让整包加载失败（T11 的降级要看得见，不是不降级）。
     */
    private Double resolveDefinition(PackUniformDefinition definition) {
        String name = definition.name();
        Double memo = resolved.get(name);
        if (memo != null) {
            return memo;
        }
        if (failed.contains(name)) {
            return null;
        }
        if (stack.contains(name)) {
            skip(name, "循环依赖 " + chain() + " -> " + name);
            failed.add(name);
            return null;
        }
        if (!definition.type().supported()) {
            skip(name, "暂不支持求值的声明类型 " + definition.type());
            failed.add(name);
            return null;
        }
        // 🔖 槽位命名空间必须跟着**正在求值的那条定义**走：uniform A 里嵌套解析变量 B 时，
        //   B 内部的无 id smooth 若记到 A 名下，两个 B-A 调用点会互相踩状态（同 QD-02 那族
        //   「一份状态被两个主人共用」）。存档/还原 = 每条定义有自己的槽位序列。
        String outerName = currentName;
        int outerCursor = smoothCursor;
        currentName = name;
        smoothCursor = 0;
        stack.addLast(name);
        Double value = evaluateBody(definition);
        stack.removeLast();
        currentName = outerName;
        smoothCursor = outerCursor;
        if (value == null) {
            failed.add(name);
        } else {
            resolved.put(name, value);
        }
        return value;
    }

    private Double evaluateBody(PackUniformDefinition definition) {
        try {
            double value = evaluateExpr(definition.expression());
            if (!Double.isFinite(value)) {
                throw new EvalException("结果不是有限数（" + value + "）");
            }
            return value;
        } catch (EvalException e) {
            skip(definition.name(), e.getMessage());
            return null;
        }
    }

    private String chain() {
        return String.join(" -> ", stack);
    }

    /**
     * 记一条跳过理由。
     *
     * <p>🔖 同名只记**第一个**理由：环上的一个节点在一次自顶向下的求值里会被访问两次
     * （「a 依赖 b」与「b 依赖 a」是同一件事的两面），第二次的措辞（「依赖未求出」）比第一次
     * （「循环依赖 a -> b -> a」）信息量低 —— WARN 里留最准的那条，别把 2 个问题刷屏成 3 行。
     */
    private void skip(String name, String reason) {
        if (skippedNames.add(name)) {
            skips.add(name + ": " + reason);
        }
    }

    // ------------------------------------------------------------------------ 表达式层

    private double evaluateExpr(UniformExpr expr) {
        return switch (expr) {
            case UniformExpr.Literal literal -> literal.value();
            case UniformExpr.Name name -> readName(name);
            case UniformExpr.Unary unary -> evalUnary(unary);
            case UniformExpr.Binary binary -> evalBinary(binary);
            case UniformExpr.Call call -> evalCall(call);
        };
    }

    /** 标识符读数：先前定义（variable/uniform）优先，其次引擎内建；取不到就整条跳过。 */
    private double readName(UniformExpr.Name node) {
        if (node.element() != UniformExpr.NO_ELEMENT) {
            Double component = inputs.vectorComponent(node.name(), node.element());
            if (component != null) {
                return component;
            }
            throw new EvalException(inputs.isVector(node.name())
                    ? "向量 '" + node.name() + "' 没有第 " + node.element() + " 个分量"
                    : missingName(node.name()));
        }
        PackUniformDefinition definition = definitions.get(node.name());
        if (definition != null) {
            Double value = resolveDefinition(definition);
            if (value == null) {
                throw new EvalException("依赖 '" + node.name() + "' 未求出");
            }
            return value;
        }
        Double scalar = inputs.scalar(node.name());
        if (scalar != null) {
            return scalar;
        }
        if (inputs.isVector(node.name())) {
            throw new EvalException("'" + node.name() + "' 是向量，表达式要的是标量");
        }
        throw new EvalException(missingName(node.name()));
    }

    /** 点名未解析输入（同一条表达式里同一个名字只记一次，WARN 才不会糊成一坨）。 */
    private String missingName(String name) {
        if (!unresolvedInputs.contains(name)) {
            unresolvedInputs.add(name);
        }
        return "未解析的输入 '" + name + "'";
    }

    private double evalUnary(UniformExpr.Unary unary) {
        double value = evaluateExpr(unary.operand());
        return switch (unary.operator()) {
            case "-" -> -value;
            // 文档把 ! 列为布尔运算符；本求值器全程用 double（非零即真），与仓库里
            // ConditionalPreprocessor.BoolExpr 的口径一致。
            case "!" -> value == 0.0 ? 1.0 : 0.0;
            default -> throw new EvalException("未知一元运算符 '" + unary.operator() + "'");
        };
    }

    /**
     * 二元运算。两侧**恒定求值**（{@code &&}/{@code ||} 也不短路）——
     * 与仓库既有布尔表达式口径一致；短路只对 {@code if()} 有意义（见类级差异点②），
     * 因为那里的「分支」是值的选择、不是条件运算符。
     */
    private double evalBinary(UniformExpr.Binary binary) {
        double left = evaluateExpr(binary.left());
        double right = evaluateExpr(binary.right());
        return switch (binary.operator()) {
            case "+" -> left + right;
            case "-" -> left - right;
            case "*" -> left * right;
            case "/" -> left / right;
            // 文档把 % 列为 float/int/vec 的「remainder」⇒ 用 Java 的 double 取余（= C 的 fmod 语义）。
            case "%" -> left % right;
            case "<" -> left < right ? 1.0 : 0.0;
            case "<=" -> left <= right ? 1.0 : 0.0;
            case ">" -> left > right ? 1.0 : 0.0;
            case ">=" -> left >= right ? 1.0 : 0.0;
            case "==" -> left == right ? 1.0 : 0.0;
            case "!=" -> left != right ? 1.0 : 0.0;
            case "&&" -> left != 0.0 && right != 0.0 ? 1.0 : 0.0;
            case "||" -> left != 0.0 || right != 0.0 ? 1.0 : 0.0;
            default -> throw new EvalException("未知运算符 '" + binary.operator() + "'");
        };
    }

    // ------------------------------------------------------------------------ 函数表

    /**
     * 函数调用总分派。
     *
     * <p>🔖 分三档（{@link #controlFlowCall} / {@link #transcendentalCall} /
     * {@link #shapingCall}）不是排版偏好：一张平表过长就会让人想「顺手加一个 case 就行」，
     * 而本项目的规则要求每条函数语义都能被点名（arity 与未知函数走同一套异常路径），
     * 分档后每一档都读得完、也守得住 QD-04 的方法长度棘轮。
     */
    private double evalCall(UniformExpr.Call call) {
        List<UniformExpr> args = call.arguments();
        String function = call.function();
        return switch (function) {
            case "if" -> conditional(args);
            case "in" -> inAny(args, function);
            case "smooth" -> smooth(args);
            case "between" -> between(args, function);
            case "equals" -> equalsTolerant(args, function);
            default -> transcendentalCall(function, args);
        };
    }

    /** 指数/对数/三角/角度换算（文档：sin cos tan asin acos atan atan2 exp exp2 exp10 log log2 log10 sqrt pow torad todeg）。 */
    private double transcendentalCall(String function, List<UniformExpr> args) {
        return switch (function) {
            case "sin" -> Math.sin(only(args, function));
            case "cos" -> Math.cos(only(args, function));
            case "tan" -> Math.tan(only(args, function));
            case "asin" -> Math.asin(only(args, function));
            case "acos" -> Math.acos(only(args, function));
            case "atan" -> atan(args, function);
            case "atan2" -> {
                require(args.size() == 2, function, 2, args.size());
                yield Math.atan2(at(args, 0, function), at(args, 1, function));
            }
            case "exp" -> Math.exp(only(args, function));
            case "exp2" -> Math.pow(2.0, only(args, function));
            case "exp10" -> Math.pow(10.0, only(args, function));
            case "log" -> log(args, function);
            case "log2" -> Math.log(at(args, 0, function)) / Math.log(2.0);
            case "log10" -> Math.log10(at(args, 0, function));
            case "sqrt" -> Math.sqrt(only(args, function));
            case "pow" -> {
                require(args.size() == 2, function, 2, args.size());
                yield Math.pow(at(args, 0, function), at(args, 1, function));
            }
            case "torad", "radians" -> Math.toRadians(only(args, function));
            case "todeg", "degrees" -> Math.toDegrees(only(args, function));
            default -> shapingCall(function, args);
        };
    }

    /** 取整/夹取/插值（BSL 实际用到的就是这一档：abs / max / min / clamp / floor / frac）。 */
    private double shapingCall(String function, List<UniformExpr> args) {
        return switch (function) {
            case "abs" -> Math.abs(only(args, function));
            case "sign", "signum" -> Math.signum(only(args, function));
            case "floor" -> Math.floor(only(args, function));
            case "ceil" -> Math.ceil(only(args, function));
            // 文档：frac = 「小数部分」⇒ x - floor(x)（负数落回 [0,1)）。
            // 🔖 这条口径对 BSL 是**决定性的**：tAmin=frac(sunAngle-0.0333) 在拂晓
            //   （sunAngle<0.0333）时必须是接近 1 的绕回值，而不是负数。
            case "frac" -> PackUniformInputs.fractionalCycle(only(args, function));
            case "min" -> {
                require(args.size() == 2, function, 2, args.size());
                yield Math.min(at(args, 0, function), at(args, 1, function));
            }
            case "max" -> {
                require(args.size() == 2, function, 2, args.size());
                yield Math.max(at(args, 0, function), at(args, 1, function));
            }
            case "clamp" -> {
                require(args.size() == 3, function, 3, args.size());
                yield Math.min(Math.max(at(args, 0, function), at(args, 1, function)),
                        at(args, 2, function));
            }
            case "mix" -> {
                require(args.size() == 3, function, 3, args.size());
                double x = at(args, 0, function);
                double y = at(args, 1, function);
                double a = at(args, 2, function);
                yield x + (y - x) * a;
            }
            // 文档：edge(k, x) = 「0 if x < k, otherwise 1」（= GLSL step）⇒ 第 0 参是门槛 k，
            //   第 1 参才是被量的 x。写成 x<k 就反了（单测 coreFunctions 钉住这条）。
            case "edge" -> {
                require(args.size() == 2, function, 2, args.size());
                yield at(args, 1, function) < at(args, 0, function) ? 0.0 : 1.0;
            }
            case "fmod" -> {
                require(args.size() == 2, function, 2, args.size());
                double x = at(args, 0, function);
                double y = at(args, 1, function);
                yield x - y * Math.floor(x / y);
            }
            default -> throw new EvalException("未知函数 '" + function + "'");
        };
    }

    /** {@code between(a, min, max)}：闭区间（文档明写 inclusive）。 */
    private double between(List<UniformExpr> args, String function) {
        require(args.size() == 3, function, 3, args.size());
        double a = at(args, 0, function);
        return a >= at(args, 1, function) && a <= at(args, 2, function) ? 1.0 : 0.0;
    }

    /** {@code equals(a, b, epsilon)}：容差比较（文档明写 abs(a-b) <= epsilon）。 */
    private double equalsTolerant(List<UniformExpr> args, String function) {
        require(args.size() == 3, function, 3, args.size());
        double delta = Math.abs(at(args, 0, function) - at(args, 1, function));
        return delta <= at(args, 2, function) ? 1.0 : 0.0;
    }

    private double atan(List<UniformExpr> args, String function) {
        if (args.size() == 1) {
            return Math.atan(evaluateExpr(args.get(0)));
        }
        require(args.size() == 2, function, 1, args.size());
        return Math.atan2(evaluateExpr(args.get(0)), evaluateExpr(args.get(1)));
    }

    /** 文档给了两种形态：{@code log(x)}=自然对数、{@code log(base, value)}=任意底 —— 参数顺序不同。 */
    private double log(List<UniformExpr> args, String function) {
        if (args.size() == 1) {
            return Math.log(evaluateExpr(args.get(0)));
        }
        require(args.size() == 2, function, 1, args.size());
        double base = evaluateExpr(args.get(0));
        return Math.log(evaluateExpr(args.get(1))) / Math.log(base);
    }

    /** {@code if(cond, val, [cond2, val2, …], val_else)}：命中即返回，**其余分支不求值**。 */
    private double conditional(List<UniformExpr> args) {
        if (args.size() < 3 || args.size() % 2 == 0) {
            throw new EvalException("if 需要奇数个且至少 3 个参数（实际 " + args.size() + "）");
        }
        for (int i = 0; i + 1 < args.size(); i += 2) {
            if (evaluateExpr(args.get(i)) != 0.0) {
                return evaluateExpr(args.get(i + 1));
            }
        }
        return evaluateExpr(args.get(args.size() - 1));
    }

    /** {@code in(x, v1, v2, …)}：x 等于任一值 → 1，否则 0（BSL 的生物群集分支就靠它）。 */
    private double inAny(List<UniformExpr> args, String function) {
        if (args.size() < 2) {
            throw new EvalException(function + " 需要至少 2 个参数（实际 " + args.size() + "）");
        }
        double subject = evaluateExpr(args.get(0));
        for (int i = 1; i < args.size(); i++) {
            if (evaluateExpr(args.get(i)) == subject) {
                return 1.0;
            }
        }
        return 0.0;
    }

    // ------------------------------------------------------------------------ smooth（跨帧状态）

    /**
     * {@code smooth([id], val, [fadeUpTime, [fadeDownTime]])}。
     *
     * <p>⚠️ 登记（近似 v1）：文档只给了「半衰期、单位=游戏刻、默认 1 刻」这一条语义，
     * Iris 的逐帧系数写法未取证（源码禁读）。这里用**半衰期的定义式**
     * {@code v += (target − v) × (1 − 0.5^(Δticks / 半衰期))} —— 它在「过 半衰期 刻 ⇒ 走完剩余量的一半」
     * 这条要求下是唯一形式，不是可选拟合；与 Iris 的差别只可能在一帧内的插值精度。
     *
     * <p>⚠️ 形态消歧（文档歧义，本仓库自有裁决）：2/3 参时「{@code [id]} 打头」与
     * 「不带 id」两种读法在文档里同形。规则 = 4 参必为 id 形态；否则仅当
     * 首参是字面量且次参不是时才当 id。BSL 用的是 4 参形态（实测 11 处），不受歧义影响。
     */
    private double smooth(List<UniformExpr> args) {
        if (args.isEmpty()) {
            throw new EvalException("smooth 至少需要 1 个参数");
        }
        boolean hasId = useIdSlot(args);
        int offset = hasId ? 1 : 0;
        int valueCount = args.size() - offset;
        if (valueCount < 1 || valueCount > 3) {
            throw new EvalException("smooth 的参数个数非法（" + args.size() + "）");
        }
        String slot = slotFor(args, hasId, offset);
        double target = evaluateExpr(args.get(offset));
        double fadeUp = valueCount >= 2 ? evaluateExpr(args.get(offset + 1)) : DEFAULT_HALF_LIFE_TICKS;
        double fadeDown = valueCount >= 3 ? evaluateExpr(args.get(offset + 2)) : fadeUp;
        return advanceSmooth(slot, target, fadeUp, fadeDown);
    }

    private boolean useIdSlot(List<UniformExpr> args) {
        // 4 参 = 文档的完整形态 (id, val, fadeUp, fadeDown)；2/3 参仅在「首参是字面量而次参不是」
        // 时才认 id（`smooth(1.0, 5)` 这种全字面量必须按 (val, fade) 读，否则 5 会被当 val）。
        if (args.size() < 2 || args.size() > 4 || !(args.get(0) instanceof UniformExpr.Literal)) {
            return false;
        }
        return !(args.get(1) instanceof UniformExpr.Literal);
    }

    /** 槽位命名：带 id 时 id 就是全局槽位（OF 让包自己保证唯一），否则退到「定义名 + 本帧第几个」。 */
    private String slotFor(List<UniformExpr> args, boolean hasId, int offset) {
        if (hasId && args.get(0) instanceof UniformExpr.Literal id) {
            return "id:" + (long) id.value();
        }
        return currentName + "#" + smoothCursor++;
    }

    private double advanceSmooth(String slot, double target, double fadeUp, double fadeDown) {
        Double previous = inputs.smoothPrevious(slot);
        if (previous == null) {
            // 首帧没有历史：直取目标值。用 0 起步等于猜一个初值（X9），
            // 而且会让 isCold 这类进世界后半秒内恒 0 —— 画面上的表现是「旗帜色慢慢渗出来」。
            inputs.smoothStore(slot, target);
            return target;
        }
        double halfLife = target > previous ? fadeUp : fadeDown;
        if (halfLife <= 0.0) {
            inputs.smoothStore(slot, target);
            return target;
        }
        double factor = 1.0 - Math.pow(0.5, inputs.deltaTicks() / halfLife);
        double next = previous + (target - previous) * factor;
        inputs.smoothStore(slot, next);
        return next;
    }

    // ------------------------------------------------------------------------ 参数把关

    /** 取第 index 个参数；越界即「参数个数不对」，绝不补默认值（X9）。 */
    private double at(List<UniformExpr> args, int index, String function) {
        if (index >= args.size()) {
            throw new EvalException(function + " 缺少第 " + (index + 1) + " 个参数（实际 "
                    + args.size() + " 个）");
        }
        return evaluateExpr(args.get(index));
    }

    /** 单参函数：多出来的参数必须被点名，不能被忽略（忽略 = 静默改变语义）。 */
    private double only(List<UniformExpr> args, String function) {
        require(args.size() == 1, function, 1, args.size());
        return evaluateExpr(args.get(0));
    }

    private void require(boolean ok, String function, int expected, int actual) {
        if (!ok) {
            throw new EvalException(function + " 需要 " + expected + " 个参数（实际 " + actual + "）");
        }
    }

    /**
     * 单条定义的求值失败（未知函数 / 未解析输入 /  arity / 非有限数）。
     *
     * <p>包私有且只在本类内部抛出并被 {@link #evaluateBody} 接住 ⇒ 对调用方而言
     * 「一条坏表达式」是**数据**（{@code skips} 列表），不是异常。
     */
    private static final class EvalException extends RuntimeException {
        EvalException(String message) {
            super(message);
        }
    }
}
