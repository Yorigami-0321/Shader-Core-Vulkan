package dev.vkdisp;
/**
 * 【参考调研】取证纪律守卫：每轮取证**必须在 Vulkan 上**，且**不做性能结论**
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓库 `docs/01-DEV-LOOP.md` §1.2（新增）
 *    与 `docs/00-INDEX.md`「给 AI 读者」块；二者都是本仓库自有规范。
 *    背景事实（实测，见 `evidence/h36-…` 与 `h33` §八）：
 *    本机 WSL2 系统级无 Vulkan ICD，而 `runClient` 带 `--graphicsBackend VULKAN`；
 *    Minecraft 在 loader 缺失时**不崩也不退出**，只打两行然后静默退回 OpenGL 继续跑
 *    ⇒ `h33`/`h34`/`h35` **三轮取证全是 OpenGL 产物**，而日志里除那两行外一切正常。
 * 1. 官方/主实现：无（纯文档文本断言）。
 * 2. 备选：
 *    <ul>
 *      <li>① 只写进文档不设守卫 —— <b>否决</b>：`h33`/`h34`/`h35` 三轮的证据文档
 *          都「如实记录了跑在 OpenGL」，但仍然被当成了可用证据用了三轮
 *          ⇒ 光写「应该怎样」拦不住人，<b>必须有构建期红灯</b>。</li>
 *      <li>② 在单测里检查运行期后端 —— <b>做不到</b>：单测 JVM 里没有 GPU/游戏进程，
 *          后端是运行期事实。</li>
 *    </ul>
 * 3. 我们的差异点：把「规范文档仍然写着 Vulkan 强制要求 + 禁止把裸
 *    `./gradlew runClient` 当作取证步骤」做成构建期红灯，防止规范被后来者删掉或改松。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 单测（只读文档，不进任何运行时）。
 */
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「每轮取证必须在 Vulkan 上」这条纪律的守卫。
 *
 * <p>🔖 <b>为什么需要守卫</b>：这条纪律已经被人违反了整整三轮而没有任何机制报警 ——
 * 每轮的证据文档都<em>如实写着</em>「跑在 OpenGL」，但它仍然被当成了有效证据使用。
 * <b>如实记录 ≠ 记录了就能用。</b>
 */
class VulkanEvidenceDisciplineTest {

    private static final Path DEV_LOOP = Path.of("docs/01-DEV-LOOP.md");
    private static final Path INDEX = Path.of("docs/00-INDEX.md");

    private static String readOrSkip(Path path) {
        Assumptions.assumeTrue(Files.exists(path), "工程文件缺失: " + path);
        try {
            return Files.readString(path);
        } catch (java.io.IOException e) {
            throw new AssertionError("读取失败: " + path, e);
        }
    }

    @Test
    @DisplayName("🔖🔖 01-DEV-LOOP 必须保留 Vulkan 取证入口（删掉就等于默许退回 OpenGL）")
    void devLoopKeepsVulkanEntryPoint() {
        String doc = readOrSkip(DEV_LOOP);
        assertTrue(doc.contains("tools/vulkan-local/run-client.sh"),
                "01-DEV-LOOP 必须写明取证走 tools/vulkan-local/run-client.sh —— "
                        + "h36 之前裸跑 ./gradlew runClient 会**静默退回 OpenGL**（Minecraft 不崩、"
                        + "不退出、继续跑满取证帧数），h33/h34/h35 三轮证据都栽在这上面");
        assertTrue(doc.contains("preflight.sh"),
                "必须同时写明 preflight.sh 自检入口");
        assertTrue(doc.contains("lavapipe"),
                "必须写明设备是 lavapipe（CPU 软件 Vulkan）—— 否则下一个人会以为那是独显，"
                        + "进而拿它的帧率当性能证据");
    }

    @Test
    @DisplayName("🔖🔖 执行顺序里不得再把裸 runClient 当作取证步骤")
    void bareRunClientIsNotTheRunStep() {
        String doc = readOrSkip(DEV_LOOP);
        // 取 §2「每次改动的执行顺序」那一段：第 6 步必须是 run-client.sh
        int s = doc.indexOf("## 2. 每次改动的执行顺序");
        assertTrue(s > 0, "找不到 §2 执行顺序");
        int e = doc.indexOf("## 3.", s);
        String section = doc.substring(s, e > s ? e : doc.length());
        assertTrue(section.contains("run-client.sh"),
                "§2 执行顺序的第 6 步必须是 run-client.sh，不是裸 ./gradlew runClient");
        assertFalse(section.contains("⑥ 跑真实产物：./gradlew runClient"),
                "§2 执行顺序里仍写着裸 ./gradlew runClient —— 这条命令在本机会静默退回 OpenGL");
    }

    @Test
    @DisplayName("🔖🔖 00-INDEX（唯一真源）必须让下一个 AI 看见这条纪律")
    void indexCarriesTheDiscipline() {
        String doc = readOrSkip(INDEX);
        assertTrue(doc.contains("Vulkan") && doc.contains("run-client.sh"),
                "00-INDEX 是跨会话的状态真源，必须写明「取证走 run-client.sh 且必须在 Vulkan 上」"
                        + " —— 否则新会话只会读到历史证据而不知道那些是 OpenGL 产物");
    }

    @Test
    @DisplayName("🔖 纪律的另一半：不做性能结论（软件 Vulkan 的帧率无意义）")
    void noPerformanceClaimFromSoftwareVulkan() {
        String doc = readOrSkip(DEV_LOOP);
        assertTrue(doc.contains("支柱③") || doc.contains("B1"),
                "必须写明 lavapipe 下帧率不代表任何真实硬件 ⇒ 支柱③ 性能线不成立");
    }
}
