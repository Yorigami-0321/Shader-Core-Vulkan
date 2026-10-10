package dev.vkdisp.bridge;

import com.mojang.renderpearl.backend.api.SpvModule;
import java.util.ArrayList;
import java.util.List;

/**
 * 【参考调研】C0 · 原版 SPIR-V 反射面只读探针（19 §4.2 的「权威臂」核实载体）
 * 0. 合规：参考对象 = 原版 26.3 自带的 `com.mojang.renderpearl.backend.api.SpvModule` 与其
 *    `frontend/shaders/SpvUtil`（我们**编译所依赖的原版 API**，读它 = 读接口；零代码并入，
 *    本项目 MIT 不受影响）。数值口径的出处逐条写在常量注释里，未核实的一律标「未验」不作依据。
 * 1. 职责：把 `SpvModule.reflect()` 的 descriptor 集合抽成**我方自有不可变记录**，
 *    使 `pack/`、`pipeline/` 不必 import 原版类型（T5）。
 * 2. 差异点：原版反射面是**可变的**（`Descriptor.binding(int)` / `descriptorSetIndex(int)`）——
 *    本探针**只读不写**，绝不改 SPIR-V 的 binding/set（改了会撞原版 `PipelineBuilder` 的布局生成）。
 * 3. 非显然约束：`getReflectionInfoIfAvailable()` 允许返回 null（反射信息不可用时不抛），
 *    而 `reflect()` 会抛 `ShaderCompileException` ⇒ 本类取前者，null ⇒ 空表 + 由调用方出诊断。
 * 4. 🔴 C0 的**结论**（2026-10-10 `javap -p` 实测，命令与输出见 `19` §4.2 的 C0 登记）：
 *    反射面**足以按类型路由 sampler**（`resourceType()` + `baseType()` 已验），
 *    但 `Type.dimensions()` 的取值**没有可用的公开常量表**（lwjgl-spvc 3.4.1 的 `Spvc`/`Spv`
 *    两个类里都搜不到 `*DIM*` 常量，原版侧也未暴露枚举）⇒ **不许把 `dimensions()` 当唯一路由依据**，
 *    3D/Cube 的判定必须以包的 `uniform sampler3D/samplerCube` 声明类型为准（`SamplerDimensionPlan` 已是这条路）。
 * 5. 性能：❄️ 冷路径（注册期一次）；线性拷贝，无缓存、无优化。
 */
public final class SpvReflectionProbe {

    /**
     * `Descriptor.resourceType()` 的取值 = SPIRV-Cross 的 `spvc_resource_type`。
     * 数值出处（两处独立一致）：lwjgl-spvc 3.4.1 `org.lwjgl.util.spvc.Spvc.SPVC_RESOURCE_TYPE_*`
     * 常量，与原版 `SpvUtil.resourceType(UniformType)` 把 `COMBINED_IMAGE_SAMPLER → 7`、
     * `UNIFORM_BUFFER → 1` 的映射；原版 `SpvUtil.DESCRIPTOR_TYPES = [1, 2, 6, 7, 10, 11]`
     * 就是「被当成描述符的资源类型」集合。
     */
    public static final int RESOURCE_UNIFORM_BUFFER = 1;
    public static final int RESOURCE_STORAGE_BUFFER = 2;
    public static final int RESOURCE_SUBPASS_INPUT = 5;
    public static final int RESOURCE_STORAGE_IMAGE = 6;

    /** GLSL 的 `sampler2D` / `sampler3D` / `samplerCube` / `sampler2DShadow`（合并采样器）。 */
    public static final int RESOURCE_SAMPLED_IMAGE = 7;

    public static final int RESOURCE_SEPARATE_IMAGE = 10;

    /** GLSL 的裸 `sampler`（`sampler2D` 与 `sampler` 分离声明的形态）。 */
    public static final int RESOURCE_SEPARATE_SAMPLER = 11;

    /**
     * `Type.baseType()` 的取值 = SPIRV-Cross 的基类型枚举。
     * 数值出处（已验）：原版 `SpvUtil.baseTypeString(int)` 的 switch 分支逐条列出；本类只取
     * sampler 路由用得上的三个，其余保留原值不解释。
     */
    public static final int BASE_TYPE_IMAGE = 16;
    public static final int BASE_TYPE_SAMPLED_IMAGE = 17;
    public static final int BASE_TYPE_SAMPLER = 18;

    private SpvReflectionProbe() {
    }

