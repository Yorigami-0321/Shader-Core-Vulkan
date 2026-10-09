package dev.vkdisp.bridge;
/**
 * 【参考调研】P0.3 全屏绘制 / 原版 renderpearl 渲染通道 API + NeoForge 帧事件时机
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 原版 Minecraft 26.3 客户端 com.mojang.renderpearl.api.commands.*
 *    （CommandEncoder / RenderPass）与 com.mojang.blaze3d.systems.RenderSystem（运行平台与官方 API 提供方）。
 *    许可证：Mojang EULA（原版）→ 只观察 javap 签名与官方调用点（原版 PostPass 的 pass 执行序列），
 *    零源码文本搬运；参考模组（VulkanMod / Sulkan 等）零接触。
 *    → 能否并入本项目（MIT）：可以 —— 本文件是独立实现的薄封装，只调用公开 API，不含被参考方代码
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：原版 PostPass#addToFrame 的 pass 执行序列（createRenderPass(label, colorView,
 *    Optional.empty(), depth|null, OptionalDouble.empty()) → setPipeline(getCompiledPipeline) →
 *    bindDefaultUniforms → draw(3,1,0,0)，无顶点绑定；全屏三角形顶点由 gl_VertexIndex 推出）；
 *    触发时机 = NeoForge RenderLevelStageEvent.AfterLevel —— GameRenderer#renderLevel 内
 *    LevelRenderer.render() 返回之后（帧图已执行 → SceneCaptureApi 已捕获本帧地形）、
 *    render3dHud 与 GameRenderer.render() 的 guiRenderer.render()（GUI 合成）之前，
 *    此刻写 main target 必然先于 GUI 出现在屏幕上（X9：合并 jar 字节码顺序实测，2026-10-01 P4.3）。
 * 2. 备选：① RenderFrameEvent.Post —— **实测否决（P4.3 根因）**：触发在 GameRenderer.render()
 *    返回之后，GUI 已画进 main target，我方 final blit 整屏覆盖 GUI（实证 p03_mainmenu_pattern.png
 *    与 P4.3 世界内截图）；② FrameGraphSetupEvent 帧图插 pass —— 调研否决（vanilla clear pass
 *    会随后全清 main target，图案必被抹掉）；上述事件均属官方事件，无需 GAP 登记。
 * 1b.（P1.1 补充）自定义 uniform 上传：原版 PostPass 用 MappableRingBuffer(usage=MAP_WRITE|UNIFORM=130)
 *    + Std140Builder 写 UBO + setUniform(name, buffer) 的官方序列；GLSL 侧块名与绑定布局 uniform 名一致
 *    （原版范本 assets/minecraft/shaders/core/clouds.vsh 的 layout(std140) uniform CloudInfo）。
 * 1d.（P3.2 本轮）原版 GameRenderer 相机接入：参考对象 = 原版 Camera / CameraRenderState /
 *    GameRenderState / Projection 的公开字段与方法语义（反编译源码仅作语义核实，零文本搬运；
 *    Mojang EULA 下仅用于公开 API 互操作）。核实事实：① cameraRenderState(projectionMatrix,
 *    viewRotationMatrix, pos, orientation, initialized) 由 Camera.extractRenderState 每帧填充，
 *    projectionMatrix 与原版世界渲染同一份（含 zZeroToOne / 真实 FOV / 窗口宽高比）；
 *    ② 离开世界 Camera.reset() 置 initialized=false 且 level=null → 必须显式回退占位相机（T11）。
 *    我们的差异点：顶点是本方验证几何（非世界地形），故在相机位姿上叠加一次性「锚点」平移，
 *    让几何停在进世界首帧的相机前方 2.5 格（与占位相机 eye 距离一致，保持前后可比）。
 * 1c.（P2 前置）纹理采样链路：原版 BindGroupLayouts.IN_SAMPLER =
 *    BindGroupLayout.builder().withUniform("InSampler", COMBINED_IMAGE_SAMPLER)（字节码核实）；
 *    GLSL 侧 uniform sampler2D InSampler（原版 core/blit_depth.fsh）；纹理创建走
 *    GpuDevice.createTexture(label, TEXTURE_BINDING|COPY_DST, RGBA8_UNORM, w,h,1,1) +
 *    NativeImage + createCommandEncoder().writeToTexture(texture, image) + createTextureView；
 *    采样器取原版 RenderSystem.getSamplerCache().getClampToEdge(FilterMode)。
 * 3. 我们的差异点：只暴露纯 Java 视图 {@link FrameSize}/{@link FrameParams} 与
 *    {@link #drawFullscreen(String, FrameParams)}；
 *    RenderPass / RenderPipeline / CompiledRenderPipeline / GpuTextureView 等原版类型全部封在方法体内，
 *    业务包零 com.mojang.renderpearl import；管线无 depthStencilState，故不挂 depth 附件；
 *    任何失败原样抛异常（不吞），由业务层打 ERROR 原文。
 * 4. 许可证核对：本项目 MIT；只调用公开 API 签名，无代码复制（07-CONSTRAINTS §〇 P1、L5-L8）。
 * 5. 性能基线：每帧一次 draw，冷路径（按 task-2 要求）不做性能优化（17-NATIVE.md §3.2）。
 */
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.joml.Vector4f;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import dev.vkdisp.glsl.translate.BuiltinsBlockLayout;
import dev.vkdisp.pipeline.model.MrtPlan;
import dev.vkdisp.render.OfUniformManager;

/**
 * 原版绘制 API 唯一入口（06-MIGRATION.md §2.1 的 bridge 红线，07-CONSTRAINTS T5）。
 *
 * <p>对主渲染目标开一个 render pass 并画全屏三角形；全部原版类型封在方法体内，
 * 业务包只看到 {@link FrameSize} 这一纯 Java 视图。
 */
public final class FrameApi {
    /** 主渲染目标尺寸的纯 Java 视图（业务包用于首帧埋点）。 */
    public record FrameSize(int width, int height) {}

    /**
     * P1.1：每帧 uniform 参数的纯 Java 视图（业务包只传裸浮点，原版类型不出 bridge）。
     *
     * <p>对应 GLSL {@code layout(std140) uniform VkDispParams { vec4 Params; };} 的 x/y 分量。
     *
     * @param phase     动画相位（秒级，滑动取模；驱动棋盘平移，用于「改数值画面就变」的验收）
     * @param intensity 强度（0..1，当前用于棋盘亮度，预留）
     */
    public record FrameParams(float phase, float intensity) {}

    /** std140 mat4 = 64 字节（列主序，列间对齐 16）。 */
    private static boolean firstTerrainReadbackLogged;

    private static final int LIGHT_MATRIX_BYTES = 64;

    /** 每帧上传的光空间矩阵环形缓冲（与 paramsRing 同为原版 MappableRingBuffer 模式）。 */
    private static MappableRingBuffer lightMatrixRing;

    /**
     * 光空间矩阵环形缓冲（懒创建，渲染线程 / 设备就绪后）。
     *
     * <p>P3.1 起内容 = {@link dev.vkdisp.shadow.LightSpaceList} 首级联合成的 view-projection
     * （uLight = P₀ × V₀）；写入与旧占位链数值逐位等价，画面不因列表化而改变。
     */
    private static MappableRingBuffer lightMatrixRing() {
        MappableRingBuffer ring = lightMatrixRing;
        if (ring == null) {
            ring = new MappableRingBuffer(
                    () -> "vkdisp light matrix",
                    GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM,
                    LIGHT_MATRIX_BYTES);
            lightMatrixRing = ring;
            dev.vkdisp.VkDisp.LOGGER.info(
                    "vkdisp: light matrix buffer created (content=light-space view-projection from LightSpaceList, bytes={})",
                    LIGHT_MATRIX_BYTES);
        }
        return ring;
    }

    /**
     * P3.1 光行进方向（固定占位，单位向量，从光源指向场景）。
     *
     * <p>来源如实登记：太阳/月亮方向（{@code EnvironmentAttributes.SUN_ANGLE} 已 javap 核实）
     * 与多级联 CSM 分割为本轮后缺口 —— 未做像素判据前不接（X9，18-PARALLEL §5 P3.1 ④）。
     */
    private static final org.joml.Vector3f LIGHT_DIRECTION =
            new org.joml.Vector3f(0.6F, -1.0F, 0.45F).normalize();

    /** 方向来源标识（08-TESTING §5「列表非空」验收日志的 source= 字段，与上行保持一致）。 */
    private static final String LIGHT_SPACE_SOURCE = "fixed-placeholder";

    /** P3.1 光空间列表（懒构建一次，帧路径只读；验收对象 = 非空，08-TESTING §5）。 */
    private static java.util.List<dev.vkdisp.shadow.LightSpaceList.Entry> lightSpaceList;

    /**
     * P3.2 Pass 3 上一次的输入来源（null = 尚未打过埋点）。只在**切换**时打一次日志
     * （T11：不静默换输入，也不逐帧刷屏）。
     */
    private static String compositeInputSource;

    /**
     * P3.3 deferred 链上一次是否激活。激活沿（false→true）打一次性埋点
     * {@code deferred chain wired: scene -> offscreen2 -> main}（每步输入=上一步输出的
     * 书面链第一环）；停用沿不需要日志 —— {@link #compositeInputSource} 的切换埋点已可见。
     */
    private static boolean deferredChainActive;

    /**
     * P4.1.4 final 链上一次是否激活。激活沿（false→true）打一次性埋点
     * {@code final chain wired: composite -> offscreen3 -> main}（final 的输入 = composite 的
     * 输出）；停用沿不打日志（composite input source 切换埋点已可见）。
     */
    private static boolean finalChainActive;

    /**
     * 光空间列表（懒构建 + 缓存）。首次构建打验收埋点：
     * 非空 → {@code light-space list ready: size=… source=…}；空 → ERROR（T11，后续帧由
     * {@link #lightSpaceMatrix()} 拒绝出帧，hook 打 ERROR 原文本帧跳过影子链）。
     */
    private static java.util.List<dev.vkdisp.shadow.LightSpaceList.Entry> lightSpaceList() {
        java.util.List<dev.vkdisp.shadow.LightSpaceList.Entry> list = lightSpaceList;
        if (list == null) {
            try {
                list = dev.vkdisp.shadow.LightSpaceList.build(LIGHT_DIRECTION);
            } catch (RuntimeException ex) {
                dev.vkdisp.VkDisp.LOGGER.error(
                        "vkdisp: light-space list build failed (T11): {}", ex.toString());
                list = java.util.List.of();
            }
            lightSpaceList = list;
            if (list.isEmpty()) {
                dev.vkdisp.VkDisp.LOGGER.error(
                        "vkdisp: light-space list EMPTY: size=0 source={} — 影子链本帧起拒绝执行",
                        LIGHT_SPACE_SOURCE);
            } else {
                dev.vkdisp.shadow.LightSpaceList.Entry first = list.get(0);
                dev.vkdisp.VkDisp.LOGGER.info(
                        "vkdisp: light-space list ready: size={} source={} dir=({}, {}, {}) "
                                + "cascade0 near={} far={} ortho=[{}, {}]x[{}, {}] zZeroToOne=true",
                        list.size(), LIGHT_SPACE_SOURCE,
                        first.lightTravelDirection().x,
                        first.lightTravelDirection().y,
                        first.lightTravelDirection().z,
                        first.near(), first.far(),
                        -dev.vkdisp.shadow.LightSpaceList.ORTHO_HALF_X,
                        dev.vkdisp.shadow.LightSpaceList.ORTHO_HALF_X,
                        -dev.vkdisp.shadow.LightSpaceList.ORTHO_HALF_Y,
                        dev.vkdisp.shadow.LightSpaceList.ORTHO_HALF_Y);
            }
        }
        return list;
    }

