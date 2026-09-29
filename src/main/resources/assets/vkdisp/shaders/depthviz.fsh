#version 330
#extension GL_ARB_separate_shader_objects : require
// vkdisp 深度可视化片元着色器（P3 前置：验证「深度附件真的被写入 → 能被采样」）
// 资源 id：vkdisp:depthviz。把深度纹理的 r 分量直接当灰度输出，使深度值变成可量化判读的图像。

uniform sampler2D InSampler;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 fragColor;

void main() {
    // 中间目标 → 中间目标：用原始 vUv（docs/18-PARALLEL.md §10 P-1f 的标定规则）。
    float depth = texture(InSampler, vUv).r;
    fragColor = vec4(vec3(depth), 1.0);
}
