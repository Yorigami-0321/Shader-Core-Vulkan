package dev.vkdisp.pipeline.model;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 后处理 pass（deferred* / composite* / final）的转译终稿契约 —— 纯解析，无原版类型。
 *
 * <p><b>与 {@link PackTerrainProgram} 的分工</b>：地形契约要额外管 varying 供值三档
 * （顶点适配层是地形专属问题）；后处理 pass 是全屏三角形，没有包自己的顶点输入要适配，
 * 契约只剩两件事：<b>哪些 colortex 槽被写</b>（附件选择与重编号的输入）与
 * <b>声明了哪些 sampler</b>（绑定组必须逐条覆盖，STRICT_VALIDATION 实测）。
 *
 * <p>🔖 <b>location 的口径（h44 已钉死）</b>：转译终稿里 {@code layout(location = N) out}
 * 的 N = <b>colortex 槽号</b>（{@code DrawBuffersSlotAdapter} 兑现 DRAWBUFFERS 之后的形态），
 * 不是附件下标。附件下标由 {@link PostOutputRenumber} 在计划期换算。
 */
public record PostPassContract(
        String qualifiedName,
        List<Integer> outputSlots,
        List<String> samplerNames,
        List<FragmentInput> inputs,
        List<Integer> mipSlots,
        int outputCount) {

    /** 片元声明的输入 varying（后处理 VS 适配层按此逐 location 供值）。 */
    public record FragmentInput(int location, String type, String name) {}

    public PostPassContract {
        outputSlots = outputSlots == null ? List.of() : List.copyOf(outputSlots);
        samplerNames = samplerNames == null ? List.of() : List.copyOf(samplerNames);
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        mipSlots = mipSlots == null ? List.of() : List.copyOf(mipSlots);
    }

    /** {@code layout(location = N) out ...;}（N 允许带空格）。 */
    private static final Pattern OUT_DECL = Pattern.compile(
            "layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s+out\\s+\\S+\\s+([A-Za-z_]\\w*)\\s*;");

    /** {@code layout(location = N) in <type> <name>;}（后处理片元的屏幕空间 varying）。 */
    private static final Pattern IN_DECL = Pattern.compile(
            "layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s+in\\s+([A-Za-z_]\\w*)\\s+([A-Za-z_]\\w*)\\s*;");

    /** OF const：{@code const bool colortex0MipmapEnabled = true;}（GAP-017 的判据来源）。 */
    private static final Pattern MIP_CONST = Pattern.compile(
            "colortex(\\d+)MipmapEnabled\\s*=\\s*true");

    /** {@code uniform samplerXXX name;}（含数组声明形态不认 —— 转译终稿里不出现，X9 不猜）。 */
    private static final Pattern SAMPLER_DECL = Pattern.compile(
            "\\buniform\\s+(sampler2D|sampler2DShadow|sampler3D|samplerCube|sampler2DArray"
                    + "|sampler2DArrayShadow|isampler2D|usampler2D)\\s+([A-Za-z_]\\w*)\\s*;");

    /**
     * 解析转译终稿。
     *
     * @param qualifiedName 程序限定名（如 {@code world0/composite3}）
     * @param source        转译终稿文本
     * @throws IllegalArgumentException 没有任何片元输出（后处理 pass 必须写至少一个附件）
     */
    public static PostPassContract parse(String qualifiedName, String source) {
        List<Integer> slots = new ArrayList<>();
        Matcher out = OUT_DECL.matcher(source == null ? "" : source);
        while (out.find()) {
            int slot = Integer.parseInt(out.group(1));
            if (!slots.contains(slot)) {
                slots.add(slot);
            }
        }
        if (slots.isEmpty()) {
            throw new IllegalArgumentException(
                    "vkdisp: post pass '" + qualifiedName + "' 没有任何 layout(location=N) out 声明"
                            + " —— 后处理步必须写至少一个附件（缺 DRAWBUFFERS 标记或转译没兑现）");
        }
        slots.sort(Integer::compareTo);
        List<FragmentInput> inputs = new ArrayList<>();
        Matcher in = IN_DECL.matcher(source == null ? "" : source);
        while (in.find()) {
            int loc = Integer.parseInt(in.group(1));
            boolean dup = inputs.stream().anyMatch(x -> x.location() == loc || x.name().equals(in.group(3)));
            if (!dup) {
                inputs.add(new FragmentInput(loc, in.group(2), in.group(3)));
            }
        }
        inputs.sort((a, b) -> Integer.compare(a.location(), b.location()));
        List<Integer> mipSlots = new ArrayList<>();
        Matcher mip = MIP_CONST.matcher(source == null ? "" : source);
        while (mip.find()) {
            int slot = Integer.parseInt(mip.group(1));
            if (!mipSlots.contains(slot)) {
                mipSlots.add(slot);
            }
        }
        mipSlots.sort(Integer::compareTo);
        List<String> samplers = new ArrayList<>();
        Matcher sampler = SAMPLER_DECL.matcher(source == null ? "" : source);
        while (sampler.find()) {
            if (!samplers.contains(sampler.group(2))) {
                samplers.add(sampler.group(2));
            }
        }
        return new PostPassContract(qualifiedName, slots, samplers, inputs, mipSlots,
                slots.get(slots.size() - 1) + 1);
    }

    /** 最大的声明槽号（colortex 池尺寸按它扩）。 */
    public int maxSlot() {
        return outputSlots.get(outputSlots.size() - 1);
    }

    /** 该 pass 声明 `const bool colortexNMipmapEnabled=true` 的槽集合（升序）。 */
    public java.util.List<Integer> mipEnabledSlots() {
        return mipSlots;
    }
}
