package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】E 线纯计算件 / 顶点属性 → stride / offset 计算表
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §4「顶点格式扩展」属性表（类型列的权威依据）；
 *    ② 本仓库 docs/08-TESTING.md §5「顶点 stride 自检」（mesh stride != binding stride 会画成拉伸的
 *      彩色尖刺且不报错 —— E 线的存在理由就是提前在单测里排掉这个雷）；
 *    ③ 公开的顶点缓冲布局事实：交错（interleaved）顶点布局的 offset = 前序属性字节数之和，
 *      stride = 全部属性字节数之和（通用图形事实，不受版权保护）。
 *    许可证：本仓库 docs（MIT 项目自有）+ 通用布局事实 → 可并入。
 *    ⚠️ 18-PARALLEL §4 E 线参考列提到 VulkanMod 的 GlUtil.vulkanFormat「格式表思路」：VulkanMod = LGPL-3.0，
 *    与 MIT 不同族 → 按 docs/07-CONSTRAINTS.md L12「判不过就换参考，不再读它的代码」，
 *    本文件不读其任何源码，计算规则来自 04-SPEC §4 + 公开布局事实。
 *    → 能否并入本项目（MIT）：本文件为独立实现，可并入
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：交错布局的紧致累加（offset_i = sum(size_0..size_{i-1})，stride = sum(size_i)）。
 * 2. 备选：按 Vulkan std140 / 4 字节对齐插入 padding —— 否决（04-SPEC §4 未给对齐列，
 *    擅自加 padding 会让 E 线的 stride 与主线顶点格式不一致，正是 T9 要防的错；若将来主线发现
 *    真实需要对齐，属契约变更，走 18-PARALLEL §3.2 流程）。
 * 3. 我们的差异点：① 紧凑打包、无隐式填充，规则写死并逐项断言（数值断言是 §7.3 要求的证据）；
 *    ② 损坏输入全部转成显式诊断（空属性集 = INFO、重复属性 = WARN 且首个生效、未知类型 = ERROR 且跳过、
 *    非法名字 = ERROR 且跳过），绝不静默算一个错 stride；
 *    ③ 提供 canonicalText()/parseCanonicalText() 做「序列化后打印比对」与篡改检测（单测证据）。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制，只含文档事实与公开布局事实。
 * 5. 性能基线：❄️ 冷路径（构建顶点格式时一次性计算），不做任何性能优化（18-PARALLEL §7.7）。
 */
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import dev.vkdisp.pack.VertexAttribute;

/**
 * 顶点属性布局的纯计算结果（E 线交付物 ①）：每个属性的字节 offset / size 与总 stride。
 *
 * <p><b>计算规则</b>（唯一数值来源，对应 04-SPEC §4 属性表）：按声明顺序做**紧凑交错累加**，
 * 即 {@code offset_i = sum(size_0 .. size_{i-1})}、{@code stride = sum(size_i)}，不插入任何隐式对齐填充。
 * {@link #isConsistent()} 把这条规则变成可断言的谓词（08-TESTING §5 的 stride 自检在主线侧的对应物）。
 *
 * <p><b>损坏输入一律显式回报</b>（07-CONSTRAINTS T11）：
 * <ul>
 *   <li>空属性集 → INFO {@code EMPTY_ATTRIBUTE_SET}，stride = 0
 *       （对无顶点绑定的全屏管线是合法情形，如 POST_PROCESSING_SNIPPET；调用方按需决定）</li>
 *   <li>重复属性名 → WARN {@code DUPLICATE_ATTRIBUTE}，首个生效、后续跳过</li>
 *   <li>非法属性名 → ERROR {@code BAD_ATTRIBUTE_NAME}，该条跳过</li>
 *   <li>未知类型 → ERROR {@code UNKNOWN_ATTRIBUTE_TYPE}，该条跳过（不猜字节数）</li>
 *   <li>null 条目 → ERROR {@code NULL_DECLARATION}，该条跳过</li>
 * </ul>
 * 只要 {@link #hasErrors()} 为 true，调用方就**不得**据此构建真实管线。
 *
 * @param attributes  按声明顺序排列的属性 offset 表（不可变）
 * @param stride      单个顶点的字节数（= 全部属性 size 之和）
 * @param diagnostics 计算过程中的显式诊断（不可变，可为空列表但绝不为 null）
 */