    /**
     * P3.1 光空间矩阵（uLight = P₀ × V₀）：取光空间列表首级联的分矩阵合成。
     *
     * <p>正交投影 × 光相机沿光反方向看向原点的视图；数值与旧 {@code ortho().lookAt()} 链逐位等价。
     * ⚠️ zZeroToOne=true：Vulkan 深度范围是 [0,1]；GL 约定 [-1,1] 会把近半几何裁掉（P3.1 基础实测）。
     * 调用点位于任何 render pass 打开之前 —— 列表为空时抛出即整帧跳过影子链（防御分支，T11）。
     */
    private static org.joml.Matrix4f lightSpaceMatrix() {
        java.util.List<dev.vkdisp.shadow.LightSpaceList.Entry> list = lightSpaceList();
        if (list.isEmpty()) {
            throw new IllegalStateException(
                    "vkdisp: light-space list is empty (see prior light-space ERROR) — skip this frame's shadow chain");
        }
        return list.get(0).viewProjection(new org.joml.Matrix4f());
    }

    /** 相机矩阵环形缓冲（mat4 64B；P3.2/P3.3 真实透视视图取代占位 NDC 视图）。 */
    private static MappableRingBuffer cameraRing;

    /** P3.2：相机矩阵来源（原版相机 / 占位回退）——来源**变化**时打一次日志（T11 显式，不静默）。 */
    private static boolean cameraSourceKnown;

    /** P3.2：上一帧是否在世界内（与 cameraSourceKnown 一起判断来源切换）。 */
    private static boolean lastFrameInWorld;

    /** P3.2：锚点矩阵（进世界首帧捕获；换世界时重新捕获）。 */
    private static org.joml.Matrix4f anchorMatrix;

    /** P3.2：锚点所在世界（ClientLevel 引用；换世界 / 重生换维度时据此重新捕获）。 */
    private static Object anchorLevel;

    /** 位姿变化埋点计数（最多 30 条；记录上次已知位姿，变化超 epsilon 才打）。 */
    private static int cameraPoseSamples;

    private static double lastPoseX = Double.NaN;
    private static double lastPoseY;
    private static double lastPoseZ;
    private static float lastPoseYaw;
    private static float lastPosePitch;

    /**
     * 占位透视相机（**菜单 / 未进世界时的显式回退分支**）：FOV 60°、zZeroToOne、eye=(0,0,-2.5) → 原点。
     *
     * <p>P3.2 起这是回退路径；世界内改用 {@link #cameraMatrix(int, int)} 的原版 GameRenderer 相机。
     * 回退与启用的切换在日志里可见（「camera source=…」），符合 T11「降级必须显式报错或 WARN」。
     */
    private static org.joml.Matrix4f placeholderCamera(int width, int height) {
        return new org.joml.Matrix4f()
                .perspective((float) Math.toRadians(60.0), width / (float) height, 0.1F, 32.0F, true)
                .lookAt(new org.joml.Vector3f(0.0F, 0.0F, -2.5F),
                        new org.joml.Vector3f(0.0F, 0.0F, 0.0F),
                        new org.joml.Vector3f(0.0F, 1.0F, 0.0F));
    }

    /**
     * P3.2：每帧相机矩阵 —— 世界内取**原版 GameRenderer 相机**（与原版世界渲染同一份投影/视图），
     * 世界外显式回退占位相机。
     *
     * <p><b>锚点设计</b>：本方顶点是验证用局部几何（±1 内），不是世界地形。进世界首帧捕获
     * {@code M = T(camPos) × R(camRot) × V_placeholder}，使得**捕获帧**满足
     * {@code P_vanilla × V_now × M ≡ P_vanilla × V_placeholder} —— 出现位置与占位版本逐像素一致；
     * 此后相机移动/转向时 V_now 变化 → 几何在屏幕上移动（这正是「原版相机真的在驱动视图」的判据）。
     *
     * <p>阴影链不受影响：Pass 1/阴影采样都在**局部坐标系**内（uLight 与 vWorldPos 同系），
     * 与相机矩阵解耦。
     */
    private static org.joml.Matrix4f cameraMatrix(int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.renderer.state.level.CameraRenderState state =
                mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        boolean inWorld = mc.level != null && state.initialized;

        // 来源切换埋点（进世界 / 回菜单各打一次；reason 写明为什么回退，T11）。
        if (!cameraSourceKnown || inWorld != lastFrameInWorld) {
            cameraSourceKnown = true;
            lastFrameInWorld = inWorld;
            if (inWorld) {
                dev.vkdisp.VkDisp.LOGGER.info(
                        "vkdisp: camera source=vanilla GameRenderer (level present, camera initialized)");
            } else {
                dev.vkdisp.VkDisp.LOGGER.info(
                        "vkdisp: camera source=placeholder fallback (level={}, initialized={})",
                        mc.level != null, state.initialized);
            }
        }

        if (!inWorld) {
            // 离开世界：释放锚点，下次进世界按新位姿重新捕获。
            anchorMatrix = null;
            anchorLevel = null;
            return placeholderCamera(width, height);
        }

        // 首帧 / 换世界：捕获锚点 M = T(pos0) × R(rot0) × V_placeholder（使捕获帧 clip = P_vanilla × V_ph）。
        // ⚠️ JOML 语义：.rotation(q) 是**赋值**（会清掉已乘的 translate），矩阵乘法必须用 .rotate(q)；
        //    V_placeholder 只取 lookAt 视图部分（不带占位透视 —— 投影一律用原版的）。
        if (anchorMatrix == null || anchorLevel != mc.level) {
            anchorLevel = mc.level;
            anchorMatrix = new org.joml.Matrix4f()
                    .translate((float) state.pos.x, (float) state.pos.y, (float) state.pos.z)
                    .rotate(state.orientation)
                    .mul(new org.joml.Matrix4f().lookAt(
                            new org.joml.Vector3f(0.0F, 0.0F, -2.5F),
                            new org.joml.Vector3f(0.0F, 0.0F, 0.0F),
                            new org.joml.Vector3f(0.0F, 1.0F, 0.0F)));
            dev.vkdisp.VkDisp.LOGGER.info(
                    "vkdisp: camera anchor captured: pos=({}, {}, {}), yaw={}, pitch={}, fov={}deg",
                    state.pos.x, state.pos.y, state.pos.z,
                    state.yRot, state.xRot, mc.gameRenderer.mainCamera().getFov());
            // 位姿基线：锚点即首个已知位姿（锚点日志本身已含位姿，这里只供后续变化检测比对）。
            lastPoseX = state.pos.x;
            lastPoseY = state.pos.y;
            lastPoseZ = state.pos.z;
            lastPoseYaw = state.yRot;
            lastPosePitch = state.xRot;
        }

        // 位姿**变化**埋点（比定时采样更硬的证据：玩家移动/转头/切视角 → 立刻留日志）。
        // epsilon 0.001 覆盖浮点抖动；上限 30 条防刷屏。
        boolean moved = anchorMatrix == null
                || Math.abs(state.pos.x - lastPoseX) > 1.0e-3
                || Math.abs(state.pos.y - lastPoseY) > 1.0e-3
                || Math.abs(state.pos.z - lastPoseZ) > 1.0e-3
                || Math.abs(state.yRot - lastPoseYaw) > 0.01
                || Math.abs(state.xRot - lastPosePitch) > 0.01;
        if (moved && cameraPoseSamples < 30) {
            cameraPoseSamples++;
            dev.vkdisp.VkDisp.LOGGER.info(
                    "vkdisp: vanilla camera pose changed #{}: pos=({}, {}, {}), yaw={}, pitch={}",
                    cameraPoseSamples, state.pos.x, state.pos.y, state.pos.z, state.yRot, state.xRot);
            lastPoseX = state.pos.x;
            lastPoseY = state.pos.y;
            lastPoseZ = state.pos.z;
            lastPoseYaw = state.yRot;
            lastPosePitch = state.xRot;
        }

        // clip = P_vanilla × R_view × T(−pos) × M：投影与视图取原版，锚点把验证几何钉在捕获帧的屏幕上。
        return new org.joml.Matrix4f(state.projectionMatrix)
                .mul(state.viewRotationMatrix)
                .translate((float) -state.pos.x, (float) -state.pos.y, (float) -state.pos.z)
                .mul(anchorMatrix);
    }

    /**
     * 相机矩阵环形缓冲（懒创建；与 paramsRing/lightMatrixRing 同为原版 MappableRingBuffer 模式）。
     * 每帧 map 写入一次，绘制后由调用方 rotate。
     */
    private static MappableRingBuffer cameraRing() {
        MappableRingBuffer ring = cameraRing;
        if (ring == null) {
            ring = new MappableRingBuffer(
                    () -> "vkdisp camera",
                    GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM,
                    LIGHT_MATRIX_BYTES);
            cameraRing = ring;
        }
        return ring;
    }
    /** std140 vec4 = 16 字节（对齐规则：vec4 偏移必须 16 字节对齐）。 */
    private static final int PARAMS_BYTES = 16;

    /** 每帧写入的 uniform 环形缓冲（原版 PostPass 同款 MappableRingBuffer）。 */
    private static MappableRingBuffer paramsRing;

    private FrameApi() {}

    /**
     * 每帧 uniform 环形缓冲（懒创建：必须在渲染线程 / 设备就绪后）。
     *
     * <p>用法照原版 {@code PostPass}：usage = {@code MAP_WRITE | UNIFORM}（实测原版字节码为 130 = 128|2），
     * 每帧 map(false,true) 写入 → close 落盘 → setUniform(name, buffer) → 绘制后 rotate()。
     */
    private static MappableRingBuffer paramsRing() {
        MappableRingBuffer ring = paramsRing;
        if (ring == null) {
            ring = new MappableRingBuffer(
                    () -> "vkdisp params",
                    GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM,
                    PARAMS_BYTES);
            paramsRing = ring;
        }
        return ring;
    }

    /**
     * P4.1.3：VkDispBuiltins 内建块的上传环 —— **各步布局各一条环**（P4.1.4 起三槽）。
     *
     * <p>composite / deferred / final 的块收编集不同（04-SPEC §3.2 上传注记「布局与缓冲」）→
     * std140 偏移不同，一条共享环必然写错位；故按槽位各一条，尺寸 =
     * max(1024, 各自块字节数)，布局来自 {@code VkDispVirtualPack} 冷路径解析。
     * 布局空（兜底 passthrough 无块）→ 不写，绑定初始缓冲（P2.4 零填充基线不变）。
     * 块字节增长（换包）→ 旧环 close 后按新尺寸重建（MappableRingBuffer 实现 AutoCloseable）。
     */
    private static final int BUILTINS_MIN_BYTES = 1024;

    private static MappableRingBuffer builtinsCompositeRing;
    private static int builtinsCompositeRingBytes;

    private static MappableRingBuffer builtinsDeferredRing;
    private static int builtinsDeferredRingBytes;

