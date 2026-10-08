package dev.vkdisp.bridge;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【参考调研】GAP-025：GLSL 采样器 {@code noisetex} 的「来源决策」纯函数档
 * 0. 合规核对（第 0 步闸门）：参考对象 = OptiFine {@code shaders.properties} 的公开语法事实
 *    （{@code texture.<name>=<路径>} 声明自定义纹理）+ 真实包 BSL v10.1.8 的三行文本事实
 *    （{@code shaders/shaders.properties:141 texture.noise=tex/noise.png}、
 *    {@code shaders/program/composite.glsl:53 uniform sampler2D noisetex}、
 *    {@code shaders/program/final.glsl:41 const int noiseTextureResolution = 512}）。
 *    → 全部为公开语法 + 本仓库自有实现，零第三方代码复制。
 * 1. 官方/主实现：OptiFine 把「内建 noisetex 采样器」与「properties 里的 {@code texture.noise}」
 *    视为同一张图 —— 声明名是 {@code noise}，采样器名是 {@code noisetex}。
 * 2. 备选：
 *    <ul>
 *      <li>① 恒用我方内置 64×64（本次改动之前的现状，{@code PackTextures} 旧第 121 行）——
 *          <b>否决</b>：包自己的噪声图根本取不到，而包按 {@code noiseTextureResolution=512}
 *          设计抖动尺度 ⇒ 频率与内容都错（GAP-025 原文）。</li>
 *      <li>② 用不上包图时静默回落 —— <b>否决</b>：X11 禁止静默降级，
 *          取证者会把「64×64 的抖动」当成包设计的抖动。</li>
 *      <li>③ 把这层别名写进 {@code ShaderProperties} 的键分发 —— <b>否决</b>：
 *          那是解析层的语义扩张，本决策不需要它 ⇒ 留在 bridge 侧自成一度闸门。</li>
 *    </ul>
 * 3. 我们的差异点：这一档<b>不碰 GPU、不碰原版类型</b> —— {@code bridge/} 里其它类都要渲染线程，
 *    决策混在里面就没法单测；把「名字解析 + 回落判定」摘出来才能被单测钉住。
 * 4. 许可证核对：本项目 MIT；零第三方代码。
 * 5. 性能基线：❄️ 冷路径（每次资源重载一次），不做优化。
 */
final class NoiseSamplerSource {

    /** GLSL 里噪声采样器的名字（BSL {@code shaders/program/composite.glsl:53 uniform sampler2D noisetex}）。 */
    static final String NOISE_SAMPLER = "noisetex";

    /** 我方内置噪声图的边长（{@code PackTextures} 确定性生成那张）。 */
    static final int BUILTIN_SIZE = 64;

    /**
     * 🔖 OF 内建采样器 → properties 声明名 的别名表。
     *
     * <p><b>为什么要这层别名</b>：{@code texture.noise} 覆盖的是<b>内建</b> {@code noisetex}，
     * 不是新增一个叫 {@code noise} 的采样器 —— 证据：BSL 只写
     * {@code texture.noise=tex/noise.png}（shaders.properties:141），GLSL 侧一律 {@code noisetex}
     * （program/composite.glsl:53）。只按原名精确匹配 ⇒ 这张声明永远查不到 ⇒ GAP-025 的根因。
     */
    private static final Map<String, String> DECLARED_AS = Map.of(NOISE_SAMPLER, "noise");

    /** 来源四态 —— 每一态都对应一条确定的日志，不留「猜」的余地（X9）。 */
    enum Source {
        /** 包声明的图已加载：真值（GAP-025 的目标状态），内置图不参与。 */
        PACK,
        /** 包没声明：内置 64×64 就是 OF 那个位置的答案，但<b>必须点名</b> —— 包的噪声尺度未必是 64。 */
        BUILTIN_NOT_DECLARED,
        /** 包声明了却没加载上（zip 缺条目 / 解码失败 / 尚未重载完）：回落内置，<b>必须点名</b>。 */
        BUILTIN_DECLARED_BUT_MISSING,
        /** 根本没有选包：没有后处理链 ⇒ 没人采 {@code noisetex}，既不点名也不预热。 */
        NO_PACK
    }

