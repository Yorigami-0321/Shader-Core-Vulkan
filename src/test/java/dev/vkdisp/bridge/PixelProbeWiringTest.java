package dev.vkdisp.bridge;
/**
 * 【参考调研】像素回读探针的**接线纪律**守卫
 * 0. 合规核对（第 0 步闸门）：参考对象 = ① 原版 26.3
 *    {@code com.mojang.renderpearl.frontend.FrontendCommandEncoder#copyTextureToBuffer}
 *    （Mojang EULA，只读**前置条件**：`isInRenderPass` 时抛
 *    {@code Close the existing render pass before performing additional commands}）、
 *    ② {@code RenderTarget#createBuffers} 的 usage = 15（必须含 COPY_SRC = 2）、
 *    ③ 本仓库自有 {@code evidence/h42-…} §4.3（「输出黑」与「没落到主目标」本轮没分开）。
 *    → 能否并入本项目（MIT）：可以（测试只做源码文本断言，不搬运任何代码）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：无（这是本项目自定的接线纪律）。
 * 2. 备选：无。
 * 3. 我们的差异点：把四条**静默失效**（探针回读在 pass 内 → 抛异常刷屏；回读源缺 COPY_SRC
 *    → 一次性 ERROR 后每帧静默无数字；探针放在诊断视图之后 → 「主目标」那个数字其实是我们
 *    自己 blit 的 colortex ⇒ 两源对照完全失效；对照结论无去重 → 按探针间隔刷屏）做成构建期红灯。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 单测（扫源码）。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 像素回读探针接线守卫。
 *
 * <p>🔖 这些断言守的全是<b>静默失效</b>：不崩、不报错、日志看着正常，但数字是错的或根本没有。
 */
class PixelProbeWiringTest {

    private static final Path FRAME = Path.of("src/main/java/dev/vkdisp/bridge/FrameApi.java");
    private static final Path HOOK = Path.of("src/main/java/dev/vkdisp/render/FullscreenPassHook.java");
    private static final Path PROBE = Path.of("src/main/java/dev/vkdisp/bridge/TargetReadback.java");
    private static final Path PASS = Path.of("src/main/java/dev/vkdisp/bridge/MrtTerrainPass.java");
    private static final Path CONFIG = Path.of("src/main/java/dev/vkdisp/VkDispConfig.java");

    private static String readOrSkip(Path path) {
        Assumptions.assumeTrue(Files.exists(path), "工程文件缺失: " + path);
        try {
            return Files.readString(path);
        } catch (java.io.IOException e) {
            throw new AssertionError("读取失败: " + path, e);
        }
    }

