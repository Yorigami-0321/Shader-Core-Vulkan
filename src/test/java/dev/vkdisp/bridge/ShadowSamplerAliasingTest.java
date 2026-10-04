package dev.vkdisp.bridge;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【参考调研】守卫「render pass **读写附件**不得同时作为 sampler」的 Vulkan 铁律 /
 * 只读本仓库源码 + 本项目实测证据。
 *
 * <p>🔴🔖 <b>这条守卫来自一次真实的未定义行为</b>：包地形片元声明了
 * {@code shadowtex0} / {@code shadowtex1} / {@code shadowcolor0}，
 * 而原实现把它们绑到**本 pass 自己的深度附件与 colortex0** ——
 * 那两张图同时是该 pass 的**读写附件**。
 * Vulkan 里「同一 image 既作读写附件又作采样器」是<b>未定义行为</b>：
 * 驱动可以丢 draw / 给垃圾 / 无事发生，<b>且不报 validation error</b>。
 *
 * <p>🔶 实测症状吻合：`evidence/h25` 与 `h26` 用 2×2 对照把闪烁的触发条件收敛到
 * <b>只有「包自己的地形片元」</b>（原版 {@code core/terrain} 不声明这些 sampler ⇒ 不触发）。
 *
 * <p>⚠️ <b>不要把本测试读成「已证明修好了」</b> —— 它只保证「不再绑读写附件」。
 * 「闪烁是否因此消失」需要客户端取证，结论以 {@code evidence/} 为准。
 */
class ShadowSamplerAliasingTest {

    private static final Path API =
            Path.of("src/main/java/dev/vkdisp/bridge/TerrainPipelineApi.java");
    private static final Path PASS_SRC =
            Path.of("src/main/java/dev/vkdisp/bridge/MrtTerrainPass.java");
    private static final Path STUBS =
            Path.of("src/main/java/dev/vkdisp/bridge/ShadowStubs.java");

    private static String read(Path p) {
        Assumptions.assumeTrue(Files.exists(p), "工程文件缺失: " + p);
        try {
            return Files.readString(p);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    /** 只统计非注释行（javadoc 的【参考调研】会引用代码字样）。 */
    private static int countCode(String text, String literal) {
        int n = 0;
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) {
                continue;
            }
            n += line.split(java.util.regex.Pattern.quote(literal), -1).length - 1;
        }
        return n;
    }

    @Test
    @DisplayName("🔖🔖 shadowtex0/1 不得绑本 pass 的深度附件（读写附件 + 采样器 = Vulkan UB）")
    void shadowtexMustNotBindOwnDepthAttachment() {
        String api = read(API);
        assertEquals(0, countCode(api, "case \"shadowtex0\", \"shadowtex1\" -> depthView"),
                "🔴 不得把本 pass 的深度视图**无条件**绑成 shadowtex0/1 —— 深度附件是**读写**的"
                        + "（清屏 0.0 + 地形写深度），同图又作采样器是 Vulkan 未定义行为，"
                        + "且本机无 validation layer ⇒ 永不报错");
        assertTrue(api.contains("case \"shadowtex0\", \"shadowtex1\" -> useStubs"),
                "shadowtex0/1 的绑定必须由开关 useStubs 决定（默认走桩纹理）");
        assertTrue(api.contains("? ShadowStubs.depthView()"),
                "🔖 **默认分支**必须是专用桩纹理（永不作附件）");
    }

    @Test
    @DisplayName("🔖🔖 shadowcolor0 不得绑本 pass 的 colortex0（同样是读写附件）")
    void shadowcolorMustNotBindOwnColorAttachment() {
        String api = read(API);
        assertEquals(0, countCode(api, "case \"shadowcolor0\" -> colorView"),
                "🔴 不得把本 pass 的 colortex0 **无条件**绑成 shadowcolor0 —— 它是被清屏并写入的读写附件");
        assertTrue(api.contains("case \"shadowcolor0\" -> useStubs"),
                "shadowcolor0 的绑定必须由开关 useStubs 决定");
        assertTrue(api.contains("? ShadowStubs.colorView()"),
                "🔖 **默认分支**必须是专用桩纹理");
    }

