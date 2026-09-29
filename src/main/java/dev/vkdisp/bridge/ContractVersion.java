package dev.vkdisp.bridge;
/**
 * 【参考调研】F1 契约冻结 / 18-PARALLEL §3.2 变更流程
 * 0. 合规核对（第 0 步闸门）：
 *    参考对象 = 本项目 docs/18-PARALLEL.md §3.2「契约冻结后的变更流程」（内部文档，非第三方代码）。
 *    → 能否并入本项目（MIT）：本文件为本项目原创常量，无第三方内容
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：无外部参考（本项目自定的契约版本机制）。
 * 2. 备选：无。
 * 3. 我们的差异点：仅一个整型常量 + Javadoc 流程说明。
 * 4. 许可证核对：本项目 MIT，零第三方代码。
 * 5. 性能基线：编译期常量，无运行时开销。
 */

/**
 * F1 冻结契约的版本号（{@code docs/18-PARALLEL.md} §3.2）。
 *
 * <p><b>变更流程（不可跳过）</b>：
 * <ol>
 *   <li>提出方说明：哪个字段/方法不够用、为什么、影响哪几条线</li>
 *   <li>env-1 统一改契约（其他环境不许自己改）</li>
 *   <li>契约版本号 +1（改 {@link #VERSION}）</li>
 *   <li>通知所有环境 rebase + 重跑各自单测</li>
 * </ol>
 *
 * <p><b>禁止</b>：并行线在自己的分支上偷偷改冻结字段（违反 07-CONSTRAINTS.md X12）。
 */
public final class ContractVersion {
    /** 当前契约版本。每次按 §3.2 流程改动 F1–F3 冻结物后 +1。 */
    public static final int VERSION = 1;

    /** F1 冻结物清单（bridge/ 接口签名）；F2 = pack/ 数据模型；F3 = glsl/ TranslateResult。 */
    public static final String F1 = "bridge-api";
    /** F2 冻结物：{@code dev.vkdisp.pack} 数据模型。 */
    public static final String F2 = "pack-model";
    /** F3 冻结物：{@code dev.vkdisp.glsl} TranslateResult / TranslateDiagnostic。 */
    public static final String F3 = "glsl-contract";

    private ContractVersion() {}
}
