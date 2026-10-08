package dev.vkdisp.render;
/**
 * 【参考调研】P4.1.3 OF 内建 uniform 上传（collect + write）/ 04-SPEC §3.2 上传注记
 * 0. 合规核对（第 0 步闸门，通过）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §3.2 表 +「上传语义（P4.1.3 起…）」注记
 *    （每条值的出处与 X9 取证结论写在文档里）；② 原版 26.3 反编译源码的公开字段/方法语义
 *    （CameraRenderState / SkyRenderer / Level.getClockTime / FogType / LightLayer /
 *    EnvironmentAttributes —— 只观察签名与赋值链，零文本搬运，Mojang EULA 下仅作语义核实）；
 *    ③ 本仓库 UniformCatalog（23 条清单）与 BuiltinsBlockLayout（std140 偏移）。
 *    外部候选 IrisShaders/OptiFine 源码 → 禁止（07-CONSTRAINTS L12 / X19-X21）；
 *    WebSearch 本环境无结果 → timeBrightness 等未取证项一律 0 + 登记（X9 拒猜）。
 *    许可证：本文件为独立编写的纯 Java 实现，不含任何外部项目代码。
 *    → 能否并入本项目（MIT）：可以 —— 只调用公开 API 语义；net.minecraft 引用与
 *      VkDispVirtualPack 同款（业务包允许；T5 红线只禁 com.mojang.renderpearl|blaze3d）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：{@link #gather}（游戏状态 → 名字→值 映射，语义 = 04-SPEC §3.2 上传注记
 *    逐行）+ {@link #write}（映射 + 布局 → ByteBuffer 绝对偏移写入，纯函数单测）。
 *    不碰 GPU：环形缓冲 map/rotate/setUniform 归 bridge/FrameApi（T5：本类零 renderpearl 引用，
 *    写入用裸 ByteBuffer 绝对 put —— 也因为 vanilla Std140Builder 无 seek，块偏移必须自算）。
 * 2. 备选：把值经 Std140Builder 顺序写（否决：收编序 ≠ 目录序，绝对偏移不可顺序重放）；
 *    全零填充照旧（否决：本轮目标就是消掉该缺口 —— sunVec 系 NaN 质量问题）。
 * 3. 我们的差异点：fill 集 = 23 目录 + aspectRatio/timeAngle/moonPhase（BSL 消费点实测），
 *    其余块成员恒 0 并一次性 INFO 列名（timeBrightness 等未取证项 X9 拒猜）；
 *    sun/moon/shadowLight = 原版天空链眼空间方向（非 BSL 包天空 sunPathRotation，P4.2 复审）。
 * 4. 许可证核对结论：本项目 MIT；零第三方代码并入（07-CONSTRAINTS §〇 P1 / L5-L8）。
 * 5. 性能基线：每帧一次 gather（十几次 JOML 小对象 + 查表）+ 一次 write（~26 成员绝对 put），
 *    毫秒级以下；按 task-2 要求不做性能优化（17-NATIVE.md §3.2、T14 达标即停）。
 */