    private static MappableRingBuffer builtinsFinalRing;
    private static int builtinsFinalRingBytes;

    /** composite 槽位环（懒建/按布局字节扩容）。 */
    private static MappableRingBuffer builtinsCompositeRing(int wantBytes) {
        MappableRingBuffer ring = builtinsCompositeRing;
        if (ring == null || wantBytes > builtinsCompositeRingBytes) {
            closeStaleBuiltinsRing(ring, "composite");
            ring = new MappableRingBuffer(
                    () -> "vkdisp builtins composite",
                    GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM,
                    wantBytes);
            builtinsCompositeRing = ring;
            builtinsCompositeRingBytes = wantBytes;
            dev.vkdisp.VkDisp.LOGGER.info(
                    "vkdisp: builtins uniform buffer created: slot=composite bytes={}", wantBytes);
        }
        return ring;
    }

    /** deferred 槽位环（懒建/按布局字节扩容）。 */
    private static MappableRingBuffer builtinsDeferredRing(int wantBytes) {
        MappableRingBuffer ring = builtinsDeferredRing;
        if (ring == null || wantBytes > builtinsDeferredRingBytes) {
            closeStaleBuiltinsRing(ring, "deferred");
            ring = new MappableRingBuffer(
                    () -> "vkdisp builtins deferred",
                    GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM,
                    wantBytes);
            builtinsDeferredRing = ring;
            builtinsDeferredRingBytes = wantBytes;
            dev.vkdisp.VkDisp.LOGGER.info(
                    "vkdisp: builtins uniform buffer created: slot=deferred bytes={}", wantBytes);
        }
        return ring;
    }

    /** final 槽位环（P4.1.4 第三布局第三环；懒建/按布局字节扩容）。 */
    private static MappableRingBuffer builtinsFinalRing(int wantBytes) {
        MappableRingBuffer ring = builtinsFinalRing;
        if (ring == null || wantBytes > builtinsFinalRingBytes) {
            closeStaleBuiltinsRing(ring, "final");
            ring = new MappableRingBuffer(
                    () -> "vkdisp builtins final",
                    GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM,
                    wantBytes);
            builtinsFinalRing = ring;
            builtinsFinalRingBytes = wantBytes;
            dev.vkdisp.VkDisp.LOGGER.info(
                    "vkdisp: builtins uniform buffer created: slot=final bytes={}", wantBytes);
        }
        return ring;
    }

    /** 换尺寸重建时关闭旧环（失败只 WARN —— 旧缓冲随后无人引用，泄漏可接受）。 */
    private static void closeStaleBuiltinsRing(MappableRingBuffer stale, String slot) {
        if (stale == null) {
            return;
        }
        try {
            stale.close();
        } catch (RuntimeException ex) {
            dev.vkdisp.VkDisp.LOGGER.warn(
                    "vkdisp: builtins ring close failed on resize: slot={} reason={}", slot, ex);
        }
    }

    /**
     * 方块图集 {w,h}（OfUniformManager.gather 用）。
     *
     * <p>取图必须走 {@code GpuTexture.getWidth(int)} —— 该类型属于 com.mojang.renderpearl，
     * T5 红线业务包不得引用，故封在 bridge 内（06-MIGRATION §2.1）。失败返回 {0,0}
     * 并一次性 WARN（T11 不静默；图集是常驻资源，真失败会持续可见）。
     */
    private static boolean atlasWarned;

