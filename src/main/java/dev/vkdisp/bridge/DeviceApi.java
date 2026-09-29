package dev.vkdisp.bridge;
/**
 * 【参考调研】P0.2 后端确认 / 原版 renderpearl 设备 API
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 原版 Minecraft 26.3 客户端 jar 内 com.mojang.renderpearl.api.device.*（运行平台与官方 API 提供方）。
 *    许可证：Mojang EULA（原版）+ NeoForge LGPL-3.0（运行时）→ 只观察 javap 签名与官方调用点日志格式，
 *    零源码文本搬运，不复制其实现。参考模组（VulkanMod/Sulkan 等）零接触。
 *    → 能否并入本项目（MIT）：本文件为独立实现的薄封装，仅调用公开 API，可并入
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：原版 net.minecraft.client.Minecraft 构造后端选择链（458-531 行官方用法范本：
 *    device.getDeviceInfo() → backendName()/name()/vendorName()/driverInfo()）
 * 2. 备选：无（官方 API 完整，见 GAP 判定：不需要登记）
 * 3. 我们的差异点：只暴露纯 Java 视图（backendKind()/deviceInfo()），业务包零 renderpearl import；
 *    设备未就绪时返回 "UNKNOWN" / 抛出明确异常，绝不静默。
 * 4. 许可证核对：本项目 MIT；只调用公开 API 签名，无代码复制。
 * 5. 性能基线：启动期一次性调用，无热路径需求。
 */
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.GpuDevice;

/**
 * 原版渲染 API 唯一入口（06-MIGRATION.md §2.1）。
 *
 * <p>本类是唯一允许 import {@code com.mojang.renderpearl.*} 的业务可见入口；
 * 向业务包暴露纯 Java 视图 {@link DeviceInfoView}，隔离原版类型变化。
 */
public final class DeviceApi {
    /** 设备信息的纯 Java 视图（业务包使用，不含任何原版类型）。 */
    public record DeviceInfoView(String backendName, String name, String vendorName, String driverInfo) {}

    private DeviceApi() {}

    /** GPU 设备是否已创建（Minecraft 构造期 initRenderer 之后为 true）。 */
    public static boolean deviceReady() {
        return RenderSystem.tryGetDevice() != null;
    }

    /**
     * 当前图形后端类型："Vulkan" / "OpenGL"（原版 backendName 原值）。
     * 设备未就绪时返回 "UNKNOWN"，绝不静默假装成功。
     */
    public static String backendKind() {
        DeviceInfoView view = deviceInfoOrNull();
        return view == null ? "UNKNOWN" : view.backendName();
    }

    /** 设备信息视图；设备未就绪时抛 {@link IllegalStateException}（不允许无声失败）。 */
    public static DeviceInfoView deviceInfo() {
        DeviceInfoView view = deviceInfoOrNull();
        if (view == null) {
            throw new IllegalStateException("vkdisp: GPU device not ready yet (RenderSystem.tryGetDevice() == null)");
        }
        return view;
    }

    private static DeviceInfoView deviceInfoOrNull() {
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return null;
        }
        DeviceInfo info = device.getDeviceInfo();
        return new DeviceInfoView(info.backendName(), info.name(), info.vendorName(), info.driverInfo());
    }
}
