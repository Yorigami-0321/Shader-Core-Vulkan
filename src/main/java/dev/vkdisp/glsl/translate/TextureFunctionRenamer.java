package dev.vkdisp.glsl.translate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.vkdisp.glsl.TranslateDiagnostic;

/**
 * 【参考调研】D 线二期旧纹理函数改名 / GLSL 1.20 → 330 core 的公开差异 + 04-SPEC §3.3
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/04-SPEC.md §3.3（源码级转译后交给原版编译器）、
 *    docs/18-PARALLEL.md §4 D 线（独占路径、完成标准、证据规范）与 §7（硬边界）——
 *    仓库内文档事实；另加 GLSL 官方公开语义：GLSL 1.20 的 texture1D / texture2D / texture3D /
 *    textureCube / texture2DProj / *Lod / shadow2D 系列在 330 core 里统一为 texture / textureProj /
 *    textureLod / textureProjLod（按采样器类型重载）。以上均不受版权保护。
 *    外部候选 IrisShaders/glsl-transformer（GPL-3.0 + 例外条款）与 glsl-preprocessor（同）→
 *    18-PARALLEL §4 D 线明示「按禁止处理」，一律不读其代码、零代码行并入
 *    （07-CONSTRAINTS L12 §1.3 陷阱 2 / X21、17-NATIVE §1.1.1）。
 *    许可证：本文件为独立编写的纯 Java 实现，不含任何外部项目代码；样本在单测中自造（§7.6）。
 *    → 能否并入本项目（MIT）：可以 —— 只采纳不受版权保护的事实性信息（函数名映射、返回类型）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：GLSL 公开函数表 —— 现代名按「采样器类型 + 查找方式」重载：
 *    texture(sampler2D, vec2) 等；textureProj 对应旧的 *Proj；textureLod 对应旧的 *Lod。
 *    shadow2D(sampler2DShadow, vec3) 在 1.20 返回 **vec4**，而现代 texture(sampler2DShadow, vec3)
 *    返回 **float** —— 任务点名的「shadow2D → texture 需注意 sampler 类型」正指此处。
 * 2. 备选：无 —— 不建 AST、不引入 glslang / ANTLR；只做标识符级改名 + 单行括号配对
 *    （冷路径清晰优先，18-PARALLEL §7.7）。
 * 3. 我们的差异点：① **只改真正的调用**（标识符后跟同行的 {@code (}），同名变量 / 宏参数不动；
 *    ② shadow 系列不止改名，还按返回类型差异包成 {@code vec4(texture(...))}：float → vec4 后
 *    的 {@code .r/.g/.b/.a} 访问与 vec4 赋值语义与 1.20 完全一致，并出 INFO 记录该适配；
 *    ③ 调用跨行（同行找不到配对右括号）时不猜：退化为纯改名并出 WARN，显式说明返回类型差异（T11）；
 *    ④ 只扫「无注释无字符串视图」的标识符，注释 / 字符串 / #define 续行一字不动；
 *    ⑤ 行内等长无关（改名会变长度），但**行数不变** —— C 线的行号映射不被切断；
 *    ⑥ 不改 {@code gl_*} 内建与 uniform（那是 FragmentOutputAdapter / UniformInjector 的职责）。
 * 4. 许可证核对结论：本项目 MIT；GPL-3.0+例外参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径（包加载期一次，08-TESTING §8 解析+转译全部 program ≤ 1 秒 由 P2 主线
 *    实测把关）；一次线性扫描，无缓存、无预优化（18-PARALLEL §7.7、T14 达标即停）。
 */
