#version 330
#extension GL_ARB_separate_shader_objects : require
// vkdisp 多附件回读片元 —— 把某一个 colortex 显示到主目标（MRT 调试视图）
// 资源 id：vkdisp:mrtview → RenderPipeline.withFragmentShader(Identifier) 解析到本文件。
// 顶点复用 vkdisp:fullscreen_flipv（1-v 翻转在顶点完成，见 P-1f / PipelineApi 的注释）。
//
// ⚠️ 采样源是**我方离屏 colortex**，按 P-1f 的实测约定：离屏 → 主目标这一步**需要** 1-v 翻转。

uniform sampler2D InSampler;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = vec4(texture(InSampler, vUv).rgb, 1.0);
}