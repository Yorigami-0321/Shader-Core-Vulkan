package dev.vkdisp.pack.uniform;
/**
 * 【参考调研】GAP-021 表达式求值器单测（语法 / 函数表 / 失败必须点名）
 * 0. 合规核对（第 0 步闸门，通过）：
 *    参考对象 = shaders.properties 官方参考「Custom Uniforms」页的语法与函数清单（只读事实，
 *    零代码复制）；期望值全部由本人按文档语义手算写出，不复用被测实现的任何中间结果
 *    （18-PARALLEL §7.6「独立硬编码断言」纪律）。
 *    本文件不含任何第三方着色器包片段。
 *    许可证：本项目 MIT；零第三方代码并入。
 * 1. 主实现：{@link PackUniformSet#fromProperties} → {@link PackUniformSet#evaluate}。
 * 2. 备选：无（{@code gather()} 那条依赖 Minecraft 运行态的车道由本类的纯函数等价物覆盖，
 *    实体类在这条测试运行时里会 {@code NoClassDefFoundError} —— 见 NightVisionSupply 的隔离理由）。
 * 3. 差异点：断言重点是「坏表达式必须进 skips 且不产出值」，不是「坏表达式返回 0」。
 * 4. 性能基线：❄️ 单测。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PackUniformExpressionTest {

    private static final double EPS = 1.0e-6;

    /** 求值一批定义（键 = properties 原样键）。 */
    private static PackUniformSet.Outcome run(Map<String, String> properties,
            PackUniformInputs inputs) {
        return PackUniformSet.fromProperties(properties).evaluate(inputs);
    }

    private static Map<String, String> props(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static PackUniformInputs inputs() {
        return new PackUniformInputs(1.0, new HashMap<>());
    }

    private static double number(Map<String, Object> values, String name) {
        Object value = values.get(name);
        assertNotNull(value, "值必须存在（不存在 = 被跳过，见 skips）：" + name);
        assertTrue(value instanceof Number, name + " 必须是数，实际 " + value);
        return ((Number) value).doubleValue();
    }

    @Test
    @DisplayName("算术与比较的优先级：乘除先于加减，比较最后（BSL 的 if 条件就靠这个）")
    void arithmeticPrecedenceMatchesDoc() {
        Map<String, Object> values = run(
                props("uniform.float.a", "2.0 + 3.0 * 4.0",
                        "uniform.float.b", "(2.0 + 3.0) * 4.0",
                        "uniform.float.c", "1.0 - 2.0 * 3.0",
                        "uniform.float.d", "if(1.0 + 1.0 > 2.5, 100, 200)"),
                inputs()).values();
        assertEquals(14.0, number(values, "a"), EPS);
        assertEquals(20.0, number(values, "b"), EPS);
        assertEquals(-5.0, number(values, "c"), EPS);
        assertEquals(200.0, number(values, "d"), EPS);
    }

    @Test
    @DisplayName("取余与整除按文档：frameCounter % 8（BSL 的 jitter 帧序）")
    void remainderOperator() {
        Map<String, Object> values = run(
                props("uniform.float.framemod8", "frameCounter % 8",
                        "uniform.float.framemod2", "frameCounter % 2"),
                inputs().scalar("frameCounter", 21)).values();
        assertEquals(5.0, number(values, "framemod8"), EPS);
        assertEquals(1.0, number(values, "framemod2"), EPS);
    }

    @Test
    @DisplayName("frac = x − floor(x)：负数绕回 [0,1)（BSL 拂晓段的 tAmin 依赖这条）")
    void fracWrapsNegatives() {
        Map<String, Object> values = run(
                props("uniform.float.a", "frac(-0.033333333)",
                        "uniform.float.b", "frac(1.75)",
                        "uniform.float.c", "floor(-1.25)"),
                inputs()).values();
        assertEquals(0.966666667, number(values, "a"), EPS);
        assertEquals(0.75, number(values, "b"), EPS);
        assertEquals(-2.0, number(values, "c"), EPS);
    }

    @Test
    @DisplayName("clamp / abs / max / min / smooth 的文档语义")
    void coreFunctions() {
        Map<String, Object> values = run(
                props("uniform.float.clamped", "clamp(24.0, 0.0, 1.0)",
                        "uniform.float.negative", "clamp(-3.0, 0.0, 1.0)",
                        "uniform.float.absolute", "abs(abs(0.25 - 0.5) - 0.25)",
                        "uniform.float.maximum", "max(sin(1.57079632679), 0.0)",
                        "uniform.float.minimum", "min(3.0, 1.5)",
                        "uniform.float.mixed", "mix(10.0, 20.0, 0.25)",
                        "uniform.float.edged", "edge(0.5, 0.75)",
                        "uniform.float.modded", "fmod(7.5, 2.0)",
                        "uniform.float.signed", "sign(-4.0)",
                        "uniform.float.turned", "torad(180.0)"),
                inputs()).values();
        assertEquals(1.0, number(values, "clamped"), EPS);
        assertEquals(0.0, number(values, "negative"), EPS);
        assertEquals(0.0, number(values, "absolute"), EPS);
        assertEquals(1.0, number(values, "maximum"), EPS);
        assertEquals(1.5, number(values, "minimum"), EPS);
        assertEquals(12.5, number(values, "mixed"), EPS);
        assertEquals(1.0, number(values, "edged"), EPS);
        assertEquals(1.5, number(values, "modded"), EPS);
        assertEquals(-1.0, number(values, "signed"), EPS);
        assertEquals(Math.PI, number(values, "turned"), EPS);
    }

    @Test
    @DisplayName("if 是变长的（文档：if(cond,val,[cond2,val2,…],else)），且非命中分支不求值")
    void conditionalIsVariadicAndShortCircuits() {
        // 第二支的 `nope` 必须**不被求值**：短路失效就会把它记进 unresolvedInputs。
        PackUniformInputs probe = inputs().scalar("pick", 1.0);
        PackUniformSet.Outcome outcome = run(
                props("uniform.float.a", "if(pick == 1.0, 10, in(nope, 1, 2), 20, 30)"), probe);
        assertEquals(10.0, number(outcome.values(), "a"), EPS);
        assertEquals(List.of(), outcome.skips(), "短路后不得有跳过");
        assertEquals(List.of(), outcome.unresolvedInputs(), "非命中分支不得被求值");

        Map<String, Object> falling = run(
                props("uniform.float.a", "if(pick == 9.0, 10, in(nope, 1, 2), 20, 30)"),
                inputs().scalar("pick", 0.0).scalar("nope", 2.0)).values();
        assertEquals(20.0, number(falling, "a"), EPS, "in() 命中第二支");
    }

    @Test
    @DisplayName("in(x, v1, v2, …) 任一相等为真（BSL 的生物群集判定的形状）")
    void inAnyEquals() {
        Map<String, Object> values = run(
                props("uniform.float.hit", "in(biomeId, 2, 17, 130)",
                        "uniform.float.miss", "in(biomeId, 5, 6)"),
                inputs().scalar("biomeId", 17.0)).values();
        assertEquals(1.0, number(values, "hit"), EPS);
        assertEquals(0.0, number(values, "miss"), EPS);
    }

    @Test
    @DisplayName("向量分量访问：cameraPosition.y（BSL 的 yCold 三条全靠它）")
    void vectorElementAccess() {
        Map<String, Object> values = run(
                props("uniform.float.up", "if(cameraPosition.y >= 113.0, 1, 0)",
                        "uniform.float.side", "cameraPosition.0 + cameraPosition.2"),
                inputs().vector("cameraPosition", new double[] {1.5, 200.0, 2.5})).values();
        assertEquals(1.0, number(values, "up"), EPS);
        assertEquals(4.0, number(values, "side"), EPS);
    }

    @Test
    @DisplayName("smooth：首帧直取目标（不猜初值），之后按半衰期推进（文档：单位=游戏刻）")
    void smoothAdvancesByHalfLife() {
        Map<String, String> definitions = props(
                "uniform.float.s", "smooth(1, target, 10, 10)");
        PackUniformSet set = PackUniformSet.fromProperties(definitions);
        Map<String, Double> state = new HashMap<>();

        Map<String, Object> first = set.evaluate(
                new PackUniformInputs(10.0, state).scalar("target", 1.0)).values();
        assertEquals(1.0, number(first, "s"), EPS, "首帧没有历史 ⇒ 取目标值，不是从 0 爬");

        Map<String, Object> second = set.evaluate(
                new PackUniformInputs(10.0, state).scalar("target", 0.0)).values();
        assertEquals(0.5, number(second, "s"), EPS, "过 10 刻 = 一个半衰期 ⇒ 走完剩余量的一半");

        Map<String, Object> third = set.evaluate(
                new PackUniformInputs(10.0, state).scalar("target", 0.0)).values();
        assertEquals(0.25, number(third, "s"), EPS);

        // 无 id 形态（1 参）：默认半衰期 1 刻 ⇒ 走 1 刻正好一半。
        PackUniformInputs plain = new PackUniformInputs(1.0, state);
        plain.scalar("v", 0.0);
        Map<String, Object> defaulted = PackUniformSet.fromProperties(
                props("uniform.float.p", "smooth(v)")).evaluate(plain).values();
        assertEquals(0.0, number(defaulted, "p"), EPS, "首帧 = 目标");
    }

    @Test
    @DisplayName("smooth 的下降半衰期与上升不同（文档：fadeUpTime / fadeDownTime 分离）")
    void smoothUsesDirectionalHalfLife() {
        Map<String, Double> state = new HashMap<>();
        PackUniformSet set = PackUniformSet.fromProperties(
                props("uniform.float.s", "smooth(7, target, 2, 1)"));
        set.evaluate(new PackUniformInputs(1.0, state).scalar("target", 0.0));
        Map<String, Object> rising = set.evaluate(
                new PackUniformInputs(1.0, state).scalar("target", 1.0)).values();
        // 上升半衰期 2 刻走 1 刻 ⇒ 1 − 0.5^(1/2) ≈ 0.29289 的推进量。
        assertEquals(1.0 - Math.pow(0.5, 0.5), number(rising, "s"), EPS);
    }

    @Test
    @DisplayName("依赖序：uniform 可引用后定义的 variable（BSL 的 isCold 引用稍后的 yCold2/3）")
    void dependencyOrderIgnoresDeclarationOrder() {
        Map<String, Object> values = run(
                props("uniform.float.a", "b * 2.0", "variable.float.b", "3.0"),
                inputs()).values();
        assertEquals(6.0, number(values, "a"), EPS);
        assertFalse(values.containsKey("b"), "variable 按文档不上传");
    }

    @Test
    @DisplayName("声明类型：float→Float、int→Integer、bool→Boolean")
    void declaredTypesLandOnTypedValues() {
        PackUniformSet.Outcome outcome = run(
                props("uniform.float.f", "2.7", "uniform.int.i", "2.7",
                        "uniform.bool.b", "in(1, 1, 2)"), inputs());
        assertEquals(2.7f, outcome.values().get("f"));
        assertEquals(2, outcome.values().get("i"), "int 口径 = 向零截断（文档未给转换规则，X9 登记）");
        assertEquals(Boolean.TRUE, outcome.values().get("b"));
    }

    @Test
    @DisplayName("未知函数 ⇒ 整条跳过 + 点名，不产出 0（X9/X11）")
    void unknownFunctionSkipsWithReason() {
        PackUniformSet.Outcome outcome = run(
                props("uniform.float.bad", "frobnicate(1.0)"), inputs());
        assertFalse(outcome.values().containsKey("bad"), "坏表达式不许落进上传映射");
        assertEquals(1, outcome.skips().size(), outcome.skips().toString());
        assertTrue(outcome.skips().get(0).contains("frobnicate"), outcome.skips().get(0));
    }

    @Test
    @DisplayName("未解析输入 ⇒ 整条跳过 + 名字进 unresolvedInputs（不静默当 0）")
    void unresolvedIdentifierSkipsAndIsNamed() {
        PackUniformSet.Outcome outcome = run(
                props("uniform.float.a", "1.0 + mysteryValue",
                        "uniform.float.b", "a * 2.0"), inputs());
        assertTrue(outcome.unresolvedInputs().contains("mysteryValue"),
                outcome.unresolvedInputs().toString());
        assertFalse(outcome.values().containsKey("a"));
        assertTrue(outcome.skips().stream().anyMatch(s -> s.startsWith("b:") && s.contains("a")),
                "依赖失败的定义也要被点名：" + outcome.skips());
    }

    @Test
    @DisplayName("循环依赖不崩、不断链：两条都被点名，其余定义照常求出")
    void cyclesAreBrokenAndNamed() {
        PackUniformSet set = PackUniformSet.fromProperties(
                props("uniform.float.a", "b + 1.0", "variable.float.b", "a + 1.0",
                        "uniform.float.ok", "2.0 * 3.0"));
        PackUniformSet.Outcome outcome = set.evaluate(inputs());
        assertEquals(6.0, number(outcome.values(), "ok"), EPS, "坏定义不得连累别的");
        assertEquals(2, outcome.skips().size(), outcome.skips().toString());
        assertTrue(outcome.skips().get(0).contains("循环依赖"), outcome.skips().get(0));
    }

    @Test
    @DisplayName("非有限结果（除零）被点名，不上传 NaN/Infinity")
    void nonFiniteResultsAreSkipped() {
        PackUniformSet.Outcome outcome = run(
                props("uniform.float.z", "1.0 / 0.0"), inputs());
        assertFalse(outcome.values().containsKey("z"));
        assertTrue(outcome.skips().get(0).contains("有限数"), outcome.skips().get(0));
    }

    @Test
    @DisplayName("语法错在冷路径就被拒（rejections），不进 definitions、不抛")
    void syntaxErrorsBecomeRejections() {
        PackUniformSet set = PackUniformSet.fromProperties(
                props("uniform.float.good", "1.0 + 2.0",
                        "uniform.float.paren", "clamp(1.0, 0.0",
                        "uniform.float.char", "1.0 $ 2.0",
                        "uniform.float.ternary", "1.0 > 2.0 ? 3.0 : 4.0",
                        "uniform.string.weird", "abc"));
        assertEquals(1, set.size(), set.rejections().toString());
        assertEquals(4, set.rejections().size(), set.rejections().toString());
        assertTrue(set.rejections().stream().anyMatch(r -> r.startsWith("uniform.float.ternary")),
                "三元不是文档语法、BSL 也没用 ⇒ 必须被拒而不是被猜：" + set.rejections());
        assertTrue(set.rejections().stream().anyMatch(r -> r.startsWith("uniform.string.weird")),
                "文档之外的类型必须被拒：" + set.rejections());
    }

    @Test
    @DisplayName("函数参数个数不对要报错，不能默认补值（X9）")
    void wrongArityIsNamed() {
        PackUniformSet.Outcome outcome = run(
                props("uniform.float.a", "clamp(1.0, 0.0)",
                        "uniform.float.b", "abs(1.0, 2.0)",
                        "uniform.float.c", "if(1.0, 2.0)"), inputs());
        assertEquals(3, outcome.skips().size(), outcome.skips().toString());
        assertTrue(outcome.values().isEmpty(), outcome.values().toString());
    }

    @Test
    @DisplayName("pi / true / false 是字面量，不是「未解析输入」（文档 Literals 一条）")
    void literalsFromDoc() {
        Map<String, Object> values = run(
                props("uniform.float.a", "sin(2.0 * pi * sunAngle)",
                        "uniform.float.c", "if(false, 1.0, 2.0)"),
                inputs().scalar("sunAngle", 0.25)).values();
        assertEquals(1.0, number(values, "a"), EPS, "sunAngle=0.25（正午）⇒ sin(π/2)=1");
        assertEquals(2.0, number(values, "c"), EPS);
    }

    @Test
    @DisplayName("标量表达式引用向量整体 ⇒ 类型错点名（不是取 .x，也不是取 0）")
    void vectorAsScalarIsNamed() {
        PackUniformSet.Outcome outcome = run(
                props("uniform.float.a", "cameraPosition * 2.0"),
                inputs().vector("cameraPosition", new double[] {1.0, 2.0, 3.0}));
        assertTrue(outcome.skips().get(0).contains("向量"), outcome.skips().get(0));
    }

    @Test
    @DisplayName("OF 覆盖语义：包自写的同名 uniform 压过引擎内建（BSL 自己写 timeAngle）")
    void packValuesOverrideBuiltinValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("timeAngle", 0.1F);
        values.put("far", 160.0F);
        PackUniformSet set = PackUniformSet.fromProperties(
                props("uniform.float.timeAngle", "worldTime / 24000.0"));
        PackUniformSet.mergeOverrides(values, set.evaluate(
                new PackUniformInputs(1.0, new HashMap<>()).scalar("worldTime", 12000)));
        assertEquals(0.5F, values.get("timeAngle"), "包算出的值必须替换内建值");
        assertEquals(160.0F, values.get("far"), "没被包声明的内建不许被动");
    }

    @Test
    @DisplayName("空集 / null 输入不炸（未选包与兜底路径的形状）")
    void emptySetAndNullTolerantInputs() {
        assertTrue(PackUniformSet.EMPTY.isEmpty());
        PackUniformSet.Outcome outcome = PackUniformSet.EMPTY.evaluate(inputs());
        assertTrue(outcome.values().isEmpty());
        assertTrue(outcome.skips().isEmpty());
        PackUniformSet.mergeOverrides(null, outcome);
        PackUniformSet.mergeOverrides(new LinkedHashMap<>(), null);
        assertEquals(0, PackUniformSet.fromProperties(null).size());
    }

    @Test
    @DisplayName("sunAngle 归一：正午 0.25 / 日落 0.5 / 午夜 0.75 / 日出 0.0")
    void sunAngleMappingMatchesBothSources() {
        // 出处：原版 SUN_ANGLE 度数（EnvironmentAttributes.java:74-76，正午=0°/360°，
        // Timelines.java:53 的两个关键帧）+ OF 文档的 0=东地平线 / 0.25=正上方 / 0.5=西地平线。
        assertEquals(0.25, PackUniformInputs.sunAngleFromDegrees(0.0), EPS);
        assertEquals(0.5, PackUniformInputs.sunAngleFromDegrees(90.0), EPS);
        assertEquals(0.75, PackUniformInputs.sunAngleFromDegrees(180.0), EPS);
        assertEquals(0.0, PackUniformInputs.sunAngleFromDegrees(270.0), EPS);
        assertEquals(0.25, PackUniformInputs.sunAngleFromDegrees(360.0), EPS);
        assertEquals(0.25, PackUniformInputs.sunAngleFromDegrees(-720.0), EPS, "负角也要绕回 [0,1)");
    }
}