/**
 * GLSL 1.20 的旧纹理 / 阴影查找函数 → GLSL 330 core 的现代名（texture 家族）。
 *
 * <p><b>映射表</b>（只用公开语义，样本自造）
 * <table border="1">
 *   <caption>旧名 → 现代名</caption>
 *   <tr><th>旧名</th><th>现代名</th></tr>
 *   <tr><td>texture1D / texture2D / texture3D / textureCube</td><td>texture</td></tr>
 *   <tr><td>texture2DProj / texture3DProj</td><td>textureProj</td></tr>
 *   <tr><td>texture2DLod / texture3DLod / textureCubeLod</td><td>textureLod</td></tr>
 *   <tr><td>texture2DProjLod / texture3DProjLod</td><td>textureProjLod</td></tr>
 *   <tr><td>shadow1D / shadow2D</td><td>vec4(texture(...))（返回类型适配）</td></tr>
 *   <tr><td>shadow1DProj / shadow2DProj</td><td>vec4(textureProj(...))（返回类型适配）</td></tr>
 * </table>
 *
 * <p><b>不改的东西</b>：{@code #define} / {@code #include}（C 线职责）、{@code gl_*} 内建输出
 * （{@link FragmentOutputAdapter}）、内建 uniform 声明（{@link UniformInjector}）、
 * 不在调用位置出现的同名标识符、跨行调用（显式 WARN 后只改名，不猜参数边界）。
 *
 * <p><b>幂等</b>：改名后源码里不再有旧函数名，第二遍扫描无改写 → 文本逐字节不变、无新增诊断
 * （跨行 WARN 只出现在第一遍有旧函数名时）。
 *
 * <p><b>行号契约</b>：诊断的 {@code line} 是**本阶段输入**行号；{@code sourceFile} 留空由
 * {@link OfGlslTranslator} 经 F3 的 {@code SourceLineMap} 回填原文件。
 */
public final class TextureFunctionRenamer {

    /** 旧名 → 现代名（顺序即公开表的书写顺序，便于诊断与文档逐条对照）。 */
    private static final Map<String, String> RENAMES = renames();

    /** shadow 系列：旧名 → 现代名；调用点额外包 {@code vec4(...)}（返回类型 vec4 → float 的适配）。 */
    private static final Map<String, String> SHADOW_WRAPS = shadowWraps();

    /** 允许出现在调用前驱位置的空白之外的字符不做特殊处理：只要求同行的 {@code (}。 */
    private TextureFunctionRenamer() {}

    /**
     * 改名结果（纯数据）。
     *
     * @param text         改名后的文本；无需改名时与输入逐字节相同
     * @param diagnostics  诊断（位置 = 本阶段输入行号；{@code sourceFile} 为 {@code null}，由入口回填）
     * @param renamedCount 实际改写的调用数（shadow 包装算一次）
     */
    public record Result(String text, List<TranslateDiagnostic> diagnostics, int renamedCount) {

