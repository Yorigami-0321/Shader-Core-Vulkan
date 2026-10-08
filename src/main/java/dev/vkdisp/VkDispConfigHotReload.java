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
import dev.vkdisp.pack.PackCompositeSource;
import dev.vkdisp.pack.PackCompileCache;
import dev.vkdisp.pack.PackPrecompileScheduler;
import dev.vkdisp.screen.PackOptionsDrive;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.client.event.ClientResourceLoadFinishedEvent;
import org.jspecify.annotations.Nullable;

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
     * @param capabilityGate  核心项（包选项相关，变了要重载）
     * @param chainEnableGating 核心项（GAP-024：只在链装配时读一次，变了必须重载才换臂）
     * @param optionOverrides 核心项（包选项相关，变了要重载）
     * @param packWater       核心项（GAP-027：{@code mrt.packWater} 只在<b>生成期与注册期</b>被读一次
     *                        ⇒ 不重载就永远停在旧的一臂：水要么继续被接、要么继续不被接，
     *                        而配置值本身看起来「改了」（QD-08 那一族，已发生四次）
     */
    private record Snapshot(boolean enabled, boolean debugLog, String packProfile,
            String shaderPack, String packOptionsScreen, boolean capabilityGate,
            boolean chainEnableGating, String optionOverrides, boolean packWater) {}

    /** 最近一次快照；null = 还没见 Loading（防御位，实际由首载 Loading 填充）。 */
    private static volatile Snapshot last;

    /**
     * P4.5 切包预编译调度器：<b>先在后台把包编好，再切资源</b>。
     *
     * <p>为什么需要（2026-10-02 用户实测「客户端进入未响应」）：切包的资源重载最终会触发
     * 虚拟包 {@code openResources} → {@link PackCompositeSource#generate}，实测切到 BSL
     * 单次 <b>3.97 秒</b>（切 {@code none} 走兜底仅 1 ms）。这段在渲染线程上跑 =
     * 窗口无响应。改为：配置变化时先异步预编译（产物进 {@link PackCompileCache}），
     * 完成后再执行 {@code reloadResourcePacks()} —— 那时 {@code openResources} 命中缓存，
     * 毫秒级返回。
     *
     * <p><b>失败语义</b>：预编译失败不阻断切换 —— 真加载时 {@code openResources} 走同步路径
     * 再编一次并给出完整诊断（T11：绝不因为加速器坏了就假装加载成功）。
     */
    private static volatile PackPrecompileScheduler scheduler;

    /**
     * 待测的「用户可见等待」起点（纳秒），0 表示当前没有在等的切包。
     *
     * <p>🔴 为什么要这个字段：既有的 {@code pack precompile done in {} ms} 在
     * {@code reloadResourcePacks()} **之前**就停表，而资源重载本身还要占用户 2.1–4.2 秒
     * （见 {@code evidence/b4-pack-switch.md}）⇒ 那个数**不是**用户等的时间，
     * 却是 B4 唯一被记录的数字 ⇒ 长期会把 B4 看成达标。必须把两段分开记。
     */
    private static final java.util.concurrent.atomic.AtomicLong PENDING_RELOAD_START_NANOS =
            new java.util.concurrent.atomic.AtomicLong();

    private VkDispConfigHotReload() {
    }

    /** 懒建调度器：后台线程 = 守护单线程，渲染线程回调 = {@code minecraft.execute}。 */
    private static PackPrecompileScheduler scheduler() {
        PackPrecompileScheduler local = scheduler;
        if (local != null) {
            return local;
        }
        synchronized (VkDispConfigHotReload.class) {
            if (scheduler == null) {
                java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "vkdisp-pack-precompile");
                            // 守护线程：绝不阻止游戏退出（用户可能正在等 JVM 关闭）
                            thread.setDaemon(true);
                            return thread;
                        });
                scheduler = new PackPrecompileScheduler(
                        pool,
                        // 渲染线程回调：用 minecraft.execute；拿不到实例时退回「就地执行」
                        //（只会在极端关闭期发生，此时立即执行比丢事件更安全）
                        runnable -> {
                            Minecraft mc = Minecraft.getInstance();
                            if (mc != null) {
                                mc.execute(runnable);
                            } else {
                                runnable.run();
                            }
                        },
                        target -> {
                            // 预编译本体：走虚拟包同一条generate 路径（不含 MC 依赖，纯冷路径）
                            VkDispVirtualPack.precompile(target);
                            return true;
                        });
            }
            return scheduler;
        }
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

            Minecraft minecraft = Minecraft.getInstance();
            if (coreChanged(previous, current)) {
                // T11：切换必须显式留痕 —— 这一行是 §6 四截图各阶段的日志锚点（p417 逐字保留）。
                VkDisp.LOGGER.info(
                        "vkdisp: config hot-reload: file={} type={} enabled={} debugLog={}"
                                + " packProfile='{}' shaderPack='{}'"
                                + " capabilityGate={} chainEnableGating={} optionOverrides='{}'"
                                + " packWater={} -> resource reload",
                        config.getFileName(), config.getType(),
                        current.enabled(), current.debugLog(),
                        current.packProfile(), current.shaderPack(),
                        current.capabilityGate(), current.chainEnableGating(),
                        current.optionOverrides(), current.packWater());
                if (minecraft != null) {
                    //观察者线程（nightconfig FileWatcher）不能直接开资源重载 → 挪渲染线程。
                    // P4.5：先异步预编译，编译完成再重载（切 BSL 实测 3.97s 同步阻塞 → 未响应）。
                    scheduleReloadWithPrecompile(current);
                }
            }

            boolean driveChanged = previous != null
                    && !Objects.equals(previous.packOptionsScreen(), current.packOptionsScreen());
            if (driveChanged) {
                runScreenDrive(current, minecraft);
            }
        } catch (Throwable t) {
            // 失败必须打 ERROR 原文（T11），且不许让观察者线程带着异常死掉。
            VkDisp.LOGGER.error("vkdisp: config hot-reload handling failed", t);
        }
    }

    /**
     * 边沿判定：本次热加载是否要触发<b>资源重载</b>。
     *
     * <p>🔖🔖 包选项相关的两项（能力门控 / 单变量覆盖）<b>也必须在这里</b>：
     * 它们在 {@code VkDispVirtualPack#openResources} 生成<b>包源</b>时被读一次，
     * 而包源生成只在资源重载时发生 ⇒ 不重载 = 配置改了但画面不变。
     * 🔖 本轮实测踩到：改 {@code pack.optionOverrides} 后<b>日志毫无反应</b>，
     * 而若不查包源日志，会误读成「开关又是死的」（QD-02 / h33 那一族）。
     * ⚠️ 它们<b>不在</b>「核心四项」的历史口径里，但<b>行为上</b>与那四项同类（都改变画面），
     * 故归入 core 判定而不是 drive 判定。
     *
     * <p>🔖 单独成方法而不是留在事件体里：判据「哪几项算核心」是这一段里
     * <b>最该被一眼看全</b>的东西，混在事件体的多分支里必然被后来人漏看。
     */
    private static boolean coreChanged(@Nullable Snapshot previous, Snapshot current) {
        if (previous == null) {
            return true;
        }
        return previous.enabled() != current.enabled()
                || previous.debugLog() != current.debugLog()
                || !Objects.equals(previous.packProfile(), current.packProfile())
                || !Objects.equals(previous.shaderPack(), current.shaderPack())
                || previous.capabilityGate() != current.capabilityGate()
                || previous.chainEnableGating() != current.chainEnableGating()
                || !Objects.equals(previous.optionOverrides(), current.optionOverrides())
                // 🔴 GAP-027：mrt.packWater 与上面那两项同类 —— 只在生成期/注册期被读一次，
                //   不重载 = 改了配置但水那一臂纹丝不动（QD-08 那一族）。
                || previous.packWater() != current.packWater();
    }

    /** 屏幕驱动分支（{@code packOptionsScreen} 边沿）：解析 → 自报 → 执行（挪渲染线程）。 */
    private static void runScreenDrive(Snapshot current, @Nullable Minecraft minecraft) {
        ScreenDriveCommand command = ScreenDriveCommand.parse(current.packOptionsScreen());
        VkDisp.LOGGER.info("vkdisp: pack options screen drive: raw='{}' -> {}",
                current.packOptionsScreen(), describe(command));
        if (command instanceof ScreenDriveCommand.Malformed malformed) {
            VkDisp.LOGGER.warn("vkdisp: pack options screen drive rejected: {}（{}）",
                    malformed.raw(), malformed.reason());
        } else if (minecraft != null) {
            minecraft.execute(() -> PackOptionsDrive.run(minecraft, command));
        }
    }

    /**
     * P4.5：核心配置变化 → <b>先预编译再切资源</b>。
     *
     * <p>分流依据是「本次变化是否需要动包」：
     * <ul>
     *   <li><b>不换包</b>（只有 enabled / debugLog 变，或 profile 变但没换包）——
     *       预编译仍可能有价值（profile 影响源），所以<b>一律预编译</b>，
     *       但 {@code shaderPack} 未变时包已在缓存里，通常毫秒级命中。</li>
     *   <li><b>换包</b> —— 必须等编译完成，否则就是原来那次 3.97s 的渲染线程阻塞。</li>
     * </ul>
     *
     * <p>预编译失败不阻断：直接照常重载，让 {@code openResources} 走同步路径重编并打诊断
     * （T11：加速器故障不能让功能不可用，也不能静默）。
     */
    private static void scheduleReloadWithPrecompile(Snapshot current) {
        Minecraft minecraft = Minecraft.getInstance();
        PackPrecompileScheduler active = scheduler();
        PackPrecompileScheduler.Target target = new PackPrecompileScheduler.Target(
                current.shaderPack(), current.packProfile(), java.util.Map.of());

        long started = System.nanoTime();
        // 记下「用户视角的等待起点」：从这一刻起，用户看到的是旧画面，直到资源重载完成。
        PENDING_RELOAD_START_NANOS.set(started);
        active.request(target);
        // T11：预编译的起止都留痕，可 grep 复核「慢的是预编译还是同步兜底」
        VkDisp.LOGGER.info(
                "vkdisp: pack precompile scheduled: selection='{}' profile='{}' (compile moved off render thread)",
                current.shaderPack(), current.packProfile());

        active.switchWhenReady(() -> {
            long millis = (System.nanoTime() - started) / 1_000_000L;
            // ⚠️ 措辞已改：这是**预编译**耗时，**不含**紧接着的资源重载。
            // 原措辞「done in {} ms -> resource reload」会被读成「切包总共这么久」，是错的。
            VkDisp.LOGGER.info(
                    "vkdisp: pack precompile done in {} ms (precompile only, resource reload follows)"
                            + " (cache entries={}, hits={}, misses={}, evictions={})",
                    millis, PackCompileCache.size(),
                    PackCompileCache.hitCount(), PackCompileCache.missCount(),
                    // T11：淘汰也留痕。原来淘汰是「超限清空整表」且完全静默，
                    // 实测（evidence/b4-pack-switch.md）正是它让每次切包都整包重编。
                    PackCompileCache.evictionCount());
            minecraft.reloadResourcePacks();
        });
    }

    /**
     * 资源重载**完成**之后记账：补上 B4 真正该盯的那个数 —— 用户从切包到看见新画面的墙钟。
     *
     * <p>🔴 为什么必须有这一条：预编译只占其中一段，资源重载还要再花 2.1–4.2 秒。
     * 只记前者 ⇒ B4 看起来达标，端到端实际是预算的 2.6–3.6 倍（见
     * {@code evidence/b4-pack-switch.md}）。§5 把 B4 定义为「切包等待墙钟」，
     * 那就得记墙钟，不是记其中一段。
     *
     * <p>用 0 做哨兵：初次进游戏也会触发本事件，但那时没有在等的切包 ⇒ 不记账。
     */
    @SubscribeEvent
    static void onResourceReloadFinished(ClientResourceLoadFinishedEvent event) {
        long started = PENDING_RELOAD_START_NANOS.getAndSet(0L);
        if (started == 0L) {
            return;
        }
        long totalMillis = (System.nanoTime() - started) / 1_000_000L;
        VkDisp.LOGGER.info(
                "vkdisp: B4 pack switch end-to-end: {} ms (scheduled -> resource reload finished;"
                        + " precompile is a prefix of this, see the 'precompile done' line)",
                totalMillis);
    }

    /** 当前配置快照。 */
    private static Snapshot snapshot() {
        return new Snapshot(
                VkDispConfig.ENABLED.get(),
                VkDispConfig.DEBUG_LOG.get(),
                VkDispConfig.PACK_PROFILE.get(),
                VkDispConfig.SHADER_PACK.get(),
                VkDispConfig.PACK_OPTIONS_SCREEN.get(),
                VkDispConfig.CAPABILITY_GATE.get(),
                VkDispConfig.CHAIN_ENABLE_GATING.get(),
                VkDispConfig.OPTION_OVERRIDES.get(),
                // GAP-027：水那一臂的开关（只在生成期/注册期被读一次 ⇒ 必须进边沿判定）。
                VkDispConfig.MRT_PACK_WATER_SHADER.get());
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
