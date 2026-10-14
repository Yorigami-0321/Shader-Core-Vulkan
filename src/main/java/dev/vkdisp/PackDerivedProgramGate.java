package dev.vkdisp;
/**
 * 【自行补充】GAP-054 · 「包的资源 vs 包的程序」二分总闸的<b>读取与自报侧</b>
 *
 * <p>0. 合规核对（第 0 步闸门）：参考对象 = 本仓库既有同类桥接类 {@code PackPostVertexSwitch} /
 *    {@code PackChainGatingSwitch}（把「一个取证总闸 + 它的点名自报」收成独立小类），
 *    以及 {@code docs/13-GAP-REGISTRY.md} 的 GAP-054「八臂之后的形状」行。
 *    → 可并入本项目（MIT）：是，全部为仓库内自有代码与自有文档事实；例外条款：无。
 * 1. 官方/主实现：无 —— OptiFine/Iris 没有「只加载资源不装程序」这一档，这是二分定位用的取证闸。
 * 2. 备选：① 把判定与自报写在 {@code VkDispVirtualPack} 里 —— <b>否决</b>：那个类已在
 *          {@code ClassLineRatchetTest} 的巨类名单里（上限 1573 行，只许降），棘轮的指示就是
 *          「新增内容落到独立类里」；② 用 {@code shaderPack="none"} 当这一刀 —— <b>否决</b>：
 *          它把资源腿一起切了，两臂之间不是单变量（臂 8 已经用过它，结论是「包带来的」）。
 * 3. 我们的差异点：这一闸<b>只切程序腿</b>。关掉后的处置逐字同 {@code VkDispVirtualPack} 的兜底分支
 *    （三源回落内置 passthrough、{@code postChain=EMPTY}、各槽布局清空、逐程序 gbuffer 产物 reset），
 *    唯一区别是<b>不碰</b> {@code PackTextures.setDesired} 与 {@code ActivePackUniforms.install} ——
 *    那两条正是「包自带的贴图/模型」与「包自己写下的 uniform 表达式」。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 冷路径（每次资源生成读一次），不进每帧路径。
 */
public final class PackDerivedProgramGate {

    /** 配置键名（与 {@code VkDispConfig} 里 {@code define} 的键逐字一致）。 */
    public static final String CONFIG_KEY = "pack.derivedPrograms";

    private PackDerivedProgramGate() {}

    /**
     * 今天允不允许生成/注册派生程序。
     *
     * @return {@code true} = 正常路线；{@code false} = 总闸关着（包的资源照旧、程序一条不注册）
     */
    public static boolean allowed() {
        return VkDispConfig.PACK_DERIVED_PROGRAMS.get();
    }

    /**
     * 关闸那一刻的点名自报（{@code [GAP-054/生成侧]}）。
     *
     * <p>🔖 这一行是<b>判据本身</b>：它声明「我方一条派生程序都没注册，而包的资源还在」，
     * 于是同一臂里那块巨型平面还在/消失才读得出「资源 vs 程序」。
     * 生成侧此前<b>没有任何</b>这样的自报（{@code 19} §7-8 第 8 格那条「探针往生成侧挪一格」
     * 从 2026-10-13 挂到今天），本行把它补上。
     */
    public static void reportBlocked(String packName, int textureBindingCount, int packUniformCount) {
        VkDisp.LOGGER.warn("vkdisp: [GAP-054/生成侧] 派生程序总闸=关（{}=false）:"
                        + " pack={} 资源腿=留（texture 绑定 {} 条 / 包自写 uniform {} 条）"
                        + " 程序腿=全空（三源 built-in passthrough、fragments 0、adapters 0、chainPasses 0）"
                        + " —— 本行为「包的程序这一侧今天一条都没注册」作证",
                CONFIG_KEY, packName, textureBindingCount, packUniformCount);
    }
}
