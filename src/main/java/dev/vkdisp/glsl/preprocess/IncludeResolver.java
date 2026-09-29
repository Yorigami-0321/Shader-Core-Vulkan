package dev.vkdisp.glsl.preprocess;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 【参考调研】C 线 — #include 文件解析接口
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = OptiFine / Iris 官方文档对 #include 的"路径如何解析"这一事实性描述
 *    （handover/ABC-LINES-HANDOVER.md §6.5：绝对路径前导 / → 相对 shaders/ 顶层；
 *    相对路径 → 相对当前文件目录）—— 属于格式事实，不受版权保护。
 *    外部候选 IrisShaders/glsl-preprocessor（GPL-3.0 + 例外条款）→ 一律按禁止处理
 *    （handover §2.1 / 07-CONSTRAINTS X20/X21），本任务不读其代码、零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 接口，不含任何外部项目代码。
 *    → 能否并入本项目（MIT）：可以（仅采纳不受版权保护的事实性路径语义）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：本仓库 handover §6.5 的 #include 路径语义 —— 解析器只负责把"被包含路径"
 *    翻译成一个相对 shaders/ 顶层的 key，真正的文件读取由调用方注入（测试用内存 Map，
 *    运行时可由资源系统实现），从而与 GPU / Mojang 完全解耦。
 * 2. 备选：把文件 IO 直接写进处理器 —— 会造成单测必须依赖真实磁盘文件，违背"冷路径可离线单测"
 *    （handover §0），故改为接口注入。
 * 3. 我们的差异点：接口只暴露 read(pathFromShadersRoot)，路径归一（去 ./、解析 ../、反斜杠归一）
 *    由 IncludeProcessor 负责，本接口保持最小、可测试。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径（包加载期一次性），无缓存、无性能优化（18-PARALLEL §7.7、T14 达标即停）。
 */
/**
 * #include 文件来源。把"被包含路径"翻译成相对 {@code shaders/} 顶层的 key，由调用方提供内容。
 *
 * <p>处理器只调用 {@link #read(String)}；路径归一（绝对 / 相对、去 {@code ./}、解析 {@code ../}）
 * 在 {@link IncludeProcessor} 内完成。这样单测可用内存 {@link Map} 注入，运行期可接资源系统，
 * 全程不触碰任何 {@code com.mojang.*} 类型。
 */
public interface IncludeResolver {

    /**
     * 读取某个相对 {@code shaders/} 顶层的文件内容。
     *
     * @param pathFromShadersRoot 归一后的相对路径，如 {@code "lib/common.glsl"}
     * @return 文件内容；不存在返回 {@code null}（由处理器报"找不到包含文件"）
     */
    String read(String pathFromShadersRoot);

    /** 基于内存 {@link Map} 的解析器（测试与简单场景使用）。 */
    static IncludeResolver of(Map<String, String> files) {
        Map<String, String> copy = new LinkedHashMap<>(files);
        return copy::get;
    }
}