    /**
     * 一条反射描述符的**只读快照**（不持有任何原版对象）。
     *
     * @param name           SPIR-V 里的资源名（即 GLSL 的 uniform 名）
     * @param binding        编译后的 binding 号
     * @param descriptorSet  编译后的 set 号
     * @param resourceType   见 {@code RESOURCE_*} 常量表
     * @param baseType       见 {@code BASE_TYPE_*} 常量表
     * @param dimensions     镜像维度 —— 🔴 取值口径未验（见类注释 4），只做日志展示，不做判据
     * @param vectorSize     向量分量数
     * @param arrayDimensions 数组维数（`sampler2D x[4]` 这类）
     */
    public record Descriptor(
            String name,
            int binding,
            int descriptorSet,
            int resourceType,
            int baseType,
            int dimensions,
            int vectorSize,
            int arrayDimensions) {

        /** 是否为合并采样器（GLSL `samplerX` —— 我方 bind group 里要的是 GpuTextureView + GpuSampler）。 */
        public boolean combinedSampler() {
            return resourceType == RESOURCE_SAMPLED_IMAGE;
        }

        /** 是否为分离图像（`textureX` / 单独声明的 image）。 */
        public boolean separateImage() {
            return resourceType == RESOURCE_SEPARATE_IMAGE || resourceType == RESOURCE_STORAGE_IMAGE;
        }

        /** 是否为裸采样器（`sampler`）。 */
        public boolean separateSampler() {
            return resourceType == RESOURCE_SEPARATE_SAMPLER;
        }
    }

    /**
     * 抽取一个模块的全部 descriptor（跨 set）。
     *
     * @return 不可变列表；反射信息不可用时为**空列表**（调用方据此出诊断，本类不静默也不抛）
     */
    public static List<Descriptor> descriptors(SpvModule module) {
        SpvModule.Reflection reflection = module.getReflectionInfoIfAvailable();
        if (reflection == null) {
            return List.of();
        }
        List<Descriptor> out = new ArrayList<>();
        for (SpvModule.Reflection.Descriptor descriptor : reflection.descriptors()) {
            SpvModule.Reflection.Type type = descriptor.type();
            out.add(new Descriptor(
                    descriptor.name(),
                    descriptor.binding(),
                    descriptor.descriptorSetIndex(),
                    descriptor.resourceType(),
                    type.baseType(),
                    type.dimensions(),
                    type.vectorSize(),
                    type.arrayDimensions()));
        }
        return List.copyOf(out);
    }

    /** 按资源类型过滤（对应原版 `Reflection.descriptors(int)`，但过滤的是我方快照）。 */
    public static List<Descriptor> samplers(List<Descriptor> descriptors) {
        List<Descriptor> out = new ArrayList<>();
        for (Descriptor descriptor : descriptors) {
            if (descriptor.combinedSampler()) {
                out.add(descriptor);
            }
        }
        return List.copyOf(out);
    }

    /** 资源类型名的可读写法（日志用；未知值保留数字，不猜语义）。 */
    public static String resourceTypeName(int resourceType) {
        return switch (resourceType) {
            case RESOURCE_UNIFORM_BUFFER -> "uniform_buffer";
            case RESOURCE_STORAGE_BUFFER -> "storage_buffer";
            case RESOURCE_SUBPASS_INPUT -> "subpass_input";
            case RESOURCE_STORAGE_IMAGE -> "storage_image";
            case RESOURCE_SAMPLED_IMAGE -> "sampled_image";
            case RESOURCE_SEPARATE_IMAGE -> "separate_image";
            case RESOURCE_SEPARATE_SAMPLER -> "separate_sampler";
            default -> "resource_type_" + resourceType;
        };
    }

    /** 基类型名的可读写法（数值出处 = 原版 `SpvUtil.baseTypeString`，见类注释）。 */
    public static String baseTypeName(int baseType) {
        return switch (baseType) {
            case 1 -> "void";
            case 2 -> "bool";
            case 7 -> "int32";
            case 8 -> "uint32";
            case 13 -> "fp32";
            case 14 -> "fp64";
            case 15 -> "struct";
            case BASE_TYPE_IMAGE -> "image";
            case BASE_TYPE_SAMPLED_IMAGE -> "sampled_image";
            case BASE_TYPE_SAMPLER -> "sampler";
            default -> "base_type_" + baseType;
        };
    }

    /** 逐条一行的摘要（每条 sampler 一行，供注册期日志与 C3 一致性报告引用）。 */
    public static String describe(List<Descriptor> descriptors) {
        StringBuilder sb = new StringBuilder();
        for (Descriptor d : samplers(descriptors)) {
            sb.append("    sampler ").append(d.name())
                    .append(" set=").append(d.descriptorSet())
                    .append(" binding=").append(d.binding())
                    .append(" resource=").append(resourceTypeName(d.resourceType()))
                    .append(" baseType=").append(baseTypeName(d.baseType()))
                    .append(" arrayDimensions=").append(d.arrayDimensions())
                    .append('\n');
        }
        return sb.toString();
    }
}
