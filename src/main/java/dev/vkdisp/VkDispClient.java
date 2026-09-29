package dev.vkdisp;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
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
        // 让 NeoForge 为本模组的配置自动生成配置界面
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        VkDisp.LOGGER.info("vkdisp: client setup, user={}", Minecraft.getInstance().getUser().getName());
    }
}