public record VertexLayout(List<AttributeOffset> attributes, int stride, List<ModelDiagnostic> diagnostics) {

    /** 规范文本头（版本化，便于将来走 18-PARALLEL §3.2 流程演进）。 */
    public static final String CANONICAL_HEADER = "vertex-layout v1";

    private static final String STRIDE_PREFIX = "stride=";
    private static final String OFFSET_PREFIX = "offset=";
    private static final String SIZE_PREFIX = "size=";

    /**
     * 一个属性在交错顶点缓冲里的位置。
     *
     * @param name     属性名（GLSL attribute 字面名）
     * @param format   受支持的分量格式
     * @param offset   起始字节偏移
     * @param byteSize 占用字节数（= {@code format.byteSize()}）
     */
    public record AttributeOffset(String name, VertexElementFormat format, int offset, int byteSize) {

        /** 紧凑构造器：字段非 null。 */
        public AttributeOffset {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(format, "format");
        }

        /** 稳定单行形式：{@code <name> <type> offset=<n> size=<n>}。 */
        public String canonicalText() {
            return this.name + " " + this.format.glslName()
                    + " " + OFFSET_PREFIX + this.offset
                    + " " + SIZE_PREFIX + this.byteSize;
        }
    }

    /** 紧凑构造器：列表不可变、非 null。 */
    public VertexLayout {
        attributes = List.copyOf(attributes);
        diagnostics = List.copyOf(diagnostics);
    }

    /**
     * 按 04-SPEC §4 属性表顺序计算布局（08-TESTING §5 的 stride 断言对象）。
     *
     * @param attributes F2 冻结枚举的属性列表（顺序即缓冲内顺序；可变参数，可空）
     * @return 布局 + 显式诊断
     */
    public static VertexLayout of(VertexAttribute... attributes) {
        Objects.requireNonNull(attributes, "attributes");
        List<AttributeDecl> declarations = new ArrayList<>(attributes.length);
        for (VertexAttribute attribute : attributes) {
            declarations.add(AttributeDecl.of(attribute));
        }
        return ofDeclarations(declarations);
    }

    /**
     * 按 04-SPEC §4 属性表顺序计算布局。
     *
     * @param attributes F2 冻结枚举的属性列表（顺序即缓冲内顺序）
     * @return 布局 + 显式诊断
     */
    public static VertexLayout of(List<VertexAttribute> attributes) {
        Objects.requireNonNull(attributes, "attributes");
        List<AttributeDecl> declarations = new ArrayList<>(attributes.size());
        for (VertexAttribute attribute : attributes) {
            declarations.add(AttributeDecl.of(attribute));
        }
        return ofDeclarations(declarations);
    }

