package dev.vkdisp.render;
/**
 * 【自行补充】GAP-021：把「包自写的 uniform 表达式」接进 {@link OfUniformManager#gather} 的供值路径。
 *
 * <p>为什么单独立一个类（与 {@link NightVisionSupply} 同一条理由，不是整洁）：
 * {@code OfUniformManagerTest} 只跑 {@code write(...)} 这条纯函数车道，而本类要读
 * {@code MobEffects.BLINDNESS} / {@code LivingEntity}（实体类在测试运行时里触发注册引导 ⇒
 * {@code NoClassDefFoundError}，本会话实测踩过）。⇒ Minecraft 侧的读取全部隔离在这里，
 * 只有 {@code gather()} 真跑到才加载；表达式求值本体在纯 Java 的
 * {@code dev.vkdisp.pack.uniform} 包，可脱离 Minecraft 单测。
 *
 * <p><b>取值来源核实（X9：核到行的才用；核不到的一律不供，让依赖它的表达式整条跳过并被点名）</b>
 * <ul>
 *   <li>✅ {@code sunAngle}（OF 口径 0..1）：由原版 {@code EnvironmentAttributes.SUN_ANGLE}
 *       （度数，{@code EnvironmentAttributes.java:74-76}，{@code ANGLE_DEGREES} 类型）换算，
 *       换算式与其出处逐条写在 {@link PackUniformInputs#sunAngleFromDegrees} 的注释里
 *       （文档给的定义 + {@code SkyRenderer.java:119} / {@code Timelines.java:53} 的
 *       「正午 = 0°/360°」双向校验）。</li>
 *   <li>✅ {@code blindness}（0..1）：原版自己算的那个「盲视黑暗度」——
 *       {@code BlindnessFogEnvironment.getModifiedDarkness(LivingEntity, float, float)} 是
 *       <b>public</b>（源码第 38-48 行：{@code endsWithin(19) ? max(duration/20, base) : 1.0}），
 *       {@code FogRenderer.java:112-121} 就是拿它去乘雾色亮度；这里以 {@code base = 0}
 *       调用 ⇒ 只取「盲视效果那一份」，不把虚空变暗混进来（OF 的 {@code blindness}
 *       文档口径是 0.0-1.0 的盲视量）。实体读取本身另有先例：
 *       {@code Camera.java:142} 直读 {@code livingEntity.hasEffect(MobEffects.BLINDNESS)}。</li>
 *   <li>✅ 其余标量/向量 = 已在 04-SPEC §3.2 上传注记逐条核实过的 {@code gather()} 结果
 *       （{@code worldTime / frameCounter / cameraPosition / rainStrength / …}）。</li>
 *   <li>🔴 <b>不供</b>：{@code biome}（OF 的数字生物群集 ID 与 {@code BIOME_*} 符号名
 *       在 26.3 原版侧没有可核实的对应物 —— {@code Registries.BIOME} 只给
 *       {@code ResourceLocation}，数字 ID 是 OptiFine 自己的表；BSL 用的正是那张表）。
 *       ⇒ BSL 的 {@code isCold/isDesert/…} 11 条整条跳过 + 一次性 WARN 点名，
 *       登记见 GAP-021 的「未解析输入」一半。</li>
 * </ul>
 *
 * <p><b>覆盖优先级</b>：OF 语义是「包自写的 {@code uniform.float.X} 覆盖引擎内建 X」
 * （BSL 就自己写了 {@code timeAngle}）⇒ 本类在 {@code gather()} <b>之后</b>把包的值 put 进
 * 同一张映射，包赢。
 */
import dev.vkdisp.pack.uniform.ActivePackUniforms;
import dev.vkdisp.pack.uniform.PackUniformInputs;
import dev.vkdisp.pack.uniform.PackUniformSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.fog.environment.BlindnessFogEnvironment;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Vector3f;

/** 只在渲染线程调用（{@code gather()} 的每一处调用点都在渲染线程）。 */
public final class PackUniformSupply {

    /** 1 游戏刻 = 50ms ⇒ 20 刻 = 1 秒（原版 TPS 恒定值，{@code smooth()} 的半衰期按刻计）。 */
    private static final double TICKS_PER_SECOND = 20.0;

    /** 单帧最多按 10 刻推进：切窗/暂停恢复的那一帧 {@code frameTime} 不可信（同 OfUniformManager 的截断口径）。 */
    private static final double MAX_DELTA_TICKS = 10.0;

    /** {@code smooth()} 的跨帧状态（渲染线程独占 ⇒ 裸 HashMap；换包时随 {@link #resetFor} 一起清）。 */
    private static final Map<String, Double> SMOOTH_STATE = new HashMap<>();

    /** 已经 WARN 过的「跳过清单指纹」，防每帧刷屏（同 {@code OfUniformManager.UPLOAD_LOGGED_SLOTS} 的打法）。 */
    private static final Set<String> WARNED = new HashSet<>();

    /** 上一次安装到本类的集合引用（引用变了 ⇒ 换包，WARN 位与平滑态一起复位）。 */
    private static PackUniformSet lastInstalled;

