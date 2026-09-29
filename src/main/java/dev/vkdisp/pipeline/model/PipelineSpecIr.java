package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】E 线纯计算件 / 管线声明中间表示（F1 RenderApi.PipelineSpec 的纯 Java 镜像）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① F1 已冻结的 dev.vkdisp.bridge.RenderApi.PipelineSpec（本项目自有契约，字段与语义直接参照）；
 *    ② 本仓库 docs/04-SPEC.md §3.3（管线构建用到的 location / vertexShader / fragmentShader 声明）。
 *    许可证：本项目自有契约与自有文档（MIT）→ 可直接消费；
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触
 *    —— 按 docs/07-CONSTRAINTS.md L12「判不过就换参考，不再读它的代码」。
 *    → 能否并入本项目（MIT）：本文件为独立实现，可并入
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：RenderApi.PipelineSpec 的五个字段（location / vertexShader / fragmentShader /
 *    vertexBindingNames / optional）；本类逐字段镜像，语义不变。
 * 2. 备选：直接复用 RenderApi.PipelineSpec —— 部分否决（它的字段类型是 net.minecraft.resources.Identifier，
 *    而 E 线要能在无游戏运行的单测里做纯 Java 计算与打印比对；Adapter of(...) 保留 F1 → E 线的一向转换）。
 * 3. 我们的差异点：① Identifier 降级为纯 String（打印/比对/单测零原版类型）；
 *    ② 增一个 programId 字段（Program 的逻辑名，如 composite1），与 location 一起进缓存键，
 *    让「同一个 Program 稳定、不同 Program 不碰撞」有第二个可断言维度（08-TESTING §6）；
 *    ③ canonicalText() 用长度前缀编码，保证键文本单射。
 * 4. 许可证核对：本项目 MIT；只镜像本项目自有契约字段，零第三方代码复制。
 * 5. 性能基线：❄️ 冷路径小对象，不做任何性能优化（18-PARALLEL §7.7）。
 */
import java.util.List;
import java.util.Objects;

import dev.vkdisp.bridge.RenderApi;

/**
 * 一条管线声明的纯 Java 中间表示（对应 04-SPEC §3.3 的构建输入）。
 *
 * <p>字段与 F1 {@link RenderApi.PipelineSpec} 一一对应，只把 {@code Identifier} 换成 {@code String}。
 * 本类只承载声明，不调用任何原版构建 API（🔴 18-PARALLEL §4 E 线禁止写 PipelineFactory）。
 *
 * @param programId          Program 逻辑名（如 {@code composite1}），用于缓存键区分不同 Program
 * @param location           管线注册位置，如 {@code vkdisp:pipeline/composite_1}
 * @param vertexShader       顶点着色器标识，如 {@code vkdisp:fullscreen}
 * @param fragmentShader     片元着色器标识，如 {@code vkdisp:composite_1}
 * @param vertexBindingNames 顶点格式逻辑名列表（如 {@code POSITION_COLOR}），顺序即绑定顺序
 * @param optional           是否可选管线（编译失败时允许降级 WARN）
 */
public record PipelineSpecIr(
        String programId,
        String location,
        String vertexShader,
        String fragmentShader,
        List<String> vertexBindingNames,
        boolean optional
) {

    /** 规范文本头（版本化）。 */
    public static final String CANONICAL_HEADER = "pipeline-spec-ir v1";

    /** 紧凑构造器：字段非 null，列表不可变。 */
    public PipelineSpecIr {
        Objects.requireNonNull(programId, "programId");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(vertexShader, "vertexShader");
        Objects.requireNonNull(fragmentShader, "fragmentShader");
        vertexBindingNames = List.copyOf(vertexBindingNames);
    }

    /**
     * 从 F1 冻结契约适配（唯一允许接触 bridge 类型的方向：F1 → E 线）。
     *
     * @param spec      F1 的管线描述（不可为 null）
     * @param programId Program 逻辑名（补充字段，F1 契约里没有）
     * @return E 线纯 Java 中间表示
     */
    public static PipelineSpecIr of(RenderApi.PipelineSpec spec, String programId) {
        Objects.requireNonNull(spec, "spec");
        return new PipelineSpecIr(
                programId,
                spec.location().toString(),
                spec.vertexShader().toString(),
                spec.fragmentShader().toString(),
                spec.vertexBindingNames(),
                spec.optional());
    }

    /** 顶点绑定数量。 */
    public int bindingCount() {
        return this.vertexBindingNames.size();
    }

    /** 规范文本（长度前缀编码，可打印比对；键计算复用同一编码）。 */
    public String canonicalText() {
        StringBuilder text = new StringBuilder(CANONICAL_HEADER).append('\n');
        CanonicalText.appendField(text, "program", this.programId);
        CanonicalText.appendField(text, "location", this.location);
        CanonicalText.appendField(text, "vertexShader", this.vertexShader);
        CanonicalText.appendField(text, "fragmentShader", this.fragmentShader);
        CanonicalText.appendField(text, "bindingNames", CanonicalText.encodeList(this.vertexBindingNames));
        CanonicalText.appendField(text, "optional", Boolean.toString(this.optional));
        return text.toString();
    }
}
