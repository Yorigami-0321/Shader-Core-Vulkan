package dev.vkdisp.pack.uniform;
/**
 * 【参考调研】GAP-021 用 BSL v10.1.8 的**真实表达式**做回归（逐字抄自包的 shaders.properties）
 * 0. 合规核对（第 0 步闸门）：
 *    本文件抄的是 {@code shaders.properties} 的 **12 行配置表达式**（{@code key=value} 形式的
 *    CPU 侧表达式），不是任何 {@code .glsl} 着色器片段 —— 18-PARALLEL §7.6 禁的是后者
 *    （「把第三方 pack 的某个 .glsl 片段复制进单测预期值 ⇒ 禁止」）与「把整张包提进仓库」；
 *    这里两者都没有做，且**期望值是本人按文档语义独立手算**的（不是从包里抄的数）。
 *    另有一条更硬的守卫 {@link #wholeRealBslPropertiesFileFromLocalPack()}：它直接读
 *    仓库外的本地包（{@code run/h27/shaderpacks/}，不在 git 里）拿全文，缺文件即跳过 ⇒
 *    「抄的 12 行」与「真包」两份口径互相校验，抄错也会红灯。
 *    许可证：本项目 MIT；零第三方代码并入。
 * 1. 主实现：{@link ShaderPropertiesParsePath}（真包那条走完整冷路径：条件编译 + 续行合并）。
 * 2. 备选：只测自造表达式（否决：GAP-021 的判据就是「BSL 那 28+13 行要能算出来」，
 *    自造样本证不到 `smooth(1, if(in(biome, …), 1, 0), 10, 10) + \\` 这种四行续写的形状）。
 * 3. 差异点：断言分两类 —— 能供值的（timeAngle / shadowFade / timeBrightness / blindFactor /
 *    framemod 系列 / yCold 系列）断具体数字；供不到值的（{@code biome} 全家）断言
 *    「整条跳过 + 被点名 + **不落进上传映射**」，后者是本条最要紧的守卫（X9/X11）。
 * 4. 性能基线：❄️ 单测。
 */
