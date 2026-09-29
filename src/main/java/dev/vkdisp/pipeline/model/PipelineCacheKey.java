package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】E 线纯计算件 / PipelineCache 键计算（纯函数）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §3.3「PipelineCache：避免重复构建；着色器重载时清理」；
 *    ② 本仓库 docs/08-TESTING.md §6「管线缓存按 program 键，顶点格式变了旧管线会残留」
 *      与 §5「stride 不匹配会画成拉伸的彩色尖刺」—— 键漏了顶点格式信息 = 复用错管线 = 彩色尖刺；
 *    ③ 公开的通用做法事实：缓存键 = 全部影响构建结果的输入的确定性编码，比较用值相等而非哈希相等。
 *    许可证：本仓库自有文档（MIT 项目）+ 通用做法事实 → 可并入。
 *    ⚠️ 18-PARALLEL §4 E 线参考列提到 VulkanMod 的格式表思路（LGPL-3.0）：按 07-CONSTRAINTS L12
 *    「判不过就换参考，不再读它的代码」，本文件不读其任何源码。
 *    → 能否并入本项目（MIT）：本文件为独立实现，可并入
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码；参考模组零接触
 * 1. 官方/主实现：键 = 规范文本（长度前缀编码的字段集合）；相等判定 = 规范文本相等（record 值相等）；
 *    fingerprint = 规范文本的 SHA-256 十六进制（仅用于日志/诊断打印，不参与相等判定）。
 * 2. 备选：用 String.hashCode 或 32 位整数哈希当键 —— 否决（哈希相等不代表输入相等，
 *    会静默复用错管线；哈希冲突在 T9 场景下就是画面错乱）。
 * 3. 我们的差异点：① 键文本用长度前缀编码，字段间不靠分隔符消歧 → 单射（不同输入必得不同文本）；
 *    ② 顶点布局与绑定布局的规范文本整体进键 → 属性类型/顺序/绑定变化都会换键
 *    （顶点格式变了旧管线残留这一坑在单测里就被拦住）；
 *    ③ 中间表示带 ERROR 时拒绝算键（IllegalArgumentException 带全部诊断原文），不许从半非法输入产出键。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制，只含文档事实与通用做法事实。
 * 5. 性能基线：❄️ 冷路径（构建/切包时算一次键），不做任何性能优化（18-PARALLEL §7.7）。
 */
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 管线缓存键（E 线交付物 ②）：同一 Program 稳定、不同 Program 不碰撞。
 *
 * <p><b>相等语义</b>：record 值相等 —— 全部字段相等才相等。{@link #canonicalText()} 是这些字段的
 * 单射编码（长度前缀），因此「键相等」等价于「影响构建结果的输入完全相同」。
 * {@link #fingerprint()} 只是给日志用的 SHA-256 短标识，**不参与**相等判定。
 *
 * <p><b>为什么顶点布局整体进键</b>：08-TESTING §5/§6 的坑 —— 顶点格式（属性集合、类型、顺序 → stride/offset）
 * 变了但复用了旧管线，症状是「拉伸的彩色尖刺且不报错」。把 {@link VertexLayout} 的规范文本放进键，
 * 属性类型变化会换键，旧管线不会被错误复用。
 *
 * @param programId          Program 逻辑名
 * @param location           管线注册位置
 * @param vertexShader       顶点着色器标识
 * @param fragmentShader     片元着色器标识
 * @param vertexBindingNames 顶点格式逻辑名（不可变）
 * @param optional           是否可选管线
 * @param vertexLayoutText   顶点布局规范文本（由 {@link VertexLayout#canonicalText()} 给出）
 * @param bindGroupLayoutText 绑定布局规范文本（由 {@link BindGroupLayoutIr#canonicalText()} 给出）
 */
public record PipelineCacheKey(
        String programId,
        String location,
        String vertexShader,
        String fragmentShader,
        List<String> vertexBindingNames,
        boolean optional,
        String vertexLayoutText,
        String bindGroupLayoutText
) {

    /** 规范文本头（版本化）。 */
    public static final String CANONICAL_HEADER = "pipeline-cache-key v1";

    /** 紧凑构造器：字段非 null，列表不可变。 */
    public PipelineCacheKey {
        Objects.requireNonNull(programId, "programId");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(vertexShader, "vertexShader");
        Objects.requireNonNull(fragmentShader, "fragmentShader");
        Objects.requireNonNull(vertexLayoutText, "vertexLayoutText");
        Objects.requireNonNull(bindGroupLayoutText, "bindGroupLayoutText");
        vertexBindingNames = List.copyOf(vertexBindingNames);
    }

    /**
     * 计算缓存键。
     *
     * <p>入参必须满足：① {@link PipelineSpecIr} 的四个字符串字段非空白；
     * ② 顶点绑定名 cache-text-safe（无空白、无 '|'、无控制字符，保证键文本单射）；
     * ③ 顶点布局与绑定布局均无 ERROR 诊断（半非法的中间表示不许产出键，T11）。
     *
     * @param spec             管线声明（不可为 null）
     * @param vertexLayout     顶点布局（不可为 null）
     * @param bindGroupLayout  绑定布局（不可为 null）
     * @return 确定性缓存键
     * @throws NullPointerException     任一入参为 null
     * @throws IllegalArgumentException 字符串字段空白 / 绑定名不安全 / 布局带 ERROR 诊断
     */
    public static PipelineCacheKey of(
            PipelineSpecIr spec, VertexLayout vertexLayout, BindGroupLayoutIr bindGroupLayout) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(vertexLayout, "vertexLayout");
        Objects.requireNonNull(bindGroupLayout, "bindGroupLayout");

        requireSpecFields(spec);
        requireNoErrors("vertexLayout", vertexLayout.hasErrors(), vertexLayout.errors());
        requireNoErrors("bindGroupLayout", bindGroupLayout.hasErrors(), bindGroupLayout.errors());

        return new PipelineCacheKey(
                spec.programId(),
                spec.location(),
                spec.vertexShader(),
                spec.fragmentShader(),
                spec.vertexBindingNames(),
                spec.optional(),
                vertexLayout.canonicalText(),
                bindGroupLayout.canonicalText());
    }

    /**
     * 多槽版缓存键（E 线二期）：把 {@link VertexBindingLayout} 的规范文本整体放进键。
     *
     * <p>刻意用独立方法名而不是重载：重载会让「传 null 字面量」的既有调用点产生歧义
     * （一期单槽入口 {@link #of(PipelineSpecIr, VertexLayout, BindGroupLayoutIr)} 必须零改动）。
     *
     * <p><b>两个入口不可混用</b>：内嵌的布局文本形状不同，同一条管线经两个入口会得到两个键。
     * 方向是安全的（宁可 cache miss，不可错复用旧管线 = T9「彩色尖刺」）。
     *
     * @param spec                管线声明（不可为 null）
     * @param vertexBindingLayout 多槽顶点布局（不可为 null）
     * @param bindGroupLayout     绑定布局（不可为 null）
     * @return 确定性缓存键
     * @throws NullPointerException     任一入参为 null
     * @throws IllegalArgumentException 字符串字段空白 / 绑定名不安全 / 布局带 ERROR 诊断
     */
    public static PipelineCacheKey ofMultiSlot(
            PipelineSpecIr spec, VertexBindingLayout vertexBindingLayout, BindGroupLayoutIr bindGroupLayout) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(vertexBindingLayout, "vertexBindingLayout");
        Objects.requireNonNull(bindGroupLayout, "bindGroupLayout");

        requireSpecFields(spec);
        requireNoErrors("vertexBindingLayout", vertexBindingLayout.hasErrors(), vertexBindingLayout.errors());
        requireNoErrors("bindGroupLayout", bindGroupLayout.hasErrors(), bindGroupLayout.errors());

        return new PipelineCacheKey(
                spec.programId(),
                spec.location(),
                spec.vertexShader(),
                spec.fragmentShader(),
                spec.vertexBindingNames(),
                spec.optional(),
                vertexBindingLayout.canonicalText(),
                bindGroupLayout.canonicalText());
    }

    private static void requireSpecFields(PipelineSpecIr spec) {
        ModelNames.requireNonBlank(spec.programId(), "programId");
        ModelNames.requireNonBlank(spec.location(), "location");
        ModelNames.requireNonBlank(spec.vertexShader(), "vertexShader");
        ModelNames.requireNonBlank(spec.fragmentShader(), "fragmentShader");
        for (String name : spec.vertexBindingNames()) {
            ModelNames.requireCacheTextSafe(name, "vertexBindingNames[]");
        }
    }

    /** 规范文本：单射编码（长度前缀字段 + 带计数前缀的绑定名列表）。 */
    public String canonicalText() {
        StringBuilder text = new StringBuilder(CANONICAL_HEADER).append('\n');
        CanonicalText.appendField(text, "program", this.programId);
        CanonicalText.appendField(text, "location", this.location);
        CanonicalText.appendField(text, "vertexShader", this.vertexShader);
        CanonicalText.appendField(text, "fragmentShader", this.fragmentShader);
        CanonicalText.appendField(text, "bindingNames", CanonicalText.encodeList(this.vertexBindingNames));
        CanonicalText.appendField(text, "optional", Boolean.toString(this.optional));
        CanonicalText.appendField(text, "vertexLayout", this.vertexLayoutText);
        CanonicalText.appendField(text, "bindGroupLayout", this.bindGroupLayoutText);
        return text.toString();
    }

    /**
     * 规范文本的 SHA-256 十六进制（64 位小写十六进制）。
     *
     * <p><b>不参与相等判定</b>：只用于日志与诊断打印（避免把长键文本刷进日志）。
     */
    public String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(this.canonicalText().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException missingSha256) {
            // 显式失败：JDK 必然提供 SHA-256，缺失说明运行环境损坏，绝不静默降级到弱哈希。
            throw new IllegalStateException(
                    "SHA-256 is required for pipeline cache keys but unavailable", missingSha256);
        }
    }

    private static void requireNoErrors(String what, boolean hasErrors, List<ModelDiagnostic> errors) {
        if (!hasErrors) {
            return;
        }
        List<String> formatted = new ArrayList<>(errors.size());
        for (ModelDiagnostic error : errors) {
            formatted.add(error.format());
        }
        throw new IllegalArgumentException(
                what + " contains ERROR diagnostics, refusing to compute a cache key: " + formatted);
    }
}
