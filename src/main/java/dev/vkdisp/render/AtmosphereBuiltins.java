package dev.vkdisp.render;
/**
 * 【自行补充】GAP-029：链上那几个「包当引擎会给、我方从来没给」的内建量的**取值**。
 *
 * <p><b>怎么掉出来的</b>：h50f 用四臂把「每 3 帧一帧整帧黑」的闸门钉到 {@code deferred1} 的
 * 云混合（{@code CLOUDS=0 ⇒ 0/171 帧为 0}，默认档 33.8%）。顺着云去查它读什么，
 * 把 {@code BSL_v10.1.8} 链上程序的 {@code uniform} 声明与 {@code OfUniformManager} 的
 * {@code values.put} 集合做差 ⇒ 19 个名字我方没供。其中 4 个是承重的，两个直接决定
 * 「太阳还在不在」：
 * <ul>
 *   <li>{@code shadowFade}：{@code lightShafts.glsl:162 totalShadow *= … * shadowFade}、
 *       {@code sunmoon.glsl:111 visibility *= shadowFade * LIGHT_SHAFT_STRENGTH} ⇒ 恒 0 =
 *       <b>光柱整条被乘没</b>；{@code forwardLighting.glsl:76}、{@code ggx.glsl:136}（高光）
 *       同一族；{@code clouds.glsl:205/223/226} 的受光走「没有太阳」那一支。</li>
 *   <li>{@code timeBrightness}：{@code sky.glsl:11 exposure = exp2(timeBrightness*0.75-0.75+…)}
 *       ⇒ 少 2 档曝光；{@code deferred1.glsl:478 sunColor = mix(lightMA, …, timeBrightness)}
 *       ⇒ <b>正午按午夜配色</b>。⇒ 这两条合起来就是 h50d 画面里那片暗绿的云最省事的解释方向。</li>
 * </ul>
 *
 * <p><b>为什么单独立一个类</b>（与 {@link NightVisionSupply} 同一条理由，不是整洁）：
 * {@code OfUniformManagerTest} 只测 {@code write(...)} 那条纯函数路径，把
 * {@code ClientLevel#getSkyDarken()} / {@code Options#gamma()} 的读取写进
 * {@code OfUniformManager} 会把原版运行态拖进测试类加载。⇒ 原版读取隔离在本类。
 *
 * <p><b>语义核实</b>（26.3.0.51-beta 源码级；✅ = 核实到行，⚠️ = 我方口径）：
 * <ul>
 *   <li>✅ {@code Level#getSkyDarken()} 是 public（{@code Level.java:934-937}），且
 *       {@code ClientLevel} 自己就调 {@code updateSkyBrightness()}（第 265、303 行）⇒ 客户端
 *       这个值是活的，不是服务端专属；其定义逐字
 *       {@code skyDarken = (int)(15.0F - environmentAttributes().getDimensionValue(SKY_LIGHT_LEVEL))}
 *       （{@code Level.java:653}）⇒ 白天 0、夜晚 11。</li>
 *   <li>⚠️ <b>归一化是我方口径</b>：{@code timeBrightness = clamp((11 - skyDarken) / 11, 0, 1)}
 *       取的是「白天 = 1、夜晚 = 0」这个包内用法能读通的形状（{@code dfade}、{@code sunColor}
 *       两支都按 0..1 的「日照强度」用）。Iris 的实现本轮<b>未取到源码</b>（X9：不许猜），
 *       所以自报行必须把口径说出口，理由同 GAP-028 的 {@code numbering=vkdisp-own-ABI}。</li>
 *   <li>✅ {@code Options#gamma()} 返回 {@code OptionInstance<Double>}
 *       （{@code client/Options.java:1475}），取值域 [-1,1] ⇒ {@code *0.5+0.5} 映到 [0,1]。</li>
 *   <li>⚠️ {@code shadowFade = 1 - rainStrength} 的依据是<b>包内用法</b>（它总是与
 *       {@code (1.0 - 0.95 * rainStrength)} 这类项<b>并乘</b>，语义是「太阳直射还剩多少」），
 *       不是 Iris 源码。</li>
 *   <li>❌ <b>{@code centerDepthSmooth} 本轮不供</b>：它要的是屏幕中心深度的<b>时域</b>平滑，
 *       我方既没有那条历史，也没有中心像素的 CPU 读数（回读深度这条通道本机不可信 ——
 *       {@code evidence/h48 §十四}）⇒ 宁可留在 {@link #DECLARED_UNSUPPLIED} 里点名，
 *       也不塞一个看起来像的值（X11）。</li>
 * </ul>
 */