        /** 记录构造：null 归一（结果对象永不为 null 语义，同 F3 契约）。 */
        public Result {
            text = text == null ? "" : text;
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    /**
     * 把旧纹理 / 阴影查找函数名改写成 GLSL 330 core 的现代名。
     *
     * @param source 输入 GLSL（{@code null} 按空串处理）
     * @return 改名结果；永不返回 {@code null}
     */
    public static Result rename(String source) {
        SourceLines lines = SourceLines.of(source);
        List<String> raw = lines.lines();
        List<TranslateDiagnostic> diagnostics = new ArrayList<>();
        State state = new State(diagnostics);
        List<String> codes = GlslTextScan.codeViews(raw, diagnostics);
        boolean[] skip = GlslTextScan.preprocessorSkipLines(raw, codes);

        List<String> renamed = new ArrayList<>(raw.size());
        for (int index = 0; index < raw.size(); index++) {
            if (skip[index]) {
                renamed.add(raw.get(index));
                continue;
            }
            state.line = index + 1;
            renamed.add(rewriteRange(raw.get(index), codes.get(index), 0, codes.get(index).length(), state));
        }
        return new Result(SourceLines.join(renamed, lines.endsWithNewline()), diagnostics, state.renamedCount);
    }

    /**
     * 改写 {@code [from, to)} 区间（{@code raw} 与 {@code code} 等长同下标）。
     *
     * <p>递归用于 shadow 包装：包装整段 {@code shadow2D(args)} 后，还要对 {@code args} 内部
     * 继续改名（例如 {@code shadow2D(t, vec3(texture2D(u, uv)))} 里的 texture2D）。
     */
    private static String rewriteRange(String raw, String code, int from, int to, State state) {
        StringBuilder out = new StringBuilder();
        int cursor = from;
        int pos = from;
        boolean replaced = false;
        while (pos < to) {
            int[] span = GlslTextScan.identifierAt(code, pos);
            if (span == null) {
                pos++;
                continue;
            }
            if (span[1] > to) {
                pos++;
                continue;
            }
            if (!GlslTextScan.atTokenStart(code, span[0])) {
                pos = span[1];
                continue;
            }
            String token = code.substring(span[0], span[1]);
            String mapped = RENAMES.get(token);
            String wrapTarget = SHADOW_WRAPS.get(token);
            if (mapped == null && wrapTarget == null) {
                pos = span[1];
                continue;
            }
            int open = GlslTextScan.skipWhitespace(code, span[1]);
            if (open >= to || code.charAt(open) != '(') {
                // 不是调用（同名变量 / 宏参数 / 换行后的括号）→ 不动，避免误改
                pos = span[1];
                continue;
            }
            int close = GlslTextScan.matchCloseParen(code, open);
            if (close < 0 || close >= to) {
                if (wrapTarget == null) {
                    out.append(raw, cursor, span[0]).append(mapped);
                    cursor = span[1];
                } else {
                    out.append(raw, cursor, span[0]).append(wrapTarget);
                    cursor = span[1];
                    state.warnUnwrapped(token);
                }
                replaced = true;
                state.renamedCount++;
                pos = span[1];
                continue;
            }
            if (mapped != null) {
                out.append(raw, cursor, span[0]).append(mapped);
                cursor = span[1];
                replaced = true;
                state.renamedCount++;
                pos = span[1];
                continue;
            }
            String inner = rewriteRange(raw, code, open, close + 1, state);
            out.append(raw, cursor, span[0]).append("vec4(").append(wrapTarget).append(inner).append(')');
            cursor = close + 1;
            replaced = true;
            state.renamedCount++;
            state.infoWrapped(token);
            pos = close + 1;
        }
        if (!replaced) {
            return raw.substring(from, to);
        }
        out.append(raw, cursor, to);
        return out.toString();
    }

    /** 旧名 → 现代名的固定表（LinkedHashMap：顺序稳定，便于文档逐条对照）。 */
    private static Map<String, String> renames() {
        Map<String, String> table = new LinkedHashMap<>();
        table.put("texture1D", "texture");
        table.put("texture2D", "texture");
        table.put("texture3D", "texture");
        table.put("textureCube", "texture");
        table.put("texture2DProj", "textureProj");
        table.put("texture3DProj", "textureProj");
        table.put("texture2DLod", "textureLod");
        table.put("texture3DLod", "textureLod");
        table.put("textureCubeLod", "textureLod");
        table.put("texture2DProjLod", "textureProjLod");
        table.put("texture3DProjLod", "textureProjLod");
        return Map.copyOf(table);
    }

    /** shadow 系列的现代名（调用点还要包 {@code vec4(...)}，见类注释）。 */
    private static Map<String, String> shadowWraps() {
        Map<String, String> table = new LinkedHashMap<>();
        table.put("shadow1D", "texture");
        table.put("shadow2D", "texture");
        table.put("shadow1DProj", "textureProj");
        table.put("shadow2DProj", "textureProj");
        return Map.copyOf(table);
    }

    /** 跨行扫描的可变状态（改名计数 + 每个名字只报一次的诊断）。 */
    private static final class State {

        private final List<TranslateDiagnostic> diagnostics;
        private final Set<String> wrappedReported = new LinkedHashSet<>();
        private final Set<String> unwrappedReported = new LinkedHashSet<>();
        private int line = TranslateDiagnostic.UNKNOWN_LINE;
        private int renamedCount;

        private State(List<TranslateDiagnostic> diagnostics) {
            this.diagnostics = diagnostics;
        }

        private void infoWrapped(String token) {
            if (!wrappedReported.add(token)) {
                return;
            }
            diagnostics.add(TranslateDiagnostic.info(token + " 已改名为 " + SHADOW_WRAPS.get(token)
                    + " 并包成 vec4(...)：GLSL 1.20 的 shadow 查找返回 vec4，现代 texture(sampler2DShadow, ...) "
                    + "返回 float，包 vec4 后 .r/.g/.b/.a 与 vec4 赋值语义保持一致", null, line));
        }

        private void warnUnwrapped(String token) {
            if (!unwrappedReported.add(token)) {
                return;
            }
            diagnostics.add(TranslateDiagnostic.warn(token + " 的调用跨行（同行找不到配对右括号），"
                    + "只做改名 " + SHADOW_WRAPS.get(token) + "，未包 vec4(...)：返回类型由 vec4 变为 float，"
                    + "请检查调用点（T11 显式可见）", null, line));
        }
    }
}
