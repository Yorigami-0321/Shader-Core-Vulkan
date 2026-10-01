package dev.vkdisp;
/**
 * 【参考调研】P4.2 切包 + P4.3 选项 GUI 驱动（配置热加载 → 分流：资源重载 / 屏幕动作）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = FML loader-12.0.0 的 net.neoforged.fml.event.config.ModConfigEvent$Reloading
 *    与 $Loading（官方事件定义类 —— javap 只核实构造签名 / getConfig() / IModBusEvent 标记与触发链：
 *    nightconfig FileWatcher.addWatch → ConfigWatcher.run → ConfigTracker.loadConfig(…,
 *    Loading::new / Reloading::new) → setConfig 发事件；不复制其实现）。→ 能否并入本项目（MIT）：
 *    可以 —— 只订阅事件公开 API 与调用原版 Minecraft 公开方法
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码。
 * 1. 官方/主实现：ModConfigEvent.Reloading —— 「配置文件变更后重新加载完成」语义
 *    （触发点 bytecode 已核实：外部改 TOML → FileWatcher 500ms 去抖 → loadConfig 重读 → 发事件；
 *    GUI 保存路径 setConfig 同样发本事件）。Loading = 配置首载（启动期也发）。
 *    事件在 mod bus 上（IModBusEvent），@EventBusSubscriber 注解无 bus 属性（javap：只有
 *    modid/value），按事件类型自动路由。
 * 2. 备选：① 自写 mtime 轮询器 —— 否决（FML 已内置 FileWatcher，自写是重复真源，X17 同理）；
 *    ② 哨兵文件 —— 否决（绕开官方热加载链，多一份要维护的状态）；③ 手动 F3+T —— 否决
 *    （本环境无输入注入，且生产用户也应免手动）。三者均不构成自行补充特性，无需 GAP 登记（T12）；
 *    ④ 所有条目变化一律重载 —— P4.2 的旧行为，P4.3 起否决为「核心四项才重载」（选项驱动值
 *    变化若也重载，"打开屏幕"会顺带触发一次无谓的全量资源重载，且 done 动作自己会重载 → 双重）。
 * 3. 我们的差异点：
 *    ① 事件只认本模组的配置（modid 过滤）—— 别的模组配置热加载不触发我方动作；
 *    ② **边沿分流**：Loading 只记快照（启动首载，资源加载尚未开始，重载是空转）；
 *       Reloading 对比快照 —— 核心四项（enabled/debugLog/packProfile/shaderPack）任一变化 →
 *       P4.2 原样的 T11 INFO + {@code reloadResourcePacks()}（p417 证据行逐字保留）；
 *       {@code packOptionsScreen} 变化 → 独立 drive INFO + 屏幕动作（不重载，done 除外）；
 *       两者互不牵连（同一次保存同时改两边 = 两个动作都执行）；快照前移后重复保存同值不重发；
 *    ③ 回调跑在 nightconfig Watcher 线程 → 经 {@code minecraft.execute} 挪回渲染线程再执行
 *       （重载与开屏都不允许在观察者线程直接开跑）；全程 catch Throwable 打 ERROR 原文（T11）。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，只观察事件签名（07-CONSTRAINTS L5-L8 / X19-X21）。
 * 5. 性能基线：❄️ 冷路径（配置文件变更才触发），不做优化（T14）。
 */

import java.util.Objects;

import dev.vkdisp.config.ScreenDriveCommand;
import dev.vkdisp.screen.PackOptionsDrive;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;

/**
 * 配置热加载 → 分流执行（P4.2 切包重载 / P4.3 选项屏幕驱动）。
 *
 * <p><b>资源重载路径</b>（核心四项任一变化）：触发一次 {@link Minecraft#reloadResourcePacks()}，
 * 使新配置立即走完整资源重载链：
 *
 * <ol>
 *   <li>虚拟资源包 {@code openResources} 重新生成 composite/deferred/final 三源
 *       （按新 {@code shaderPack} / {@code packProfile} / {@code enabled} 选择）；</li>
 *   <li>ShaderManager 重编译 9 条自定义管线（旧包管线随资源重载释放 —— §6「无残留」的机制保证）；</li>
 *   <li>{@code ClientResourceLoadFinishedEvent} 再发 → 扫包日志与链路埋点重新可见（证据链）。</li>
 * </ol>
 *
 * <p><b>屏幕驱动路径</b>（{@code packOptionsScreen} 边沿）：解析
 * {@link ScreenDriveCommand} 并在渲染线程执行（{@link PackOptionsDrive}）——
 * 打开选项屏幕 / 改屏幕里的选项 / 翻页 / 完成（完成自己触发重载，把改动送进着色器）。
 *
 * <p>不订阅 {@link ModConfigEvent.Unloading}：没有后续动作语义。
 */
@EventBusSubscriber(modid = VkDisp.MOD_ID, value = Dist.CLIENT)
public final class VkDispConfigHotReload {

