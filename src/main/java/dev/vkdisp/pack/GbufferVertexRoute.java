package dev.vkdisp.pack;
/**
 * 【自行补充】GAP-054：一条 {@code gbuffers_*} 程序<b>交进包资源的那份顶点终稿</b>能不能喂 NDC 全屏三角
 * / 本仓库自研（判据从 {@code PostVertexLinker} 的产物上量出来，不是某个决定的转述）
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓库自有实现 —— ① {@code PostVertexLinker}（clip 中性化那一步
 *    与 {@code emitsPositionPassthrough} 谓词）；② {@code PackSamplerSuperset} / {@code C1} 那轮立的
 *    「静态状态放进 {@code static final} 容器」写法（QD-03 的 {@code StaticFieldRatchetTest} 口径）。
 *    外部候选（Iris / OptiFine / VulkanMod / Sulkan / Beryl）零接触（07-CONSTRAINTS L12）；
 *    许可证：本文件独立编写（MIT），零第三方代码并入。
 * 1. 官方/主实现：原版没有「包顶点程序」这回事 ⇒ 本类是本项目的责任分离点。
 * 2. 备选：① 把判据位放进 {@code VkDispVirtualPack.GbufferArtifacts} —— <b>否决</b>：那个类正好压在
 *       {@code ClassLineRatchetTest} 的 1573 行上限（本轮实测加 45 行即变红），而棘轮的裁决口径是
 *       「新增内容落到独立类里」（QD-10 / {@code 19} §3.1）；
 *    ② 放进 {@link GbufferVertexSources} —— <b>否决</b>：那个类是<b>生成期</b>的「谁走哪条路」判定（无状态），
 *       本类要存的是<b>跨线程的一次实测结果</b>（资源线程写、渲染线程读），混在一起就把冷路径类变成了持有者；
 *    ③ 渲染期现读终稿文本 —— <b>否决</b>：那等于为一条判据每帧扫一遍着色器源（支柱③：冷路径不许进渲染线程）。
 * 3. 我们的差异点：🔑 <b>键的写入点唯一，且与「契约是否成立」同源</b>：只有
 *    {@link PackTerrainSource#linkPostVertexRoute} 那条路会 note 一个 true，而它同时也是
 *    {@code gbuffers_skybasic} 契约（{@code packContract}）唯一的产出点 ⇒ 「契约在、判据是上一轮的」
 *    这种漂移按构造不可能出现（X42：两侧必须同源）。
 * 4. 许可证核对：本项目 MIT；零第三方代码并入。
 * 5. 性能基线：❄️ 写 = 每次生成一次；读 = 每帧一次哈希查表（不扫源、不分配）。
 */
import dev.vkdisp.glsl.translate.PostVertexLinker;
import java.util.concurrent.ConcurrentHashMap;

/** 「这条 gbuffer 程序的顶点终稿把 {@code Position} 原样交出去了没」的持有者（生成期写、渲染期读）。 */
public final class GbufferVertexRoute {

    /**
     * 程序名 → 实测结果。
     *
     * <p>🔖 放 {@code static final} 容器而不是静态非 final 字段：{@code StaticFieldRatchetTest} 的棘轮
     * 口径是「静态<b>非 final</b> 字段只许降」（QD-03），换成原子/并发容器只是换写法，不假装债还掉了
     * （真正去处是 B2/B3 的 {@code PackSession}）。
     */
    private static final ConcurrentHashMap<String, Boolean> CLIP_PASSTHROUGH = new ConcurrentHashMap<>();

    private GbufferVertexRoute() {
    }

    /**
     * 对<b>交进包资源的那份顶点终稿</b>量一次并记下。
     *
     * @param program     程序名（{@code gbuffers_skybasic} 等）
     * @param vertexSource 终稿文本；{@code null} / 空白 = 本条没有可用顶点 ⇒ 记 false
     * @return 量出来的那一位（调用方要把它并进自报，别让判据只活在日志里）
     */
    public static boolean note(String program, String vertexSource) {
        boolean passthrough = vertexSource != null
                && PostVertexLinker.emitsPositionPassthrough(vertexSource);
        if (passthrough) {
            CLIP_PASSTHROUGH.put(program, Boolean.TRUE);
        } else {
            CLIP_PASSTHROUGH.remove(program);
        }
        return passthrough;
    }

    /**
     * 本条程序的顶点终稿是否可以喂 NDC 全屏三角（{@code SkyPackGbuffer} 的几何路线闸门读的就是它）。
     *
     * <p>🔴 查不到 = <b>false</b>（fail-closed）：没有实测就不许喂，天空退回原版重放。
     * 方向上 false 永远是安全的 —— 它砸的是「少一层包效果」，而 true 砸的是「画错一半屏幕且不报错」。
     */
    public static boolean passesPositionThrough(String program) {
        return Boolean.TRUE.equals(CLIP_PASSTHROUGH.get(program));
    }

    /** 已记下的程序名（自报与单测读数用；顺序不保证）。 */
    public static java.util.Set<String> recordedPrograms() {
        return java.util.Set.copyOf(CLIP_PASSTHROUGH.keySet());
    }

    /** 清空（只给单测之间隔离用；产品路径不调）。 */
    public static void resetForTests() {
        CLIP_PASSTHROUGH.clear();
    }
}
