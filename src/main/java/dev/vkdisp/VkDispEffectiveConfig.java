package dev.vkdisp;
/**
 * 【参考调研】GAP-056 的取证仪器：启动期全量生效档自报
 * 0. 合规核对（第 0 步闸门）：参考对象 = NeoForge 26.3 的
 *    {@code net.neoforged.neoforge.common.ModConfigSpec.ConfigValue}（公开 API：
 *    {@code getPath()} / {@code get()} / {@code getDefault()}，javap 核实签名）与
 *    FML 的 {@code ModConfigEvent.Loading}/{@code Reloading}（本仓 {@link VkDispConfigHotReload} 已订阅）。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以，只调公开 API，不复制其实现。
 *    → 例外条款：无；本文件不含 GPL / LGPL / ARR 内容。
 * 1. 官方/主实现：配置事件（官方）；自报本身官方没有 —— 官方只保证「值已加载」。
 * 2. 备选：① 手写键清单（否决：会漂 —— {@code VkDispVirtualPack} 那条「默认关」过期注释已经白骗过一轮归因）；
 *    ② {@code SPEC.getValues()} 那棵树（否决：javap 核实它是 **Builder 里的默认值树**，不是 loaded 后的生效值；
 *      {@code ConfigValue.set()} 写的是 {@code loadedConfig}，{@code get()} 读那里）；
 *    ③ 反射 {@code Class.forName} + 单字段名（否决：QD-14 那一族结构性脆弱）。
 * 3. 我们的差异点：枚举走 {@code VkDispConfig} 的 **public static {@code ConfigValue} 字段** ——
 *    编译期就绑在本类上、加键自动进表，不存在「忘了登记」这种失效模式。
 * 4. 许可证核对：本项目 LGPL-3.0-or-later；参考仅观察公开签名。
 * 5. 性能基线：❄️ 冷路径（启动一次 + 每次配置热重载一次，不在每帧路径上，T14）。
 */

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 把**每一个**配置键的「本帧实际生效值 + 是否等于代码默认」打进日志。
 *
 * <p><b>为什么要有这个类</b>（{@code GAP-056}，2026-10-14）：本仓的归因纪律是「一次一臂、一律冷启动，
 * 每臂起跑后回读实际生效档」，但今天做不到 —— 日志里只有散落的三四五个键，于是「臂 7 = `[mixin]` 整块
 * 11 键全关」这类说法只拿到间接证据；更糟的是把一行**不读该键**的 WARN 当成了档回读，
 * 结果一格「已排除」被误签（逐字见 {@code docs/13} 的 GAP-056 表首行）。
 *
 * <p><b>口径</b>：一行日志只能为它自己读过的值作证。所以本块每个键都真的走一次
 * {@link ModConfigSpec.ConfigValue#get()}（= FML 加载后的内存值），
 * 默认值走 {@link ModConfigSpec.ConfigValue#getDefault()}（= {@code .define(…, 字面量)}）。
 *
 * <p><b>输出形状</b>（{@code [生效档]}）：一行摘要 + 每个配置段一行，键按点分路径升序，
 * 与代码默认不同的键在值后标 {@code ≠}：
 * <pre>
 * vkdisp: [生效档] 档=启动首载 57 键 / 非默认 2：mrt.terrain, mrt.packTerrainShader
 * vkdisp: [生效档] [mrt] terrain=true≠ packTerrainShader=true≠ ...
 * </pre>
 */
public final class VkDispEffectiveConfig {

    /** 日志前缀（取证侧 grep 用的针）。 */
    public static final String LABEL = "[生效档]";

    /**
     * 一个键的一条读数。
     *
     * @param key         点分路径，例如 {@code mrt.packTerrainShader}
     * @param effective   本帧实际生效值的文本
     * @param codeDefault {@code .define(…, 字面量)} 里那个默认值的文本
     * @param differs     生效值 ≠ 代码默认
     */
    public record Reading(String key, String effective, String codeDefault, boolean differs) {
    }

    /**
     * 全部键的点分路径（升序）。
     *
     * <p>枚举对象 = {@link VkDispConfig} 里所有 {@code public static} 且类型是
     * {@link ModConfigSpec.ConfigValue} 的字段（{@code BooleanValue}/{@code IntValue}/… 都继承自它）。
     */
    public static List<String> keyNames() {
        List<String> names = new ArrayList<>();
        for (ModConfigSpec.ConfigValue<?> value : declaredValues()) {
            names.add(pathOf(value));
        }
        names.sort(String::compareTo);
        return names;
    }

    /** 每个键各读一次生效值与代码默认（顺序 = {@link #keyNames()} 的升序）。 */
    public static List<Reading> snapshot() {
        List<ModConfigSpec.ConfigValue<?>> values = declaredValues();
        values.sort((a, b) -> pathOf(a).compareTo(pathOf(b)));
        List<Reading> out = new ArrayList<>(values.size());
        for (ModConfigSpec.ConfigValue<?> value : values) {
            Object effective = value.get();
            Object codeDefault = value.getDefault();
            String effectiveText = render(effective);
            String defaultText = render(codeDefault);
            out.add(new Reading(pathOf(value), effectiveText, defaultText,
                    !effectiveText.equals(defaultText)));
        }
        return out;
    }

    /**
     * 纯格式化：给定档位名与读数，产出要打进日志的行（第一行摘要，其后每段一行）。
     *
     * <p>抽成纯函数是为了让「每个键都在块里、非默认键被标出来」这两条能被单测钉住 ——
     * 本类自己就是用来消灭「读数说不清」的，它不能成为新的说不清。
     */
    public static List<String> format(String reason, List<Reading> readings) {
        List<String> lines = new ArrayList<>();
        List<String> differing = new ArrayList<>();
        Map<String, StringJoiner> sections = new LinkedHashMap<>();
        for (Reading reading : readings) {
            if (reading.differs()) {
                differing.add(reading.key());
            }
            int dot = reading.key().indexOf('.');
            String section = dot < 0 ? "(根)" : reading.key().substring(0, dot);
            String leaf = dot < 0 ? reading.key() : reading.key().substring(dot + 1);
            sections.computeIfAbsent(section, k -> new StringJoiner(" "))
                    .add(leaf + "=" + reading.effective() + (reading.differs() ? "≠" : ""));
        }
        lines.add("vkdisp: " + LABEL + " 档=" + reason + " " + readings.size() + " 键 / 非默认 "
                + differing.size() + (differing.isEmpty() ? "" : "：" + String.join(", ", differing)));
        for (Map.Entry<String, StringJoiner> section : sections.entrySet()) {
            lines.add("vkdisp: " + LABEL + " [" + section.getKey() + "] " + section.getValue());
        }
        return lines;
    }

    /**
     * 打一次全量自报（冷路径）。
     *
     * @param reason 档位从哪来（{@code 启动首载} / {@code 热重载}），只进摘要行
     */
    public static void report(String reason) {
        if (!VkDispConfig.SPEC.isLoaded()) {
            // 没加载就调 get() 会拿到默认值的半成品 ⇒ 那正是本类要消灭的东西，宁可少打一行也不打假的。
            VkDisp.LOGGER.warn("vkdisp: {} 没打（配置尚未加载，reason={}）—— 这一档没有生效档读数", LABEL, reason);
            return;
        }
        for (String line : format(reason, snapshot())) {
            VkDisp.LOGGER.info(line);
        }
    }

    /** {@code VkDispConfig} 里声明出来的全部 ConfigValue（未排序、未去重）。 */
    private static List<ModConfigSpec.ConfigValue<?>> declaredValues() {
        List<ModConfigSpec.ConfigValue<?>> out = new ArrayList<>();
        for (Field field : VkDispConfig.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    || !ModConfigSpec.ConfigValue.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                out.add((ModConfigSpec.ConfigValue<?>) field.get(null));
            } catch (IllegalAccessException e) {
                // getFields() 只给 public 成员，而本类是 public —— 走到这里说明反射面变了，必须响。
                throw new IllegalStateException("vkdisp: 生效档自报读不到字段 " + field.getName(), e);
            }
        }
        return out;
    }

    private static String pathOf(ModConfigSpec.ConfigValue<?> value) {
        return String.join(".", value.getPath());
    }

    /** 值的文本口径统一在这里：字符串带引号（空串要能看出来是空串，不是「没打」）。 */
    private static String render(Object value) {
        if (value instanceof String text) {
            return "\"" + text + "\"";
        }
        return String.valueOf(value);
    }

    private VkDispEffectiveConfig() {
    }
}
