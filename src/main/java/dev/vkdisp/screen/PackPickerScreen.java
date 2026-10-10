package dev.vkdisp.screen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dev.vkdisp.VkDisp;
import dev.vkdisp.VkDispConfig;
import dev.vkdisp.pack.ShaderPackScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;

/**
 * 【临时 · 测试用】包选择屏：下拉直选 {@code shaderpacks/} 里的光影包。
 *
 * <p><b>为什么存在</b>：正式路径是改 {@code vkdisp-client.toml} 的 {@code shaderPack}，
 * 靠 FML {@code ConfigWatcher} → {@code Reloading} →资源重载生效。测试时反复改文件
 * 太慢，本屏提供一次点击即改值 + 落盘 + 重载。
 *
 * <p><b>定位与边界</b>（改动前必读）：
 * <ul>
 *   <li>🟡<b>临时工具，非最终形态</b>：真正的入口应当像 Iris 那样挂在视频设置里；
 *       本项目的 mixin 预算全部留给管线装配层（{@code MIXIN_CONFIG_COUNT = 1}，
 *       注入点登记表见 {@code docs/04-SPEC.md} §5.0），不改原版菜单，
 *       故先落在模组配置页位置做测试用。</li>
 *   <li>替换掉 NeoForge 自动生成的 {@code ConfigurationScreen}（该类 {@code final}，
 *       无法继承加选项卡）—— 所以本屏内附一个「完整 TOML」按钮回原配置页，
 *       避免把原有配置入口弄丢。</li>
 *   <li>选中即<b>立即生效</b>（落盘 + 资源重载），不等「完成」按钮：测试要的就是即时反馈。</li>
 * </ul>
 *
 * <p><b>生效链</b>：本屏直接 {@code ConfigValue.set} + {@code save()} 落盘，
 * 落盘本身触发 FML {@code FileWatcher} → {@code ModConfigEvent.Reloading} →
 * {@link dev.vkdisp.VkDispConfigHotReload} 边沿检测到 {@code shaderPack} 变化 →
 * {@code minecraft.execute(reloadResourcePacks)}。**本屏不自己调重载**——
 * 走与外部改文件完全同一条链（单一事实来源，避免两条生效路径行为分叉）。
 *
 * <p><b>失败语义</b>（T11）：写值 / 落盘失败 → ERROR 原文 + 状态行显示失败，
 * 绝不假装成功（不重载 = 用户会以为切了包其实没切）。
 *
 * <p>许可证：本文件为独立编写，只调用原版公开 API（签名经 javap 核实），
 * 不含任何外部项目代码（07-CONSTRAINTS §〇 P1）。
 */
public final class PackPickerScreen extends Screen {

    /** 行高（逻辑像素）。 */
    private static final int ROW_H = 20;
    /** 左右留白。 */
    private static final int MARGIN = 10;
    /** 页眉文本区高度（两行）。 */
    private static final int TOP_H = 30;

    private final Screen parent;
    private final Path inventoryDir;

    /** 候选状态（构造时构建一次；下拉选中只改配置不重建候选）。 */
    private PackPickerCandidates.Result candidates;

    /** 最近一次动作结果（页眉第二行）。 */
    private String lastAction = "";

    /** 本屏是否已触发过至少一次写值（用于关屏时提示未落盘的语义，暂只打日志）。 */
    private boolean dirty;

    /**
     * 工厂。
     *
     * @param parent 返回时回退的父屏（原版配置页或 null = 回游戏）
     */
    public PackPickerScreen(Screen parent) {
        super(Component.literal("vkdisp pack picker"));
        this.parent = parent;
        Minecraft minecraft = Minecraft.getInstance();
        this.inventoryDir = minecraft.gameDirectory.toPath().resolve("shaderpacks");
    }

    @Override
    protected void init() {
        // 每次 init 都重建候选（重扫按钮靠 rebuildWidgets → init 走这条路）
        candidates = PackPickerCandidates.build(
                ShaderPackScanner.scan(inventoryDir),
                VkDispConfig.SHADER_PACK.get(),
                Files.isDirectory(inventoryDir));

        List<PackPickerCandidates.Choice> choices = candidates.choices();
        // 当前值不在库存时补一条占位（不改配置，只让用户看见「你配的那个包没了」）
        List<PackPickerCandidates.Choice> shown = new java.util.ArrayList<>(choices);
        if (!candidates.currentInList()) {
            shown.add(new PackPickerCandidates.Choice(
                    PackPickerCandidates.missingLabel(),
                    PackPickerCandidates.missingLabel()));
        }

        String initial = PackPickerCandidates.initialSelection(candidates);
        int y = TOP_H + 8;
        // 显示函数必须走 label 而不是 value：shaderPack 的「自动」态值是**空串**，
        // 直接渲染值会让按钮一片空白（2026-10-02 用户实测缺陷）。见 PackPickerCandidates#labelOf。
        addRenderableWidget(CycleButton.builder(
                        value -> Component.literal(PackPickerCandidates.labelOf(candidates, value)), initial)
                .withValues(shown.stream().map(PackPickerCandidates.Choice::value).toList())
                .displayOnlyValue()
                .create(MARGIN, y, Math.max(120, width - MARGIN * 2 - 130), ROW_H,
                        Component.literal("shaderPack"), this::onPick));
        addRenderableWidget(Button.builder(Component.literal("重扫"), b -> {
                    // 磁盘上可能刚放了新包 → 重建控件时 init() 会重扫
                    rebuildWidgets();
                    VkDisp.LOGGER.info("vkdisp: pack picker rescan: {}",
                            PackPickerCandidates.statusLine(candidates));
                })
                .bounds(width - MARGIN - 122, y, 60, ROW_H).build());
        addRenderableWidget(Button.builder(Component.literal("完成"), b -> onClose())
                .bounds(width - MARGIN - 58, y, 58, ROW_H).build());

        addRenderableWidget(Button.builder(Component.literal("打开包选项（本包）"), b -> openPackOptions())
                .bounds(MARGIN, y + ROW_H + 6, 160, ROW_H).build());
        addRenderableWidget(Button.builder(Component.literal("完整 TOML 配置"), b -> openVanillaConfig())
                .bounds(MARGIN + 166, y + ROW_H + 6, 160, ROW_H).build());
    }

