package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】取证「取证件」—— 当前**实际生效**的包选项覆盖（防静默空转）
 * 0. 合规核对（第 0 步闸门）：
 *    参考对象 = ① 本仓库自有的 {@code dev.vkdisp.config.PackOptionStore}（选项 GUI「完成」的落盘格式，
 *    MIT 自有代码）；② 本仓库自有的 {@code dev.vkdisp.pack.PackOptionOverrideSwitch}
 *    （配置侧覆盖串，MIT 自有代码）；③ 本仓库 h27 实测记录的一条真实事故形态：
 *    「B 臂漏带包选项覆盖 ⇒ `ADVANCED_MATERIALS` 丢失 ⇒ 被测 sampler 压根没声明
 *    ⇒ A/B **静默变成空转**」，而日志看起来完全正常。
 *    全部为仓库内自有代码与自有实测事实，不受版权保护。
 *    → 能否并入本项目（MIT）：可以（本文件为独立编写的纯 Java 描述类）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码，也不含任何 Mojang 源码
 * 1. 官方/主实现：无（Vulkan / 原版里都不存在「取证时自报生效配置」这个概念）。
 * 2. 备选：
 *    <ul>
 *      <li>① 每次取证前人工核对配置文件 —— <b>否决（本类的成因）</b>：h27 实测过
 *          「kill 后立刻改配置会被孤儿客户端回写覆盖」，即人工核对的那份内容
 *          可能正是被覆盖掉的那份；且本项目已有 <b>两个</b>覆盖来源（落盘 store + 配置串），
 *          手工只查其中一个必然漏。</li>
 *      <li>② 只在启动时打一行覆盖串 —— 否决：取证过程会热加载改配置（h44 就用了热加载切档），
 *          启动时那一行与当前实际生效的已经不是同一份。</li>
 *      <li>③ 把覆盖串塞进记忆键就算自报 —— 否决：记忆键只保证「契约与配置一致」，
 *          <b>不保证</b>「人知道自己覆盖了什么」；键进了日志也只是一串哈希。</li>
 *    </ul>
 * 3. 我们的差异点：把「两个来源 + 它们的合并结果」做成一份**可自报**的纯数据，
 *   关键语义是<b>合并优先级</b>必须显式且可单测 —— 否则「配置串说 PARALLAX=false、
 *   store 里也是 false」这类情况无法与「配置串写了但没命中」区分。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 冷路径（每探针轮构造一次；默认 300 帧一轮）。
 */

/**
 * 当前**实际生效**的包选项覆盖（纯数据，零 FML / 零文件系统依赖 ⇒ 可单测）。
 *
 * <p><b>它防的是哪一种失败</b>（h27 实测，本项目第五次同族的根源）：
 * 取证者以为自己改了包选项、实际没改，于是 A/B 两臂**静默变成同一件事**，
 * 而日志里每一行都正常。两个覆盖来源必须一起看：
 * <pre>
 *   ① config/vkdisp-pack-options.properties —— 选项 GUI 点「完成」时落盘（持久，跨会话）
 *   ② pack.optionOverrides 配置串        —— 取证用（不落盘，只在内存）
 * </pre>
 * 🔖 2026-10-05 实测：本项目自己在取证时踩了这条 —— 落盘 store 里存着
 * {@code PARALLAX=false}（前一轮 A/B 留下的），而本轮**没有**清理它
 * ⇒ 那一轮的「默认档」其实带着视差关闭覆盖 ⇒ 结论方向被带偏了一整轮。
 */
