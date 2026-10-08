package dev.vkdisp.pack;
/**
 * 【自行补充】GAP-024 · 链装配期的「哪些全屏步因 {@code program.*.enabled=false} 不进链」决策
 * （纯逻辑，可单测；求值本身复用 {@link ProgramEnableGate}，本类只做<b>编排 + 自报</b>）。
 *
 * <p><b>为什么单独一个类</b>（本仓库既有约定：判定逻辑与 GPU / Minecraft 类型分家）：
 * 「这一级跑不跑」是一次数组进、一个决策出的纯函数，它必须在无 GPU、无 FML 配置体系的
 * 单测里就能自证（08-TESTING §7.3 的并行线判据）。而 {@link PackPostChain} 还带着
 * 编译产物、契约解析、location 重编号 —— 把决策塞进那个循环就没法单独测它。
 *
 * <p><b>与 {@link ProgramEnableGate} 的分工</b>：那个类回答「一条表达式在三值逻辑下是什么」，
 * 本类回答「整条链在这一组表达式下留哪几级、并且<em>如何把这件事说出来</em>」。
 * 求值器<b>不重造</b>（X17 单一真源）：{@code ||}/{@code &&}/Kleene 三值/UNKNOWN 的处置
 * 全部沿用 {@link ProgramEnableGate}，本类不写第二份布尔逻辑。
 *
 * <p>🔴 <b>自报是本类的主体而不是附属品</b>（07-CONSTRAINTS X11 禁静默）：
 * {@link Plan#report} 产出的那一行必须<b>无条件</b>存在，且必须同时给出
 * ① 考虑了多少级、② 留了多少级、③ 跳过了<em>哪几级（点名）</em>、
 * ④ <em>哪些表达式我方认不出来</em>（这些照样进链，但名字必须打出来 —— 那是我方知识的边界）。
 * 「没有这行」与「这行说没跳过任何东西」必须是两个可区分的状态：
 * 这是本项目反复踩过的坑（GAP-026 的 store 残留行、QD-02 的死开关同一族）。
 *
 * <p><b>UNKNOWN 的处置继承门控器的保守口径</b>：看不懂 ⇒ <b>保留这一级</b>。
 * 少跑一级 = 画面少一道工序（看得见的错误），多跑一级 = 白烧（隐藏的错误）；
 * 但「表达式看不懂」本身是我方能力缺口，只能靠自报暴露，不许当成「包没说要开就是开 / 关」。
 */
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** 链装配期的 enabled 门控决策。 */
public final class ChainEnableGating {

    /** 日志行前缀（取证按它 grep；改它等于改判据口径，须同步文档）。 */
    public static final String REPORT_PREFIX = "vkdisp: [GAP-024] post chain enable-gating:";

    /** 配置键名（与 {@link PackChainGatingSwitch#CONFIG_KEY} 同源，写在日志里便于对回配置）。 */
    public static final String CONFIG_KEY = PackChainGatingSwitch.CONFIG_KEY;

    private ChainEnableGating() {
    }

    /**
     * 链装配候选里的一级。
     *
     * @param programName 程序名（{@code composite3}；链按名去重，故名字唯一）
     * @param expression  包写的 {@code program.<名>.enabled} 原文；null / 空白 = 包没给开关 = 恒启用
     */
    public record Step(String programName, String expression) {
        public Step {
            Objects.requireNonNull(programName, "programName");
        }

        /** 包有没有为这一级写开关。 */
        public boolean hasSwitch() {
            return expression != null && !expression.isBlank();
        }
    }