import dev.vkdisp.pack.properties.ShaderProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackUniformBslTest {

    private static final double EPS = 1.0e-6;

    /** 本地第三方包（只在开发机存在，不进仓库；缺文件 ⇒ 相关用例跳过而不是假绿）。 */
    private static final Path LOCAL_BSL_ZIP =
            Path.of("run", "h27", "shaderpacks", "BSL_v10.1.8.zip");

    private static double number(Map<String, Object> values, String name) {
        Object value = values.get(name);
        assertTrue(value instanceof Number, name + " 必须是数，实际=" + value);
        return ((Number) value).doubleValue();
    }

    /**
     * BSL 的自定义时间链（shaders.properties:145-151 逐字），输入用**正午**。
     *
     * <p>正午 ⇔ {@code sunAngle = 0.25}：出处 = OF/Iris 文档《uniforms · Sun angle》
     * 「0.25 represents the sun directly above」+ 原版 {@code Timelines.java:53}
     * 把 SUN_ANGLE 的 360/0 关键帧钉在 {@code 6000}（= 正午），换算式见
     * {@link PackUniformInputs#sunAngleFromDegrees}。
     */
    private static PackUniformSet bslTimeChain() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("variable.float.tAmin", "frac(sunAngle - 0.033333333)");
        properties.put("variable.float.tAlin",
                "if(tAmin < 0.433333333, tAmin * 1.15384615385, tAmin * 0.882352941176 + 0.117647058824)");
        properties.put("variable.float.hA", "if(tAlin > 0.5, 1.0, 0.0)");
        properties.put("variable.float.tAfrc", "frac(tAlin * 2.0)");
        properties.put("variable.float.tAfrs", "tAfrc * tAfrc * (3.0 - 2.0 * tAfrc)");
        properties.put("variable.float.tAmix", "if(hA < 0.5, 0.3, -0.1)");
        properties.put("uniform.float.timeAngle",
                "(tAfrc * (1.0 - tAmix) + tAfrs * tAmix + hA) * 0.5");
        properties.put("uniform.float.shadowFade",
                "clamp(1.0 - (abs(abs(sunAngle - 0.5) - 0.25) - 0.23) * 100.0, 0.0, 1.0)");
        properties.put("uniform.float.timeBrightness", "max(sin(timeAngle * 6.28318530718), 0.0)");
        return PackUniformSet.fromProperties(properties);
    }

    private static PackUniformInputs engine(double sunAngle, double blindness) {
        return new PackUniformInputs(1.0, new HashMap<>())
                .scalar("sunAngle", sunAngle)
                .scalar("blindness", blindness);
    }

    /** 全文那条要额外供 {@code cameraPosition}（yCold 三条）与 {@code frameCounter}（framemod 两条）。 */
    private static PackUniformInputs fullEngine(double sunAngle, double blindness) {
        return engine(sunAngle, blindness)
                .vector("cameraPosition", new double[] {0.0, 70.0, 0.0})
                .scalar("frameCounter", 21);
    }

    @Test
    @DisplayName("🔖 BSL 时间链：正午 ⇒ timeAngle=0.25、shadowFade=1、timeBrightness≈1")
    void bslTimeChainAtNoonProducesTheExpectedNumbers() {
        PackUniformSet.Outcome outcome = bslTimeChain().evaluate(engine(0.25, 0.0));
        Map<String, Object> values = outcome.values();
        assertEquals(0.25, number(values, "timeAngle"), EPS,
                "包自己算出的 timeAngle 必须落在我方内建口径上（worldTime/24000，正午 6000 ⇒ 0.25）");
        assertEquals(1.0, number(values, "shadowFade"), EPS,
                "GAP-021 判据：正午 shadowFade=1（此前恒 0 ⇒ BSL lightShafts.glsl:162 把光柱整条 ×0）");
        assertTrue(number(values, "timeBrightness") > 0.99,
                "fog.glsl:33 的日照色调依赖它 > 0，实际=" + values.get("timeBrightness"));
        assertEquals(0.0, outcome.skips().size(), outcome.skips().toString());
        assertFalse(values.containsKey("tAmin"), "variable 按 OF 语义不上传");
    }

    @Test
    @DisplayName("🔖 BSL 时间链：午夜 ⇒ timeAngle=0.75、timeBrightness=0（不是猜的 0，是 max 的结果）")
    void bslTimeChainAtMidnightGoesDark() {
        Map<String, Object> values = bslTimeChain().evaluate(engine(0.75, 0.0)).values();
        assertEquals(0.75, number(values, "timeAngle"), EPS);
        assertEquals(0.0, number(values, "timeBrightness"), EPS);
        assertEquals(1.0, number(values, "shadowFade"), EPS,
                "shadowFade 的式子在午夜同为 1（包里光柱另受 sunVec 方向门控，见 lightShafts.glsl:5）");
    }

    @Test
    @DisplayName("🔖 BSL shadowFade 的两个地平线端点（日出 0.0 / 日落 0.5）必须淡到 0")
    void bslShadowFadeVanishesAtHorizons() {
        assertEquals(0.0, number(bslTimeChain().evaluate(engine(0.0, 0.0)).values(), "shadowFade"), EPS);
        assertEquals(0.0, number(bslTimeChain().evaluate(engine(0.5, 0.0)).values(), "shadowFade"), EPS);
        // 端点之间要真的淡进来（不是常数）：sunAngle=0.02 时式子刚好到 1。
        assertEquals(1.0, number(bslTimeChain().evaluate(engine(0.02, 0.0)).values(), "shadowFade"), EPS);
    }

    @Test
    @DisplayName("🔖 BSL 盲视链（shaders.properties:162-163）：blindness=1 ⇒ blindFactor=1")
    void bslBlindFactorChain() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("variable.float.blindFactorSqrt", "clamp(blindness * 2.0 - 1.0, 0.0, 1.0)");
        properties.put("uniform.float.blindFactor", "blindFactorSqrt * blindFactorSqrt");
        PackUniformSet set = PackUniformSet.fromProperties(properties);
        assertEquals(1.0, number(set.evaluate(engine(0.25, 1.0)).values(), "blindFactor"), EPS);
        assertEquals(0.0, number(set.evaluate(engine(0.25, 0.25)).values(), "blindFactor"), EPS,
                "blindness<0.5 时式子归零（BSL fog.glsl:213 的盲区雾因此不出现）");
        assertEquals(0.25, number(set.evaluate(engine(0.25, 0.75)).values(), "blindFactor"), EPS);
    }

    @Test
    @DisplayName("🔖 BSL 帧抖动 uniform（shaders.properties:210-211，注意等号两侧有空格）")
    void bslFrameJitterUniforms() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("uniform.float.framemod8 ", " frameCounter % 8");
        properties.put("uniform.float.framemod2 ", " frameCounter % 2");
        Map<String, Object> values = PackUniformSet.fromProperties(properties)
                .evaluate(new PackUniformInputs(1.0, new HashMap<>()).scalar("frameCounter", 21))
                .values();
        assertEquals(5.0, number(values, "framemod8"), EPS, "jitter.glsl:24 拿它做整数下标");
        assertEquals(1.0, number(values, "framemod2"), EPS);
    }

    @Test
    @DisplayName("🔖 BSL 的 yCold 三条（引用 cameraPosition.y，且被**后面**的 isCold 引用）")
    void bslColdHeightVariablesResolve() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("variable.float.yCold1", "if(cameraPosition.y >= 93.0, 1, 0)");
        properties.put("variable.float.yCold2", "if(cameraPosition.y >= 123.0, 1, 0)");
        properties.put("variable.float.yCold3", "if(cameraPosition.y >= 153.0, 1, 0)");
        properties.put("uniform.float.coldSum", "yCold1 + yCold2 + yCold3");
        PackUniformSet set = PackUniformSet.fromProperties(properties);

        assertEquals(1.0, sumAt(set, 110.0), EPS, "110 只越过 93 ⇒ 三条里只成立一条");
        assertEquals(2.0, sumAt(set, 130.0), EPS, "130 越过 93 与 123");
        assertEquals(3.0, sumAt(set, 200.0), EPS, "200 三条全过");
    }

    private static double sumAt(PackUniformSet set, double cameraY) {
        Map<String, Object> values = set.evaluate(new PackUniformInputs(1.0, new HashMap<>())
                .vector("cameraPosition", new double[] {0.0, cameraY, 0.0})).values();
        return number(values, "coldSum");
    }

    @Test
    @DisplayName("🔴 BSL 的生物群集 11 条：biome 供不到 ⇒ 整条跳过 + 点名，绝不落 0")
    void bslBiomeUniformsAreSkippedAndNamedNotGuessed() {
        Map<String, String> properties = new LinkedHashMap<>();
        // shaders.properties:196（#else 分支的数字形态；我方 properties 预处理把未赋值的
        // MC_VERSION 当 0 ⇒ >= 11800 恒假 ⇒ 走这一支，这是 ConditionalPreprocessor 自报的既有缺口）
        properties.put("uniform.float.isDesert", "smooth(2, if(in(biome, 2, 17, 130), 1, 0), 10, 10)");
        PackUniformSet set = PackUniformSet.fromProperties(properties);
        assertTrue(set.rejections().isEmpty(), set.rejections().toString());
        PackUniformSet.Outcome outcome = set.evaluate(engine(0.25, 0.0));
        assertFalse(outcome.values().containsKey("isDesert"),
                "供不到值时不许产出 0（0 会被包读成「不在沙漠」，日志却一切正常）");
        assertEquals(List.of("biome"), outcome.unresolvedInputs(), outcome.unresolvedInputs().toString());
        assertTrue(outcome.skips().get(0).startsWith("isDesert"), outcome.skips().toString());
    }

    @Test
    @DisplayName("🔴 BIOME_* 符号名同样供不到：整条点名跳过（不映射、不猜 ID）")
    void bslSymbolicBiomeNamesAreNamedToo() {
        Map<String, String> properties = new LinkedHashMap<>();
        // shaders.properties:176（#if MC_VERSION >= 11800 分支的符号形态）
        properties.put("uniform.float.isDesert",
                "smooth(2, if(in(biome, BIOME_DESERT), 1, 0), 10, 10)");
        PackUniformSet.Outcome outcome = PackUniformSet.fromProperties(properties)
                .evaluate(engine(0.25, 0.0));
        assertFalse(outcome.values().containsKey("isDesert"));
        // 🔖 点名口径：`in()` 先算被检的 x（=biome），一遇到未解析就整条停住 ⇒ WARN 里出现的是
        //   `biome`，符号名 BIOME_DESERT 不会被单独列出（它也确实不是「下一个能补上的输入」——
        //   补 biome 的同时必须有 ID 表，那张表就是本条点名的缺口）。
        assertEquals(List.of("biome"), outcome.unresolvedInputs(), outcome.unresolvedInputs().toString());
        assertTrue(outcome.skips().get(0).startsWith("isDesert"), outcome.skips().toString());
    }

    // ---------------------------------------------------------------- 真包全文（本地、不进仓库）

    /**
     * 直接吃 BSL 的 {@code shaders/shaders.properties} 全文：走完整冷路径
     * （{@link ShaderProperties#parse} 的条件编译 + 续行合并）后逐条求值。
     *
     * <p>🔖 为什么这一条不能少：手抄的 12 行证不到
     * {@code uniform.float.isCold=smooth(...) + \\} 那种**四行续写**与
     * {@code #if/#else} 分支裁剪 —— 那两处恰恰是本包此前会炸的地方（p415 run1 的 CRLF 教训）。
     */
    @Test
    @DisplayName("🔖 BSL 全文 28+13 行：可解出的全部解出，解不到的全部点名")
    void wholeRealBslPropertiesFileFromLocalPack() throws IOException {
        Assumptions.assumeTrue(Files.exists(LOCAL_BSL_ZIP),
                "本地没有 BSL 包（" + LOCAL_BSL_ZIP + "）⇒ 跳过；仓库不提交第三方包（18-PARALLEL §7.6）");
        String text = readShadersProperties();
        ShaderProperties parsed = ShaderProperties.parse(text);
        PackUniformSet set = PackUniformSet.fromProperties(parsed.directives());

        // 生效分支（#else）下的定义数：10 条 variable + 17 条 uniform = 27。
        assertEquals(27, set.size(), "解析条数 = 生效分支的全部行：" + set.rejections());
        assertTrue(set.rejections().isEmpty(), "BSL 的表达式必须全部解析成功：" + set.rejections());

        PackUniformSet.Outcome outcome = set.evaluate(fullEngine(0.25, 1.0));
        assertEquals(0.25, number(outcome.values(), "timeAngle"), EPS);
        assertEquals(1.0, number(outcome.values(), "shadowFade"), EPS);
        assertTrue(number(outcome.values(), "timeBrightness") > 0.99);
        assertEquals(1.0, number(outcome.values(), "blindFactor"), EPS);
        assertEquals(5.0, number(outcome.values(), "framemod8"), EPS, "frameCounter=21 ⇒ 21%8");

        // X11 守卫：每个包自写的 uniform 名都要有归属 —— 要么有值，要么被点名，不许凭空消失。
        for (String name : set.uniformNames()) {
            boolean accounted = outcome.values().containsKey(name)
                    || outcome.skips().stream().anyMatch(skip -> skip.startsWith(name + ":"));
            assertTrue(accounted, name + " 既没求值也没被点名（静默丢失）");
        }
        assertEquals(List.of("biome"), outcome.unresolvedInputs(),
                "BSL 唯一供不到的输入就是 biome（数字 ID 表属 OptiFine 自有，26.3 侧无可核实对应物）");
        assertEquals(11, outcome.skips().size(), "11 条生物群集 uniform 全部跳过：" + outcome.skips());
    }

    private static String readShadersProperties() throws IOException {
        try (ZipFile zip = new ZipFile(LOCAL_BSL_ZIP.toFile())) {
            ZipEntry entry = zip.getEntry("shaders/shaders.properties");
            Assumptions.assumeTrue(entry != null, "包内没有 shaders/shaders.properties");
            try (InputStream in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
