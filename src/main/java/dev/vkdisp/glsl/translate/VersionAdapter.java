package dev.vkdisp.glsl.translate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.vkdisp.glsl.TranslateDiagnostic;

/**
 * 【参考调研】D 线版本指令适配（P4.1.2 驱动层）/ shaderc 140 地板 + glslang location 门控原文
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 三份公开事实（均不受版权保护；只取数值与判定规则，零代码行并入）：
 *    ① 本仓库 runClient 实测 shaderc 原文（2026-09-30，/tmp/p41a_runclient.log）：
 *       {@code error: #version: Desktop shaders for Vulkan SPIR-V require version 140 or higher}；
 *    ② 本仓库 runClient 实测第 7 类驱动错误（2026-09-30，/tmp/p41b_runclient.log）：
 *       {@code 'location qualifier on input' : not supported for this version or the enabled extensions}
 *       （composite.fsh:223/225、deferred.fsh:214 —— ⑤ 级补写的片元 in 在 #version 330 下被拒）；
 *    ③ glslang 主线源码（BSD-3-Clause；ParseHelper.cpp 的 EvqVaryingIn / EvqVaryingOut 分支与
 *       Versions.cpp 的 profileRequires 实现，2026-09-30 取证 khronosGroup/glslang）：
 *       片元 {@code layout(location) in} 与顶点 {@code layout(location) out} 均要求
 *       「版本 ≥ 410 或启用 GL_ARB_separate_shader_objects」；profileRequires 对扩展行为
 *       Enable / Require / Warn 判启用、Disable / Forbid 判未启用；ES profile 走独立门
 *       （片元 in / 顶点 out 均 310，无扩展豁免），不存在 {@code #version 410 es}。
 *    外部候选 IrisShaders/glsl-transformer（GPL-3.0 + 例外条款，18-PARALLEL §4 D 线明示
 *    "按禁止处理"）→ 按禁止处理（07-CONSTRAINTS L12 §1.3 陷阱 2 / X21），零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 实现，不含任何外部项目代码。
 *    → 能否并入本项目（MIT）：可以（仅采纳不受版权保护的驱动报错原文、版本门控数值与判定规则）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：GLSL 公开语义 —— {@code #version N} 声明源码方言版本；Vulkan SPIR-V 桌面
 *    着色器要求 N ≥ 140（shaderc 实测，见 0 ①）；片元 in / 顶点 out 的 {@code layout(location)}
 *    在无 SSO 扩展时要求 N ≥ 410（glslang 门控，见 0 ③）。改版本号只改指令本身的数字，
 *    行内其余字节（profile 尾词、行尾注释）原位保留。
 * 2. 备选：无 —— 不建 AST、不引入预处理框架；先在逐行注释剥离视图上全文扫 SSO 扩展、再正则
 *    取版本号，够用即停（08-TESTING §8.1 达标即停）。
 * 3. 我们的差异点：① **三段升级规则**（全部只升不降、原位改字节）：
 *       N &lt; 140 → 恒升 {@link #TARGET_VERSION}（shaderc 硬地板，SSO 扩展豁免不了）；
 *       140 ≤ N &lt; 410 且全文无活跃 SSO 扩展 → 升 410（location 门控；一次升号同时满足
 *       片元 in 与顶点 out 两个 410 门，无需按 stage 分流）；
 *       N ≥ 410 或已有活跃 SSO 扩展 → 原样（fixture「330 + SSO」形态因此字节级不动，
 *       P3.3 已证通路保持）；
 *       ES profile（{@code #version 300 es} 等）→ 恒原样：GLSL ES 版本序列无 410，升号会产出
 *       不存在的 {@code 410 es}；目标生态（OF / Iris / 内建 / fixture）无 ES 输入证据，交驱动
 *       显式报错（T11）。② 注释里的 {@code #version}（行注释、块注释体）不改写 —— 用
 *    CommentState 等长剥离视图判定，改写落在原始行同下标上，注释字节不动；③ 无 {@code #version}
 *    指令的输入不合成插入（插入会改变行数契约；缺版本指令交由驱动显式报错，T11）；④ 成功改写
 *    不产生诊断（与 ①②③ 行内等行数变换同一口径 —— 纯方言改写不是降级），行数恒等，行号映射
 *    不被切断；⑤ SSO 判定 = 注释剥离后的全文扫描，模式只认 GL_ARB_ 前缀与 enable / require /
 *    warn 三种行为（与 0 ③ 的门控原文一一对应）；多行同名扩展以「存在任一活跃行为」为准
 *    （后行覆盖的病态形态无输入证据，发生时由驱动显式报错，T11）。
 * 4. 许可证核对结论：本项目 MIT；GPL-3.0+例外参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径（包加载期一次）；两遍线性扫描（第一遍剥注释扫 SSO、第二遍改版本），
 *    无缓存、无预优化（18-PARALLEL §7.7、T14 达标即停）。
 */
/**
 * {@code #version} 指令的 Vulkan 适配：按 shaderc 地板（140）与 glslang location 门控（410 / SSO）
 * 三段规则就地升级，等行数、零诊断。
 *
 * <p><b>改写规则</b>：行首（可缩进）{@code # version N [profile] ...} 且满足下列任一条件 →
 * 数字替换为 410，同行其余字节原位保留：
 * <ul>
 *   <li>N &lt; 140（shaderc 硬地板，与扩展无关）；</li>
 *   <li>140 ≤ N &lt; 410 且源内无活跃
 *       {@code #extension GL_ARB_separate_shader_objects : enable|require|warn}；</li>
 * </ul>
 * 其余情况（N ≥ 410、已有活跃 SSO、ES profile、无版本指令）原样返回。注释内的伪装指令
 * （CommentState 剥离后才匹配）不动。
 *
 * <p><b>幂等</b>：410 不在改写条件内，第二遍逐字节不变、无诊断。
 *
 * <p><b>行号契约</b>：无插入，输出行号与输入一一对应；诊断（当前恒空）的 {@code line}
 * 是本阶段输入行号，由 {@link OfGlslTranslator} 按 ①–⑤ 级同款方式经上游映射回填。
 */
