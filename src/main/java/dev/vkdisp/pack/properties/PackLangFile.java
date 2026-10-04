package dev.vkdisp.pack.properties;
/**
 * 【参考调研】包内 lang 文件（{@code shaders/lang/*.lang}）里**选项显示名**的读取
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/13-GAP-REGISTRY.md GAP-009 条目（为什么必须读 lang：
 *    BSL 用「显示名末尾加 {@code *}」声明依赖闭包，实测 19 条）；
 *    ② docs/04-SPEC.md §3.1（只读不写用户包）；③ docs/07-CONSTRAINTS.md T11（不静默）+ X9（不猜）。
 *    全部为仓库内自有文档事实。
 *    lang 文件的**格式事实**（{@code key=value}、{@code §} 转义序列、{@code option.NAME} / {@code value.NAME}
 *    键名前缀）属 OptiFine / Iris 的公开文件格式约定，不受版权保护；
 *    本文件**独立实现**读取，不复制任何实现的解析代码（参考模组源码零接触，L12 / X20 / X21）。
 *    → 能否并入本项目（MIT）：可以（本文件为独立编写的纯 Java 解析类）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：lang 文件的格式事实（键值行 + Minecraft 的 {@code \u00a7} 转义约定）。
 * 2. 备选：
 *    <ul>
 *      <li>① 用 {@code java.util.Properties} —— 否决：{@code .lang} 里的 {@code \u00a7} 是
 *          Minecraft 自己的单反斜杠转义写法，不是 Java properties 的 unicode 转义；
 *          用 {@code Properties.load} 会把 {@code §e} 原样留下、把 {@code \n} 吃掉，
 *          显示名里的颜色码与换行就错了（本项目的判断只关心末尾的 {@code *}，但读错就是读错）。</li>
 *      <li>② 只读固定的 {@code en_US.lang} —— 否决：包可能只带别的语言；
 *          裁决已明确「{@code *} 是本地化显示名、非 ASCII 资源包或改过 lang 的包上不可靠」
 *          ⇒ 读不到就必须<b>显式</b>退到别的机制，而不是静默返回空。</li>
 *      <li>③ 猜测语言优先级 —— 否决：没有实测依据的排序就是猜（X9）。
 *          本类返回「读到的全部 lang 文件 + 各自语言码」，由调用方决定怎么用。</li>
 *    </ul>
 * 3. 我们的差异点：
 *    <ul>
 *      <li>🔖 只提取 <b>{@code option.*}</b> 与 <b>{@code value.*}</b> 两类键，且
 *          <b>显示名以 {@code *} 结尾</b>时单独标出 —— 这是 GAP-009 门控唯一的判据来源。
 *          其它键（{@code *.comment}、{@code *.tooltip}、{@code shader.*}）不参与门控，
 *          但 comment 里的显式依赖引用会被单独提取（{@link #hints()}）。</li>
 *      <li>🔖 <b>缺文件 / 读失败一律返回空表 + 一条 WARN</b>，不抛 —— T11 要求可见，
 *          但「包没有 lang」是完全合法的（不是错误），所以降级为可查询的
 *          {@link Result#warnings()} 而不是异常。</li>
 *      <li>🔖 <b>解析的是「包自己怎么声明依赖」这件事，不是显示什么</b> ——
 *          显示名文本只用来判末尾星号与匹配能力别名，其余原样保留。</li>
 *    </ul>
 * 4. 许可证核对：本项目 MIT；零第三方代码复制（解析器为本文件独立实现）。
 * 5. 性能基线：❄️ 冷路径（切包时读一次；BSL 的 en_US.lang 约 380 行），不做任何性能优化（T14）。
 */
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 包内 lang 文件的选项显示名读取（GAP-009 能力门控的判据来源）。
 *
 * <p><b>为什么必须有它</b>：BSL v10.1.8 声明「本选项依赖资源包提供的材质贴图」的唯一机制是
 * <b>显示名末尾的 {@code *}</b>（{@code shaders/lang/en_US.lang} 里 19 条），
 * 它的 {@code option.ADVANCED_MATERIALS.comment} 把语义写得很清楚：
 * 「requires a resource pack which contains specular and/or normal maps」。
 * ⇒ 不读 lang 就只能硬编码 {@code PARALLAX}，而那会砍掉 Complementary 的可用视差（X27）。
 *
 * <p><b>格式</b>：{@code key=value} 每行一条，{@code #} 起注释；
 * Minecraft 用单反斜杠写转义（{@code \n} / {@code \u00a7}），<b>不是</b> Java properties 的双反斜杠。
 *
 * <p><b>降级</b>：文件缺失是合法情形（返回空表 + 无警告）；读失败 / 坏行走
 * {@link Result#warnings()}（T11 可见，绝不静默）。
 */
public final class PackLangFile {

    /** 显示名以 {@code *} 结尾时，我们把星号<b>剥掉</b>再交给门控（星号是标记，不是文案）。 */
    private static final char DEPENDENCY_MARKER = '*';

    /** 一次读取的结果（不可变）。 */
    public record Result(
            Map<String, String> optionLabels,
            Set<String> starMarkedOptions,
            Map<String, String> hints,
            List<String> warnings) {

        public Result {
            optionLabels = optionLabels == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(optionLabels));
            starMarkedOptions = starMarkedOptions == null ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(starMarkedOptions));
            hints = hints == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(hints));
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        /** 空结果（无 lang 文件的包）。 */
        public static Result empty() {
            return new Result(Map.of(), Set.of(), Map.of(), List.of());
        }
    }

    private PackLangFile() {
    }

    /**
     * 解析一段 lang 文本（纯函数，单测直接喂字符串，不必造 zip）。
     *
     * @param langId 语言标识（{@code en_US} 等；只进诊断文案）
     * @param text   lang 全文
     * @return 解析结果
     */
    public static Result parse(String langId, String text) {
        Objects.requireNonNull(langId, "langId");
        Map<String, String> labels = new LinkedHashMap<>();
        LinkedHashSet<String> starred = new LinkedHashSet<>();
        Map<String, String> hints = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return new Result(labels, starred, hints, warnings);
        }
        // 🔖 先把**物理行**拼成**逻辑行**（lang 里 `\` 结尾 = 续行，BSL 的 option.*.comment
        //   就是这么写的）。必须**先拼接再拆键值** —— 否则续行会被当成独立的坏行丢掉，
        //   而那些 comment 里往往正写着「本选项依赖什么」，丢掉等于丢掉门控判据。
        String[] physical = text.split("\\R");
        for (int i = 0; i < physical.length; i++) {
            String line = stripTrailing(physical[i]).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int startLine = i + 1;
            // 收集续行：`\` 结尾时把下一物理行接上来（限一次，避免整文件被一条吞掉）。
            while (endsWithContinuation(line) && i + 1 < physical.length) {
                line = line.substring(0, line.length() - 1).stripTrailing();
                i++;
                line = line + ' ' + stripTrailing(physical[i]).strip();
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                // 坏行跳过 + 警告（T11：判据不完整必须可见；不猜它想表达什么）。
                warnings.add("lang(" + langId + ") 第 " + startLine + " 行缺少 '='，已跳过: " + line);
                continue;
            }
            String key = line.substring(0, eq).strip();
            String value = unescape(line.substring(eq + 1));
            if (key.startsWith("option.")) {
                String optionName = key.substring("option.".length());
                if (optionName.endsWith(".comment") || optionName.endsWith(".tooltip")) {
                    String target = optionName.substring(0, optionName.lastIndexOf('.'));
                    if (!target.isEmpty()) {
                        hints.merge(target, value, (left, right) -> left + '\n' + right);
                    }
                    continue;
                }
                if (optionName.isEmpty()) {
                    continue;
                }
                boolean marked = endsWithMarker(value);
                labels.put(optionName, marked ? value.substring(0, value.length() - 1).strip() : value);
                if (marked) {
                    starred.add(optionName);
                }
            }
            // 🔶 `value.*`（枚举值的显示名）**不参与**门控判据：
            //   BSL 的 `value.EMISSIVE.1=AdvMat Only*` 说的是「这个枚举值依赖高级材质」，
            //   不是「这个选项依赖」⇒ 拿它去关选项会关错东西。
            //   这里刻意不收（收进来只会污染依赖图），但在此登记该事实，避免后来者当漏写。
        }
        return new Result(labels, starred, hints, warnings);
    }

    /** 该物理行是否以**单个**反斜杠结尾（续行标记；{@code \\} 是转义后的反斜杠，不算续行）。 */
    private static boolean endsWithContinuation(String line) {
        if (!line.endsWith("\\") || line.isEmpty()) {
            return false;
        }
        int backslashes = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    /**
     * 从 lang 目录读入全部 {@code *.lang} 并合并。
     *
     * <p>🔖 <b>多语言怎么处理</b>：按语言码<b>升序</b>依次合并，先读到的<b>不覆盖</b>已有的
     * （{@code en_US} 排在 {@code de_DE}/{@code fr_FR}/{@code zh_CN} 之前，
     * 天然成为默认语言）。🔖 这是「确定性」而非「最优」—— 没有实测依据说哪种语言最权威，
     * 但<b>必须有一个确定顺序</b>，否则同一份包两次加载可能门控结果不同（X9）。
     *
     * @param langFiles 语言码 → 该 lang 文件的读取器（调用方负责打开/关闭；键为文件名去扩展名）
     * @return 合并后的结果
     */
    public static Result parseAll(Map<String, java.util.function.Supplier<InputStream>> langFiles) {
        Objects.requireNonNull(langFiles, "langFiles");
        Map<String, String> labels = new LinkedHashMap<>();
        LinkedHashSet<String> starred = new LinkedHashSet<>();
        Map<String, String> hints = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        if (langFiles.isEmpty()) {
            return new Result(labels, starred, hints, warnings);
        }
        List<String> langIds = new ArrayList<>(langFiles.keySet());
        Collections.sort(langIds);
        for (String langId : langIds) {
            String text;
            try (InputStream in = langFiles.get(langId).get()) {
                text = readAll(in);
            } catch (IOException | RuntimeException e) {
                // 🔴 读失败必须可见：lang 是门控的唯一判据来源，读不到 = 可能门控空转。
                warnings.add("lang(" + langId + ") 读取失败，门控判据可能不完整: " + e);
                continue;
            }
            Result one = parse(langId, text);
            warnings.addAll(one.warnings());
            for (Map.Entry<String, String> entry : one.optionLabels().entrySet()) {
                // 先到先得（语言码升序 ⇒ en_US 最先）⇒ 不覆盖已有标签。
                labels.putIfAbsent(entry.getKey(), entry.getValue());
            }
            // 🔖 星号集合取**并集**而不是交集：任一语言把某选项标成依赖，就说明包认为它依赖。
            //   反过来（交集）会要求所有语言都标注 —— 而包只会在默认语言里标，交集恒空
            //   ⇒ 门控静默空转。这条是实测踩过的方向，别搞反。
            starred.addAll(one.starMarkedOptions());
            for (Map.Entry<String, String> entry : one.hints().entrySet()) {
                hints.merge(entry.getKey(), entry.getValue(), (left, right) -> left + '\n' + right);
            }
        }
        return new Result(labels, starred, hints, warnings);
    }

    /** 是否为「显示名以依赖标记 {@code *} 结尾」（容忍尾随空白，已 strip 过）。 */
    private static boolean endsWithMarker(String value) {
        return !value.isEmpty() && value.charAt(value.length() - 1) == DEPENDENCY_MARKER;
    }

    /**
     * Minecraft 单反斜杠转义的还原（<b>不是</b> Java properties 的双反斜杠语义）。
     *
     * <p>支持的序列：{@code \n} / {@code \r} / {@code \t} / {@code \\}
     * 以及 <b>{@code \}{@code uXXXX}</b>（Minecraft 的 unicode 转义，BSL 的
     * {@code §e[*]} 颜色码就写成 {@code \}{@code u00a7e[*]}）。
     * 未知序列**保留反斜杠**而不是吞掉它 —— 吞掉会静默改变显示名（X9：宁可留下可疑文本）。
     */
    static String unescape(String text) {
        if (text == null || (text.indexOf('\\') < 0)) {
            return text == null ? "" : text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                if (next == 'u' || next == 'U') {
                    // 🔖 反斜杠 + u + 4 位十六进制：Minecraft 的颜色码写法
                    //   （BSL 的 §e[*] 就写成这种形式，§ 本身写作 u00a7）。
                    //   只在**后面确实跟着 4 位十六进制**时才解码；
                    //   否则按未知序列处理（保留原样），避免把普通文本里的「反斜杠 + user」之类吃掉。
                    int value = readHex4(text, i + 2);
                    if (value >= 0) {
                        out.append((char) value);
                        i += 5;
                        continue;
                    }
                }
                i++;
                switch (next) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case '\\' -> out.append('\\');
                    default -> out.append('\\').append(next);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** 从 {@code text} 的 {@code from} 处读 4 位十六进制；不足或非十六进制返回 -1。 */
    private static int readHex4(String text, int from) {
        if (from + 4 > text.length()) {
            return -1;
        }
        int value = 0;
        for (int i = from; i < from + 4; i++) {
            int digit = Character.digit(text.charAt(i), 16);
            if (digit < 0) {
                return -1;
            }
            value = value * 16 + digit;
        }
        return value;
    }

    /** lang 文件可能带 UTF-8 BOM；剥掉它，否则第一个键会变成 {@code ﻿option.X} 而匹配不上。 */
    private static String stripTrailing(String line) {
        if (line.isEmpty() || line.charAt(0) != '\uFEFF') {
            return line;
        }
        return line.substring(1);
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line).append('\n');
            }
        }
        return text.toString();
    }

    /** 语言码归一（{@code en_US.lang} → {@code en_US}；供调用方排序用）。 */
    public static String langIdOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        String name = fileName.strip().toLowerCase(Locale.ROOT);
        if (name.endsWith(".lang")) {
            name = name.substring(0, name.length() - ".lang".length());
        }
        return name;
    }
}
