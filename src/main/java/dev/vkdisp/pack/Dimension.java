package dev.vkdisp.pack;

import java.util.Optional;

/**
 * 【参考调研】维度子目录（world0 / world-1 / world1 ...）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §3.1（DimensionProperties → world0/1/-1/end 分支）
 *    与 docs/08-TESTING.md §4（解析验收要识别根目录 + world0/、world-1/、world1/、world_end/）；
 *    ② OptiFine 官方文档（sp614x/optifine 的 OptiFineDoc/doc/shaders.properties）中关于维度目录的说明——
 *    只提取目录命名与覆盖语义这类格式事实。许可证：该仓库无 LICENSE（GitHub license API 404）→ ARR，
 *    按 07-CONSTRAINTS X20 文本表达不可并入，仅用不受版权保护的事实性信息，零复制；Iris（LGPL-3.0，已核 LICENSE）同口径；
 *    参考模组（VulkanMod / Sulkan / Beryl）零接触。
 *    → 能否并入本项目（MIT）：本文件为独立实现的枚举，只含格式事实，可并入
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：OF 官方说明——着色器可按世界维度放在 /shaders/world&lt;id&gt; 子目录（world-1 = 下界、world1 = 末地）；
 *    world 文件夹存在时该维度只从其加载 .vsh/.fsh（忽略默认文件夹）；空 world 文件夹 = 该维度禁用着色器；
 *    维度文件夹同样参与选项扫描；模组维度的 world 文件夹同样生效（目录名不局限于已知三个）。
 * 2. 备选：Iris 沿用同一目录约定（格式事实）；本文件不读其代码。
 * 3. 我们的差异点：① 08-TESTING §4 实测包里还有 world_end/，04-SPEC §3.1 也写了 "end"——别名 world_end / end 一律归 END；
 *    ② 模组维度目录名任意（world&lt;id&gt;），枚举只做"已知目录名 ↔ 语义"映射：未知目录名 fromFolder 返回 Optional.empty，
 *    原始目录名由 Program#dimensionFolder 与 ShaderPack#dimensionFolders 原样保留（不静默丢弃，T11）；
 *    ③ 空目录禁用语义只落在 ShaderPack#dimensionFolders（目录存在性集合）上，扁平 programs 列表表达不了空目录。
 * 4. 许可证核对：本项目 MIT；本文件零第三方代码复制，仅含文档事实。
 * 5. 性能基线：❄️ 冷路径（扫描期一次性映射），不做任何性能优化（18-PARALLEL §7.7）。
 */

/**
 * 维度子目录的已知映射助手（非数据字段本体——程序归属的原始目录名以字符串保存在 {@link Program#dimensionFolder()}）。
 *
 * <p><b>冻结契约</b>：字段已按 {@code docs/18-PARALLEL.md} §3 F2 冻结，供 A/B/C/D/E/F 并行线与关键路径共同消费。
 * 任何字段/语义变更必须走 {@code 18-PARALLEL.md} §3.2 流程（提出方说明 → env-1 统一改 → 契约版本号 +1 → 通知各环境 rebase），
 * 任何一方不得自行增删改（{@code 07-CONSTRAINTS.md} X12）。冷路径数据，只求清晰、不做性能优化（§7.7）。
 */
public enum Dimension {

    /** shaders/ 根目录（维度无关的默认程序；目录名为空串）。 */
    ROOT(""),

    /** 主世界覆盖目录 world0（维度 id 0）。 */
    OVERWORLD("world0"),

    /** 下界覆盖目录 world-1（维度 id -1）。 */
    NETHER("world-1"),

    /** 末地覆盖目录 world1（维度 id 1）；别名 "world_end"、"end" 也映射到本常量（08-TESTING §4 / 04-SPEC §3.1）。 */
    END("world1");

    private final String folderName;

    Dimension(String folderName) {
        this.folderName = folderName;
    }

    /** 规范目录名（ROOT 为空串；END 的别名 world_end / end 不经本字段，见 {@link #fromFolder(String)}）。 */
    public String folderName() {
        return folderName;
    }

    /**
     * 目录名 → 已知维度。识别：空串 → ROOT；world0 → OVERWORLD；world-1 → NETHER；
     * world1 / world_end / end → END；其余（含模组维度的 world&lt;id&gt;）→ {@link Optional#empty()}，
     * 由调用方按原样保留目录名并显式打日志（T11，不静默）。null → empty。
     *
     * @param folderName shaders/ 下的子目录名（不含路径分隔符）
     */
    public static Optional<Dimension> fromFolder(String folderName) {
        if (folderName == null) {
            return Optional.empty();
        }
        if (folderName.equals("world_end") || folderName.equals("end")) {
            return Optional.of(END);
        }
        for (Dimension dimension : values()) {
            if (dimension.folderName.equals(folderName)) {
                return Optional.of(dimension);
            }
        }
        return Optional.empty();
    }
}
