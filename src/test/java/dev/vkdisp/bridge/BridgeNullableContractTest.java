package dev.vkdisp.bridge;
/**
 * 【参考调研】QD-01 守卫：`bridge/` 里任何「会返回 null 的方法」必须标 `@Nullable`
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓库 `docs/07-CONSTRAINTS.md` §3.2
 *    （规范原文：「所有可空返回值必须标注（NeoForge 用 org.jspecify）」）
 *    + 原版自身的空安全约定（已核实：Minecraft 26.3 的
 *    `com.mojang.renderpearl.api.pipeline.ShaderSource` 的
 *    `getShader` / `getInclude` 在 class 文件里就带
 *    `RuntimeVisibleTypeAnnotations: org/jspecify/annotations/Nullable`）。
 *    → 全部为本仓库自有规范 + 原版公开 API 事实，无外部代码搬运。
 * 1. 官方/主实现：`org.jspecify.annotations.Nullable`（JSpecify 1.0，Apache-2.0，
 *    随 NeoForge 分发；原版自己就在用）。
 * 2. 备选：
 *    <ul>
 *      <li>① 只在 javadoc 里写「返回 null」—— <b>否决</b>：`h33` 的 QD-02 与 GAP-009
 *          两次教训都说明，**文档不是契约**；本轮 QD-01 的病根正是「规范写了、执行没跟上」，
 *          而没有任何东西让它持续。</li>
 *      <li>② 全仓一次性标完 —— <b>本轮不做</b>：内部 60+ 处 `return null` 逐个核对语义
 *          需要逐个读调用点，属另一轮的工作量。本轮只圈定 `bridge/`（对外 API 面）
 *          并把其余登记为后续项（见 `QUALITY-DEBT.md` QD-01）。</li>
 *    </ul>
 * 3. 我们的差异点：把「`bridge/` 里没有未标注的可空返回值」做成**构建期红灯** ——
 *    新写一个会返回 null 的方法而忘了标注，`./gradlew test` 直接挂。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 单测。
 */
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * QD-01 的**闭环回路**：没有守卫的话，规范会在下一轮再次失效
 * （这正是 QD-02「`debugLog` 死开关」与 `h33`「能力门控死开关」两次复发的原因）。
 */
class BridgeNullableContractTest {

    private static final Path BRIDGE = Path.of("src/main/java/dev/vkdisp/bridge");

    /**
     * 方法声明的判定：至少一个修饰符 + 返回类型 + 方法名 + 左括号，且缩进 ≥ 4。
     *
     * <p>🔖 <b>为什么要求修饰符</b>：这样才能把**字段声明**与**局部变量**排除掉
     * （它们的缩进更深或形态不同）；多行签名的<b>续行</b>不以修饰符开头 ⇒ 不会误判成新方法。
     */
    private static final Pattern DECL = Pattern.compile(
            "^\\s{4,}(?:(?:public|private|protected|static|final|synchronized|abstract|default)"
                    + "\\s+)+[\\w.$<>\\[\\]]+\\s+\\w+\\s*\\(");

    /** 类 / 接口 / 枚举 / record 的声明（用来定位「类体深度」）。 */
    private static final Pattern TYPE_DECL = Pattern.compile(
            "^(?:public\\s+|final\\s+|abstract\\s+)*(?:class|interface|enum|record)\\s+\\w+");

    /** 返回类型里带 {@code <...>} 时声明可能跨行；这里只需要行首形态判定。 */
    private static final String NULL_RETURN = "return null;";

    private record Violation(String file, int line, String signature) {
    }

