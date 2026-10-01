package dev.vkdisp.screen;
/**
 * 【参考调研】P4.3 选项 GUI（pack 声明的选项可渲染可改）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 原版 Minecraft 26.3 公共控件 API（Mojang EULA —— javap 核实的公开签名：
 *    Screen.extractRenderState(GuiGraphicsExtractor,int,int,float) / init() / rebuildWidgets() /
 *    addRenderableWidget、CycleButton.builder(...).withValues(...).displayOnlyValue().create(...)、
 *    EditBox(Font,int,int,int,int,Component)+setResponder、Button.builder(...).bounds(...).build()、
 *    GuiGraphicsExtractor.text(Font,String,int,int,int)、Minecraft.setScreenAndShow ——
 *    只观察签名与调用语义，零源码文本搬运）；
 *    ② 本仓库 docs/08-TESTING.md §1 P4.3 验收行（"pack 声明的选项能渲染并能改 | 截图"）；
 *    ③ docs/04-SPEC.md §3.5（选项值语义在 PackOptions —— 本类只做布局与转发，不自造值语义）；
 *    ④ docs/07-CONSTRAINTS.md T5（业务包不 import com.mojang.* —— 本类只 import net.minecraft.*）
 *    与 T11（打开失败 / 坏值必须显式 WARN）。
 *    → 能否并入本项目（MIT）：可以 —— 只调用原版公开 API，布局与业务逻辑全部自研
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：原版视频设置类屏幕的通用骨架（屏幕 = init() 里摆控件 + extractRenderState
 *    里画静态文本；状态→控件单向重建）。控件清单按 javap 26.3 实测签名选用，不参考其具体布局代码。
 * 2. 备选：① 自绘整屏（不加控件，全部手绘 + 手写命中测试）—— 否决（重新发明焦点/命中/键盘导航，
 *    收益为零且更容易出错）；② 嵌进原版 VideoSettingsScreen 的选项页 —— 否决（那是原版选项的容器，
 *    动态塞 284 个包选项会碰其排序/翻译键约定，且打开路径必然牵扯输入注入）；③ 只做只读展示页
 *    —— 否决（验收行明确要求"能改"）。
 * 3. 我们的差异点：
 *    ① **会话分离**：布局无业务状态 —— 值/基线/触碰/提交全在纯 Java 的
 *       {@code PackOptionsSession}（可单测），本类只负责控件↔会话的单向重建；
 *    ② **改值三入口归一**：控件回调、配置驱动（{@link PackOptionsDrive}）、完成前终同步，
 *       全部落到 {@code session.set} 同一方法（同一归一化、同一诊断通道）；
 *    ③ **完成 = 存盘 + 关屏 + 资源重载**（改动必须重新生成源才进着色器），放弃 = ESC/onClose
 *       直接丢弃（存储文件不写 —— 持久化只在"完成"发生）；
 *    ④ 双列分页网格（页大小按屏幕几何计算）—— 不做滚动列表（冷路径 UI，够用即可，T14）。
 * 4. 许可证核对结论：本项目 MIT；只调用原版公开 API（javap 签名核实），零第三方代码复制；
 *    T5 红线自查：本文件 import 无 com.mojang.*、无 sodium/caffeinemc。
 * 5. 性能基线：❄️ 冷路径（开屏一次、改值一次、完成一次；重建为 30 控件级），
 *    不做任何性能优化（18-PARALLEL §7.7、07-CONSTRAINTS T14）。
 */
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.vkdisp.VkDisp;
import dev.vkdisp.VkDispConfig;
import dev.vkdisp.config.OptionDiagnostic;
import dev.vkdisp.config.PackOptionStore;
import dev.vkdisp.config.PackOptions;
import dev.vkdisp.config.PackOptionsSession;
import dev.vkdisp.pack.Option;
import dev.vkdisp.pack.PackCompositeSource;
import dev.vkdisp.pack.ShaderPack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 选项屏幕（P4.3）：把所选包声明的选项渲染成可改的控件网格，并把"完成"落进持久化与着色器。
 *
 * <p><b>打开</b>（{@link #create}）：按 {@code shaderPack} 三态取当前生效包（{@code
 * PackCompositeSource.loadSelectedForOptions}，不编译）→ 载入 {@link PackOptionStore} →
 * 构造 {@link PackOptionsSession}（默认 → profile → 存储回放，与生效链同序）。
 *
 * <p><b>渲染</b>：双列分页网格；值列表非空且当前值在列内 → {@link CycleButton}（循环选值），
 * 否则 {@link EditBox}（自由文本）；页眉画 包名/选项数/触碰数/页码 + 最近一次改值结果。
 *
 * <p><b>改值</b>：控件回调与外部驱动（{@link PackOptionsDrive}）都进
 * {@code PackOptionsSession.set}（归一化/诊断单通道）；驱动路径额外重建控件
 * （状态→控件单向同步），控件路径仅在值被钳制/拒绝时重建。
 *
 * <p><b>完成</b>：文本框终同步 → 触碰差分提交进存储 → 落盘 {@code config/vkdisp-pack-options.properties}
 * → 关屏 → {@code reloadResourcePacks()}（重生成源，改动进着色器）。<b>放弃</b>（ESC / 放弃按钮）
 * = {@link #onClose()} 默认路径：不写文件，一切照旧。
 *
 * <p><b>已知未覆盖（登记）</b>：① 真实鼠标/键盘输入路径未实测（本环境无输入注入；
 * 控件回调与驱动路径共用同一 {@code session.set} 入口，回调本身无独立取证）；
 * ② 滑条控件未做（BSL 的 251 个 slider 选项以循环按钮渲染，功能等价、形态不同）；
 * ③ 开屏期间外部切包：会话是开屏快照，完成仍按包键隔离落盘（不会写错包），但显示可能过期；
 * ④ 菜单态（未进世界）开屏未取证；⑤ 所选包与生效包的编译选包分叉见
 * {@code PackCompositeSource.loadSelectedForOptions} javadoc。
 */
public final class PackOptionsScreen extends Screen {

    /** 行高（逻辑像素）。 */
    private static final int ROW_H = 20;
    /** 双列网格。 */
    private static final int COLUMNS = 2;
    /** 屏幕左右留白。 */
    private static final int MARGIN = 8;
    /** 页眉文本区高度（两行）。 */
    private static final int TOP_H = 28;
    /** 页脚按钮行高度。 */
    private static final int BOTTOM_H = 28;

    private final PackOptionStore store;
    private PackOptionsSession session;

    /** 当前页的标签几何（extractRenderState 画文本用；init 时重建）。 */
    private final List<Row> rows = new ArrayList<>();
    /** 当前页的自由文本框（完成前终同步用；init 时重建）。 */
    private final List<TextInput> textInputs = new ArrayList<>();
    /** 当前布局的每页容量（init 计算；页码显示与驱动翻页共用）。 */
    private int layoutPageSize = 1;
    /** 页眉第二行的最近动作（改值结果 / 重置结果；空 = 尚无动作）。 */
    private String lastAction = "";

    /** 一行标签：选项名 + 绘制原点 + 标签可用宽。 */
    private record Row(String label, int x, int y, int labelWidth) {}

    /** 自由文本框与它的选项名（终同步时按名回写会话）。 */
    private record TextInput(String name, EditBox box) {}

    private PackOptionsScreen(PackOptionsSession session, PackOptionStore store) {
        super(Component.literal("vkdisp pack options"));
        this.session = session;
        this.store = store;
    }

    /**
     * 开屏工厂：三态选择取当前生效包 + 载存储 + 构造会话。
     * 无可选包（库存空 / 选择 none / 指定名不存在）→ 返回 null 并 WARN（T11，不硬开空屏）。
     */
    public static PackOptionsScreen create(Minecraft minecraft) {
        String profile = VkDispConfig.PACK_PROFILE.get();
        String selection = VkDispConfig.SHADER_PACK.get();
        Path gameDir = minecraft.gameDirectory.toPath();
        Optional<ShaderPack> pack = PackCompositeSource.loadSelectedForOptions(
                gameDir.resolve("shaderpacks"), selection);
        if (pack.isEmpty()) {
            VkDisp.LOGGER.warn(
                    "vkdisp: pack options screen open failed: no selectable pack"
                            + "（selection='{}' inventory 缺包 / none / 包无 composite）",
                    selection);
            return null;
        }
        PackOptionStore store = PackOptionStore.load(PackOptionStore.pathFor(gameDir));
        for (String warning : store.loadWarnings()) {
            VkDisp.LOGGER.warn("vkdisp: pack option store: {}", warning);
        }
        PackOptionsSession session = PackOptionsSession.create(pack.get(), profile, store);
        for (OptionDiagnostic diagnostic : session.buildDiagnostics()) {
            VkDisp.LOGGER.warn("vkdisp: pack options session: {}", diagnostic.format());
        }
        VkDisp.LOGGER.info(
                "vkdisp: pack options screen opened: pack={} options={} changed={} profile='{}' storeEntries={}",
                session.packName(), session.definitions().size(), session.changedCount(),
                session.profileName(), store.size());
        return new PackOptionsScreen(session, store);
    }

    // ---------------------------------------------------------------- 布局

    @Override
    protected void init() {
        rows.clear();
        textInputs.clear();

        int gridTop = TOP_H;
        int footerY = height - BOTTOM_H;
        int rowsPerColumn = Math.max(1, (footerY - 4 - gridTop) / ROW_H);
        layoutPageSize = rowsPerColumn * COLUMNS;
        int columnWidth = (width - MARGIN * 2) / COLUMNS;
        int labelWidth = Math.max(56, columnWidth / 2 - 4);

        List<Option> page = session.pageOptions(session.page(), layoutPageSize);
        for (int i = 0; i < page.size(); i++) {
            Option option = page.get(i);
            int column = i / rowsPerColumn;
            int row = i % rowsPerColumn;
            int x = MARGIN + column * columnWidth;
            int y = gridTop + row * ROW_H;
            rows.add(new Row(option.name(), x + 2, y + 6, labelWidth - 6));
            addOptionWidget(option, x + labelWidth, y, Math.max(40, columnWidth - labelWidth - 6));
        }
        addFooter(footerY + 2);
    }

    /** 一个选项 → 循环按钮（值列表内）或文本框（自由文本 / 当前值不在列表）。 */
    private void addOptionWidget(Option option, int x, int y, int widgetWidth) {
        PackOptions options = session.options();
        String current = options.value(option.name());
        List<String> values = option.values();
        if (values != null && !values.isEmpty() && values.contains(current)) {
            CycleButton<String> button = CycleButton.builder(Component::literal, current)
                    .withValues(values)
                    .displayOnlyValue()
                    .create(x, y, widgetWidth, ROW_H, Component.literal(option.name()),
                            (ignored, chosen) -> onWidgetSet(option.name(), chosen, false));
            addRenderableWidget(button);
        } else {
            EditBox box = new EditBox(font, x, y, widgetWidth, ROW_H,
                    Component.literal(option.name()));
            box.setMaxLength(128);
            box.setValue(current == null ? "" : current);
            // responder 后挂：setValue 的初始值不触发回写（避免开屏即产生改值日志）。
            box.setResponder(text -> onWidgetSet(option.name(), text, true));
            addRenderableWidget(box);
            textInputs.add(new TextInput(option.name(), box));
        }
    }

    /** 页脚：翻页 | 放弃 | 重置本包 | 完成。 */
    private void addFooter(int y) {
        addRenderableWidget(Button.builder(Component.literal("◀"), b -> turnPage(-1))
                .bounds(MARGIN, y, 40, 20).build());
        addRenderableWidget(Button.builder(Component.literal("▶"), b -> turnPage(+1))
                .bounds(MARGIN + 44, y, 40, 20).build());

        int right = width - MARGIN;
        addRenderableWidget(Button.builder(Component.literal("完成"), b -> commitAndClose())
                .bounds(right - 76, y, 76, 20).build());
        right -= 76 + 4;
        addRenderableWidget(Button.builder(Component.literal("重置本包"), b -> resetPack())
                .bounds(right - 64, y, 64, 20).build());
        right -= 64 + 4;
        addRenderableWidget(Button.builder(Component.literal("放弃"), b -> onClose())
                .bounds(right - 56, y, 56, 20).build());
    }

    /** 翻页（页码钳到合法区间）→ 重建控件。 */
    private void turnPage(int delta) {
        int target = session.page() + delta;
        int clamped = session.page(target, layoutPageSize);
        VkDisp.LOGGER.info("vkdisp: pack options screen page: {}（共 {} 页）",
                clamped + 1, session.pageCount(layoutPageSize));
        rebuildWidgets();
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    public void extractRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(gui, mouseX, mouseY, partialTick);
        String header = "pack=" + session.packName()
                + "  options=" + session.definitions().size()
                + "  changed=" + session.changedCount()
                + "  page=" + (session.page() + 1) + "/" + session.pageCount(layoutPageSize);
        gui.text(font, header, MARGIN, 8, 0xFFFFFFFF);
        String sub = "profile='" + session.profileName() + "'"
                + (lastAction.isEmpty() ? "" : "    last: " + lastAction);
        gui.text(font, sub, MARGIN, 18, 0xFFAAAAAA);
        for (Row row : rows) {
            gui.text(font, fitLabel(row.label(), row.labelWidth()), row.x(), row.y(), 0xFFE0E0E0);
        }
    }

    /** 标签超宽 → 截断加省略号（画在按钮左侧，不许压过去）。 */
    private String fitLabel(String label, int maxWidth) {
        if (font.width(label) <= maxWidth) {
            return label;
        }
        String ellipsis = "...";
        return font.plainSubstrByWidth(label, Math.max(0, maxWidth - font.width(ellipsis))) + ellipsis;
    }

    // ---------------------------------------------------------------- 改值入口（三路归一）

    /**
     * 控件回调路径。{@code fromTextInput} = 文本框逐键回写：
     * 打字中间态可能非法 —— 不重建（重建会丢焦点），非法值被 {@code session.set} 拒绝即止，
     * 完成时由 {@link #commitAndClose} 终同步兜底。
     */
    private void onWidgetSet(String name, String value, boolean fromTextInput) {
        PackOptions.SetOutcome outcome = session.set(name, value);
        lastAction = outcome.format();
        VkDisp.LOGGER.info("vkdisp: pack option set: {}", lastAction);
        if (!fromTextInput && !outcome.applied()) {
            // 循环按钮显示了被拒/被钳的请求值 → 重建，让状态→控件回到会话真值。
            rebuildWidgets();
        }
    }

    /**
     * 驱动路径（{@link PackOptionsDrive}，配置热加载边沿）：外部改值后控件必然过期 →
     * 一律重建（状态→控件单向同步）。屏幕未开时由驱动方拒绝，不进本方法。
     */
    void applyDriveSet(String name, String value) {
        PackOptions.SetOutcome outcome = session.set(name, value);
        lastAction = "drive " + outcome.format();
        VkDisp.LOGGER.info("vkdisp: pack options screen drive set: {}", outcome.format());
        rebuildWidgets();
    }

    /** 驱动翻页（{@code page:N}，1 基；越界钳到合法页）。 */
    void applyDrivePage(int page1) {
        int clamped = session.page(page1 - 1, layoutPageSize);
        lastAction = "drive page " + (clamped + 1);
        VkDisp.LOGGER.info("vkdisp: pack options screen drive page: {} -> 第 {} 页（共 {} 页）",
                page1, clamped + 1, session.pageCount(layoutPageSize));
        rebuildWidgets();
    }

    // ---------------------------------------------------------------- 完成 / 重置

    /**
     * 完成：文本框终同步 → 触碰差分落盘 → 关屏 → 资源重载（改动进着色器）。
     * 落盘失败 → ERROR + 保持屏幕打开（改动还在内存里可重试，不静默丢）。
     */
    void commitAndClose() {
        for (TextInput input : textInputs) {
            String typed = input.box().getValue();
            PackOptions.SetOutcome outcome = session.set(input.name(), typed);
            if (!outcome.applied() && !typed.equals(session.options().value(input.name()))) {
                VkDisp.LOGGER.info(
                        "vkdisp: pack option final sync rejected: {}='{}' -> {}（按会话现值提交）",
                        input.name(), typed, outcome.status());
            }
        }
        PackOptionsSession.CommitResult result = session.commit(store);
        Path file = PackOptionStore.pathFor(minecraft.gameDirectory.toPath());
        try {
            store.save(file);
        } catch (IOException e) {
            VkDisp.LOGGER.error("vkdisp: pack options screen save failed: {}", file, e);
            lastAction = "保存失败: " + e.getMessage();
            return; // 保持开屏：用户可重试；绝不假装成功（T11）
        }
        VkDisp.LOGGER.info(
                "vkdisp: pack options screen saved: pack={} stored={} removed={} changed={} store={}",
                session.packName(), result.stored(), result.removed(),
                result.changedEntries(), file);
        minecraft.setScreenAndShow(null);
        minecraft.reloadResourcePacks();
    }

    /**
     * 重置本包：清掉存储里本包的全部条目并重建会话（回到 默认+profile 基线）。
     * 只改内存 —— 落盘仍等"完成"（放弃 = 连同重置一起丢弃，语义一致）。
     */
    void resetPack() {
        int cleared = store.clearPack(session.packName());
        session = PackOptionsSession.create(session.pack(), session.profileName(), store);
        lastAction = "已重置 " + cleared + " 条";
        VkDisp.LOGGER.info("vkdisp: pack options screen reset: pack={} cleared={}（待完成时落盘）",
                session.packName(), cleared);
        rebuildWidgets();
    }

}
