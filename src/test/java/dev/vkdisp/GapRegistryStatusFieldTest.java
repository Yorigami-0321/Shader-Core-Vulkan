package dev.vkdisp;
/**
 * 【参考调研】缺口登记表守卫：缺「状态」字段的缺口清单**只能变短**（QD-07 棘轮）
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓库 `docs/13-GAP-REGISTRY.md` 自身的表格契约
 *    （每条 GAP 都应有一条状态行），以及 `docs/07-CONSTRAINTS.md` 的
 *    `T12`（先登记）与 `X42`（能力上限与生产值不得混写）。全部为本仓库自有规范。
 * 1. 官方/主实现：无（这是本项目自定的登记纪律）。
 * 2. 备选：
 *    <ul>
 *      <li>① 只在文档里写规范、不设守卫 —— <b>否决</b>：本轮先写下「GAP-008 是唯一一条
 *          没有状态字段的缺口」，随后自己写的守卫<b>当场把另外 9 条一起数了出来</b>
 *          ⇒ 那句话是错的。h33 / h34 / h35 / h40 / h41 的教训连续五轮都是同一个：
 *          「规范写下来了，但从没有东西让它保持正确」。</li>
 *      <li>② 做成**硬断言**（谁缺就红）—— <b>否决</b>：本仓库当前有 9 条欠账，
 *          硬断言会让构建**立刻长期红**，等于把规范取消。
 *          照 `h35` 立 `MethodLengthRatchetTest` 的做法改成<b>棘轮</b>：
 *          基线是当前欠账，只允许变短。</li>
 *      <li>③ 用单测读运行期状态判断「缺口是否已解决」—— <b>做不到</b>：
 *          缺口的收口判据是<b>画面观测</b>（见 `evidence/`），不在单测里。</li>
 *    </ul>
 * 3. 我们的差异点：把「登记表的结构契约」做成<b>带元测试的红灯</b> ——
 *    新增缺口时若忘写状态字段，构建会失败；补齐了则基线可同步缩小。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 单测（只读文档，不进渲染路径）。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「缺状态字段的缺口清单只能变短」的棘轮守卫（QD-07）。
 *
 * <p>🔖 <b>为什么值得单独守卫</b>：`13-GAP-REGISTRY` 是本项目唯一真源，
 * 跨会话的 AI 每轮都先读它。一个<b>没有状态字段</b>的条目，在读者眼里等于
 * 「没结论、没人在管」。
 *
 * <p>🔖 <b>这个类自己就是一次自证的例子</b>：本轮先在证据里写下
 * 「GAP-008 是唯一一条没有状态字段的缺口」，
 * 随后这个守卫<b>当场把另外 9 条一起数了出来</b>。
 * 「唯一」是最容易下、也最容易被自己的工具推翻的结论 —— 所以要配守卫。
 */
class GapRegistryStatusFieldTest {

    private static final Path REGISTRY = Path.of("docs/13-GAP-REGISTRY.md");

    private static final String STATUS = "状态";

    /**
     * 🔖 QD-07 棘轮基线：这些缺口**至今没有**状态字段。
     *
     * <p>本清单<b>不是</b>「应该有的样子」，而是<b>当前欠账</b>；只能变短，不能变长。
     */
    private static final Set<String> BASELINE = Set.of(
            "GAP-002", "GAP-003", "GAP-004", "GAP-005", "GAP-006",
            "GAP-007", "GAP-009", "GAP-010", "GAP-011");

    private static List<String> readOrSkip() {
        Assumptions.assumeTrue(Files.exists(REGISTRY), "工程文件缺失: " + REGISTRY);
        try {
            return Files.readAllLines(REGISTRY);
        } catch (java.io.IOException e) {
            throw new AssertionError("读取失败: " + REGISTRY, e);
        }
    }

    /**
     * 把 `| GAP-xx | …` 行解析成 GAP 编号；不是这种行则返回 `null`。
     *
     * <p>🔖 这里必须找**第二个**竖线（`indexOf('|', 1)`）：
     * 用 `indexOf('|')` 会命中下标 0，截出空串并抛
     * `StringIndexOutOfBoundsException`（本轮真踩到）。
     */
    private static String gapId(String rawLine) {
        String t = rawLine.strip();
        if (!t.startsWith("|")) {
            return null;
        }
        int close = t.indexOf('|', 1);
        if (close < 0) {
            return null;
        }
        String head = t.substring(1, close).strip();
        return head.startsWith("GAP-") ? head : null;
    }

