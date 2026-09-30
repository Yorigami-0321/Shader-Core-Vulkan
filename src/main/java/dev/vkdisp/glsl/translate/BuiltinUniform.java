package dev.vkdisp.glsl.translate;

import java.util.Objects;

/**
 * 【参考调研】D 线 GLSL 转译 / OF 方言与现代 GLSL 的事实性差异 + 04-SPEC §3.2 内建 uniform 表
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/04-SPEC.md §3.2（OF 内建 uniform 表）、§3.3（attribute/varying 老式语法）、
 *    §4（顶点属性扩展表）、docs/08-TESTING.md §4、docs/18-PARALLEL.md §4 D 线（独占路径与完成标准）、
 *    docs/03-DIRECTION.md §3.2（"OF 有 attribute/varying 老式语法、gl_ 内建差异"）的事实性要求 ——
 *    仓库内文档与 GLSL 官方限定符语义均为公开事实，不受版权保护。
 *    外部候选 IrisShaders/glsl-transformer（GPL-3.0 + 例外条款）与 glsl-preprocessor（同）→
 *    例外条款是否覆盖本项目未核实，一律按禁止处理（07-CONSTRAINTS L12 §1.3 陷阱 2 / X21），
 *    本任务不读其代码、零代码行并入（07-CONSTRAINTS §〇 P1 / L5 / X19）。
 *    许可证：本文件为独立编写的纯 Java 实现，不含任何外部项目代码。
 *    → 能否并入本项目（MIT）：可以 —— 只采纳不受版权保护的事实性信息（方言差异、uniform 语义、行号要求）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：现代 core profile GLSL 的限定符语义 —— 顶点阶段输入/输出限定符为 in/out，
 *    片元阶段输入为 in，attribute 仅存在于顶点阶段（老式 GLSL 1.20 及以前）；内建 uniform 表
 *    逐条取自 04-SPEC §3.2（23 条：gbufferModelView 至 eyeBrightnessSmooth）。
 * 2. 备选：无 —— 不引入任何 GLSL 解析库（07-CONSTRAINTS T4 只禁 SPIR-V，此处亦无必要）；
 *    本线只做文本级行内重写，不建 AST（冷路径清晰优先，18-PARALLEL §7.7）。
 * 3. 我们的差异点：① 行内重写不改行数，C 线的行号映射不被切断；② 内建 uniform 按 04-SPEC
 *    固定顺序、只注入缺失项、已声明项不重写（幂等）；③ 诊断只报问题（WARN/ERROR），
 *    失败绝不静默（T11），成功不产生噪声日志。
 * 4. 许可证核对结论：本项目 MIT；GPL-3.0+例外参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径（包加载期一次，08-TESTING §8 解析+转译全部 program ≤ 1 秒 由 P2 主线
 *    实测把关）；本文件线性扫描，无缓存、无预优化（18-PARALLEL §7.7、T14 达标即停）。
 */
/**
 * OF 内建 uniform 的一条规格（04-SPEC §3.2 表的一行：名称 + GLSL 类型 + 语义）。
 *
 * <p>本 record 只描述"该注入什么声明"，不负责取值与上传 —— 上传语义见 04-SPEC §3.4 的
 * {@code OfUniformManager}（关键路径，不属本线）。
 *
 * @param name          uniform 名（必须与 GLSL 源码里使用的名字字面一致）
 * @param type          GLSL 类型（mat4 / vec3 / float / int / ivec2），取自 04-SPEC §3.2
 * @param descriptionZh 中文语义（文档原文）
 * @param descriptionEn 英文语义（便于日志与后续文档）
 */
public record BuiltinUniform(String name, String type, String descriptionZh, String descriptionEn) {

    /** 记录构造：四个字段都是声明本体，缺一不可（快速失败）。 */
    public BuiltinUniform {
        Objects.requireNonNull(name, "name 不许为 null");
        Objects.requireNonNull(type, "type 不许为 null");
        Objects.requireNonNull(descriptionZh, "descriptionZh 不许为 null");
        Objects.requireNonNull(descriptionEn, "descriptionEn 不许为 null");
    }

    /** 独立声明文本（包源码形态），例如 {@code uniform mat4 gbufferModelView;}。 */
    public String declaration() {
        return "uniform " + this.type + " " + this.name + ";";
    }

    /**
     * 块成员声明文本（不带 {@code uniform} 关键字），例如 {@code mat4 gbufferModelView;}。
     *
     * <p>注入时用它而不是 {@link #declaration()}：Vulkan GLSL 禁止非透明 uniform 游离在块外
     * （驱动实测 shaderc 原文 {@code 'non-opaque uniforms outside a block' : not allowed when
     * using GLSL for Vulkan}），{@link UniformInjector} 把全部内建发射进单个具名无实例名块
     * {@code layout(std140) uniform VkDispBuiltins { … };}（原版 shader 同款形态）——
     * 无实例名块的成员仍在全局作用域，包源码里的 {@code gbufferModelView * …} 引用字面不变。
     */
    public String blockMember() {
        return this.type + " " + this.name + ";";
    }
}