    private static int[] blockAtlasSize() {
        try {
            net.minecraft.client.renderer.texture.AbstractTexture texture =
                    Minecraft.getInstance().getTextureManager().getTexture(
                            net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
            if (texture == null) {
                return new int[] {0, 0};
            }
            GpuTexture gpu = texture.getTexture();
            if (gpu == null) {
                return new int[] {0, 0};
            }
            return new int[] {gpu.getWidth(0), gpu.getHeight(0)};
        } catch (RuntimeException ex) {
            if (!atlasWarned) {
                atlasWarned = true;
                dev.vkdisp.VkDisp.LOGGER.warn(
                        "vkdisp: block atlas size unavailable (atlasSize=0,0): {}", ex.toString());
            }
            return new int[] {0, 0};
        }
    }

    /**
     * 离屏渲染目标（P2 前置：图案先画到它上面，再被下一个 pass 采样进主目标）。
     *
     * <p>用原版 {@link TextureTarget}（{@code RenderTarget} 子类，vanilla 内部目标同款）：
     * 自带颜色/深度纹理与需要时的 {@code resize}，避免手工管理 {@code GpuTexture} 生命周期。
     * 之所以需要离屏目标：同一 pass 内既写又采样同一纹理在 Vulkan 属非法反馈回路，
     * 真实 composite 链必须靠中间目标（ping-pong）——这就是最小可运行的 ping-pong 骨架。
     */
    private static TextureTarget offscreenTargetA;
    private static TextureTarget offscreenTargetB;
    /** P3.3：槽 2 = deferred 步输出（只在链路激活时懒建，颜色专用无深度）。 */
    private static TextureTarget offscreenTargetC;
    /** P4.1.4：槽 3 = composite 输出（仅 final 链激活时懒建，颜色专用无深度）。 */
    private static TextureTarget offscreenTargetD;

    /**
     * 按主目标尺寸取第 {@code slot} 个离屏目标（0/1 ping-pong 两端 + 2 deferred 输出 + 3 composite 输出）。
     *
     * <p>尺寸变化时 resize，不每帧重建；所有目标都按主目标尺寸分配，保证中间级分辨率一致。
     */
    private static TextureTarget offscreenTarget(int slot, int width, int height) {
        TextureTarget target = switch (slot) {
            case 0 -> offscreenTargetA;
            case 1 -> offscreenTargetB;
            case 2 -> offscreenTargetC;
            default -> offscreenTargetD;
        };
        if (target == null) {
            // 槽 0 带深度附件（P3 前置：图案 pass 写深度、depthviz pass 采样它）；
            // 槽 1 只做颜色 ping-pong、槽 2 承载 deferred 输出、槽 3 承载 final 链的
            // composite 输出，都不需要深度。
            target = new TextureTarget(
                    "vkdisp offscreen " + slot,
                    width,
                    height,
                    GpuFormat.RGBA8_UNORM,
                    slot == 0 ? GpuFormat.D32_FLOAT : null);
            switch (slot) {
                case 0 -> offscreenTargetA = target;
                case 1 -> offscreenTargetB = target;
                case 2 -> offscreenTargetC = target;
                default -> offscreenTargetD = target;
            }
        } else if (target.width != width || target.height != height) {
            target.resize(width, height);
        }
        return target;
    }

    /** 几何顶点数：两个四边形 × 2 三角形 × 3 顶点 = 12。 */
    private static final int GEOMETRY_VERTEX_COUNT = 12;

    /** 几何顶点缓冲（懒创建：12 顶点 × 28 字节 = 336 字节）。 */
    private static GpuBuffer geometryBuffer;

    /**
     * 构造并上传本验证用的几何：**近**四边形（红，z=0.3，先画）+ **远**四边形（绿，z=0.7，后画）。
     *
     * <p>判定设计：两块在中间区域重叠，且**远的后画**。若深度测试生效，重叠区应保持**红色**
     * （靠近相机的片元胜出、后画的远片元被剔除）；若深度测试失效，后画的绿色会覆盖红色。
     * 这样"深度剔除到底有没有生效"就变成可像素级判定的硬事实，不依赖主观观察。
     *
     * <p>顶点布局与 {@code PipelineApi.GEOMETRY_VERTEX_FORMAT} 严格对应：
     * Position(RGB32_FLOAT, 12B, offset 0) + Color(RGBA32_FLOAT, 16B, offset 12)，stride = 28B。
     */
    private static GpuBuffer geometryBuffer() {
        GpuBuffer buffer = geometryBuffer;
        if (buffer == null) {
            // ⚠️ 必须用**直接缓冲**：createBuffer 走 LWJGL 的本地内存路径（按地址拷贝），
            // 传堆 ByteBuffer 会读到非法地址 → JVM 原生崩溃（实测 hs_err：SIGSEGV in
            // StubRoutines::jbyte_disjoint_arraycopy, si_addr=0x10）。
            java.nio.ByteBuffer data = java.nio.ByteBuffer
                    .allocateDirect(GEOMETRY_VERTEX_COUNT * 28)
                    .order(java.nio.ByteOrder.nativeOrder());
            putQuad(data, -0.8F, 0.0F, -0.6F, 0.6F, 0.3F, 1.0F, 0.0F, 0.0F);
            putQuad(data, -0.4F, 0.4F, -0.6F, 0.6F, 0.7F, 0.0F, 1.0F, 0.0F);
            data.flip();
            buffer = RenderSystem.getDevice()
                    .createBuffer(() -> "vkdisp geometry", GpuBuffer.USAGE_VERTEX, data);
            geometryBuffer = buffer;
            // 一次性埋点：确认顶点缓冲真实创建且大小符合（336 = 12 顶点 × 28 字节）。
            dev.vkdisp.VkDisp.LOGGER.info(
                    "vkdisp: geometry buffer created: size={} expected={} stride={} vertices={}",
                    buffer.size(),
                    (long) GEOMETRY_VERTEX_COUNT * 28,
                    PipelineApi.geometryVertexStride(),
                    GEOMETRY_VERTEX_COUNT);
        }
        return buffer;
    }

    /** 追加一个四边形（两个三角形，6 顶点）到顶点数据：NDC 范围 + 固定 z + 固定颜色。 */
    private static void putQuad(java.nio.ByteBuffer data, float x0, float x1, float y0, float y1, float z,
            float r, float g, float b) {
        float[][] triangles = {
                {x0, y0, x1, y0, x1, y1},
                {x0, y0, x1, y1, x0, y1},
        };
        for (float[] triangle : triangles) {
            for (int i = 0; i < 6; i += 2) {
                data.putFloat(triangle[i]).putFloat(triangle[i + 1]).putFloat(z);
                data.putFloat(r).putFloat(g).putFloat(b).putFloat(1.0F);
            }
        }
    }

    /**
     * P1.2 断言用：已注册管线中「编译成功」的数量（纯整数视图）。
     *
     * <p>与 {@link PipelineApi#registeredPipelineCount()} 比较，二者不等说明有管线静默编译失败
     * （{@code 08-TESTING.md} §3 要求「注册数 == 编译成功数（不得静默少）」）。
     */
    public static int compiledPipelineCount() {
        int compiled = 0;
        for (RenderPipeline pipeline : PipelineApi.registeredPipelines()) {
            if (RenderSystem.getCompiledPipelineNullable(pipeline) != null) {
                compiled++;
            }
        }
        return compiled;
    }

    /**
     * 全屏管线是否已编译完成（可以绘制）。
     *
     * <p><b>为什么需要它</b>：管线在 Minecraft 构造期注册（早于资源重载），而 GLSL 编译发生在
     * 资源重载的异步阶段；重载完成前 {@code getCompiledPipelineNullable} 返回 null。
     * 这属于<b>等待</b>而非失败，调用方应跳过该帧而不是打 ERROR（P0.3 实测发现：早期帧按失败处理
     * 会刷出 11 条 ERROR，违反 01-DEV-LOOP §9「日志无 ERROR」）。
     *
     * @return 已编译返回 true；尚未完成编译返回 false（不抛异常，供每帧轮询）
     */
    public static boolean isPipelineReady() {
        // 本帧链（P2.4 起）：geometry → shadowed → composite（Pass 3 已由 blit 换成包 composite；
        // pattern、blit、depthviz 已注册但不在本帧链中）。
        return PipelineApi.isGeometryPipelineRegistered()
                && PipelineApi.isShadowedPipelineRegistered()
                && PipelineApi.isCompositePipelineRegistered()
                && PipelineApi.isCompositeScenePipelineRegistered()
                && PipelineApi.isDeferredPipelineRegistered()
                && PipelineApi.isFinalPipelineRegistered()
                && RenderSystem.getCompiledPipelineNullable(PipelineApi.geometryPipeline()) != null
                && RenderSystem.getCompiledPipelineNullable(PipelineApi.shadowedPipeline()) != null
                && RenderSystem.getCompiledPipelineNullable(PipelineApi.compositePipeline()) != null
                && RenderSystem.getCompiledPipelineNullable(PipelineApi.compositeScenePipeline()) != null
                // P3.3：deferred 是 required 管线（虚拟包总有源可编）→ 就绪判据一并要求它编译完成，
                // 否则「世界内开链」会在帧中途撞未编译。P4.1.4 final 同理（required，兜底总有源）。
                && RenderSystem.getCompiledPipelineNullable(PipelineApi.deferredPipeline()) != null
                && RenderSystem.getCompiledPipelineNullable(PipelineApi.finalPipeline()) != null;
    }

    /**
     * 在主渲染目标（main target）上执行一次全屏绘制。
     *
     * <p>必须在渲染线程调用（RenderLevelStageEvent.AfterLevel 即是，见类【参考调研】第 1 条）；
     * 此处位于 swapchain 上屏之前、GUI 合成之前，
     * 写入的颜色会出现在本帧画面上。
     *
     * @param label  render pass 调试标签（renderdoc / Vulkan 调试层可读）
     * @param params 每帧 uniform 参数（纯 Java 值；写入 {@code VkDispParams} 块后绘制）
     * @return 主目标尺寸，供业务层打首帧埋点
     * <p>调用前应先查 {@link #isPipelineReady()}：未就绪时本方法抛异常（防止静默画不出东西），
     * 就绪状态下的绘制失败同样原样抛出。
     *
     * @throws IllegalStateException 管线未注册 / 尚未编译完成 / 主目标纹理视图不存在时抛出（绝不静默）
     */
    public static FrameSize drawFullscreen(String label, FrameParams params) {
        RenderSystem.assertOnRenderThread();
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuTextureView colorView = main.getColorTextureView();
        if (colorView == null) {
            throw new IllegalStateException("vkdisp: main target color texture view is null (can't open render pass)");
        }
        int width = colorView.getWidth(0);
        int height = colorView.getHeight(0);

        // 离屏目标与采样器必须在开启 render pass **之前**解析：pass 打开期间 encoder 不允许其它命令
        // （实测异常原文："Close the existing render pass before performing additional commands"）。
        TextureTarget targetA = offscreenTarget(0, width, height);
        TextureTarget targetB = offscreenTarget(1, width, height);
        GpuTextureView viewA = targetA.getColorTextureView();
        GpuTextureView depthA = targetA.getDepthTextureView();
        GpuTextureView viewB = targetB.getColorTextureView();
        if (depthA == null) {
            throw new IllegalStateException("vkdisp: offscreen0 depth texture view is null (expected D32_FLOAT)");
        }
        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);

        // 三条管线都要就绪（几何 + 阴影采样 + 合成）；任一未编译完成都抛异常，绝不静默少画一个 pass。
        CompiledRenderPipeline shadowed = RenderSystem.getCompiledPipelineNullable(PipelineApi.shadowedPipeline());
        if (shadowed == null) {
            throw new IllegalStateException(
                    "vkdisp: shadowed pipeline not compiled yet: " + PipelineApi.SHADOWED_LOCATION);
        }
        CompiledRenderPipeline geometry = RenderSystem.getCompiledPipelineNullable(PipelineApi.geometryPipeline());
        if (geometry == null) {
            throw new IllegalStateException(
                    "vkdisp: geometry pipeline not compiled yet: " + PipelineApi.GEOMETRY_LOCATION);
        }
        // P2.4：Pass 3 从 blit 换成包 composite（片元来自虚拟包 vkdisp_pack，见 PipelineApi）。
        CompiledRenderPipeline composite = RenderSystem.getCompiledPipelineNullable(PipelineApi.compositePipeline());
        if (composite == null) {
            throw new IllegalStateException(
                    "vkdisp: composite pipeline not compiled yet: " + PipelineApi.COMPOSITE_LOCATION);
        }
        // P3.2：scene 输入变体（无 v 翻转顶点；镜像实测根因 → 两条管线按输入源选，见 Pass 3）。
        CompiledRenderPipeline compositeScene =
                RenderSystem.getCompiledPipelineNullable(PipelineApi.compositeScenePipeline());
        if (compositeScene == null) {
            throw new IllegalStateException(
                    "vkdisp: composite scene pipeline not compiled yet: " + PipelineApi.COMPOSITE_SCENE_LOCATION);
        }
        // P3.3：deferred 步管线（不翻转顶点 + vkdisp_pack:deferred，p416 采样源规则）；isPipelineReady 已含此判据，
        // 走到这里仍 null = 编译期异常，抛出不静默（T11）。
        CompiledRenderPipeline deferred = RenderSystem.getCompiledPipelineNullable(PipelineApi.deferredPipeline());
        if (deferred == null) {
            throw new IllegalStateException(
                    "vkdisp: deferred pipeline not compiled yet: " + PipelineApi.DEFERRED_LOCATION);
        }
        // P4.1.4：final 步管线（恒等不翻转顶点 + vkdisp_pack:final，attachment 恒等拷贝；
        // p416 复核维持，根因在上游链顶点，见 PipelineApi.FINAL_PIPELINE_ID）；
        // isPipelineReady 已含此判据，走到这里仍 null = 编译期异常，抛出不静默（T11）。
        CompiledRenderPipeline finalStepPipeline =
                RenderSystem.getCompiledPipelineNullable(PipelineApi.finalPipeline());
        if (finalStepPipeline == null) {
            throw new IllegalStateException(
                    "vkdisp: final pipeline not compiled yet: " + PipelineApi.FINAL_LOCATION);
        }

        // P3.2：相机矩阵（世界内=原版 GameRenderer，菜单=占位回退，来源切换见日志）；map/close 仍在开启 pass 之前。
        MappableRingBuffer camRing = cameraRing();
        try (GpuBufferSlice.MappedView view = camRing.currentBuffer().map(false, true)) {
            Std140Builder.intoBuffer(view.data()).putMat4f(cameraMatrix(width, height));
        }

        // P3.1 前置：光空间矩阵写入自己的环形缓冲（map/close 不是编码器命令，但仍在开启 pass 之前执行，
        // 遵守「pass 打开期间不动 encoder」的实测规则）。
        MappableRingBuffer matrixRing = lightMatrixRing();
        try (GpuBufferSlice.MappedView view = matrixRing.currentBuffer().map(false, true)) {
            Std140Builder.intoBuffer(view.data()).putMat4f(lightSpaceMatrix());
        }

        // P1.1：把本帧参数写进环形缓冲的当前槽（std140：vec4 Params = {phase, intensity, 0, 0}）。
        MappableRingBuffer ring = paramsRing();
        try (GpuBufferSlice.MappedView view = ring.currentBuffer().map(false, true)) {
            Std140Builder.intoBuffer(view.data()).putVec4(params.phase(), params.intensity(), 0.0F, 0.0F);
        }

        // P3.3 链路判定（必须在开启任何 pass 之前定：决定槽 2 懒建与 pass 序列）：
        //   世界内 && scene 已捕获 && 所选包声明 deferred → 开 deferred 步；否则保持既有基线
        //   （世界内无 deferred = P3.2 直连 scene；未捕获首帧 = P2.4 fixture，见 Pass 4）。
        //   P4.3 起入口只在世界内（AfterLevel），菜单不再到达本方法（菜单态图案 = 实测否决的 GUI 覆盖）。
        boolean useScene = Minecraft.getInstance().level != null && SceneCaptureApi.hasScene()
                && SceneCaptureApi.sceneColorView() != null;
        boolean deferredChain = useScene && dev.vkdisp.VkDispVirtualPack.hasDeferredProgram();
        GpuTextureView viewC = null;
        if (deferredChain) {
            viewC = offscreenTarget(2, width, height).getColorTextureView();
            if (viewC == null) {
                throw new IllegalStateException("vkdisp: offscreen2 (deferred output) color texture view is null");
            }
            if (!deferredChainActive) {
                deferredChainActive = true;
                // 一次性埋点（书面链第一环）：scene 是 deferred 的输入、offscreen2 是其输出。
                dev.vkdisp.VkDisp.LOGGER.info(
                        "vkdisp: deferred chain wired: scene -> offscreen2 -> main (pack deferred)");
            }
        } else {
            deferredChainActive = false;
        }
        // P4.1.4 链路判定：所选包声明 final 片元 → 开 final 步（composite 改写中间目标 offscreen3，
        // final 再拷回主目标）。**不要求 useScene** —— final 是屏幕空间末步（世界内同语义；
        // 入口 AfterLevel 只在世界内触发，见类【参考调研】第 1 条）。
        boolean finalChain = dev.vkdisp.VkDispVirtualPack.hasFinalProgram();
        GpuTextureView viewD = null;
        if (finalChain) {
            viewD = offscreenTarget(3, width, height).getColorTextureView();
            if (viewD == null) {
                throw new IllegalStateException("vkdisp: offscreen3 (composite output) color texture view is null");
            }
            if (!finalChainActive) {
                finalChainActive = true;
                dev.vkdisp.VkDisp.LOGGER.info(
                        "vkdisp: final chain wired: composite -> offscreen3 -> main (pack final)");
            }
        } else {
            finalChainActive = false;
        }

        // P4.1.3：内建 uniform 上传（各步布局 → 各环；map/close 仍在开启 pass 之前，
        // 遵守「pass 打开期间不动 encoder」的实测规则）。布局空 = 兜底无块 → 不写，
        // 绑初始缓冲（P2.4 零填充基线不变）；值采集只在有成员可写时发生。
        BuiltinsBlockLayout compositeLayout =
                dev.vkdisp.VkDispVirtualPack.compositeBuiltinsLayout();
        BuiltinsBlockLayout deferredLayout =
                dev.vkdisp.VkDispVirtualPack.deferredBuiltinsLayout();
        BuiltinsBlockLayout finalLayout =
                dev.vkdisp.VkDispVirtualPack.finalBuiltinsLayout();
        Map<String, Object> builtinsValues = null;
        if (!compositeLayout.isEmpty()
                || (deferredChain && !deferredLayout.isEmpty())
                || (finalChain && !finalLayout.isEmpty())) {
            builtinsValues = OfUniformManager.gather(
                    Minecraft.getInstance(), width, height, blockAtlasSize(), lightSpaceList(),
                    // GAP-022：链（composite*/deferred*/final）的顶点是我方 passthrough、
                    // 不读投影矩阵，而片元按 GL 口径写分支 ⇒ 这一族才吃 mrt.depthGlProxy。
                    OfUniformManager.Family.CHAIN);
        }
        MappableRingBuffer compositeBuiltins = builtinsCompositeRing(
                Math.max(BUILTINS_MIN_BYTES, compositeLayout.byteSize()));
        if (!compositeLayout.isEmpty()) {
            try (GpuBufferSlice.MappedView view =
                    compositeBuiltins.currentBuffer().map(false, true)) {
                OfUniformManager.logUploadOnce("composite", compositeLayout,
                        OfUniformManager.write(compositeLayout, builtinsValues, view.data()),
                        builtinsValues);
            }
        }
        MappableRingBuffer deferredBuiltins = null;
        if (deferredChain) {
            deferredBuiltins = builtinsDeferredRing(
                    Math.max(BUILTINS_MIN_BYTES, deferredLayout.byteSize()));
            if (!deferredLayout.isEmpty()) {
                try (GpuBufferSlice.MappedView view =
                        deferredBuiltins.currentBuffer().map(false, true)) {
                    OfUniformManager.logUploadOnce("deferred", deferredLayout,
                            OfUniformManager.write(deferredLayout, builtinsValues, view.data()),
                            builtinsValues);
                }
            }
        }
        MappableRingBuffer finalBuiltins = null;
        if (finalChain) {
            finalBuiltins = builtinsFinalRing(
                    Math.max(BUILTINS_MIN_BYTES, finalLayout.byteSize()));
            if (!finalLayout.isEmpty()) {
                try (GpuBufferSlice.MappedView view =
                        finalBuiltins.currentBuffer().map(false, true)) {
                    OfUniformManager.logUploadOnce("final", finalLayout,
                            OfUniformManager.write(finalLayout, builtinsValues, view.data()),
                            builtinsValues);
                }
            }
        }

        // 链段（各自不同附件 —— 规避「同一附件第二次 createRenderPass 不生效」）：
        //  Pass 1 阴影贴图：清屏(黑, 深度1.0) → 几何(经光空间矩阵) → offscreen0 的**深度**即阴影贴图
        //  Pass 2 世界视图：采样阴影贴图深度 → offscreen1（受阴影片元变暗）
        //  Pass 3（仅 P3.3 链）：scene → offscreen2（deferred 步，不翻转 —— 采样源是场景）
        //  Pass 4/3：输入 → 主目标或 offscreen3（**P2.4 起 = 包 composite**；顶点随采样源：
        //        scene/链路径 → 不翻转，fixture → flipv（P-1f 基线）；P4.1.4 final 链激活时
        //        composite 落中间目标，见 Pass 5/4）
        //  Pass 5/4（仅 P4.1.4 链）：offscreen3 → 主目标（包 final，恒等不翻转 —— attachment 恒等拷贝）
        // 说明：图案背景本轮**不进链** —— 阴影贴图只应包含遮挡物深度，背景深度会污染贴图；
        //       pattern/blit/depthviz 管线仍注册并通过计数断言，只是不在本帧执行。
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass pass = encoder.createRenderPass(
                () -> label + " 1 (shadow map: geometry -> offscreen0)",
                viewA,
                Optional.of(new Vector4f(0.0F, 0.0F, 0.0F, 1.0F)),
                depthA,
                OptionalDouble.of(1.0D))) {
            pass.setPipeline(geometry);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform(PipelineApi.LIGHT_MATRIX_UNIFORM, matrixRing.currentBuffer());
            pass.setVertexBuffer(0, geometryBuffer().slice());
            pass.draw(GEOMETRY_VERTEX_COUNT, 1, 0, 0);
        }
        // Pass 2（P3.3）：**世界视图**渲染几何，同时采样阴影贴图（Pass 1 的深度）做深度比较；
        // 受阴影的片元变暗 → 「阴影真的生效」变成可判定的颜色差异（不同附件，规避同附件二次 pass 问题）。
        try (RenderPass pass = encoder.createRenderPass(
                () -> label + " 2 (world + shadow sample -> offscreen1)",
                viewB,
                Optional.of(new Vector4f(0.0F, 0.0F, 0.0F, 1.0F)),
                null,
                OptionalDouble.empty())) {
            pass.setPipeline(shadowed);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform(PipelineApi.LIGHT_MATRIX_UNIFORM, matrixRing.currentBuffer());
            pass.setUniform(PipelineApi.CAMERA_UNIFORM, camRing.currentBuffer());
            pass.setUniform(PipelineApi.SAMPLER_UNIFORM, depthA, sampler);
            pass.setVertexBuffer(0, geometryBuffer().slice());
            pass.draw(GEOMETRY_VERTEX_COUNT, 1, 0, 0);
        }
        // Pass 3（仅 P3.3 链）：scene → offscreen2（deferred 步）。
        // 输入 = SceneCaptureApi 捕获的地形（本帧帧图已写入）、输出 = 槽 2 ——
        // 书面链「每步的输入纹理是上一步的输出」的中间环（08-TESTING §5）。
        // 顶点**不翻转**（p416 方向矫正，推翻首版「净翻转守恒」）：首版 flipv 的理由是与
        // composite 的 +1 抵消，但 deferred 不改写 colortex0（P4.1.2 全黑根因实证）—— 它不在
        // 彩色净翻转的算式里；color/depthtex0 都采样场景 ⇒ 与 P3.2 同规则不翻转，
        // 顺带修正 depth 配对（原 flipv 使行 y 写 AO 却读 scene 深度行 1−y）。
        if (deferredChain) {
            try (RenderPass pass = encoder.createRenderPass(
                    () -> label + " 3 (deferred: scene -> offscreen2, pack deferred)",
                    viewC, Optional.empty(), null, OptionalDouble.empty())) {
                pass.setPipeline(deferred);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform(PipelineApi.BUILTINS_UNIFORM, deferredBuiltins.currentBuffer());
                pass.setUniform(PipelineApi.SAMPLER_UNIFORM, SceneCaptureApi.sceneColorView(), sampler);
                // P4.1.2：validateDraw 按布局逐条校验（javap 取证）—— 18 包 sampler 缺一即抛。
                // deferred 只读场景色（其 DRAWBUFFERS:4 写出的 AO 落在本步输出 viewC），
                // 不采样 gaux1 → color/aux 同绑场景色占位。
                PipelineApi.setPackSamplerUniforms(pass,
                        SceneCaptureApi.sceneColorView(), SceneCaptureApi.sceneColorView(), sampler);
                pass.draw(3, 1, 0, 0);
            }
        }
        // Pass 4（有链）/ Pass 3（无链）：输入 → 主目标（最后一级；主目标只由本 pass 写入）。
        // 输入三态（来源**切换**打一次埋点，T11，不静默换输入）：
        //   链激活   → deferred 输出 offscreen2（= 上一步 deferred 的输出，P3.3）
        //   世界直连 → scene 捕获（P3.2：flipv 采 scene 实测镜像 → 无翻转顶点双管线）
        //   菜单回退 → offscreen1 fixture（P2.4 基线）
        // ⚠️ 采样的必须是**本帧有内容的那个目标**（实测教训：曾误采样本链未写入的目标）。
        // P2.4：fixture 路径 = 包 composite + fullscreen_flipv（1-v 翻转 P-1f 基线）；
        //       scene/链路径 = composite_scene 不翻转顶点（p416 采样源规则）；
        //       VkDispBuiltins 自 P4.1.3 起按 composite 槽位布局写入真实值（OfUniformManager），
        //       布局空（兜底无块）时绑定初始零值缓冲（P2.4 基线语义）。
        GpuTextureView compositeInput;
        String compositeSource;
        if (deferredChain) {
            compositeInput = viewC;
            compositeSource = "deferred output (P3.3)";
        } else if (useScene) {
            compositeInput = SceneCaptureApi.sceneColorView();
            compositeSource = "scene capture (P3.2 terrain)";
        } else {
            compositeInput = viewB;
            compositeSource = "fixture offscreen1";
        }
        if (!compositeSource.equals(compositeInputSource)) {
            compositeInputSource = compositeSource;
            dev.vkdisp.VkDisp.LOGGER.info("vkdisp: composite input source: {}", compositeSource);
        }
        // pass 编号随链路态：有 deferred 链时其占 3、本 pass 是 4；无 deferred 链保持 3（调试标签口径）。
        // P4.1.4：final 链激活时 composite 落中间目标 offscreen3（主目标只由 final 写一次 ——
        // 规避「同一附件第二次 createRenderPass 不生效」）。
        String compositeStep = deferredChain ? " 4" : " 3";
        GpuTextureView compositeOutput = finalChain ? viewD : colorView;
        String compositeTargetLabel = finalChain ? "offscreen3" : "main";
        try (RenderPass pass = encoder.createRenderPass(
                () -> label + compositeStep + " (" + compositeSource + " -> "
                        + compositeTargetLabel + ", pack composite)",
                compositeOutput, Optional.empty(), null, OptionalDouble.empty())) {
            // 顶点随**采样源**切换（p416 方向矫正的单条规则）：彩色采样源是 vanilla 场景
            // （scene 直连 = packColor 场景；链模式 = OF 语义下 packColor 仍是场景色，见下方
            // packColor 注释）→ 不翻转（composite_scene）；fixture = 我方中间目标 → flipv
            // （P-1f 1-v 基线）。首版链模式走 flipv 是「净翻转守恒」推导错算了一跳
            // —— deferred 不改写 colortex0，彩色路径 scene →[composite flipv]→ offscreen3
            // →[final 恒等]→ main = 净 +1 翻转 = p416_run1 实测地平线 row 205（翻转）。
            pass.setPipeline(useScene ? compositeScene : composite);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform(PipelineApi.BUILTINS_UNIFORM, compositeBuiltins.currentBuffer());
            pass.setUniform(PipelineApi.SAMPLER_UNIFORM, compositeInput, sampler);
            // P4.1.2：布局 18 包 sampler 必须全部 setUniform 才能 draw（validateDraw 逐条校验）。
            // OF 合成语义（BSL 源实测）：deferred 步 DRAWBUFFERS:4 不改写 colortex0 ——
            // 链模式下 composite 的彩色主输入仍是**场景色**（绑 viewC=AO/NaN 缓冲即首跑全黑
            // 根因），我方 deferred 输出（AO）按 gaux1=colortex4 身份挂辅助位；
            // 直连/菜单模式无此分叉（color=aux=compositeInput）。
            GpuTextureView packColor = deferredChain ? SceneCaptureApi.sceneColorView() : compositeInput;
            GpuTextureView packAux = deferredChain ? viewC : packColor;
            PipelineApi.setPackSamplerUniforms(pass, packColor, packAux, sampler);
            pass.draw(3, 1, 0, 0);
        }
        // P4.1.4 Pass 5（final 链激活时）：offscreen3 → 主目标（**final 是最后且唯一的主目标写入**）。
        // 顶点**恒等不翻转**（attachment 恒等拷贝推导，p416 复核维持 —— run1 翻转的根因在上游
        // 链 composite 顶点，已在 Pass 4 修复；run2 曾临时用 final flipv 对冲回正，
        // 见 PipelineApi.FINAL_PIPELINE_ID）。
        // 采样语义：colortex0/colortex1（BSL final 读 colortex1）→ color = composite 输出；
        // gaux1 保持 deferred/colortex4 身份（链激活时 viewC，否则同 color）。
        if (finalChain) {
            String finalPassNo = deferredChain ? " 5" : " 4";
            try (RenderPass pass = encoder.createRenderPass(
                    () -> label + finalPassNo + " (offscreen3 -> main, pack final)",
                    colorView, Optional.empty(), null, OptionalDouble.empty())) {
                pass.setPipeline(finalStepPipeline);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform(PipelineApi.BUILTINS_UNIFORM, finalBuiltins.currentBuffer());
                pass.setUniform(PipelineApi.SAMPLER_UNIFORM, viewD, sampler);
                GpuTextureView finalAux = deferredChain ? viewC : viewD;
                PipelineApi.setPackSamplerUniforms(pass, viewD, finalAux, sampler);
                pass.draw(3, 1, 0, 0);
            }
        }
        // P4.1.3：各内建环都在绘制后 rotate（未激活步的环不创建/不写也就无需轮换）。
        compositeBuiltins.rotate();
        if (deferredBuiltins != null) {
            deferredBuiltins.rotate();
        }
        if (finalBuiltins != null) {
            finalBuiltins.rotate();
        }
        // 原版 PostPass 同款：绘制后再 rotate，保证本帧写入的槽在 GPU 用完前不被复用。
        ring.rotate();

