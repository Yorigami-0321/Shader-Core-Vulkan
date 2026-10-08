package dev.vkdisp.pack.uniform;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 求值输入（引擎内建 + 跨帧状态）。
 *
 * <p>🔖 <b>为什么只收 {@code double} 与 {@code double[]} </b>：本包必须能被 JUnit 直接跑
 * （{@code OfUniformManagerTest} 那条测试车道里 {@code LivingEntity}/{@code MobEffects}
 * 这类注册表引导会 {@code NoClassDefFoundError} —— 本会话已实测踩过一次，
 * 见 {@code render/NightVisionSupply} 的隔离理由）。把 Minecraft / joml 类型挡在边界之外，
 * 求值器才能既**进渲染路径**又**进单测**。类型转换在
 * {@code dev.vkdisp.render.PackUniformSupply} 做完再进来。
 *
 * <p>🔖 <b>为什么缺值是 {@code null} 而不是 0</b>：X9。取不到 `biome` 时把表达式里的
 * {@code biome} 当 0 算，会产出「看起来是数、实际是假」的 uniform（BSL 的
 * {@code isDesert} 会变成 0 = 不在沙漠，而日志一切正常）—— 正是 GAP-021 要消掉的那类缺口。
 * 缺值必须让整条表达式跳过并被点名（见 {@link PackUniformSet#evaluate}）。
 */
public final class PackUniformInputs {

    private final Map<String, Double> scalars = new LinkedHashMap<>();
    private final Map<String, double[]> vectors = new LinkedHashMap<>();
    private final Map<String, Double> smoothState;
    private final double deltaTicks;

    /**
     * @param deltaTicks  距上一次求值的间隔（游戏刻，20 刻 = 1 秒）；{@code smooth()} 的半衰期按它算
     * @param smoothState 跨帧平滑状态（调用方持有 ⇒ 换包/重载时跟着换；渲染线程独占，不做并发）
     */
    public PackUniformInputs(double deltaTicks, Map<String, Double> smoothState) {
        this.deltaTicks = deltaTicks;
        this.smoothState = smoothState == null ? new HashMap<>() : smoothState;
    }

    /** 标量输入（引擎内建或先前定义的 variable/uniform）。 */
    public PackUniformInputs scalar(String name, double value) {
        scalars.put(name, value);
        return this;
    }

    /** 向量输入（只允许 {@code double[]} 形态，取 {@code .x/.y/.z/.w} 用）。 */
    public PackUniformInputs vector(String name, double[] components) {
        vectors.put(name, components == null ? new double[0] : components.clone());
        return this;
    }

    public double deltaTicks() {
        return deltaTicks;
    }

    /** 标量读数；未提供 → {@code null}（调用方据此点名，绝不返回 0）。 */
    Double scalar(String name) {
        return scalars.get(name);
    }

    /** 向量分量读数；未提供该向量 → {@code null}。 */
    Double vectorComponent(String name, int element) {
        double[] components = vectors.get(name);
        if (components == null) {
            return null;
        }
        return element < components.length ? components[element] : null;
    }

    /** 该名字是否以**向量**形态存在（用来把「把 vec3 当标量用」报成类型错而不是「未解析输入」）。 */
    boolean isVector(String name) {
        return vectors.containsKey(name);
    }

    Double smoothPrevious(String slot) {
        return smoothState.get(slot);
    }

    void smoothStore(String slot, double value) {
        smoothState.put(slot, value);
    }

    /**
     * OF 内建 {@code sunAngle}（0..1）—— 由原版太阳角（度）换算。
     *
     * <p>✅ 语义出处（两处独立核实，互相印证）：
     * <ul>
     *   <li>OF/Iris 公开文档《uniforms · Sun angle》：「A float, ranging from 0.0 to 1.0 …
     *       0.0 = 太阳在**东**地平线、0.25 = 正上方、0.5 = 太阳在**西**地平线、0.75 = 月亮在正上方」，
     *       并给出算法 {@code sunAngle = celestialAngle < 0.75 ? celestialAngle + 0.25 : celestialAngle - 0.75}
     *       （{@code celestialAngle} 以 0.0 = 正午）⇒ 即 {@code frac(celestialAngle + 0.25)}；</li>
     *   <li>原版 26.3.0.51-beta 源码：{@code EnvironmentAttributes.SUN_ANGLE} 是
     *       {@code ANGLE_DEGREES} 类型（{@code EnvironmentAttributes.java:74-76}），
     *       原版本身就这么用（{@code SkyRenderer.java:119} 度→弧度、
     *       {@code DaylightDetectorBlock.java:49} 同一条式子），
     *       且 {@code Timelines.java:53} 的过场轨道把 SUN_ANGLE 的两个关键帧钉在
     *       {@code 6000}（= 正午）处的 360/0 ⇒ 正午 ↔ 0°（≡360°）。</li>
     * </ul>
     * 正午 θ=0 ⇒ {@code frac(0/360 + 0.25) = 0.25} ✅ 与文档「0.25 = 正上方」逐位一致；
     * 又因本仓库已核实的太阳世界向量为 {@code (−sinθ, cosθ, 0)}（04-SPEC §3.2 上传注记，
     * 晨东/午顶/昏西三点校验），θ=−90°(=270°) 落在 +X=东 ⇒ {@code frac(270/360+0.25)=0.0}
     * ✅ 文档的「0.0 = 东地平线」也吻合 —— 两个方向都自洽才敢用。
     */
    public static double sunAngleFromDegrees(double degrees) {
        return fractionalCycle(degrees / 360.0 + 0.25);
    }

    /** 归一到 [0,1)（{@code floor} 口径 ⇒ 负角也落在环上，与 OF 的「绕一圈」同义）。 */
    static double fractionalCycle(double value) {
        return value - Math.floor(value);
    }
}
