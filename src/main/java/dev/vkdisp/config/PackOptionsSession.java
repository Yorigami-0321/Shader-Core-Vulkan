package dev.vkdisp.config;
/**
 * 【参考调研】P4.3 选项会话（选项屏幕的纯逻辑内核：基线 / 工作值 / 触碰差分 / 提交）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §3.5（PackOptions 值容器与归一化 —— 本类只编排，不改其语义）；
 *    ② docs/18-PARALLEL.md §5 P2.4 ④ 选项覆盖链（默认 → profile → 覆盖差分 —— 本类的基线取
 *    「默认+profile」正是该链的前半段快照）；③ docs/08-TESTING.md §1 P4.3 验收行
 *    （"pack 声明的选项能渲染并能改" —— "改"的差分与提交由本类定义）；④
 *    dev.vkdisp.config.PackOptionStore（持久化外壳，本类的提交目标）。全部为仓库内自有事实。
 *    许可证：本文件为独立实现的纯 Java 模型类 → 可并入本项目（MIT）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：无外部实现可参考（参考模组的选项会话按禁止处理，只按本项目契约自编排）。
 * 2. 备选：① 屏幕直接持有 PackOptions + 手写差分 —— 否决（差分/提交是可测的纯逻辑，散进
 *    MC 屏幕类就没了单测）；② 提交时把**全部**当前值写进存储 —— 否决（会把 profile 值也固化
 *    进存储，之后改 profile 永远不生效 —— 存储只该记"用户真动过的"）；③ 存相对差分
 *    （记录对基线的 delta 回放）—— 否决（基线随 profile 变，delta 会漂移；存绝对值，
 *    应用顺序 默认→profile→store 保证 store 永远最后生效，语义稳定）。
 * 3. 我们的差异点：
 *    ① **三层快照**：baseline = 默认+profile（进存储前的工作值）；working = baseline + store 回放；
 *       触碰集 = working 与 baseline 的差分 —— 提交只写触碰集、只删"改回去了"的旧条目；
 *    ② GUI 优先于 profile：store 在 profile **之后**回放（与 PackCompositeSource 生效链同序）；
 *    ③ 存储里存在但包已不声明的选项 → 保留不动 + WARN（T11，绝不静默删除用户的持久化数据）；
 *    ④ 分页是纯函数（{@link #pageCount} / {@link #pageOptions}），页码指针也收在本类 ——
 *       屏幕只做布局，不持有任何业务状态。
 * 4. 许可证核对结论：本项目 MIT；零第三方代码复制（只编排本项目 F 线既有组件）。
 * 5. 性能基线：❄️ 冷路径（开屏一次、改值一次、完成一次，均为百项级内存操作），不做优化（T14）。
 */
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import dev.vkdisp.pack.Option;
import dev.vkdisp.pack.ShaderPack;

/**
 * 一次选项屏幕会话的纯逻辑状态（P4.3）：选项容器 + 基线 + 分页 + 提交。
 * 不含任何 {@code net.minecraft.*} —— 供单测直接驱动。
 *
 * <p><b>典型链</b>（与 {@code PackCompositeSource.generate} 的生效链严格同序）：
 * {@link PackOptions#of(pack)} → {@link PackOptions#applyProfile}（快照 = baseline）
 * → {@link PackOptionStore} 回放（GUI 改动优先于 profile）→ 交互 {@link #set} →
 * {@link #commit} 写回存储（仅触碰项）→ 调用方存盘并触发资源重载。
 *
 * <p><b>生命周期</b>：每次开屏 {@link #create} 一个新实例；"重置本包" = 清存储后重新
 * {@link #create}；页面指针是唯一可变的 UI 状态。
 */
public final class PackOptionsSession {

    /** 提交结果：写入几条、删除几条（删除 = 曾持久化但已改回基线的条目），以及写入的键值对。 */
    public record CommitResult(int stored, int removed, List<String> changedEntries) {
        /** 归一构造：列表冻结。 */
        public CommitResult {
            changedEntries = changedEntries == null ? List.of() : List.copyOf(changedEntries);
        }
    }

    private final ShaderPack pack;
    private final String profileName;
    private final PackOptions options;
    private final Map<String, String> baseline;
    private final List<OptionDiagnostic> buildDiagnostics;
    private int page;

    private PackOptionsSession(ShaderPack pack, String profileName, PackOptions options,
            Map<String, String> baseline, List<OptionDiagnostic> buildDiagnostics) {
        this.pack = pack;
        this.profileName = profileName;
        this.options = options;
        this.baseline = baseline;
        this.buildDiagnostics = List.copyOf(buildDiagnostics);
        this.page = 0;
    }