    /**
     * 上一次见过的配置快照（边沿判定的参照）。
     *
     * @param enabled         核心项（变了要重载）
     * @param debugLog        核心项（变了要重载）
     * @param packProfile     核心项（变了要重载）
     * @param shaderPack      核心项（变了要重载）
     * @param packOptionsScreen 驱动项（变了只执行屏幕动作，不重载）
     */
    private record Snapshot(boolean enabled, boolean debugLog, String packProfile,
            String shaderPack, String packOptionsScreen) {}

    /** 最近一次快照；null = 还没见 Loading（防御位，实际由首载 Loading 填充）。 */
    private static volatile Snapshot last;

    private VkDispConfigHotReload() {
    }

    /** 配置首载（含启动）→ 只记快照，不触发任何动作（启动期重载是空转，P4.2 既定语义）。 */
    @SubscribeEvent
    static void onConfigLoading(ModConfigEvent.Loading event) {
        if (!VkDisp.MOD_ID.equals(event.getConfig().getModId())) {
            return;
        }
        last = snapshot();
    }

    /**
     * 配置热加载完成 → 边沿分流：核心四项变化 → P4.2 原样的 INFO + 资源重载；
     * {@code packOptionsScreen} 变化 → drive INFO + 屏幕动作（不重载）。
     */
    @SubscribeEvent
    static void onConfigReloading(ModConfigEvent.Reloading event) {
        ModConfig config = event.getConfig();
        if (!VkDisp.MOD_ID.equals(config.getModId())) {
            return; // 只管自己的配置（别的模组热加载不关我方资源的事）
        }
        try {
            // 值此刻已是热加载后的新值（loadConfig 先重读落盘 → setConfig → 发事件）。
            Snapshot current = snapshot();
            Snapshot previous = last;
            last = current; // 快照先前移：本事件的两个分支共用这一次读数
            if (previous == null) {
                // 没见过 Loading（理论不可达；防御）：按核心变化处理一次，不执行屏幕动作
                // （启动边上自动开屏是惊吓，宁可少做）。
                VkDisp.LOGGER.warn("vkdisp: config hot-reload: no Loading snapshot yet, treating as core change");
            }

            boolean coreChanged = previous == null
                    || previous.enabled() != current.enabled()
                    || previous.debugLog() != current.debugLog()
                    || !Objects.equals(previous.packProfile(), current.packProfile())
                    || !Objects.equals(previous.shaderPack(), current.shaderPack());
            boolean driveChanged = previous != null
                    && !Objects.equals(previous.packOptionsScreen(), current.packOptionsScreen());

            Minecraft minecraft = Minecraft.getInstance();
            if (coreChanged) {
                // T11：切换必须显式留痕 —— 这一行是 §6 四截图各阶段的日志锚点（p417 逐字保留）。
                VkDisp.LOGGER.info(
                        "vkdisp: config hot-reload: file={} type={} enabled={} debugLog={}"
                                + " packProfile='{}' shaderPack='{}' -> resource reload",
                        config.getFileName(), config.getType(),
                        current.enabled(), current.debugLog(),
                        current.packProfile(), current.shaderPack());
                if (minecraft != null) {
                    // 观察者线程（nightconfig FileWatcher）不能直接开资源重载 → 挪渲染线程。
                    minecraft.execute(() -> minecraft.reloadResourcePacks());
                }
            }

            if (driveChanged) {
                ScreenDriveCommand command = ScreenDriveCommand.parse(current.packOptionsScreen());
                VkDisp.LOGGER.info(
                        "vkdisp: pack options screen drive: raw='{}' -> {}",
                        current.packOptionsScreen(), describe(command));
                if (command instanceof ScreenDriveCommand.Malformed malformed) {
                    VkDisp.LOGGER.warn(
                            "vkdisp: pack options screen drive rejected: {}（{}）",
                            malformed.raw(), malformed.reason());
                } else if (minecraft != null) {
                    minecraft.execute(() -> PackOptionsDrive.run(minecraft, command));
                }
            }
        } catch (Throwable t) {
            // 失败必须打 ERROR 原文（T11），且不许让观察者线程带着异常死掉。
            VkDisp.LOGGER.error("vkdisp: config hot-reload handling failed", t);
        }
    }

    /** 当前配置快照。 */
    private static Snapshot snapshot() {
        return new Snapshot(
                VkDispConfig.ENABLED.get(),
                VkDispConfig.DEBUG_LOG.get(),
                VkDispConfig.PACK_PROFILE.get(),
                VkDispConfig.SHADER_PACK.get(),
                VkDispConfig.PACK_OPTIONS_SCREEN.get());
    }

    /** drive INFO 的动作摘要（与解析结果一一对应，坏输入也可见原文）。 */
    private static String describe(ScreenDriveCommand command) {
        if (command instanceof ScreenDriveCommand.None) {
            return "none (neutral)";
        }
        if (command instanceof ScreenDriveCommand.Open) {
            return "open";
        }
        if (command instanceof ScreenDriveCommand.Done) {
            return "done";
        }
        if (command instanceof ScreenDriveCommand.Set set) {
            return "set " + set.name() + "=" + set.value();
        }
        if (command instanceof ScreenDriveCommand.Page page) {
            return "page " + page.page1();
        }
        if (command instanceof ScreenDriveCommand.Malformed malformed) {
            return "malformed (" + malformed.reason() + ")";
        }
        return String.valueOf(command);
    }
}
