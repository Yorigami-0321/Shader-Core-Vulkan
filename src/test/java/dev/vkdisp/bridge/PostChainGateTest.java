package dev.vkdisp.bridge;
/**
 * 【参考调研】GAP-036 半开档自报的守卫 / 只读工程自身源码
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓库自有代码（MIT）。
 *    → 可并入本项目（MIT）：是，本文件只做源码文本与纯函数断言，不引用外部实现。
 * 1. 官方/主实现：无。
 * 2. 备选：无。
 * 3. 我们的差异点：钉住「判定与自报同一条链」——
 *    {@code [GAP-036/半开档]} 那行旧文案点名了它没读的键，于是 {@code GAP-054} 误签了一格「已排除」、
 *    {@code GAP-056} 把一行 WARN 当成了「文件被回写」的证据。这里把那次的教训做成构建期红灯。
 * 4. 许可证核对：本项目 MIT。
 * 5. 性能基线：❄️ 单测（{@link PostChainGate#readout()} 本身不在每帧路径上，注释里写死了理由）。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PostChainGate} 与它那两个消费者（{@code FrameApi} / {@code FullscreenPassHook}）的守卫。
 */
class PostChainGateTest {

    private static final Path GATE = Path.of("src/main/java/dev/vkdisp/bridge/PostChainGate.java");
    private static final Path FRAME_API = Path.of("src/main/java/dev/vkdisp/bridge/FrameApi.java");
    private static final Path HOOK = Path.of("src/main/java/dev/vkdisp/render/FullscreenPassHook.java");
    private static final Path CONFIG = Path.of("src/main/java/dev/vkdisp/VkDispConfig.java");

    private static String readOrSkip(Path path) {
        Assumptions.assumeTrue(Files.exists(path), "工程文件缺失: " + path);
        try {
            return Files.readString(path);
        } catch (java.io.IOException e) {
            throw new AssertionError("读取失败: " + path, e);
        }
    }

    /** 只统计非注释行（javadoc 与 🔖 注释里会引用代码字样，不能算进断言）。 */
    private static int countCode(String text, String literal) {
        int count = 0;
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                continue;
            }
            count += line.split(Pattern.quote(literal), -1).length - 1;
        }
        return count;
    }

    /** 取某个方法体内的文本（按大括号配平，够用：这两个方法都没有嵌套到需要解析器的程度）。 */
    private static String bodyOf(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "找不到方法：" + signature);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open, i + 1);
                }
            }
        }
        throw new AssertionError("方法体大括号不配平：" + signature);
    }

    /** 按出现顺序取出方法体里调到的 {@code gate…()} 谓词名。 */
    private static List<String> gateCallsInOrder(String body) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\b(gate[A-Za-z]+)\\(\\)").matcher(body);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static String[] gateNames() {
        String body = bodyOf(readOrSkip(GATE), "String[] GATE_NAMES");
        List<String> names = new ArrayList<>();
        Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(body);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names.toArray(new String[0]);
    }

    @Test
    @DisplayName("📐 describe 真值表：只写读过的项，第一项不成立即停笔")
    void describeOnlyPrintsWhatWasMeasured() {
        String[] names = gateNames();
        assertEquals(6, names.length, "六个合取项是 GAP-036 那条撤回的立论基础，数量变了必须同步文案");

        boolean[] all = {true, true, true, true, true, true};
        String allText = PostChainGate.describe(all, 6, true);
        assertTrue(allText.endsWith("⇒ 链跑"), allText);
        for (String name : names) {
            assertTrue(allText.contains(name + "=true"), "全真时六项都要出现，缺 " + name + "：" + allText);
        }

        boolean[] firstFails = {false, true, true, true, true, true};
        String oneItem = PostChainGate.describe(firstFails, 1, false);
        assertEquals(names[0] + "=false ⇒ 链不跑", oneItem,
                "🔴 第一项就不成立时，后面的项**没被读过**，不许出现在自报里（那正是 GAP-056 的失效模式）");

        boolean[] middleFails = {true, true, false, false, false, false};
        String three = PostChainGate.describe(middleFails, 3, false);
        assertTrue(three.contains(names[2] + "=false"), "停笔那一项本身要出现：" + three);
        assertFalse(three.contains(names[3]), "停在第三项 ⇒ 第四项及之后不许出现：" + three);
    }

    @Test
    @DisplayName("🔗 active() 与 readout() 用的是同一组谓词、同一个顺序（两处漂移=日志说的不是代码判的）")
    void activeAndReadoutShareTheOrder() {
        String source = readOrSkip(GATE);
        List<String> activeOrder = gateCallsInOrder(bodyOf(source, "public static boolean active()"));
        List<String> readoutOrder = gateCallsInOrder(bodyOf(source, "public static String readout()"));
        assertEquals(activeOrder, readoutOrder,
                "🔴 判定顺序与自报顺序不一致：active()=" + activeOrder + " readout()=" + readoutOrder);
        assertEquals(gateNames().length, activeOrder.size(),
                "🔴 GATE_NAMES 的条数与谓词调用数不等");
    }

    @Test
    @DisplayName("🏷 自报里点名的都是**真存在的配置键**（旧文案点了个没读的键 ⇒ 立红灯）")
    void gateNamesAreRealKeysOrExplicitProse() {
        Set<String> declared = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\\.define[A-Za-z]*\\(\"([^\"]+)\"").matcher(readOrSkip(CONFIG));
        while (m.find()) {
            declared.add(m.group(1));
        }
        List<String> unexplained = new ArrayList<>();
        for (String name : gateNames()) {
            String key = name.startsWith("!") ? name.substring(1) : name;
            boolean realKey = declared.contains(key);
            boolean prose = !key.contains(".");
            if (!realKey && !prose) {
                unexplained.add(name);
            }
        }
        assertTrue(unexplained.isEmpty(),
                "🔴 自报名里出现了「带点、但 VkDispConfig 里没有这个键」的项：" + unexplained);
    }

    @Test
    @DisplayName("🧭 FrameApi 不再自己抄一遍六项判定（判定单点在 PostChainGate）")
    void frameApiDelegatesToTheGate() {
        String frameApi = readOrSkip(FRAME_API);
        String body = bodyOf(frameApi, "public static boolean isPostChainActive()");
        assertTrue(body.contains("PostChainGate.active()"), "isPostChainActive 必须委托门控：" + body);
        assertEquals(0, countCode(frameApi, "MRT_PACK_TERRAIN_SHADER"),
                "🔴 FrameApi 里又自己读了一遍链门控的键 ⇒ 两处会漂移");
    }

    @Test
    @DisplayName("🔴 半开档那行 WARN 只打它真测过的量，并把「为什么没跑」交给门控自报")
    void halfOpenWarnNamesOnlyWhatItMeasures() {
        String hook = readOrSkip(HOOK);
        assertEquals(0, countCode(hook, "mrt.packTerrainShader=false"),
                "🔴 [GAP-036/半开档] 的文案里又出现了它没读的键（GAP-056 那条撤回的原点）");
        assertTrue(hook.contains("PostChainGate.readout()"),
                "🔴 半开档那行没把「链为什么没跑」交给 PostChainGate.readout()");
        assertTrue(hook.contains("if (MrtTerrainPass.enabled() && !chainActive && !MrtTerrainPass.toMain())"),
                "半开档的判据本身没变（三量），变了要同步 GAP-036/GAP-056 的说明");
    }
}
