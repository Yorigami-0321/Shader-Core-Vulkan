package dev.vkdisp.config;
/**
 * 【参考调研】F 线选项模型 / 诊断输出口（WARN 不静默的落点）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/07-CONSTRAINTS.md T11「任何降级路径必须显式报错或打 WARN」；
 *    ② docs/18-PARALLEL.md §7.3（并行线证据：降级路径显式报错或 WARN）与 §2 并行判据
 *    （输入输出全是内存数据、不触碰 com.mojang.*、不需要 runClient、能用 JUnit 断言）；
 *    ③ docs/04-SPEC.md §3.5（config/ 组件职责）。
 *    许可证：本仓库自有文档（本项目 MIT）→ 可直接消费。第三方源码零接触（07-CONSTRAINTS L12）。
 *    → 能否并入本项目（MIT）：本文件为独立实现，可并入
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：函数式接口 + JDK 自带 java.lang.System.Logger（System.getLogger）作为默认实现。
 * 2. 备选：直接用 org.slf4j.LoggerFactory（本项目主线 VkDisp.LOGGER 用 LogUtils）—— 否决作为本线默认：
 *    config/ 是 ❄️ 冷路径、要能在无游戏运行的单测里构造，故默认实现只用 JDK 类型；
 *    主线（P4.3）需要接到游戏日志时，用 OptionDiagnosticSink.of(...) 传一个 lambda 桥到 VkDisp.LOGGER 即可，
 *    不必让 config/ 依赖任何日志框架。备选：不提供输出口、只留内存诊断列表 —— 否决（T11 要求"不静默"，
 *    必须有真正的默认输出）。
 * 3. 我们的差异点：① 接口只收 OptionDiagnostic，由默认实现按 severity 选择 JDK 日志级别；
 *    ② 提供 systemLogger()（默认）与 collecting(...)（单测取证）两个静态工厂；
 *    ③ 不提供 noop()：静默降级违反 T11，需要静默的调用方只能自己写空 lambda 并自担后果。
 * 4. 许可证核对：本项目 MIT；只用 JDK 标准库类型，零第三方代码复制。
 * 5. 性能基线：❄️ 冷路径（只在钳制 / 非法值 / 缺省这类边界上调用），不做任何性能优化（18-PARALLEL §7.7）。
 */
import java.util.List;
import java.util.Objects;

/**
 * 选项诊断的输出口（{@code 07-CONSTRAINTS.md} T11：降级必须可见，不许静默）。
 *
 * <p>实现必须非阻塞、无副作用地记录一条诊断；本接口不做节流、不做格式化决策
 * （格式化交给 {@link OptionDiagnostic#format()} 与具体实现）。
 */
@FunctionalInterface
public interface OptionDiagnosticSink {

    /** 记录一条诊断。 */
    void accept(OptionDiagnostic diagnostic);

    /**
     * 默认实现：写到 JDK 的 {@link System.Logger}（{@code vkdisp.config}），按 severity 选级别。
     * 无需任何日志框架即可在游戏与单测里输出。
     */
    static OptionDiagnosticSink systemLogger() {
        return diagnostic -> {
            System.Logger logger = System.getLogger("vkdisp.config");
            System.Logger.Level level = switch (diagnostic.severity()) {
                case ERROR -> System.Logger.Level.ERROR;
                case WARN -> System.Logger.Level.WARNING;
                case INFO -> System.Logger.Level.INFO;
            };
            logger.log(level, "[vkdisp] " + diagnostic.format());
        };
    }

    /** 取证实现在：把诊断对象收进给定列表（单测用）。 */
    static OptionDiagnosticSink collecting(List<OptionDiagnostic> target) {
        Objects.requireNonNull(target, "target");
        return target::add;
    }

    /** 取证实现在：把 {@link OptionDiagnostic#format()} 单行文本收进给定列表（单测用）。 */
    static OptionDiagnosticSink collectingLines(List<String> target) {
        Objects.requireNonNull(target, "target");
        return diagnostic -> target.add(diagnostic.format());
    }
}
