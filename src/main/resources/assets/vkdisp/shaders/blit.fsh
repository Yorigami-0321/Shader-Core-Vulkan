#version 330
#extension GL_ARB_separate_shader_objects : require
// vkdisp 传递片元着色器：**原样拷贝**（采样输入纹理 → 写目标，不做任何坐标变换）
// 资源 id：vkdisp:blit → RenderPipeline.withFragmentShader(Identifier) 解析到本文件。
// 顶点着色器复用 vkdisp:fullscreen（原始 vUv，不翻转）。
//
// 本文件唯一的消费者是 `FrameApi.generateMipPyramids`（mip L-1 → mip L），那是**同一张图的
// 两个层级之间**的拷贝 ⇒ 取向必须逐字相同。
// 🔴 这里不得加 `1.0 - vUv.y`：那次 V 翻转补偿的是「中间目标 → 主目标」，它住在
//    `fullscreen_flipv.vsh`（口径见其头注 P-1f）。带进金字塔的后果是每生成一级镜像一次
//    ⇒ 奇数级上下颠倒，而 BSL 的 bloom 把 mip 1..7 求和 ⇒ 场景的镜像副本被加回画面
//    （真机「倒影/虚影」，登记见 docs/13-GAP-REGISTRY.md GAP-031）。
//    判据可数：分带读数下 m1 的 SKY_BAND 必须 < TERRAIN_BAND（与 mip0 同大小关系）。

uniform sampler2D InSampler;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 fragColor;

void main() {
    vec3 sampled = texture(InSampler, vUv).rgb;
    fragColor = vec4(sampled, 1.0);
}
