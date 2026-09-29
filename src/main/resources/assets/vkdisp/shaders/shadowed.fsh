#version 330
#extension GL_ARB_separate_shader_objects : require
// vkdisp 阴影采样片元着色器（P3.3：世界坐标 → 光空间 → 采样阴影贴图深度 → 明暗区分）
// 资源 id：vkdisp:shadowed。受阴影者变暗、受光者保持原色 —— 「阴影真的生效」的可判定证据。

uniform sampler2D InSampler;   // 阴影贴图深度（offscreen0 的 depth 视图）

layout(std140) uniform LightMatrix {
    mat4 uLight;
};

layout(location = 0) in vec4 vColor;
layout(location = 1) in vec3 vWorldPos;
layout(location = 0) out vec4 fragColor;

void main() {
    // 世界坐标 → 光空间裁剪坐标（与阴影贴图渲染用的是同一个 uLight）。
    vec4 lightClip = uLight * vec4(vWorldPos, 1.0);
    // 正交投影范围 x∈[-1.2,1.2] y∈[-1,1] → 纹理 uv（0..1）。
    // y 方向：光空间投影的 y-up 与纹理行序的对应关系依赖栈约定，此处按 y-up 映射，
    // 若实测阴影偏移/翻转则翻转 v（见 CHANGE_LOG 的迭代记录）。
    vec2 suv = vec2(lightClip.x / 2.4 + 0.5, lightClip.y / 2.0 + 0.5);
    float stored = texture(InSampler, suv).r;
    float myDepth = lightClip.z;             // zZeroToOne：已在 [0,1]
    float bias = 0.003;
    bool inShadow = (myDepth - bias) > stored;

    vec3 color = inShadow ? vColor.rgb * 0.35 : vColor.rgb;
    fragColor = vec4(color, 1.0);
}
