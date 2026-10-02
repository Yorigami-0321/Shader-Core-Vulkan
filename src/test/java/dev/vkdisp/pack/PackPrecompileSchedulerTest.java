package dev.vkdisp.pack;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PackPrecompileScheduler} 契约：异步化切包的状态机。
 *
 * <p><b>本类存在的理由</b>：2026-10-02 用户实测切到 BSL 时客户端多次「未响应」，
 * 根因 = 3.97 秒全量编译跑在渲染线程。把编译移出主线程是修复，而「后台线程 + 回调」
 * 天然带来一个致命风险：<b>回调可能在编译完成前就被登记，若实现成「轮询一次状态」，
 * 回调会被永久丢弃 → 游戏再也不切包</b>。本测试类重点锁这条。
 */
class PackPrecompileSchedulerTest {

    /** 收集被切渲染线程执行的任务（测试里手动 drain，模拟 minecraft.execute）。 */
    private static final class ManualRenderExecutor implements Executor {
        private final java.util.List<Runnable> queue = new java.util.ArrayList<>();

        @Override
        public void execute(Runnable command) {
            queue.add(command);
        }

        /** 排空队列（模拟主线程 tick）。 */
        void drain() {
            List<Runnable> copy;
            synchronized (queue) {
                copy = new java.util.ArrayList<>(queue);
                queue.clear();
            }
            for (Runnable runnable : copy) {
                runnable.run();
            }
        }
    }

    private static final Executor DIRECT = Runnable::run;

    @Test
    void switchActionRunsAfterBackgroundCompileCompletes() {
        ManualRenderExecutor render = new ManualRenderExecutor();
        AtomicInteger compiled = new AtomicInteger();
        AtomicInteger switched = new AtomicInteger();
        var scheduler = new PackPrecompileScheduler(DIRECT, render, target -> {
            compiled.incrementAndGet();
            return true;
        });

        scheduler.switchWhenReady(switched::incrementAndGet);
        scheduler.request(new PackPrecompileScheduler.Target("BSL", "", Map.of()));

        // 后台（DIRECT）已跑完，但渲染线程还没 drain → 此刻不该切
        assertEquals(1, compiled.get(), "后台线程应已完成编译");
        assertEquals(0, switched.get(), "编译未落到渲染线程前不许切（会在渲染线程上重编）");

        render.drain();
        assertEquals(1, switched.get(), "渲染线程 drain 后必须切一次（回调不许丢）");
        assertEquals(PackPrecompileScheduler.State.READY, scheduler.status().state());
    }

    @Test
    void compileFailureStillSwitches() {
        // T11：预编译失败不阻断切换 —— 否则用户点了完全没反应 = 静默失败
        ManualRenderExecutor render = new ManualRenderExecutor();
        AtomicInteger switched = new AtomicInteger();
        var scheduler = new PackPrecompileScheduler(DIRECT, render, target -> {
            throw new IllegalStateException("模拟编译爆炸");
        });

        scheduler.switchWhenReady(switched::incrementAndGet);
        scheduler.request(new PackPrecompileScheduler.Target("BSL", "", Map.of()));
        render.drain();

        assertEquals(1, switched.get(), "预编译失败也必须切（真加载会同步重编并给诊断）");
    }