        // 🔖 像素回读探针**不在这里调用** —— 它移到 {@code FullscreenPassHook} 的最末尾。
        //   🔴🔖 本轮实测踩到并修掉：放在这里时，本方法还没跑完、地形 MRT pass
        //   （{@link MrtTerrainPass#drawAfterLevel()}，由 Hook 在本方法**返回之后**调用）
        //   也就还没往主目标里画 ⇒ 在 {@code mrt.terrainToMain=true} 这一档下，
        //   探针读到的「主目标」是**我方 pass 写入之前**的内容 ⇒ 恰好在需要它的时候失效。
        //   ⇒ 位置必须晚于地形 pass、早于诊断 blit（唯一满足处 = Hook 末尾）。

        // GAP-003 多附件能力验证（诊断视图，默认关闭 ⇒ 常规帧到这里零开销）。
        // ⚠️ 放在**整条链之后**：它会把主目标覆盖成某个 colortex 的内容，
        // 放在链中间会毁掉前面 pass 的产物（那不是诊断，是自伤）。
        if (MrtProbe.enabled()) {
            if (MrtTerrainPass.enabled()) {
                // 🔖 方案 A 的 colortex 优先回读：它才是「地形真的画进多附件」的直接证据
                //（MrtProbe 自己的 colortex 是全屏三角形探针，与地形无关）。
                // ⚠️ 槽数必须取 **MrtTerrainPass 的** —— 探针那套本轮没建，是 0；
                //    上一版误取探针槽数 ⇒ 每帧抛「slot 0 out of range 0..-1」（本轮踩到，已修）。
                if (!MrtTerrainPass.toMain() && MrtTerrainPass.actualSlots() > 0) {
                    int slot = MrtPlan.requireViewSlot(MrtProbe.viewSlot(), MrtTerrainPass.actualSlots());
                    GpuTextureView terrainView = MrtTerrainPass.slotView(slot);
                    // 🔖 埋点节流：只打一次。原版把这行写成「每帧都打」，
                    //   一次跑出上千行日志 —— 热路径日志 I/O 本身就是开销（M-01 的教训）。
                    if (!firstTerrainReadbackLogged) {
                        firstTerrainReadbackLogged = true;
                        dev.vkdisp.VkDisp.LOGGER.info(
                                "vkdisp: [GAP-003/A] colortex readback: requestedSlot={} resolvedSlot={}"
                                        + " slots={} view={} probeView={}",
                                MrtProbe.viewSlot(), slot, MrtTerrainPass.actualSlots(),
                                terrainView == null ? "null" : terrainView.toString(),
                                MrtProbe.slotView(slot) == null ? "null" : "present");
                    }
                    MrtProbe.drawExternalView(label + " gbuffer terrain", terrainView, slot);
                }
            } else {
                MrtProbe.resizeIfNeeded(main);
                MrtProbe.draw(label + " mrt");
            }
        }
        return new FrameSize(width, height);
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // 🔴 通用后处理链执行器（deferred* → composite* → final；colortex 接线，用户点名
    //    「FrameApi:952 的 packColor 从 scene 改采 colortex」的机制化版本：整链按名接，
    //    不再是「18 个名字一律绑 scene」）。
    //
    // 🔖 附件布局铁律（h26 那族 UB 的机制级封堵）：
    //   每条管线定宽 8 个颜色目标 ⇒ 附件表 = [本 pass 写入的槽按升序] + [scratch 填满]。
    //   **被采样的 colortex 只可能出现在 scratch 侧**（它不是本 pass 的读写附件），
    //   「既作附件又作采样器」在构造上不成立。
    //
    // 🔖 时序：本方法必须在**地形 MRT pass 之后**调用（colortex 必须先有内容）。
    // ────────────────────────────────────────────────────────────────────────────────

