#version 330
#extension GL_ARB_separate_shader_objects : require
// vkdisp 合成/传递片元着色器（P2 前置：采样离屏渲染目标 → 写主目标）
// 资源 id：vkdisp:blit → RenderPipeline.withFragmentShader(Identifier) 解析到本文件。
// 顶点着色器复用 vkdisp:fullscreen（同一个全屏三角形）。

uniform sampler2D InSampler;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 fragColor;

void main() {
    // ⚠️ 实测约定（2026-09-29 标定）：vUv.y=0 → NDC y=-1 → 屏幕**顶部**（Vulkan NDC y 向下）；
    // 而 NativeImage 行 0 对应采样坐标 v=1（纹理原点在下）→ 采样输入纹理/渲染目标时必须 V 翻转，
    // 否则画面上下颠倒。翻转后与本 pass 的目标方向一致（用「底部橙色带」实测验证：带仍在底部）。
    // 详见 docs/18-PARALLEL.md §10 P-1f 与 CHANGE_LOG 的量化记录。
    vec3 sampled = texture(InSampler, vec2(vUv.x, 1.0 - vUv.y)).rgb;
    fragColor = vec4(sampled, 1.0);
}
