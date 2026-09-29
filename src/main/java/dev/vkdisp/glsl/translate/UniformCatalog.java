package dev.vkdisp.glsl.translate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【参考调研】D 线 OF 内建 uniform 目录 / 04-SPEC §3.2 内建 uniform 表
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/04-SPEC.md §3.2「OF 内建 uniform（必须提供的语义）」表 ——
 *    仓库内文档事实，不受版权保护。外部候选 IrisShaders/glsl-transformer（GPL-3.0 + 例外条款）→
 *    例外条款是否覆盖本项目未核实，一律按禁止处理（07-CONSTRAINTS L12 §1.3 陷阱 2 / X21），
 *    本任务不读其代码、零代码行并入（07-CONSTRAINTS §〇 P1 / L5 / X19）。
 *    许可证：本文件为独立编写的纯 Java 实现，不含任何外部项目代码。
 *    → 能否并入本项目（MIT）：可以 —— 只采纳不受版权保护的事实性信息（uniform 名/类型/语义）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：04-SPEC §3.2 表逐条转写为 23 条 BuiltinUniform（顺序即表顺序）：
 *    gbufferModelView/gbufferProjection/gbufferModelViewInverse/gbufferProjectionInverse/
 *    shadowModelView/shadowProjection（mat4），cameraPosition/sunPosition/moonPosition/
 *    shadowLightPosition（vec3），frameTimeCounter/viewWidth/viewHeight/near/far/wetness/
 *    rainStrength（float），frameCounter/isEyeInWater/worldTime/worldDay（int），
 *    atlasSize/eyeBrightnessSmooth（ivec2）。
 * 2. 备选：无 —— 不引入任何 uniform 反射 / 解析库；纯静态表（冷路径清晰优先）。
 * 3. 我们的差异点：表是**冻结在代码里的显式清单**而不是从文档运行时读取 —— 单测可逐条断言
 *    表与 04-SPEC §3.2 一致；注入顺序固定，保证幂等（跑两遍文本一致）。
 * 4. 许可证核对结论：本项目 MIT；GPL-3.0+例外参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：❄️ 冷路径；静态初始化一次建索引，查询为 O(1) 查表；不做任何预优化
 *    （18-PARALLEL §7.7、T14 达标即停）。
 */
/**
 * OF 内建 uniform 的固定清单（04-SPEC §3.2 的 23 条，顺序 = 文档表顺序）。
 *
 * <p><b>这是 D 线的"完整性"事实来源</b>：{@link UniformInjector} 只按本清单注入缺失项，
 * 单测逐条断言清单与文档一致 —— 少一条就是验收不通过。
 *
 * <p><b>冻结契约（对 D 线内部而言）</b>：变更必须同步 04-SPEC §3.2 与单测；本线独占
 * {@code glsl/translate/}，跨线改动一律不走本类（18-PARALLEL §7.2）。
 */
public final class UniformCatalog {

    /** 表中 uniform 的固定顺序清单（不可变）。 */
    private static final List<BuiltinUniform> UNIFORMS = List.of(
            new BuiltinUniform("gbufferModelView", "mat4", "主视图矩阵", "main view matrix"),
            new BuiltinUniform("gbufferProjection", "mat4", "投影矩阵", "projection matrix"),
            new BuiltinUniform("gbufferModelViewInverse", "mat4", "视图逆矩阵", "inverse view matrix"),
            new BuiltinUniform("gbufferProjectionInverse", "mat4", "投影逆矩阵", "inverse projection matrix"),
            new BuiltinUniform("shadowModelView", "mat4", "光源空间视图矩阵", "light-space view matrix"),
            new BuiltinUniform("shadowProjection", "mat4", "光源空间投影矩阵", "light-space projection matrix"),
            new BuiltinUniform("cameraPosition", "vec3", "摄像机世界坐标", "camera position in world space"),
            new BuiltinUniform("sunPosition", "vec3", "太阳位置", "sun position"),
            new BuiltinUniform("moonPosition", "vec3", "月亮位置", "moon position"),
            new BuiltinUniform("shadowLightPosition", "vec3", "光源方向", "shadow light position"),
            new BuiltinUniform("frameTimeCounter", "float", "秒级累加（用于动画）", "seconds elapsed, for animation"),
            new BuiltinUniform("frameCounter", "int", "帧计数", "frame counter"),
            new BuiltinUniform("viewWidth", "float", "视口宽度", "viewport width"),
            new BuiltinUniform("viewHeight", "float", "视口高度", "viewport height"),
            new BuiltinUniform("near", "float", "近裁剪面", "near clip plane"),
            new BuiltinUniform("far", "float", "远裁剪面", "far clip plane"),
            new BuiltinUniform("wetness", "float", "潮湿程度", "wetness"),
            new BuiltinUniform("rainStrength", "float", "雨强度", "rain strength"),
            new BuiltinUniform("isEyeInWater", "int", "0/1/2(岩浆)/3(粉雪)", "0/1/2(lava)/3(powder snow)"),
            new BuiltinUniform("worldTime", "int", "游戏时间", "world time"),
            new BuiltinUniform("worldDay", "int", "游戏日", "world day"),
            new BuiltinUniform("atlasSize", "ivec2", "方块图集尺寸", "block atlas size"),
            new BuiltinUniform("eyeBrightnessSmooth", "ivec2", "亮度", "smoothed eye brightness"));

    /** 名称到规格的索引（初始化期构建；重名即构建失败 —— 表本身不许有歧义）。 */
    private static final Map<String, BuiltinUniform> BY_NAME = index();

    private UniformCatalog() {}

    /** 按 04-SPEC §3.2 表顺序返回全部 23 条（不可变）。 */
    public static List<BuiltinUniform> uniforms() {
        return UNIFORMS;
    }

    /**
     * 按名字查表。
     *
     * @param name uniform 名；{@code null} 返回 {@code null}
     * @return 对应规格；不是内建 uniform 时返回 {@code null}（可空，调用方必须判空）
     */
    public static BuiltinUniform find(String name) {
        return name == null ? null : BY_NAME.get(name);
    }

    /** 是否为 OF 内建 uniform 名。 */
    public static boolean isBuiltin(String name) {
        return find(name) != null;
    }

    /** 建立名称索引，并在建表时自检重名（表写错要立刻炸，不许静默取后者）。 */
    private static Map<String, BuiltinUniform> index() {
        Map<String, BuiltinUniform> byName = new LinkedHashMap<>();
        for (BuiltinUniform uniform : UNIFORMS) {
            BuiltinUniform previous = byName.put(uniform.name(), uniform);
            if (previous != null) {
                throw new IllegalStateException("vkdisp: OF 内建 uniform 表存在重名 " + uniform.name());
            }
        }
        return Map.copyOf(byName);
    }
}