    // ---------------------------------------------------------------- 动作

    /**
     * 下拉选中 → 写配置 + 落盘。
     *
     * <p>不直接调 {@code reloadResourcePacks()}：落盘会被FML {@code FileWatcher} 捕获 →
     * {@code Reloading} → 热加载类检测到 {@code shaderPack} 变化后自己重载（单一路径）。
     */
    private void onPick(CycleButton<String> button, String selected) {
        String target = PackPickerCandidates.resolve(selected);
        String before = VkDispConfig.SHADER_PACK.get();
        if (target.equals(before)) {
            lastAction = "未变化（" + describe(before) + "）";
            return;
        }
        try {
            VkDispConfig.SHADER_PACK.set(target);
            VkDispConfig.SHADER_PACK.save();
            dirty = true;
            lastAction = "已落盘 " + describe(before) + " → " + describe(target)
                    + "（等待配置热加载触发重载）";
            //T11：切换必须显式留痕，这行是本屏的日志锚点
            VkDisp.LOGGER.info("vkdisp: pack picker apply: '{}' -> '{}'", before, target);
        } catch (Throwable t) {
            lastAction = "写值/落盘失败：" + t.getMessage();
            VkDisp.LOGGER.error("vkdisp: pack picker apply failed: target='{}'", target, t);
        }
    }

    /** 打开本包选项屏（复用 P4.3 的 {@link PackOptionsScreen}）。 */
    private void openPackOptions() {
        Minecraft minecraft = Minecraft.getInstance();
        PackOptionsScreen screen = PackOptionsScreen.create(minecraft);
        if (screen == null) {
            // create 已打 WARN（无可选包）——本屏再补一条，让用户知道按钮为什么没反应
            lastAction = "包选项屏打开失败（无可选包，详见日志）";
            return;
        }
        minecraft.setScreenAndShow(screen);
    }

    /** 回 NeoForge 自动生成的完整 TOML 配置页（不丢原有配置入口）。 */
    private void openVanillaConfig() {
        Minecraft minecraft = Minecraft.getInstance();
        ModList.get().getModContainerById(VkDisp.MOD_ID).ifPresentOrElse(
                container -> minecraft.setScreenAndShow(
                        new ConfigurationScreen(container, this)),
                // 理论不可达（@Mod 已注册）；不可达时也不静默 —— 显式失败（T11）
                () -> lastAction = "找不到 vkdisp 的 ModContainer（异常，请看日志）");
        VkDisp.LOGGER.info("vkdisp: pack picker open vanilla config screen");
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    public void extractRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(gui, mouseX, mouseY, partialTick);
        gui.text(font, "vkdisp 临时包选择（测试用）", MARGIN, 6, 0xFFFFFFFF);
        String status = PackPickerCandidates.statusLine(candidates);
        gui.text(font, fit(status, width - MARGIN * 2), MARGIN, 18, 0xFFAAAAAA);
        if (!lastAction.isEmpty()) {
            gui.text(font, fit(lastAction, width - MARGIN * 2), MARGIN, TOP_H - 2, 0xFF80C0FF);
        }
    }

    /** 超宽文本截断。 */
    private String fit(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("..."))) + "...";
    }

    @Override
    public void onClose() {
        Minecraft minecraft = Minecraft.getInstance();
        if (dirty) {
            // 重载由配置热加载链异步触发，此处只留痕，不重复调（避免双重重载）
            VkDisp.LOGGER.info("vkdisp: pack picker closed after apply (reload handled by hot-reload chain)");
        }
        minecraft.setScreenAndShow(parent);
    }

    /** 配置值的可读描述（空串显示为「自动」，与 UI 文案一致）。 */
    private static String describe(String value) {
        if (value == null || value.isEmpty()) {
            return "自动";
        }
        if (PackPickerCandidates.NONE.equals(value)) {
            return "passthrough";
        }
        return value;
    }
}
