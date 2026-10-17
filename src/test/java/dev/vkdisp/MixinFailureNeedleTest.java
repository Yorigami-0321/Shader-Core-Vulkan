package dev.vkdisp;
/**
 * 【参考调研】P5：注入失败判据针的自一致性守卫 / 只读 docs/07
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓库文档（LGPL-3.0-or-later）。
 *    → 可并入本项目（LGPL-3.0-or-later）：是，只做文本断言，不引用外部实现。
 * 1. 官方/主实现：无。
 * 2. 备选：无。
 * 3. 我们的差异点：钉住「六种标记**有定义**，且那条合成针真的覆盖六条」——
 *    2026-10-13 那轮引用的「六种注入失败标记全为 0」既没有定义、针又匹配不到任何真实措辞，
 *    于是崩掉的那一趟按判据读成「0 命中」。这条红灯防它再犯。
 * 4. 许可证核对：本项目 LGPL-3.0-or-later。
 * 5. 性能基线：❄️ 单测。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code 07-CONSTRAINTS.md} §1.4.1bis 六条标记与那条合成针的守卫。
 */
class MixinFailureNeedleTest {

    private static final Path CONSTRAINTS = Path.of("docs/07-CONSTRAINTS.md");

    /** §1.4.1bis 那一节（到 §1.4.2 为止）。 */
    private static String section() {
        String doc = readOrSkip(CONSTRAINTS);
        int start = doc.indexOf("### 1.4.1bis");
        assertTrue(start >= 0, "🔴 docs/07 里找不到 §1.4.1bis ⇒ 「六种注入失败标记」又回到没有定义的状态");
        int end = doc.indexOf("### 1.4.2", start);
        assertTrue(end > start, "§1.4.1bis 的节尾找不到");
        return doc.substring(start, end);
    }

    private static String readOrSkip(Path path) {
        Assumptions.assumeTrue(Files.exists(path), "文档缺失: " + path);
        try {
            return Files.readString(path);
        } catch (java.io.IOException e) {
            throw new AssertionError("读取失败: " + path, e);
        }
    }

    @Test
    @DisplayName("🔖 「六种标记」在库里有定义：§1.4.1bis 必须是六行")
    void sixMarkersAreDefined() {
        int rows = 0;
        for (String line : section().split("\n")) {
            if (line.matches("^\\|\\s*[1-6]\\s*\\|.*")) {
                rows++;
            }
        }
        assertEquals(6, rows, "🔴 §1.4.1bis 的标记行数不是 6（CHANGE_LOG 与 19 §7-7 引用的就是这六条）");
    }

    @Test
    @DisplayName("🪡 合成针确实覆盖六条，且用的措辞是 jar 里真有的那句")
    void combinedNeedleCoversTheSixMarkers() {
        String section = section();
        Matcher m = Pattern.compile("grep -nE '([^']+)'").matcher(section);
        assertTrue(m.find(), "🔴 §1.4.1bis 里没有那条可复制的合成针");
        String needle = m.group(1);

        for (String fragment : new String[] {
                "Mixin apply for mod ",   // 真实措辞（旧针 Mixin apply failed 没有这句话）
                "from phase \\[",
                "Critical injection failure",
                "Failed to load mixin ",
                "will not be applied",
                "MixinInitialisationError",
                "MixinApplyError",
                "requires mixin subsystem version",
                "Error initialising mixin ",
        }) {
            assertTrue(needle.contains(fragment),
                    "🔴 合成针缺一条：" + fragment + " ⇒ 那一族的失败会读成「0 命中」");
        }
        assertFalse(needle.contains("Mixin apply failed"),
                "🔴 合成针里还留着那条假绿针（sponge-mixin 从不打这句话）");
    }
}