    /** 后处理链的 scratch 附件（填满定宽 8 的未写槽；每帧清 0，从不被采样）。 */
    private static TextureTarget[] postScratch;

    /** 后处理链的 builtins 环（每槽一条；环尺寸按该槽布局字节扩容，同三步口径）。 */
    private static MappableRingBuffer[] postBuiltinsRings;
    private static int[] postBuiltinsRingBytes;

    /** 链就绪判据：注册 + 全部槽编译 + 池/深度可用。 */
    public static boolean isPostChainReady() {
        if (!PipelineApi.arePostPipelinesRegistered()) {
            return false;
        }
        var chain = dev.vkdisp.VkDispVirtualPack.postChain();
        if (chain.passes().isEmpty()) {
            return false;
        }
        for (int i = 0; i < chain.passes().size(); i++) {
            if (RenderSystem.getCompiledPipelineNullable(PipelineApi.postPipeline(i)) == null) {
                return false;
            }
        }
        return MrtTerrainPass.poolSize() >= chain.maxSlot() + 1
                && MrtTerrainPass.depthView() != null;
    }

    /** 链是否应接管本帧（配置 + 地形包片元在场 + 非 toMain 诊断档）。 */
    public static boolean isPostChainActive() {
        return dev.vkdisp.VkDispConfig.MRT_POST_CHAIN.get()
                && dev.vkdisp.VkDispConfig.MRT_TERRAIN_ENABLED.get()
                && dev.vkdisp.VkDispConfig.MRT_PACK_TERRAIN_SHADER.get()
                && !MrtTerrainPass.toMain()
                && dev.vkdisp.VkDispVirtualPack.terrainProgram() != null
                && !dev.vkdisp.VkDispVirtualPack.postChain().passes().isEmpty();
    }

    /**
     * 执行整条后处理链（必须在渲染线程、地形 MRT pass 之后）。
     *
     * @param label render pass 标签前缀
     * @return 主目标尺寸
     * @throws IllegalStateException 未就绪仍被调用（就绪判据是调用方的事，这里绝不静默跳过）
     */
    /**
     * 链开跑前的两道响亮闸门（抽出来只为守 QD-04 棘轮：{@code drawPostChain} 正好压在 60 行，
     * 而 GAP-022 给它加了按族供值那一行 ⇒ <b>降线靠提取，不靠放宽棘轮</b>）。
     *
     * @return 主目标颜色视图（链的最后一级要写进它）
     */
    private static GpuTextureView requireChainReady(RenderTarget main) {
        if (!isPostChainReady()) {
            throw new IllegalStateException(
                    "vkdisp: drawPostChain called while chain not ready (调用方必须先查 isPostChainReady)");
        }
        // 🔴 GAP-020 代次读数（h50j 之后加的）：链**开跑前**先抄一份各槽被读代。
        //   为什么打在这里而不是 `drawPostChain` 里加一行：那一格已经被 QD-04 棘轮顶满
        //   （>60 行的方法数有基线），而本方法**每帧恰好被调一次**、且它就是「链开跑前」那一刻。
        //   读数本身在 `reportChainGenerations()`（链尾）打，start/end 同一行才可比。
        snapshotChainStartGenerations();
        GpuTextureView colorView = main.getColorTextureView();
        if (colorView == null) {
            throw new IllegalStateException("vkdisp: main target color texture view is null (can't open post chain)");
        }
        return colorView;
    }

    /** 链开跑前那一刻各槽的「被读代」（0/1）；-1 = 还没建。 */
    private static int[] chainStartGens = {-1, -1, -1, -1, -1, -1, -1, -1};

    private static void snapshotChainStartGenerations() {
        for (int slot = 0; slot < chainStartGens.length; slot++) {
            chainStartGens[slot] = MrtTerrainPass.poolGeneration(slot);
        }
    }

    public static FrameSize drawPostChain(String label) {
        RenderSystem.assertOnRenderThread();
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuTextureView colorView = requireChainReady(main);
        int width = colorView.getWidth(0);
        int height = colorView.getHeight(0);

        var chain = dev.vkdisp.VkDispVirtualPack.postChain();
        var passes = chain.passes();

        // scratch / rings 必须在开 pass 之前建好（pass 内不许新 encoder 命令，h10 实测规则）。
        // 🔴 自定义纹理（texture.<sampler> 指令，GAP-009 素材线）同样**必须**在开 pass 之前
        //   完成上传（上传自己新建 encoder）。
        PackTextures.ensureReady();
        ensurePostScratch(main);
        ensurePostRings();

        // 每帧一次采集（各槽布局不同，值集同一来源；与旧三步同款 gather）。
        java.util.Map<String, Object> builtinsValues = OfUniformManager.gather(
                Minecraft.getInstance(), width, height, blockAtlasSize(), lightSpaceList(),
                OfUniformManager.Family.CHAIN);   // GAP-022：链这一族才吃 depthGlProxy

        // 🔴 GAP-017（h46 K 臂证伪 maxLod 钳制后改为真做）：链采样保持完整 mip 范围，
        //   高 LOD 采样由 `ColortexPool` 的**真实 mip 链** + 每帧降采样金字塔供给
        //   （见 generateMipPyramids）。BSL 有 6 个后处理程序按 `colortexNMipmapEnabled`
        //   按级采样 —— 钳制只是把错误从「驱动未定义」换成「八 tap 全落 mip0 的必然过曝」。
        GpuSampler sampler = chainSampler();
        GpuTextureView sceneView = SceneCaptureApi.hasScene() ? SceneCaptureApi.sceneColorView() : null;
        // InSampler（OF 语义 = 场景色）：链模式采不到 colortex 时才回 scene。
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        refreshDepthGlProxy(main, encoder, label); // GAP-022 ① 地形之后、链第一级之前（WHY 见下方方法体）

        // 采样名→视图解析器（每帧一条；「按名接 colortex」的机制所在 —— 见 chainResolver 注释）。
        final GpuTextureView fallbackView = MrtTerrainPass.poolView(0) != null
                ? MrtTerrainPass.poolView(0) : postScratchView(0);
        PipelineApi.PostSamplerViewResolver resolver = chainResolver(fallbackView);

        // GAP-003 天空线判据（h48e）：链**开跑前**再读一次 colortex0。
        //   它切的是最后两种可能：`c0@afterSky` 有内容而这个取点没内容 ⇒ 天空写进去的东西
        //   到链的采样器眼里已经不存在（布局/屏障问题）；两个取点都有内容 ⇒ 恒 0 是
        //   包那一级**自己算出来的**（那就去查它读的 uniform 与分支）。
        TargetReadback.probeChainStart();
        java.util.Set<Integer> mipSlots = primeChainPyramids(encoder, label, chain);
        GpuSampler pyramidSampler = pyramidSampler();
        for (int slot = 0; slot < chainPassLimit(passes); slot++) {
            var passPlan = passes.get(slot);
            runPostPass(encoder, label, slot, passPlan, colorView, fallbackView,
                    sceneView, sampler, resolver, builtinsValues);
            // 🔴 GAP-020：金字塔必须与「同一个读者同时会读到的 mip0」同源 ⇒ 写完立刻重建。
            regeneratePyramidsForWritten(encoder, label, passPlan, mipSlots, pyramidSampler);
        }
        logChainExecutedOnce(passes.subList(0, chainPassLimit(passes)), sceneView);
        reportChainGenerations();
        return new FrameSize(width, height);
    }

