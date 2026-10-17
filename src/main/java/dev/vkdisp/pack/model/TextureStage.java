package dev.vkdisp.pack.model;

import java.util.Optional;

/**
 * 【参考调研】包可以把自有贴图挂在哪七个时刻上（移植件）
 * 0. 合规核对（第 0 步闸门）：
 *    上游 = Vitrail {@code dev/vitrail/pack/model/TextureStage.java:1-64} @ 4a45f44…（LGPL-3.0，
 *    读取日期 2026-10-17）；Vitrail {@code NOTICE} 未记它源自 Iris ⇒ 台账只记 Vitrail。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以，进 {@code NOTICE.md} 移植台账（§〇 P1）。
 *    → 例外条款：无。
 * 3. 我们的差异点：包名/缩进 + 本注释块；七个名字与九个族的对应关系逐字保留。
 *    本仓 {@code pack/ProgramStage} 是「程序阶段」的另一张表，接线轮要把两者的重合处定成一个真源。
 * 4. 许可证核对结论：本项目 LGPL-3.0-or-later；本文件为 Vitrail 移植件，改动见上。
 * 5. 性能基线：❄️ 冷路径。
 *
 * <p>包可以把自有贴图挂在**七个**时刻上，以及每个时刻覆盖哪些程序。
 *
 * <p>七个名字、九个族，因为其中两个名字各覆盖两个族：{@code gbuffers} 也覆盖 shadow pass，
 * {@code composite} 也覆盖 final。这不是读者可以自行化简的简写：
 * 包对 {@code composite} 覆盖 {@code colortex3}，对 final 同样生效，Iris 把同一张 map 交给两个渲染器。
 *
 * <p>七个之外的名字，本引擎是「丢一行并留一句日志」，而不是凭空造一个 stage：
 * 这个集合在 Iris 和 OptiFine 里都是闭合的，写 {@code texture.shadow.x} 的包写了一行从来没人兑现过的指令。
 */
public enum TextureStage {

    SETUP,
    BEGIN,
    SHADOWCOMP,
    PREPARE,
    /** 每个 gbuffers 程序，以及和它们一起的 shadow pass。 */
    GBUFFERS,
    DEFERRED,
    /** 每个 composite，以及和它们一起的 final。 */
    COMPOSITE;

    /** 包写在 {@code texture.} 与采样器名之间的那个词。 */
    public static Optional<TextureStage> parse(String name) {
        return switch (name) {
            case "setup" -> Optional.of(SETUP);
            case "begin" -> Optional.of(BEGIN);
            case "shadowcomp" -> Optional.of(SHADOWCOMP);
            case "prepare" -> Optional.of(PREPARE);
            case "gbuffers" -> Optional.of(GBUFFERS);
            case "deferred" -> Optional.of(DEFERRED);
            case "composite" -> Optional.of(COMPOSITE);
            default -> Optional.empty();
        };
    }

    /**
     * 某个程序在一帧里画在哪个 stage，按裸名字判定，如 {@code composite4} 或 {@code gbuffers_water}。
     * 不属于任何族的名字返回 empty —— 那是没有任何东西会去跑的名字。
     */
    public static Optional<TextureStage> of(String program) {
        String family = ProgramNames.familyOf(program);
        if (ProgramNames.geometry(family)) {
            return Optional.of(GBUFFERS);
        }

        return switch (family) {
            case "setup" -> Optional.of(SETUP);
            case "begin" -> Optional.of(BEGIN);
            case "shadowcomp" -> Optional.of(SHADOWCOMP);
            case "prepare" -> Optional.of(PREPARE);
            case "deferred" -> Optional.of(DEFERRED);
            case "composite", "final" -> Optional.of(COMPOSITE);
            default -> Optional.empty();
        };
    }
}
