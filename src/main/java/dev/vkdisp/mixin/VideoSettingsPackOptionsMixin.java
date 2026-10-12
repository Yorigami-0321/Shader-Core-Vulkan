package dev.vkdisp.mixin;
/**
 * 【参考调研】R6 / M-14 {@code @Inject}：把 vkdisp 的光影包与包选项<b>追加</b>进原版视频设置页
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 原版 26.3.0.51-beta sources jar：{@code VideoSettingsScreen} 第 <b>108</b> 行
 *    {@code protected void addOptions()}（实现 {@code OptionsSubScreen} 第 52 行的 abstract；
 *    它自己在体内就调了 {@code list.addHeader/addBig/addSmall}），以及 {@code OptionsSubScreen}
 *    第 <b>21</b> 行 {@code protected @Nullable OptionsList list}、第 72-74 行
 *    {@code onClose() → list.applyUnsavedChanges()}；
 *    ② Mixin 0.8.7 的 {@code @Mixin(targets=…)} / {@code @Inject} / {@code @Shadow} / {@code @At}
 *    **注解签名**（javap 核实常量池，不读其实现）；
 *    ③ 本仓库自有的 {@code dev.vkdisp.screen.VanillaPackOptionsPage}（业务全在那边，本文件只转发）。
 *    许可证：Mixin = MIT；原版 = Mojang EULA（只观察语句与签名）。
 *    → 能否并入本项目（MIT）：可以；本文件为独立编写的注入类
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：原版的 {@code OptionsList} 就是官方装配面 —— 我们只往里<b>加行</b>，
 *    排序、滚动、每行两列、标签、键盘与朗读、关屏时应用未保存值，全是原版在做。
 * 2. 备选：① NeoForge {@code ScreenEvent.Init.Post} —— <b>否决</b>：它只 {@code addListener}，
 *    不进 {@code HeaderAndFooterLayout} ⇒ 控件与原版页脚重叠（{@code 04-SPEC} §5.0 M-14 行已记）；
 *    ② {@code ConfigurationScreen} 加选项卡 —— <b>不可行</b>：该类 {@code final}；
 *    ③ 注 {@code init} 的 TAIL —— <b>否决</b>：{@code init} 会因换尺寸/翻页被反复调用，
 *    而 {@code addOptions} 只在列表建立那一趟里调 ⇒ 挂在那里天然「一次开屏一次」。
 * 3. 我们的差异点：这是<b>本仓第一个 GUI 屏幕注入点</b>（类别闸门已于 2026-10-12 废除，
 *    保留的是登记制 + 可关闭制 + 逐个开启）。方法体一行转发（X25），且<b>不改</b>原版任何一行行为：
 *    关掉 {@code mixin.packOptionsInVanilla} 就回到今天的原版视频设置页。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：每次打开视频设置页一趟（不是每帧），与旧 {@code PackOptionsScreen.init} 同量级。
 */
import dev.vkdisp.bridge.MixinTargets;
import dev.vkdisp.screen.VanillaPackOptionsPage;
import net.minecraft.client.gui.components.OptionsList;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * H 线 M-14：原版视频设置页尾部追加 vkdisp 那一段（登记表：{@code 04-SPEC} §5.0 M-14 行）。
 *
 * <p><b>为什么用 {@code @Shadow} 拿列表</b>：{@code list} 声明在父类 {@code OptionsSubScreen} 第 21 行，
 * 是 {@code protected} —— mixin 允许 shadow 继承来的成员，注入类合并后就是该类本身，读它不越界。
 * ⚠️ 该字段每帧可为 {@code null}（{@code addContents} 才赋值）⇒ 交给 bridge 侧点名，不许静默跳过。
 *
 * <p><b>可关闭</b>：配置键 {@code mixin.packOptionsInVanilla}（默认开）。关档 = 原版页里不出现
 * vkdisp 段（自研屏幕已随 R6 退役，那时改包/改选项要回到改配置文件）。
 */
@Mixin(targets = {MixinTargets.VIDEO_SETTINGS_SCREEN}, remap = false)
public abstract class VideoSettingsPackOptionsMixin {

    /** {@code OptionsSubScreen#list}（第 21 行，protected，可为 null）—— 原版的选项列表本体。 */
    @Shadow
    protected @Nullable OptionsList list;

    /**
     * 在原版把自己的行加完之后，接着加 vkdisp 的行。
     *
     * <p>🔴 {@code require = 1 / expect = 1}：{@code addOptions} 改名或签名一变就当场硬失败，
     * 不许「静默没接上」（T10 / X11）。
     */
    @Inject(method = {MixinTargets.VIDEO_SETTINGS_ADD_OPTIONS}, at = @At("TAIL"), require = 1, expect = 1)
    private void vkdisp$appendPackOptions(CallbackInfo ci) {
        VanillaPackOptionsPage.onAddOptions(this.list);
    }
}