public final class VersionAdapter {

    /** Vulkan SPIR-V 桌面着色器的最低 #version（shaderc 实测原文，见类注释 0 ①）。 */
    public static final int MINIMUM_VERSION = 140;

    /** 升级目标版本：glslang 片元 in / 顶点 out location 门控值（见类注释 0 ③）；≥ 地板 140。 */
    public static final int TARGET_VERSION = 410;

    /**
     * 版本指令头部：{@code [ws] #[ws]version[ws]+<数字>[ws+<profile>]}；
     * 用 {@link Matcher#lookingAt} 从行首匹配，group(2) 为可选 profile 尾词（判 ES 用）。
     */
    private static final Pattern VERSION_DIRECTIVE =
            Pattern.compile("\\s*#\\s*version\\s+(\\d+)(?:\\s+(\\w+))?");

    /**
     * 活跃 SSO 扩展：{@code [ws] #[ws]extension[ws]GL_ARB_separate_shader_objects[ws]:[ws]}
     * 后接 enable / require / warn（与 profileRequires 的启用判定一一对应；disable / forbid 不匹配）。
     */
    private static final Pattern SSO_ACTIVE_EXTENSION = Pattern.compile(
            "\\s*#\\s*extension\\s+GL_ARB_separate_shader_objects\\s*:\\s*(?:enable|require|warn)");

    private VersionAdapter() {}

    /**
     * 适配结果（纯数据）。
     *
     * @param text         适配后的文本；无需改写时与输入逐字节相同
     * @param diagnostics  诊断（恒空：纯方言改写与 ①–③ 级同口径不产生噪声；位置 = 本阶段输入行号，
     *                     {@code sourceFile} 为 {@code null}，由入口回填）
     * @param upgradedCount 实际升级的 {@code #version} 指令数
     */
    public record Result(String text, List<TranslateDiagnostic> diagnostics, int upgradedCount) {

        /** 记录构造：null 归一（结果对象永不为 null 语义，同 F3 契约）。 */
        public Result {
            text = text == null ? "" : text;
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    /**
     * 按三段规则把源码里的 {@code #version} 版本号就地升到 {@link #TARGET_VERSION}
     * （注释中的伪指令不动，行数不变；ES profile 与已满足门控的源码原样）。
     *
     * @param source 输入 GLSL（{@code null} 按空串处理）
     * @return 适配结果；永不返回 {@code null}
     */
    public static Result upgrade(String source) {
        SourceLines lines = SourceLines.of(source);
        List<String> rawLines = lines.lines();
        // ① 注释剥离视图全文扫描：活跃 SSO 扩展（版本行先于扩展行，须扫完全文才能判定）。
        List<String> codeLines = new ArrayList<>(rawLines.size());
        CommentState comments = new CommentState();
        boolean hasSso = false;
        for (int index = 0; index < rawLines.size(); index++) {
            String code = comments.stripComments(rawLines.get(index), index + 1);
            codeLines.add(code);
            if (!hasSso && SSO_ACTIVE_EXTENSION.matcher(code).lookingAt()) {
                hasSso = true;
            }
        }
        // ② 三段规则改写版本数字（字节落点用无注释视图的下标，注释与行内其余字节原位保留）。
        int upgraded = 0;
        List<String> adapted = new ArrayList<>(rawLines.size());
        for (int index = 0; index < rawLines.size(); index++) {
            String raw = rawLines.get(index);
            Matcher matcher = VERSION_DIRECTIVE.matcher(codeLines.get(index));
            String replacement = raw;
            if (matcher.lookingAt()) {
                int version = parseVersion(matcher.group(1));
                boolean esProfile = "es".equals(matcher.group(2));
                if (version >= 0 && needsUpgrade(version, esProfile, hasSso)) {
                    replacement = raw.substring(0, matcher.start(1)) + TARGET_VERSION
                            + raw.substring(matcher.end(1));
                    upgraded++;
                }
            }
            adapted.add(replacement);
        }
        if (upgraded == 0) {
            return new Result(lines.text(), List.of(), 0);
        }
        return new Result(SourceLines.join(adapted, lines.endsWithNewline()), List.of(), upgraded);
    }

    /**
     * 三段升级判定（见类注释 3 ①）。
     *
     * @param version    解析出的版本号（调用方保证 ≥ 0）
     * @param esProfile  profile 尾词是否为 {@code es}（恒不升：ES 序列无 410）
     * @param hasSso     全文是否存在活跃 {@code GL_ARB_separate_shader_objects}
     * @return 是否需要就地升级
     */
    private static boolean needsUpgrade(int version, boolean esProfile, boolean hasSso) {
        if (esProfile) {
            return false;
        }
        if (version < MINIMUM_VERSION) {
            return true;
        }
        return version < TARGET_VERSION && !hasSso;
    }

    /**
     * 解析版本数字。
     *
     * @return 版本号；位数过多溢出等不可解析形态返回 {@code -1}（不改写，交由驱动显式报错，T11）
     */
    private static int parseVersion(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException overflow) {
            return -1;
        }
    }
}
