package dev.vkdisp.glsl.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link SampleFactorProbeAdapter} 的分离能力 —— 判「采样为 0」还是「乘子为 0」的守卫。
 *
 * <p>🔖 <b>为什么这两个开关必须分开</b>：albedo 是<b>乘积</b>，
 * 两侧任一为 0 结果都是 0。同时替换两侧等于什么都没替换 ——
 * 那种「两臂都没变」会被读成「两个因子都不是原因」，而真相是实验无效。
 */
class SampleFactorProbeAdapterTest {

    /** BSL 高级材质路径非视差赋值的实测形态（只作测试输入，不进产品路径）。 */
    private static final String REAL_ALBEDO_LINE =
            "\tvec4 albedo = texture2D(texture, texCoord) * vec4(color.rgb, 1.0);";

    @BeforeEach
    @AfterEach
    void reset() {
        SampleFactorProbeAdapter.setForceSample(false);
        SampleFactorProbeAdapter.setForceMultiplier(false);
    }

    private static String wrap(String body) {
        return "#version 410\nlayout(location = 0) out vec4 vkdispFragOut0;\n"
                + "void main() {\n" + body + "\n}\n";
    }

    @Test
    @DisplayName("🔖🔖 左侧探针只换采样侧，乘子逐字保留（单变量）")
    void sampleSideKeepsMultiplierIntact() {
        SampleFactorProbeAdapter.setForceSample(true);
        String out = SampleFactorProbeAdapter.apply(ShaderStage.FRAGMENT, wrap(REAL_ALBEDO_LINE)).text();
        assertTrue(out.contains("vec4(1.0, 0.5, 0.25, 1.0)"),
                "左侧必须被换成已知非零常量");
        assertTrue(out.contains("* vec4(color.rgb, 1.0)"),
                "乘子必须**逐字保留** —— 一起换掉就不是单变量实验了");
    }

    @Test
    @DisplayName("🔖🔖 右侧探针只换乘子，采样调用逐字保留（单变量）")
    void multiplierSideKeepsSampleIntact() {
        SampleFactorProbeAdapter.setForceMultiplier(true);
        String out = SampleFactorProbeAdapter.apply(ShaderStage.FRAGMENT, wrap(REAL_ALBEDO_LINE)).text();
        assertTrue(out.contains("texture2D(texture, texCoord)"),
                "采样调用必须逐字保留");
        assertTrue(out.contains("* vec4(1.0, 1.0, 1.0, 1.0)"),
                "乘子必须被换成单位元");
    }

    @Test
    @DisplayName("🔖🔖 两侧同时开启必须自报「无法分出是哪一侧」（不悄悄继续）")
    void bothSidesWarnAboutIndiscernibility() {
        SampleFactorProbeAdapter.setForceSample(true);
        SampleFactorProbeAdapter.setForceMultiplier(true);
        SampleFactorProbeAdapter.Result result =
                SampleFactorProbeAdapter.apply(ShaderStage.FRAGMENT, wrap(REAL_ALBEDO_LINE));
        assertTrue(result.diagnostics().stream()
                        .anyMatch(d -> d.message().contains("两侧同时开启")),
                "两侧同开时必须自报实验无效 —— 否则「画面没变」会被读成「两个因子都不是原因」");
        assertEquals(2, result.patched(), "两侧各命中一处（同一行改两次）");
    }

    @Test
    @DisplayName("🔖🔖 一处都没命中必须报 WARN 并说明「开关没生效 ≠ 结论不成立」")
    void noHitWarnsThatSwitchDidNotTake() {
        SampleFactorProbeAdapter.setForceSample(true);
        String source = wrap("\tvec4 albedo = vec4(1.0, 0.0, 0.0, 1.0); // 无乘法链");
        SampleFactorProbeAdapter.Result result =
                SampleFactorProbeAdapter.apply(ShaderStage.FRAGMENT, source);
        assertEquals(0, result.patched());
        assertTrue(result.diagnostics().stream()
                        .anyMatch(d -> d.message().contains("一处都没命中")),
                "零命中必须吵出来");
        assertTrue(result.diagnostics().stream()
                        .anyMatch(d -> d.message().contains("开关没生效")),
                "必须点明「开关没生效」与「结论不成立」是两件事（X45）");
    }

    @Test
    @DisplayName("🔖🔖 命中数不足以定位 → 必须自报命中行的**原文**")
    void hitLinesAreSelfReported() {
        SampleFactorProbeAdapter.setForceSample(true);
        SampleFactorProbeAdapter.Result result =
                SampleFactorProbeAdapter.apply(ShaderStage.FRAGMENT, wrap(REAL_ALBEDO_LINE));
        assertEquals(1, result.hitLines().size());
        assertTrue(result.hitLines().getFirst().contains("texture2D"),
                "自报必须含命中行原文 —— 只报「改了 N 处」时，N>0 也可能改在了无关的乘法上");
        assertTrue(result.hitLines().getFirst().contains("第 4 行"),
                "行号要能对上（包装后：1=#version / 2=out 声明 / 3=void main() { / 4=albedo 行）");
    }