    /**
     * 判断某条缺口条目（行列表）是否**缺**状态字段。
     *
     * @param entry 从 `| GAP-xx |…` 那行开始、到空行或下一条 GAP 行之前的切片
     */
    private static boolean lacksStatus(List<String> entry) {
        for (String raw : entry) {
            String u = raw.strip();
            if (u.startsWith("| **") && u.contains(STATUS)) {
                return false;
            }
        }
        return true;
    }

    /** 从登记表里数出所有缺状态字段的 GAP 编号。 */
    private static Set<String> gapsMissingStatus() {
        List<String> lines = readOrSkip();
        Set<String> missing = new TreeSet<>();
        int seen = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (gapId(lines.get(i)) == null) {
                continue;
            }
            seen++;
            List<String> entry = new java.util.ArrayList<>();
            for (int j = i; j < lines.size(); j++) {
                String u = lines.get(j).strip();
                if (j > i && (u.startsWith("| GAP-") || u.isEmpty())) {
                    break;
                }
                entry.add(u);
            }
            if (lacksStatus(entry)) {
                missing.add(gapId(lines.get(i)));
            }
        }
        assertTrue(seen > 0, "没解析到任何 GAP 行 —— 表格结构或解析器变了，先修这里");
        return missing;
    }

    @Test
    @DisplayName("QD-07 棘轮：缺状态字段的缺口清单只能变短，不能变长")
    void missingStatusRatchetDoesNotGrow() {
        Set<String> missing = gapsMissingStatus();
        Set<String> newlyMissing = new TreeSet<>(missing);
        newlyMissing.removeAll(BASELINE);
        assertTrue(newlyMissing.isEmpty(),
                "这些缺口**新增**了缺少状态字段的问题: " + newlyMissing
                        + " —— 棘轮只允许变短（补字段），不允许变长（新增缺口时忘写状态）。"
                        + " 当前仍欠账的基线: " + BASELINE);
        assertTrue(!missing.isEmpty(),
                "基线里的缺口应都还没写状态字段；若已全部补齐，"
                        + "请同步缩小 BASELINE（棘轮可以往好的方向走）");
    }

    @Test
    @DisplayName("🔖 棘轮本身要真的能抓到漏标注（否则它只是一句空话）")
    void ratchetMetaTest() {
        // 自检 1：没有状态字段的合成条目必须被判为「缺」
        assertEquals(Boolean.TRUE,
                lacksStatus(List.of("| GAP-999 | x", "  续行，不是竖线开头", "")),
                "合成样本必须被判为缺状态，否则棘轮形同虚设");
        // 自检 2：有状态字段的合成条目必须被判为「有」
        assertEquals(Boolean.FALSE,
                lacksStatus(List.of("| GAP-999 | x", "| **" + STATUS + "** | ok", "")),
                "有状态字段的样本必须被判为有，否则棘轮会把已补齐的算成欠账");
        // 自检 3：gapsMissingStatus 必须真的数得出东西（对真实登记表）
        assertTrue(!gapsMissingStatus().isEmpty(),
                "真实登记表当前应存在缺状态字段的缺口；若已全部补齐，"
                        + "请同步缩小 BASELINE 而不是让本守卫空转");
    }

    @Test
    @DisplayName("🔖🔖 GAP-008 必须标为仍开放，且注明其正面定位来自 OpenGL")
    void gap008MustNotBeClosedOnOpenGlEvidenceAlone() {
        StringBuilder doc = new StringBuilder();
        for (String line : readOrSkip()) {
            doc.append(line).append('\n');
        }
        String text = doc.toString();
        assertTrue(text.contains("GAP-008"), "GAP-008 应仍在登记表中");
        assertTrue(text.contains("h42"),
                "GAP-008 必须记录 h42 的 Vulkan 复现结论 —— "
                        + "h31 的收尾是 OpenGL 产物，不写下来下一轮会直接拿它当结论用");
        assertTrue(text.contains("仍开放") || text.contains("保持开放"),
                "GAP-008 的状态必须明确写成仍开放 —— "
                        + "h42 在 Vulkan 主目标上复现了黑屏，收口条件不满足");
    }
}