    private static String stripNonCode(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inBlock = false;
        for (String raw : text.split("\n")) {
            String line = raw;
            int guard = 0;
            while (guard++ < 200) {
                if (inBlock) {
                    int end = line.indexOf("*/");
                    if (end < 0) {
                        line = "";
                        break;
                    }
                    inBlock = false;
                    line = line.substring(end + 2);
                    continue;
                }
                int start = line.indexOf("/*");
                if (start < 0) {
                    break;
                }
                int end = line.indexOf("*/", start + 2);
                if (end < 0) {
                    inBlock = true;
                    line = line.substring(0, start);
                    break;
                }
                line = line.substring(0, start) + line.substring(end + 2);
            }
            // 行注释：去掉 // 之后的内容（// 可能出现在字符串里，但本守卫只关心结构，故可接受）
            int cmt = line.indexOf("//");
            if (cmt >= 0) {
                line = line.substring(0, cmt);
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    /** 扫描一个文件，返回「返回 null 但所在方法没有 {@code @Nullable}」的违例。 */
    private static List<Violation> scan(String fileName, String source) {
        String code = stripNonCode(source);
        String[] lines = code.split("\n");
        List<Violation> out = new ArrayList<>();

        int depth = 0;
        // 🔖🔖 类体深度：文件层是 0，**类体是 1**。
        //   首版把「方法声明所在层」也当成 0 ⇒ 类声明一开括号 depth 就变成 1
        //   ⇒ 所有方法声明都被判成「不在 depth==0」⇒ 守卫一条都不报（形同虚设）。
        //   这个坑是本类的元测试抓出来的 —— 所以元测试不是装饰，是必需品。
        int classDepth = -1;
        int methodDepth = -1;
        String declSignature = "";
        boolean annotated = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (classDepth < 0 && depth == 0 && TYPE_DECL.matcher(line.trim()).find()) {
                classDepth = 1;
            }

            // 🔖 用 find() 而不是 matches()：声明行后面还跟着 ')'、'{'、throws 等，
            //   正则锚在 '^' 上只要求**行首匹配**；matches() 会因尾部对不上而永不命中。
            if (classDepth > 0 && depth == classDepth && DECL.matcher(line).find()) {
                declSignature = line.trim();
                methodDepth = -1;
                annotated = false;
                for (int back = 1; back <= 6 && i - back >= 0; back++) {
                    if (lines[i - back].contains("@Nullable")) {
                        annotated = true;
                        break;
                    }
                    // 遇到上一个成员的结尾就停，避免把上一个方法的注解算到本方法头上
                    if (!lines[i - back].trim().isEmpty() && !lines[i - back].trim().startsWith("@")) {
                        break;
                    }
                }
            }

            if (line.contains(NULL_RETURN) && methodDepth >= 0 && depth == methodDepth) {
                if (!annotated) {
                    out.add(new Violation(fileName, i + 1, declSignature));
                }
            }

            int opens = 0;
            int closes = 0;
            for (char c : line.toCharArray()) {
                if (c == '{') {
                    opens++;
                } else if (c == '}') {
                    closes++;
                }
            }
            if (methodDepth < 0 && opens > 0 && depth == classDepth && !declSignature.isEmpty()) {
                methodDepth = classDepth + 1;
            }
            depth += opens - closes;
            if (classDepth > 0 && depth <= classDepth) {
                depth = classDepth;
                methodDepth = -1;
                declSignature = "";
            }
        }
        return out;
    }

    private static List<Violation> scanAll() {
        Assumptions.assumeTrue(Files.isDirectory(BRIDGE), "工程目录缺失: " + BRIDGE);
        List<Violation> all = new ArrayList<>();
        try (var stream = Files.list(BRIDGE)) {
            for (Path p : stream.sorted().toList()) {
                if (!p.getFileName().toString().endsWith(".java")) {
                    continue;
                }
                all.addAll(scan(p.getFileName().toString(), Files.readString(p)));
            }
        } catch (java.io.IOException e) {
            throw new AssertionError("读取失败", e);
        }
        return all;
    }

    @Test
    @DisplayName("🔖🔖 bridge/ 里任何返回 null 的方法都必须标 @Nullable（QD-01 闭环）")
    void everyNullableReturnIsAnnotated() {
        List<Violation> bad = scanAll();
        StringBuilder detail = new StringBuilder();
        for (Violation v : bad) {
            detail.append('\n').append("  ").append(v.file()).append(':').append(v.line())
                    .append("  ").append(v.signature());
        }
        assertTrue(bad.isEmpty(),
                "以下方法会返回 null 但没有 @Nullable 标注 —— 07 §3.2 规范："
                        + "「所有可空返回值必须标注（NeoForge 用 org.jspecify）」。" + detail);
    }

    @Test
    @DisplayName("🔖 守卫本身要真的能抓到漏标注（否则它只是一句空话）")
    void guardActuallyDetectsMissingAnnotation() {
        String fake = """
                package dev.vkdisp.bridge;
                class Fake {
                    public static Object leaky() {
                        return null;
                    }

                    @Nullable
                    public static Object fine() {
                        return null;
                    }
                }
                """;
        List<Violation> bad = scan("Fake.java", fake);
        assertFalse(bad.isEmpty(), "守卫对漏标注的样本必须报错 —— 否则本测试形同虚设");
        assertTrue(bad.stream().anyMatch(v -> v.signature().startsWith("public static Object leaky")),
                "必须精确抓到 leaky()");
        assertTrue(bad.stream().noneMatch(v -> v.signature().startsWith("public static Object fine")),
                "已标注的 fine() 不得被误报");
    }

    @Test
    @DisplayName("🔖 守卫不得把 javadoc/注释里的 return null 当成真违例")
    void guardIgnoresComments() {
        String fake = """
                package dev.vkdisp.bridge;
                /**
                 * 这里写着 return null; 但只是文档。
                 */
                class Fake {
                    public static Object fine() {
                        return "x";
                    }
                }
                """;
        assertTrue(scan("Fake.java", fake).isEmpty(),
                "注释里的 'return null;' 是文档不是代码 —— 误报会让守卫不可用");
    }
}
