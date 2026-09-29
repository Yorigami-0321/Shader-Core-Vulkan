package dev.vkdisp;
/**
 * 【参考调研】F4 测试基建冒烟 / JUnit 5 官方文档
 * 0. 合规核对（第 0 步闸门）：
 *    参考对象 = JUnit 5 (junit-team/junit5) 官方 Quick Start 用法（assertEquals / @Test 约定）。
 *    许可证 = EPL-2.0（仓库 LICENSE 文件）→ 仅作为 testImplementation 依赖引入，
 *    不复制其源码；EPL 依赖不进入分发 jar（test scope 不打包）。
 *    → 能否并入本项目（MIT）：可以（依赖与本项目代码分离，jar 不含 JUnit）
 *    → 例外条款：无 GPL/LGPL/ARR 内容
 * 1. 官方/主实现：JUnit Jupiter 5（@Test / assertEquals 标准 API）。
 * 2. 备选：无（18-PARALLEL.md F4 指定 JUnit 5）。
 * 3. 我们的差异点：仅验证「测试基建可用」这一事实，无业务逻辑。
 * 4. 许可证核对：本项目 MIT；JUnit EPL-2.0 仅测试期依赖。
 * 5. 性能基线：测试代码不进运行时，无性能影响。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** F4 闸门冒烟：证明 build.gradle 的 JUnit 5 接线与 src/test 骨架真实可用（不是"看起来对"）。 */
class F4InfraSmokeTest {
    @Test
    void junitPlatformIsWired() {
        assertEquals(4, 2 + 2, "JUnit 断言通道必须真实工作");
    }

    @Test
    void mainSourceSetIsOnTestClasspath() {
        // 冒烟：测试 classpath 能看到 main 源码（保证后续单测可测业务类）
        assertTrue(dev.vkdisp.VkDisp.MOD_ID.equals("vkdisp"), "main 源码应在测试 classpath 上");
    }
}
