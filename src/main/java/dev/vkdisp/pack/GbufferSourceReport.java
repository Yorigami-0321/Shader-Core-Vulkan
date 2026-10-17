package dev.vkdisp.pack;
/**
 * 【自行补充】GAP-027 逐程序 gbuffer 源「到底落没落表」的判据 + 自报
 * 0. 合规核对：参考对象 = 本仓库自有 {@code GbufferVertexSources}（同一类「把 VkDispVirtualPack
 *    挤出去」的独立类形状，QD-10）。零外部实现。许可证：LGPL-3.0-or-later；零第三方代码复制。
 * 1. 官方/主实现：原版没有「半接线」这个概念 —— 只有我们自己那张虚拟包会逐程序配平两个源。
 * 2. 备选：留在 {@code VkDispVirtualPack} 里 —— <b>否决</b>：该类正压在 {@code ClassLineRatchetTest}
 *    的 1573 行实测上限（本轮加 11 行就被棘轮拦下），而棘轮的裁决一直是「新增内容落到独立类」。
 * 3. 我们的差异点：🔴 <b>只有一侧为 null 时必须点名是哪一侧</b>。2026-10-16 真机臂 B 的学费：
 *    半接线在这里是「两侧一起不落」，而资源加载期只报 {@code Couldn't find source for VERTEX shader}
 *    ⇒ 日志里没有任何一行说适配层在落表那一刻就已经是 null，于是「memo 被上一轮取空」与
 *    「生成器返回 null」两种完全不同的原因在日志里长得一模一样（只能靠猜 = 本仓反复付学费那一族）。
 *    两侧都不在 = 该条本来就不接线，那边已由 {@code not wired because ...} 的 WARN 点名 ⇒ 这里不重复。
 * 4. 许可证核对：本项目 LGPL-3.0-or-later。
 * 5. 性能基线：❄️ 冷路径 —— 只在 {@code openResources} 落表时逐程序调一次，渲染期零调用。
 */
import dev.vkdisp.VkDisp;

/** 「这条 gbuffer 程序的两个源落不落表」的判据与自报（无状态）。 */
public final class GbufferSourceReport {

    private GbufferSourceReport() {
    }

    /**
     * @param fragmentSource 该片元源；{@code null} = 本轮没有
     * @param adapterSource  该顶点适配层源；{@code null} = 本轮没有
     * @return {@code true} = 两侧都现成、可以落表；{@code false} = 调用点整条不落表
     */
    public static boolean bothPresent(String program, String fragmentSource, String adapterSource) {
        if (fragmentSource != null && adapterSource != null) {
            return true;
        }
        if (fragmentSource != null || adapterSource != null) {
            VkDisp.LOGGER.warn("vkdisp: [GAP-027/半接线] {} 落表时只有一侧是现成的"
                    + "（fragment={} adapter={}）⇒ <b>整条不落表</b>，管线侧两个源都拿不到；"
                    + "这一行点名的就是「哪一侧在落表时已经是 null」",
                    program, fragmentSource == null ? "无" : "有",
                    adapterSource == null ? "无" : "有");
        }
        return false;
    }

    /**
     * 落表之后把<b>这一轮真的进了表的 id 全列出来</b>（一次一类，{@code fsh} / {@code vsh} 各一行）。
     *
     * <p>🔴 为什么要这一行：2026-10-16 真机上「顶点适配层取不到」与「片元一直取得到」同时成立，
     * 而落表侧的「半接线」判据一条都没响 ⇒ 只剩「表里到底有没有这条 id」这一种可能，
     * 而它此前只能靠推。空表不报（本条就不接线，没有可判的东西）。
     *
     * @param fragment {@code true} = 片元那一类，{@code false} = 顶点适配层那一类
     */
    public static java.util.Map<net.minecraft.resources.Identifier, byte[]> noteWritten(
            boolean fragment, java.util.Map<net.minecraft.resources.Identifier, byte[]> out) {
        java.util.Map<net.minecraft.resources.Identifier, byte[]> immutable =
                java.util.Map.copyOf(out);
        if (!immutable.isEmpty()) {
            VkDisp.LOGGER.info("vkdisp: [GAP-027/落表] stage={} 本轮进了 {} 条: {}",
                    fragment ? "fsh" : "vsh", immutable.size(), immutable.keySet());
        }
        return immutable;
    }
}