    /**
     * 按生效链构造一次会话：默认 → profile（空白名跳过）→ store 回放。
     * 构建期诊断（profile 错名、store 未知选项等）收进 {@link #buildDiagnostics()}。
     *
     * @param pack        目标包（非 null）
     * @param profileName profile 名；null / 空白 = 包默认值
     * @param store       持久化覆盖；null = 无（等价空存储）
     */
    public static PackOptionsSession create(ShaderPack pack, String profileName, PackOptionStore store) {
        Objects.requireNonNull(pack, "pack");
        String profile = profileName == null ? "" : profileName.trim();
        List<OptionDiagnostic> diagnostics = new ArrayList<>();
        PackOptions options = PackOptions.of(pack, OptionDiagnosticSink.collecting(diagnostics));
        if (!profile.isEmpty()) {
            options.applyProfile(profile, pack.profiles());
        }
        // baseline 快照 = 进存储回放前（默认+profile）—— 提交差分的参照。
        Map<String, String> baseline = new LinkedHashMap<>(options.values());
        if (store != null) {
            applyStore(store, pack.name(), options, diagnostics);
        }
        return new PackOptionsSession(pack, profile, options, baseline, diagnostics);
    }

    /** 存储回放：本包条目 → {@link PackOptions#set}（归一化与诊断走原通道）；未知选项保留不动 + WARN。 */
    private static void applyStore(PackOptionStore store, String packName, PackOptions options,
            List<OptionDiagnostic> diagnostics) {
        for (Map.Entry<String, String> entry : store.forPack(packName).entrySet()) {
            if (!options.contains(entry.getKey())) {
                diagnostics.add(OptionDiagnostic.warn("STORE_UNKNOWN_OPTION",
                        "持久化选项 '" + entry.getKey() + "' 不在当前包的选项定义中，已跳过"
                                + "（条目保留未动，可用「重置本包」清除）"));
                continue;
            }
            options.set(entry.getKey(), entry.getValue()); // 诊断经 collecting sink 汇入
        }
    }

    /** 目标包（屏幕「重置本包」重建会话用）。 */
    public ShaderPack pack() {
        return pack;
    }

    /** 实际生效的 profile 名（空串 = 默认值路径）。 */
    public String profileName() {
        return profileName;
    }

    /** 包名（= 存储键的包段）。 */
    public String packName() {
        return pack.name();
    }

    /** 选项容器（屏幕渲染读 {@link PackOptions#definitions()} / {@link PackOptions#value}）。 */
    public PackOptions options() {
        return options;
    }

    /** 全部可渲染选项（包声明顺序：可见且未禁用）。 */
    public List<Option> definitions() {
        return options.definitions();
    }

    /** 基线快照（默认+profile，不可变）：与当前值不同的选项即"触碰项"。 */
    public Map<String, String> baseline() {
        return Map.copyOf(baseline);
    }

    /** 构建期诊断（profile / store 回放产生；不可变）。 */
    public List<OptionDiagnostic> buildDiagnostics() {
        return buildDiagnostics;
    }

    /** 改一个选项（走 {@link PackOptions#set} 全套归一化，诊断经 sink 实时输出）。 */
    public PackOptions.SetOutcome set(String name, String rawValue) {
        return options.set(name, rawValue);
    }

    /** 当前触碰项数（工作值 ≠ 基线）。 */
    public int changedCount() {
        int count = 0;
        for (Map.Entry<String, String> entry : options.values().entrySet()) {
            if (!entry.getValue().equals(baseline.get(entry.getKey()))) {
                count++;
            }
        }
        return count;
    }

    /** 当前触碰项（选项名=当前值，声明序）。 */
    public List<String> changedEntries() {
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> entry : options.values().entrySet()) {
            if (!entry.getValue().equals(baseline.get(entry.getKey()))) {
                changed.add(entry.getKey() + "=" + entry.getValue());
            }
        }
        return List.copyOf(changed);
    }

    /**
     * 提交进存储（不落盘 —— 落盘由调用方 {@link PackOptionStore#save} 负责）：
     * 触碰项写入；"改回基线"的旧条目删除；存储里本包的未知条目不动（见 {@link #create}）。
     */
    public CommitResult commit(PackOptionStore store) {
        Objects.requireNonNull(store, "store");
        int stored = 0;
        int removed = 0;
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> entry : options.values().entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (!value.equals(baseline.get(name))) {
                store.put(pack.name(), name, value);
                stored++;
                changed.add(name + "=" + value);
            } else if (store.contains(pack.name(), name)) {
                store.remove(pack.name(), name);
                removed++;
            }
        }
        return new CommitResult(stored, removed, changed);
    }

    // ---------------------------------------------------------------- 分页

    /** 当前页号（0 基）。 */
    public int page() {
        return page;
    }

    /** 设置页号（越界钳到合法区间；返回钳后值）。 */
    public int page(int candidate, int pageSize) {
        int pages = pageCount(pageSize);
        this.page = Math.max(0, Math.min(candidate, pages - 1));
        return this.page;
    }

    /** 页数（每页 {@code pageSize} 项，至少 1 页；pageSize ≤ 0 视为 1）。 */
    public int pageCount(int pageSize) {
        int size = Math.max(1, pageSize);
        int count = definitions().size();
        return count == 0 ? 1 : (count + size - 1) / size;
    }

    /** 指定页的选项切片（声明序；越界页钳到合法区间后返回）。 */
    public List<Option> pageOptions(int pageIndex, int pageSize) {
        int size = Math.max(1, pageSize);
        List<Option> all = definitions();
        int page = page(pageIndex, size);
        int from = page * size;
        if (from >= all.size()) {
            return List.of();
        }
        return List.copyOf(all.subList(from, Math.min(from + size, all.size())));
    }
}
