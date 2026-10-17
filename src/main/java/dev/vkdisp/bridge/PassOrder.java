package dev.vkdisp.bridge;
/**
 * 【参考调研】R5 / M-13：把「谁必须先跑」从实测反推变成<b>显式声明</b>的帧图 pass 边
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 原版 26.3 {@code com/mojang/blaze3d/framegraph/FrameGraphBuilder.java}：
 *    {@code public FramePass addPass(String name)}（按调用序把 pass 入 {@code List<Pass> passes}）、
 *    {@code execute(...)} 里先 {@code identifyPassesToKeep()} 再对每个 pass 调
 *    {@code resolvePassOrder(...)}；② {@code FramePass} 接口的公开方法
 *    {@code requires(FramePass)} / {@code reads(ResourceHandle)} / {@code disableCulling()}；
 *    ③ {@code LevelRenderer} 的三个 pass 创建点：{@code "sky"} 第 382 行、{@code "main"} 第 403 行、
 *    以及 {@code FrameGraphSetupEvent} 在第 249 行触发（早于两者）。
 *    全部为「观察公开签名与语句顺序」，零源码搬运（Mojang EULA）。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以 —— 本文件是独立编写的桥接侧
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：{@code FramePass#requires} 就是官方给「必须后跑」的声明方式。
 * 2. 备选：① 只按插入序 {@code addPass} —— **已被实测否证**（h48g：天空被排到地形之后，
 *    {@code c0@afterSky} 只剩 0.0611，正确顺序下同取点是 72.85~84.50）；
 *    ② 靠 ORDER-MARK 日志反推顺序 + {@code mrt.terrainAfterLevel} 那条「画在帧图之后」的 A/B 车道
 *    —— **本类就是把这两件替掉的那一件**（替掉的动作在第 2 刀，见 CHANGE_LOG）。
 * 3. 我们的差异点：{@code requires} 的边必须在**两边都已存在**之后才加得成，而我方 pass 创建最早
 *    （第 249 行的事件）⇒ 本类做成「谁先到谁登记，后到的那一方补边」的<b>双向</b>登记，
 *    于是「我方 pass ↔ 原版 sky pass」这条边与创建顺序无关。
 * 4. 许可证核对：本项目 LGPL-3.0-or-later；零第三方代码复制。
 * 5. 性能基线：❄️ 只在装配期跑（每帧 ≤3 次引用赋值），渲染期零开销。
 */
import com.mojang.blaze3d.framegraph.FramePass;
import dev.vkdisp.VkDispConfig;
import org.jspecify.annotations.Nullable;

/**
 * M-13 的登记处：帧图 pass 的<b>显式先后声明</b>（登记表：{@code docs/04-SPEC.md} §5.0 的 M-13 行）。
 *
 * <p><b>为什么必须有它</b>：GAP-003 的 gbuffer 线要求「天空 → 地形 → 链」这个顺序。
 * 顺序不是由插入位置决定的（{@code resolvePassOrder} 按 {@code requiredPassIds} 与读写边解析），
 * 所以我方 pass 必须<b>说出</b>它依赖原版 {@code "sky"} —— 不说就只能实测反推，
 * 而反推出来的结论每帧都可能飘（h48g 就是那一格）。
 */
public final class PassOrder {

    /** 我方 gbuffer 地形 pass（在 {@code FrameGraphSetupEvent} 里创建；null = 本帧没开这一格）。 */
    private static @Nullable FramePass ownTerrain;

    /** 原版 {@code "sky"} pass（第 382 行创建，晚于我方 pass ⇒ 边只能由它这一侧补）。 */
    private static @Nullable FramePass vanillaSky;

    /** 原版 {@code "main"} pass（第 403 行创建；M-04 接管主 pass 时要用同一个句柄）。 */
    private static @Nullable FramePass vanillaMain;

    /** 本帧这条边到底有没有加成（自报用：区分「没到」与「到了但没连」）。 */
    private static boolean edgeDeclared;

    private PassOrder() {
    }

    /** 我方 gbuffer 地形 pass 创建完就登记（若原版 sky 已先到，立刻补边）。 */
    public static void declareOwnTerrain(@Nullable FramePass pass) {
        if (!VkDispConfig.MIXIN_PIN_PASS_ORDER.get()) {
            return;
        }
        ownTerrain = pass;
        edgeDeclared = false;
        if (pass != null && vanillaSky != null) {
            pass.requires(vanillaSky);
            edgeDeclared = true;
        }
    }

    /**
     * 原版 pass 创建的回调（由 {@code mixin/FrameGraphPassOrderMixin} 的 {@code @WrapOperation} 转发）。
     *
     * @param id   原版传入的 pass 名（只认 {@code "sky"} / {@code "main"}）
     * @param pass 刚创建出来的那个 pass（被包住调用的返回值 —— 边只能加在真实对象上）
     * @return 原样返回，<b>不改</b>原版任何行为
     */
    public static FramePass onPassDeclared(String id, FramePass pass) {
        if (!VkDispConfig.MIXIN_PIN_PASS_ORDER.get()) {
            return pass;
        }
        if (MixinTargets.VANILLA_SKY_PASS_ID.equals(id)) {
            vanillaSky = pass;
            // 🔖 双向：我方 pass 早在第 249 行就创建了，所以这里才是边真正落下的地方。
            if (ownTerrain != null) {
                ownTerrain.requires(pass);
                edgeDeclared = true;
            }
        } else if (MixinTargets.VANILLA_MAIN_PASS_ID.equals(id)) {
            vanillaMain = pass;
        }
        return pass;
    }

    /** 原版 {@code "sky"} pass 的句柄（GAP-003 / R4：天空改挂到官方钩子之后，边的另一端就是它）。 */
    public static @Nullable FramePass vanillaSky() {
        return vanillaSky;
    }

    /** 原版 {@code "main"} pass 的句柄（M-04 / R7 接管主 pass 的入口）。 */
    public static @Nullable FramePass vanillaMain() {
        return vanillaMain;
    }

    /** 本帧「terrain requires sky」到底连上了没有（观测面自报用，不参与渲染决策）。 */
    public static boolean edgeDeclared() {
        return edgeDeclared;
    }

    /** 每帧装配开始时清空（帧图 pass 对象<b>不能</b>跨帧复用 —— 复用旧对象 = 连到不存在的 pass）。 */
    public static void resetForFrame() {
        ownTerrain = null;
        vanillaSky = null;
        vanillaMain = null;
        edgeDeclared = false;
    }
}
