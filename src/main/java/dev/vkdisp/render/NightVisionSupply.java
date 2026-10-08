package dev.vkdisp.render;
/**
 * 【自行补充】GAP-007：夜视 / 基岩层高度这两个内建量的**取值**。
 *
 * <p>为什么单独立一个类（不是为了整洁）：`OfUniformManagerTest` 只测 `write(...)` 这条**纯函数**
 * 路径，把 `MobEffects` / `LivingEntity` 的读取写进 `OfUniformManager` 会让整个测试类
 * `NoClassDefFoundError: net/minecraft/world/entity/LivingEntity`（实体类在测试运行时里
 * 触发不了注册引导）。⇒ 实体侧读取隔离在本类，只有 `gather()` 真跑到才加载。
 *
 * <p>语义核实（26.3.0.51-beta 源码级，零猜测部分标 ✅，近似部分标 ⚠️）：
 * <ul>
 *   <li>✅ `LivingEntity.hasEffect(MobEffect)` 是 public（原版 `Camera.java:142` 自己就这么读盲视）；</li>
 *   <li>✅ `GameRenderer.nightVisionScale(LivingEntity, float)` 是 public static（第 425-428 行），
 *       但它对**没有**夜视也返回 1.0 ⇒ 不能直接当 `nightVision` 喂给包（BSL 用它做增益
 *       `deferred1.glsl:494 color.rgb *= 1.0 + nightVision` ⇒ 全屏永久 ×2）；</li>
 *   <li>⚠️ 近似 v1（登记）：有夜视时取原版的临期抖动值，无夜视取 0；OF 自己的精确曲线未取证（X9）。</li>
 * </ul>
 */
import net.minecraft.client.Minecraft;
import net.minecraft.world.effect.MobEffects;
import org.jspecify.annotations.Nullable;

/** 只在渲染线程调用；无状态，纯取值。 */
final class NightVisionSupply {

    private NightVisionSupply() {
    }

    /** OF `nightVision`：0 = 没有夜视；有则沿用原版的临期抖动（见类注释的 ×2 陷阱）。 */
    static float value(@Nullable Minecraft mc, float partialTicks) {
        if (mc == null || mc.player == null) {
            return 0.0F;
        }
        if (!mc.player.hasEffect(MobEffects.NIGHT_VISION)) {
            return 0.0F;
        }
        return net.minecraft.client.renderer.GameRenderer.nightVisionScale(mc.player, partialTicks);
    }

    /**
     * OF `bedrockLevel`：✅ `Level.dimensionType()`（public）→ `DimensionType.minY()`
     * （record 分量 `int minY`，源码第 34 行）。BSL 只在云/星里拿它当「玩家接近或低于基岩层
     * 就把云压掉」的门槛（`clouds.glsl:270/464/504/586`）。
     */
    static float bedrockLevel(@Nullable Minecraft mc) {
        if (mc == null || mc.level == null) {
            return 0.0F;
        }
        return (float) mc.level.dimensionType().minY();
    }
}
