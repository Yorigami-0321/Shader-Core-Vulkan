package dev.vkdisp.pack.model;

import java.util.Locale;
import java.util.Optional;

/**
 * 【参考调研】包自带的贴图（移植件）
 * 0. 合规核对（第 0 步闸门）：
 *    上游 = Vitrail {@code dev/vitrail/pack/model/PackTexture.java:1-84} @ 4a45f44…（LGPL-3.0，
 *    读取日期 2026-10-17）；{@link #gameResource(String)} 那条「名字里有冒号就是游戏的资源」的判据
 *    由上游点名取自 Iris（{@code ShaderPack.readTexture}）⇒ 台账记 Vitrail + Iris 两条。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以，进 {@code NOTICE.md} 移植台账（§〇 P1）。
 *    → 例外条款：无。
 * 3. 我们的差异点：包名/缩进 + 本注释块。🔴 本文件**只到「包声明了什么」为止，不碰设备**：
 *    本仓现有的贴图供给（{@code bridge/NoiseSamplerSource}、包纹理预热那几处）仍是自建路径，
 *    接线轮再把它们的「声明→绑定」段换成这份模型，避免一张表两处住。
 * 4. 许可证核对结论：本项目 LGPL-3.0-or-later；本文件为 Vitrail 移植件，改动见上。
 * 5. 性能基线：❄️ 冷路径。
 *
 * <p>包自带文件的贴图：按它声明的样子，以及背后的文件被找到之后的样子。
 *
 * <p>两族指令落在这里而它们做的事不同，这就是 stage 被带在字段里而不是抹掉的原因。
 * {@code customTexture.NAME} 带来一个对别人毫无意义的名字，声明它的每个程序都绑到这个文件。
 * {@code texture.STAGE.NAME} 根本不带名字：它**替换**已有那张贴图绑的东西，且只在属于该 stage 的程序里 ⇒
 * 同一个 {@code colortex3} 在一半帧里是颜色目标、在另一半里是查找表。
 *
 * @param sampler 采样器名
 * @param stage   为空即 {@code customTexture}；带 stage 即 {@code texture.<stage>.<名>} 的覆盖
 * @param path    包怎么写的位置。前导斜杠表示相对 {@code shaders/}，本身不是名字的一部分。
 *                其中出现冒号表示包点名的是**游戏自己的**资源，此时背后根本没有包的文件，见 {@link #gameResource()}
 * @param raw     解码图像为空、按原样上传的 blob 为有
 * @param blur    采样器是否线性过滤。raw blob 默认 true 而 PNG 默认 false，这是 OptiFine 的规则不是口味；
 *                文件旁的 {@code .mcmeta} 覆盖它
 * @param clamp   采样器是否 clamp 而非 repeat，默认规则同上
 */
public record PackTexture(String sampler, Optional<TextureStage> stage, String path,
        Optional<Raw> raw, boolean blur, boolean clamp) {

    /** raw blob 能被给成的形状，按包的拼法拼写。 */
    public enum Shape { TEXTURE_1D, TEXTURE_2D, TEXTURE_3D, TEXTURE_RECTANGLE }

    /**
     * 一个 blob 是什么，因为它内部 nothing 说明这一点。
     *
     * @param sizeY 1D 贴图为 0；{@code sizeZ} 非 3D 时为 0 —— 那就是格式写它们的方式，也是 {@link #bytes()} 允许的东西
     */
    public record Raw(Shape shape, TargetFormat.Resolution internalFormat, int sizeX, int sizeY,
            int sizeZ, PixelFormat pixelFormat, PixelType pixelType) {

        /**
         * 声明要为真，文件必须装得下多少字节。
         *
         * <p>这是唯一能分辨整个 blob 与截断 blob 的东西，也没有别的东西能做到：短文件上传时一言不发，
         * 读出来是噪声 —— 而那恰恰就是这些文件本来大多装的东西。
         */
        public long bytes() {
            return (long) this.sizeX * Math.max(this.sizeY, 1) * Math.max(this.sizeZ, 1)
                    * this.pixelType.bytesPerTexel(this.pixelFormat);
        }
    }

    /** 文件是要解码的图像，而不是按原样上传的 blob。 */
    public boolean png() {
        return this.raw.isEmpty();
    }

    /**
     * 包点名的是**游戏拥有**的贴图而非自己的文件，此时路径由游戏的资源栈回答，这里没人去打开它。
     *
     * <p>判据是词里有冒号 —— 那是 Iris 的规则而不是猜测（{@code ShaderPack.readTexture}）：
     * 包自己的路径相对 {@code shaders/} 书写，从不带冒号。
     */
    public boolean gameResource() {
        return gameResource(this.path);
    }

    public static boolean gameResource(String path) {
        return path.indexOf(':') >= 0;
    }

    /** 日志用的一行：哪个名字被挪动了，现在从哪儿读。 */
    public String describe() {
        return this.stage.map(stage -> stage.name().toLowerCase(Locale.ROOT) + " " + this.sampler
                        + " overridden by ")
                .orElse(this.sampler + " supplied by ") + this.path
                + this.raw.map(raw -> " (" + raw.shape() + " " + raw.internalFormat().declared() + " "
                        + raw.sizeX() + "x" + raw.sizeY() + "x" + raw.sizeZ() + ")").orElse("");
    }
}