    /**
     * 一次门控的完整决策（不可变）。
     *
     * @param gatingEnabled {@code pack.chainEnableGating} 本次是否生效（关 = 本臂没门控，日志必须能看出来）
     * @param considered    被考虑的候选全屏步数（去重后、进契约解析之前）
     * @param switches      其中<b>带</b> {@code enabled} 表达式的步数（与 considered 不等 = 多数步本来就没开关）
     * @param skipped       因表达式判为 FALSE 而不进链的程序名（保序）
     * @param unresolved    认不出来的表达式清单（{@code 名[表达式 -> [认不出的名字]]}）；这些步<b>照样进链</b>
     */
    public record Plan(
            boolean gatingEnabled,
            int considered,
            int switches,
            List<String> skipped,
            List<String> unresolved) {

        public Plan {
            skipped = List.copyOf(skipped);
            unresolved = List.copyOf(unresolved);
        }

        /** 保留下来、继续走契约解析的步数。 */
        public int kept() {
            return considered - skipped.size();
        }

        /** 这一级进不进链？（未被门控或门控关 ⇒ 一律进；被点名跳过 ⇒ 不进。） */
        public boolean keeps(String programName) {
            return !skipped.contains(programName);
        }

        /**
         * 🔴 那一条自报行（单行、稳定字段序、<b>任何计数为零都照打</b>）。
         *
         * <p>格式（键序即口径，取证脚本按 {@code key=value} 取数）：
         * <pre>
         * vkdisp: [GAP-024] post chain enable-gating: pack=&lt;包名&gt; gating=on|off
         *         considered=N switches=N kept=N skipped=N skippedNames=[a, b] unresolved=[...]
         * </pre>
         *
         * @param packName 包名（多包库存里定位「哪次装配说的话」）
         */
        public String report(String packName) {
            return REPORT_PREFIX
                    + " pack=" + packName
                    + " gating=" + (gatingEnabled ? "on" : "off")
                    + " considered=" + considered
                    + " switches=" + switches
                    + " kept=" + kept()
                    + " skipped=" + skipped.size()
                    + " skippedNames=" + skipped
                    + " unresolved=" + unresolved
                    + (gatingEnabled ? ""
                            : " (开关 " + CONFIG_KEY + "=false ⇒ program.*.enabled 未生效，全部步按旧行为进链)");
        }
    }

    /**
     * 做一次门控决策。
     *
     * @param steps        候选步（链装配顺序；null 元素被拒绝，因为那意味着调用方漏了某一步）
     * @param optionValues 选项名 → 当前值（<b>必须与编译用同一份</b>，否则「按 A 编、按 B 跑」）。
     *                     空表 = 我方不掌握任何选项 ⇒ 每条表达式都判 UNKNOWN ⇒ 全部保留 + 全部自报
     * @param gatingEnabled 门控总开关（关 ⇒ 什么都不评估，行为与门控接入前逐字一致）
     */
    public static Plan plan(List<Step> steps, Map<String, String> optionValues, boolean gatingEnabled) {
        Objects.requireNonNull(steps, "steps");
        Objects.requireNonNull(optionValues, "optionValues");
        List<Step> valid = new ArrayList<>(steps.size());
        for (Step step : steps) {
            if (step == null) {
                throw new IllegalArgumentException(
                        "vkdisp: ChainEnableGating 收到 null 步 —— 漏一步就少一条自报（X11 禁静默）");
            }
            valid.add(step);
        }
        if (!gatingEnabled) {
            // 关掉 = 一行都不评估。switches 仍然报出来，否则「这个包到底有没有开关」
            // 在 OFF 那一臂的日志里就消失了，A/B 两侧不可比。
            return new Plan(false, valid.size(), (int) valid.stream().filter(Step::hasSwitch).count(),
                    List.of(), List.of());
        }
        Function<String, Optional<String>> resolver =
                name -> Optional.ofNullable(optionValues.get(name));
        List<String> skipped = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        int switches = 0;
        for (Step step : valid) {
            if (!step.hasSwitch()) {
                continue; // 包没写开关 = 恒启用，不占「认不出来」的额度
            }
            switches++;
            ProgramEnableGate.Result result = ProgramEnableGate.evaluate(step.expression(), resolver);
            if (!result.unresolvedNames().isEmpty()) {
                unresolved.add(step.programName() + "[" + result.expression()
                        + " -> 认不出 " + result.unresolvedNames() + "]");
            }
            if (!result.keepPass()) {
                skipped.add(step.programName());
            }
        }
        return new Plan(true, valid.size(), switches, skipped, unresolved);
    }
}
