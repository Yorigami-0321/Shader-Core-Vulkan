package dev.vkdisp.bridge;
/**
 * 【参考调研】GAP-036 半开档 / GAP-056 取证纪律：链门控的单点判定与自报
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓自有代码 {@code FrameApi#isPostChainActive()}
 *    （判定式逐字搬来，未改语义）与 {@code VkDispConfig} 的公开 {@code ConfigValue}。
 *    → 能否并入本项目（MIT）：可以，自有代码内部重构，无外部许可面。
 * 1. 官方/主实现：无（原版没有「后处理链」这个概念）。
 * 2. 备选：把六项读数直接写在 {@code FullscreenPassHook} 的 WARN 文案里（否决：那份合取式就有两处真源，
 *    {@code FrameApi} 改了文案不说 ⇒ 又回到「日志报的因与代码判的因不是同一处」）。
 * 3. 我们的差异点：判定与自报**共用同一组六个谓词、同一个求值顺序**，且自报只报它真读到过的项。
 * 4. 许可证核对：本项目 MIT。
 * 5. 性能基线：{@link #active()} 与原来一样短路、零分配（每帧 1~4 次）；
 *    {@link #readout()} 只在**路径变了**那一次被调，不在每帧路径上。
 */

import java.util.StringJoiner;

/**
 * 「整条后处理链这一帧要不要接管」的门控（原 {@code FrameApi#isPostChainActive()} 的六个合取项）。
 *
 * <p><b>为什么要单独成类</b>（2026-10-14，{@code GAP-036}/{@code GAP-056}）：
 * {@code [GAP-036/半开档]} 那行 WARN 的文案点名了 {@code mrt.packTerrainShader}，
 * 而它旁边那条判定式**从来没读过这个键** —— 链不跑有六个可能的因，那一行把其中一个当成了结论，
 * 于是「臂 3 = 两键档」被误签、{@code mrt.packTerrainShader} 被误记成「已排除」。
 * ⇒ 现在判定的六个因**只在这里写一遍**，要对外说「为什么链没跑」必须调 {@link #readout()}。
 */
public final class PostChainGate {

    /** 六个合取项的自报名（顺序必须与 {@link #active()} 逐字一致，由单测钉住）。 */
    static final String[] GATE_NAMES = {
            "mrt.postChain",
            "mrt.terrain",
            "mrt.packTerrainShader",
            "!mrt.terrainToMain",
            "包地形片元产物在场",
            "链有 pass",
    };

    /** 链要不要接管本帧：六个合取项全为真。 */
    public static boolean active() {
        return gatePostChain()
                && gateTerrain()
                && gatePackTerrainShader()
                && gateNotToMain()
                && gateTerrainProgram()
                && gateChainHasPasses();
    }

    /**
     * 按 {@link #active()} 的同一顺序逐项求值，**第一项不成立即停**，停后面的项不打。
     *
     * <p>「只报读过的项」是有意的：{@code VkDispVirtualPack.postChain()} 在链没配的档里根本不该被问，
     * 而一行日志也不该说自己没测过的东西。
     *
     * @return 形如 {@code mrt.postChain=true mrt.terrain=true … ⇒ 链跑}
     */
    public static String readout() {
        boolean[] measured = new boolean[GATE_NAMES.length];
        int[] count = new int[1];
        // && 的短路顺序 = active() 的顺序 ⇒ 「读到哪一项、为什么停」两处必然是同一个答案。
        boolean active = measure(measured, count, gatePostChain())
                && measure(measured, count, gateTerrain())
                && measure(measured, count, gatePackTerrainShader())
                && measure(measured, count, gateNotToMain())
                && measure(measured, count, gateTerrainProgram())
                && measure(measured, count, gateChainHasPasses());
        return describe(measured, count[0], active);
    }

    private static boolean gatePostChain() {
        return dev.vkdisp.VkDispConfig.MRT_POST_CHAIN.get();
    }

    private static boolean gateTerrain() {
        return dev.vkdisp.VkDispConfig.MRT_TERRAIN_ENABLED.get();
    }

    private static boolean gatePackTerrainShader() {
        return dev.vkdisp.VkDispConfig.MRT_PACK_TERRAIN_SHADER.get();
    }

    private static boolean gateNotToMain() {
        return !MrtTerrainPass.toMain();
    }

    private static boolean gateTerrainProgram() {
        return dev.vkdisp.VkDispVirtualPack.terrainProgram() != null;
    }

    private static boolean gateChainHasPasses() {
        return !dev.vkdisp.VkDispVirtualPack.postChain().passes().isEmpty();
    }

    /** 记一笔读数并计数，把原值交回给 {@code &&} 链 ⇒ 短路点就是停笔点。 */
    private static boolean measure(boolean[] measured, int[] count, boolean value) {
        measured[count[0]++] = value;
        return value;
    }

    /**
     * 纯格式化：只写**读过的那几项**（{@code count} 项），后面的项不出现 —— 它没被读过。
     *
     * <p>单测钉这张小真值表：全真 ⇒ 六项都在且「链跑」；第一项假 ⇒ 只有一项且「链不跑」。
     */
    static String describe(boolean[] measured, int count, boolean active) {
        StringJoiner out = new StringJoiner(" ");
        for (int i = 0; i < count; i++) {
            out.add(GATE_NAMES[i] + "=" + measured[i]);
        }
        return out + " ⇒ 链" + (active ? "跑" : "不跑");
    }

    private PostChainGate() {
    }
}
