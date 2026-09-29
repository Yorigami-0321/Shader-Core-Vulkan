package dev.vkdisp.pack;

/**
 * 【参考调研】program 阶段族（OF/Iris 程序命名表）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §3.1（ProgramStage：SHADOW / GBUFFERS_* / DEFERRED* / COMPOSITE*）
 *    与 docs/08-TESTING.md §5（检查点顺序 shadow → gbuffers → deferred → composite → final）；
 *    ② OptiFine 官方文档仓库 sp614x/optifine 的 OptiFineDoc/doc/shaders.txt「Shader Programs」表——只提取程序名/族别/顺序这类格式事实。
 *    许可证核对：sp614x/optifine 无 LICENSE 文件（GitHub license API 返回 404）→ ARR，按 07-CONSTRAINTS X20 其文本表达不可并入；
 *    仅使用不受版权保护的事实性信息（程序名、族名、执行顺序），零文本复制。
 *    Iris（IrisShaders/Iris，LGPL-3.0，已核仓库 LICENSE）同口径，只取格式事实；参考模组（VulkanMod / Sulkan / Beryl）零接触。
 *    → 能否并入本项目（MIT）：本文件为独立实现的枚举，只含格式事实，可并入
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：OF 官方「Shader Programs」表——shadow、shadowcomp[1-99]、prepare[1-99]、
 *    gbuffers_*(terrain/entities/water/...)、deferred[1-99]、composite[1-99]、final；程序名前缀即族名，数字后缀即族内序号。
 * 2. 备选：Iris 对 program 的同类分类（18-PARALLEL §4 A 线参考列所列格式事实）；本文件不读其代码，只用命名事实。
 * 3. 我们的差异点：① 在 04-SPEC §3.1 与任务清单之外补了 SHADOWCOMP、PREPARE 两族——OF 官方表存在这两个族，
 *    缺了会被归入 UNKNOWN 造成静默漏解析；② 只冻结"族"（枚举），族内编号由 Program.stageIndex 承载，避免枚举爆炸；
 *    ③ deferred_pre/composite_pre 是无文件的虚拟程序，不参与文件扫描（万一解析到则归对应族且 index=0，见 stageIndex 文档）；
 *    ④ 族级 order 是粗序——gbuffers_water 实际渲染于 deferred 之后，精确编排归 render/FrameComposer（04-SPEC §3.4）。
 * 4. 许可证核对：本项目 MIT；本文件零第三方代码复制，仅含文档事实。
 * 5. 性能基线：❄️ 冷路径（仅扫描/重载时调用），不做任何性能优化（18-PARALLEL §7.7）。
 */

/**
 * 着色器包内一个 program 的阶段族（枚举）。
 *
 * <p><b>冻结契约</b>：字段已按 {@code docs/18-PARALLEL.md} §3 F2 冻结，供 A/B/C/D/E/F 并行线与关键路径共同消费。
 * 任何字段/语义变更必须走 {@code 18-PARALLEL.md} §3.2 流程（提出方说明 → env-1 统一改 → 契约版本号 +1 → 通知各环境 rebase），
 * 任何一方不得自行增删改（{@code 07-CONSTRAINTS.md} X12）。冷路径数据，只求清晰、不做性能优化（§7.7）。
 */
public enum ProgramStage {

    /** 影子贴图程序（shadow；含 shadow_solid / shadow_cutout 这类别名，官方表标注为回退到 shadow）。 */
    SHADOW("shadow", 0),

    /** 影子合成程序（shadowcomp / shadowcomp1..99）。04-SPEC §3.1 未列，按 OF 官方程序表补入（见【参考调研】第 3 条）。 */
    SHADOWCOMP("shadowcomp", 1),

    /** 预处理程序（prepare / prepare1..99，OF 官方语义：地形渲染之前）。04-SPEC §3.1 未列，按官方程序表补入。 */
    PREPARE("prepare", 2),

    /** 几何缓冲程序（gbuffers_*：terrain / entities / water / hand / sky* / weather 等，族内变体看 Program#name）。 */
    GBUFFERS("gbuffers", 3),

    /** 延迟程序（deferred / deferred1..99）。 */
    DEFERRED("deferred", 4),

    /** 合成程序（composite / composite1..99）。 */
    COMPOSITE("composite", 5),

    /** 最终输出程序（final，写回主渲染目标）。 */
    FINAL("final", 6),

    /** 无法识别的程序名。必须显式暴露给调用方并打日志，不许静默归类（07-CONSTRAINTS T11）。 */
    UNKNOWN("unknown", -1);

    private final String key;
    private final int order;

    ProgramStage(String key, int order) {
        this.key = key;
        this.order = order;
    }

    /** 族键（小写族名；UNKNOWN 为 "unknown"）。 */
    public String key() {
        return key;
    }

    /**
     * 族级粗序：SHADOW(0) → SHADOWCOMP(1) → PREPARE(2) → GBUFFERS(3) → DEFERRED(4) → COMPOSITE(5) → FINAL(6)；UNKNOWN = -1。
     *
     * <p>注意：这是族级顺序，不是精确渲染顺序——OF 语义里 gbuffers_water / gbuffers_hand_water 实际渲染于 deferred 之后，
     * 精确编排归 render/FrameComposer（04-SPEC §3.4），消费方不得只凭本值排帧。
     */
    public int order() {
        return order;
    }

    /**
     * 按程序名归族（前缀匹配；{@code shadowcomp} 必须先于 {@code shadow} 判定）。
     * 未识别 → {@link #UNKNOWN}：显式暴露，不静默归类（T11）。
     *
     * @param programName 程序名（如 "gbuffers_terrain"、"composite3"）；null / 空白 → UNKNOWN
     */
    public static ProgramStage parse(String programName) {
        if (programName == null) {
            return UNKNOWN;
        }
        String name = programName.trim();
        if (name.isEmpty()) {
            return UNKNOWN;
        }
        if (name.startsWith("shadowcomp")) {
            return SHADOWCOMP;
        }
        if (name.startsWith("shadow")) {
            return SHADOW;
        }
        if (name.startsWith("prepare")) {
            return PREPARE;
        }
        if (name.startsWith("gbuffers_")) {
            return GBUFFERS;
        }
        if (name.startsWith("deferred")) {
            return DEFERRED;
        }
        if (name.startsWith("composite")) {
            return COMPOSITE;
        }
        if (name.equals("final")) {
            return FINAL;
        }
        return UNKNOWN;
    }

    /**
     * 程序名末尾的连续数字后缀；无后缀 → 0。
     *
     * <p>对 composite1 / deferred99 / shadowcomp3 / prepare2 有编号语义；
     * gbuffers_*、shadow、final 无编号语义（恒为 0）；虚拟程序 *_pre 无数字后缀（index=0）。
     * 极端超长数字无法解析成 int 时按 0 兜底（冷路径，不抛异常）。
     *
     * @param programName 程序名；null → 0
     */
    public static int stageIndex(String programName) {
        if (programName == null) {
            return 0;
        }
        String name = programName.trim();
        int end = name.length();
        int start = end;
        while (start > 0 && Character.isDigit(name.charAt(start - 1))) {
            start--;
        }
        if (start == end) {
            return 0;
        }
        try {
            return Integer.parseInt(name.substring(start));
        } catch (NumberFormatException tooLong) {
            return 0;
        }
    }
}