    private PackUniformSupply() {
    }

    /**
     * 求值包自写的 uniform 并覆盖 {@code values} 里的同名内建。
     *
     * @param values      {@link OfUniformManager#gather} 已经填好的内建映射（就地覆盖）
     * @param mc          客户端单例（null / 未进世界 ⇒ 引擎侧补充量按 0 供，与既有内建口径一致）
     * @param partialTicks 世界插值刻（{@code LevelRenderState.worldPartialTicks}，与角度/盲视读取同源）
     */
    public static void applyOverrides(Map<String, Object> values, Minecraft mc, float partialTicks) {
        PackUniformSet set = ActivePackUniforms.current();
        // 复位先于空判：换到「没有包」也要清跨帧状态，否则下一张包的同名 smooth 槽位
        // （BSL 用的就是显式 id 1..13）会拿上一张包的旧值当历史。
        resetFor(set);
        if (set.isEmpty()) {
            return;
        }
        PackUniformSet.Outcome outcome = set.evaluate(buildInputs(values, mc, partialTicks));
        // 覆盖方向（包赢内建）与「谁先谁后」都是 OF 语义，放在纯 Java 层里可单测：
        //   PackUniformSet.mergeOverrides。
        PackUniformSet.mergeOverrides(values, outcome);
        warnOnce(set, outcome);
    }

    private static PackUniformInputs buildInputs(Map<String, Object> values, Minecraft mc,
            float partialTicks) {
        PackUniformInputs inputs = new PackUniformInputs(deltaTicks(values), SMOOTH_STATE);
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            feed(inputs, entry.getKey(), entry.getValue());
        }
        inputs.scalar("sunAngle", sunAngle(mc, partialTicks));
        inputs.scalar("blindness", blindness(mc, partialTicks));
        return inputs;
    }

    /** 只喂「能读成数」的内建：矩阵/自定义类型进不了标量表达式，跳过它们不是丢值（表达式引用到时会被点名）。 */
    private static void feed(PackUniformInputs inputs, String name, Object value) {
        if (value instanceof Number number) {
            inputs.scalar(name, number.doubleValue());
        } else if (value instanceof Vector3f vector) {
            inputs.vector(name, new double[] {vector.x, vector.y, vector.z});
        } else if (value instanceof int[] pair) {
            inputs.vector(name, new double[] {pair.length > 0 ? pair[0] : 0, pair.length > 1 ? pair[1] : 0});
        } else if (value instanceof double[] components) {
            inputs.vector(name, components);
        }
    }

    private static double deltaTicks(Map<String, Object> values) {
        Object frameTime = values.get("frameTime");
        double seconds = frameTime instanceof Number number ? number.doubleValue() : 0.0;
        return Math.min(MAX_DELTA_TICKS, Math.max(0.0, seconds * TICKS_PER_SECOND));
    }

    private static double sunAngle(Minecraft mc, float partialTicks) {
        if (mc == null || mc.level == null) {
            return 0.0;
        }
        Float degrees = mc.gameRenderer.mainCamera().attributeProbe()
                .getValue(EnvironmentAttributes.SUN_ANGLE, partialTicks);
        return degrees == null ? 0.0 : PackUniformInputs.sunAngleFromDegrees(degrees);
    }

    private static double blindness(Minecraft mc, float partialTicks) {
        if (mc == null || mc.level == null) {
            return 0.0;
        }
        // 原版自己也是这个形状（BlindnessFogEnvironment.isApplicable 要求实体是 LivingEntity，
        // FogRenderer.java:112-114 才去调 getModifiedDarkness）；base=0 ⇒ 只要盲视那一份，
        // 不掺虚空变暗（FogRenderer.java:111 的那半属于 darknessFactor，不属本条）。
        if (!(mc.gameRenderer.mainCamera().entity() instanceof LivingEntity entity)) {
            return 0.0;
        }
        return new BlindnessFogEnvironment().getModifiedDarkness(entity, 0.0F, partialTicks);
    }

    /** 换包（集合引用变化）⇒ 平滑状态与 WARN 位一起复位：跨包的 {@code smooth} 槽位没有意义。 */
    private static void resetFor(PackUniformSet set) {
        if (lastInstalled == set) {
            return;
        }
        lastInstalled = set;
        SMOOTH_STATE.clear();
        WARNED.clear();
    }

    private static void warnOnce(PackUniformSet set, PackUniformSet.Outcome outcome) {
        List<String> skips = outcome.skips();
        List<String> unresolved = outcome.unresolvedInputs();
        if (skips.isEmpty() && unresolved.isEmpty()) {
            return;
        }
        String fingerprint = set.size() + "/" + skips + "/" + unresolved;
        if (!WARNED.add(fingerprint)) {
            return;
        }
        dev.vkdisp.VkDisp.LOGGER.warn(
                "vkdisp: [GAP-021] pack-authored uniforms partially unevaluated:"
                        + " definitions={} skipped={} unresolvedInputs={} skipReasons={}"
                        + "（这些值按「不供」处理，不落 0；缺失的内建供值见 GAP-007）",
                set.size(), skips.size(), unresolved, skips);
    }
}
