package dev.vkdisp;
/**
 * 【自行补充】GAP-016 的产品级止血段：把包地形片元里 <b>albedo 乘法链那一行</b>的两参采样钉到显式 mip0
 *
 * <p>0. 合规核对：参考对象 = 本仓库自有的 {@code glsl/translate/SampleFactorProbeAdapter}
 *    （它已经在探针档里做同一件事，锚点行也是同一条）。零外部实现（07-CONSTRAINTS L12）；
 *    许可证：本文件独立编写（MIT）。
 *
 * <p>1. 为什么从 {@code VkDispVirtualPack} 抽出来：本类原来住在那个大方法的第 1199-1224 行，
 *    而 {@code ClassLineRatchetTest} 给那个类的上限是实测值 1573（只许降）。棘轮的指示一直是
 *    「新增内容落到独立类里」（QD-10 / {@code 19} §3.1），本轮 GAP-007 要往同一段里接东西 ⇒
 *    按同一条指示把这段已经独立成题的逻辑搬出来，**没有放宽任何基线**。
 *
 * <p>2. 语义一条都不改（与搬出前逐字同）：
 *    ① 只对<b>地形</b>那条程序生效；② 三个前提缺一不可（{@code mrt.terrainLodZero} 关、
 *    {@code mrt.terrainAtlasLod0} 开、探针档的强制位没被占着）；③ 命中数不是 1 就打 ERROR
 *    ——「只改一行」是 h45 立的铁律（那一轮探针一次改了 124 处）；④ {@code setForceLodZero}
 *    必须包在 {@code finally} 里还原，否则这个静态窗口会漏到下一条程序（QD-03 同族）。
 *    🔖 水是否需要同一段，由 GAP-016 自己在程序的臂里判，<b>不在此顺手套用</b>（X39）。
 */
import dev.vkdisp.glsl.translate.SampleFactorProbeAdapter;
import dev.vkdisp.glsl.translate.ShaderStage;

/** 地形片元的「图集采样钉到 mip0」产品级止血段（生成期调用；无状态）。 */
final class GbufferAlbedoLod0 {

    private GbufferAlbedoLod0() {
    }

    /**
     * 该打的时候打，不该打就原样交回。
     *
     * @param programName  程序名（只用于日志定位）
     * @param terrainProgram 本条是不是地形那条（锚点判据取自地形，见类注释第 2 条）
     * @param fragment     将要落进包资源的片元终稿
     */
    static String apply(String programName, boolean terrainProgram, String fragment) {
        if (!terrainProgram || VkDispConfig.MRT_TERRAIN_LOD_ZERO.get()
                || !VkDispConfig.MRT_TERRAIN_ATLAS_LOD0.get()
                || SampleFactorProbeAdapter.forceLodZeroEnabled()) {
            return fragment;
        }
        SampleFactorProbeAdapter.setForceLodZero(true);
        try {
            SampleFactorProbeAdapter.Result productLod =
                    SampleFactorProbeAdapter.apply(ShaderStage.FRAGMENT, fragment);
            if (productLod.patchedLodZero() != 1) {
                VkDisp.LOGGER.error("vkdisp: [GAP-016] 产品级 LOD0 段命中 {} 处（预期 1）"
                                + " —— 只改一行是 h45 立的铁律，请人工核对：{}",
                        productLod.patchedLodZero(), SampleFactorProbeAdapter.lastLodHitLines());
            }
            return productLod.text();
        } finally {
            SampleFactorProbeAdapter.setForceLodZero(false);
        }
    }
}