    /**
     * 核心计算：按声明顺序紧凑累加，损坏输入转显式诊断。
     *
     * @param declarations 属性声明列表（可为空；不可为 null，元素可为 null → NULL_DECLARATION 诊断）
     * @return 布局 + 显式诊断
     */
    public static VertexLayout ofDeclarations(List<AttributeDecl> declarations) {
        Objects.requireNonNull(declarations, "declarations");
        List<AttributeOffset> offsets = new ArrayList<>(declarations.size());
        List<ModelDiagnostic> diagnostics = new ArrayList<>();
        Set<String> acceptedNames = new HashSet<>();
        int cursor = 0;

        if (declarations.isEmpty()) {
            diagnostics.add(ModelDiagnostic.info("EMPTY_ATTRIBUTE_SET",
                    "no vertex attributes declared: stride=0 (valid only for pipelines without a vertex buffer)"));
        }

        for (AttributeDecl declaration : declarations) {
            if (declaration == null) {
                diagnostics.add(ModelDiagnostic.error("NULL_DECLARATION",
                        "null attribute declaration at position " + offsets.size() + " ignored"));
                continue;
            }
            if (!ModelNames.isGlslIdentifier(declaration.name())) {
                diagnostics.add(ModelDiagnostic.error("BAD_ATTRIBUTE_NAME",
                        "attribute name is not a GLSL identifier: '" + declaration.name() + "' (declaration skipped)"));
                continue;
            }
            Optional<VertexElementFormat> format = declaration.format();
            if (format.isEmpty()) {
                diagnostics.add(ModelDiagnostic.error("UNKNOWN_ATTRIBUTE_TYPE",
                        "attribute '" + declaration.name() + "' has unsupported type '" + declaration.glslType()
                                + "'; supported: " + VertexElementFormat.supportedNames() + " (declaration skipped)"));
                continue;
            }
            if (!acceptedNames.add(declaration.name())) {
                diagnostics.add(ModelDiagnostic.warn("DUPLICATE_ATTRIBUTE",
                        "attribute '" + declaration.name() + "' declared more than once; first declaration wins"));
                continue;
            }
            VertexElementFormat resolved = format.get();
            offsets.add(new AttributeOffset(declaration.name(), resolved, cursor, resolved.byteSize()));
            cursor += resolved.byteSize();
        }
        return new VertexLayout(offsets, cursor, diagnostics);
    }

    /** 属性条目数。 */
    public int attributeCount() {
        return this.attributes.size();
    }

    /** 只返回 ERROR 级诊断（调用方用它决定拒绝构建）。 */
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

    /** 是否存在指定诊断码的条目（单测断言用，避免依赖诊断顺序）。 */
    public boolean hasDiagnostic(String code) {
        for (ModelDiagnostic diagnostic : this.diagnostics) {
            if (diagnostic.code().equals(code)) {
                return true;
            }
        }
        return false;
    }

    /** 按名字查属性（不存在 → empty）。 */
    public Optional<AttributeOffset> findAttribute(String name) {
        for (AttributeOffset attribute : this.attributes) {
            if (attribute.name().equals(name)) {
                return Optional.of(attribute);
            }
        }
        return Optional.empty();
    }

    /**
     * 自检：无 ERROR，且 offsets 恰好是前序 size 的累加、stride 恰好是全部 size 之和。
     *
     * <p>这是 08-TESTING §5「stride 自检」在纯计算侧的对应物：主线用它在绘制前拦下
     * mesh stride 与 binding stride 不一致（T9 / X8）。
     */
    public boolean isConsistent() {
        if (this.hasErrors()) {
            return false;
        }
        int running = 0;
        for (AttributeOffset attribute : this.attributes) {
            if (attribute.offset() != running) {
                return false;
            }
            if (attribute.byteSize() != attribute.format().byteSize()) {
                return false;
            }
            running += attribute.byteSize();
        }
        return running == this.stride;
    }

    /** 规范文本：头 + stride 行 + 每属性一行（可打印、可比对；{@link #parseCanonicalText} 可反向解析）。 */
    public String canonicalText() {
        StringBuilder text = new StringBuilder(CANONICAL_HEADER).append('\n');
        text.append(STRIDE_PREFIX).append(this.stride).append('\n');
        for (AttributeOffset attribute : this.attributes) {
            text.append(attribute.canonicalText()).append('\n');
        }
        return text.toString();
    }