    /** 代次自报的节流计数（跑过多少帧链）。 */
    private static long chainFrames;

    /**
     * 🔖 GAP-020 重开后的**代次奇偶自报**（每 120 帧链一行；纯读，不改任何渲染行为）。
     *
     * <p>为什么要打：一帧里各槽被写的次数不同 ⇒ 翻代次数不同 —— BSL 这条链实测
     * 槽 0 被写 6 次（偶数，代次不变）、槽 1 被写 5 次、槽 2 被 3 次（奇数，代次每帧翻）。
     * 而金字塔是**按当时的被读代**重建的 ⇒ 「读者采到哪一代的 mip」这件事只有打出来才能判。
     * h48o 量到的严格 3 帧周期空帧（{@code 0 N N}）要么落在这条线上（GAP-020 机制回来），
     * 要么与它无关（转向深度为 3 的环形资源）—— 这一行就是那条分岔的读数。
     */
    private static void reportChainGenerations() {
        chainFrames++;
        // 🔴 探针开着 ⇒ **每帧一行**（h50j 之后加的）：黑帧是逐帧事件，120 帧一行的节流
        //   永远对不到探针的帧号上 —— 那条读数只能回答「代次整体在不在翻」，
        //   回答不了「黑的那 1/3 帧是不是落在某一个代次相位上」。
        //   生产档（探针关）保持原来的 120 帧一行，热路径日志 I/O 纪律不变（h34 那一族）。
        if (!dev.vkdisp.VkDispConfig.MRT_PIXEL_PROBE.get() && chainFrames % 120L != 0L) {
            return;
        }
        dev.vkdisp.VkDisp.LOGGER.info("vkdisp: [GAP-020/gen] chainFrame={} 开跑前 c0={} c1={} c2={} c4={}"
                        + " | 链尾 c0={} c1={} c2={} c4={}（一帧内写次数 c0=6 c1=5 c2=3 c4=2"
                        + " ⇒ 奇数次的槽每帧翻代；探针的 c0@chainStart 取的是「开跑前」这一格）",
                chainFrames,
                chainStartGens[0], chainStartGens[1], chainStartGens[2], chainStartGens[4],
                MrtTerrainPass.poolGeneration(0), MrtTerrainPass.poolGeneration(1),
                MrtTerrainPass.poolGeneration(2), MrtTerrainPass.poolGeneration(4));
    }

    /** 链「已执行」自报只打一次（热路径日志 I/O 纪律）。 */
    private static void logChainExecutedOnce(
            java.util.List<dev.vkdisp.pack.PackPostChain.Pass> passes, GpuTextureView sceneView) {
        if (postChainLogged.compareAndSet(false, true)) {
            dev.vkdisp.VkDisp.LOGGER.info(
                    "vkdisp: [chain] post chain executed: passes={} first={} last={} (colortex-backed,"
                            + " 定宽 {} 附件 + scratch；InSampler={}）",
                    passes.size(), passes.get(0).programName(),
                    passes.get(passes.size() - 1).programName(), PipelineApi.POST_FRAME_WIDTH,
                    sceneView != null ? "scene" : "colortex0 fallback");
        }
    }

    /**
     * 执行链里的一个 pass：定宽附件表 + builtins 环 + 全量 sampler 绑定 + 一次全屏 draw。
     *
     * <p>🔖 附件布局：写入槽按升序占 location 0..m-1（片元源已由 {@code PostOutputRenumber}
     * 换算），剩余槽位挂 scratch 并清 0。**被采样的 colortex 只可能出现在未写位置** ⇒
     * 「同 pass 既作附件又作采样器」（h26 那族静默 UB）在构造上不成立。
     */
    private static void runPostPass(CommandEncoder encoder, String label, int passSlot,
            dev.vkdisp.pack.PackPostChain.Pass passPlan, GpuTextureView colorView,
            GpuTextureView fallbackView, GpuTextureView sceneView, GpuSampler sampler,
            PipelineApi.PostSamplerViewResolver resolver, Map<String, Object> builtinsValues) {
        var compiled = RenderSystem.getCompiledPipelineNullable(PipelineApi.postPipeline(passSlot));
        if (compiled == null) {
            throw new IllegalStateException("vkdisp: post pipeline slot " + passSlot + " vanished mid-frame");
        }
        List<Integer> slots = passPlan.attachmentSlots();
        RenderPassDescriptor.Builder descriptor =
                RenderPassDescriptor.builder(() -> label + " post" + passSlot + " (" + passPlan.programName() + ")");
        for (int a = 0; a < PipelineApi.POST_FRAME_WIDTH; a++) {
            boolean written = a < slots.size();
            GpuTextureView view = written
                    ? writtenAttachmentView(passPlan, slots.get(a), colorView)
                    : postScratchView(a);
            // 写入槽 = LOAD（保留上游 pass 写进同一 colortex 的内容）；scratch = 清 0。
            descriptor.withColorAttachment(view,
                    written ? Optional.empty() : Optional.of(new Vector4f(0.0F, 0.0F, 0.0F, 1.0F)));
            // （scratch 已是 alpha=1，见上；这里注释的是「为什么必须 1」——见 ensureColortex 同源说明）
        }
        MappableRingBuffer ring = postBuiltinsRings[passSlot];
        BuiltinsBlockLayout layout = dev.vkdisp.VkDispVirtualPack.postBuiltinsLayout(passSlot);
        if (!layout.isEmpty()) {
            try (GpuBufferSlice.MappedView mapped = ring.currentBuffer().map(false, true)) {
                OfUniformManager.logUploadOnce("post" + passSlot, layout,
                        OfUniformManager.write(layout, builtinsValues, mapped.data()), builtinsValues);
            }
        }
        try (RenderPass pass = encoder.createRenderPass(descriptor.build())) {
            pass.setPipeline(compiled);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform(PipelineApi.BUILTINS_UNIFORM, ring.currentBuffer());
            pass.setUniform(PipelineApi.SAMPLER_UNIFORM,
                    sceneView != null ? sceneView : fallbackView, sampler);
            PipelineApi.setPostSamplerUniforms(pass, resolver, sampler);
            pass.draw(3, 1, 0, 0);
        }
        ring.rotate();
        // 🔴 GAP-018：本 pass 写过的池槽翻代 ⇒ 下一级的采样器看见刚写的内容，
        //   而任何时刻「被采样的那张图」都不会同时是「正在被写的那张图」。
        //   final 步的槽 0 打的是主目标（不在池里）⇒ 不参与翻代。
        java.util.List<Integer> wrotePoolSlots = new java.util.ArrayList<>();
        for (int writtenSlot : passPlan.attachmentSlots()) {
            if (!(passPlan.isFinal() && writtenSlot == 0)) {
                wrotePoolSlots.add(writtenSlot);
            }
        }
        MrtTerrainPass.advanceWrittenSlots(wrotePoolSlots);
        // 🔬 GAP-020 判据：这一级写完之后立刻取一次 ⇒ 同一臂、同一机位拿到整条链的亮度曲线。
        TargetReadback.probeChainPass(passSlot, passPlan.programName(), wrotePoolSlots);
    }

    /** 写入槽的视图：final 步的槽 0 = 主目标（OF：final 的输出就是屏幕）；其余 = 池的**待写那一代**（GAP-018）。 */
    private static GpuTextureView writtenAttachmentView(dev.vkdisp.pack.PackPostChain.Pass passPlan,
            int colortexSlot, GpuTextureView colorView) {
        GpuTextureView view = (passPlan.isFinal() && colortexSlot == 0)
                ? colorView : MrtTerrainPass.poolWriteView(colortexSlot);
        if (view == null) {
            throw new IllegalStateException(
                    "vkdisp: post pass " + passPlan.programName() + " 附件槽 " + colortexSlot
                            + " 无视图（池尺寸与链不符 —— 注册/生成两侧不同步，X42 同族）");
        }
        return view;
    }

    /**
     * 采样名→视图解析（按名接线，替代旧「18 名一律绑 scene」）。
     *
     * <p>🔖 每个占位分支都返回**显式视图**而非 null —— 「绑 null 看不出根因」正是 h33 那 2702 条的来源；
     * 还没有真值来源的名字（lighttex/vxDepth/dhDepth）同样显式给 colortex0 占位并在此登记，
     * 不假装接好了（sampler3D 族 = GAP-014 原版能力缺失）。
     * {@code noisetex} 已从该清单毕业：包声明的 {@code texture.noise} 是真值，
     * 取不到时由 {@link PackTextures} 兜内置噪声并**一次性点名**（GAP-025 / X11）。
     */
    private static PipelineApi.PostSamplerViewResolver chainResolver(GpuTextureView fallbackView) {
        return name -> {
            // 🔴 GAP-009 素材线：包自带的自定义纹理（BSL 的 noisetex/dirt 等）优先 ——
            //   不接真值 = 这些效果采一张别的图，画面能亮但语义错。
            GpuTextureView custom = PackTextures.view(name);
            if (custom != null) {
                return custom;
            }
            if (name.startsWith("colortex")) {
                try {
                    int slot = Integer.parseInt(name.substring("colortex".length()));
                    GpuTextureView v = MrtTerrainPass.poolView(slot);
                    if (v != null) {
                        return v;
                    }
                } catch (NumberFormatException ignored) {
                    // 非 colortex<数字> 形态走占位（不抛 —— 名字来自固定超集）。
                }
                return fallbackView;
            }
            // OF: gauxN = colortex(N+3)（BSL 生态里 gaux1 = colortex4 —— h44 §4.5 同源判据）。
            if (name.startsWith("gaux")) {
                GpuTextureView g = MrtTerrainPass.poolView(4);
                return g != null ? g : fallbackView;
            }
            if (name.startsWith("depthtex")) {
                // 🔴 GAP-022 ①：开关开着 ⇒ 绑 GL 口径代理（1 − z_en，天空=1.0）；
                //   开关关着 / 代理缺席 ⇒ 回到今天这张反向 Z 原图，缺席那条由 DepthGlProxy
                //   自己打一次 WARN（不许静默换绑 —— X11）。
                //   depthtex1/2 与 0 号同源一起翻，理由见 DepthGlProxy#chooseDepthSource
                //   （GAP-023「三名一张」是<b>另一条</b>缺陷，本轮不动它的观测面）。
                return dev.vkdisp.bridge.DepthGlProxy.chainDepthView(
                        name, MrtTerrainPass.depthView(), fallbackView);
            }
            if (name.startsWith("shadowtex")) {
                GpuTextureView s = ShadowStubs.depthView();
                return s != null ? s : fallbackView;
            }
            if (name.startsWith("shadowcolor")) {
                GpuTextureView s = ShadowStubs.colorView();
                return s != null ? s : fallbackView;
            }
            return fallbackView;
        };
    }