    /** 只统计非注释行里的出现次数（javadoc 会引用代码字样）。 */
    private static int countCode(String text, String literal) {
        int count = 0;
        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                continue;
            }
            count += line.split(Pattern.quote(literal), -1).length - 1;
        }
        return count;
    }

    @Test
    @DisplayName("🔖🔖 探针必须在所有 render pass 关闭之后调用（pass 内回读会抛）")
    void probeRunsAfterAllPassesClosed() {
        String frame = readOrSkip(FRAME);
        String hook = readOrSkip(HOOK);
        int probeAt = hook.indexOf("TargetReadback.probeFrameTail()");
        assertTrue(probeAt > 0, "帧尾必须调用探针，否则永远不会取到数字");

        // 🔖 跨文件不能比下标，所以比较的基准是 Hook 里**调用链的那一行**：
        //   `FrameApi#drawFullscreen` 返回时，它内部所有 render pass 都已关闭。
        int chainAt = hook.indexOf("FrameApi.drawFullscreen(");
        assertTrue(chainAt > 0, "Hook 里必须调用整条链（否则本守卫的前提失效，先修它）");
        assertTrue(chainAt < probeAt,
                "探针调用必须在整条链之后 —— 原版 FrontendCommandEncoder 在 render pass 打开期间"
                        + "做 copyTextureToBuffer 会抛 \"Close the existing render pass before "
                        + "performing additional commands\"（实测同类错误在 h33 刷了 2702 行）");
        assertTrue(frame.lastIndexOf("try (RenderPass pass") > 0,
                "链里至少要有一个 render pass（否则本守卫的前提失效，先修它）");
    }

    @Test
    @DisplayName("🔖🔖 探针必须在**地形 MRT pass 之后**（否则 toMain 档读到的是写入前的内容）")
    void probeRunsAfterTerrainPass() {
        // 🔖🔖 本轮实测踩到：探针首版放在 FrameApi#drawFullscreen 的末尾，
        //   而地形 MRT pass（afterLevel 模式）是由 Hook 在**那个方法返回之后**才调的
        //   ⇒ 探针读到的「主目标」是地形 pass **写入之前**的内容
        //   ⇒ 恰好在 `mrt.terrainToMain=true`（GAP-008 取证那一档）失效，
        //   而且日志上完全看不出来（数字照打，只是早了半帧）。
        String hook = readOrSkip(HOOK);
        int probeAt = hook.indexOf("TargetReadback.probeFrameTail()");
        int terrainAt = hook.indexOf("MrtTerrainPass.drawAfterLevel()");
        assertTrue(probeAt > 0 && terrainAt > 0, "找不到探针调用或地形 pass 调用");
        assertTrue(terrainAt < probeAt,
                "探针必须在地形 MRT pass 之后 —— 在它之前读主目标，"
                        + "读到的不是「地形有没有落到主目标」而是「落点之前长什么样」");
    }

    @Test
    @DisplayName("🔖🔖 探针必须在诊断视图 blit 之前（否则「主目标」那个数字是假的）")
    void probeRunsBeforeDiagnosticBlit() {
        // 🔖🔖 MrtProbe.drawExternalView 会把**主目标覆盖成某个 colortex 的内容**。
        //   若回读在其后，「主目标」与「colortex」两个数字实际指向同一张图
        //   ⇒ 两源对照（h42 §4.3 要的那个分离判据）彻底失效，而且日志上完全看不出来。
        String frame = readOrSkip(FRAME);
        String hook = readOrSkip(HOOK);
        int probeAt = hook.indexOf("TargetReadback.probeFrameTail()");
        int blitAt = frame.indexOf("MrtProbe.drawExternalView(");
        assertTrue(probeAt > 0 && blitAt > 0, "找不到探针调用或诊断回读调用");
        assertTrue(probeAt < blitAt,
                "探针必须在诊断 blit 之前 —— 之后主目标已被覆盖成 colortex 内容，"
                        + "两个数字会指向同一张图，两源对照静默失效");
    }

    @Test
    @DisplayName("🔖🔖 FrameApi 里不得再调探针（它早于地形 pass，位置本身就是错的）")
    void frameApiMustNotCallProbe() {
        // 🔖 这条是上一条的**反面**：探针曾被放在 drawFullscreen 末尾。
        //   只加「必须在 terrain pass 之后」而不禁掉旧位置，将来有人搬回去就会静默失效。
        assertEquals(-1, readOrSkip(FRAME).indexOf("TargetReadback.probeFrameTail()"),
                "FrameApi#drawFullscreen 末尾早于地形 MRT pass —— 探针不许放在那里。"
                        + "正确位置是 FullscreenPassHook 的最末尾（唯一同时满足三条约束处）");
    }

    @Test
    @DisplayName("🔖 回读源必须检查 USAGE_COPY_SRC（缺了会抛且只报一次）")
    void sourceMustHaveCopySrc() {
        String probe = readOrSkip(PROBE);
        assertTrue(probe.contains("GpuTexture.USAGE_COPY_SRC"),
                "必须显式检查 COPY_SRC：原版 FrontendCommandEncoder 在缺它时抛"
                        + " \"Texture needs USAGE_COPY_SRC to be a source for a copy\"");
        assertTrue(probe.contains("NOT_COPY_SRC_NOTED"),
                "该失败必须**一次性**报告 —— 每帧一条 ERROR 会刷屏（h33 实测 2702 行那类）");
    }

    @Test
    @DisplayName("🔖🔖 terrainToMain 档不得拿 colortex0 当对照（它那档不是附件）")
    void toMainLaneMustNotProbeSlotZero() {
        // 🔴🔖 本轮实测踩到：`mrt.terrainToMain=true` 时**附件 0 已被换成主目标视图**，
        //   所以 `colortex0` 这一帧根本没被写过 ⇒ 读它必然得到「零填充的旧内容」
        //   ⇒ 日志会报 `colortex0 allZero=true`，而真相是「那张图不是附件」。
        //   那正是本项目最该消灭的形态：**诊断给出一个看起来像证据的假数字**
        //   （会让人以为「colortex 全黑 ⇒ 包片元输出黑」，而实际是没写过）。
        String probe = readOrSkip(PROBE);
        assertTrue(probe.contains("MrtTerrainPass.toMain()"),
                "探针必须显式判 terrainToMain 档 —— 该档下 colortex0 不是附件");
        assertTrue(probe.contains("toMainNoSlotNoted"),
                "该档下若没有第二个被写的目标，必须**明确说不产出两源对照**，"
                        + "而不是拿一个不存在的数字充数");
        assertTrue(probe.contains("不是附件"),
                "必须把「槽 0 在该档下不是附件」这句话写进日志 —— 否则读日志的人无从分辨"
                        + "「没写」与「写了但是黑的」");
    }

    @Test
    @DisplayName("🔖🔖 每个源必须一个独立缓冲（两个源回读进同一个缓冲会互相覆盖）")
    void oneBufferPerSource() {
        String probe = readOrSkip(PROBE);
        assertTrue(probe.contains("BUFFERS.get(label)"),
                "缓冲必须按源标签分别持有 —— 同一帧要回读主目标与 colortex 两个源，"
                        + "共用一个缓冲会让「两个数字」变成「同一个数字的两份解读」");
        assertTrue(probe.contains("PENDING"),
                "必须有在途计数：回调是异步的，重建缓冲前必须确认该源没有在途请求，"
                        + "否则旧回调会去 map 已关闭的缓冲（GPU 对象生命周期 bug）");
    }

    @Test
    @DisplayName("🔖 两源对照必须同帧（键里带轮次，不许拿跨帧数字硬比）")
    void comparisonIsSameRound() {
        String probe = readOrSkip(PROBE);
        assertTrue(probe.contains("String roundTag = label + \"#\" + round"),
                "已完成的统计键必须带轮次 —— 否则会出现「拿这一帧的主目标配上一帧的 colortex」，"
                        + "那比不比对更坏：它给出一个看起来有依据、实则无效的结论");
        assertTrue(probe.contains("if (main == null || slot == null"),
                "缺一侧时必须**不出结论**，而不是默认「两者一致」");
    }

    @Test
    @DisplayName("🔖🔖 四种组合都必须能报出来（漏掉的那一格恰好是 GAP-008 的定义形态）")
    void allFourVerdictsAreDistinguishable() {
        // 🔖🔖 本类第一版只写了三种组合，漏掉的正是
        //   「主目标有内容 + colortex 逐像素全黑」这一格 —— 而那恰好是
        //   **GAP-008 的定义形态**（包片元 albedo ≡ 0；主目标此时是原版画面，看着正常）。
        //   漏掉它 ⇒ 日志会说「两源都有内容」⇒ 取证者据此以为 colortex 正常
        //   ⇒ 又一次误判（h31 的收尾就是这样被推翻的）。
        String probe = readOrSkip(PROBE);
        for (String verdict : List.of("BOTH_BLACK", "SLOT_BLACK", "NOT_ON_MAIN", "BOTH_HAVE_CONTENT")) {
            assertTrue(probe.contains("\"" + verdict + "\""),
                    "缺少结论分支 " + verdict + " —— 四种组合必须齐全，"
                            + "少一格就会让某一类现象被读成另一类");
        }
    }

    @Test
    @DisplayName("🔖 探针默认关（GPU→CPU 拷贝不是可以每帧做的事）")
    void probeIsOffByDefault() {
        String config = readOrSkip(CONFIG);
        assertTrue(config.contains("define(\"mrt.pixelProbe\", false)"),
                "mrt.pixelProbe 必须默认 false —— 回读要走一趟 GPU→CPU 拷贝并映射内存，"
                        + "支柱③ 的 B1 预算是「不装包帧时间 ≤ 原版 +2%」");
        assertTrue(config.contains("defineInRange(\"mrt.pixelProbeEvery\""),
                "必须有节流间隔配置项");
    }

    @Test
    @DisplayName("🔖 colortex 回读要的是纹理不是视图（不同能力）")
    void readbackNeedsTextureNotView() {
        // 🔖 GPU→CPU 拷贝的源是 GpuTexture；GpuTextureView 只能当采样器/附件。
        //   两者是不同能力，必须各留一个访问器。
        String pass = readOrSkip(PASS);
        assertTrue(pass.contains("public static GpuTexture slotTexture(int slot)"),
                "必须提供 slotTexture(int) —— 现有 slotView(int) 拿的是视图，不能回读");
        assertTrue(pass.contains("public static GpuTextureView slotView(int slot)"),
                "slotView(int) 必须保留：渲染路径用的是视图，不能为了回读把它改掉");
    }

    @Test
    @DisplayName("🔖🔖 探针的每一条 error/warn 都必须落在「有节流守卫」的方法里")
    void everyProbeErrorIsThrottled() {
        // 🔖 判据不是「数量够少」（那是本轮第一版的写法：写死上限后，加一行正当告警就会破，
        //   而加告警是正当需求）—— 判据是**结构性**的：承载 error/warn 的那个方法里
        //   必须存在去重机制。两种形态都接受：
        //   ① compareAndSet / getAndSet 一次性哨兵；
        //   ② 「只在结论变化时报告」的早退。
        String probe = readOrSkip(PROBE);
        List<String[]> methods = methodsOf(probe);
        assertTrue(!methods.isEmpty(), "解析不出任何方法 —— 守卫的解析器坏了，先修它");
        for (String[] method : methods) {
            String body = method[1];
            if (!body.contains("VkDisp.LOGGER.error(") && !body.contains("VkDisp.LOGGER.warn(")) {
                continue;
            }
            boolean throttled = body.contains("compareAndSet")
                    || body.contains("getAndSet")
                    || body.contains(".equals(lastVerdict)");
            assertTrue(throttled,
                    "方法 " + method[0] + " 里有 error/warn，但整个方法看不到任何去重机制。"
                            + "\n  探针每 N 帧（默认 300）跑一次；无节流的告警会按 N 帧重复，"
                            + "一次三分钟取证就是几十行同样内容"
                            + "（h33 实测同类刷了 2702 行，h34 刷了 499 行）。"
                            + "\n  修法二选一：挂 compareAndSet/getAndSet 一次性哨兵；"
                            + "或改成「结论与上次不同才报」。");
        }
    }

    /**
     * 把源码切成 (方法名, 方法体)。
     *
     * <p>🔖 <b>为什么自己写而不用正则全文扫</b>：本守卫要回答的是
     * 「<b>承载那条日志的方法</b>里有没有守卫」，
     * 而全文逐行找守卫会把「上一个方法的哨兵」误算到下一个方法头上
     * —— 那正是 h33 死开关那类缺陷的形态（看起来有守卫，其实没挂上）。
     */
    private static List<String[]> methodsOf(String source) {
        List<String[]> out = new ArrayList<>();
        Pattern decl = Pattern.compile(
                "^\\s*(?:public|private|protected)\\s+(?:static\\s+)?[\\w.$<>\\[\\]]+\\s+(\\w+)\\s*\\(.*\\)\\s*\\{\\s*$");
        StringBuilder body = new StringBuilder();
        String name = "";
        boolean inMethod = false;
        for (String raw : source.split("\n")) {
            String trimmed = raw.strip();
            if (trimmed.startsWith("*") || trimmed.startsWith("//")) {
                continue;
            }
            if (!inMethod) {
                Matcher matcher = decl.matcher(trimmed);
                if (matcher.matches()) {
                    inMethod = true;
                    name = matcher.group(1);
                    body.append(raw).append('\n');
                }
                continue;
            }
            body.append(raw).append('\n');
            if (trimmed.startsWith("}")) {
                out.add(new String[] {name, body.toString()});
                inMethod = false;
                body.setLength(0);
            }
        }
        if (inMethod) {
            out.add(new String[] {name, body.toString()});
        }
        return out;
    }

    @Test
    @DisplayName("🔖 取到数时必须有 INFO 行（否则探针等于没跑）")
    void probeLogsWhenItGetsNumbers() {
        assertTrue(countCode(readOrSkip(PROBE), "VkDisp.LOGGER.info(") >= 1,
                "探针取到统计后必须打一行 INFO —— 否则「没数字」与「数字是 0」在日志上无法区分");
    }
}