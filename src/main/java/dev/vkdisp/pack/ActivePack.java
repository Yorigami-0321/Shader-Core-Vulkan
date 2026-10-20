package dev.vkdisp.pack;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 「正在画的是哪张包」这一个事实的 holder（0 = 兜底/无包）。
 *
 * <p><b>合规核对（第 0 步闸门）</b>：零外部代码，只读本项目自己的生成期事实。
 * → 能否并入本项目（LGPL-3.0-or-later）：可以。→ 例外条款：无。
 *
 * <p>🔴 <b>为什么要单独一个类而不是在 {@code VkDispVirtualPack} 里加个字段</b>：那条棘轮
 * （{@code ClassLineRatchetTest}，上限 1573 行）的口径就是「新增内容请落到独立类里」。
 *
 * <p>🔴 <b>为什么渲染侧需要它</b>：{@code ShaderPackService#load} 对<b>扫描到的每一张包</b>各跑一次，
 * 所以任何「加载期发布的表」都可能被后加载的包覆盖掉 —— 2026-10-17 真机实测到的正是这个形状：
 * 链已经按 {@code composite5} 派生了，混合表却是另一张包的（逐缓冲那一格静默丢掉，日志一切正常）。
 * 混合表按包名分格，消费方拿这里的名字查自己那一格；名字不在表里 = 没有声明 ——
 * 这是 GAP-018/045 那一族「缓存/表没有包这一维」的第六次，前五次都是靠日志反推的。
 */
public final class ActivePack {

    private static final AtomicReference<String> NAME = new AtomicReference<>("");

    private ActivePack() {
    }

    /** 生成期在装链的同一处调用（{@code null} = 兜底 = 空串）。 */
    public static void set(String packName) {
        NAME.set(packName == null ? "" : packName);
    }

    /** 正在画的包（空串 = 没有包）。 */
    public static String get() {
        return NAME.get();
    }
}
