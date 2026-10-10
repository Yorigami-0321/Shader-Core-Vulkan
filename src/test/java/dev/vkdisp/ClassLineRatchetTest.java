package dev.vkdisp;
/**
 * 【参考调研】B0 棘轮一：主源码「>1000 行的类」不得增长（19 §3.4 方案 3 / QD-10）
 * 0. 合规：参考对象 = 本仓库 `docs/19-IMPROVEMENT-PATHS.md` §3.4（「Strangler + 棘轮」方案，
 *    明确点名照 `MethodLengthRatchetTest` 的做法）与 `docs/QUALITY-DEBT.md` QD-10。
 *    全部为本仓库自有文档事实，零第三方代码（detekt/ESLint 只作为「失败模式」的反面案例引用）。
 * 1. 官方/主实现：无（纯源码文本统计）。
 * 2. 备选：① 直接开工拆类 —— 否决：B3 才动所有权，先拆等于没有护栏地改热路径（QD-04 的教训）；
 *          ② 只在文档里记数 —— 否决：`19` §1 自己就抓到文档数字与代码不同步（QD-03 的「95」vs 实测 175）。
 * 3. 差异点：基线不是「一个总数」而是**每个文件一个行数上限** ⇒ 把某个类拆小之后，
 *    别处想再长回来会被立刻挡下（只卡总数的话，拆一个 + 长一个 会互相抵消而无人察觉）。
 * 4. 许可证：本项目 MIT；零复制。
 * 5. 性能：❄️ 单测（扫源码，不进渲染路径）。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 巨类棘轮（QD-10）。
 *
 * <p>🔖 <b>数值出处 = 本测试的 {@link #CAPS}</b>，不要再往文档里手抄行数
 * （`19` §5.1 的裁决：凡能被脚本算出来的数字，不许手抄）。
 */
class ClassLineRatchetTest {

    /** 巨类判定阈值：与 `19` §1 基线表同口径（`>1000` 行的主源码类）。 */
    private static final int GIANT = 1000;

    /**
     * 每个巨类的行数上限（2026-10-10 实测：6 个文件）。
     *
     * <p>规则：<b>超过上限 = 红灯</b>；<b>比上限少超过 {@link #SLACK} 行也是红灯</b> —— 后者是
     * 「拆小了就立刻把上限降下来」的锁进机制，防止省下的高度在某天被悄悄吃回去。
     *
     * <p>🔖 其中两行与 `19` 附录 A-A2 的 `wc -l` 数字**差 1**：`MrtTerrainPass` / `TargetReadback`
     * 文件末尾**没有换行符**（实测 `tail -c 1` 是 `}`），`wc -l` 因此少数一行；本口径数的是真实行数。
     * 换行符补齐后这两条上限会各降 1 —— 属于同一次改动里顺手做的事。
     */
    private static final Map<String, Integer> CAPS = Map.of(
            "dev/vkdisp/bridge/FrameApi.java", 1675,
            "dev/vkdisp/VkDispVirtualPack.java", 1573,
            "dev/vkdisp/bridge/PipelineApi.java", 1163,
            "dev/vkdisp/bridge/TerrainPipelineApi.java", 1131,
            "dev/vkdisp/bridge/TargetReadback.java", 1112,
            "dev/vkdisp/bridge/MrtTerrainPass.java", 1069);

    /** 允许的「拆小后未降基线」余量：超过就必须更新 {@link #CAPS}（把进步锁住）。 */
    private static final int SLACK = 40;

    private static final Path SRC = Path.of("src/main/java");

