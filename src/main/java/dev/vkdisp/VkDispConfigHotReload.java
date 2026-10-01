package dev.vkdisp;
/**
 * 【参考调研】P4.2 切包触发（配置热加载 → 资源重载）/ FML ModConfigEvent.Reloading
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = FML loader-12.0.0 的 net.neoforged.fml.event.config.ModConfigEvent$Reloading
 *    （官方事件定义类 —— javap 只核实构造签名 / getConfig() / IModBusEvent 标记与触发链：
 *    nightconfig FileWatcher.addWatch → ConfigWatcher.run → ConfigTracker.loadConfig(…,
 *    Reloading::new) → setConfig 发事件；不复制其实现）。→ 能否并入本项目（MIT）：
 *    可以 —— 只订阅事件公开 API 与调用原版 Minecraft 公开方法
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码。
 * 1. 官方/主实现：ModConfigEvent.Reloading —— 「配置文件变更后重新加载完成」语义
 *    （触发点 bytecode 已核实：外部改 TOML → FileWatcher 500ms 去抖 → loadConfig 重读 → 发事件；
 *    GUI 保存路径 setConfig 同样发本事件）。事件在 mod bus 上（IModBusEvent），
 *    @EventBusSubscriber 注解无 bus 属性（javap：只有 modid/value），按事件类型自动路由。
 * 2. 备选：① 自写 mtime 轮询器 —— 否决（FML 已内置 FileWatcher，自写是重复真源，X17 同理）；
 *    ② 哨兵文件 —— 否决（绕开官方热加载链，多一份要维护的状态）；③ 手动 F3+T —— 否决
 *    （本环境无输入注入，且生产用户也应免手动）。三者均不构成自行补充特性，无需 GAP 登记（T12）。
 * 3. 我们的差异点：
 *    ① 事件只认本模组的配置（modid 过滤）—— 别的模组配置热加载不触发我方资源重载；
 *    ② 任意本模组配置项变更都触发一次 {@code Minecraft.reloadResourcePacks()} ——
 *       使 enabled / packProfile / shaderPack 的外部改值<b>立即生效</b>（P4.2 切包回归
 *       08-TESTING §6 的切换驱动：外部改 TOML = 切包，无需输入注入）；
 *    ③ 回调跑在 nightconfig Watcher 线程 → 经 {@code minecraft.execute} 挪回渲染线程再重载
 *       （资源重载不允许在观察者线程直接开跑）；全程 catch Throwable 打 ERROR 原文（T11）。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，只观察事件签名（07-CONSTRAINTS L5-L8 / X19-X21）。
 * 5. 性能基线：❄️ 冷路径（配置文件变更才触发，每次一次资源重载 = F3+T 同量级），不做优化（T14）。
 */

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;

/**
 * P4.2 切包回归的切换驱动：本模组配置文件被外部改写（或 GUI 保存）后，
 * FML 热加载链发 {@link ModConfigEvent.Reloading} —— 本类接住它并触发一次
 * {@link Minecraft#reloadResourcePacks()}，使新配置立即走完整资源重载链：
 *
 * <ol>
 *   <li>虚拟资源包 {@code openResources} 重新生成 composite/deferred/final 三源
 *       （按新 {@code shaderPack} / {@code packProfile} / {@code enabled} 选择）；</li>
 *   <li>ShaderManager 重编译 9 条自定义管线（旧包管线随资源重载释放 —— §6「无残留」的机制保证）；</li>
 *   <li>{@code ClientResourceLoadFinishedEvent} 再发 → 扫包日志与链路埋点重新可见（证据链）。</li>
 * </ol>
 *
 * <p>不订阅 {@link ModConfigEvent.Loading}：那是启动期配置首载，此刻资源加载尚未开始，
 * 再触发重载是空转；不订阅 {@link ModConfigEvent.Unloading}：没有后续动作语义。
 */
@EventBusSubscriber(modid = VkDisp.MOD_ID, value = Dist.CLIENT)
public final class VkDispConfigHotReload {

    private VkDispConfigHotReload() {
    }

    /** 配置热加载完成 → 挪回渲染线程触发资源重载（P4.2 切包 / 外部改值自动生效）。 */
    @SubscribeEvent
    static void onConfigReloading(ModConfigEvent.Reloading event) {
        ModConfig config = event.getConfig();
        if (!VkDisp.MOD_ID.equals(config.getModId())) {
            return; // 只管自己的配置（别的模组热加载不关我方资源的事）
        }
        try {
            // 值此刻已是热加载后的新值（loadConfig 先重读落盘 → setConfig → 发事件）。
            // T11：切换必须显式留痕 —— 这一行是 §6 四截图各阶段的日志锚点。
            VkDisp.LOGGER.info(
                    "vkdisp: config hot-reload: file={} type={} enabled={} debugLog={}"
                            + " packProfile='{}' shaderPack='{}' -> resource reload",
                    config.getFileName(), config.getType(),
                    VkDispConfig.ENABLED.get(), VkDispConfig.DEBUG_LOG.get(),
                    VkDispConfig.PACK_PROFILE.get(), VkDispConfig.SHADER_PACK.get());
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null) {
                return; // 极早期：配置热加载先于客户端就绪，首载资源加载自会读到新值
            }
            // 观察者线程（nightconfig FileWatcher）不能直接开资源重载 → 挪渲染线程。
            minecraft.execute(() -> minecraft.reloadResourcePacks());
        } catch (Throwable t) {
            // 失败必须打 ERROR 原文（T11），且不许让观察者线程带着异常死掉。
            VkDisp.LOGGER.error("vkdisp: config hot-reload handling failed", t);
        }
    }
}
