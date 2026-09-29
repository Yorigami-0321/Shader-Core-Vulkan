package dev.vkdisp.pack;

/**
 * 【参考调研】program 的 uniform 声明（04-SPEC §3.2 内建 uniform 语义）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §3.2「OF 内建 uniform（必须提供的语义）」表与 §3.3
 *    （BindGroupFactory：从 uniform/采样器声明生成 BindGroupLayout）——任务点名 §3.2 为字段设计依据；
 *    ② OptiFine 官方文档（sp614x/optifine，OptiFineDoc/doc/shaders.txt「Uniforms」表）中 uniform 名与类型的事实。
 *    许可证：sp614x/optifine 无 LICENSE（GitHub license API 404）→ ARR，按 07-CONSTRAINTS X20 不并入其文本表达，
 *    仅用不受版权保护的事实（uniform 名/类型），零文本复制；Iris（LGPL-3.0，已核 LICENSE）同口径；
 *    参考模组（VulkanMod / Sulkan / Beryl）零接触。
 *    → 能否并入本项目（MIT）：本文件为独立实现的 record，只含格式事实，可并入
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：04-SPEC §3.2 内建表的类型词汇（mat4 / vec3 / float / int / ivec2）+ OF 官方 uniform 表；
 *    声明由 GLSL 源扫描得到（C/D 线解析产物落进 Program#uniforms）。
 * 2. 备选：无（内建 uniform 名单表本身不放这里——它归 glsl/UniformInjector（D 线，04-SPEC §3.2/§3.3 转译层），
 *    避免同一张表在两处冻结成双源）。
 * 3. 我们的差异点：① 只承载"某 program 源码里声明了 uniform"这一事实（名字 + GLSL 类型），
 *    不区分内建/自定义——是否内建由 D 线对照 §3.2 表判定；② 不在此表达绑定组/槽位语义（那是 E 线 BindGroupLayout 中间表示）；
 *    ③ shaders.properties 的 uniform.&lt;type&gt;.&lt;name&gt;=表达式 属于自定义 uniform 运行时值（F 线 OptionBinding 范畴），
 *    不混进源码声明列表。
 * 4. 许可证核对：本项目 MIT；本文件零第三方代码复制，仅含文档事实。
 * 5. 性能基线：❄️ 冷路径（解析期构造一次，管线构建/选项绑定只读），不做任何性能优化（18-PARALLEL §7.7）。
 */

/**
 * 单条 uniform 声明（record，不可变）。
 *
 * <p><b>冻结契约</b>：字段已按 {@code docs/18-PARALLEL.md} §3 F2 冻结，供 A/B/C/D/E/F 并行线与关键路径共同消费
 * （E 线 BindGroupLayout 中间表示、F 线选项 → uniform 绑定都按本结构消费）。
 * 任何字段/语义变更必须走 {@code 18-PARALLEL.md} §3.2 流程（提出方说明 → env-1 统一改 → 契约版本号 +1 → 通知各环境 rebase），
 * 任何一方不得自行增删改（{@code 07-CONSTRAINTS.md} X12）。冷路径数据，只求清晰、不做性能优化（§7.7）。
 *
 * @param name    uniform 名（如 "gbufferModelView"、"frameTimeCounter"）；非空白，构造时裁剪
 * @param glslType GLSL 类型原文（如 "mat4"、"vec3"、"float"、"int"、"ivec2"）；非空白，构造时裁剪
 */
public record UniformDecl(String name, String glslType) {

    /** 冻结契约声明见类级 Javadoc。 */
    public UniformDecl {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("vkdisp: uniform name must not be blank");
        }
        name = name.trim();
        if (glslType == null || glslType.isBlank()) {
            throw new IllegalArgumentException("vkdisp: uniform '" + name + "' glslType must not be blank");
        }
        glslType = glslType.trim();
    }
}