    @Test
    @DisplayName("🔖🔖🔖 乘子探针也只认「左值是采样调用」的行（否则改坏标量乘法 → 片元编译失败）")
    void multiplierProbeAlsoRequiresSampleOnTheLeft() {
        // 🔴🔖 h45 实测：首版让乘子探针命中任何乘法赋值 ⇒ BSL 地形片元上命中 **124 处**，
        //   含 `float f = a * b;` 这类标量运算 ⇒ 改写成 `= a * vec4(1,1,1,1)` 是类型错误
        //   ⇒ ShaderCompileException、地形契约掉回原版。是「命中数自报」把它现形的。
        SampleFactorProbeAdapter.setForceMultiplier(true);
        String source = wrap(REAL_ALBEDO_LINE
                + "\n\tfloat f = a * b;"
                + "\n\tvec3 g = c * d;"
                + "\n\tvec4 h = e * g * f;");
        SampleFactorProbeAdapter.Result result =
                SampleFactorProbeAdapter.apply(ShaderStage.FRAGMENT, source);
        assertEquals(1, result.patchedMultiplier(),
                "只应命中 albedo 那一行；标量/其它乘法一律不动");
        assertTrue(result.text().contains("float f = a * b;"), "标量乘法必须逐字保留");
        assertTrue(result.text().contains("vec3 g = c * d;"), "vec3 乘法必须逐字保留");
        assertTrue(result.text().contains("vec4 h = e * g * f;"),
                "不含采样调用的乘法必须逐字保留（即使类型看起来对）");
        assertTrue(result.text().contains("* " + SampleFactorProbeAdapter.PROBE_MULTIPLIER),
                "albedo 那一行的乘子必须被换成单位元");
    }

    @Test
    @DisplayName("🔖🔖 命中数与预期不符时必须能被一眼看出（本轮就是靠它发现 124 处）")
    void hitCountIsSelfReported() {
        SampleFactorProbeAdapter.setForceMultiplier(true);
        SampleFactorProbeAdapter.Result result =
                SampleFactorProbeAdapter.apply(ShaderStage.FRAGMENT, wrap(REAL_ALBEDO_LINE));
        assertEquals(1, result.patched(),
                "单条 albedo 链应恰好命中 1 处 —— 与实测的巨大偏差正是缺陷信号");
        assertEquals(1, result.hitLines().size());
    }

    @Test
    @DisplayName("🔖 复合表达式（a = f(x) * g(y) + h(z)）**不**改写（宁可漏也不误匹配）")
    void compoundExpressionIsNotRewritten() {
        SampleFactorProbeAdapter.setForceSample(true);
        SampleFactorProbeAdapter.setForceMultiplier(true);
        String body = "\tvec4 albedo = texture2D(t, uv) * vec4(c.rgb, 1.0) + vec4(0.1);";
        SampleFactorProbeAdapter.Result result =
                SampleFactorProbeAdapter.apply(ShaderStage.FRAGMENT, wrap(body));
        assertEquals(0, result.patched(),
                "右值含顶层 '+' 时替换会改变表达式结构而不只是换因子 ⇒ 误匹配会产出**假证据**，宁可不改");
        assertTrue(result.text().contains(body), "源必须逐字不变");
    }

    @Test
    @DisplayName("🔖 等行数：行数必须不变（行号映射不受影响，同 ⑦½ / 7.75 段口径）")
    void lineCountIsPreserved() {
        SampleFactorProbeAdapter.setForceSample(true);
        SampleFactorProbeAdapter.setForceMultiplier(true);
        String source = wrap(REAL_ALBEDO_LINE + "\n\tfloat x = 1.0;");
        SampleFactorProbeAdapter.Result result =
                SampleFactorProbeAdapter.apply(ShaderStage.FRAGMENT, source);
        assertEquals(source.split("\n", -1).length, result.text().split("\n", -1).length,
                "探针必须等行数 —— 否则 C 线的行号映射被切断，诊断行号全部失真");
    }

    @Test
    @DisplayName("🔖 开关全关时源逐字不变、零诊断（默认路径零开销）")
    void disabledIsIdentity() {
        String source = wrap(REAL_ALBEDO_LINE);
        SampleFactorProbeAdapter.Result result =
                SampleFactorProbeAdapter.apply(ShaderStage.FRAGMENT, source);
        assertEquals(source, result.text());
        assertTrue(result.diagnostics().isEmpty(), "默认关时不得有任何诊断（否则污染正常运行的日志）");
        assertFalse(SampleFactorProbeAdapter.anyEnabled());
    }

    @Test
    @DisplayName("🔖 非片元阶段不改写（乘法链在片元里，顶点侧改了是错位）")
    void nonFragmentStageUntouched() {
        SampleFactorProbeAdapter.setForceSample(true);
        SampleFactorProbeAdapter.setForceMultiplier(true);
        String source = wrap(REAL_ALBEDO_LINE);
        SampleFactorProbeAdapter.Result result =
                SampleFactorProbeAdapter.apply(ShaderStage.VERTEX, source);
        assertEquals(source, result.text());
        assertEquals(0, result.patched());
    }
}