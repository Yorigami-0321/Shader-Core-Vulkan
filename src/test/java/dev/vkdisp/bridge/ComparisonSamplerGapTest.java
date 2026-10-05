package dev.vkdisp.bridge;
/**
 * 【参考调研】GAP-015 守卫：`sampler2DShadow` 的「比较采样器」缺口必须一直**明示**着
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓库 `docs/13-GAP-REGISTRY.md` GAP-015 与
 *    `docs/07-CONSTRAINTS.md` · `X9`（不猜）· `X11`（禁止静默降级）· `T11`（降级必须可见）。
 *    以及原版 API 事实（`h38` 逐类反汇编核实，见 GAP-015 的「原版现状」行）。
 *    全部为本仓库自有规范 + 原版公开 API 事实，无外部代码搬运。
 * 1. 官方/主实现：原版 `GpuDevice#createSampler` 无 `CompareOp` 重载；
 *    `SamplerCache` 的 `boolean` 是 `useMipmaps` 不是 `compare`。
 * 2. 备选：
 *    <ul>
 *      <li>① 绑非比较采样器且**不吭声** —— <b>这就是被本守卫拦下的现状</b>：
 *          静默 UB，且取证者会把阴影结果当真数据。</li>
 *      <li>③ 不绑 + 报错（学 GAP-012/014）—— <b>否决</b>：`shadowtex0/1` 在本包地形程序里
 *          **每种配置都在**（实测 `SHADOW_DEPTH_2D=2`），不绑 ⇒ draw 抛 `Missing uniform`
 *          ⇒ 地形整条不渲染。按支柱①（兼容优先），「阴影不可信」优于「地形完全不画」。</li>
 *    </ul>
 * 3. 我们的差异点：把「这个 UB 必须一直有一次性说明」变成构建期红灯 ——
 *    将来若有人「顺手把那条 WARN 删掉换安静」，构建会失败。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 单测（扫源码，不进渲染路径）。
 */
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GAP-015 的「必须一直可见」守卫。
 *
 * <p>🔖 <b>为什么这条缺口可以长期存在、但不能悄悄存在</b>：
 * 原版<b>根本给不出</b>比较采样器 ⇒ 我们<b>做不出</b>类型正确的绑定
 * （这与 GAP-014「原版不能建 3D 纹理」是同一类**结构性**限制，不是本项目的实现缺陷）。
 * 既然做不出来，唯一诚实的选择就是**每次取证都能看到它**——
 * 否则阴影结果会被当成可信数据，污染后续所有以它为前提的判断。
 */
class ComparisonSamplerGapTest {

    private static final Path API = Path.of("src/main/java/dev/vkdisp/bridge/TerrainPipelineApi.java");
    private static final Path PLAN =
            Path.of("src/main/java/dev/vkdisp/pipeline/model/SamplerDimensionPlan.java");
    private static final Path REGISTRY = Path.of("docs/13-GAP-REGISTRY.md");

    private static String readOrSkip(Path path) {
        Assumptions.assumeTrue(Files.exists(path), "工程文件缺失: " + path);
        try {
            return Files.readString(path);
        } catch (java.io.IOException e) {
            throw new AssertionError("读取失败: " + path, e);
        }
    }

    @Test
    @DisplayName("🔖🔖 比较采样器缺口必须在一次性绑定摘要里明示（不得静默 UB）")
    void comparisonSamplerGapIsDisclosed() {
        String api = readOrSkip(API);
        assertTrue(api.contains("GAP-015"),
                "bindPackTerrainUniforms 的一次性摘要必须包含 GAP-015 的说明 —— "
                        + "sampler2DShadow 要的是比较采样器，而原版建不出来（h38 反汇编核实），"
                        + "我们只能绑非比较采样器 = UB。**不吭声**的话，"
                        + "取证者会把阴影结果当成可信数据");
        assertTrue(api.contains("阴影项的结果不可信"),
                "说明必须写明**后果**是什么（阴影不可信），而不只是提一句缺口编号");
        assertTrue(api.contains("不绑"),
                "必须写明为什么不学 GAP-012/014 那样「不绑 + 报错」—— "
                        + "shadowtex0/1 每种配置都在，不绑会让地形整条不渲染");
    }

    @Test
    @DisplayName("🔖🔖 那条说明必须是**一次性**的，不能变成每帧刷屏")
    void disclosureIsOneShot() {
        String api = readOrSkip(API);
        int guard = api.indexOf("GAP-015");
        int oneShot = api.indexOf("PACK_TERRAIN_BIND_LOGGED.getAndSet(true)");
        assertTrue(guard > 0, "找不到 GAP-015 说明");
        assertTrue(oneShot > 0, "找不到一次性哨兵");
        assertTrue(oneShot < guard,
                "GAP-015 的说明必须写在 PACK_TERRAIN_BIND_LOGGED 一次性哨兵**之后**（即其内部）"
                        + " —— 放在外面就变成每帧一条 WARN，正是 h34 刚修掉的 499 行刷屏");
    }

    @Test
    @DisplayName("🔖🔖 登记表必须有 GAP-015 完整条目（07 T12：先登记再自行补充）")
    void gapIsRegistered() {
        String reg = readOrSkip(REGISTRY);
        assertTrue(reg.contains("### GAP-015"),
                "GAP-015 必须在 13-GAP-REGISTRY 有完整条目 —— "
                        + "本项目历次教训都是「没登记的缺口会被人当成不存在」");
        assertTrue(reg.contains("useMipmaps"),
                "条目必须记下核实依据（那个 boolean 是 useMipmaps 不是 compare）—— "
                        + "否则下一个人会以为「换个带 compare 的重载就行」而白找一遍");
    }

    @Test
    @DisplayName("🔖 守卫不得越界：不得要求改成不绑（那会让地形整条不渲染）")
    void guardDoesNotMandateUnbinding() {
        String api = readOrSkip(API);
        String plan = readOrSkip(PLAN);
        assertTrue(plan.contains("SHADOW_DEPTH_2D"),
                "shadowtex* 必须继续绑**深度**视图 —— 这条不能退化："
                        + "绑 2D 颜色视图是 GAP-012 那种 UB（而且更糟：深度语义全丢）");
        assertFalse(plan.contains("case \"shadowtex0\" -> null"),
                "shadowtex0 不得改成不绑定 —— 按支柱①（兼容优先），"
                        + "「阴影不可信」优于「地形完全不渲染」");
    }
}
