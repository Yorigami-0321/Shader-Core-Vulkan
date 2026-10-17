package dev.vkdisp.screen;
/**
 * 【参考调研】R6 / M-14：把光影包与包选项嵌进<b>原版视频设置页</b>（替掉自研屏幕与自研分页）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 原版 26.3.0.51-beta sources jar：{@code OptionsSubScreen}（第 21 行
 *    {@code protected @Nullable OptionsList list}、第 52 行 {@code protected abstract addOptions}、
 *    第 72-74 行 {@code onClose → list.applyUnsavedChanges()}）；{@code VideoSettingsScreen}
 *    第 108 行 {@code protected void addOptions()}（它自己就在里面调 {@code list.addHeader/addBig/
 *    addSmall}，第 109-…行）；{@code OptionsList} 第 30-63 行那六个 public 装配方法
 *    （{@code addBig(OptionInstance|AbstractWidget)} / {@code addSmall(OptionInstance...)} 每行两个 /
 *    {@code addSmall(List<AbstractWidget>)} / {@code addHeader(Component)}）；
 *    {@code OptionInstance} 第 86-114 行 public 构造器 + 第 39 行 {@code OptionInstance.Enum}
 *    （{@code (List<T>, Codec<T>)}）+ 第 183 行 {@code CaptionBasedToString}；
 *    ② 本仓库自有：{@link PackPickerCandidates}（候选与标签的纯逻辑，MC-free、已有单测）、
 *    {@link dev.vkdisp.config.PackOptionsSession}/{@link dev.vkdisp.config.PackOptionStore}
 *    （会话 + 落盘）、{@code dev.vkdisp.pack.PackCompositeSource#loadSelectedForOptions}、
 *    旧 {@code PackOptionsScreen} 的<b>语义</b>（不是它的布局）。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以；本文件独立编写
 *    → 例外条款：无 GPL / LGPL / ARR 代码并入
 * 1. 官方/主实现：整条通路都在原版类里 —— 我们只是<b>往原版的 OptionsList 里加行</b>，
 *    滚动、每行两列、标签绘制、键盘/朗读、关屏时的 {@code applyUnsavedChanges} 全是原版在做。
 * 2. 备选（都否决，理由可数）：① {@code ConfigurationScreen} 加选项卡 —— <b>不可行</b>：该类是
 *    {@code final}（nf 源第 112 行），加不了页；② NeoForge {@code ScreenEvent.Init.Post} ——
 *    <b>不可行</b>：它只 {@code addListener}，不进 {@code HeaderAndFooterLayout} ⇒ 控件与原版页脚
 *    重叠（本轮 §5.0 M-14 行已记这条）；③ 保留自研双列分页、只是换个入口 —— <b>否决</b>：
 *    那正是 R6 要删的东西（{@code layoutPageSize}/{@code turnPage}/{@code fitLabel} 那一族）。
 * 3. 我们的差异点：🔴 <b>分页整个消失</b> —— 旧屏幕自己算「每列几行 × 2 列 = 一页容量」并画页码，
 *    现在 {@code addSmall(OptionInstance...)} 每行放两个、超出的由原版列表滚动消化；
 *    旧标签截断 {@code fitLabel} 也没了（原版按 {@code OptionInstance.caption} 画）。
 *    ⚠️ 语义差一条，必须自报而不是假装等价：<b>换包</b>之后本页选项行仍属于<b>开屏那一刻</b>的那个包
 *    （原版没有「重建页」的入口给我们调），所以页头明说「换包后请重开本页」。
 * 4. 许可证核对：本项目 LGPL-3.0-or-later；零第三方代码并入。
 * 5. 性能基线：开屏一次遍历 {@code definitions()} 建按钮（与旧屏幕 {@code init()} 同量级，
 *    旧屏幕还要每帧画一批文本标签 ⇒ 这里只会更省）；不测不下结论（T14 / X55）。
 */
