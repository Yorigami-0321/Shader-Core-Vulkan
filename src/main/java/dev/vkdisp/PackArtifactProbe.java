package dev.vkdisp;
/**
 * 【自行补充】{@code GAP-054} 的取证探针：谁在要哪一条 gbuffer 程序的产物。
 *
 * <p><b>为什么需要它</b>：真机 bisect 说「关掉 {@code mrt.skyPackShader} 那块巨型平面就消失」，
 * 但平面<b>不是</b> {@code SkyPackGbuffer.render} 画的（那条路线的自报行在日志里一次都没出现，
 * 而它的唯一调用方在我方 gbuffer pass 内，默认档不跑）。⇒ 键的效应来自<b>注册侧</b>，
 * 而注册侧有四个出口（契约 / 片元 id / 适配层 id / 块布局），任何一个都可能被第三方拿去用。
 * <b>不猜</b>（X9）：把「谁第一次要走了哪条程序的哪样东西」打出来，一趟真机就能定生产者。
 *
 * <p><b>形状</b>：每个「出口 × 程序」只报一次（{@code ConcurrentHashMap} 的键集去重），
 * 调用链用 {@link StackWalker} 现取（只在<b>首次</b>那一次付出代价 ⇒ 热路径上是一次哈希查表）。
 *
 * <p><b>它不改任何行为</b>：只读、只打日志；关掉它 = 删掉四处一行调用。
 */
public final class PackArtifactProbe {

    /** 已自报过的「出口 × 程序」（键数天然有界：4 个出口 × 表里 3 条程序）。 */
    private static final java.util.Set<String> NOTED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private PackArtifactProbe() {
    }

    /**
     * 某个出口第一次交出某条程序的产物时自报一行调用链。
     *
     * @param api     出口名（{@code packContract} / {@code packFragmentShaderId} / …）
     * @param program 程序名（{@code gbuffers_terrain} / {@code gbuffers_water} / {@code gbuffers_skybasic}）
     */
    public static void noteFirstRequest(String api, String program) {
        if (!NOTED.add(api + '|' + program)) {
            return;
        }
        String callers = StackWalker.getInstance().walk(frames -> frames.skip(2).limit(6)
                .map(f -> f.getClassName() + '#' + f.getMethodName() + ':' + f.getLineNumber())
                .collect(java.util.stream.Collectors.joining(" ← ")));
        VkDisp.LOGGER.info("vkdisp: [GAP-054/探针] {}('{}') 首次被要走，调用链={}", api, program,
                callers.isEmpty() ? "-" : callers);
    }

    /** 已自报的条数（单测/诊断读数）。 */
    public static int notedCount() {
        return NOTED.size();
    }
}