import java.util.List;

/** 只在渲染线程调用；本类全是纯函数（见文件末尾那条「不许 import 原版类型」）。 */
public final class AtmosphereBuiltins {

    /** 夜晚的 {@code skyDarken}（= 15 − 4）：低于这个值不再往下压 {@code timeBrightness}。 */
    static final int SKY_DARKEN_NIGHT = 11;

    /** 白天 → 1.0、夜晚 → 0.0；口径见类头 ⚠️ 那一条。 */
    public static float timeBrightness(int skyDarken) {
        return clamp01((SKY_DARKEN_NIGHT - skyDarken) / (float) SKY_DARKEN_NIGHT);
    }

    /** 晴 → 1.0、暴雨 → 0.0（包内它总是与雨项并乘，见类头）。 */
    public static float shadowFade(float rainStrength) {
        return clamp01(1.0F - rainStrength);
    }

    /** 原版 gamma ∈ [-1,1] → [0,1]。 */
    public static float screenBrightness(double gamma) {
        return clamp01((float) (gamma * 0.5 + 0.5));
    }

    private static float clamp01(float v) {
        return v < 0.0F ? 0.0F : (v > 1.0F ? 1.0F : v);
    }

    /**
     * 「链上声明了、我方<b>显式</b>不供」的名字清单（登记 ≠ 忘掉）。
     *
     * <p>分两类理由，都在 GAP-029 的登记表行里：① 按包设计 0 就是安全缺省
     * （{@code blindFactor} 一族：BSL 逐字 {@code if (blindFactor > 0.0 || …)} 才乘）；
     * ② 属于没接的外挂（{@code dh*} = Distant Horizons、{@code vx*} = Voxy，两侧都有
     * {@code #ifdef} 门）。{@code centerDepthSmooth} 是第三类：<b>想供但供不了</b>，见类头 ❌。
     */
    public static List<String> declaredUnsupplied() {
        return DECLARED_UNSUPPLIED;
    }

    private static final List<String> DECLARED_UNSUPPLIED = List.of(
            "blindFactor", "darknessFactor", "darknessLightFactor",
            "endFlashIntensity", "endFlashPosition",
            "centerDepthSmooth",
            "dhFarPlane", "dhNearPlane", "dhProjection", "dhProjectionInverse",
            "dhPreviousProjection", "dhRenderDistance",
            "vxProj", "vxProjInv", "vxProjPrev", "vxRenderDistance");

    /**
     * 🔴 <b>本类刻意一个原版类型都不 import</b>（连 {@code Minecraft} 都不行）。
     * 理由不是整洁：测试源集<b>没有</b> Minecraft 类路径，而 javac 做<b>重载决议</b>时就要
     * 加载形参类型 —— 一旦这里出现 {@code timeBrightness(Minecraft)} 这种重载，
     * {@code AtmosphereBuiltinsTest} 里逐字 {@code timeBrightness(0)} 就会报
     * {@code error: cannot access Minecraft}（本轮实测）。⇒ 读 {@code mc.level}/{@code mc.options}
     * 这两步留在 {@code OfUniformManager#gather} 里（它本来就在读 {@code mc.level.getRainLevel}），
     * 本类只留纯函数。
     */
    private AtmosphereBuiltins() {
    }
}