import dev.vkdisp.glsl.translate.BuiltinsBlockLayout;
import dev.vkdisp.shadow.LightSpaceList;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.MoonPhase;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * OF 内建 uniform 的取值与写入（04-SPEC §3.4「上传 §3.2 的内建 uniform」）。
 *
 * <p><b>两段拆分</b>：{@link #gather} 从游戏状态取值成 {@code Map<String, Object>}
 * （值类型 = {@link Matrix4f} / {@link Vector3f} / {@code Float} / {@code Integer} /
 * {@code int[]}，键 = GLSL 成员名）；{@link #write} 按 {@link BuiltinsBlockLayout}
 * 的 std140 绝对偏移把命中成员写进缓冲 —— 纯函数，单测直接构造映射断言字节。
 *
 * <p><b>块成员 ≠ 填充集</b>：块里还有收编来的包私有 uniform（timeBrightness、blindFactor…），
 * 未取证的一律不填（保持缓冲初始零值）并由调用方一次性 INFO 列名（X9：宁可 0 也不猜）。
 */
public final class OfUniformManager {

    /**
     * 一次性 INFO：同一槽位（composite/deferred/final…）只打一次上传摘要。
     *
     * <p>P4.1.4 教训：曾用两个布尔（deferred ? deferredLogDone : compositeLogDone）——
     * 未知槽位（final）会落 else 分支被 composite 标志吞掉，composite 先打过就永远不出 final 行。
     * 改为按槽位名做集合门（新槽位天然各自一次，无需再改门）。
     * 仅渲染线程调用（drawFullscreen 内），普通 HashSet 足够。
     */
    private static final java.util.Set<String> UPLOAD_LOGGED_SLOTS = new java.util.HashSet<>();

    /** frameTimeCounter 累加器（换世界重置；单帧截断防切窗尖峰）。 */
    private static long lastFrameNanos = -1L;
    private static float frameSeconds;
    /** {@code frameTime} 的供值：最近一次**有效**帧间隔（秒）；尖峰帧沿用旧值。 */
    private static float frameDeltaSeconds;
    private static Object lastLevelKey;

    /** frameCounter 自增（跨世界持续）。 */
    private static int frameCounter;

    /** gbufferPrevious* / previousCameraPosition 的历史槽（上一次 gather 的当帧值；首帧前为 null）。 */
    private static org.joml.Matrix4f previousView;
    private static org.joml.Matrix4f previousProjection;
    private static Vector3f previousCamera;
    /** 上一次供值时的世界引用（换世界 ⇒ 历史与当帧对齐；null = 菜单/尚未进世界）。 */
    private static Object previousMatrixLevel;

    private OfUniformManager() {}

    /**
     * 写入结果（调用方一次性 INFO 摘要用）。
     *
     * @param written     按布局写入的成员数
     * @param missing     布局有但填充集没有的成员数（恒 0 值，预期行为）
     * @param mismatched  类型不匹配跳过的成员数（注入期已 WARN 过的声明冲突）
     * @param overflow    偏移超出缓冲容量跳过的成员数（环尺寸 < 块字节，理论不可达）
     * @param missingNames 未填充成员名（截断前全量交给调用方截断打印）
     */
    public record WriteStats(int written, int missing, int mismatched, int overflow,
            List<String> missingNames) {}

    /**
     * 采集本帧内建值（04-SPEC §3.2 上传注记逐条；出处见文档，X9）。
     *
     * @param mc           客户端单例（level / 游戏渲染状态 / 图集）
     * @param width        本 pass 主目标宽高（viewWidth/viewHeight/aspectRatio）
     * @param atlasSize    方块图集 {w,h}（由 bridge 取 —— GpuTexture 是 renderpearl 类型，
     *                     T5 红线业务包不得引用；FrameApi.blockAtlasSize 提供）
     * @param shadowEntries 光空间列表（P3.1；空 = 单位阵）
     * @return 名字 → 值；永不 null
     */
    public static Map<String, Object> gather(Minecraft mc, int width, int height,
            int[] atlasSize, List<LightSpaceList.Entry> shadowEntries) {
        Map<String, Object> values = new HashMap<>();
        LevelRenderState levelState = mc.gameRenderer.gameRenderState().levelRenderState;
        CameraRenderState camera = levelState.cameraRenderState;
        SkyRenderState sky = levelState.skyRenderState;
        boolean inWorld = mc.level != null && camera.initialized;

        // ---- 视图 / 投影（世界内=原版同参；菜单=与 FrameApi.placeholderCamera 同参） ----
        Matrix4f view;
        Matrix4f projection;
        if (inWorld) {
            view = new Matrix4f(camera.viewRotationMatrix)
                    .translate((float) -camera.pos.x, (float) -camera.pos.y, (float) -camera.pos.z);
            projection = new Matrix4f(camera.projectionMatrix);
        } else {
            view = new Matrix4f().lookAt(
                    new Vector3f(0.0F, 0.0F, -2.5F),
                    new Vector3f(0.0F, 0.0F, 0.0F),
                    new Vector3f(0.0F, 1.0F, 0.0F));
            projection = new Matrix4f().perspective(
                    (float) Math.toRadians(60.0), width / (float) height, 0.1F, 32.0F, true);
        }
        values.put("gbufferModelView", view);
        values.put("gbufferProjection", projection);
        values.put("gbufferModelViewInverse", inverted(view));
        values.put("gbufferProjectionInverse", inverted(projection));
        Vector3f cameraPos = inWorld
                ? new Vector3f((float) camera.pos.x, (float) camera.pos.y, (float) camera.pos.z)
                : new Vector3f();
        values.put("cameraPosition", cameraPos);
        // ---- 上一帧相机（TAA / 运动模糊 / DOF 聚焦的输入）----
        // 🔖 此前这三项在 builtins 上传自报里长期是 `unfilled`（恒 0）⇒ 包把「上一帧」当成
        //   「相机在原点、矩阵是单位阵」⇒ 运动向量 = 整屏假位移 ⇒ 时序混合把画面往错误的
        //   历史帧上抹（h48 判读暗帧时的候选之一，先按公开语义把值供上，再按画面判）。
        // 🔖 「上一帧」= **上一次 gather**（链模式每帧一次）。换世界时对齐成当帧：
        //   跨维度/重载之后的旧相机没有意义，喂它 = 让包拿上一张地图的相机当历史。
        boolean worldSwitch = mc.level != previousMatrixLevel;
        previousMatrixLevel = mc.level;
        values.put("gbufferPreviousModelView",
                worldSwitch || previousView == null ? view : previousView);
        values.put("gbufferPreviousProjection",
                worldSwitch || previousProjection == null ? projection : previousProjection);
        values.put("previousCameraPosition",
                worldSwitch || previousCamera == null ? cameraPos : previousCamera);
        previousView = view;
        previousProjection = projection;
        previousCamera = cameraPos;
        values.put("near", inWorld ? Camera.PROJECTION_Z_NEAR : 0.1F);
        values.put("far", inWorld ? camera.depthFar : 32.0F);
        values.put("viewWidth", (float) width);
        values.put("viewHeight", (float) height);

        // ---- 阴影矩阵（P3.1 LightSpaceList 首级联；空列表 = 单位阵） ----
        if (shadowEntries != null && !shadowEntries.isEmpty()) {
            LightSpaceList.Entry first = shadowEntries.getFirst();
            values.put("shadowModelView", new Matrix4f(first.shadowModelView()));
            values.put("shadowProjection", new Matrix4f(first.shadowProjection()));
        } else {
            values.put("shadowModelView", new Matrix4f());
            values.put("shadowProjection", new Matrix4f());
        }

        // ---- 天体方向（眼空间，原版天空链；缩放无关性论证见 04-SPEC 上传注记） ----
        // X9（p413 run1 实测反推）：角度/月相/雨量**直读 attributeProbe 与 Level** ——
        // SkyRenderState 字段只在 LevelExtractor 跑过后才有效，首帧/加载帧为默认 0
        // （rainStrength 曾误报 1.0；天气实为晴：weather.dat raining=0 + advance_weather=0）。
        // probe 即 SkyRenderer:119-125 自己的取值源（度 × π/180），故语义逐位等价。
        // 世界向量 Ry(−90°)·Rx(θ)·(0,1,0) = (−sinθ, cosθ, 0) —— 与 SkyRenderer 的
        // rotateDegrees(YP,−90) + rotate(XP,θ) 同链，晨东/午顶/昏西三点校验过。
        EnvironmentAttributeProbe probe = mc.gameRenderer.mainCamera().attributeProbe();
        float partialTicks = levelState.worldPartialTicks;
        float sunAngle = inWorld ? probeAngle(probe, EnvironmentAttributes.SUN_ANGLE, partialTicks) : 0.0F;
        float moonAngle = inWorld ? probeAngle(probe, EnvironmentAttributes.MOON_ANGLE, partialTicks) : 0.0F;
        Vector3f sunWorld = new Vector3f(-Mth.sin(sunAngle), Mth.cos(sunAngle), 0.0F);
        Vector3f moonWorld = new Vector3f(-Mth.sin(moonAngle), Mth.cos(moonAngle), 0.0F);
        Vector3f sunEye = eyeDirection(camera.viewRotationMatrix, sunWorld);
        Vector3f moonEye = eyeDirection(camera.viewRotationMatrix, moonWorld);
        values.put("sunPosition", sunEye);
        values.put("moonPosition", moonEye);
        values.put("shadowLightPosition",
                new Vector3f(sunWorld.y > 0.0F ? sunEye : moonEye));

        // ---- 时间（OF 级秒/帧计数 + 日时钟；26.3 无 getDayTime → getDefaultClockTime） ----
        long clock = inWorld ? mc.level.getDefaultClockTime() : 0L;
        int worldTime = (int) Math.floorMod(clock, 24000L);
        values.put("worldTime", worldTime);
        values.put("worldDay", (int) Math.floorDiv(clock, 24000L));
        values.put("timeAngle", worldTime / 24000.0F);
        values.put("aspectRatio", width / (float) height);
        values.put("frameTimeCounter", tickFrameTime(mc, inWorld));
        // frameTime = 上一帧耗时（OF 公开语义）。🔖 这条是 BSL 白屏前线的机制位：
        //   包里 `exp2(-frameTime * SPEED)` 是**时序混合系数**，恒 0 ⇒ 混合系数恒 1 ⇒
        //   混合永远返回「旧值」，而旧值就是缓冲初始 0 ⇒ 自动曝光 colortex2.r 卡在 0 ⇒
        //   `color /= 2*0 + 0.125` = 固定 ×8 增益 ⇒ 整屏削顶（evidence/h47）。
        //   尖峰帧（>0.5s，切窗/暂停）不更新 ⇒ 沿用上一次的有效步长，而不是回 0 卡死。
        values.put("frameTime", frameDeltaSeconds);
        int frameNo = ++frameCounter;
        values.put("frameCounter", frameNo);
        // 🔖 QD-02：`vkdisp.debugLog` 的**真实消费点之一**。此前该开关只有定义与热重载快照、
        //   **零消费点**（`grep DEBUG_LOG` 只有 2 处命中）⇒ 开关它没有任何可观察效果，比没有更误导。
        //   这里报「本帧实际写进了哪些键」，用于排查 uniform 缺失（静默失败的头号来源）。
        //   ⚠️ **必须节流**：本方法每帧都跑，无节流的 INFO 会把热路径变成 I/O 瓶颈。
        if (dev.vkdisp.VkDispConfig.DEBUG_LOG.get() && frameNo % 300 == 0) {
            dev.vkdisp.VkDisp.LOGGER.info(
                    "vkdisp: [qd-02] ofUniform keys={} frame={} firstKeys={}",
                    values.size(), frameNo, new java.util.ArrayList<>(values.keySet()).subList(0, 6));
            // 🔬 GAP-022 矩阵那一半的**取证行**。登记表明确要求：只能用**游戏里真实**的那一对矩阵，
            //   不能用我方重构造的矩阵 —— 上一轮的往返检查正是这样自毁的
            //   （`gl⁻¹·(ndc,1)` 那类恒等式在 `m32 ≠ ±1` 时不成立）。
            //   这里把**真正喂给包的那一对**逐元素打出来，配 near/far 与窗口深度读数，
            //   于是「翻深度」与「翻矩阵」能否用同一条式子做完，可以用数字判而不是猜。
            dev.vkdisp.VkDisp.LOGGER.info("vkdisp: [GAP-022/matrix] frame={} inWorld={}"
                            + " projection={} projectionInverse={} windowDepth@d1/16/128={}",
                    frameNo, inWorld, flat(projection), flat(inverted(projection)),
                    GlDepthConvention.windowDepth(projection, 1.0F)
                            + "/" + GlDepthConvention.windowDepth(projection, 16.0F)
                            + "/" + GlDepthConvention.windowDepth(projection, 128.0F));
        }
        MoonPhase moonPhase = inWorld
                ? probe.getValue(EnvironmentAttributes.MOON_PHASE, partialTicks) : null;
        values.put("moonPhase", (moonPhase != null ? moonPhase : sky.moonPhase).index());

        // ---- 天气（v1：wetness = rainStrength，OF 级平滑登记；菜单恒 0） ----
        // rainLevel 直读 Level（= SkyRenderer:122 的 1 − rainBrightness 恒等变形，等价
        // 但不依赖 SkyRenderState 提取态）。
        float rainStrength = inWorld ? mc.level.getRainLevel(partialTicks) : 0.0F;
        values.put("rainStrength", rainStrength);
        values.put("wetness", rainStrength);
        values.put("isEyeInWater", eyeInWater(camera));
        // cloudHeight：✅ 公开路径核实（26.3.0.51-beta）
        //   `LevelRenderState.cloudHeight` 是 **public float**（源码第 28 行），
        //   原版自己在 `LevelRenderer:554/563` 就把它当云层高度传给云渲染。
        //   取法与天空重放同源：`gameRenderer.gameRenderState().levelRenderState`。
        //   为什么值得补：BSL 的体积云 `DrawCloudVolumetric` 用它当云层底高，
        //   恒 0 ⇒ 云层被压到海平面以下 ⇒ 一个像素都看不见（GAP-007 清单项）。
        values.put("cloudHeight", inWorld ? levelState.cloudHeight : 0.0F);
        // bedrockLevel / nightVision：取值隔离在 `NightVisionSupply`（实体/注册表类在测试
        // 运行时里会 NoClassDefFoundError），语义出处与那条「不能直接喂 nightVisionScale，
        // 否则 BSL 的 `*= 1.0 + nightVision` 会变全屏永久 ×2」的陷阱写在那个类的注释里。
        values.put("bedrockLevel", inWorld ? NightVisionSupply.bedrockLevel(mc) : 0.0F);
        values.put("nightVision", NightVisionSupply.value(mc, partialTicks));

        // ---- 图集 / 眼亮度（亮度为近似 v1，见 04-SPEC 上传注记登记） ----
        values.put("atlasSize", new int[] {
                atlasSize == null || atlasSize.length < 2 ? 0 : atlasSize[0],
                atlasSize == null || atlasSize.length < 2 ? 0 : atlasSize[1]});
        values.put("eyeBrightnessSmooth", inWorld
                ? eyeBrightness(mc, camera, levelState)
                : new int[] {0, 0});
        // 🔴 GAP-021：包自写的 `uniform.<类型>.<名>=<表达式>` 必须在**最后**并入 ——
        //   OF 语义是包覆盖同名内建（BSL 就自己写了 `uniform.float.timeAngle`，
        //   见 BSL_v10.1.8 shaders.properties:151），反过来把我方内建压上去就等于
        //   「包作者写的时间曲线被引擎的朴素曲线替换掉」，画面表现为昼夜过渡生硬但日志正常。
        //   表达式求值本体在纯 Java 的 pack/uniform/（无 Minecraft 类型，可单测）；
        //   本类只在渲染线程调它，Minecraft 侧的补充供值（sunAngle/blindness）隔离在
        //   PackUniformSupply（实体类会炸测试车道，同 NightVisionSupply 那条理由）。
        PackUniformSupply.applyOverrides(values, mc, partialTicks);
        return values;
    }

    /**
     * 按布局把命中成员写进缓冲（纯函数：不改 layout/values，只写 dst）。
     *
     * <p>绝对偏移 + 原生字节序（vanilla Std140Builder 无 seek，顺序重放在收编序下必错 ——
     * 本方法即为此设计）。类型不匹配跳过（注入期 recordDeclaration 已 WARN 过同名异型）。
     *
     * @param layout 块布局（null/空 = 什么都不写）
     * @param values 填充集（null 按空映射）
     * @param dst    目标缓冲（容量 < 成员末端的条目计入 overflow 跳过，绝不越界写）
     * @return 写入统计；永不 null
     */
    public static WriteStats write(BuiltinsBlockLayout layout, Map<String, Object> values,
            ByteBuffer dst) {
        if (layout == null || layout.isEmpty() || dst == null) {
            return new WriteStats(0, 0, 0, 0, List.of());
        }
        dst.order(ByteOrder.nativeOrder());
        int written = 0;
        int missing = 0;
        int mismatched = 0;
        int overflow = 0;
        List<String> missingNames = new java.util.ArrayList<>();
        Map<String, Object> map = values == null ? Map.of() : values;
        for (BuiltinsBlockLayout.Member member : layout.members()) {
            if (member.offset() + member.size() > dst.capacity()) {
                overflow++;
                continue;
            }
            Object value = map.get(member.name());
            if (value == null) {
                missing++;
                missingNames.add(member.name());
                continue;
            }
            if (put(dst, member.offset(), member.type(), value)) {
                written++;
            } else {
                mismatched++;
            }
        }
        return new WriteStats(written, missing, mismatched, overflow,
                List.copyOf(missingNames));
    }

    /**
     * 按成员类型把一个值写到绝对偏移（返回 false = 类型不匹配 → 跳过并计数）。
     *
     * <p>覆盖填充集实际用到的类型：float（Number 宽化）、int/uint（Number）、bool、
     * vec3（Vector3f）、ivec2（int[]，atlasSize/eyeBrightnessSmooth）、mat4
     * （Matrix4f → {@code get(float[16])} 列主序 = std140 列主序）。其余类型无填充值，
     * 走不到这里；万一走到 = 该成员被声明成异型（注入期已 WARN），如实计 mismatched。
     */
    private static boolean put(ByteBuffer dst, int offset, String type, Object value) {
        switch (type) {
            case "float" -> {
                if (value instanceof Number number) {
                    dst.putFloat(offset, number.floatValue());
                    return true;
                }
            }
            case "int", "uint" -> {
                if (value instanceof Number number) {
                    dst.putInt(offset, number.intValue());
                    return true;
                }
            }
            case "bool" -> {
                if (value instanceof Boolean bool) {
                    dst.putInt(offset, bool ? 1 : 0);
                    return true;
                }
                if (value instanceof Number number) {
                    dst.putInt(offset, number.intValue());
                    return true;
                }
            }
            case "vec3" -> {
                if (value instanceof Vector3f vector) {
                    dst.putFloat(offset, vector.x);
                    dst.putFloat(offset + 4, vector.y);
                    dst.putFloat(offset + 8, vector.z);
                    return true;
                }
            }
            case "ivec2" -> {
                if (value instanceof int[] pair && pair.length >= 2) {
                    dst.putInt(offset, pair[0]);
                    dst.putInt(offset + 4, pair[1]);
                    return true;
                }
            }
            case "mat4" -> {
                if (value instanceof Matrix4f matrix) {
                    float[] elements = new float[16];
                    matrix.get(elements);
                    for (int i = 0; i < 16; i++) {
                        dst.putFloat(offset + i * 4, elements[i]);
                    }
                    return true;
                }
            }
            default -> {
                return false;
            }
        }
        return false;
    }

    /** 一次性上传摘要（每槽各一次；T11：填没填都要在日志里看得见）。 */
    public static void logUploadOnce(String slot, BuiltinsBlockLayout layout,
            WriteStats stats, Map<String, Object> values) {
        if (!UPLOAD_LOGGED_SLOTS.add(slot)) {
            return;
        }
        List<String> missing = stats.missingNames();
        // 🔖 截断阈值 16（原 8）：h47 的 `frameTime` 恰好落在被截掉的后半段，
        //   于是「哪个内置量没供值」这个**决定性的问题**在日志里看不见 —— 判读面被截断 = 假证据。
        String shown = missing.size() <= 16
                ? missing.toString()
                : missing.subList(0, 16) + " …+" + (missing.size() - 16);
        dev.vkdisp.VkDisp.LOGGER.info(
                "vkdisp: builtins uploaded: slot={} members={} bytes={} written={} unfilled={}"
                        + " mismatched={} overflow={} sample={{far={}, worldTime={},"
                        + " frameTimeCounter={}, frameTime={}, rainStrength={}}} unfilledNames={}"
                        + " packAuthored={} count={}",
                slot, layout.members().size(), layout.byteSize(),
                stats.written(), stats.missing(), stats.mismatched(), stats.overflow(),
                values.get("far"), values.get("worldTime"),
                values.get("frameTimeCounter"), values.get("frameTime"),
                values.get("rainStrength"), shown,
                packOverrideSummary(values), packOverrideCount(values));
    }

    /**
     * GAP-021 判据里那条「自报行要出现 {@code shadowFade=/timeBrightness=} 的非 0 值」。
     *
     * <p>🔖 为什么单独报：包自写的值走的是同一张映射、同一个块，缺了这行就只能靠画面反推
     * 「表达式到底跑没跑」（QD-02 那一族：数字在场但看不出是谁供的）。
     * 只列**本帧真有值**的名字（被跳过的由 {@code PackUniformSupply} 的一次性 WARN 负责），
     * 数量另行给出 ⇒ 截断不会藏掉决定性条目（QD-02 的 16 阈值教训）。
     */
    private static String packOverrideSummary(Map<String, Object> values) {
        List<String> names = dev.vkdisp.pack.uniform.ActivePackUniforms.current().uniformNames();
        StringBuilder out = new StringBuilder();
        for (String name : names) {
            Object value = values.get(name);
            if (value == null) {
                continue;
            }
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(name).append('=').append(value);
        }
        return out.length() == 0 ? "-" : out.toString();
    }

    private static int packOverrideCount(Map<String, Object> values) {
        int count = 0;
        for (String name : dev.vkdisp.pack.uniform.ActivePackUniforms.current().uniformNames()) {
            if (values.get(name) != null) {
                count++;
            }
        }
        return count;
    }

    // ----------------------------------------------------------------------------------
    // 私有：逐值语义（04-SPEC §3.2 上传注记；每条出处写在那里，此处只写实现）
    // ----------------------------------------------------------------------------------

    /** 逆矩阵（奇异 → 单位阵；JOML 1.10.9 的 invert() 无布尔返回，先查行列式防除零）。 */
    private static Matrix4f inverted(Matrix4f matrix) {
        Matrix4f result = new Matrix4f(matrix);
        float determinant = result.determinant();
        if (determinant == 0.0F || Float.isNaN(determinant)) {
            result.identity();
        } else {
            result.invert();
        }
        return result;
    }

    /**
     * GAP-022 取证用：把矩阵按**行**摊平成一行可读文本（走 {@code get(row, col)} 显式点名行列，
     * 不用 {@code get(float[])} —— 那个的行列序还要先查，而本行的全部意义就是让元素序没有歧义）。
     */
    private static String flat(Matrix4f m) {
        StringBuilder sb = new StringBuilder(128);
        for (int row = 0; row < 4; row++) {
            sb.append(row == 0 ? "{r0=[" : ", r" + row + "=[");
            for (int col = 0; col < 4; col++) {
                sb.append(col == 0 ? "" : ", ").append(m.get(row, col));
            }
            sb.append(']');
        }
        return sb.append("]}").toString();
    }

    /**
     * 探针角度（度 → 弧度；式 = SkyRenderer:119 逐位同款）。无值（菜单 / 未同步）→ 0。
     * 直读 probe 而非 SkyRenderState —— 后者字段在提取前为默认值（p413 run1 实测）。
     */
    private static float probeAngle(EnvironmentAttributeProbe probe,
            EnvironmentAttribute<Float> attribute, float partialTicks) {
        Float degrees = probe.getValue(attribute, partialTicks);
        return degrees == null ? 0.0F : degrees * ((float) Math.PI / 180.0F);
    }

    /** 世界方向 → 眼空间单位方向（viewRotation 正交，变换后归一防数值漂移）。 */
    private static Vector3f eyeDirection(Matrix4f viewRotation, Vector3f worldDirection) {
        Vector3f eye = new Vector3f(worldDirection);
        new Matrix3f(viewRotation).transform(eye);
        float length = eye.length();
        if (length > 1.0e-6F) {
            eye.div(length);
        }
        return eye;
    }

    /**
     * frameTimeCounter：wall-clock 秒累加。换世界（level 引用变化）重置到 0；
     * 单帧增量 >0.5s（切窗 / 暂停恢复）截断，避免包内动画瞬移。
     */
    private static float tickFrameTime(Minecraft mc, boolean inWorld) {
        long now = System.nanoTime();
        Object levelKey = inWorld ? mc.level : null;
        if (levelKey != lastLevelKey) {
            lastLevelKey = levelKey;
            frameSeconds = 0.0F;
            lastFrameNanos = now;
            return frameSeconds;
        }
        if (lastFrameNanos >= 0L) {
            float delta = (now - lastFrameNanos) / 1.0e9F;
            if (delta > 0.0F && delta < 0.5F) {
                frameSeconds += delta;
                frameDeltaSeconds = delta;
            }
        }
        lastFrameNanos = now;
        return frameSeconds;
    }

    /** isEyeInWater：fogType → 0/1/2/3（FogType 枚举 5 值，ATMOSPHERIC/NONE → 0）。 */
    private static int eyeInWater(CameraRenderState camera) {
        FogType fog = camera.fogType;
        if (fog == null) {
            return 0;
        }
        return switch (fog) {
            case WATER -> 1;
            case LAVA -> 2;
            case POWDER_SNOW -> 3;
            default -> 0;
        };
    }

    /**
     * eyeBrightnessSmooth 近似 v1（登记项：OF 精确曲线与帧平滑未取证，X9 拒猜）：
     * x = eye 处方块光 ×16；y = 天空光 × SKY_LIGHT_FACTOR（环境属性 probe，0..1 因子）
     * ×16 后 round；两分量各 clamp 到 0..240（15×16 = OF 上限）。
     */
    private static int[] eyeBrightness(Minecraft mc, CameraRenderState camera,
            LevelRenderState levelState) {
        BlockPos eye = BlockPos.containing(camera.pos.x, camera.pos.y, camera.pos.z);
        int block = mc.level.getBrightness(LightLayer.BLOCK, eye);
        int sky = mc.level.getBrightness(LightLayer.SKY, eye);
        Float skyFactor = mc.gameRenderer.mainCamera().attributeProbe().getValue(
                net.minecraft.world.attribute.EnvironmentAttributes.SKY_LIGHT_FACTOR,
                levelState.worldPartialTicks);
        int x = Mth.clamp(block, 0, 15) * 16;
        float factor = skyFactor == null ? 1.0F : Mth.clamp(skyFactor, 0.0F, 1.0F);
        float scaledSky = Mth.clamp(sky, 0, 15) * factor * 16.0F;
        int y = Mth.clamp(Math.round(scaledSky), 0, 240);
        return new int[] {x, y};
    }
}