    /** 相对 `src/main/java` 的路径 → 行数（只含 `.java`）。 */
    private static List<Map.Entry<String, Integer>> lineCounts() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(SRC), "工程目录缺失: " + SRC);
        List<Map.Entry<String, Integer>> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(SRC)) {
            List<Path> files = stream.filter(p -> p.toString().endsWith(".java")).sorted().toList();
            for (Path p : files) {
                out.add(Map.entry(SRC.relativize(p).toString().replace('\\', '/'),
                        lineCount(Files.readString(p))));
            }
        }
        return out;
    }

    /**
     * 行数口径 = **真实内容行数**：数换行符，末行没有换行符时补一行。
     *
     * <p>🔖 与 `wc -l`（`19` 附录 A-A2 的口径）的**唯一差别**：末尾无换行符的文件 `wc -l` 少数一行，
     * 本仓库今天有两条就是这样（见 {@link #CAPS} 的注记）。
     * <p>🔖 不用 {@code split("\n", -1).length} —— 那个口径对「以换行收尾」的文件会多算一行
     * （末段是空串），首次跑就把 6 个巨类全部误报成「长了 1 行」。
     */
    private static int lineCount(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        int newlines = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                newlines++;
            }
        }
        boolean endsWithNewline = !text.isEmpty() && text.charAt(text.length() - 1) == '\n';
        return endsWithNewline ? newlines : newlines + 1;
    }

    @Test
    @DisplayName("🔖 巨类不得新增：>1000 行的文件必须全部在 CAPS 名单里")
    void noNewGiantClass() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : lineCounts()) {
            if (entry.getValue() > GIANT && !CAPS.containsKey(entry.getKey())) {
                offenders.add(entry.getKey() + " = " + entry.getValue() + " 行（不在名单里）");
            }
        }
        assertTrue(offenders.isEmpty(),
                "新增了 >" + GIANT + " 行的类 ⇒ 按 19 §3.4，要么先立棘轮再拆，要么把它做成独立小类。"
                        + "当前越界：" + offenders);
    }

    @Test
    @DisplayName("🔖 名单里的巨类只许变短：不得超上限，也不得省下超过 SLACK 却不降上限")
    void giantClassesDoNotGrowAndLockInShrink() throws IOException {
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : lineCounts()) {
            Integer cap = CAPS.get(entry.getKey());
            if (cap == null) {
                continue;
            }
            int actual = entry.getValue();
            if (actual > cap) {
                failures.add(entry.getKey() + " 长了：" + actual + " > 上限 " + cap
                        + " ⇒ 新增内容请落到独立类里（QD-10 的拆分方向见 19 §3.1）");
            } else if (cap - actual > SLACK) {
                failures.add(entry.getKey() + " 已缩到 " + actual + "，上限仍是 " + cap
                        + " ⇒ 请把 CAPS 降到实测值（把进步锁住）");
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    @DisplayName("🔖 名单本身必须与当前实测一致（漏登记/多登记都算红灯）")
    void everyGiantClassIsRegistered() throws IOException {
        List<String> giants = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : lineCounts()) {
            if (entry.getValue() > GIANT) {
                giants.add(entry.getKey());
            }
        }
        assertEquals(CAPS.keySet().size(), giants.size(),
                "实测 >" + GIANT + " 行的文件数是 " + giants.size() + "，CAPS 登记了 "
                        + CAPS.keySet().size() + " 个：" + giants
                        + " ⇒ 两者必须同时改（拆掉一个就把条目删掉）");
    }

    @Test
    @DisplayName("🔖 行数口径：末行有换行符时与 wc -l 同数，末行无换行符时取真实行数")
    void lineCountMatchesWcCaliber() {
        assertEquals(3, lineCount("a\nb\nc\n"), "wc -l 对以换行收尾的三行文件报 3");
        assertEquals(2, lineCount("a\nb"), "末行无换行符时 wc -l 报 1，但真实行数 2 ⇒ 取真实行数");
        assertEquals(0, lineCount(""));
    }

    @Test
    @DisplayName("🔖 扫描器自身能找到东西（否则棘轮会变成永远通过的空话）")
    void scannerActuallyCounts() throws IOException {
        List<Map.Entry<String, Integer>> counts = lineCounts();
        assertTrue(counts.size() > 100, "主源码 java 文件数应远大于 100，实测 " + counts.size());
        assertTrue(counts.stream().anyMatch(e -> e.getValue() > GIANT),
                "扫描器必须至少数出一个巨类（当前工程有 6 个）");
    }
}
