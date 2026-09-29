package dev.vkdisp;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

/**
 * 模组主类（公共侧）。
 *
 * <p>定位：基于 Minecraft 原版自带的 Vulkan 渲染后端，实现一个能加载
 * OptiFine / Iris 格式着色器包的引擎。不依赖、也不替代任何第三方前置。
 *
 * <p>Phase 0 目标：本类保持最小，只负责注册配置与客户端入口，
 * 第一个可见产物是「屏幕上出现一个由自定义 RenderPipeline 画出的全屏图案」。
 */
@Mod(VkDisp.MOD_ID)
public final class VkDisp {
    /** 模组 id，必须与 gradle.properties 的 mod_id 及 neoforge.mods.toml 一致。 */
    public static final String MOD_ID = "vkdisp";

    /** 统一日志。 */
    public static final Logger LOGGER = LogUtils.getLogger();

    // FML 会识别 IEventBus / ModContainer 这类参数并自动注入
    public VkDisp(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, VkDispConfig.SPEC);
    }
}
