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
 *    触发时机 = NeoForge ClientHooks.fireRenderFramePost —— Minecraft.renderFrame 中
 *    GameRenderer.render() 之后、swapchainBlit（主目标上屏）之前，此刻写 main target 必然出现在屏幕上。
 * 2. 备选：FrameGraphSetupEvent 帧图插 pass —— 调研否决（vanilla clear pass 会随后全清 main target，
 *    图案必被抹掉），不采用；RenderFrameEvent 属官方事件，无需 GAP 登记。
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
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.joml.Vector4f;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;

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
    private static final int LIGHT_MATRIX_BYTES = 64;

    /** 每帧上传的光空间矩阵环形缓冲（与 paramsRing 同为原版 MappableRingBuffer 模式）。 */
    private static MappableRingBuffer lightMatrixRing;

    /**
     * 光空间矩阵环形缓冲（懒创建，渲染线程 / 设备就绪后）。
     *
     * <p><b>P3.1 前置的占位矩阵</b>：当前固定为「平移 +0.3、缩放 0.6」的仿射矩阵，
     * 作用是把「矩阵 uniform 上传 → 顶点着色器变换 → 画面位移」这条链路**变成可像素级判定的事实**
     * （与上一轮 NDC 直写相比，四边形位置必然改变）。P3.1 正式实现时，这里会换成
     * 从光照方向构造的 view-projection 矩阵（视锥/光空间语义由主线后续交付）。
     */
    private static MappableRingBuffer lightMatrixRing() {
        MappableRingBuffer ring = lightMatrixRing;
        if (ring == null) {
            ring = new MappableRingBuffer(
                    () -> "vkdisp light matrix",
                    GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM,
                    LIGHT_MATRIX_BYTES);
            lightMatrixRing = ring;
            // 一次性埋点：矩阵上传链路启用（translate=+0.3, scale=0.6 的占位光空间矩阵）。
            dev.vkdisp.VkDisp.LOGGER.info(
                    "vkdisp: light matrix buffer created (translate=+0.3, scale=0.6, bytes={})",
                    LIGHT_MATRIX_BYTES);
        }
        return ring;
    }

    /** P3.1 占位光照方向（单位向量，指向地面）；后续由世界光照/时间提供。 */
    private static final org.joml.Vector3f LIGHT_DIRECTION =
            new org.joml.Vector3f(0.6F, -1.0F, 0.45F).normalize();

    /** 光空间方向是否已打过埋点。 */
    private static boolean lightSpaceLogged;

    /**
     * P3.1 光空间 view-projection：正交投影（覆盖几何所在范围）× 相机沿光反方向看向原点的视图矩阵。
     *
     * <p>这是影子 pass 的核心：顶点从「世界空间」被变换到「光空间」，深度即阴影贴图。
     * joml 的 {@code ortho(...).lookAt(...)} 语义 = P * V（右乘视图逆），与 std140 mat4 列主序一致。
     */
    private static org.joml.Matrix4f lightSpaceMatrix() {
        org.joml.Vector3f eye = new org.joml.Vector3f(LIGHT_DIRECTION).negate().mul(4.0F);
        if (!lightSpaceLogged) {
            lightSpaceLogged = true;
            dev.vkdisp.VkDisp.LOGGER.info(
                    "vkdisp: light space computed: dir=({}, {}, {}), eye=({}, {}, {}), ortho=[-1.2,1.2]x[-1,1], near=0.1 far=8",
                    LIGHT_DIRECTION.x, LIGHT_DIRECTION.y, LIGHT_DIRECTION.z,
                    eye.x, eye.y, eye.z);
        }
        // ⚠️ zZeroToOne=true：Vulkan 深度范围是 [0,1]；默认的 GL 约定 [-1,1] 会把近半几何裁掉
        // （实测：灰度集中在 16..56 且阴影面积极小，修正后回到中值区）。
        return new org.joml.Matrix4f()
                .ortho(-1.2F, 1.2F, -1.0F, 1.0F, 0.1F, 8.0F, true)
                .lookAt(eye, new org.joml.Vector3f(0.0F, 0.0F, 0.0F), new org.joml.Vector3f(0.0F, 1.0F, 0.0F));
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
     * 离屏渲染目标（P2 前置：图案先画到它上面，再被下一个 pass 采样进主目标）。
     *
     * <p>用原版 {@link TextureTarget}（{@code RenderTarget} 子类，vanilla 内部目标同款）：
     * 自带颜色/深度纹理与需要时的 {@code resize}，避免手工管理 {@code GpuTexture} 生命周期。
     * 之所以需要离屏目标：同一 pass 内既写又采样同一纹理在 Vulkan 属非法反馈回路，
     * 真实 composite 链必须靠中间目标（ping-pong）——这就是最小可运行的 ping-pong 骨架。
     */
    private static TextureTarget offscreenTargetA;
    private static TextureTarget offscreenTargetB;

    /**
     * 按主目标尺寸取第 {@code slot} 个离屏目标（0/1 两个，交替作为 ping-pong 的两端）。
     *
     * <p>尺寸变化时 resize，不每帧重建；两个目标都按主目标尺寸分配，保证中间级分辨率一致。
     */
    private static TextureTarget offscreenTarget(int slot, int width, int height) {
        TextureTarget target = slot == 0 ? offscreenTargetA : offscreenTargetB;
        if (target == null) {
            // 槽 0 带深度附件（P3 前置：图案 pass 写深度、depthviz pass 采样它）；
            // 槽 1 只做颜色 ping-pong，不需要深度。
            target = new TextureTarget(
                    "vkdisp offscreen " + slot,
                    width,
                    height,
                    GpuFormat.RGBA8_UNORM,
                    slot == 0 ? GpuFormat.D32_FLOAT : null);
            if (slot == 0) {
                offscreenTargetA = target;
            } else {
                offscreenTargetB = target;
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
        // 本帧链用到 geometry / shadowed / blit 三条（pattern、composite、depthviz 已注册但不在本帧链中）。
        return PipelineApi.isGeometryPipelineRegistered()
                && PipelineApi.isShadowedPipelineRegistered()
                && PipelineApi.isBlitPipelineRegistered()
                && RenderSystem.getCompiledPipelineNullable(PipelineApi.geometryPipeline()) != null
                && RenderSystem.getCompiledPipelineNullable(PipelineApi.shadowedPipeline()) != null
                && RenderSystem.getCompiledPipelineNullable(PipelineApi.blitPipeline()) != null;
    }

    /**
     * 在主渲染目标（main target）上执行一次全屏绘制。
     *
     * <p>必须在渲染线程调用（RenderFrameEvent.Post 即是）；此处位于 swapchain 上屏之前，
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

        // 三条管线都要就绪（图案 + 合成 + 传递）；任一未编译完成都抛异常，绝不静默少画一个 pass。
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
        CompiledRenderPipeline blit = RenderSystem.getCompiledPipelineNullable(PipelineApi.blitPipeline());
        if (blit == null) {
            throw new IllegalStateException(
                    "vkdisp: blit pipeline not compiled yet: " + PipelineApi.BLIT_LOCATION);
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

        // P3.1 影子 pass 链路（三段，各自不同附件 —— 规避「同一附件第二次 createRenderPass 不生效」）：
        //  Pass 1 阴影贴图：清屏(黑, 深度1.0) → 几何(经光空间矩阵) → offscreen0 的**深度**即阴影贴图
        //  Pass 2 可视化：采样阴影贴图深度 → offscreen1 灰度（depthviz）
        //  Pass 3：offscreen1 → 主目标
        // 说明：图案背景本轮**不进链** —— 阴影贴图只应包含遮挡物深度，背景深度会污染贴图；
        //       图案/合成管线仍注册并通过计数断言（registered=5），只是不在本帧执行。
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
        // Pass 3：offscreen1 → 主目标（最后一级；主目标只由本 pass 写入）。
        // ⚠️ 采样的必须是**本帧链实际写入的那个目标**（实测教训：曾误采样本链未写入的目标）。
        try (RenderPass pass = encoder.createRenderPass(
                () -> label + " 3 (offscreen1 -> main)", colorView, Optional.empty(), null, OptionalDouble.empty())) {
            pass.setPipeline(blit);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform(PipelineApi.SAMPLER_UNIFORM, viewB, sampler);
            pass.draw(3, 1, 0, 0);
        }
        // 原版 PostPass 同款：绘制后再 rotate，保证本帧写入的槽在 GPU 用完前不被复用。
        ring.rotate();
        return new FrameSize(width, height);
    }
}
