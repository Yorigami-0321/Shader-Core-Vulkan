package dev.vkdisp.glsl.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vkdisp.pipeline.model.PostPassContract;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 后处理 VS 适配层守卫（h46 实测：location 重叠会砸掉**全部** required 管线）。 */
class PackPostVertexAdapterTest {

    @Test
    @DisplayName("🔴 契约占了 location 0 时不得再输出 vUv（overlapping location = 全链编译失败）")
    void noVUvWhenContractOwnsLocationZero() {
        PackPostVertexAdapter.Result r = PackPostVertexAdapter.generate(List.of(
                new PostPassContract.FragmentInput(0, "vec2", "texCoord"),
                new PostPassContract.FragmentInput(1, "vec3", "sunVec"),
                new PostPassContract.FragmentInput(3, "vec3", "eastVec")));
        long loc0 = r.glsl().lines().filter(l -> l.contains("layout(location = 0)")).count();
        assertEquals(1, loc0, () -> "location 0 只许出现一次；实际源:\n" + r.glsl());
        assertTrue(r.glsl().contains("texCoord = uv;"), "屏幕语义的 texCoord 必须接 uv");
        assertTrue(r.glsl().contains("eastVec = vec3(0.0)"));
        assertEquals(List.of("sunVec", "eastVec"), r.zeroSupplied(),
                "零值供值必须逐条登记（不假装供对了值）");
    }

    @Test
    @DisplayName("🔖 空契约（兜底槽）= 只有 vUv 的全屏三角形")
    void emptyContractKeepsVUvPassthrough() {
        PackPostVertexAdapter.Result r = PackPostVertexAdapter.generate(List.of());
        assertTrue(r.glsl().contains("layout(location = 0) out vec2 vUv;"));
        assertTrue(r.glsl().contains("vUv = uv;"));
        assertFalse(r.glsl().contains("overlapping"), "模板自身不得重复 location");
        assertEquals(0, r.zeroSupplied().size());
    }

    @Test
    @DisplayName("🔴 不认识的类型直接抛（不许猜一个像的）")
    void unknownTypeIsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> PackPostVertexAdapter.generate(List.of(
                        new PostPassContract.FragmentInput(0, "mat3", "m"))));
    }
}
