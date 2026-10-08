package dev.vkdisp;
/**
 * 【自行补充】GAP-021 / 转译链：把包在 {@code #if} 里当数值用的 {@code MC_VERSION} 供出来。
 *
 * <p><b>为什么需要它</b>（本轮实测，不靠印象）：BSL v10.1.8 里 {@code MC_VERSION} 出现
 * <b>53 次、跨 32 个着色器文件</b>（外加 {@code shaders.properties} 1 次），
 * 而我方<b>从来没有定义过它</b> —— 两个条件求值器（{@code glsl.preprocess.DefineProcessor.ExprEval}
 * 与 {@code pack.properties.ConditionalPreprocessor.BoolExpr}）对「未定义标识符」的口径都是
 * <b>按 0 算</b> ⇒ 每一条 {@code #if MC_VERSION >= …} 都判假 ⇒
 * <b>整包是按「运行在 1.7 之前的老版本」编译的</b>。
 * 例：{@code shaders.properties:167} 的 {@code #if MC_VERSION >= 11800} 决定生物群集那批
 * {@code uniform.float.isCold/isDesert/…} 用<b>符号名</b>还是用<b>老数字 ID</b> ——
 * 我方一直取的是老数字那一支。
 *
 * <p><b>编码口径不是猜的</b>，是从包自己的门限反推出来的（全部形如 {@code major*10000 + minor*100 + patch}）：
 * <pre>
 *   11800 → 1.18.0     11300 → 1.13.0     10710 → 1.7.10     10800 → 1.8.0
 *   11605 → 1.16.5     12106 → 1.21.6     12109 → 1.21.9     12111 → 1.21.11
 * </pre>
 *
 * <p><b>版本串从哪来</b>：运行期 {@code SharedConstants.getGameVersion().name()}，
 * 其真身是 classpath 上那份 {@code version.json}（实测逐字 {@code "id": "26.3", "name": "26.3"}）
 * ⇒ {@code MC_VERSION = 26*10000 + 3*100 = 260300}，高于包里全部门限 ⇒ 所有「新版本」分支都会走到。
 * 🔖 用反射读而不是 {@code import}：本仓库的<b>单测类路径没有原版类</b>
 * （实测 {@code ClassNotFoundException: net.minecraft.SharedConstants}），
 * 而「编码怎么算」这件事必须能离线钉住（同 {@code PackChainGatingSwitch} 的分离理由）。
 */
import java.util.OptionalInt;

public final class McVersion {

    /** 包与引擎两侧共用的宏名（OF/Iris 公开口径）。 */
    public static final String MACRO = "MC_VERSION";

    /** 解析结果缓存；仅在**成功解析过一次**后有效。 */
    private static volatile int cached;

    /** 是否已成功解析过（false = 还没拿到，下次调用再试一次）。 */
    private static volatile boolean resolved;

    /** 单测 / A-B 取证槽；{@code null} = 走真实解析。 */
    private static volatile Integer override;

    /** 最近一次读到的原始版本串（取证用；未解析成功时为 null）。 */
    private static volatile String rawName;

    private McVersion() {
    }

    /**
     * 把版本串编码成包期望的整数。纯函数、可离线测。
     *
     * @return 解析不出来时 {@link OptionalInt#empty()}（⇒ 调用方<b>不定义</b>这个宏，
     *         而不是喂一个猜的数：宁可让分支照旧判假，也不制造一个「看起来像证据的假数字」）
     */
    public static OptionalInt encode(String versionName) {
        if (versionName == null) {
            return OptionalInt.empty();
        }
        String[] parts = versionName.strip().split("\\.");
        if (parts.length < 2) {
            return OptionalInt.empty();
        }
        try {
            int major = Integer.parseInt(parts[0].trim());
            int minor = Integer.parseInt(parts[1].trim());
            int patch = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 0;
            if (major < 0 || minor < 0 || patch < 0) {
                return OptionalInt.empty();
            }
            return OptionalInt.of(major * 10_000 + minor * 100 + patch);
        } catch (NumberFormatException e) {
            // 形如 "1.20.2-rc2" / 快照后缀：不猜，交回空值。
            return OptionalInt.empty();
        }
    }

    /**
     * 本进程该给包的 {@code MC_VERSION}；{@link OptionalInt#empty()} = 拿不到 ⇒ <b>不定义</b>。
     *
     * <p>🔴 本方法<b>一行日志都不打</b>：它是从转译器（{@code DefineProcessor}）里被调的，
     * 而转译器与属性解析器都跑在<b>单测类路径</b>上 —— 那里没有 FML 的配置类，
     * 一旦去碰 {@code VkDisp.LOGGER} 就是 {@code NoClassDefFoundError: IConfigSpec}
     * （本轮实测：只改了一行注释的 {@code GlslPipelineTest} 当场变红）。
     * 「拿不到」这件事由调用方在自己的层打（{@code ShaderPackCompiler} 的自报行），
     * 本类只暴露 {@link #lastFailure()} 供它说清楚原因。
     *
     * <p>🔖 失败<b>不永久缓存</b>：首次转译可能发生在 FML 还没就绪的窗口里，
     * 一次失败就把「不定义」钉死到进程结束，等于把偶发失败变成永久降级。
     */
    public static OptionalInt current() {
        Integer localOverride = override;
        if (localOverride != null) {
            return localOverride < 0 ? OptionalInt.empty() : OptionalInt.of(localOverride);
        }
        if (resolved) {
            return OptionalInt.of(cached);
        }
        synchronized (McVersion.class) {
            if (resolved) {
                return OptionalInt.of(cached);
            }
            OptionalInt attempt = resolveFromGame();
            if (attempt.isPresent()) {
                cached = attempt.getAsInt();
                resolved = true;
            }
            return attempt;
        }
    }

    /** 最近一次解析失败的原因；{@code null} = 没失败过（或后来成功了）。给调用方打日志用。 */
    public static String lastFailure() {
        return failure;
    }

    /** 读到的原始版本串（取证用）；未解析成功时 {@code null}。 */
    public static String rawName() {
        return rawName;
    }

    /** 宏名 → 数值；不是 {@link #MACRO} 或拿不到时返回空（调用方按「未定义」处理）。 */
    public static OptionalInt numericOf(String identifier) {
        if (MACRO.equals(identifier)) {
            return current();
        }
        return OptionalInt.empty();
    }

    /** 最近一次解析失败的原因（不打日志，只存着让调用方去说）。 */
    private static volatile String failure;

    /** 单测 / A-B 专用；传负数 = 强制「拿不到」。用完必须复位回 {@code null}。 */
    public static void overrideForTest(Integer value) {
        override = value;
        resolved = false;
        cached = 0;
        failure = null;
        rawName = null;
    }

    private static OptionalInt resolveFromGame() {
        try {
            Class<?> shared = Class.forName("net.minecraft.SharedConstants");
            Object worldVersion = shared.getMethod("getGameVersion").invoke(null);
            Object name = worldVersion.getClass().getMethod("name").invoke(worldVersion);
            rawName = name == null ? null : String.valueOf(name);
            OptionalInt encoded = encode(rawName);
            if (encoded.isEmpty()) {
                failure = "版本串 \"" + rawName + "\" 编不出 MC_VERSION（期望 major.minor[.patch]）";
            }
            return encoded;
        } catch (Throwable t) {
            // 无原版类（单测类路径）/ 方法改名 —— 都按「拿不到」处理，不猜一个数。
            failure = "读 SharedConstants.getGameVersion().name() 失败：" + t;
            return OptionalInt.empty();
        }
    }
}
