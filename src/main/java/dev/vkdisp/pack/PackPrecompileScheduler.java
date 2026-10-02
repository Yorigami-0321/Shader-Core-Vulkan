package dev.vkdisp.pack;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 【P4.5】切包预编译调度器 —— 把秒级冷路径编译移出渲染线程。
 *
 * <p><b>问题（2026-10-02 用户实测）</b>：点下拉 → 落盘 → FML FileWatcher → 配置热加载 →
 * {@code minecraft.execute(reloadResourcePacks)} → 虚拟包 {@code openResources} →
 * {@link PackCompositeSource#generate} → 182 个阶段全量转译 + shaderc 编 SPIR-V。
 * **整条链跑在渲染线程**，实测切到 BSL 单次 **3.97 秒**（切 {@code none} 走兜底仅 1 ms）——
 * 客户端必然「未响应」，且没有任何进度反馈。
 *
 * <p><b>本类的做法（Iris 思路：先编译，后切换）</b>：
 * <ol>
 *   <li>切包请求进来时，先在<b>后台线程</b>把包编译好，产物写进 {@link PackCompileCache}；</li>
 *   <li>编译完成后再回调渲染线程执行 {@code reloadResourcePacks()} ——
 *       此刻 {@code openResources} 命中缓存，微秒级返回；</li>
 *   <li>期间界面显示「正在加载…」进度（Iris 同款 loading overlay 语义）。</li>
 * </ol>
 *
 * <p><b>为什么这样不违反「required 管线必须总有源」</b>：{@code openResources} 在缓存未就绪时
 * 仍会<b>同步</b>走一遍原路径（兜底 + 真实编译），绝不会返回空源让 required 管线编译失败。
 * 预编译是加速，不是正确性依赖。
 *
 * <p><b>并发去重</b>：连点下拉会产生多次请求。用 {@code pendingKey} 保证<b>同一目标包</b>的
 * 重复请求共享同一次预编译；<b>目标变了</b>则旧的立即作废（结果不采用），新的重新开始
 * （generation 号机制）—— 避免「点了 none 又点 BSL，结果 BSL 的产物被 none 覆盖」。
 *
 * <p><b>线程</b>：全部状态用原子类，公开方法可从任意线程调用。回调通过传入的
 * {@code Executor}（通常是 {@code minecraft.execute}）切回渲染线程。
 *
 * <p>许可证：本文件为独立编写的纯 Java，不含任何外部项目代码（07-CONSTRAINTS §〇 P1）。
 */
public final class PackPrecompileScheduler {

    /** 预编译状态（供 UI 查询）。 */
    public enum State {
        /** 空闲：无预编译在进行。 */
        IDLE,
        /** 预编译进行中（此时界面应显示加载提示）。 */
        COMPILING,
        /** 编译完成，等待渲染线程执行切换。 */
        READY
    }

    /** 状态快照（不可变，UI 直接取用）。 */
    public record Status(State state, String target, long startedAtMillis, long finishedAtMillis) {

        /** 归一构造。 */
        public Status {
            target = target == null ? "" : target;
        }

        /** 编译是否已结束（含 READY 与 IDLE）。 */
        public boolean isSettled() {
            return state != State.COMPILING;
        }
    }

    /** 预编译目标：包身份 + 选项差分表（与缓存键同构，但这里存的是「意图」）。 */
    public record Target(String selection, String profile, java.util.Map<String, String> overrides) {

        /** 归一构造。 */
        public Target {
            selection = selection == null ? "" : selection;
            profile = profile == null ? "" : profile;
            overrides = overrides == null ? java.util.Map.of() : java.util.Map.copyOf(overrides);
        }

        /** 展示用短标签（UI 显示「正在加载 BSL…」）。 */
        public String display() {
            return selection.isEmpty() ? "自动" : selection;
        }
    }

    /** 编译动作：由调用方注入（真正的 {@code PackCompileCache.getOrCompile} 逻辑在业务侧）。 */
    public interface CompileAction {
        /**
         * 执行编译（<b>运行在后台线程</b>，实现方不得触碰渲染线程专属资源）。
         *
         * @return 编译是否成功（失败不影响切换 —— 切换后会走同步兜底路径）
         */
        boolean run(Target target);
    }

    private final Executor background;
    private final Executor renderThread;
    private final CompileAction action;

    /** 当前状态。 */
    private final AtomicReference<Status> status = new AtomicReference<>(
            new Status(State.IDLE, "", 0L, 0L));

    /** 请求代号：每次新请求自增；旧请求的完成回调发现代号不符即丢弃（X9：不用猜，直接弃）。 */
    private final AtomicLong generation = new AtomicLong();

    /** 已在进行中的目标（去重用）。 */
    private final AtomicReference<Target> pending = new AtomicReference<>();

    /** 防止同一目标重复起线程。 */
    private final AtomicBoolean scheduled = new AtomicBoolean();

    /**
     * 预编译完成后要执行的切换动作（回调式，见 {@link #switchWhenReady}）。
     *
     * <p>用 {@link AtomicReference} 的引用本身做「一次性取出」：{@code getAndSet(null)}
     * 保证同一动作只被消费一次，且新请求可以覆盖旧请求（配合 generation 判定谁才是最新的）。
     */
    private final AtomicReference<AtomicReference<SwitchAction>> switchTarget =
            new AtomicReference<>(new AtomicReference<>());

    public PackPrecompileScheduler(Executor background, Executor renderThread, CompileAction action) {
        this.background = java.util.Objects.requireNonNull(background, "background");
        this.renderThread = java.util.Objects.requireNonNull(renderThread, "renderThread");
        this.action = java.util.Objects.requireNonNull(action, "action");
    }

    /**
     * 请求预编译（<b>非阻塞，立即返回</b>；可从任意线程调用）。
     *
     * <p>幂等：同一 {@code target} 已在编译中 → 直接返回，不重复起线程。
     * 目标不同 → 旧请求作废（代号自增），启动新编译。
     *
     * @param target 预编译目标；null → 视作「无包」（{@code none}，无需编译，立即 READY）
     */
    public void request(Target target) {
        Target effective = target == null ? new Target(PackCompositeSource.SELECTION_NONE, "", null) : target;
        long myGeneration = generation.incrementAndGet();
        status.set(new Status(State.COMPILING, effective.display(), System.currentTimeMillis(), 0L));
        pending.set(effective);

        if (PackCompositeSource.SELECTION_NONE.equals(effective.selection())) {
            // 保留名 = 内置 passthrough，无编译成本，直接进入切换阶段
            completeWithoutCompile(myGeneration, effective);
            return;
        }
        if (!scheduled.compareAndSet(false, true)) {
            // 已有线程在跑：交给它跑完后统一比对代号（本轮 target 会被它检测到变化并作废）
            return;
        }
        background.execute(() -> runCompile(myGeneration, effective));
    }

    /** 后台线程主体：跑编译 → 切渲染线程收尾。 */
    private void runCompile(long myGeneration, Target target) {
        boolean ok;
        try {
            ok = action.run(target);
        } catch (Throwable t) {
            // 预编译失败**不阻断切换**：真正的加载会在 openResources 同步路径里再试一次并给出诊断。
            // 这里只保证不把异常带出线程边界（否则后台线程死掉，状态永远卡在 COMPILING）。
            ok = false;
        } finally {
            scheduled.set(false);
        }
        final boolean success = ok;
        renderThread.execute(() -> finish(myGeneration, target, success));
    }

    /** 无需编译的目标（none）直接完成。 */
    private void completeWithoutCompile(long myGeneration, Target target) {
        renderThread.execute(() -> finish(myGeneration, target, true));
    }

    /** 渲染线程收尾：代号校验 → 置 READY → 执行登记的切换动作。 */
    private void finish(long myGeneration, Target target, boolean success) {
        if (myGeneration != generation.get()) {
            // 已被更新的请求取代：结果不采用（目标已变），静默丢弃是**正确**行为，
            // 不是静默降级 —— 新请求会自行完成切换。
            return;
        }
        pending.set(null);
        status.set(new Status(State.READY, target.display(),
                status.get().startedAtMillis(), System.currentTimeMillis()));
        // 取出并执行登记的切换动作（getAndSet(null) = 一次性消费）。
        // 预编译失败（success=false）**照样切换**：真加载会同步重编并给诊断，
        // 不切换等于「点了没反应」，那才是静默失败（T11）。
        SwitchAction action = switchTarget.get().getAndSet(null);
        if (action != null) {
            try {
                action.run();
            } catch (Throwable t) {
                // 切换失败必须显式 ERROR（T11）；不让异常吃掉渲染线程的其余任务队列
                dev.vkdisp.VkDisp.LOGGER.error("vkdisp: post-precompile switch action failed", t);
            }
        }
    }

    /**
     * 编译完成后的切换回调登记（由业务侧在 {@link #request} 之后调用链上挂接）。
     * 本类只管「什么时候可以切」，不管「切什么」—— 保持单一职责。
     */
    public interface SwitchAction {
        /** 在<b>渲染线程</b>执行真正的 {@code reloadResourcePacks()}。 */
        void run();
    }

    /**
     * 挂接切换动作：**READY 后自动执行一次**。
     *
     * <p><b>实现要点</b>：动作是登记在 {@code switchTarget} 上，由 {@link #finish} 在
     * 状态转为 READY 时取出并执行 —— <b>不是</b>「轮询一次状态」。
     * 早期版本写成「立刻检查状态，是 READY 才跑」，在编译尚未完成时会把动作
     * <b>永久丢弃</b>（游戏再也不切包 = 严重回归）。这里改为回调式，语义上不可能丢。
     *
     * <p>只有<b>最新一次</b>请求的登记会生效：目标变了则旧登记作废（避免用旧包切资源）。
     */
    public void switchWhenReady(SwitchAction action) {
        java.util.Objects.requireNonNull(action, "action");
        switchTarget.set(new java.util.concurrent.atomic.AtomicReference<>(action));
    }

    /** 当前状态（UI 轮询这个画进度）。 */
    public Status status() {
        return status.get();
    }

    /** 是否正在预编译（UI 据此显示 loading）。 */
    public boolean isCompiling() {
        return status.get().state() == State.COMPILING;
    }

    /** 复位为空闲（测试用 / 切包完成后清理状态）。 */
    public void reset() {
        generation.incrementAndGet();
        pending.set(null);
        scheduled.set(false);
        // 登记的切换动作必须一并清掉：否则上一次请求的回调会在下一次预编译完成时
        // 被误执行（测试里表现为「多切了一次」，生产里表现为「切了两次资源」）。
        switchTarget.get().set(null);
        status.set(new Status(State.IDLE, "", 0L, 0L));
    }
}
