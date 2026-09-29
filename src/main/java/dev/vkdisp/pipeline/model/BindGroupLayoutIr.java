package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】E 线纯计算件 / BindGroupLayout 声明中间表示（纯数据 + 可序列化打印比对）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §3.3（原版 BindGroupLayout.builder().withSampler(...)/
 *      .withUniform(..., UNIFORM_BUFFER).build() 的官方用法形状，以及真实管线例子里
 *      colortex0 / depthtex0 / OfSceneParams 这类绑定名）；
 *    ② F1 已冻结的 dev.vkdisp.bridge.RenderApi（风格参照：纯 Java record 承载跨线契约）。
 *    许可证：本仓库自有文档与自有契约（MIT 项目）→ 可直接消费；
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触
 *    —— 按 docs/07-CONSTRAINTS.md L12「判不过就换参考，不再读它的代码」。
 *    → 能否并入本项目（MIT）：本文件为独立实现，可并入
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：原版 BindGroupLayout 的声明顺序语义 —— 采样器 / uniform 缓冲按声明顺序占用
 *    binding 索引（0..n-1）；绑定名是管线侧契约（如 colortex0 / depthtex0 / OfSceneParams）。
 * 2. 备选：直接持有原版 BindGroupLayout 对象 —— 否决（🔴 07-CONSTRAINTS T5：业务包不得直接
 *    引用原版渲染类型；而且 E 线必须能在无 GPU、无游戏的单测里自证）。
 * 3. 我们的差异点：① 纯 Java record 中间表示，索引 = 声明顺序（由本类持有，不放在 Binding 里，
 *    避免出现「索引与顺序不一致」这种半非法状态）；② 提供 canonicalText()/parseCanonicalText()
 *    做打印比对与篡改检测；③ 损坏输入（空名 / 重名 / 未知 kind / 索引错位）全部显式诊断（T11）。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制，只含官方 API 的声明顺序事实与本仓库文档。
 * 5. 性能基线：❄️ 冷路径（构建管线时一次性生成），不做任何性能优化（18-PARALLEL §7.7）。
 */
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * BindGroupLayout 的**声明中间表示**（E 线交付物 ③）：纯数据、可打印比对、零原版渲染类型依赖。
 *
 * <p><b>索引语义</b>：binding 索引 = 列表中的声明顺序（0..n-1），与 {@link Builder} 的调用顺序一致；
 * 这与原版 {@code BindGroupLayout.builder()} 的「声明顺序即索引」语义对应（04-SPEC §3.3）。
 *
 * <p><b>损坏输入一律显式回报</b>（07-CONSTRAINTS T11）：
 * <ul>
 *   <li>空名 / 非 GLSL 标识符 → ERROR {@code BAD_BINDING_NAME}，该条跳过</li>
 *   <li>重名 → WARN {@code DUPLICATE_BINDING_NAME}，首个生效、后续跳过</li>
 *   <li>null 条目 → ERROR {@code NULL_BINDING}，该条跳过</li>
 *   <li>解析文本时的头 / 行格式 / 未知 kind / 索引错位 → 各自的 ERROR 诊断</li>
 * </ul>
 *
 * @param bindings    按索引顺序排列的绑定声明（不可变）
 * @param diagnostics 生成/解析过程中的显式诊断（不可变，可为空列表但绝不为 null）
 */
public record BindGroupLayoutIr(List<Binding> bindings, List<ModelDiagnostic> diagnostics) {

    /** 规范文本头（版本化）。 */
    public static final String CANONICAL_HEADER = "bind-group-layout v1";

    /** 绑定类型（对应 04-SPEC §3.3 用到的三类声明）。 */
    public enum BindingKind {
        /** 采样器（纹理 + 采样状态），如 colortex0 / depthtex0。 */
        SAMPLER,
        /** uniform 缓冲，如 OfSceneParams。 */
        UNIFORM_BUFFER,
        /** 只读/读写存储缓冲（阴影/实例数据类 pass 用）。 */
        STORAGE_BUFFER;

