package dev.vkdisp.screen;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.vkdisp.pack.ShaderPackScanner;

/**
 * 【临时】包选择候选的纯逻辑（无 MC 依赖，可单测）。
 *
 * <p>把 {@code shaderpacks/} 的扫描结果转成下拉框的候选列表，并处理「当前配置值不在候选里」
 * 的情形（X9：不猜、不静默丢包）。
 *
 * <p><b>为什么单独拆一类</b>：Screen 类要import {@code net.minecraft.*}，无法进单测；
 * 候选构建 + 值归一是本临时工具的全部业务逻辑，拆开后可有单测覆盖。
 *
 * <p>许可证：本文件为独立编写的纯 Java，不含任何外部项目代码（07-CONSTRAINTS §〇 P1）。
 */
public final class PackPickerCandidates {

    /** 自动选择的保留值（= {@code VkDispConfig.SHADER_PACK} 默认空串的语义标记）。 */
    public static final String AUTO = "";

    /** 强制 passthrough 的保留值。 */
    public static final String NONE = "none";

    /** 一条候选：写入配置的原始值（{@link #AUTO} / {@link #NONE} / 包名）+ 下拉里显示的文本。 */
    public record Choice(String value, String label) {}

    /**
     * 一次候选构建的完整结果。
     *
     * @param choices       下拉候选（首两条恒为自动 / passthrough，其后按包名排序）
     * @param currentValue   当前配置里的 {@code shaderPack} 原值（仅回显，本类不改它）
     * @param currentInList  currentValue 是否命中候选；false 时下拉需要补一条「当前值（不存在）」
     * @param packCount     扫到的合法包数
     * @param problemCount  扫描问题条目数（> 0 时UI 需提示，但不是错误）
     * @param inventoryExists shaderpacks 目录是否存在
     */
    public record Result(List<Choice> choices, String currentValue, boolean currentInList,
            int packCount, int problemCount, boolean inventoryExists) {

        /** 记录构造：null 归一。 */
        public Result {
            choices = choices == null ? List.of() : List.copyOf(choices);
            currentValue = currentValue == null ? AUTO : currentValue;
        }
    }

    private PackPickerCandidates() {}

    /**
     * 构建候选列表。
     *
     * <p>顺序：自动 → passthrough → 包名（字典序）。**包名原样作为配置值**——
     * 与 {@code PackCompositeSource} 的「按包名精确匹配」口径一致，本类不做任何名称改写。
     *
     * @param scanResult    扫描结果（{@code null} 当作空结果，不抛）
     * @param currentValue  当前配置值（{@code null} 归一为 {@link #AUTO}）
     * @param inventoryExists shaderpacks 目录是否存在（用于 UI 提示）
     */
    public static Result build(ShaderPackScanner.ScanResult scanResult,
            String currentValue, boolean inventoryExists) {
        String current = currentValue == null ? AUTO : currentValue;
        List<ShaderPackScanner.DiscoveredPack> packs =
                scanResult == null ? List.of() : scanResult.packs();
        int problems = scanResult == null ? 0 : scanResult.problems().size();

        List<String> names = new ArrayList<>();
        for (ShaderPackScanner.DiscoveredPack pack : packs) {
            //同名去重：两个条目同名时下拉值会撞车，只留第一个（与扫描顺序一致，T11 由扫描日志兜底）
            if (!names.contains(pack.name())) {
                names.add(pack.name());
            }
        }
        names.sort(String::compareTo);

        List<Choice> choices = new ArrayList<>();
        choices.add(new Choice(AUTO, defaultAutoLabel()));
        choices.add(new Choice(NONE, defaultNoneLabel()));
        boolean inList = current.isEmpty() || NONE.equals(current);
        for (String name : names) {
            choices.add(new Choice(name, name));
            if (name.equals(current)) {
                inList = true;
            }
        }
        return new Result(choices, current, inList, names.size(), problems, inventoryExists);
    }

    /**
     * 候选值 → 下拉里显示的文本。
     *
     * <p><b>为什么必须有这个反查</b>（2026-10-02 用户实测缺陷「none 一开始不显示任何字」）：
     * {@code CycleButton} 只拿到<b>值</b>，显示文本由构造时传入的
     * {@code Function<T, Component>} 决定。{@code shaderPack} 的「自动」态配置值是
     * <b>空串</b>，直接把它当文本渲染 → 空字符串 → 按钮上一片空白。
     * 所以文本必须走 {@link Choice#label()} 而不是 {@link Choice#value()}。
     *
     * <p>值不在候选里（当前配置指向一个已被删除的包）→ 返回值本身而不是空串：
     * 宁可显示原始包名，也不显示空白（X9：不静默、不猜测该显示什么）。
     *
     * @param result 候选构建结果；null → 返回 {@code fallbackText}
     * @param value  下拉当前值（null 归一为 {@link #AUTO}）
     * @return 显示文本，永不为 null、永不空白（除非 {@code fallbackText} 本身空白）
     */
    public static String labelOf(Result result, String value) {
        String key = value == null ? AUTO : value;
        if (result != null) {
            for (Choice choice : result.choices()) {
                if (choice.value().equals(key)) {
                    return choice.label();
                }
            }
        }
        return key.isEmpty() ? defaultAutoLabel() : key;
    }

    /** 「自动」态的固定显示文本（{@link #AUTO} 的 value 是空串，不能直接当文本用）。 */
    public static String defaultAutoLabel() {
        return "自动（扫描序第一个）";
    }

    /** 「强制 passthrough」态的固定显示文本。 */
    public static String defaultNoneLabel() {
        return "passthrough（不加载包）";
    }

    /** 下拉初始值：命中候选就用当前值，否则补一条占位（原样回显，不静默改成别的包）。
     *
     * <p>补出来的占位值带一个不可能与真实包名相等的哨兵前缀
     * （{@code §missing§} 不含路径分隔符与盘符，真实包名不可能匹配），
     * 选中它等于「不改配置」，{@link #resolve} 会把它归一回「保持原值」。
     */
    public static String initialSelection(Result result) {
        if (result == null) {
            return AUTO;
        }
        return result.currentInList() ? result.currentValue() : missingLabel();
    }

    /** 「当前值不在库存」占位项的固定文本。 */
    public static String missingLabel() {
        return "§missing§（当前配置值，库存无此包）";
    }

    /**
     * 下拉选中值 → 要写入配置的 {@code shaderPack} 值。
     *
     * <p>占位项 / null / 空串统一归一为 {@link #AUTO}——**空串就是自动的合法配置值**，
     * 不需要哨兵（{@link #initialSelection} 用哨兵只为在 UI 上区分「自动」与「不存在的当前值」）。
     */
    public static String resolve(String selected) {
        if (selected == null || selected.isEmpty() || missingLabel().equals(selected)) {
            return AUTO;
        }
        return selected;
    }

    /** 一行状态文本（页眉用；纯字符串，无MC 依赖）。 */
    public static String statusLine(Result result) {
        if (result == null) {
            return "候选构建失败";
        }
        if (!result.inventoryExists()) {
            return "shaderpacks/ 目录不存在（packCount=0）";
        }
        if (result.packCount() == 0) {
            return "shaderpacks/ 内没有合法包（problems=" + result.problemCount() + "）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("包数=").append(result.packCount());
        if (result.problemCount() > 0) {
            sb.append("  问题=").append(result.problemCount()).append("（详见日志）");
        }
        if (!result.currentInList()) {
            sb.append("  当前值 '").append(result.currentValue()).append("' 不在库存");
        }
        return sb.toString();
    }
}
