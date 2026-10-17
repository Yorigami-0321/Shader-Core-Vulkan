package dev.vkdisp.pack;
/**
 * 【参考调研】四个 Switch 类共用的一句判据：本进程的 FML 配置加载了没有
 * 0. 合规核对（第 0 步闸门）：参考对象 = NeoForge {@code ModConfigSpec#isLoaded()}（公开方法，javap 核实）
 *    与本仓 {@link dev.vkdisp.VkDispConfig#SPEC}。→ 可并入本项目（LGPL-3.0-or-later）：是，只调公开 API。
 * 1. 官方/主实现：{@code ModConfigSpec.isLoaded()}。
 * 2. 备选：catch 那句 {@code IllegalStateException("Cannot get config value before config is loaded")}
 *    的**消息文本**（否决：本仓纪律 —— 针不许绑在别人的措辞上，见 {@code 07} §1.4.1bis 那条假绿针）。
 * 3. 我们的差异点：把「**类在、配置没加载**」与「开关坏掉」分开。这第三态此前没人管：
 *    2026-10-14 把主源集构件接进测试类路径后，{@code ChainEnableGatingTest} 当场变红 ——
 *    {@code ConfigValue#get()} 抛 {@code IllegalStateException}，落进 {@code catch (Throwable)}
 *    ⇒ 留下假的 {@code reflectionFailure} 位（QD-14 那一族的第三个样本）。
 * 4. 许可证核对：本项目 LGPL-3.0-or-later。
 * 5. 性能基线：❄️ 只在链装配 / 生成期被读，不在每帧路径上。
 */
final class SwitchConfigAccess {

    private SwitchConfigAccess() {
    }

    /**
     * {@code VkDispConfig.SPEC.isLoaded()} —— 仍然走反射，理由与四个 Switch 类本身相同
     * （它们不许静态依赖 FML，否则无 FML 环境的进程连类都加载不了）。
     *
     * @return 配置已加载 = {@code true}；没加载（单测 / 非游戏 JVM）= {@code false}
     * @throws IllegalStateException {@code SPEC} 字段读不到 —— 那才是真故障，必须留痕，
     *                               不许被当成「没加载」静默回落默认值（QD-02「死开关」那一族）
     */
    static boolean loaded(Class<?> vkDispConfig) {
        try {
            Object spec = vkDispConfig.getField("SPEC").get(null);
            return spec != null && Boolean.TRUE.equals(spec.getClass().getMethod("isLoaded").invoke(spec));
        } catch (NoSuchFieldException | NoSuchMethodException | IllegalAccessException
                | java.lang.reflect.InvocationTargetException e) {
            throw new IllegalStateException(
                    "VkDispConfig.SPEC 读不到（isLoaded 判不出）—— 开关的第三态失效了，不是「配置没加载」", e);
        }
    }
}
