package dev.vkdisp.pack.uniform;

/**
 * 当前生效包的自写 uniform 集合（冷路径写入、渲染线程读取）。
 *
 * <p>🔖 <b>为什么要一个全局位</b>：{@code render/OfUniformManager.gather()} 是静态入口，
 * 被 {@code bridge/FrameApi}（composite/deferred/final/post）与 {@code bridge/TerrainPipelineApi}
 * 共 4 处调用，而它手上只有 {@code Minecraft} 和宽高 —— 拿不到 {@code ShaderPack} 模型。
 * 这条「冷路径记账、热路径取值」的形状不是新造的：同仓已有
 * {@code bridge/PackTextures.setDesired(...)}（GAP-009 纹理绑定）与
 * {@code VkDispVirtualPack.postChain}（整链）两个先例，都挂在
 * {@code VkDispVirtualPack.generateSources()} 这同一次激活上。
 *
 * <p>🔖 <b>必须成对复位</b>：换包/关总开关/异常兜底这三条路径都要 {@code install(EMPTY)}，
 * 否则上一张包的 {@code timeAngle} 会继续覆盖下一张包（画面表现为「换了包但时间感没换」，
 * 而日志一切正常 —— 与 QD-02 那一族「静默的错值」同源）。复位的调用点在
 * {@code VkDispVirtualPack}，与 {@code PackTextures.setDesired(null, …)} 同一行位置。
 *
 * <p>线程：{@code volatile} 引用换整份不可变集合 ⇒ 渲染线程永远读到「某一包的完整集合」，
 * 不会出现半份定义表。
 */
public final class ActivePackUniforms {

    private static volatile PackUniformSet current = PackUniformSet.EMPTY;

    private ActivePackUniforms() {
    }

    /** 包激活时安装（null 视作 {@link PackUniformSet#EMPTY}）。 */
    public static void install(PackUniformSet set) {
        current = set == null ? PackUniformSet.EMPTY : set;
    }

    /** 每帧取值；永不 null。 */
    public static PackUniformSet current() {
        return current;
    }
}