    @Test
    void staleTargetDoesNotTriggerSwitch() {
        // 连点下拉：先 none 再 BSL。只有最后一个目标（Bsl）能触发切换，
        // 否则会「用 none 的产物切 BSL 的资源」。
        ManualRenderExecutor render = new ManualRenderExecutor();
        AtomicReference<CountDownLatch> gate = new AtomicReference<>(new CountDownLatch(0));
        AtomicInteger switched = new AtomicInteger();

        var scheduler = new PackPrecompileScheduler(DIRECT, render, target -> {
            try {
                gate.get().await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return true;
        });

        scheduler.switchWhenReady(switched::incrementAndGet);
        scheduler.request(new PackPrecompileScheduler.Target("none", "", Map.of()));
        // 第一个目标还没跑完时来了新目标
        scheduler.request(new PackPrecompileScheduler.Target("BSL", "", Map.of()));
        render.drain();

        assertEquals(1, switched.get(), "只应切换一次（最后一个目标）");
        assertEquals("BSL", scheduler.status().target(), "状态应停在最新目标");
    }

    @Test
    void noneSelectionSkipsCompileAndSwitchesImmediately() {
        // 保留名 = 内置 passthrough，无编译成本（实测 1ms），不该起后台线程
        ManualRenderExecutor render = new ManualRenderExecutor();
        AtomicInteger compiled = new AtomicInteger();
        AtomicInteger switched = new AtomicInteger();
        var scheduler = new PackPrecompileScheduler(DIRECT, render, target -> {
            compiled.incrementAndGet();
            return true;
        });

        scheduler.switchWhenReady(switched::incrementAndGet);
        scheduler.request(new PackPrecompileScheduler.Target(
                PackCompositeSource.SELECTION_NONE, "", Map.of()));
        render.drain();

        assertEquals(0, compiled.get(), "none 不该触发编译");
        assertEquals(1, switched.get(), "none 也必须切（否则包切不掉）");
    }

    @Test
    void statusIsCompilingWhileInFlight() {
        ManualRenderExecutor render = new ManualRenderExecutor();
        CountDownLatch gate = new CountDownLatch(1);
        var scheduler = new PackPrecompileScheduler(DIRECT, render, target -> {
            try {
                gate.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return true;
        });

        scheduler.request(new PackPrecompileScheduler.Target("BSL", "", Map.of()));
        assertTrue(scheduler.isCompiling(), "编译进行中状态必须是 COMPILING（UI 据此显示加载）");
        assertEquals("BSL", scheduler.status().target());

        gate.countDown();
        render.drain();
        assertFalse(scheduler.isCompiling());
    }

    @Test
    void switchActionRunsAtMostOnce() {
        // 一次性消费：同一个动作不能被重复执行（否则切两次资源）
        ManualRenderExecutor render = new ManualRenderExecutor();
        AtomicInteger switched = new AtomicInteger();
        var scheduler = new PackPrecompileScheduler(DIRECT, render, target -> true);

        scheduler.switchWhenReady(switched::incrementAndGet);
        scheduler.request(new PackPrecompileScheduler.Target("BSL", "", Map.of()));
        render.drain();
        render.drain(); // 再 drain 一次（空队列，不该产生新动作）
        render.drain();

        assertEquals(1, switched.get(), "切换动作必须恰好执行一次");
    }

    @Test
    void resetClearsPendingSwitch() {
        // 回归锁：reset 后残留回调会在下一次预编译完成时被误执行
        ManualRenderExecutor render = new ManualRenderExecutor();
        AtomicInteger switched = new AtomicInteger();
        var scheduler = new PackPrecompileScheduler(DIRECT, render, target -> true);

        scheduler.switchWhenReady(switched::incrementAndGet);
        scheduler.reset();
        scheduler.request(new PackPrecompileScheduler.Target("BSL", "", Map.of()));
        render.drain();

        assertEquals(0, switched.get(), "reset 前登记的回调必须被清掉");
    }

    @Test
    void nullTargetDegradesToNone() {
        ManualRenderExecutor render = new ManualRenderExecutor();
        AtomicInteger compiled = new AtomicInteger();
        AtomicInteger switched = new AtomicInteger();
        var scheduler = new PackPrecompileScheduler(DIRECT, render, target -> {
            compiled.incrementAndGet();
            return true;
        });

        scheduler.switchWhenReady(switched::incrementAndGet);
        scheduler.request(null);
        render.drain();

        assertEquals(0, compiled.get(), "null 目标视作 none，不该编译");
        assertEquals(1, switched.get());
        assertNotNull(scheduler.status());
    }

    @Test
    void targetNormalizesNullFields() {
        var target = new PackPrecompileScheduler.Target(null, null, null);
        assertEquals("", target.selection());
        assertEquals("", target.profile());
        assertEquals(Map.of(), target.overrides());
        assertEquals("自动", target.display());
    }
}
