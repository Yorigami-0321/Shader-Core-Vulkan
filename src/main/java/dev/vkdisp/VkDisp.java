package dev.vkdisp;
/**
 * 【参考调研】P0.1 骨架 / 官方 MDK 骨架
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    NeoForge MDK 26.3 模板（ModDevGradle）许可证 = MIT —— 证据：仓库根 TEMPLATE_LICENSE.txt（NeoForged，MIT）+ 官方仓库 LICENSE 文件；
 *    → 能否并入本项目（MIT）：可以（MIT 同族可并入，保留署名，已随分发）
 *    → 例外条款：无；不含任何 LGPL / GPL / ARR 内容
 * 1. 官方/主实现：NeoForge MDK 26.3 (ModDevGradle 2.0.147, commit eec248c) — 参考了：@Mod 注册方式、IEventBus 注入、配置注册。
 * 2. 备选：无（本阶段只要骨架）
 * 3. 我们的差异点：仅做 P0.1 骨架，不写渲染管线；后续按 01-DEV-LOOP.md 顺序推进。
 * 4. 许可证核对：MIT（本项目）；MDK 模板 MIT；不并入 LGPL/GPL 代码。
 * 5. 性能基线：P0.1 冷路径（启动日志），无优化需求。
 */

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