    /**
     * @param source       来源
     * @param declaredName 实际命中的 properties 声明名（{@code texture.<此名>}）；无声明为空串
     * @param declaredPath 声明的相对路径；无声明为空串
     */
    record Decision(Source source, String declaredName, String declaredPath) {

        /** 内置图是否要顶上（= 需要在开 pass 之前预热它）。 */
        boolean usesBuiltin() {
            return source != Source.PACK && source != Source.NO_PACK;
        }

        /** 这条回落是否必须被点名（X11）。 */
        boolean mustDisclose() {
            return source == Source.BUILTIN_NOT_DECLARED
                    || source == Source.BUILTIN_DECLARED_BUT_MISSING;
        }

        /** 点名文案：带声明路径与「为什么」，让取证者能按原文定位根因。 */
        String disclosure() {
            return switch (source) {
                case BUILTIN_NOT_DECLARED -> "noisetex 回落内置 " + BUILTIN_SIZE + "x" + BUILTIN_SIZE
                        + " 噪声图：所选包未声明 texture.noise"
                        + " —— 包若按自有分辨率设计噪声/抖动（noiseTextureResolution），频率与包设计不符";
                case BUILTIN_DECLARED_BUT_MISSING -> "noisetex 回落内置 " + BUILTIN_SIZE + "x"
                        + BUILTIN_SIZE + " 噪声图：包声明了 texture." + declaredName + "='"
                        + declaredPath + "' 但该图未能加载（上面 [GAP-009] 的 FAILED 原文即根因）";
                default -> "";
            };
        }
    }

    private NoiseSamplerSource() {}

    /**
     * 采样器名 → 可能在 properties 里声明它的名字清单（原名优先，其次 OF 别名）。
     *
     * <p>🔖 没有别名的名字（{@code dirt} 等）原样返回 ⇒ 既有自定义纹理的绑定行为一字不变。
     */
    static List<String> declarationNames(String samplerName) {
        String alias = DECLARED_AS.get(samplerName);
        if (alias == null || alias.equals(samplerName)) {
            return List.of(samplerName);
        }
        // 原名先于别名：包同时写 texture.noisetex 与 texture.noise 时，精确名字是更明确的意图。
        return List.of(samplerName, alias);
    }

    /**
     * 纯决策（无 GPU）：这一次 {@code noisetex} 该由谁供给。
     *
     * @param bindings     包声明表（声明名 → 相对路径，即 {@code PackTextures.desired}）
     * @param loadedNames  已成功加载的声明名集合（{@code PackTextures.loaded} 的键）
     * @param packSelected 是否真的选了包（无包时后处理链不存在，谈不上回落）
     */
    static Decision decide(Map<String, String> bindings, Set<String> loadedNames,
            boolean packSelected) {
        if (!packSelected) {
            return new Decision(Source.NO_PACK, "", "");
        }
        for (String declaredName : declarationNames(NOISE_SAMPLER)) {
            if (loadedNames.contains(declaredName)) {
                return new Decision(Source.PACK, declaredName,
                        bindings.getOrDefault(declaredName, ""));
            }
        }
        String declared = firstDeclared(bindings);
        return declared.isEmpty()
                ? new Decision(Source.BUILTIN_NOT_DECLARED, "", "")
                : new Decision(Source.BUILTIN_DECLARED_BUT_MISSING, declared,
                        bindings.getOrDefault(declared, ""));
    }

    /** 绑定表里能给 noisetex 用的声明名（原名或别名）；一条都没有 ⇒ 空串（调用方据此判「未声明」）。 */
    private static String firstDeclared(Map<String, String> bindings) {
        for (String declaredName : declarationNames(NOISE_SAMPLER)) {
            if (bindings.containsKey(declaredName)) {
                return declaredName;
            }
        }
        return "";
    }
}