    /**
     * GAP-022 ①：刷新 GL 口径深度代理（建/重建纹理 + 跑那一趟全屏翻转 pass）。
     *
     * <p>🔖 开关<b>关掉</b>时走 {@code release()}，不是「留着但不用」：代理一旦在场，
     * 它就是 {@code depthtex*} 那个分支里「开关开着就用它」的那个「它」—— 留下一张没人维护的旧图
     * 等于给下一轮 A/B 留一个看不见的状态源（h33/h34 那一族的形状）。
     */
    private static void refreshDepthGlProxy(RenderTarget main, CommandEncoder encoder, String label) {
        if (!dev.vkdisp.VkDispConfig.MRT_DEPTH_GL_PROXY.get()) {
            dev.vkdisp.bridge.DepthGlProxy.release();
            return;
        }
        dev.vkdisp.bridge.DepthGlProxy.ensure(main.width, main.height);
        dev.vkdisp.bridge.DepthGlProxy.renderFlipPass(
                encoder, label, MrtTerrainPass.depthView());
    }

    /** 读前重建 + 写后标脏（GAP-017 脏集机制）。 */
    /**
     * 一级 pass 写完之后的**即时**金字塔重建（GAP-020）。
     *
     * <p>惰性重建（「下一个读者之前才建」）在 GAP-018 的双代轮转下必然滞后一次写：
     * 包用隐式导数取级时读到的是**上一代**的金字塔 ⇒ 与它同时读到的 mip0 不同源 ⇒
     * 整帧算成黑（h48 Z3 臂：黑帧上 `deferred1` 的 c0=0，而同一个 pass 的 c4/c5 有内容）。
     */
    private static void regeneratePyramidsForWritten(CommandEncoder encoder, String label,
            dev.vkdisp.pack.PackPostChain.Pass passPlan, java.util.Set<Integer> mipSlots,
            GpuSampler pyramidSampler) {
        java.util.Set<Integer> wroteMipSlots =
                new java.util.LinkedHashSet<>(passPlan.attachmentSlots());
        wroteMipSlots.retainAll(mipSlots);
        if (!wroteMipSlots.isEmpty()) {
            generateMipPyramids(encoder, label, wroteMipSlots, pyramidSampler);
        }
    }

    /**
     * 为脏槽逐级降采样：L 级 = 采样 L−1 级视图的一次全屏 blit（LINEAR 滤波）。
     *
     * <p>🔖 为什么用 blit 管线：`vkdisp:pipeline/blit` = 不翻转全屏三角形 + {@code InSampler}
     * 单附件 —— 与「采上一级、写下一级」完全同形，零新管线。视图方向恒等（同一张纹理的
     * 相邻级），不涉及 p416 的翻转选择。
     */
    /** 金字塔 blit 用的采样器：ClampToEdge + LINEAR（与链采样器是两件事，别混用）。 */
    private static GpuSampler pyramidSampler() {
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
    }

    /**
     * GAP-017/020：链**开跑前**给「刚被写的那一代」建一次降采样金字塔。
     *
     * <p>返回需要金字塔的槽集 —— 循环里每级写完立刻重建（{@code regeneratePyramidsForWritten}）
     * 用的就是同一个集合；两边必须同源，否则链中段读到的 mip 是上一次的残留（GAP-020 的成因）。
     */
    private static java.util.Set<Integer> primeChainPyramids(CommandEncoder encoder, String label,
            dev.vkdisp.pack.PackPostChain.Chain chain) {
        java.util.Set<Integer> mipSlots = chainMipSlotsOrComplain(chain);
        if (!mipSlots.isEmpty()) {
            generateMipPyramids(encoder, label, mipSlots, pyramidSampler());
        }
        return mipSlots;
    }

    private static void generateMipPyramids(CommandEncoder encoder, String label,
            java.util.Set<Integer> slots, GpuSampler linear) {
        CompiledRenderPipeline blit =
                RenderSystem.getCompiledPipelineNullable(PipelineApi.blitPipeline());
        if (blit == null) {
            dev.vkdisp.VkDisp.LOGGER.warn("vkdisp: [GAP-017] blit 管线未编译 ⇒ 本帧跳过 mip 金字塔生成");
            return;
        }
        for (int slot : slots) {
            int levels = MrtTerrainPass.poolLevels(slot);
            if (mipGenLogged.compareAndSet(false, true)) {
                // 🔖 一次性自报：金字塔**真的在跑**与「mip 恰好是未初始化的黑」必须能在日志里分开
                //   （L 臂教训：白消失了，但消失的原因可能是真金字塔，也可能是黑 mip ——
                //    没有自报就没有判据，两个假象长得一模一样）。
                dev.vkdisp.VkDisp.LOGGER.info(
                        "vkdisp: [GAP-017] mip pyramid generating: slot={} levels={}"
                                + " (declared mip slots are regenerated before their first read)",
                        slot, levels);
            }
            for (int level = 1; level < levels; level++) {
                GpuTextureView dst = MrtTerrainPass.poolMipView(slot, level);
                GpuTextureView src = MrtTerrainPass.poolMipView(slot, level - 1);
                if (dst == null || src == null) {
                    break;
                }
                final int mipLevel = level;
                final int mipSlot = slot;
                try (RenderPass pass = encoder.createRenderPass(
                        () -> label + " mip c" + mipSlot + " L" + mipLevel,
                        dst,
                        Optional.of(new Vector4f(0.0F, 0.0F, 0.0F, 1.0F)),
                        null,
                        OptionalDouble.empty())) {
                    pass.setPipeline(blit);
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform(PipelineApi.SAMPLER_UNIFORM, src, linear);
                    pass.draw(3, 1, 0, 0);
                }
            }
        }
    }

    /** 「二分上限生效」自报只打一次。 */
    private static final java.util.concurrent.atomic.AtomicBoolean chainCapLogged =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** 「声明集为空」的告警只打一次。 */
    private static final java.util.concurrent.atomic.AtomicBoolean mipSlotsChecked =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** 金字塔生成自报只打一次（同上纪律）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean mipGenLogged =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * 链的采样器（所有 colortex 采样名共用）。
     *
     * <p>🔬 {@code mrt.chainSamplerLod0} 是 **GAP-016 同族判据**：钉 mip0 ⇒ 包里的
     *   **隐式导数** `texture2D` 不再有机会选到坏 mip（显式 `texture2DLod(…,0)` 不受影响）。
     *   开了它黑帧消失 ⇒ 黑因 = 导数选 mip（BSL 的 `composite` 正是纯 texture2D 读 colortex0）；
     *   代价是包**故意**的高 LOD tap（bloom / 自动曝光计量）全落到 mip0 ⇒ 判据档，不是产品档。
     */
    private static GpuSampler chainSampler() {
        return RenderSystem.getDevice().createSampler(
                com.mojang.renderpearl.api.textures.AddressMode.CLAMP_TO_EDGE,
                com.mojang.renderpearl.api.textures.AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR, FilterMode.LINEAR, 1,
                dev.vkdisp.VkDispConfig.MRT_CHAIN_SAMPLER_LOD0.get()
                        ? OptionalDouble.of(0.0) : OptionalDouble.empty());
    }

    /** 🔬 二分判据：只跑前 N 级（帧尾探针读「最后被跑级」的输出槽 ⇒ 谁抬亮一目了然）。 */    private static int chainPassLimit(java.util.List<dev.vkdisp.pack.PackPostChain.Pass> passes) {
        int maxPasses = Math.min(passes.size(),
                dev.vkdisp.VkDispConfig.MRT_POST_CHAIN_MAX_PASSES.get());
        if (maxPasses != passes.size() && chainCapLogged.compareAndSet(false, true)) {
            dev.vkdisp.VkDisp.LOGGER.warn("vkdisp: [chain] mrt.postChainMaxPasses 生效：只跑 {}/{} 级"
                    + "（**main 此时不被链写** —— 判读对象是最后被跑级的输出槽，不是 main）",
                    maxPasses, passes.size());
        }
        return maxPasses;
    }

    /** 声明集提取 + 「解析断了」告警（空集对 BSL = 异常，不需要）。 */
    private static java.util.Set<Integer> chainMipSlotsOrComplain(
            dev.vkdisp.pack.PackPostChain.Chain chain) {
        java.util.Set<Integer> mipSlots = chain.mipEnabledSlots();
        if (mipSlots.isEmpty() && mipSlotsChecked.compareAndSet(false, true)) {
            dev.vkdisp.VkDisp.LOGGER.warn("vkdisp: [GAP-017] 全链没有任何 `colortexNMipmapEnabled` 声明"
                    + " —— 若当前包确有 bloom/TAA（BSL 有 6 个），是 const 解析断了，不是不需要");
        }
        return mipSlots;
    }

    /** 链执行「接管自报」只打一次（热路径日志 I/O 纪律）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean postChainLogged =
            new java.util.concurrent.atomic.AtomicBoolean();

    private static void ensurePostScratch(RenderTarget main) {
        if (postScratch == null) {
            postScratch = new TextureTarget[PipelineApi.POST_FRAME_WIDTH];
        }
        for (int i = 0; i < postScratch.length; i++) {
            if (postScratch[i] == null) {
                postScratch[i] = new TextureTarget("vkdisp post scratch" + i,
                        main.width, main.height, GpuFormat.RGBA8_UNORM, null);
            } else if (postScratch[i].width != main.width || postScratch[i].height != main.height) {
                postScratch[i].resize(main.width, main.height);
            }
        }
    }

    private static GpuTextureView postScratchView(int index) {
        TextureTarget target = postScratch[index];
        if (target == null) {
            throw new IllegalStateException("vkdisp: post scratch " + index + " not created");
        }
        GpuTextureView view = target.getColorTextureView();
        if (view == null) {
            throw new IllegalStateException("vkdisp: post scratch " + index + " view is null");
        }
        return view;
    }

    private static void ensurePostRings() {
        int n = dev.vkdisp.VkDispVirtualPack.POST_SLOT_COUNT;
        if (postBuiltinsRings == null) {
            postBuiltinsRings = new MappableRingBuffer[n];
            postBuiltinsRingBytes = new int[n];
        }
        for (int slot = 0; slot < n; slot++) {
            final int ringSlot = slot;
            int want = Math.max(BUILTINS_MIN_BYTES,
                    dev.vkdisp.VkDispVirtualPack.postBuiltinsLayout(ringSlot).byteSize());
            if (postBuiltinsRings[ringSlot] == null || want > postBuiltinsRingBytes[ringSlot]) {
                MappableRingBuffer stale = postBuiltinsRings[ringSlot];
                if (stale != null) {
                    try {
                        stale.close();
                    } catch (RuntimeException ex) {
                        dev.vkdisp.VkDisp.LOGGER.warn(
                                "vkdisp: post builtins ring close failed on resize: slot={} reason={}",
                                ringSlot, ex);
                    }
                }
                postBuiltinsRings[ringSlot] = new MappableRingBuffer(
                        () -> "vkdisp builtins post" + ringSlot,
                        GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM,
                        want);
                postBuiltinsRingBytes[ringSlot] = want;
            }
        }
    }
}
