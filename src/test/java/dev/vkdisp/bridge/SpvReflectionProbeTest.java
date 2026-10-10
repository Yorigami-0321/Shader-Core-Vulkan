package dev.vkdisp.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * C0 探针守卫：sampler 过滤 + 视图路由用的只读快照口径。
 *
 * <p>🔴 <b>为什么这里不出现任何原版类型</b>（实测于 2026-10-10）：`testCompileClasspath` 里
 * 拿不到 `SpvModule$Reflection$Descriptor` 这一族**二级嵌套类型**（写
 * {@code SpvModule.Reflection.Descriptor} 时 javac 报「程序包SpvModule.Reflection不存在」，
 * 而同一句在 `src/main` 里编译通过）⇒ 探针的「原版对象 → 快照」那一层适配**不可能**在单测里
 * 用假实现覆盖，只能在真机注册期用日志自证（C1 的取证项）。
 * 因此本测试覆盖的是**决策面**：哪些资源算 sampler、名称表、describe 的行形状。
 */
class SpvReflectionProbeTest {

    private static SpvReflectionProbe.Descriptor sampler(String name, int binding, int set) {
        return new SpvReflectionProbe.Descriptor(name, binding, set,
                SpvReflectionProbe.RESOURCE_SAMPLED_IMAGE,
                SpvReflectionProbe.BASE_TYPE_SAMPLED_IMAGE, 0, 1, 0);
    }

    @Test
    @DisplayName("sampled_image(7) 就是 GLSL 的 samplerX；uniform 块与裸 sampler 不算")
    void onlyCombinedImageSamplerCountsAsSampler() {
        SpvReflectionProbe.Descriptor uniformBlock = new SpvReflectionProbe.Descriptor(
                "VkDispBuiltins", 0, 0, SpvReflectionProbe.RESOURCE_UNIFORM_BUFFER, 15, 0, 0, 0);
        SpvReflectionProbe.Descriptor separateSampler = new SpvReflectionProbe.Descriptor(
                "samplerPlain", 5, 1, SpvReflectionProbe.RESOURCE_SEPARATE_SAMPLER,
                SpvReflectionProbe.BASE_TYPE_SAMPLER, 0, 1, 0);
        SpvReflectionProbe.Descriptor shadow = sampler("shadowtex0", 4, 1);

        assertTrue(shadow.combinedSampler());
        assertFalse(uniformBlock.combinedSampler());
        assertFalse(separateSampler.combinedSampler());
        assertTrue(separateSampler.separateSampler(), "裸 sampler 走 separate_sampler(11)");
        assertFalse(uniformBlock.separateImage(), "uniform_buffer 不是图像类资源");

        List<SpvReflectionProbe.Descriptor> all = List.of(uniformBlock, shadow, separateSampler);
        assertEquals(List.of(shadow), SpvReflectionProbe.samplers(all),
                "samplers() 只留合并采样器，供按类型路由视图");
    }

    @Test
    @DisplayName("describe() 每条 sampler 一行且同时给 set/binding（C1 注册期日志的判据形状）")
    void describePrintsOneLinePerSampler() {
        String text = SpvReflectionProbe.describe(List.of(
                sampler("colortex1", 2, 1), sampler("depthtex0", 9, 1)));
        String[] lines = text.split("\n");
        assertEquals(2, lines.length, "两条 sampler ⇒ 两行");
        assertTrue(lines[0].contains("sampler colortex1") && lines[0].contains("set=1")
                        && lines[0].contains("binding=2"),
                "行内必须同时有名字 / set / binding，实测：" + lines[0]);
        assertTrue(lines[1].contains("sampler depthtex0"), "实测：" + lines[1]);
        assertEquals("", SpvReflectionProbe.describe(List.of()), "没有 sampler 就是空串");
    }

    @Test
    @DisplayName("未验数值不猜语义：未知 resourceType/baseType 保留数字（X9）")
    void unknownNumericKeepsRawValue() {
        assertEquals("resource_type_99", SpvReflectionProbe.resourceTypeName(99));
        assertEquals("base_type_42", SpvReflectionProbe.baseTypeName(42));
        assertEquals("sampled_image",
                SpvReflectionProbe.resourceTypeName(SpvReflectionProbe.RESOURCE_SAMPLED_IMAGE));
        assertEquals("sampler", SpvReflectionProbe.baseTypeName(SpvReflectionProbe.BASE_TYPE_SAMPLER));
    }

    @Test
    @DisplayName("🔖 数值口径钉在原版侧：与 SpvUtil.resourceType(UniformType) 的映射一致")
    void numericCaliberMatchesVanillaSpvUtil() {
        // 原版 frontend/shaders/SpvUtil.java（26.3 实测）：COMBINED_IMAGE_SAMPLER -> 7、
        // UNIFORM_BUFFER -> 1；SpvUtil.DESCRIPTOR_TYPES = [1, 2, 6, 7, 10, 11]。
        assertEquals(7, SpvReflectionProbe.RESOURCE_SAMPLED_IMAGE,
                "COMBINED_IMAGE_SAMPLER 的 SPIRV-Cross 值必须由原版映射钉死，不猜");
        assertEquals(1, SpvReflectionProbe.RESOURCE_UNIFORM_BUFFER);
        assertEquals(11, SpvReflectionProbe.RESOURCE_SEPARATE_SAMPLER);
        assertEquals(10, SpvReflectionProbe.RESOURCE_SEPARATE_IMAGE);
        assertEquals(6, SpvReflectionProbe.RESOURCE_STORAGE_IMAGE);
    }
}