public record PackOptionEvidence(
        String storeSpec,
        String configSpec,
        java.util.Map<String, String> effective) {

    

    /** 无任何覆盖时的哨兵值（比空串更醒目：日志里出现它一眼就知道「什么都没覆盖」）。 */
    public static final String NONE = "<none>";

    /**
     * 归一：两个来源的原始串 null 归一为空；有效表去 null 并按键排序（确定性）。
     *
     * <p>🔖 排序是**刻意**的：证据行必须逐位可比 —— 若顺序取决于 HashMap 迭代序，
     * 同一份配置两次取证会打出**不同**的字符串，而读的人会以为配置变了。
     */
    public PackOptionEvidence {
        storeSpec = storeSpec == null ? "" : storeSpec;
        configSpec = configSpec == null ? "" : configSpec;
        java.util.TreeMap<String, String> sorted = new java.util.TreeMap<>();
        if (effective != null) {
            effective.forEach((key, value) -> {
                if (key != null && value != null) {
                    sorted.put(key, value);
                }
            });
        }
        effective = java.util.Collections.unmodifiableMap(sorted);
    }

    /**
     * 构造一份取证件。
     *
     * @param storeEntries 落盘 store 的条目（{@code <pack>.<option> → value}；可为 null）
     * @param configSpec   配置串（{@code NAME=value;NAME2=value2}）
     */
    public static PackOptionEvidence of(java.util.Map<String, String> storeEntries, String configSpec) {
        java.util.LinkedHashMap<String, String> merged =
                new java.util.LinkedHashMap<>(storeEntries == null ? java.util.Map.of() : storeEntries);
        String spec = configSpec == null ? "" : configSpec;
        for (String piece : spec.split(";")) {
            String trimmed = piece.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                // 🔖 解析失败**不在这里**报错：解析与诊断由 PackOptionOverride 负责，
                //   本类只负责「已生效的东西是什么」。⇒ 无 '=' 的片段原样记进有效表，
                //   让「写了但没生效」在证据行里**看得见**（而不是被静默丢掉）。
                merged.put("<malformed>" + trimmed, "<no '='>");
                continue;
            }
            String key = trimmed.substring(0, eq).strip();
            String value = trimmed.substring(eq + 1).strip();
            // 🔖 配置串**覆盖** store（配置是取证旋钮，取证者最后动的那一层）。
            merged.put(key, value);
        }
        return new PackOptionEvidence(
                storeEntries == null || storeEntries.isEmpty() ? "" : render(storeEntries),
                spec, merged);
    }

    /** 落盘 store 一路的原始串（空 = 无）。 */
    public String storeSpec() {
        return storeSpec;
    }

    /** 配置串一路的原始内容（空 = 无）。 */
    public String configSpec() {
        return configSpec;
    }

    /** 合并后的有效覆盖（配置串压过 store；不可变、按键升序）。 */
    public java.util.Map<String, String> effective() {
        return effective;
    }

    /** 是否有任何覆盖生效。 */
    public boolean anyActive() {
        return !effective.isEmpty();
    }

    /** 是否存在**语法就不对**的覆盖片段（解析失败的可见出口，避免它静默消失）。 */
    public boolean hasMalformedEntry() {
        return effective.keySet().stream().anyMatch(key -> key.startsWith("<malformed>"));
    }

    /**
 * 把条目表渲染成 {@code k=v;k=v} 形式（证据行用；顺序 = 传入表的迭代序）。
 *
 * <p>🔖 <b>null 键值对在这里也要过滤</b>：归一构造只过滤了有效表，
 * 而 store 那一路的原文是<b>未经归一</b>的输入 ⇒ 不过滤就会在证据行里留下
 * {@code null=x} 这种读不出来、却看起来「像一条真实覆盖」的文本。
 */
    private static String render(java.util.Map<String, String> entries) {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> entry : entries.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    /**
     * 单行自报形式（进每条证据行）。
     *
     * <p>🔖 <b>三个来源都要出现</b>，即使为空也要出现为 {@link #NONE}：
     * 「没打这一项」与「打了但显示为空」在日志上无法区分。
     */
    public String format() {
        return "overrides[store=" + (storeSpec.isBlank() ? NONE : storeSpec)
                + " config=" + (configSpec.isBlank() ? NONE : configSpec)
                + " effective=" + (effective.isEmpty() ? NONE : effective) + "]";
    }
}