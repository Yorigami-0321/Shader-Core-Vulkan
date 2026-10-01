package dev.vkdisp.config;
/**
 * 【参考调研】P4.3 选项持久化（GUI 改动的包选项 → 落盘 → 下次生成源时回放）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §3.1（只读不写用户包 —— 本文件只写模组自己的
 *    config/ 文件，绝不写 shaderpacks/）与 §3.5（选项值容器 PackOptions，本类是它的持久化外壳）；
 *    ② docs/08-TESTING.md §1 P4.3 验收行（"pack 声明的选项能渲染并能改" —— "改"的跨会话
 *    保持需要本文件）；③ docs/18-PARALLEL.md §5 F 线（选项覆盖链：默认 → profile → 覆盖差分）。
 *    全部为仓库内自有文档事实。文件格式 = 本文件自定义的 properties 风格行集
 *    （{@code #} 注释 + {@code key=value} + 反斜杠转义 —— 自定义并自读自写，非复制任何实现）。
 *    许可证：本文件为独立实现的纯 Java IO 类 → 可并入本项目（MIT）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：无外部实现可参考（两个参考模组的选项 UI 持久化属其私有格式，
 *    且按 07-CONSTRAINTS 禁止读其代码）；按本项目契约自定义格式与语义。
 * 2. 备选：① 把改动写回用户包的 shaders.properties —— 否决（违反 04-SPEC §3.1 只读红线）；
 *    ② 用 java.util.Properties 直接 load/store —— 否决（Properties.store 每次写入时间戳注释、
 *    Hashtable 无插入序，证据行与断言都要求确定性输出；自定义行集 20 行读写器足够且可测）；
 *    ③ 存进 FML 配置（vkdisp-client.toml）—— 否决（选项按包千变万化，静态 ConfigSchema
 *    装不下动态键；且 TOML 被 FileWatcher 监听，写入会触发配置热加载 → 资源重载环）。
 * 3. 我们的差异点：
 *    ① **键 = {@code <packName>.<optionName>}，按最后一个点拆分** —— 选项名是 GLSL 标识符
 *       永不含点，包名可以含点（如 BSL_v10.1.8）；拆分只发生在读取侧，写入侧始终拼全键；
 *    ② 文件**不进 FML 监听**（不叫 *.toml，不注册 ConfigTracker）—— 屏幕自己触发重载，
 *       避免「保存 → 热加载 → 重载 → ……」的回环；
 *    ③ 读取永不抛：缺文件 = 空存储、坏行 = 跳过 + {@link #loadWarnings()}（T11 由调用方落日志）；
 *    ④ 无 dot 的键 / 缺 {@code =} 的行不进存储（写入侧永远产出合法行，坏行只可能来自手编）。
 * 4. 许可证核对结论：本项目 MIT；零第三方代码复制（读写器为本文件独立实现）。
 * 5. 性能基线：❄️ 冷路径（虚拟包 openResources / 选项屏幕开屏 / 完成时各一次整文件读写，
 *    百行级），不做任何性能优化（18-PARALLEL §7.7、07-CONSTRAINTS T14）。
 */
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 包选项覆盖的持久化存储（P4.3 选项 GUI 的"改完还在"半边）。
 *
 * <p><b>文件</b>：{@code <gameDir>/config/vkdisp-pack-options.properties}（{@link #pathFor}）。
 * 每行 {@code <packName>.<optionName>=<value>}，{@code #} 起注释；键按<b>最后一个点</b>拆分
 * （选项名是 GLSL 标识符永不含点，包名可含点）。本文件只写模组自己的 config 目录，
 * 绝不写用户 shaderpacks（04-SPEC §3.1）。
 *
 * <p><b>与生效链的关系</b>：{@code PackCompositeSource.generate} 在 profile 之后、覆盖差分之前
 * 回放本存储（GUI 改动优先于 profile）；本存储本身<b>不触发</b>任何重载
 * （不进 FML 监听 —— 屏幕"完成"时自己调资源重载，见 {@code dev.vkdisp.screen.PackOptionsScreen}）。
 *
 * <p><b>线程</b>：非线程安全的普通容器 —— 使用方都在渲染线程（openResources / 屏幕回调），
 * 读文件在构造时一次完成（{@link #load}）。
 */
public final class PackOptionStore {

    /** 落盘文件名（固定于 {@code config/} 下）。 */
    public static final String FILE_NAME = "vkdisp-pack-options.properties";

    /** 全键（{@code pack.option}）→ 值；插入序保存（证据行与断言要确定性）。 */
    private final LinkedHashMap<String, String> entries = new LinkedHashMap<>();

    /** 读取时跳过的坏行说明（T11：调用方负责落 WARN 日志）。 */
    private final List<String> loadWarnings = new ArrayList<>();

    private PackOptionStore() {
    }

    /** 空存储（无文件语义）。 */
    public static PackOptionStore empty() {
        return new PackOptionStore();
    }

    /**
     * 存储文件路径：{@code <gameDir>/config/vkdisp-pack-options.properties}。
     *
     * @param gameDir 游戏根目录（{@code Minecraft.gameDirectory}）；null → null
     */
    public static Path pathFor(Path gameDir) {
        if (gameDir == null) {
            return null;
        }
        return gameDir.resolve("config").resolve(FILE_NAME);
    }

    /**
     * 从文件读取。永不抛：文件不存在 = 空存储；坏行 = 跳过 + {@link #loadWarnings()}；
     * 读取异常 = 空存储 + 警告（T11）。
     *
     * @param file 目标文件；null / 非常规文件 = 空存储
     */
    public static PackOptionStore load(Path file) {
        PackOptionStore store = new PackOptionStore();
        if (file == null || !Files.isRegularFile(file)) {
            return store;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                    continue; // 注释与空行
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    store.loadWarnings.add("第 " + lineNumber + " 行缺少 '='，已跳过: " + line);
                    continue;
                }
                String key = unescape(trimmed.substring(0, eq));
                String value = unescape(trimmed.substring(eq + 1));
                if (key.lastIndexOf('.') <= 0) {
                    store.loadWarnings.add("第 " + lineNumber + " 行键不含 '<pack>.<option>' 结构，已跳过: " + line);
                    continue;
                }
                store.entries.put(key, value);
            }
        } catch (IOException e) {
            store.loadWarnings.add("读取失败（按空存储继续）: " + e.getMessage());
        }
        return store;
    }

    /** 该包下某选项的持久化值（不存在 = 空）。 */
    public Optional<String> get(String packName, String optionName) {
        return Optional.ofNullable(entries.get(fullKey(packName, optionName)));
    }

    /** 该包下是否存在该选项的持久化条目。 */
    public boolean contains(String packName, String optionName) {
        return entries.containsKey(fullKey(packName, optionName));
    }

    /** 写入 / 覆盖一个条目。 */
    public void put(String packName, String optionName, String value) {
        entries.put(fullKey(packName, optionName), Objects.requireNonNull(value, "value"));
    }

    /** 移除一个条目；返回是否真的存在过。 */
    public boolean remove(String packName, String optionName) {
        return entries.remove(fullKey(packName, optionName)) != null;
    }

    /**
     * 清空指定包的全部条目（"重置本包"）；返回移除数。
     * 其它包的条目不受影响。
     */
    public int clearPack(String packName) {
        int removed = 0;
        var iterator = entries.keySet().iterator();
        while (iterator.hasNext()) {
            if (packOf(iterator.next()).equals(packName)) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    /**
     * 指定包的全部条目（选项名 → 值，文件插入序）。
     * 按<b>最后一个点</b>拆键：{@code a.b.OPT} 属于包 {@code a.b} 而非 {@code a}。
     */
    public Map<String, String> forPack(String packName) {
        LinkedHashMap<String, String> view = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            if (packOf(entry.getKey()).equals(packName)) {
                view.put(optionOf(entry.getKey()), entry.getValue());
            }
        }
        return view;
    }

    /** 出现过的全部包名（文件插入序；{@link #forPack} 拆键口径）。 */
    public LinkedHashSet<String> packNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String key : entries.keySet()) {
            names.add(packOf(key));
        }
        return names;
    }

    /** 是否没有任何条目。 */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** 条目总数（跨所有包）。 */
    public int size() {
        return entries.size();
    }

    /** 读取时跳过的坏行说明（不可变；无坏行 = 空表）。 */
    public List<String> loadWarnings() {
        return List.copyOf(loadWarnings);
    }

    /**
     * 整库写入文件（父目录不存在则创建）。只写本格式的行 —— 覆盖旧内容。
     *
     * @throws IOException 写入失败（调用方按 T11 落 ERROR）
     */
    public void save(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("# vkdisp 包选项覆盖（P4.3 选项 GUI 的「完成」持久化）");
            writer.newLine();
            writer.write("# 格式: <packName>.<optionName>=<value>（键按最后一个点拆分；选项名是 GLSL 标识符不含点）");
            writer.newLine();
            writer.write("# 本文件不进 FML 配置监听：由选项屏幕「完成」时自行触发资源重载。");
            writer.newLine();
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                writer.write(escapeKey(entry.getKey()));
                writer.write('=');
                writer.write(escapeValue(entry.getValue()));
                writer.newLine();
            }
        }
    }

    // ---------------------------------------------------------------- 私有

    /** 全键拼接（写入侧始终拼接，不做拆分）。 */
    private static String fullKey(String packName, String optionName) {
        return Objects.requireNonNull(packName, "packName") + '.' + Objects.requireNonNull(optionName, "optionName");
    }

    /** 最后一个点之前的包名（{@code a.b.OPT} → {@code a.b}；无点 = 非法键，返回全键）。 */
    private static String packOf(String fullKey) {
        int dot = fullKey.lastIndexOf('.');
        return dot <= 0 ? fullKey : fullKey.substring(0, dot);
    }

    /** 最后一个点之后的选项名。 */
    private static String optionOf(String fullKey) {
        int dot = fullKey.lastIndexOf('.');
        return dot <= 0 ? "" : fullKey.substring(dot + 1);
    }

    /** 键侧转义：反斜杠与 {@code =}（值里允许 {@code =}，分隔取第一个 {@code =}）。 */
    private static String escapeKey(String key) {
        StringBuilder text = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            switch (c) {
                case '\\' -> text.append("\\\\");
                case '=' -> text.append("\\=");
                case '\n' -> text.append("\\n");
                case '\r' -> text.append("\\r");
                default -> text.append(c);
            }
        }
        return text.toString();
    }

    /** 值侧转义：反斜杠与换行（值可含 {@code =}，无需转义）。 */
    private static String escapeValue(String value) {
        StringBuilder text = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> text.append("\\\\");
                case '\n' -> text.append("\\n");
                case '\r' -> text.append("\\r");
                default -> text.append(c);
            }
        }
        return text.toString();
    }

    /** 通用反转义：{@code \\X} → {@code X}（写入侧只产出 {@code \\}、{@code \=}、{@code \n}、{@code \r}）。 */
    private static String unescape(String text) {
        if (text.indexOf('\\') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(++i);
                out.append(switch (next) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    default -> next;
                });
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
