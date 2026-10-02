package dev.vkdisp;
/**
 * 【参考调研】P0.1 骨架 / 官方 MDK 骨架
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    NeoForge MDK 26.3 模板（ModDevGradle）许可证 = MIT —— 证据：仓库根 TEMPLATE_LICENSE.txt（NeoForged，MIT）+ 官方仓库 LICENSE 文件；
 *    → 能否并入本项目（MIT）：可以（MIT 同族可并入，保留署名，已随分发）
 *    → 例外条款：无；不含任何 LGPL / GPL / ARR 内容
 * 1. 官方/主实现：NeoForge MDK 26.3 (ModDevGradle 2.0.147, commit eec248c) — 参考了：@Mod 注册方式、IEventBus 注入、配置注册。
 *    P0.2 断言出处：docs/08-TESTING.md §2 断言格式 + 原版 net.minecraft.client.Minecraft 529-531 行官方用法范本
 *    （device.getDeviceInfo() 后端/设备日志）+ 本轮 task-4 调研报告（后端选择链与 --graphicsBackend 开关）。
 *    断言实现本体在 bridge/DeviceApi.java（其【参考调研】含合规第 0 条核对）。
 * 2. 备选：无（本阶段只要骨架）
 * 3. 我们的差异点：P0.1 骨架 + P0.2 后端断言（经 bridge/DeviceApi 打 backend/device 日志，断言失败打 ERROR）；
 *    不写渲染管线，后续按 01-DEV-LOOP.md 顺序推进。
 * 4. 许可证核对：MIT（本项目）；MDK 模板 MIT；不并入 LGPL/GPL 代码。
 * 5. 性能基线：P0.1 冷路径（启动日志），无优化需求。
 */

import dev.vkdisp.bridge.DeviceApi;
import dev.vkdisp.screen.PackPickerScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * 模组客户端入口。本模组是纯客户端模组（渲染引擎），不会在专用服务端加载。
 *
 * <p>Phase 0 的接线点就在这里：确认游戏确实跑在 Vulkan 后端上，并打一条可核对的日志。
 */
@Mod(value = VkDisp.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = VkDisp.MOD_ID, value = Dist.CLIENT)
public final class VkDispClient {
    public VkDispClient(ModContainer container) {
        // 【临时 · 测试用】包选择屏取代 NeoForge 自动生成的 ConfigurationScreen。
        // 原版 ConfigurationScreen 是 final 类，无法继承加选项卡；要挂按钮只能自建屏。
        // 本屏内附「完整 TOML 配置」按钮回原配置页，原有配置入口不丢。
        // 待形态确定（是否像 Iris 那样挂进视频设置）后可能改走别的接线。
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (modContainer, parentScreen) -> new PackPickerScreen(parentScreen));
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        VkDisp.LOGGER.info("vkdisp: client setup, user={}", Minecraft.getInstance().getUser().getName());
        // P0.2 关键断言（08-TESTING.md §2）：必须打后端类型与设备名，且绝不能是 OpenGL。
        // enqueueWork 保证在主线程（渲染线程）执行，此时后端/设备已在 Minecraft 构造期创建完毕。
        event.enqueueWork(VkDispClient::logBackendAssertion);
    }

    /**
     * P0.2 关键断言：日志打印后端类型与设备名；不是 Vulkan 就打 ERROR，绝不静默。
     *
     * <p>值域注意：原版 backendName() 的原值是 "Vulkan" / "OpenGL"（首字母大写，不是全大写），
     * 比较必须用 "Vulkan"，勿按文档字面写成 "VULKAN" 导致永远失配。
     */
    private static void logBackendAssertion() {
        if (!DeviceApi.deviceReady()) {
            VkDisp.LOGGER.error("vkdisp: P0.2 断言失败 —— GPU 设备未就绪，无法确认后端（预期此时 RenderSystem.tryGetDevice() 非空）");
            return;
        }
        String backend = DeviceApi.backendKind();
        VkDisp.LOGGER.info("vkdisp: backend={}, device={}", backend, DeviceApi.deviceInfo().name());
        if (!"Vulkan".equals(backend)) {
            VkDisp.LOGGER.error("vkdisp: P0.2 断言失败 —— 当前后端是 {}，必须是 Vulkan（检查 --graphicsBackend 启动参数与 options.txt）", backend);
        }
    }
}
