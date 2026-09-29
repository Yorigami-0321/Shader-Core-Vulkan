package dev.vkdisp.glsl.translate;

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
 * 着色器阶段 —— D 线转译所需的最小语境（决定 attribute / varying 的现代 GLSL 语义）。
 *
 * <p><b>为什么必须有它</b>：现代 core profile 里 varying 在顶点阶段是输出（out）、
 * 在片元阶段是输入（in），而 attribute 只允许出现在顶点阶段。
 * 没有阶段信息就无法重写 —— 此时本线**拒绝猜测**（07-CONSTRAINTS X9），改为产出 ERROR 诊断（T11）。
 *
 * <p>{@link #UNKNOWN} 保留给调用方拿不到阶段信息的场景：若输入里没有任何
 * attribute / varying 声明，转译是纯透传（成功）；一旦出现声明则报 ERROR。
 */
public enum ShaderStage {

    /** 顶点着色器（.vsh）：attribute 改写为 in，varying 改写为 out。 */
    VERTEX,

    /** 片元着色器（.fsh）：varying 改写为 in；attribute 在该阶段非法。 */
    FRAGMENT,

    /** 阶段未知：出现 attribute / varying 声明时显式报 ERROR，不猜方向。 */
    UNKNOWN;

    /** 阶段是否已知（{@link #UNKNOWN} 之外均为已知）。 */
    public boolean isKnown() {
        return this != UNKNOWN;
    }
}
