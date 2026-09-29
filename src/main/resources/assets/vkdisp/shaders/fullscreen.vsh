#version 330
#extension GL_ARB_separate_shader_objects : require
// vkdisp 全屏三角形顶点着色器（P0.3 首个可见产物）
// 资源 id：vkdisp:fullscreen → 由 RenderPipeline withVertexShader(Identifier) 解析到本文件。
// 首行必须是 #version（编译器不注入版本行）；#extension 与原版 core 着色器写法一致。

// 本管线无顶点绑定：顶点位置完全由 gl_VertexIndex 推出，draw(3,1,0,0) 不需要顶点缓冲。
// uv 取 (0,0)/(2,0)/(0,2) 三个点构成覆盖整个 NDC 的大三角形，
// 落在视口内的片元恰好对应 uv 的 0..1 可见区（片元着色器据此铺图案）。
layout(location = 0) out vec2 vUv;

void main() {
    vec2 uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(uv * vec2(2, 2) + vec2(-1, -1), 0, 1);
    vUv = uv;
}
