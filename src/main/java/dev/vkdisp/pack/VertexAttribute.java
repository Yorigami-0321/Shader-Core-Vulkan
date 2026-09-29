package dev.vkdisp.pack;

/**
 * 【参考调研】顶点格式扩展属性（04-SPEC §4 属性表 + OF 官方 Attributes 表）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §4「顶点格式扩展」属性表（字段设计的权威依据，任务点名）；
 *    ② OptiFine 官方文档（sp614x/optifine，OptiFineDoc/doc/shaders.txt「Attributes」表）中属性名与分量类型的事实。
 *    许可证：sp614x/optifine 无 LICENSE（GitHub license API 404）→ ARR，按 07-CONSTRAINTS X20 不并入其文本表达，
 *    仅使用不受版权保护的事实性信息（属性名、分量个数与类型），零文本复制；Iris（LGPL-3.0，已核 LICENSE）同口径；
 *    参考模组（VulkanMod / Sulkan / Beryl）零接触。04-SPEC §4 引用的 SKILL.md"字段名字面一致"坑同样只取结论。
 *    → 能否并入本项目（MIT）：本文件为独立实现的枚举，只含格式事实，可并入
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：04-SPEC §4 属性表——Position(vec3f) / Color(vec4ub) / UV0(vec2f) / UV1(vec2s) / UV2(vec2s) /
 *    Normal(vec3b) / mc_Entity(vec2s) / mc_midTexCoord(vec2f)；OF 官方 Attributes 表还列出 at_tangent、at_velocity、at_midBlock。
 * 2. 备选：VulkanMod 的 GlUtil.vulkanFormat 格式表思路（18-PARALLEL §4 E 线参考列，LGPL 只读思路）——本文件只存属性引用，
 *    stride/offset 计算表归 E 线 pipeline/model/，不在此重复。
 * 3. 我们的差异点：① 04-SPEC §4 之外补了 at_tangent / at_velocity / at_midBlock（OF 官方属性表事实，
 *    主流 PBR 包会声明；04-SPEC 未列，若 §4 不采纳可走 18-PARALLEL §3.2 移除）；
 *    ② 已知出入：04-SPEC §4 标 mc_Entity 为 vec2s（我们合成时只带方块 ID），OF 官方表为 vec3（xy = blockId/renderType），
 *    UV1/UV2 的"用途"描述与 OF 官方 vaUV1=overlay / vaUV2=lightmap 亦不一致——本契约只冻结"名字 + 类型"两列，
 *    按 §4 为准；用途出入已随本任务上报 lead，由 env-1 决定是否修订 §4；
 *    ③ 字面一致要求直接用 GLSL 属性名做枚举常量（Position / mc_midTexCoord 等是合法 Java 标识符），name() 即字面量。
 * 4. 许可证核对：本项目 MIT；本文件零第三方代码复制，仅含文档事实。
 * 5. 性能基线：❄️ 冷路径（构建管线/格式时引用一次），不做任何性能优化（18-PARALLEL §7.7）。
 */

/**
 * 顶点属性引用（枚举常量名即 GLSL/VertexFormat 里字面一致的属性名，04-SPEC §4 明确要求"字段名必须与着色器声明完全一致"）。
 *
 * <p><b>冻结契约</b>：字段已按 {@code docs/18-PARALLEL.md} §3 F2 冻结，供 A/B/C/D/E/F 并行线与关键路径共同消费。
 * 任何字段/语义变更必须走 {@code 18-PARALLEL.md} §3.2 流程（提出方说明 → env-1 统一改 → 契约版本号 +1 → 通知各环境 rebase），
 * 任何一方不得自行增删改（{@code 07-CONSTRAINTS.md} X12）。冷路径数据，只求清晰、不做性能优化（§7.7）。
 *
 * <p>类型命名法沿用 04-SPEC §4：字母后缀表示分量类型（f = float32、s = int16、b = int8、ub = uint8），
 * 如 vec3f = 3 × float32。at_* 三项按 OF 官方文档的分量类型换算到同一命名法（vec4f / vec3f）。
 * 字节对齐与 stride/offset 的计算不在此冻结——归 E 线 pipeline/model/ 的计算表（18-PARALLEL §4 E）。
 */
public enum VertexAttribute {

    /** 位置（04-SPEC §4：vec3f；原版 VertexFormat 既有元素）。 */
    Position("vec3f", false),

    /** 顶点色（04-SPEC §4：vec4ub；原版既有元素）。 */
    Color("vec4ub", false),

    /** 主纹理坐标槽（04-SPEC §4：vec2f；原版既有元素）。 */
    UV0("vec2f", false),

    /** 纹理坐标槽 1（04-SPEC §4：vec2s；原版既有元素。用途描述见 §4 表与其出入说明：【参考调研】第 3 条）。 */
    UV1("vec2s", false),

    /** 纹理坐标槽 2（04-SPEC §4：vec2s；原版既有元素。用途描述见 §4 表与其出入说明：【参考调研】第 3 条）。 */
    UV2("vec2s", false),

    /** 法线（04-SPEC §4 加粗：vec3b，OF PBR 必备；OF 扩展属性）。 */
    Normal("vec3b", true),

    /** 方块/实体 ID（04-SPEC §4 加粗：vec2s，供 block.properties 图层号查询；OF 扩展属性）。 */
    mc_Entity("vec2s", true),

    /** 方块中心 UV（04-SPEC §4 加粗：vec2f；OF 扩展属性）。 */
    mc_midTexCoord("vec2f", true),

    /** 切线向量 + 手性（OF 官方属性表：vec4；04-SPEC §4 未列，预留——PBR 法线贴图用）。 */
    at_tangent("vec4f", true),

    /** 相对上一帧的顶点位移（OF 官方属性表：vec3；04-SPEC §4 未列，预留——运动模糊类效果用）。 */
    at_velocity("vec3f", true),

    /** 相对方块中心的偏移，1/64 米单位（OF 官方属性表：vec3；04-SPEC §4 未列，预留——仅方块）。 */
    at_midBlock("vec3f", true);

    private final String glslType;
    private final boolean extension;

    VertexAttribute(String glslType, boolean extension) {
        this.glslType = glslType;
        this.extension = extension;
    }

    /** 分量类型（04-SPEC §4 命名法，如 "vec3f"、"vec2s"、"vec4ub"）。 */
    public String glslType() {
        return glslType;
    }

    /**
     * 是否为 OF 扩展属性：true = 原版 VertexFormat 默认不含，管线层必须按 04-SPEC §4 主动补建
     * （false = 原版既有元素）。Normal/mc_Entity/mc_midTexCoord 的 true 来自 §4 表加粗标注；
     * at_* 三项因 §4 未收录而天然不是原版默认元素，同样为 true。
     */
    public boolean extension() {
        return extension;
    }
}
