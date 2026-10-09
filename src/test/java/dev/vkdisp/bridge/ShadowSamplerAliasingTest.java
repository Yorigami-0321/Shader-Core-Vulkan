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
        // 🔶 2026-10-05 绑定改为「按 SamplerDimensionPlan 的维度决策」分派，
        //   所以字面量从 `case "shadowtex0", …` 变成 `case SHADOW_DEPTH_2D ->`。
        //   **本测试要守的不变式是「默认走桩」**，不是某个 case 的写法 ——
        //   否则重构一下 switch 形状就会误报，而误报久了就会被当成噪音忽略（那才是真正的失守）。
        assertTrue(api.contains("case SHADOW_DEPTH_2D -> useStubs"),
                "shadowtex0/1 的绑定必须由开关 useStubs 决定（默认走桩纹理）；"
                        + "若本测试失败，先确认绑定是否仍由 SamplerDimensionPlan 维度决策分派");
        assertTrue(api.contains("? ShadowStubs.depthView()"),
                "🔖 **默认分支**必须是专用桩纹理（永不作附件）");
        assertTrue(api.contains("SamplerDimensionPlan.diagnostic()")
                        || api.contains("SamplerDimensionPlan"),
                "🔖 绑定必须经 SamplerDimensionPlan 的**维度决策**，不得回到「一律喂图集」的 default 分支");
    }

    @Test
    @DisplayName("🔖🔖 shadowcolor0 不得绑本 pass 的 colortex0（同样是读写附件）")
    void shadowcolorMustNotBindOwnColorAttachment() {
        String api = read(API);
        assertEquals(0, countCode(api, "case \"shadowcolor0\" -> colorView"),
                "🔴 不得把本 pass 的 colortex0 **无条件**绑成 shadowcolor0 —— 它是被清屏并写入的读写附件");
        // 🔶 同上：case 形状随维度决策重构而变，守卫的是「默认走桩」这个不变式。
        assertTrue(api.contains("case SHADOW_COLOR_2D -> useStubs"),
                "shadowcolor0 的绑定必须由开关 useStubs 决定");
        assertTrue(api.contains("? ShadowStubs.colorView()"),
                "🔖 **默认分支**必须是专用桩纹理");
    }

    /**
     * 🔴 2026-10-05 新增：<b>维度决策不得回退</b>。
     *
     * <p>本轮修了另一个同类的静默 UB —— 包的 4 个 {@code sampler3D}
     * （{@code lighttex} / {@code lighttex0} / {@code lighttex1} / {@code voxeltex}）
     * 被落到 {@code default -> atlas}，也就是**拿 2D 图集视图喂 3D 采样器**：
     * 描述符类型不匹配 = Vulkan 未定义行为，同样不报错。
     * ⇒ 守卫「不可绑的类型宁可让 draw 抛 {@code Missing uniform}，也不喂错维度」。
     */
    @Test
    @DisplayName("🔖🔖 维度不可绑时必须显式不绑（不喂错维度造成静默 UB）")
    void unsupportedDimensionMustNotBindWrongView() {
        String api = read(API);
        assertTrue(api.contains("if (!binding.bindable())"),
                "必须显式判断「该 sampler 有没有类型匹配的视图」");
        assertTrue(api.contains("**不绑定**") || api.contains("不绑定"),
                "不可绑时必须走「不绑定 + ERROR」这条路（响亮失败），"
                        + "而不是回退到某个看起来能用的视图");
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
    @DisplayName("🔖🔖 桩深度必须清到 1.0 —— 读这张图的是**包**，包按 GL 口径读 shadowtex")
    void stubDepthIsClearedToNoOccluderForPackConvention() {
        Assumptions.assumeTrue(Files.exists(STUBS), "ShadowStubs 缺失");
        String stubs = read(STUBS);
        assertTrue(stubs.contains("depthTex, 1.0F"),
                "桩深度必须清到 **1.0**。\n"
                        + "本条此前断言的是 0.0，理由逐字写着「反向 Z 下 0.0 = 远平面 ⇒ 阴影判为无遮挡」——\n"
                        + "那句对**引擎自己的深度**成立，对 `shadowtex*` 不成立：包读这张图用的是 GL 口径\n"
                        + "（BSL `shaders/lib/lighting/shadows.glsl:3` 声明 `uniform sampler2DShadow shadowtex0`\n"
                        + "  → `shadow2D(tex, vec3(uv, z))`，比较方向「我的 z ≤ 图里存的 ⇒ 亮」）\n"
                        + "⇒ 存 0.0 被读成「贴脸就有遮挡物」= 全场景在影子里。\n"
                        + "真机证据（evidence/h50g-shadowstub-ab.md）：同一臂、同机位、同配置，只差这一格 ——\n"
                        + "  0.0 ⇒ 天空与云全黑；1.0 ⇒ 云出现灰白色块状结构。\n"
                        + "🔖 为什么此前没人发现：GAP-029 之前 `shadowFade` 从来没供 ⇒ 恒 0 ⇒\n"
                        + "  BSL 逐字 `shadow = mix(vec3(1.0), shadow, shadowFade)`（shadows.glsl:220）\n"
                        + "  把整个阴影项跳过 ⇒ 桩里存什么都无所谓。两个缺陷互相挡着（本项目第 N 例）。");
        // 反向断言：不许有人「顺手把 0.0 改回去」（那正是引擎自己深度的清屏值，见下面那条测试）。
        assertFalse(stubs.contains("depthTex, 0.0F"), "桩深度又变回 0.0 = 全场景在影子里");
    }

    @Test
    @DisplayName("🔖 代价必须写在注释里：桩纹理没有真阴影贴图 ⇒ 阴影项不承诺")
    void costIsDocumented() {
        Assumptions.assumeTrue(Files.exists(STUBS), "ShadowStubs 缺失");
        assertTrue(read(STUBS).contains("不承诺"),
                "必须如实登记「桩纹理里没有真阴影贴图 ⇒ 阴影项不承诺」，"
                        + "不能让后来人以为阴影是对的");
    }

    /**
     * 🔴🔴 h48y 新增：<b>桩的创建不得被 A/B 开关门住</b>。
     *
     * <p>为什么钉这条：本轮把 GAP-023 的 {@code depthtex*} 也改成绑桩（与 {@code ShadowStubs}
     * 共用同一张图），而那条分支是<b>无条件</b>调 {@code ShadowStubs#depthView()} 的。
     * 旧的 {@code ensureShadowStubs()} 却只在 {@code mrt.shadowStubs=true} 时才 {@code init()}
     * ⇒ 在 {@code false} 档（专门用来复现 h25/h26「整帧地形间歇消失」的取证臂）必然
     * {@code depthView == null}，而 {@code depthView()} 刻意<b>不</b>懒建（懒建会在
     * render pass 内新建 encoder）⇒ <b>抛 IllegalStateException</b>。
     *
     * <p>后果不是「多一个异常」而是<b>对照实验被换掉一个自变量</b>：那条臂原本要复现的
     * 症状是「画面闪烁」，会退化成「地形整层不画」。
     *
     * <p>🔶 为什么仍然钉<b>源码形状</b>而不是纯性质：真正的守卫在运行侧（init 有没有跑），
     * 单测没有 GPU；这里能纯判定的是「init 是否被开关包住」——
     * 它一旦回到被包住的状态，下一个读代码的人拿到的就是一条会抛的路径。
     * 这是本仓少数该钉形状的场景，理由写在这里，不做默默收紧。
     */
    @Test
    @DisplayName("🔴🔴 A/B 档（mrt.shadowStubs=false）也必须建桩 —— depthtex* 无条件取那张图")
    void stubCreationIsNotGatedByTheAbSwitch() {
        String pass = read(PASS_SRC);
        // 取出 ensureShadowStubs 的方法体（源码形状守卫，故取到下一个方法为止）。
        int from = pass.indexOf("private static void ensureShadowStubs()");
        assertTrue(from > 0, "找不到 ensureShadowStubs()（若被重命名，请同步更新本守卫的锚点）");
        // 🔖 取到「方法体的收尾花括号」为止，而不是 indexOf("private ")：
        //   后者会撞上紧跟其后的 javadoc 里出现的 private 字样，把无关文本一起吞进来。
        int cursor = pass.indexOf("{", from);
        int bodyEnd = -1;
        int level = 0;
        for (int i = cursor; i >= 0 && i < pass.length(); i++) {
            char c = pass.charAt(i);
            if (c == '{') {
                level++;
            } else if (c == '}') {
                level--;
                if (level == 0) {
                    bodyEnd = i + 1;
                    break;
                }
            }
        }
        assertTrue(bodyEnd > from, "无法解析 ensureShadowStubs 的方法体边界（源码形状已变？）");
        String body = pass.substring(from, bodyEnd);

        // 🔖 这里刻意**不用** countCode 的「字面量计数」写法：
        //   那个 helper 是**逐行**匹配的，喂多行字面量永远数出 0 ⇒ 断言恒真、守卫是空的
        //   （h48y 实测踩过：这样写的第一版在 bug 版源码上照样 PASSED，理由写在这里）。
        //   改为判定**结构**：init 那一行是否位于任何 if 的花括号内。
        //   取代码行（滤掉注释），逐行数花括号深度 —— 深度 0 即「不在任何 if 内」。
        String[] codeLines = java.util.Arrays.stream(body.split("\n"))
                .map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("*") && !l.startsWith("//"))
                .toArray(String[]::new);
        // 基准深度 0：**方法签名那一行的开括号自己会把深度抬到 1**，
        // 所以「深度 == 1」正是「处于方法体作用域、不在任何 if 内」。
        // （h48y 实测：这里一开始写成基准 1 ⇒ 固定代码上就误报红灯，深度语义要按签名计入。）
        int depth = 0;
        boolean initUnconditional = false;
        for (String line : codeLines) {
            boolean isInit = line.contains("ShadowStubs.init();");
            if (isInit && depth == 1) {
                initUnconditional = true;
            }
            for (char c : line.toCharArray()) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                }
            }
        }
        assertTrue(initUnconditional,
                "🔴 桩的创建不得再被 A/B 开关包住 —— GAP-023 的 depthtex* 无条件调"
                        + " ShadowStubs#depthView()，而它不懒建 ⇒ 开关 false 时会抛"
                        + " IllegalStateException，把「复现闪烁」那条 A/B 臂变成「整层不画」。"
                        + "ensureShadowStubs 方法体为：" + body);
        // 开关的语义仍是「阴影那一族绑桩还是绑附件」，判据不能因此被削弱。
        assertTrue(body.contains("MRT_SHADOW_STUBS"),
                "开关仍必须在本方法里被读 —— 它决定的是**绑定**，不是**桩是否存在**");
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