        /**
         * 按名字解析（大小写敏感的枚举名；首尾空白裁剪）。
         *
         * @param token 形如 {@code SAMPLER} 的文本；null / 未收录 → empty（调用方必须显式诊断）
         */
        public static Optional<BindingKind> tryParse(String token) {
            if (token == null) {
                return Optional.empty();
            }
            String trimmed = token.trim();
            for (BindingKind kind : values()) {
                if (kind.name().equals(trimmed)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }

        /** 全部受支持 kind 名（按声明顺序）。 */
        public static List<String> supportedNames() {
            List<String> names = new ArrayList<>(values().length);
            for (BindingKind kind : values()) {
                names.add(kind.name());
            }
            return List.copyOf(names);
        }
    }

    /**
     * 一条绑定声明（索引由所在列表的位置决定）。
     *
     * @param kind 绑定类型
     * @param name 绑定名（GLSL/管线侧逻辑名，如 {@code colortex0}）
     */
    public record Binding(BindingKind kind, String name) {

        /** 紧凑构造器：字段非 null。 */
        public Binding {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(name, "name");
        }
    }

    /** 紧凑构造器：列表不可变、非 null。 */
    public BindGroupLayoutIr {
        bindings = List.copyOf(bindings);
        diagnostics = List.copyOf(diagnostics);
    }

    /** 流式构造器（形状对应 04-SPEC §3.3 的 builder 用法；索引按调用顺序分配）。 */
    public static final class Builder {
        private final List<Binding> pending = new ArrayList<>();

        private Builder() {
        }

        /** 追加一个采样器绑定。 */
        public Builder sampler(String name) {
            this.pending.add(new Binding(BindingKind.SAMPLER, name));
            return this;
        }

        /** 追加一个 uniform 缓冲绑定。 */
        public Builder uniformBuffer(String name) {
            this.pending.add(new Binding(BindingKind.UNIFORM_BUFFER, name));
            return this;
        }

        /** 追加一个存储缓冲绑定。 */
        public Builder storageBuffer(String name) {
            this.pending.add(new Binding(BindingKind.STORAGE_BUFFER, name));
            return this;
        }

        /** 生成中间表示（校验规则见 {@link BindGroupLayoutIr#ofBindings(List)}）。 */
        public BindGroupLayoutIr build() {
            return BindGroupLayoutIr.ofBindings(this.pending);
        }
    }

    /** 新建流式构造器。 */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 从声明列表生成中间表示（索引 = 列表位置），并显式回报损坏条目。
     *
     * @param bindings 绑定声明列表（可为空 = 无绑定的全屏管线；不可为 null，元素可为 null）
     * @return 中间表示 + 显式诊断
     */
    public static BindGroupLayoutIr ofBindings(List<Binding> bindings) {
        Objects.requireNonNull(bindings, "bindings");
        List<Binding> accepted = new ArrayList<>(bindings.size());
        List<ModelDiagnostic> diagnostics = new ArrayList<>();
        Set<String> acceptedNames = new HashSet<>();
        for (Binding binding : bindings) {
            if (binding == null) {
                diagnostics.add(ModelDiagnostic.error("NULL_BINDING",
                        "null binding declaration at index " + accepted.size() + " ignored"));
                continue;
            }
            if (!ModelNames.isGlslIdentifier(binding.name())) {
                diagnostics.add(ModelDiagnostic.error("BAD_BINDING_NAME",
                        "binding name is not a GLSL identifier: '" + binding.name() + "' (binding skipped)"));
                continue;
            }
            if (!acceptedNames.add(binding.name())) {
                diagnostics.add(ModelDiagnostic.warn("DUPLICATE_BINDING_NAME",
                        "binding name '" + binding.name() + "' declared more than once; first declaration wins"));
                continue;
            }
            accepted.add(binding);
        }
        return new BindGroupLayoutIr(accepted, diagnostics);
    }

    /** 绑定数量。 */
    public int size() {
        return this.bindings.size();
    }

    /** 只返回 ERROR 级诊断。 */
    public List<ModelDiagnostic> errors() {
        List<ModelDiagnostic> errors = new ArrayList<>();
        for (ModelDiagnostic diagnostic : this.diagnostics) {
            if (diagnostic.isError()) {
                errors.add(diagnostic);
            }
        }
        return List.copyOf(errors);
    }

    /** 是否存在 ERROR 级诊断。 */
    public boolean hasErrors() {
        for (ModelDiagnostic diagnostic : this.diagnostics) {
            if (diagnostic.isError()) {
                return true;
            }
        }
        return false;
    }

    /** 是否存在指定诊断码的条目（单测断言用）。 */
    public boolean hasDiagnostic(String code) {
        for (ModelDiagnostic diagnostic : this.diagnostics) {
            if (diagnostic.code().equals(code)) {
                return true;
            }
        }
        return false;
    }

    /** 按名字查绑定索引；不存在 → -1。 */
    public int bindingIndexOf(String name) {
        for (int i = 0; i < this.bindings.size(); i++) {
            if (this.bindings.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** 规范文本：头 + 每行 {@code <index> <KIND> <name>}（可打印、可比对、可反解）。 */
    public String canonicalText() {
        StringBuilder text = new StringBuilder(CANONICAL_HEADER).append('\n');
        for (int i = 0; i < this.bindings.size(); i++) {
            Binding binding = this.bindings.get(i);
            text.append(i).append(' ').append(binding.kind().name()).append(' ').append(binding.name()).append('\n');
        }
        return text.toString();
    }

    /**
     * 反解规范文本（打印比对通道）。
     *
     * <p>校验：头必须匹配；每行必须是三个 token；索引必须等于该行的序号；kind 必须在白名单内；
     * 名字必须是 GLSL 标识符。任何一条不满足都是 ERROR（篡改不会被静默吞掉）。
     *
     * @param text 规范文本（不可为 null；空文本 → BAD_HEADER 显式诊断）
     * @return 中间表示 + 解析诊断
     */
    public static BindGroupLayoutIr parseCanonicalText(String text) {
        Objects.requireNonNull(text, "text");
        List<ModelDiagnostic> diagnostics = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        if (!CANONICAL_HEADER.equals(lines[0].trim())) {
            diagnostics.add(ModelDiagnostic.error("BAD_HEADER",
                    "expected first line '" + CANONICAL_HEADER + "' but was: '" + lines[0] + "'"));
            return new BindGroupLayoutIr(List.of(), diagnostics);
        }
        List<Binding> parsed = new ArrayList<>();
        int lineOrdinal = 0;
        for (int lineIndex = 1; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length != 3) {
                diagnostics.add(ModelDiagnostic.error("MALFORMED_BINDING_LINE",
                        "expected '<index> <KIND> <name>' but was: '" + line + "'"));
                lineOrdinal++;
                continue;
            }
            Integer printedIndex = parseIntOrNull(parts[0]);
            if (printedIndex == null || printedIndex != lineOrdinal) {
                diagnostics.add(ModelDiagnostic.error("BINDING_INDEX_MISMATCH",
                        "binding line " + lineOrdinal + " declares index '" + parts[0]
                                + "'; index must equal declaration order"));
            }
            Optional<BindingKind> kind = BindingKind.tryParse(parts[1]);
            if (kind.isEmpty()) {
                diagnostics.add(ModelDiagnostic.error("UNKNOWN_BINDING_KIND",
                        "unsupported binding kind '" + parts[1] + "'; supported: " + BindingKind.supportedNames()));
                lineOrdinal++;
                continue;
            }
            parsed.add(new Binding(kind.get(), parts[2]));
            lineOrdinal++;
        }
        BindGroupLayoutIr validated = ofBindings(parsed);
        diagnostics.addAll(validated.diagnostics());
        return new BindGroupLayoutIr(validated.bindings(), diagnostics);
    }

    private static Integer parseIntOrNull(String raw) {
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException notAnInteger) {
            return null;
        }
    }
}