import com.mojang.serialization.Codec;
import dev.vkdisp.VkDisp;
import dev.vkdisp.VkDispConfig;
import dev.vkdisp.config.PackOptionStore;
import dev.vkdisp.config.PackOptions;
import dev.vkdisp.config.PackOptionsSession;
import dev.vkdisp.pack.Option;
import dev.vkdisp.pack.PackCompositeSource;
import dev.vkdisp.pack.ShaderPack;
import dev.vkdisp.pack.ShaderPackScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 原版视频设置页里的 vkdisp 那一段（M-14；登记表：{@code 04-SPEC} §5.0 M-14 行）。
 *
 * <p>🔴 <b>会话的生命周期 = 一次开屏</b>：{@code addOptions()} 每个视频设置页实例只会跑一次
 * （{@code init} 里 {@code addContents} → {@code addOptions}），所以这里在那一刻建会话并占住
 * 这一个槽位；屏幕关掉就换掉。不做「每帧重建」—— 那会让用户刚拨的按钮立刻被覆盖。
 */
public final class VanillaPackOptionsPage {

    /** 本模块开屏时建的那一份会话（final 持有者：{@code StaticFieldRatchetTest} 的「静态非 final 只许降」口径）。 */
    private static final java.util.concurrent.atomic.AtomicReference<@Nullable Page> PAGE =
            new java.util.concurrent.atomic.AtomicReference<>();

    private VanillaPackOptionsPage() {
    }

    /** 一次开屏的全部可变状态：会话 + 存储 + 自由文本框 + 开屏时的包名（自报与「换包后重开」提示用）。 */
    private static final class Page {
        private PackOptionsSession session;
        private final PackOptionStore store;
        private final String packNameAtOpen;
        /** 自由值选项的文本框（点「应用」时终同步，口径与旧 {@code commitAndClose} 一致）。 */
        private final List<TextInput> textInputs = new ArrayList<>();

        private Page(PackOptionsSession session, PackOptionStore store) {
            this.session = session;
            this.store = store;
            this.packNameAtOpen = session.packName();
        }
    }

    /** 文本框与它的选项名（终同步时按名回写会话）。 */
    private record TextInput(String name, EditBox box) {
    }

