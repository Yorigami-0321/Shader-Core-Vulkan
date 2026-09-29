#version 330
#extension GL_ARB_separate_shader_objects : require
// vkdisp 合成/传递片元着色器（P2 前置：采样离屏渲染目标 → 写主目标）
// 资源 id：vkdisp:blit → RenderPipeline.withFragmentShader(Identifier) 解析到本文件。
// 顶点着色器复用 vkdisp:fullscreen（同一个全屏三角形）。

uniform sampler2D InSampler;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 fragColor;

void main() {
    // ⭐ 实测约定（2026-09-29 三 pass 链标定）：
    //   - vUv.y=0 → NDC y=-1 → 屏幕**顶部**（Vulkan NDC 的 y 向下）；
    //   - **中间目标 → 主目标** 这一步采样必须 V 翻转（本文件），而 **中间目标 → 中间目标** 用原始 vUv（composite.fsh）。
    //   两级同时翻转会让整链上下颠倒（用图案底部方向参考带实测定位）；终态已用「参考带仍在底部 + 四角标颜色」
    //   双重量化验证。约定登记见 docs/18-PARALLEL.md §10 P-1f。
    vec3 sampled = texture(InSampler, vec2(vUv.x, 1.0 - vUv.y)).rgb;
    fragColor = vec4(sampled, 1.0);
}