    /**
     * 🔬 A/B 逃生舱本身也要被守卫：它<b>故意</b>恢复未定义行为，
     * 所以必须**默认关**、**只在两个 case 里**出现、且**必须自报**。
     */
    @Test
    @DisplayName("🔬 A/B 开关必须默认 true，且旧绑定只在开关的 false 分支里出现")
    void abSwitchDefaultsToSafe() {
        String cfg = read(Path.of("src/main/java/dev/vkdisp/VkDispConfig.java"));
        assertTrue(cfg.contains("MRT_SHADOW_STUBS"), "缺少 A/B 开关");
        assertTrue(cfg.contains(".define(\"mrt.shadowStubs\", true)"),
                "🔖 mrt.shadowStubs 必须**默认 true**（安全侧）—— "
                        + "默认 false 就等于把未定义行为设成出厂行为");

        String stubs = read(STUBS);
        // 🔖 A/B 分支必须是**纯直通**：不得建纹理、不得 clear、不得改视图 ——
        //   它存在的唯一意义是「原样复现旧行为」，任何加工都会污染对照。
        assertTrue(stubs.contains("ownDepthAttachment") && stubs.contains("ownColorAttachment"),
                "缺少 A/B 直通辅助方法");
        assertEquals(0, countCode(stubs, "ownDepthAttachment(") - 1,
                "ownDepthAttachment 应只被声明一次（定义处），不得有别的调用/加工点");
        assertEquals(0, countCode(stubs, "ownColorAttachment(") - 1,
                "ownColorAttachment 同上");
        assertTrue(stubs.contains("**故意**"), "A/B 分支必须在注释里写明是故意恢复未定义行为");

        String pass = read(PASS_SRC);
        assertTrue(pass.contains("mrt.shadowStubs=false"),
                "🔖 开关为 false 时必须**自报**（WARN），否则取证时无法从日志确认变量生效（X51）");
    }

    @Test
    @DisplayName("🔖 桩纹理存在、1×1、且**永不作为 render pass 附件**")
    void stubsExistAndAreNeverAttachments() {
        Assumptions.assumeTrue(Files.exists(STUBS), "ShadowStubs 缺失");
        String stubs = read(STUBS);
        assertTrue(stubs.contains("ShadowStubs"), "占位");
        assertTrue(stubs.contains("1, 1, 1, 1") || stubs.contains("1, 1"),
                "桩纹理应为 1×1（只为提供合法采样源，不必与主目标同尺寸）");
        // 🔖 桩纹理一旦被当附件用，别名问题就回来了 —— 这里锁死「不进任何 pass 描述符」。
        assertEquals(0, countCode(stubs, "withDepthAttachment"),
                "桩纹理**不得**出现在任何 render pass 描述符里");
        assertEquals(0, countCode(stubs, "withColorAttachment"),
                "桩纹理**不得**出现在任何 render pass 描述符里");
    }

    @Test
    @DisplayName("🔖 桩深度清到 0.0（反向 Z 的远平面 ⇒ 阴影项取「无遮挡」）")
    void stubDepthIsClearedToFarPlane() {
        Assumptions.assumeTrue(Files.exists(STUBS), "ShadowStubs 缺失");
        String stubs = read(STUBS);
        assertTrue(stubs.contains("depthTex, 0.0F"),
                "🔖 桩深度必须清到 **0.0** —— 反向 Z 下 0.0 = 远平面 ⇒ 阴影判为「无遮挡」，"
                        + "是可解释的缺省；清成 1.0 会变成「全部遮挡」而把地形涂黑");
    }

    @Test
    @DisplayName("🔖 代价必须写在注释里：桩纹理没有真阴影贴图 ⇒ 阴影项不承诺")
    void costIsDocumented() {
        Assumptions.assumeTrue(Files.exists(STUBS), "ShadowStubs 缺失");
        assertTrue(read(STUBS).contains("不承诺"),
                "必须如实登记「桩纹理里没有真阴影贴图 ⇒ 阴影项不承诺」，"
                        + "不能让后来人以为阴影是对的");
    }

    @Test
    @DisplayName("🔖 我方 pass 的深度附件仍是**读写**的（这条不能被「顺手改成只读」破坏）")
    void ownDepthAttachmentStaysWritable() {
        // 反向清屏值 0.0 与可写深度是 h04 §5 用三条字节码级证据坐实的铁律；
        // 若有人为了绕开别名问题把深度改成只读，会破坏地形互相遮挡。
        String pass = read(PASS_SRC);
        assertTrue(pass.contains("withDepthAttachment(colortexDepth.getDepthTextureView(), OptionalDouble.of(0.0))"),
                "深度附件必须仍是「清屏 0.0 + 可写」");
    }
}