    /**
     * 反解规范文本（E 线交付物 ③ 的「序列化后打印比对」通道）。
     *
     * <p>解析时**重新推导** offset / size / stride，并与文本里印的值逐项比对：任何不一致都是 ERROR
     * （篡改或跨版本格式漂移都会被抓到，绝不静默接受）。返回的布局以推导值为准。
     *
     * @param text 规范文本（不可为 null；空文本 → BAD_HEADER 显式诊断而非静默空布局）
     * @return 布局 + 解析诊断
     */
    public static VertexLayout parseCanonicalText(String text) {
        Objects.requireNonNull(text, "text");
        List<ModelDiagnostic> diagnostics = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        if (!CANONICAL_HEADER.equals(lines[0].trim())) {
            diagnostics.add(ModelDiagnostic.error("BAD_HEADER",
                    "expected first line '" + CANONICAL_HEADER + "' but was: '" + lines[0] + "'"));
            return new VertexLayout(List.of(), 0, diagnostics);
        }

        Integer declaredStride = null;
        List<AttributeDecl> declarations = new ArrayList<>();
        List<int[]> printedValues = new ArrayList<>();
        for (int lineIndex = 1; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex].trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith(STRIDE_PREFIX)) {
                declaredStride = parseIntOrNull(line.substring(STRIDE_PREFIX.length()));
                if (declaredStride == null) {
                    diagnostics.add(ModelDiagnostic.error("BAD_STRIDE_LINE",
                            "stride value is not an integer: '" + line + "'"));
                }
                continue;
            }
            String[] parts = line.split("\\s+");
            boolean wellFormed = parts.length == 4
                    && parts[2].startsWith(OFFSET_PREFIX)
                    && parts[3].startsWith(SIZE_PREFIX);
            if (!wellFormed) {
                diagnostics.add(ModelDiagnostic.error("MALFORMED_ATTRIBUTE_LINE",
                        "expected '<name> <type> offset=<int> size=<int>' but was: '" + line + "'"));
                continue;
            }
            Integer offset = parseIntOrNull(parts[2].substring(OFFSET_PREFIX.length()));
            Integer size = parseIntOrNull(parts[3].substring(SIZE_PREFIX.length()));
            if (offset == null || size == null) {
                diagnostics.add(ModelDiagnostic.error("MALFORMED_ATTRIBUTE_LINE",
                        "offset/size value is not an integer: '" + line + "'"));
                continue;
            }
            declarations.add(new AttributeDecl(parts[0], parts[1]));
            printedValues.add(new int[] {offset, size});
        }

        if (declaredStride == null && !diagnosticsContains(diagnostics, "BAD_STRIDE_LINE")) {
            diagnostics.add(ModelDiagnostic.error("MISSING_STRIDE",
                    "canonical text has no '" + STRIDE_PREFIX + "<int>' line"));
        }

        VertexLayout recomputed = ofDeclarations(declarations);
        diagnostics.addAll(recomputed.diagnostics());
        for (int i = 0; i < declarations.size(); i++) {
            AttributeDecl declaration = declarations.get(i);
            Optional<AttributeOffset> actual = recomputed.findAttribute(declaration.name());
            if (actual.isEmpty()) {
                continue;
            }
            int[] printed = printedValues.get(i);
            if (actual.get().offset() != printed[0]) {
                diagnostics.add(ModelDiagnostic.error("ATTRIBUTE_OFFSET_MISMATCH",
                        "attribute '" + declaration.name() + "': printed offset=" + printed[0]
                                + " but recomputed " + actual.get().offset()));
            }
            if (actual.get().byteSize() != printed[1]) {
                diagnostics.add(ModelDiagnostic.error("ATTRIBUTE_SIZE_MISMATCH",
                        "attribute '" + declaration.name() + "': printed size=" + printed[1]
                                + " but format " + actual.get().format().glslName()
                                + " is " + actual.get().byteSize() + " bytes"));
            }
        }
        if (declaredStride != null && declaredStride != recomputed.stride()) {
            diagnostics.add(ModelDiagnostic.error("STRIDE_MISMATCH",
                    "printed stride=" + declaredStride + " but recomputed " + recomputed.stride()));
        }
        return new VertexLayout(recomputed.attributes(), recomputed.stride(), diagnostics);
    }

    private static boolean diagnosticsContains(List<ModelDiagnostic> diagnostics, String code) {
        for (ModelDiagnostic diagnostic : diagnostics) {
            if (diagnostic.code().equals(code)) {
                return true;
            }
        }
        return false;
    }

    private static Integer parseIntOrNull(String raw) {
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException notAnInteger) {
            return null;
        }
    }
}
