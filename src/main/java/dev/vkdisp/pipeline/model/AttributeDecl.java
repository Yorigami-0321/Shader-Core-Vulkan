package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】E 线纯计算件 / 一条顶点属性声明（名字 + 类型）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §4「顶点格式扩展」属性表（名字列 + 类型列，任务点名的权威依据）；
 *    ② F2 已冻结的 dev.vkdisp.pack.VertexAttribute（同仓库契约，名字/类型与 §4 逐项对应）。
 *    许可证：本仓库自有文档与自有契约（MIT 项目）→ 可直接消费；参考模组（VulkanMod LGPL-3.0 /
 *    Sulkan GPL-3.0 / Beryl ARR）零接触 —— 按 docs/07-CONSTRAINTS.md L12「判不过就换参考，不再读它的代码」。
 *    → 能否并入本项目（MIT）：本文件为独立实现，可并入
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：04-SPEC §4 的「名字 + 类型」两列（Position/vec3f、Color/vec4ub、UV0/vec2f、
 *    UV1/vec2s、UV2/vec2s、Normal/vec3b、mc_Entity/vec2s、mc_midTexCoord/vec2f）。
 * 2. 备选：直接用 F2 的 VertexAttribute 枚举当唯一输入 —— 部分否决（枚举的类型是编译期固定的，
 *    单测需要构造「未知类型」这种损坏输入来验证 T11 显式诊断路径，所以保留 String 类型的声明载体；
 *    VertexAttribute 通过 of(...) 适配，枚举仍是生产路径的入口）。
 * 3. 我们的差异点：声明里保留原始类型字符串（而不是直接给枚举），好处是未知类型能在
 *    VertexLayout 里变成 UNKNOWN_ATTRIBUTE_TYPE 诊断而不是编译不过或静默按 0 字节算；
 *    canonicalText() 给出稳定单行形式，供缓存键与打印比对复用。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制，只含文档事实。
 * 5. 性能基线：❄️ 冷路径小对象，不做任何性能优化（18-PARALLEL §7.7）。
 */
import java.util.Objects;
import java.util.Optional;

import dev.vkdisp.pack.VertexAttribute;

/**
 * 一条顶点属性声明：GLSL 里的属性名 + 04-SPEC §4 命名法的类型名。
 *
 * <p>名字合法性（GLSL 标识符）与类型是否受支持都在 {@link VertexLayout} 里转成
 * {@link ModelDiagnostic} 显式回报（T11），本 record 只保证字段非 null。
 *
 * @param name     属性名，必须与着色器 attribute 声明字面一致（04-SPEC §4），如 {@code mc_midTexCoord}
 * @param glslType 04-SPEC §4 命名法类型名（如 {@code vec3f} / {@code vec4ub}）；未知类型由消费方显式诊断
 */
public record AttributeDecl(String name, String glslType) {

    /** 紧凑构造器：字段非 null。 */
    public AttributeDecl {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(glslType, "glslType");
    }

    /** 从 F2 冻结枚举适配（名字即枚举常量名，类型即枚举的 glslType()）。 */
    public static AttributeDecl of(VertexAttribute attribute) {
        Objects.requireNonNull(attribute, "attribute");
        return new AttributeDecl(attribute.name(), attribute.glslType());
    }

    /** 用受支持的格式直接构造。 */
    public static AttributeDecl named(String name, VertexElementFormat format) {
        Objects.requireNonNull(format, "format");
        return new AttributeDecl(name, format.glslName());
    }

    /** 查该声明对应的受支持格式；未知类型 → empty（调用方必须显式诊断）。 */
    public Optional<VertexElementFormat> format() {
        return VertexElementFormat.tryParse(this.glslType);
    }

    /** 稳定单行形式：{@code <name> <glslType>}（供缓存键与打印比对）。 */
    public String canonicalText() {
        return this.name + " " + this.glslType;
    }
}