    /**
     * M-14 的注入转发点（{@code VideoSettingsScreen#addOptions} 的 TAIL）。
     *
     * @param list 原版刚填完自己那些行的 {@code OptionsList}（{@code OptionsSubScreen#list}，
     *             由 mixin 侧 {@code @Shadow} 交出；{@code null} = 该屏还没建列表，本帧不接）
     */
    public static void onAddOptions(@Nullable OptionsList list) {
        if (!VkDispConfig.MIXIN_PACK_OPTIONS_IN_VANILLA.get()) {
            return;
        }
        if (list == null) {
            VkDisp.LOGGER.warn("vkdisp: [M-14] 视频设置页的 OptionsList 还是 null ⇒ 本帧不接包选项"
                    + "（原版在 addContents 里才建列表；这条一旦出现在日志就是挂点时机不对，别当噪音）");
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Page page = openSession(mc);
        if (page == null) {
            return;
        }
        list.addHeader(Component.literal("vkdisp 光影包（M-14）"));
        addPackSelector(list, mc);
        int small = addEnumeratedOptions(list, page);
        int boxes = addFreeTextOptions(list, page);
        addApplyAndResetButtons(list, mc, page);
        VkDisp.LOGGER.info("vkdisp: [M-14] 包选项已进原版视频设置页：pack={} 选项={} 循环行={} 文本行={}"
                + "（分页/标签截断已删；换包后需重开本页才换一批选项）",
                page.packNameAtOpen, page.session.definitions().size(), small * 2, boxes);
    }

    /** 开屏建会话（无可选包 ⇒ 一行 WARN 后本段整块不出现，绝不硬开空段；口径与旧 {@code create()} 相同）。 */
    private static @Nullable Page openSession(Minecraft mc) {
        Path dir = mc.gameDirectory.toPath().resolve("shaderpacks");
        Optional<ShaderPack> pack = PackCompositeSource.loadSelectedForOptions(
                dir, VkDispConfig.SHADER_PACK.get());
        if (pack.isEmpty()) {
            VkDisp.LOGGER.info("vkdisp: [M-14] 本帧视频设置页不出现 vkdisp 段：没有可选的光影包"
                    + "（shaderPack='{}'）", VkDispConfig.SHADER_PACK.get());
            return null;
        }
        PackOptionStore store = PackOptionStore.load(PackOptionStore.pathFor(mc.gameDirectory.toPath()));
        PackOptionsSession session = PackOptionsSession.create(
                pack.get(), VkDispConfig.PACK_PROFILE.get(), store);
        Page page = new Page(session, store);
        PAGE.set(page);
        return page;
    }

    /** 包选择行：候选来自 {@link PackPickerCandidates}（自动 / 无 / 库存里的包 / 占位）。 */
    private static void addPackSelector(OptionsList list, Minecraft mc) {
        Path dir = mc.gameDirectory.toPath().resolve("shaderpacks");
        PackPickerCandidates.Result candidates = PackPickerCandidates.build(
                ShaderPackScanner.scan(dir), VkDispConfig.SHADER_PACK.get(), Files.isDirectory(dir));
        List<String> values = new ArrayList<>(
                candidates.choices().stream().map(PackPickerCandidates.Choice::value).toList());
        if (!candidates.currentInList()) {
            values.add(PackPickerCandidates.missingLabel());
        }
        list.addBig(new OptionInstance<>("vkdisp.shaderPack", OptionInstance.noTooltip(),
                (caption, value) -> Component.literal(
                        PackPickerCandidates.labelOf(candidates, value)),
                new OptionInstance.Enum<>(values, Codec.STRING),
                PackPickerCandidates.initialSelection(candidates),
                value -> {
                    VkDispConfig.SHADER_PACK.set(value);
                    VkDispConfig.SHADER_PACK.save();
                    VkDisp.LOGGER.info("vkdisp: [M-14] shaderPack 已改成 '{}'（走 FML 文件监听 ⇒ 热重载；"
                            + "本页那些选项行仍属于 '{}'，要换一批选项请重开本页）",
                            value, PAGE.get() == null ? "无" : PAGE.get().packNameAtOpen);
                }));
    }

    /** 声明了取值列表的选项 ⇒ 原版循环按钮，每行两个（{@code addSmall} 自己配对）。 */
    private static int addEnumeratedOptions(OptionsList list, Page page) {
        List<OptionInstance<?>> rows = new ArrayList<>();
        for (Option option : page.session.definitions()) {
            List<String> values = option.values();
            String current = page.session.options().value(option.name());
            if (values == null || values.isEmpty() || !values.contains(current)) {
                continue;
            }
            rows.add(rowFor(option.name(), values, current, page));
        }
        if (!rows.isEmpty()) {
            list.addSmall(rows.toArray(new OptionInstance<?>[0]));
        }
        return rows.size();
    }

    /** 没声明取值列表的选项 ⇒ 原版行 + 我方 {@code EditBox}（能力平移：旧屏幕那批文本框不能凭空消失）。 */
    private static int addFreeTextOptions(OptionsList list, Page page) {
        int count = 0;
        for (Option option : page.session.definitions()) {
            List<String> values = option.values();
            String current = page.session.options().value(option.name());
            boolean enumerated = values != null && !values.isEmpty() && values.contains(current);
            if (enumerated) {
                continue;
            }
            // 这条 OptionInstance 只提供原版的「标签 + 值」通路，控件用下面那个 EditBox。
            OptionInstance<String> caption = rowFor(option.name(), List.of(), current, page);
            EditBox box = new EditBox(Minecraft.getInstance().font, 0, 0, 150, 20,
                    Component.literal(option.name()));
            box.setMaxLength(128);
            box.setValue(current == null ? "" : current);
            // responder 后挂：setValue 的初始值不触发回写（避免开屏即产生改值日志，同旧屏幕）。
            box.setResponder(text -> applySet(option.name(), text, true, null));
            page.textInputs.add(new TextInput(option.name(), box));
            list.addSmall(box, caption, null);
            count++;
        }
        return count;
    }

    private static OptionInstance<String> rowFor(String name, List<String> values, String current,
            Page page) {
        return new OptionInstance<>(name, OptionInstance.noTooltip(),
                (caption, value) -> Component.literal(value),
                new OptionInstance.Enum<>(values.isEmpty() ? List.of(current) : values, Codec.STRING),
                current,
                value -> applySet(name, value, false, page));
    }

    /** 「应用并重载」与「重置本包」两个按钮（旧页脚那三颗的等价物；「放弃」由原版关屏给出）。 */
    private static void addApplyAndResetButtons(OptionsList list, Minecraft mc, Page page) {
        list.addBig(Button.builder(Component.literal("vkdisp：应用并重载资源包"),
                b -> commit(mc, page)).build());
        list.addBig(Button.builder(Component.literal("vkdisp：重置本包选项"),
                b -> {
                    int cleared = page.store.clearPack(page.session.packName());
                    page.session = PackOptionsSession.create(page.session.pack(),
                            page.session.profileName(), page.store);
                    page.textInputs.clear();
                    VkDisp.LOGGER.info("vkdisp: [M-14] 已重置 {} 条（待点「应用」才落盘；"
                            + "本页控件仍是旧值 ⇒ 重开本页看新基线）", cleared);
                }).build());
    }

    /** 改值：写会话 + 点名（被拒也要看得见，X11）；循环按钮的拒绝旧逻辑靠重建控件，这里改成回读会话真值。 */
    private static void applySet(String name, String value, boolean fromTextInput, @Nullable Page page) {
        Page target = page != null ? page : PAGE.get();
        if (target == null) {
            return;
        }
        PackOptions.SetOutcome outcome = target.session.set(name, value);
        VkDisp.LOGGER.info("vkdisp: [M-14] pack option set: {}", outcome.format());
        if (!outcome.applied() && !fromTextInput) {
            VkDisp.LOGGER.info("vkdisp: [M-14] 该值被会话拒绝 ⇒ 行控件与会话真值不一致，"
                    + "重开本页回到真值（旧屏幕在这里是 rebuildWidgets，原版页没有给我们这个入口）");
        }
    }

    /** 应用：文本框终同步 → 差分落盘 → 重载资源包。落盘失败 = ERROR 且不假装成功（T11，同旧屏幕）。 */
    private static void commit(Minecraft mc, Page page) {
        for (TextInput input : page.textInputs) {
            String typed = input.box().getValue();
            PackOptions.SetOutcome outcome = page.session.set(input.name(), typed);
            if (!outcome.applied() && !typed.equals(page.session.options().value(input.name()))) {
                VkDisp.LOGGER.info("vkdisp: [M-14] 文本框终同步被拒: {}='{}' -> {}（按会话现值提交）",
                        input.name(), typed, outcome.status());
            }
        }
        PackOptionsSession.CommitResult result = page.session.commit(page.store);
        Path file = PackOptionStore.pathFor(mc.gameDirectory.toPath());
        try {
            page.store.save(file);
        } catch (IOException e) {
            VkDisp.LOGGER.error("vkdisp: [M-14] 包选项落盘失败: {}", file, e);
            return;
        }
        VkDisp.LOGGER.info("vkdisp: [M-14] 已落盘并重载：pack={} stored={} removed={} changed={} store={}",
                page.session.packName(), result.stored(), result.removed(),
                result.changedEntries(), file);
        mc.reloadResourcePacks();
    }
}
