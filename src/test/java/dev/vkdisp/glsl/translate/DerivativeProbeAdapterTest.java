package dev.vkdisp.glsl.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 派生导数探针（诊断段）的单测（纯文本）。
 *
 * <p>这组断言的由来：h13 把 GAP-008 的范围缩到唯一一个「坏路径有、工作路径没有」的
 * albedo 算子 —— textureGrad(texture_0, …, dFdx(texCoord), dFdy(texCoord))。
 * 而 dcdx/dcdy 声明在**片元**里（第 293 行），顶点侧探针够不着，只能在转译段做。
 */
class DerivativeProbeAdapterTest {

    private static final String N = "\n";
    private static final String TAB = "	";

    @AfterEach
    void reset() {
        DerivativeProbeAdapter.setEnabled(false);
    }

    @Test
    @DisplayName("`🔖 默认关：关闭时必须原样返回（一个字都不改）")
    void disabledByDefaultIsIdentity() {
        DerivativeProbeAdapter.setEnabled(false);
        String src = "vec2 dcdx = dFdx(texCoord);" + N
                + "vec2 dcdy = dFdy(texCoord);";
        DerivativeProbeAdapter.Result r = DerivativeProbeAdapter.apply(null, src);
        assertEquals(src, r.text(),
                "`🔖 诊断开关默认必须是恒等变换（漏开/漏关都不能改语义）");
        assertEquals(0, r.patched());
        assertFalse(DerivativeProbeAdapter.enabled());
    }

    @Test
    @DisplayName("`🔖 开启：dcdx/dcdy 的右值换成 vec2(0.0)，其余行原样")
    void rewritesBothDerivatives() {
        DerivativeProbeAdapter.setEnabled(true);
        String src = "void main() {" + N
                + TAB + "vec2 dcdx = dFdx(texCoord);" + N
                + TAB + "vec2 dcdy = dFdy(texCoord);" + N
                + TAB + "vec4 c = textureGrad(s, p, dcdx, dcdy);" + N
                + "}";
        DerivativeProbeAdapter.Result r = DerivativeProbeAdapter.apply(null, src);
        assertEquals(2, r.patched());
        assertTrue(r.text().contains("dcdx = vec2(0.0);"));
        assertTrue(r.text().contains("dcdy = vec2(0.0);"));
        assertFalse(r.text().contains("dFdx("),
                "`🔖 dFdx 必须全部被换掉，否则 LOD 判据不干净");
        assertTrue(r.text().contains("textureGrad(s, p, dcdx, dcdy);"),
                "`🔖 探针只改导数初值，不得碰 textureGrad 调用本身（那就不是单变量了）");
    }

    @Test
    @DisplayName("`🔎 等行数：改写不得增删行（否则行号映射全错）")
    void preservesLineCount() {
        DerivativeProbeAdapter.setEnabled(true);
        String src = "a" + N + "vec2 dcdx = dFdx(texCoord);" + N
                + "b" + N + "c";
        int before = src.split(N, -1).length;
        DerivativeProbeAdapter.Result r = DerivativeProbeAdapter.apply(null, src);
        assertEquals(before, r.text().split(N, -1).length,
                "`🔖 转译链的行号映射依赖「等行数」这一前提，破坏它等于毒化全部诊断行号");
    }

    @Test
    @DisplayName("`🔖 X44 回归：改写后的行必须仍以分号收尾（行尾注释会吃掉分号）")
    void keepsSemicolonWithTrailingComment() {
        DerivativeProbeAdapter.setEnabled(true);
        String src = "vec2 dcdx = dFdx(texCoord); // 屏幕空间导数";
        DerivativeProbeAdapter.Result r = DerivativeProbeAdapter.apply(null, src);
        String line = r.text().trim();
        assertTrue(line.endsWith("// 屏幕空间导数"),
                "`🔖 行尾注释必须原样保留（F3 契约：保行号、保注释）");
        int cut = line.indexOf("//");
        assertTrue(line.substring(0, cut).trim().endsWith(";"),
                "`🔖 半句里必须带分号：否则就是 h11 那种「分号被注释吃掉 ⇒ 整批管线加载失败」");
    }

    @Test
    @DisplayName("`🔦 X45 回归：开启时必须自报状态与命中数")
    void selfReportsStateAndHitCount() {
        DerivativeProbeAdapter.setEnabled(true);
        DerivativeProbeAdapter.Result hit = DerivativeProbeAdapter.apply(null,
                "vec2 dcdx = dFdx(texCoord);");
        assertTrue(hit.diagnostics().stream().anyMatch(d -> d.message().contains("已把 1 处")),
                "`🔦 不自报命中数 ⇒ 无法区分「改了但没用」与「根本没改到」");
        DerivativeProbeAdapter.Result miss = DerivativeProbeAdapter.apply(null, "int x = 1;");
        assertTrue(miss.diagnostics().stream().anyMatch(d -> d.message().contains("开关没生效")),
                "`🔦 一处没命中时必须明说「开关没生效（不是结论不成立）」");
    }

    @Test
    @DisplayName("`🔖 不得误伤其它 dFdx（只认 dcdx/dcdy 两个名字）")
    void doesNotTouchOtherDerivatives() {
        DerivativeProbeAdapter.setEnabled(true);
        String src = "vec2 other = dFdx(texCoord);";
        DerivativeProbeAdapter.Result r = DerivativeProbeAdapter.apply(null, src);
        assertEquals(0, r.patched(),
                "`🔖 改到别的导数上就不是单变量了（会连带改变本不相关的采样）");
        assertEquals(src, r.text());
    }
}
