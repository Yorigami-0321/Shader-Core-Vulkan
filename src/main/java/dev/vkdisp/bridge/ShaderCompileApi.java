package dev.vkdisp.bridge;
/**
 * 【参考调研】P2.3 阶段源 → SPIR-V 驱动级编译 / 原版 GlslCompiler 公开签名
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 原版 Minecraft 26.3 com.mojang.renderpearl.frontend.shaders.GlslCompiler /
 *    PipelineBuilder（运行平台提供方；Mojang EULA 覆盖）与 com.mojang.renderpearl.util.ShaderCompileException。
 *    许可证：Mojang EULA（原版）→ 只观察 javap 反编译签名与调用点字节码（PipelineBuilder 中
 *    `compileToSpv(Identifier.toString(), 源文本, ShaderType, ShaderDefines, ShaderSource)` 的实参顺序），
 *    零源码文本搬运；参考模组（VulkanMod / Sulkan / Iris）零接触。
 *    → 能否并入本项目（MIT）：可以 —— 本文件是独立编写的薄封装，只调用公开 API，不含被参考方代码
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：原版 PipelineBuilder#generateBackendCreateInfo 的编译调用 —— 每个阶段
 *    `GlslCompiler.compileToSpv(name, source, type, defines, shaderSource)` → SpvModule；
 *    GlslCompiler 构造参数取自 `DeviceInfo.isZZeroToOne()` 与 `DeviceFeatures.shaderDrawParameters()`
 *    （javap 字节码核实，不是猜的默认值）；错误经 ShaderCompileException 携带原文（含 file:line）。
 *    这就是原版管线走的同一条 GLSL→SPIR-V（shaderc）编译路径。
 * 2. 备选：① 把阶段源写成临时 assets 再让 ShaderManager 编译 —— 需要资源重载周期，且要往用户
 *    资源系统塞临时文件，否决；② 自链 shaderc 绑定 —— X17 之前禁止自建原生依赖，否决。
 *    两条否决都不构成自行补充特性，无需 GAP 登记（T12）。
 * 3. 我们的差异点：① 只取「编译成功与否 + SPIR-V 字节数 + 错误原文」纯 Java 视图，
 *    SpvModule 用完即关（本阶段不创建管线，SPIR-V 不外流，04-SPEC §3.1 只读不写）；
 *    ② ShaderSource 桩对 getInclude 返回 null —— 若转译产物仍残留 #include，shaderc 回调会拿到
 *    "not found" 错误并经 ShaderCompileException 显式报出（T11，不静默吞掉残留指令）；
 *    ③ 设备未就绪 / 意外异常一律降级为显式失败视图，绝不抛给业务层（T11 + 调用方打 ERROR）。
 * 4. 许可证核对：本项目 MIT；只调用公开 API 签名，无代码复制（07-CONSTRAINTS L5-L8）。
 * 5. 性能基线：启动/重载期每个阶段一次（冷路径），不做性能优化（17-NATIVE §3.2、T14）。
 */
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import com.mojang.renderpearl.util.ShaderCompileException;
import java.util.Objects;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;

/**
 * 阶段级着色器编译入口（06-MIGRATION §2.1 的 bridge 红线：业务包零 {@code com.mojang.renderpearl} import）。
 *
 * <p>P2.3 用它把「包 → 最终 GLSL 源」（{@code ShaderPackCompiler}，冷路径）接到**驱动级编译**：
 * 源文本经原版 GlslCompiler（shaderc）编到 SPIR-V，成功/失败与错误原文回成纯 Java 视图。
 */
public final class ShaderCompileApi {

    /**
     * 单阶段编译结果（纯数据视图，不含任何原版类型）。
     *
     * @param success 是否编译成功（SPIR-V 产出）
     * @param spvBytes SPIR-V 字节数（成功时 &gt; 0；失败为 0）
     * @param error   失败时的错误原文（ShaderCompileException 消息，含 file:line；成功为空串）
     */
    public record StageResult(boolean success, int spvBytes, String error) {
        /** 归一构造：error 永不为 null。 */
        public StageResult {
            error = error == null ? "" : error;
        }
    }

    /** 空 include 桩：转译产物不应残留 #include；残留时 shaderc 回调拿到 null → 显式 "not found"。 */
    private static final ShaderSource NO_INCLUDES = new ShaderSource() {
        @Override
        public String getShader(Identifier id, ShaderType type) {
            return null;
        }

        @Override
        public CachedIncludeSource getInclude(Identifier id) {
            return null;
        }

        @Override
        public void close() {
            // 无资源可释放。
        }
    };

    private ShaderCompileApi() {}

    /**
     * 把一个阶段的最终 GLSL 源编译到 SPIR-V（原版管线同款 shaderc 路径）。
     *
     * <p>永不抛异常：设备未就绪 / ShaderCompileException / 意外 RuntimeException 全部降级为
     * {@code success=false} 的失败视图（T11：错误原文进 error 字段，由业务层打 ERROR）。
     *
     * @param debugName 编译诊断名（原版传 Identifier.toString()；本方格式同构，如
     *                   {@code vkdisp:pack/vkdisp-fixture-dir/composite.fsh}，出现在 shaderc 错误原文里）
     * @param source    阶段最终 GLSL 源文本（已过 include 展开与 OF 转译）
     * @param vertex    true = 顶点阶段，false = 片元阶段
     */
    public static StageResult compileStage(String debugName, String source, boolean vertex) {
        Objects.requireNonNull(debugName, "vkdisp: debugName 不许为 null");
        if (source == null || source.isBlank()) {
            return new StageResult(false, 0, "源文本为 null/空白，无法编译");
        }
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return new StageResult(false, 0, "GPU 设备未就绪（RenderSystem.tryGetDevice() == null）");
        }
        DeviceInfo info = device.getDeviceInfo();
        // 构造参数与原版 PipelineBuilder 完全同源（javap 核实），不猜默认值（X9）。
        try (GlslCompiler compiler = new GlslCompiler(
                info.isZZeroToOne(), info.features().shaderDrawParameters())) {
            ShaderType type = vertex ? ShaderType.VERTEX : ShaderType.FRAGMENT;
            SpvModule module = compiler.compileToSpv(debugName, source, type, ShaderDefines.EMPTY, NO_INCLUDES);
            try {
                int bytes = module.spv() == null ? 0 : module.spv().remaining();
                if (bytes <= 0) {
                    return new StageResult(false, 0, "SPIR-V 产出为空（0 字节）");
                }
                return new StageResult(true, bytes, "");
            } finally {
                module.close();
            }
        } catch (ShaderCompileException e) {
            // 原版错误原文（含 file:line:col）原样回传，不改写不吞（T11）。
            String message = e.getMessage();
            return new StageResult(false, 0, message == null ? e.getClass().getName() : message);
        } catch (RuntimeException e) {
            String message = e.getMessage();
            return new StageResult(false, 0,
                    e.getClass().getName() + ": " + (message == null ? "(no message)" : message));
        }
    }
}
